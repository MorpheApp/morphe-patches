/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.album

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patches.music.interaction.playlisttracktap.CommandResolverFingerprint
import app.morphe.patches.music.interaction.playlisttracktap.WatchEndpointCommandBuilderFingerprint
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/album/PlayAlbumSongsPatch;"

private const val EXTENSION_REGISTRY_CLASS = "Lcom/google/protobuf/ExtensionRegistryLite;"

@Suppress("unused")
val playAlbumSongsPatch = patch(
    name = "Play album songs",
    description = "Adds an option to play the songs of albums instead of their music videos."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_music_play_album_songs", summary = true)
        )

        // region Resolve the obfuscated proto classes.

        val builderMatches = WatchEndpointCommandBuilderFingerprint.instructionMatches

        // GeneratedMessageLite, whose method names are not obfuscated.
        val messageClass = builderMatches[1].getMethodCalled().definingClass
        val commandDefaultInstanceField = builderMatches[7].fieldReference
        val commandClass = commandDefaultInstanceField.definingClass

        val responseClass = WatchNextResponseModelConstructorFingerprint.method.parameterTypes.single().toString()
        val responseDefaultInstanceField = classDefBy(responseClass).fields.first {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.type == responseClass
        }

        // endregion

        // region Fill in the extension methods that use the proto classes.

        fun extensionMethod(name: String) = Fingerprint(definingClass = EXTENSION_CLASS, name = name).method

        extensionMethod("serializeMessage").addInstructions(
            0,
            """
                check-cast p0, $messageClass
                invoke-virtual { p0 }, $messageClass->toByteArray()[B
                move-result-object p0
                return-object p0
            """
        )
        // The methods have only their parameter registers, plus one in the methods without any.
        extensionMethod("parseMessage").addInstructions(
            0,
            """
                check-cast p0, $messageClass
                check-cast p2, $EXTENSION_REGISTRY_CLASS
                invoke-static { p0, p1, p2 }, $messageClass->parseFrom($messageClass[B$EXTENSION_REGISTRY_CLASS)$messageClass
                move-result-object p0
                return-object p0
            """
        )
        // The registry the app parses its own messages with.
        extensionMethod("extensionRegistry").addInstructions(
            0,
            """
                invoke-static { }, $EXTENSION_REGISTRY_CLASS->getGeneratedRegistry()$EXTENSION_REGISTRY_CLASS
                move-result-object v0
                return-object v0
            """
        )
        extensionMethod("watchNextResponseDefaultInstance").addInstructions(
            0,
            """
                sget-object v0, $responseDefaultInstanceField
                return-object v0
            """
        )
        extensionMethod("commandDefaultInstance").addInstructions(
            0,
            """
                sget-object v0, $commandDefaultInstanceField
                return-object v0
            """
        )

        // endregion

        // The album queue is changed before the app builds anything from the response.
        WatchNextResponseModelConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->rewriteWatchNextResponse(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $responseClass
            """
        )

        // A tapped album track is changed before the app starts playing it.
        CommandResolverFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->overrideCommand(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p1
                check-cast p1, $commandClass
            """
        )
    }
}
