package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Caption settings. Used to organize all caption related settings together.
 */
internal val settingsMenuCaptionGroup = mutableSetOf<BasePreference>()

/**
 * Adds an instance method, public and final by default.
 *
 * @param parameters Parameter types; J and D require two registers.
 * @param freeRegisters Number of free registers, starting at v0.
 */
internal fun MutableClass.addHelperMethod(
    name: String,
    returnType: String,
    parameters: List<String> = listOf(),
    freeRegisters: Int = 1,
    accessFlags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
    smali: String,
) {
    require(!AccessFlags.STATIC.isSet(accessFlags)) { "Caption helper must be an instance method" }
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            accessFlags,
            null,
            null,
            // p0 is this; wide primitives use pairs, including when registers shift.
            MutableMethodImplementation(
                freeRegisters + 1 + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
            ),
        ).toMutable().apply {
            addInstructions(0, smali)
        }
    )
}

@Suppress("unused")
val captionsPatch = bytecodePatch(
    name = "Captions",
    description = "Adds options to disable captions from being automatically enabled, " +
            "select a preferred caption language, add more auto-translate languages, " +
            "and show captions in picture-in-picture.",
) {
    dependsOn(
        autoCaptionsPatch,
        preferredCaptionLanguagePatch,
        autoTranslateCaptionsPatch,
        captionCookiesPatch,
        transcriptPatch,
        translatedCaptionPensPatch,
        captionWindowMeasurePatch,
        stableCaptionBlocksPatch,
        captionViewportPatch,
        captionsInPipPatch,
        captionsAfterSeekPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_captions_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = settingsMenuCaptionGroup
            )
        )
    }
}
