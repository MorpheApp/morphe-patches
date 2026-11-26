/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference.about;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Opens a dialog showing the patches version, the official links and the people behind Morphe.
 */
@SuppressWarnings({"unused", "deprecation"})
public class MorpheAboutPreference extends Preference {

    static class WebLink {
        /**
         * Localized name replacements for links.
         */
        private static final Map<String, String> webLinkNameReplacements = new HashMap<>() {
            {
                // Handle no string resources available, and use the original untranslated tet.
                var websiteStringKey = "morphe_settings_about_links_website";
                if (ResourceUtils.getIdentifier(ResourceType.STRING, websiteStringKey) != 0) {
                    put("website", websiteStringKey);
                    put("donate", "morphe_settings_about_links_donate");
                    put("translations", "morphe_settings_about_links_translations");
                }
            }
        };

        final boolean preferred;
        /**
         * Whether the link asks for a donation, which leads the other links on a row of its own.
         */
        final boolean donation;
        final String name;
        final String url;

        WebLink(JSONObject json, boolean donation) throws JSONException {
            this(json.getBoolean("preferred"), donation, json.getString("name"), json.getString("url"));
        }

        WebLink(String name, String url) {
            this(true, false, name, url);
        }

        private WebLink(boolean preferred, boolean donation, String name, String url) {
            this.preferred = preferred;
            this.donation = donation;
            String localizedNameKey = webLinkNameReplacements.get(name.toLowerCase(Locale.US));
            this.name = (localizedNameKey != null) ? str(localizedNameKey) : name;
            this.url = url;
        }

        @NonNull
        @Override
        public String toString() {
            return "WebLink{" +
                    "preferred=" + preferred +
                    ", donation=" + donation +
                    ", name='" + name + '\'' +
                    ", url='" + url + '\'' +
                    '}';
        }
    }

    public static void showVancedAsPastContributor(boolean includeVanced) {
        MorpheCreditsDialog.showVancedAsPastContributor = includeVanced;
    }

    // Dummy urls. Tapping one opens the list of people it stands for rather than a page.
    static final String CREDITS_LINK_PLACEHOLDER_URL = "https://morphe.software/credits/";
    static final String SUPPORTERS_LINK_PLACEHOLDER_URL = "https://morphe.software/supporters/";
    // Opens Morphe itself, to patch the app again.
    static final String MANAGER_LINK_PLACEHOLDER_URL = "https://morphe.software/manager/";

    private static String useNonBreakingHyphens(String text) {
        // Replace any dashes with non-breaking dashes, so the English text 'pre-release'
        // and the dev release number does not break and cover two lines.
        return text.replace("-", "&#8209;"); // #8209 = non-breaking hyphen.
    }

    private static void appendTile(StringBuilder html, String url, String title, String icon, boolean wide) {
        html.append("<a href=\"").append(AboutDialogStyle.escapeHtml(url))
                .append(wide ? "\" class=\"tile wide\">" : "\" class=\"tile\">")
                .append(icon)
                .append("<span class=\"tile-title\">").append(title).append("</span>")
                .append("</a>");
    }

    /**
     * Apps that do not support bundling resources must override this.
     *
     * @return A localized string to display for the key.
     */
    protected String getString(String key, Object... args) {
        return str(key, args);
    }

