/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3446
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.playlisttracktap

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/PlaylistTrackTapPatch;"
private const val EXTENSION_COMMAND_INTERFACE = $$"Lapp/morphe/extension/music/patches/PlaylistTrackTapPatch$Command;"

@Suppress("unused")
val playlistTrackTapPatch = bytecodePatch(
    name = "Playlist track tap action",
    description = "Adds an option to play only the tapped track of a playlist or album, " +
            "or to add it to the queue, instead of replacing the queue with the whole playlist.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            ListPreference("morphe_music_playlist_track_tap_action")
        )

        // region Resolve the obfuscated proto classes.

        val builderMatches = WatchEndpointCommandBuilderFingerprint.instructionMatches

        val watchEndpointDefaultInstanceField = builderMatches[0].getFieldAccessed()
        val watchEndpointClass = watchEndpointDefaultInstanceField.definingClass

        // GeneratedMessageLite and its builder, whose method names are not obfuscated.
        val createBuilderMethod = builderMatches[1].getMethodCalled()
        val messageClass = createBuilderMethod.definingClass
        val messageBuilderClass = createBuilderMethod.returnType

        val playlistIdField = builderMatches[3].getFieldAccessed()
        val presenceBitsField = builderMatches[5].getFieldAccessed()
        val playlistIndexField = builderMatches[6].getFieldAccessed()

        val commandDefaultInstanceField = builderMatches[7].getFieldAccessed()
        val watchEndpointExtensionField = builderMatches[8].getFieldAccessed()
        val commandClass = commandDefaultInstanceField.definingClass

        val setExtensionMethod = builderMatches[9].getMethodCalled()
        val extendableBuilderClass = setExtensionMethod.definingClass

        val getExtensionMethod = getCommandBuilderExtensionFingerprint(
            extendableBuilderClass, setExtensionMethod.parameterTypes.first().toString()
        ).method

        val queueAddEndpointExtensionField = QueueAddEndpointCommandFingerprint
            .instructionMatches.first().getFieldAccessed()
        val queueAddEndpointClass = queueAddEndpointExtensionField.definingClass
        val queueAddEndpointDefaultInstanceField = classDefBy(queueAddEndpointClass).fields.first {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.type == queueAddEndpointClass
        }

        // endregion

        // region Implement the extension interface using the proto classes.

        mutableClassDefBy(commandClass).apply {
            interfaces.add(EXTENSION_COMMAND_INTERFACE)

            val toBuilder = "$messageClass->toBuilder()$messageBuilderClass"
            val copyOnWrite = "$messageBuilderClass->copyOnWrite()V"
            val build = "$messageBuilderClass->build()$messageClass"
            val instanceField = "$messageBuilderClass->instance:$messageClass"

            addInterfaceMethod(
                "patch_getWatchEndpoint",
                registerCount = 2,
                """
                    invoke-virtual { p0 }, $toBuilder
                    move-result-object v0
                    check-cast v0, $extendableBuilderClass
                    sget-object v1, $watchEndpointExtensionField
                    invoke-virtual { v0, v1 }, $getExtensionMethod
                    move-result-object v0
                    # A command without a watch endpoint returns the default instance.
                    sget-object v1, $watchEndpointDefaultInstanceField
                    if-eq v0, v1, :none
                    return-object v0
                    :none
                    const/4 v0, 0x0
                    return-object v0
                """
            )

            addInterfaceMethod(
                "patch_createSingleTrackCommand",
                registerCount = 3,
                """
                    # Copy the watch endpoint without the playlist id and index.
                    check-cast p1, $messageClass
                    invoke-virtual { p1 }, $toBuilder
                    move-result-object v0
                    invoke-virtual { v0 }, $copyOnWrite
                    iget-object v1, v0, $instanceField
                    check-cast v1, $watchEndpointClass
                    const-string v2, ""
                    iput-object v2, v1, $playlistIdField
                    const/4 v2, 0x0
                    iput v2, v1, $playlistIndexField
                    # Clear the presence bits of the playlist id (0x2) and index (0x4).
                    iget v2, v1, $presenceBitsField
                    and-int/lit8 v2, v2, -0x7
                    iput v2, v1, $presenceBitsField
                    invoke-virtual { v0 }, $build
                    move-result-object v1

                    # Copy the command with the new watch endpoint.
                    invoke-virtual { p0 }, $toBuilder
                    move-result-object v0
                    check-cast v0, $extendableBuilderClass
                    sget-object v2, $watchEndpointExtensionField
                    invoke-virtual { v0, v2, v1 }, $setExtensionMethod
                    invoke-virtual { v0 }, $build
                    move-result-object v0
                    return-object v0
                """
            )

            addInterfaceMethod(
                "patch_createQueueAddCommand",
                registerCount = 3,
                """
                    sget-object v0, $queueAddEndpointDefaultInstanceField
                    invoke-static { v0, p1 }, $messageClass->parseFrom($messageClass[B)$messageClass
                    move-result-object v0
                    sget-object v1, $commandDefaultInstanceField
                    invoke-virtual { v1 }, $messageClass->createBuilder()$messageBuilderClass
                    move-result-object v1
                    check-cast v1, $extendableBuilderClass
                    sget-object v2, $queueAddEndpointExtensionField
                    invoke-virtual { v1, v2, v0 }, $setExtensionMethod
                    invoke-virtual { v1 }, $build
                    move-result-object v0
                    return-object v0
                """
            )
        }

        // endregion

        CommandResolverFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->overrideCommand($EXTENSION_COMMAND_INTERFACE)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $commandClass
            """
        )
    }
}

/**
 * Adds a public method declared by the extension [EXTENSION_COMMAND_INTERFACE] to this class.
 *
 * @param registerCount Number of registers used besides the parameter registers.
 */
context(patchContext: BytecodePatchContext)
private fun MutableClass.addInterfaceMethod(name: String, registerCount: Int, smali: String) {
    val declaration = patchContext.classDefBy(EXTENSION_COMMAND_INTERFACE).methods.single { it.name == name }
    // Parameter registers are the receiver, and each parameter.
    val parameterRegisters = 1 + declaration.parameterTypes.size

    methods.add(
        ImmutableMethod(
            type,
            name,
            declaration.parameters.map { ImmutableMethodParameter(it.type, null, null) },
            declaration.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(registerCount + parameterRegisters),
        ).toMutable().apply {
            addInstructionsWithLabels(0, smali)
        }
    )
}
