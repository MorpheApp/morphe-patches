package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/CaptionCookiesPatch;"

internal val captionCookiesPatch = bytecodePatch(
    description = "Adds an option to set cookies in Timed Text API (Caption Data API) requests.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    execute {
        settingsMenuCaptionGroup.addAll(listOf(
            SwitchPreference("morphe_set_caption_cookies"),
            TextPreference(
                "morphe_caption_cookies",
                inputType = InputType.TEXT_MULTI_LINE
            ),
            NonInteractivePreference(
                key = "morphe_get_caption_cookies",
                tag = "app.morphe.extension.youtube.settings.preference.GetCaptionCookiesPreference",
            )
        ))

        TimedTextUrlFingerprint.let {
            it.method.apply {
                val helperMethod = ImmutableMethod(
                    definingClass,
                    "patch_setUrlRequestHeaders",
                    listOf(
                        ImmutableMethodParameter(
                            "Lorg/chromium/net/UrlRequest\$Builder;",
                            null,
                            null
                        )
                    ),
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    annotations,
                    null,
                    MutableMethodImplementation(4),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->getRequireCookies()Z
                            move-result v0
                            if-eqz v0, :disabled
                            
                            # Set Cookie.
                            const-string v0, "Cookie"
                            invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->getCookies()Ljava/lang/String;
                            move-result-object v1
                            invoke-virtual { p1, v0, v1 }, Lorg/chromium/net/UrlRequest${'$'}Builder;->addHeader(Ljava/lang/String;Ljava/lang/String;)Lorg/chromium/net/UrlRequest${'$'}Builder;

                            # Set User-Agent.
                            const-string v0, "User-Agent"
                            invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->getUserAgent()Ljava/lang/String;
                            move-result-object v1

                            # Set Header.
                            invoke-virtual { p1, v0, v1 }, Lorg/chromium/net/UrlRequest${'$'}Builder;->addHeader(Ljava/lang/String;Ljava/lang/String;)Lorg/chromium/net/UrlRequest${'$'}Builder;

                            :disabled
                            return-void
                        """,
                    )
                }

                it.classDef.methods.add(helperMethod)

                val urlIndex = it.instructionMatches.first().index
                val urlRegister =
                    getInstruction<FiveRegisterInstruction>(urlIndex).registerD
                val buildIndex = urlIndex + 1
                val buildRegister =
                    getInstruction<OneRegisterInstruction>(buildIndex).registerA

                addInstruction(
                    buildIndex + 1,
                    "invoke-direct { p0, v$buildRegister }, $helperMethod"
                )

                addInstruction(
                    urlIndex,
                    "invoke-static { v$urlRegister }, $EXTENSION_CLASS_DESCRIPTOR->setRequireCookies(Ljava/lang/String;)V"
                )
            }
        }
    }
}
