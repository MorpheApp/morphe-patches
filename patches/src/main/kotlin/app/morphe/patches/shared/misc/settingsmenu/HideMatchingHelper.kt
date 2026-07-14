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
internal const val SETTINGS_MENU_FILTER_CLASS = "Lapp/morphe/extension/shared/patches/BaseSettingsMenuFilter;"

/**
 * Smali descriptor of the helper method injected by [injectHideMatchingHelper].
 */
internal const val HIDE_MATCHING_METHOD =
    "$PREFERENCE_GROUP_CLASS->patch_hideMatching([Ljava/lang/String;Ljava/lang/CharSequence;)V"

/**
 * Catches async title assignments that skip the tree walk.
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
                const/4 v0, 0x0
                invoke-static { v0, p1 }, $SETTINGS_MENU_FILTER_CLASS->capture(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)V

                invoke-static { }, $extensionClass->getNeedles()[Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original

                invoke-static { p1, v0 }, $SETTINGS_MENU_FILTER_CLASS->equalsAny(Ljava/lang/CharSequence;[Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :original

                iget-boolean v0, p0, $visibleField
                if-eqz v0, :original

                invoke-static { p1 }, $SETTINGS_MENU_FILTER_CLASS->logHidden(Ljava/lang/CharSequence;)V

                const/4 v0, 0x0
                invoke-virtual { p0, v0 }, $setVisible
            """,
            ExternalLabel("original", firstInstruction)
        )
    }
}

/**
 * Field reads are required because R8 inlines setTitle into the constructor
 * for XML-inflated titles, and private CharSequence fields are widened first because a
 * subclass method on PreferenceGroup cannot access them otherwise.
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

    // Pick title = first non-null CharSequence field on this Preference.
    // R8 can reorder fields so we cannot rely on mTitle being declared first.
    val titlePickPass = textFields.mapIndexed { i, field ->
        """
            iget-object v3, v2, $PREFERENCE_CLASS->${field.name}:${field.type}
            if-eqz v3, :skip_title_$i
            move-object v8, v3
            goto :title_picked
            :skip_title_$i
        """.trimIndent()
    }.joinToString("\n")

    val hideFieldChecks = textFields.joinToString("\n") { field ->
        """
            iget-object v3, v2, $PREFERENCE_CLASS->${field.name}:${field.type}
            invoke-static { v3, p1 }, $SETTINGS_MENU_FILTER_CLASS->equalsAny(Ljava/lang/CharSequence;[Ljava/lang/String;)Z
            move-result v4
            if-nez v4, :match
        """.trimIndent()
    }

    PreferenceGroupGetPreferenceFingerprint.classDef.apply {
        val helper = ImmutableMethod(
            type,
            "patch_hideMatching",
            listOf(
                ImmutableMethodParameter("[Ljava/lang/String;", null, null),
                ImmutableMethodParameter("Ljava/lang/CharSequence;", null, null)
            ),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(12),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    if-eqz p1, :done
                    iget-object v0, p0, $listField
                    invoke-interface { v0 }, Ljava/util/List;->size()I
                    move-result v1

                    if-eqz v1, :done

                    array-length v7, p1

                    const/4 v5, 0x0
                    const/4 v6, 0x0

                    :outer
                    if-ge v6, v1, :after_loop

                    invoke-interface { v0, v6 }, Ljava/util/List;->get(I)Ljava/lang/Object;
                    move-result-object v2
                    check-cast v2, $PREFERENCE_CLASS

                    # Pass 1: pick title for capture + recursion parent context.
                    const/4 v8, 0x0
                    $titlePickPass

                    :title_picked
                    if-eqz v8, :hide_pass
                    invoke-static { p2, v8 }, $SETTINGS_MENU_FILTER_CLASS->capture(Ljava/lang/CharSequence;Ljava/lang/CharSequence;)V

                    :hide_pass
                    # Pass 2: hide only when needles are present.
                    if-eqz v7, :recurse

                    $hideFieldChecks

                    goto :recurse

                    :match
                    # Log + hide only if this pref is currently visible; goto :recurse so nested
                    # groups still get processed for capture and their own hide pass.
                    iget-boolean v4, v2, $visibleField
                    if-eqz v4, :recurse
                    invoke-static { v3 }, $SETTINGS_MENU_FILTER_CLASS->logHidden(Ljava/lang/CharSequence;)V
                    const/4 v4, 0x0
                    invoke-virtual { v2, v4 }, $setVisible
                    goto :recurse

                    :recurse
                    instance-of v4, v2, $PREFERENCE_GROUP_CLASS
                    if-eqz v4, :count
                    check-cast v2, $PREFERENCE_GROUP_CLASS
                    invoke-virtual { v2, p1, v8 }, $HIDE_MATCHING_METHOD

                    :count
                    # Counting + self-hide are meaningful only when we are actually hiding.
                    if-eqz v7, :next
                    iget-boolean v4, v2, $visibleField
                    if-nez v4, :next
                    add-int/lit8 v5, v5, 0x1

                    :next
                    add-int/lit8 v6, v6, 0x1
                    goto :outer

                    :after_loop
                    if-eqz v7, :done
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
