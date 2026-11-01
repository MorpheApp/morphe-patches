/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4174
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.announcements;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Pair;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows a dialog on app startup when a newer Morphe Patches release includes YouTube changes.
 * Releases that only change other apps are ignored.
 */
@SuppressWarnings("unused")
public final class PatchesUpdateNoticePatch {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/MorpheApp/morphe-patches/releases?per_page=30";
    private static final String MANAGER_PACKAGE = "app.morphe.manager";
    private static final String WEBSITE_URL = "https://morphe.software";

    /**
     * Minimum time between two checks, to stay well below the GitHub API rate limit.
     */
    private static final long CHECK_INTERVAL_MS = 60 * 60 * 1000; // 1 hour.

    /**
     * Maximum number of changes listed in the dialog.
     */
    private static final int MAX_LISTED_CHANGES = 6;

    private static final float ITEM_TEXT_SCALE = 0.9f;
    private static final String BULLET = "•  ";

    /**
     * Matches YouTube entries of the release notes, but not YouTube Music entries.
     * Group 1 is the patch name (if any), group 2 is the change text.
     */
    private static final Pattern YOUTUBE_ENTRY = Pattern.compile(
            "^\\* \\*\\*YouTube(?! Music)(?: - ([^:*]+))?:\\*\\* (.+)$");
    private static final Pattern MARKDOWN_LINKS = Pattern.compile("\\s*\\(\\[[^\\]]*\\]\\([^)]*\\)\\)");

    private static volatile boolean checkedThisSession;

    private PatchesUpdateNoticePatch() {
    }

    private static final class UpdateInfo {
        final String latestVersion;
        final List<String> features = new ArrayList<>();
        final List<String> fixes = new ArrayList<>();
        final List<String> other = new ArrayList<>();

        UpdateInfo(String latestVersion) {
            this.latestVersion = latestVersion;
        }

        int changeCount() {
            return features.size() + fixes.size() + other.size();
        }
    }

