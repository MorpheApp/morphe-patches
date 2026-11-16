package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

import com.reddit.domain.model.Subreddit;

import app.morphe.extension.reddit.settings.preference.PermanentNsfwPreference;
import app.morphe.extension.reddit.settings.Settings;

import app.morphe.extension.reddit.patches.nsfw.PermanentNsfwDisabler;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

/** Runtime entry points for permanent NSFW disabling. */
public final class PermanentNsfwPatch {
    private static PermanentNsfwDisabler disabler;

    private PermanentNsfwPatch() {}

    public static boolean isPatchIncluded() {
        return false; // Rewritten by the patcher only after resolving its required hook.
    }

    public static boolean isEnabled() {
        return isPatchIncluded() && getDisabler().isEnabled();
    }

    public static boolean allowNsfw(boolean requested) {
        return !isEnabled() && requested;
    }

    public static boolean enforceSafeSearch(boolean safeSearch) {
        return isEnabled() || safeSearch;
    }

    public static int disabledTitleResource(int original) {
        return isEnabled() ? ResourceUtils.getStringIdentifier("morphe_permanent_nsfw_disabled") : original;
    }

    public static int disabledMessageResource(int original) {
        return isEnabled() ? ResourceUtils.getStringIdentifier("morphe_permanent_nsfw_blocked_message") : original;
    }

    public static int goBackLabelResource(int original) {
        return isEnabled() ? ResourceUtils.getStringIdentifier("action_go_back") : original;
    }

    public static void showProfileWarning(Activity activity) {
        Utils.runOnMainThread(() -> {
            try {
                if (!activity.isFinishing() && !activity.isDestroyed()) openProfileWarning(activity);
            } catch (Exception ex) {
                Logger.printException(() -> "showProfileWarning failure", ex);
            }
        });
    }

    private static void openProfileWarning(Activity activity) {
        // Replaced with Reddit's native warning screen and navigation call.
    }

    /** The anonymous profile drawer uses XML switches instead of Compose settings. */
    public static void hideAnonymousControls(View root) {
        if (!isEnabled()) return;
        try {
            for (String name : new String[]{"toggle_over18", "toggle_blur_nsfw", "title_settings", "divider"}) {
                View control = root.findViewById(ResourceUtils.getIdIdentifier(name));
                if (control != null) control.setVisibility(View.GONE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideAnonymousControls failure", ex);
        }
    }

    /** The native recent-visit writer receives community metadata before saving it. */
    public static boolean skipRecentSubreddit(Subreddit subreddit) {
        return isEnabled() && subreddit != null && Boolean.TRUE.equals(subreddit.getOver18());
    }

    /** Hide records saved before permanent disabling from Reddit's two recent-visit views. */
    public static List<Subreddit> visibleRecentSubreddits(List<Subreddit> recent) {
        if (!isEnabled() || recent == null) return recent;
        List<Subreddit> visible = new ArrayList<>(recent.size());
        for (Subreddit subreddit : recent) {
            if (!Boolean.TRUE.equals(subreddit.getOver18())) visible.add(subreddit);
        }
        return visible;
    }

    /** Called after the existing Morphe settings categories have been built. */
    @SuppressWarnings("deprecation")
    public static void installPreference(PreferenceFragment fragment) {
        try {
            if (isEnabled()) {
                enforceDisabledPreferences();
                for (String key : new String[]{"morphe_remove_nsfw_dialog", "morphe_redgifs_audio", "morphe_redgifs_hd"}) {
                    removeByKey(fragment.getPreferenceScreen(), key);
                }
            }
            fragment.getPreferenceScreen().addPreference(new PermanentNsfwPreference(fragment.getActivity()));
        } catch (Exception ex) {
            Logger.printException(() -> "installPreference failure", ex);
        }
    }

    /** Also clear saved bypass/media options, rather than merely hiding their controls. */
    public static void enforceDisabledPreferences() {
        Settings.REMOVE_NSFW_DIALOG.save(false);
        Settings.REDGIFS_AUDIO.save(false);
        Settings.REDGIFS_HD.save(false);
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

}
