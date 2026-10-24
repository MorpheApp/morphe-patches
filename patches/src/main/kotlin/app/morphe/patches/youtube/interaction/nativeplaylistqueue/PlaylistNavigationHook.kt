/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patches.youtube.layout.playlistautoplay.NavigationIntentEnumFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** Resolve the shared native navigation owner for controls and the playback-engine bridge. */
internal fun installPlaylistNavigation(
    context: BytecodePatchContext,
    controllerType: String
) = with(context) {
    val enumType = NavigationIntentEnumFingerprint.matchSingle().originalClassDef.type
    val wrapperType = playlistNavigationRequestFingerprint(enumType).matchSingle().originalClassDef.type

    // The playlist controls are created with the playlist's own navigator.
    val creation = playlistControlsCreationFingerprint(controllerType).matchSingle()
    val navigatorType = creation.instructionMatches[4].instruction.getReference<TypeReference>()!!.type
    val controlsField = creation.instructionMatches.last().instruction.getReference<FieldReference>()!!

    // 21.28+ declares the controls as an interface with two implementations.
    val controlsImplementations = if (AccessFlags.INTERFACE.isSet(classDefBy(controlsField.type).accessFlags)) {
        playlistControlsImplementationFingerprint(navigatorType, controlsField.type).matchAll()
    } else {
        listOf(playlistControlsConstructorFingerprint(navigatorType, controlsField.type).matchSingle())
    }

    check(controlsImplementations.isNotEmpty()) { "Native playlist queue: playlist controls are missing" }

    controlsImplementations.forEach { constructor ->
        val navigatorField = constructor.instructionMatches.single().instruction.getReference<FieldReference>()!!
        val controls = mutableClassDefBy(constructor.originalClassDef.type)

        controls.interfaces.add(NAVIGATOR_OWNER_INTERFACE)
        controls.addBridgeMethod("patch_getNavigator", emptyList(), "Ljava/lang/Object;", 1, """
            iget-object v0, p0, $navigatorField
            return-object v0
        """)
    }

    mutableClassDefBy(controllerType).addBridgeMethod("patch_getNavigator", emptyList(), "Ljava/lang/Object;", 2, """
        iget-object v0, p0, $controlsField
        instance-of v1, v0, $NAVIGATOR_OWNER_INTERFACE
        if-eqz v1, :missing
        check-cast v0, $NAVIGATOR_OWNER_INTERFACE
        invoke-interface {v0}, $NAVIGATOR_OWNER_INTERFACE->patch_getNavigator()Ljava/lang/Object;
        move-result-object v0
        return-object v0

        :missing
        const/4 v0, 0x0
        return-object v0
    """)

    installPlaylistNotification(context, wrapperType, enumType)
    installPlaylistRepeat(context, navigatorType)
    installPlaylistPlayerControls(context, navigatorType, enumType, wrapperType)
}