    /**
     * Injection point.
     */
    public static void showNoticeIfNeeded(Activity activity) {
        try {
            if (!Settings.PATCHES_UPDATE_NOTICE.get() || checkedThisSession) return;
            checkedThisSession = true;

            final String installedVersion = stripVersionPrefix(Utils.getPatchesReleaseVersion());
            // Dev releases can be published several times a day, so they are only included if enabled.
            final boolean includeDevReleases = Settings.PATCHES_UPDATE_NOTICE_DEV_RELEASES.get();
            final long now = System.currentTimeMillis();
            // Check again right away if different patches are installed or the dev releases setting was changed.
            final boolean checkDue = !isCacheCurrent(installedVersion, includeDevReleases)
                    || now - Settings.PATCHES_UPDATE_NOTICE_LAST_CHECK_MS.get() >= CHECK_INTERVAL_MS;
            final boolean canFetch = checkDue && Utils.isNetworkConnected();

            if (!canFetch) {
                // Show the last found update again until it is installed or dismissed.
                UpdateInfo cached = loadCachedUpdate(installedVersion, includeDevReleases);
                if (cached != null) {
                    // Post, so the dialog is shown after the activity is created.
                    Utils.runOnMainThread(() -> showIfNotDismissed(activity, cached));
                }
                return;
            }

            Utils.runOnBackgroundThread(() -> {
                try {
                    UpdateInfo info = fetchUpdateInfo(installedVersion, includeDevReleases);
                    Settings.PATCHES_UPDATE_NOTICE_LAST_CHECK_MS.save(System.currentTimeMillis());
                    saveCachedUpdate(installedVersion, includeDevReleases, info);

                    Utils.runOnMainThread(() -> showIfNotDismissed(activity, info));
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Failed to check for patches updates", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "showNoticeIfNeeded failure", ex);
        }
    }

    private static void showIfNotDismissed(Activity activity, @Nullable UpdateInfo info) {
        if (info == null) return;

        if (info.latestVersion.equals(Settings.PATCHES_UPDATE_NOTICE_DISMISSED_VERSION.get())) {
            Logger.printDebug(() -> "Patches update " + info.latestVersion + " was dismissed");
            return;
        }

        showDialog(activity, info);
    }

    private static void saveCachedUpdate(String installedVersion, boolean includeDevReleases, @Nullable UpdateInfo info) {
        try {
            JSONObject json = new JSONObject();
            json.put("installed", installedVersion);
            json.put("dev", includeDevReleases);
            if (info == null) {
                // Nothing to show, but remember for which patches and setting this was checked.
                Settings.PATCHES_UPDATE_NOTICE_CACHE.save(json.toString());
                return;
            }

            json.put("latest", info.latestVersion);
            json.put("features", new JSONArray(info.features));
            json.put("fixes", new JSONArray(info.fixes));
            json.put("other", new JSONArray(info.other));
            Settings.PATCHES_UPDATE_NOTICE_CACHE.save(json.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save patches update cache", ex);
        }
    }

    @Nullable
    private static JSONObject loadCache(String installedVersion, boolean includeDevReleases) {
        try {
            String cache = Settings.PATCHES_UPDATE_NOTICE_CACHE.get();
            if (cache.isEmpty()) return null;

            JSONObject json = new JSONObject(cache);
            // The cache is outdated once different patches are installed or the dev releases setting is changed.
            if (!installedVersion.equals(json.optString("installed"))
                    || includeDevReleases != json.optBoolean("dev")) {
                return null;
            }
            return json;
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load patches update cache", ex);
            return null;
        }
    }

    private static boolean isCacheCurrent(String installedVersion, boolean includeDevReleases) {
        return loadCache(installedVersion, includeDevReleases) != null;
    }

    @Nullable
    private static UpdateInfo loadCachedUpdate(String installedVersion, boolean includeDevReleases) {
        try {
            JSONObject json = loadCache(installedVersion, includeDevReleases);
            if (json == null || !json.has("latest")) return null;

            UpdateInfo info = new UpdateInfo(json.getString("latest"));
            addAll(info.features, json.optJSONArray("features"));
            addAll(info.fixes, json.optJSONArray("fixes"));
            addAll(info.other, json.optJSONArray("other"));
            return info.changeCount() == 0 ? null : info;
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load patches update cache", ex);
            return null;
        }
    }

    private static void addAll(List<String> list, @Nullable JSONArray array) {
        if (array == null) return;
        for (int i = 0, length = array.length(); i < length; i++) {
            list.add(array.optString(i));
        }
    }

    /**
     * @return The YouTube changes of all releases newer than the installed patches,
     *         or null if there is no newer release with YouTube changes.
     */
    @Nullable
    private static UpdateInfo fetchUpdateInfo(String installedVersion, boolean includeDevReleases) throws Exception {
        HttpURLConnection connection = Requester.openConnection(RELEASES_URL);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        Logger.printDebug(() -> "Fetching patches releases from: " + connection.getURL());

        final int responseCode = connection.getResponseCode();
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            connection.disconnect();
            // Keep the cached result, and try again on the next app launch.
            throw new IOException("Failed to get patches releases. Response code: " + responseCode);
        }

        JSONArray releases = Requester.parseJSONArrayAndDisconnect(connection);
        UpdateInfo info = null;
        Set<String> seen = new LinkedHashSet<>();

        // Releases are listed newest first.
        for (int i = 0, length = releases.length(); i < length; i++) {
            JSONObject release = releases.getJSONObject(i);
            if (release.optBoolean("draft")) continue;
            if (release.optBoolean("prerelease") && !includeDevReleases) continue;

            String version = stripVersionPrefix(release.optString("tag_name"));
            if (compareVersions(version, installedVersion) <= 0) break;

            if (info == null) {
                info = new UpdateInfo(version);
            }
            parseYouTubeChanges(release.optString("body"), info, seen);
        }

        if (info == null || info.changeCount() == 0) {
            Logger.printDebug(() -> "No newer patches with YouTube changes. Installed: " + installedVersion);
            return null;
        }

        return info;
    }

    private static void parseYouTubeChanges(String body, UpdateInfo info, Set<String> seen) {
        List<String> section = info.other;

        for (String rawLine : body.split("\\r?\\n")) {
            String line = rawLine.trim();

            if (line.startsWith("### ")) {
                if (line.contains("Features")) {
                    section = info.features;
                } else if (line.contains("Bug Fixes")) {
                    section = info.fixes;
                } else {
                    section = info.other;
                }
                continue;
            }

            Matcher matcher = YOUTUBE_ENTRY.matcher(line);
            if (!matcher.matches()) continue;

            String patchName = matcher.group(1);
            String text = MARKDOWN_LINKS.matcher(matcher.group(2)).replaceAll("")
                    .replace("`", "")
                    .trim();
            if (text.isEmpty()) continue;

            String change = patchName == null
                    ? text
                    : patchName.trim() + ": " + text;

            if (seen.add(change)) {
                section.add(change);
            }
        }
    }

    private static void showDialog(Activity activity, UpdateInfo info) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            SpannableStringBuilder message = new SpannableStringBuilder(Html.fromHtml(
                    str("morphe_patches_update_notice_dialog_message",
                            TextUtils.htmlEncode(info.latestVersion),
                            TextUtils.htmlEncode(stripVersionPrefix(Utils.getPatchesReleaseVersion()))),
                    Html.FROM_HTML_MODE_LEGACY));

            int remaining = MAX_LISTED_CHANGES;
            remaining = appendSection(message, str("morphe_patches_update_notice_dialog_features"), info.features, remaining);
            remaining = appendSection(message, str("morphe_patches_update_notice_dialog_fixes"), info.fixes, remaining);
            appendSection(message, str("morphe_patches_update_notice_dialog_other"), info.other, remaining);

            final int hidden = info.changeCount() - MAX_LISTED_CHANGES;
            if (hidden > 0) {
                message.append("\n\n");
                final int start = message.length();
                message.append(str("morphe_patches_update_notice_dialog_more", hidden));
                message.setSpan(new StyleSpan(Typeface.ITALIC), start, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                message.setSpan(new RelativeSizeSpan(ITEM_TEXT_SCALE), start, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    str("morphe_patches_update_notice_dialog_title"), // Title.
                    message, // Message.
                    null, // No EditText.
                    str("morphe_patches_update_notice_dialog_update"), // OK button text.
                    () -> openManager(activity), // OK button action.
                    null, // No cancel button.
                    str("morphe_patches_update_notice_dialog_dismiss"), // Neutral button text.
                    () -> Settings.PATCHES_UPDATE_NOTICE_DISMISSED_VERSION.save(info.latestVersion),
                    true // Dismiss dialog on Neutral button click.
            );

            Utils.showDialog(activity, dialogPair.first);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to show patches update dialog", ex);
        }
    }

    private static int appendSection(SpannableStringBuilder message, String title, List<String> changes, int remaining) {
        if (changes.isEmpty() || remaining <= 0) return remaining;

        message.append("\n\n");
        final int titleStart = message.length();
        message.append(title);
        message.setSpan(new StyleSpan(Typeface.BOLD), titleStart, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        for (String change : changes) {
            if (remaining <= 0) break;
            remaining--;

            message.append("\n");
            final int itemStart = message.length();
            message.append(BULLET);

            // Patch name in bold, if the change belongs to a specific patch.
            final int separator = change.indexOf(": ");
            if (separator > 0) {
                final int nameStart = message.length();
                message.append(change, 0, separator + 1);
                message.setSpan(new StyleSpan(Typeface.BOLD), nameStart, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                message.append(change.substring(separator + 1));
            } else {
                message.append(change);
            }

            // Smaller text, and wrapped lines aligned after the bullet.
            message.setSpan(new RelativeSizeSpan(ITEM_TEXT_SCALE), itemStart, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            message.setSpan(new LeadingMarginSpan.Standard(0, Dim.dp12), itemStart, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return remaining;
    }

    private static void openManager(Activity activity) {
        try {
            Intent intent = activity.getPackageManager().getLaunchIntentForPackage(MANAGER_PACKAGE);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return;
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not open Morphe Manager", ex);
        }

        Utils.openLink(WEBSITE_URL);
    }

    private static String stripVersionPrefix(String version) {
        return version.startsWith("v") ? version.substring(1) : version;
    }

    /**
     * Compares versions such as 1.49.0, 1.49.0-dev.7 and 2.0.0-dev.1.
     * A release is newer than any dev release of the same version.
     */
    static int compareVersions(String a, String b) {
        String[] aParts = a.split("-", 2);
        String[] bParts = b.split("-", 2);

        int result = compareNumbers(aParts[0], bParts[0]);
        if (result != 0) return result;

        final boolean aIsRelease = aParts.length == 1;
        final boolean bIsRelease = bParts.length == 1;
        if (aIsRelease || bIsRelease) {
            return Boolean.compare(aIsRelease, bIsRelease);
        }

        return compareNumbers(aParts[1].replaceAll("[^0-9.]", ""), bParts[1].replaceAll("[^0-9.]", ""));
    }

    private static int compareNumbers(String a, String b) {
        String[] aNumbers = a.split("\\.");
        String[] bNumbers = b.split("\\.");

        for (int i = 0, length = Math.max(aNumbers.length, bNumbers.length); i < length; i++) {
            final int aNumber = i < aNumbers.length ? parseIntOrZero(aNumbers[i]) : 0;
            final int bNumber = i < bNumbers.length ? parseIntOrZero(bNumbers[i]) : 0;
            if (aNumber != bNumber) return Integer.compare(aNumber, bNumber);
        }
        return 0;
    }

    private static int parseIntOrZero(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
