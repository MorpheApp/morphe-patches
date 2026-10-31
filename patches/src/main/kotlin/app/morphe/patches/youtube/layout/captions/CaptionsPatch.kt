package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
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
 * Adds a public final method.
 *
 * @param parameters Object parameter types.
 * @param freeRegisters Number of free registers, starting at v0.
 */
internal fun MutableClass.addHelperMethod(
    name: String,
    returnType: String,
    parameters: List<String> = listOf(),
    freeRegisters: Int = 1,
    smali: String,
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            // Parameter registers are p0 (this) and one for each parameter.
            MutableMethodImplementation(freeRegisters + 1 + parameters.size),
        ).toMutable().apply {
            addInstructions(0, smali)
        }
    )
}

@Suppress("unused")
val captionsPatch = bytecodePatch(
    name = "Captions",
    description = "Adds options to disable captions from being automatically enabled, " +
            "select a preferred and a secondary caption language, add more auto-translate languages, " +
            "and show captions in stable blocks and in picture-in-picture.",
) {
    dependsOn(
        autoCaptionsPatch,
        preferredCaptionLanguagePatch,
        autoTranslateCaptionsPatch,
        captionCookiesPatch,
        transcriptPatch,
        translatedCaptionPensPatch,
        captionTrackCapturePatch,
        captionWindowMeasurePatch,
        stableCaptionBlocksPatch,
        captionViewportPatch,
        captionsInPipPatch,
        captionsAfterSeekPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val remaining = settingsMenuCaptionGroup.associateByTo(mutableMapOf()) { it.key }
        fun take(vararg keys: String) = keys.mapNotNull { remaining.remove(it) }.toSet()

        val languages = take(
            "morphe_preferred_caption_language",
            "morphe_secondary_caption_language",
            "morphe_auto_translate_additional_languages",
        )
        val presentation = take(
            "morphe_caption_scale",
            "morphe_stable_caption_blocks",
            "morphe_auto_captions_style",
            "morphe_captions_in_pip",
        )

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_captions_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    PreferenceCategory(
                        key = "morphe_captions_languages",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = languages
                    ),
                    PreferenceCategory(
                        key = "morphe_captions_presentation",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = presentation
                    ),
                    PreferenceScreenPreference(
                        key = "morphe_captions_advanced",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = remaining.values.toSet()
                    ),
                )
            )
        )
    }
}
