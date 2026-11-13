/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4178
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Pair;
import android.util.TypedValue;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.SharedSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Shows a dialog on app startup when a newer Morphe Patches release includes changes for the patched app.
 * Releases that only change other apps are ignored.
 */
@SuppressWarnings("unused")
public final class PatchesUpdateNoticePatch {

    public enum UpdateNoticeType {
        OFF,
        /**
         * Stable releases only.
         */
        STABLE,
        /**
         * Stable and dev releases. Dev releases can be published several times a day.
         */
        DEV
    }

    /**
     * Raw file urls have no rate limit, unlike the GitHub API.
     * The main branch changelog lists the stable releases, the dev branch changelog also the dev releases.
     */
    private static final String STABLE_CHANGELOG_URL =
            "https://raw.githubusercontent.com/MorpheApp/morphe-patches/main/CHANGELOG.md";
    private static final String DEV_CHANGELOG_URL =
            "https://raw.githubusercontent.com/MorpheApp/morphe-patches/dev/CHANGELOG.md";
    private static final String MANAGER_PACKAGE = "app.morphe.manager";
    private static final String WEBSITE_URL = "https://morphe.software";

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    /**
     * Maximum number of changes listed in the dialog.
     */
    private static final int MAX_LISTED_CHANGES = 6;

    /**
     * Text size of the {@link CustomDialog} message, in sp.
     */
    private static final float MESSAGE_TEXT_SIZE_SP = 16;
    private static final float HEADING_TEXT_SCALE = 1.15f;
    private static final float HEADING_GAP_TEXT_SCALE = 0.4f;
    private static final float MORE_TEXT_SCALE = 0.9f;
    private static final int RULE_ALPHA = 0x33;
    private static final String BULLET = "• ";

    /**
     * Release headers, such as "## [2.1.0](url) (date)", "# [1.28.0-dev.3](url) (date)" and "# 1.0.0 (date)".
     * Group 1 is the version.
     */
    private static final Pattern RELEASE_HEADER = Pattern.compile("^#{1,2} \\[?([^\\]\\s]+)");

