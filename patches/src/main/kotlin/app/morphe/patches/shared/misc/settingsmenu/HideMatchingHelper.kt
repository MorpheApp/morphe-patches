/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.misc.settingsmenu

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val PREFERENCE_GROUP_CLASS = "Landroidx/preference/PreferenceGroup;"
private const val PREFERENCE_CLASS = "Landroidx/preference/Preference;"
private const val LOGGER_CLASS = "Lapp/morphe/extension/shared/patches/BaseSettingsMenuFilter;"

/**
 * Smali descriptor of the helper method injected by [injectHideMatchingHelper].
 */
internal const val HIDE_MATCHING_METHOD =
    "$PREFERENCE_GROUP_CLASS->patch_hideMatching([Ljava/lang/String;)V"

/**
 * Hooks Preference.setTitle to catch async title assignments that arrive after onCreatePreferences.
 * Must be paired with [injectHideMatchingHelper] because R8 sometimes inlines setTitle
 * into the Preference constructor for XML-inflated titles, skipping the setter entirely.
 */
internal fun BytecodePatchContext.injectSettingsMenuFilterHook(extensionClass: String) {
    val setVisible = PreferenceSetVisibleFingerprint.method
    val visibleField = PreferenceSetVisibleFingerprint.let {
        it.instructionMatches.first().instruction.getReference<FieldReference>()!!
    }

    PreferenceSetTitleFingerprint.method.apply {
        val firstInstruction = implementation!!.instructions.first()

        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $extensionClass->getNeedles()[Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original

                invoke-static { p1, v0 }, $LOGGER_CLASS->equalsAny(Ljava/lang/CharSequence;[Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :original

                iget-boolean v0, p0, $visibleField
                if-eqz v0, :original

                invoke-static { p1 }, $LOGGER_CLASS->logHidden(Ljava/lang/CharSequence;)V

                const/4 v0, 0x0
                invoke-virtual { p0, v0 }, $setVisible
            """,
            ExternalLabel("original", firstInstruction)
        )
    }
}

/**
 * Injects a helper on PreferenceGroup that recursively walks the backing list, reads CharSequence
 * fields directly, calls setVisible(false) on matches, and self-hides when every child ends up
 * invisible (so an empty category header disappears too). Field reads (not getters) are required
 * because R8 can inline setTitle into the Preference constructor for XML-inflated titles. Private
 * CharSequence fields are widened to public first: a subclass method cannot access private parent
 * fields, and our helper lives on PreferenceGroup.
 */
internal fun BytecodePatchContext.injectHideMatchingHelper() {
    val listField = PreferenceGroupGetPreferenceFingerprint.let {
        it.instructionMatches.last().instruction.getReference<FieldReference>()!!
    }
    val setVisible = PreferenceSetVisibleFingerprint.method
    // mVisible: guards against duplicate work + drives the self-hide-when-all-children-invisible rule.
    val visibleField = PreferenceSetVisibleFingerprint.let {
        it.instructionMatches.first().instruction.getReference<FieldReference>()!!
    }

    val preferenceClass = mutableClassDefByOrNull(PREFERENCE_CLASS)
        ?: throw PatchException("Class not found in target: $PREFERENCE_CLASS")
    val textFields = preferenceClass.fields.filter { it.type == "Ljava/lang/CharSequence;" }
    if (textFields.isEmpty()) {
        throw PatchException("No CharSequence fields on $PREFERENCE_CLASS - obfuscation changed")
    }
    textFields.forEach { field ->
        if (AccessFlags.PRIVATE.isSet(field.accessFlags)) {
            val flags = (field.accessFlags and AccessFlags.PRIVATE.value.inv()) or AccessFlags.PUBLIC.value
            field.setAccessFlags(flags)
        }
    }

    val fieldChecks = textFields.joinToString("\n") { field ->
        """
            iget-object v3, v2, $PREFERENCE_CLASS->${field.name}:${field.type}
            invoke-static { v3, p1 }, $LOGGER_CLASS->equalsAny(Ljava/lang/CharSequence;[Ljava/lang/String;)Z
            move-result v4
            if-nez v4, :match
        """.trimIndent()
    }

    PreferenceGroupGetPreferenceFingerprint.classDef.apply {
        val helper = ImmutableMethod(
            type,
            "patch_hideMatching",
            listOf(ImmutableMethodParameter("[Ljava/lang/String;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(10),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    if-eqz p1, :done
                    array-length v0, p1
                    if-eqz v0, :done

                    iget-object v0, p0, $listField
                    invoke-interface { v0 }, Ljava/util/List;->size()I
                    move-result v1

                    if-eqz v1, :done

                    const/4 v5, 0x0
                    add-int/lit8 v6, v1, -0x1

                    :outer
                    if-ltz v6, :after_loop

                    invoke-interface { v0, v6 }, Ljava/util/List;->get(I)Ljava/lang/Object;
                    move-result-object v2
                    check-cast v2, $PREFERENCE_CLASS

                    $fieldChecks

                    goto :recurse

                    :match
                    # Log + hide only if this pref is currently visible; either way, fall through
                    # to :recurse so nested groups still get processed.
                    iget-boolean v4, v2, $visibleField
                    if-eqz v4, :recurse
                    invoke-static { v3 }, $LOGGER_CLASS->logHidden(Ljava/lang/CharSequence;)V
                    const/4 v4, 0x0
                    invoke-virtual { v2, v4 }, $setVisible

                    :recurse
                    instance-of v4, v2, $PREFERENCE_GROUP_CLASS
                    if-eqz v4, :count
                    check-cast v2, $PREFERENCE_GROUP_CLASS
                    invoke-virtual { v2, p1 }, $HIDE_MATCHING_METHOD

                    :count
                    # Single counting point: pref counts as hidden if it ended up invisible,
                    # whether we hid it, recursion hid it, or the app had it invisible already.
                    iget-boolean v4, v2, $visibleField
                    if-nez v4, :next
                    add-int/lit8 v5, v5, 0x1

                    :next
                    add-int/lit8 v6, v6, -0x1
                    goto :outer

                    :after_loop
                    # All direct children invisible? Hide self so an empty category header disappears.
                    if-ne v5, v1, :done
                    const/4 v3, 0x0
                    invoke-virtual { p0, v3 }, $setVisible

                    :done
                    return-void
                """
            )
        }
        methods.add(helper)
    }
}
