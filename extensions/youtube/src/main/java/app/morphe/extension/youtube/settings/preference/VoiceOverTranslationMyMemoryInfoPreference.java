package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_MY_MEMORY;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.AttributeSet;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.URLLinkPreference;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Allows tapping the MyMemory info preference to open the MyMemory website.
 */
@SuppressWarnings({"deprecation", "unused"})
public class VoiceOverTranslationMyMemoryInfoPreference extends URLLinkPreference {
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, str) -> {
        // Because this listener may run before the Morphe settings fragment updates Settings,
        // this could show the prior config and not the current.
        //
        // Push this call to the end of the main run queue,
        // so all other listeners are done and Settings is up to date.
        Utils.runOnMainThread(this::updateUI);
    };

    {
        externalURL = "https://mymemory.translated.net";
    }

    private void updateUI() {
        //noinspection deprecation
        setEnabled(Settings.VOT_ENABLED.get()
                && Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_MY_MEMORY));
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        updateUI();
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public VoiceOverTranslationMyMemoryInfoPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public VoiceOverTranslationMyMemoryInfoPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public VoiceOverTranslationMyMemoryInfoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public VoiceOverTranslationMyMemoryInfoPreference(Context context) {
        super(context);
    }
}