    private String createDialogHtml(List<WebLink> aboutLinks, @Nullable String currentVersion) {
        StringBuilder html = new StringBuilder(AboutDialogStyle.documentStart(getContext()))
                .append(AboutDialogStyle.pageStart(true));

        html.append("<div class=\"dialog-header\"><div class=\"about-header\">");

        html.append("<div class=\"app-logo\">").append(AboutDialogStyle.LOGO).append("</div>");

        // Names which part of Morphe this is, as Morphe itself names the manager.
        html.append("<div><div class=\"app-name\">Morphe</div><div class=\"app-component\">")
                .append(getString("morphe_settings_about_component"))
                .append("</div></div></div>");

        String appPatchesVersion = Utils.getPatchesReleaseVersion();

        html.append("<div class=\"version-card\"><div class=\"version-label-row\"><span class=\"version-label\">")
                .append(getString("morphe_settings_about_version"))
                .append("</span>");
        if (Utils.isPreReleasePatches()) {
            html.append("<span class=\"badge\">")
                    .append(useNonBreakingHyphens(getString("morphe_settings_about_prerelease")))
                    .append("</span>");
        }
        html.append("</div><div class=\"version\">")
                .append(useNonBreakingHyphens(AboutDialogStyle.isolateLtr(appPatchesVersion)))
                .append("</div>");

        // Nothing stands here while the latest version is unknown: that is not the same as up to date.
        if (currentVersion != null) {
            if (appPatchesVersion.equalsIgnoreCase(currentVersion)) {
                html.append("<div class=\"version-status\">")
                        .append(getString("morphe_settings_about_links_dev_header_up_to_date"))
                        .append("</div>");
            } else {
                html.append("<div class=\"version-status update\"><div class=\"version-status-text\">")
                        .append(useNonBreakingHyphens(getString("morphe_settings_about_update_available",
                                AboutDialogStyle.isolateLtr(currentVersion))))
                        .append("<div class=\"version-hint\">")
                        .append(getString("morphe_settings_about_update_hint"))
                        .append("</div></div><a href=\"").append(MANAGER_LINK_PLACEHOLDER_URL)
                        .append("\" class=\"update-button\">")
                        .append(getString("morphe_patches_update_notice_dialog_update"))
                        .append("</a></div>");
            }
        }

        html.append("</div></div>"); // end .version-card and .dialog-header

        html.append("<div class=\"section\"><div class=\"group-label\">")
                .append(getString("morphe_settings_about_links_header"))
                .append("</div><div class=\"tiles\">");

        for (WebLink link : aboutLinks) {
            appendTile(html, link.url, link.name,
                    link.donation ? AboutDialogStyle.donateIcon() : AboutDialogStyle.linkIcon(link.url),
                    link.donation);
        }

        // The people behind the project close the same grid the links are in.
        appendTile(html, CREDITS_LINK_PLACEHOLDER_URL, getString("morphe_settings_about_contributors"),
                AboutDialogStyle.linkIcon(CREDITS_LINK_PLACEHOLDER_URL), false);
        appendTile(html, SUPPORTERS_LINK_PLACEHOLDER_URL, getString("morphe_settings_about_supporters"),
                AboutDialogStyle.linkIcon(SUPPORTERS_LINK_PLACEHOLDER_URL), false);
        html.append("</div></div>")
                .append(AboutDialogStyle.pageEnd(getString("morphe_settings_about_close"), null))
                .append(AboutDialogStyle.DOCUMENT_END);

        return html.toString();
    }

    {
        setOnPreferenceClickListener(pref -> {
            Context context = pref.getContext();

            // Show a progress spinner if the social links are not fetched yet.
            if (Utils.isNetworkConnected() && !AboutRoutes.hasFetchedLinks() && !AboutRoutes.hasFetchedPatchersVersion()) {
                // Show a progress spinner, but only if the api fetch takes more than a half a second.
                final long delayToShowProgressSpinner = 500;
                ProgressDialog progress = new ProgressDialog(getContext());
                progress.setProgressStyle(ProgressDialog.STYLE_SPINNER);

                Handler handler = new Handler(Looper.getMainLooper());
                Runnable showDialogRunnable = progress::show;
                handler.postDelayed(showDialogRunnable, delayToShowProgressSpinner);

                Utils.runOnBackgroundThread(() ->
                        fetchLinksAndShowDialog(context, handler, showDialogRunnable, progress));
            } else {
                // No network call required and can run now.
                fetchLinksAndShowDialog(context, null, null, null);
            }

            return false;
        });
    }

    private void fetchLinksAndShowDialog(Context context,
                                         @Nullable Handler handler,
                                         Runnable showDialogRunnable,
                                         @Nullable ProgressDialog progress) {
        List<WebLink> links = AboutRoutes.fetchAboutLinks();
        String currentVersion = AboutRoutes.getLatestPatchesVersion();
        String htmlDialog = createDialogHtml(links, currentVersion);

        // Enable to randomly force a delay to debug the spinner logic.
        final boolean debugSpinnerDelayLogic = false;
        //noinspection ConstantConditions
        if (debugSpinnerDelayLogic && handler != null && Math.random() < 0.5f) {
            Utils.doNothingForDuration((long) (Math.random() * 4000));
        }

        Utils.runOnMainThreadNowOrLater(() -> {
            if (handler != null) {
                handler.removeCallbacks(showDialogRunnable);
            }

            // Don't continue if the activity is done. To test this tap the
            // dialog and immediately press back before the dialog can show.
            if (context instanceof Activity activity) {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    Logger.printDebug(() -> "Not showing about dialog, activity is closed");
                    return;
                }
            }

            if (progress != null && progress.isShowing()) {
                progress.dismiss();
            }
            new AboutWebViewDialog(getContext(), htmlDialog, false).show();
        });
    }

    public MorpheAboutPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public MorpheAboutPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public MorpheAboutPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public MorpheAboutPreference(Context context) {
        super(context);
    }
}
