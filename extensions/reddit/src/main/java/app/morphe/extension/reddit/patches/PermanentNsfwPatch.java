package app.morphe.extension.reddit.patches;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;

import app.morphe.extension.reddit.settings.preference.PermanentNsfwPreference;

import app.morphe.extension.reddit.patches.nsfw.PermanentNsfwDisabler;
import app.morphe.extension.reddit.patches.nsfw.RedditNsfwInterceptor;
import app.morphe.extension.shared.Utils;
import okhttp3.OkHttpClient;

/** Runtime entry points injected by the experimental patch. */
public final class PermanentNsfwPatch {
    private static PermanentNsfwDisabler disabler;

    private PermanentNsfwPatch() {}

    public static boolean isPatchIncluded() {
        return false; // Rewritten by the patcher only after resolving its required hook.
    }

    public static boolean isEnabled() {
        return getDisabler().isEnabled();
    }

    /** Called after the existing Morphe settings categories have been built. */
    @SuppressWarnings("deprecation")
    public static void installPreference(PreferenceFragment fragment) {
        if (isEnabled()) removeByKey(fragment.getPreferenceScreen(), "morphe_remove_nsfw_dialog");
        fragment.getPreferenceScreen().addPreference(new PermanentNsfwPreference(fragment.getActivity()));
    }

    @SuppressWarnings("deprecation")
    private static void removeByKey(PreferenceGroup group, String key) {
        for (int i = group.getPreferenceCount() - 1; i >= 0; i--) {
            Preference preference = group.getPreference(i);
            if (key.equals(preference.getKey())) group.removePreference(preference);
            else if (preference instanceof PreferenceGroup child) removeByKey(child, key);
        }
    }

    public static synchronized PermanentNsfwDisabler getDisabler() {
        if (disabler == null) {
            // Keep the original private filename so an update never resets NSFW disabling.
            SharedPreferences preferences = Utils.getContext().getSharedPreferences(
                    "reddit_permanent_nsfw_lock", Context.MODE_PRIVATE);
            disabler = new PermanentNsfwDisabler(new PermanentNsfwDisabler.Store() {
                @Override public boolean read() {
                    return preferences.getBoolean("enabled", false);
                }
                @Override public boolean enable() {
                    return preferences.edit().putBoolean("enabled", true).commit();
                }
            });
        }
        return disabler;
    }

    /** Must run before OkHttpClient snapshots the builder's application interceptors. */
    public static void install(OkHttpClient.Builder builder) {
        for (okhttp3.Interceptor interceptor : builder.interceptors()) {
            if (interceptor instanceof RedditNsfwInterceptor) return;
        }
        // First application interceptor sees responses after the others, including cache hits.
        builder.interceptors().add(0, new RedditNsfwInterceptor(() -> getDisabler().isEnabled()));
    }
}
