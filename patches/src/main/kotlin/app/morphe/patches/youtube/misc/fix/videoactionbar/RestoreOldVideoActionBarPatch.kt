/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.videoactionbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.request.buildRequestPatch
import app.morphe.patches.shared.misc.request.hookBuildRequest
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.contexthook.Endpoint
import app.morphe.patches.youtube.misc.contexthook.addClientVersionHook
import app.morphe.patches.youtube.misc.contexthook.clientContextHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.getReference
import app.morphe.util.insertLiteralOverride
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/RestoreOldVideoActionBarPatch;"

private const val EXTENSION_CONFIG_INFO_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/RestoreOldVideoActionBarPatch$ConfigInfoInterface;"

internal val restoreOldVideoActionBarPatch = bytecodePatch(
    description = "Overrides 'X-Youtube-Cold-Config-Data', fixes 'Hide video action buttons' and 'Return YouTube Dislike', "
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
        clientContextHookPatch,
        buildRequestPatch
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_restore_old_video_action_bar", summary = true)
        )

        if (is_20_30_or_greater) {
            hookBuildRequest(
                descriptor = "$EXTENSION_CLASS->fixVideoActionBar",
                hookHeader = true
            )

            val configInfoClass = with(BuildInnerTubeProtoRequestBodyFingerprint) {
                val match = instructionMatches.first()
                val index = match.index
                val instruction = match.instruction
                val register = instruction.registersUsed[0]

                method.addInstructions(
                    index,
                    "invoke-static { v$register }, $EXTENSION_CLASS->fixVideoActionBar($EXTENSION_CONFIG_INFO_INTERFACE)V"
                )

                instruction.getReference<FieldReference>()!!.type
            }

            getConfigInfoFingerprint(configInfoClass).let {
                it.classDef.apply {
                    interfaces.add(EXTENSION_CONFIG_INFO_INTERFACE)

                    mapOf(
                        0 to "patch_setColdConfigData",
                        1 to "patch_setColdHashData"
                    ).forEach { (matchIndex, interfaceMethodName) ->
                        val coldDataField = it.instructionMatches[matchIndex].instruction.getReference<FieldReference>()!!

                        methods.add(
                            ImmutableMethod(
                                type,
                                interfaceMethodName,
                                listOf(
                                    ImmutableMethodParameter("Ljava/lang/String;", null, null)
                                ),
                                "V",
                                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                                null,
                                null,
                                MutableMethodImplementation(3),
                            ).toMutable().apply {
                                addInstructions(
                                    0,
                                    """
                                        iput-object p1, p0, $coldDataField
                                        return-void
                                    """
                                )
                            }
                        )
                    }
                }
            }

            // If cold config data is overridden, the related video overlay breaks in fullscreen.
            // As a workaround for this, restore the old related video overlay.
            listOf(
                ModernRelateVideoOverlayFingerprint,
                RelateVideoOverlayLayoutParamFingerprint
            ).forEach { fingerprint ->
                fingerprint.matchAll().forEach {
                    it.method.insertLiteralOverride(
                        it.instructionMatches.first().index,
                        "$EXTENSION_CLASS->fixRelatedVideoOverlay(Z)Z"
                    )
                }
            }
        } else {
            // Since there are no side effects, this version simply spoofs the app version in the player.
            setOf(
                Endpoint.GET_WATCH,
                Endpoint.NEXT,
            ).forEach { endpoint ->
                addClientVersionHook(
                    endpoint,
                    "$EXTENSION_CLASS->getVideoActionBarAppVersionOverride(Ljava/lang/String;)Ljava/lang/String;",
                )
            }
        }
    }
}