    /**
     * Group 1 to 3 are the version numbers, group 4 is the dev release number (if any).
     */
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-dev\\.(\\d+))?$");

    /**
     * Release note entries with a semantic release scope, such as "* **YouTube - Patch name:** Change text".
     * Group 1 is the scope, group 2 is the change text.
     */
    private static final Pattern SCOPED_ENTRY = Pattern.compile("^\\* \\*\\*([^*]+?):\\*\\* (.+)$");
    /**
     * Separates the app and the patch name of a scope, such as "YouTube Music - Patch name".
     */
    private static final String SCOPE_PATCH_SEPARATOR = " - ";
    /**
     * Separates the apps of a scope shared by several apps, such as "YouTube / YouTube Music".
     */
    private static final Pattern SCOPE_APP_SEPARATOR = Pattern.compile("\\s*[/&,]\\s*");
    private static final Pattern COMMIT_LINK = Pattern.compile("/commit/([0-9a-f]+)\\)");
    private static final Pattern MARKDOWN_LINKS = Pattern.compile("\\s*\\(\\[[^\\]]*\\]\\([^)]*\\)\\)");

    private static volatile boolean checkedThisSession;

    private PatchesUpdateNoticePatch() {
    }

    /**
     * @return The app name used in the scopes of the release notes, such as "YouTube Music".
     */
    @SuppressWarnings("SameReturnValue")
    private static String getChangelogScope() {
        return ""; // Value is replaced during patching.
    }

    private static final class UpdateInfo {
        final String latestVersion;
        final String installedVersion;
        final List<String> features = new ArrayList<>();
        final List<String> fixes = new ArrayList<>();
        final List<String> other = new ArrayList<>();

        UpdateInfo(String latestVersion, String installedVersion) {
            this.latestVersion = latestVersion;
            this.installedVersion = installedVersion;
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
            final UpdateNoticeType noticeType = SharedSettings.PATCHES_UPDATE_NOTICE.get();
            if (noticeType == UpdateNoticeType.OFF || checkedThisSession) return;
            checkedThisSession = true;

            final String installedVersion = Utils.getPatchesReleaseVersion();
            final boolean includeDevReleases = noticeType == UpdateNoticeType.DEV;

            Utils.runOnBackgroundThread(() -> {
                try {
                    UpdateInfo info = fetchUpdateInfo(installedVersion, includeDevReleases);
                    if (info == null) return;

                    if (info.latestVersion.equals(SharedSettings.PATCHES_UPDATE_NOTICE_DISMISSED_VERSION.get())) {
                        Logger.printDebug(() -> "Patches update " + info.latestVersion + " was dismissed");
                        return;
                    }

                    Utils.runOnMainThread(() -> showDialog(activity, info));
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Failed to check for patches updates", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "showNoticeIfNeeded failure", ex);
        }
    }

    /**
     * @return The app changes of all releases newer than the installed patches,
     *         or null if there is no newer release with changes for the app.
     */
    @Nullable
    private static UpdateInfo fetchUpdateInfo(String installedVersion, boolean includeDevReleases) throws IOException {
        final int[] installed = parseVersion(installedVersion);
        if (installed == null) {
            Logger.printDebug(() -> "Unknown installed patches version: " + installedVersion);
            return null;
        }

        HttpURLConnection connection = Requester.openConnection(
                includeDevReleases ? DEV_CHANGELOG_URL : STABLE_CHANGELOG_URL);
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            Logger.printDebug(() -> "Fetching patches changelog from: " + connection.getURL());

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                throw new IOException("Failed to get patches changelog. Response code: " + responseCode);
            }

            // The changelog is large, but only its start is read.
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                return parseChangelog(reader, installedVersion, installed, includeDevReleases);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Releases are listed newest first. The changes of the newer releases are read
     * until the stable release the installed patches are based on.
     * <p>
     * A stable release lists again all changes of its dev releases. So if dev patches are installed,
     * the changes of the installed and older dev releases are read too, and are not shown.
     */
    @Nullable
    private static UpdateInfo parseChangelog(BufferedReader reader, String installedVersion,
                                             int[] installed, boolean includeDevReleases) throws IOException {
        final String appScope = getChangelogScope();

        // Changes by commit, so a change listed by both a stable and a dev release is used once.
        Map<String, String> features = new LinkedHashMap<>();
        Map<String, String> fixes = new LinkedHashMap<>();
        Map<String, String> other = new LinkedHashMap<>();
        Set<String> installedChanges = new HashSet<>();

        String latestVersion = null;
        boolean skipRelease = true;
        boolean installedRelease = false;
        Map<String, String> section = other;

        String line;
        while ((line = reader.readLine()) != null) {
            Matcher header = RELEASE_HEADER.matcher(line);
            if (header.find()) {
                final int[] version = parseVersion(header.group(1));
                section = other;
                skipRelease = version == null;
                if (version == null) continue;

                final boolean stable = version[3] == Integer.MAX_VALUE;
                final int comparison = compareVersions(version, installed);
                if (stable && comparison <= 0) break;

                installedRelease = comparison <= 0;
                if (!installedRelease && latestVersion == null) {
                    // Dev releases newer than the latest stable release are not shown if not enabled.
                    skipRelease = !stable && !includeDevReleases;
                    if (!skipRelease) latestVersion = header.group(1);
                }
                continue;
            }

            if (skipRelease) continue;
            line = line.trim();

            if (line.startsWith("### ")) {
                if (line.contains("Features")) {
                    section = features;
                } else if (line.contains("Bug Fixes")) {
                    section = fixes;
                } else {
                    section = other;
                }
                continue;
            }

            Matcher entry = SCOPED_ENTRY.matcher(line);
            if (!entry.matches()) continue;

            String scope = Objects.requireNonNull(entry.group(1));
            String patchName = null;
            final int patchSeparator = scope.indexOf(SCOPE_PATCH_SEPARATOR);
            if (patchSeparator >= 0) {
                patchName = scope.substring(patchSeparator + SCOPE_PATCH_SEPARATOR.length());
                scope = scope.substring(0, patchSeparator);
            }
            if (!isAppScope(scope, appScope)) continue;

            //noinspection ConstantConditions
            String text = MARKDOWN_LINKS.matcher(entry.group(2)).replaceAll("")
                    .replace("`", "")
                    .trim();
            if (text.isEmpty()) continue;

            String change = patchName == null
                    ? text
                    : patchName.trim() + ": " + text;
            Matcher commit = COMMIT_LINK.matcher(line);
            String key = commit.find() ? commit.group(1) : change;

            if (installedRelease) {
                installedChanges.add(key);
            } else {
                section.putIfAbsent(key, change);
            }
        }

        if (latestVersion == null) {
            Logger.printDebug(() -> "No newer patches. Installed: " + installedVersion);
            return null;
        }

        UpdateInfo info = new UpdateInfo(latestVersion, installedVersion);
        addChanges(info.features, features, installedChanges);
        addChanges(info.fixes, fixes, installedChanges);
        addChanges(info.other, other, installedChanges);

        if (info.changeCount() == 0) {
            Logger.printDebug(() -> "No newer patches with " + appScope + " changes. Installed: " + installedVersion);
            return null;
        }
        return info;
    }

    /**
     * @return If the apps of a scope include the app, such as "YouTube" in "YouTube / YouTube Music".
     *         Scopes of patches that are not specific to an app, such as "Settings", do not match.
     */
    private static boolean isAppScope(String scope, String appScope) {
        for (String app : SCOPE_APP_SEPARATOR.split(scope.trim())) {
            if (app.equals(appScope)) return true;
        }
        return false;
    }

    private static void addChanges(List<String> list, Map<String, String> changes, Set<String> installedChanges) {
        for (Map.Entry<String, String> change : changes.entrySet()) {
            if (!installedChanges.contains(change.getKey())) {
                list.add(change.getValue());
            }
        }
    }

    private static void showDialog(Activity activity, UpdateInfo info) {
        try {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            SpannableStringBuilder message = new SpannableStringBuilder(Html.fromHtml(
                    str("morphe_patches_update_notice_dialog_message",
                            TextUtils.htmlEncode(info.latestVersion),
                            TextUtils.htmlEncode(info.installedVersion)),
                    Html.FROM_HTML_MODE_LEGACY));

            // Wrapped lines of a change start under its text, not under the bullet.
            Paint bulletPaint = new Paint();
            bulletPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, MESSAGE_TEXT_SIZE_SP,
                    activity.getResources().getDisplayMetrics()));
            final int bulletIndent = Math.round(bulletPaint.measureText(BULLET));
            final int ruleColor = (ThemeUtils.getAppForegroundColor() & 0x00FFFFFF) | (RULE_ALPHA << 24);

            int remaining = MAX_LISTED_CHANGES;
            remaining = appendSection(message, str("morphe_patches_update_notice_dialog_features"),
                    info.features, remaining, bulletIndent, ruleColor);
            remaining = appendSection(message, str("morphe_patches_update_notice_dialog_fixes"),
                    info.fixes, remaining, bulletIndent, ruleColor);
            appendSection(message, str("morphe_patches_update_notice_dialog_other"),
                    info.other, remaining, bulletIndent, ruleColor);

            final int hidden = info.changeCount() - MAX_LISTED_CHANGES;
            if (hidden > 0) {
                message.append("\n\n");
                final int start = message.length();
                message.append(str("morphe_patches_update_notice_dialog_more", hidden));
                message.setSpan(new StyleSpan(Typeface.ITALIC), start, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                message.setSpan(new RelativeSizeSpan(MORE_TEXT_SCALE), start, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
                    () -> SharedSettings.PATCHES_UPDATE_NOTICE_DISMISSED_VERSION.save(info.latestVersion),
                    true // Dismiss dialog on Neutral button click.
            );

            Utils.showDialog(activity, dialogPair.first);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to show patches update dialog", ex);
        }
    }

    /**
     * Section titles are bold with a thin, faint rule under them, like the headings of the
     * release notes on GitHub.
     */
    private static int appendSection(SpannableStringBuilder message, String title, List<String> changes,
                                     int remaining, int bulletIndent, int ruleColor) {
        if (changes.isEmpty() || remaining <= 0) return remaining;

        message.append("\n\n");
        final int titleStart = message.length();
        message.append(title);
        final int titleEnd = message.length();
        message.setSpan(new StyleSpan(Typeface.BOLD), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.setSpan(new RelativeSizeSpan(HEADING_TEXT_SCALE), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        message.setSpan(new RuleSpan(ruleColor), titleStart, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        // A short gap between the rule and the changes.
        final int gapStart = message.length();
        message.append("\n ");
        message.setSpan(new RelativeSizeSpan(HEADING_GAP_TEXT_SCALE), gapStart, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

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

            message.setSpan(new LeadingMarginSpan.Standard(0, bulletIndent), itemStart, message.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return remaining;
    }

    /**
     * Draws a thin line along the bottom of a heading line, across the full width.
     */
    @SuppressWarnings("ClassCanBeRecord")
    private static final class RuleSpan implements LineBackgroundSpan {
        private final int color;

        RuleSpan(int color) {
            this.color = color;
        }

        @Override
        public void drawBackground(Canvas canvas, Paint paint, int left, int right, int top,
                                   int baseline, int bottom, @NonNull CharSequence text, int start, int end,
                                   int lineNumber) {
            final int oldColor = paint.getColor();
            final Paint.Style oldStyle = paint.getStyle();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(left, bottom - Dim.dp1, right, bottom, paint);
            paint.setColor(oldColor);
            paint.setStyle(oldStyle);
        }
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

    /**
     * @return The major, minor, patch and dev release numbers of versions such as 2.1.0 and 2.2.0-dev.1,
     *         or null if the version has a different format.
     *         Stable releases use {@link Integer#MAX_VALUE} as the dev release number,
     *         so a stable release is newer than its dev releases.
     */
    @Nullable
    static int[] parseVersion(String version) {
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.matches()) return null;

        String devNumber = matcher.group(4);
        //noinspection ConstantConditions
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                devNumber == null ? Integer.MAX_VALUE : Integer.parseInt(devNumber)
        };
    }

    static int compareVersions(int[] a, int[] b) {
        for (int i = 0, length = a.length; i < length; i++) {
            if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
        }
        return 0;
    }
}
