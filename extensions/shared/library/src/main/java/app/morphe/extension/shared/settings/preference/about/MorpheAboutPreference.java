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
 * Opens a dialog showing official links.
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
                    put("credits", "morphe_settings_about_links_credits");
                }
            }
        };

        final boolean preferred;
        final String name;
        @Nullable
        final String subText;
        final String url;

        WebLink(JSONObject json) throws JSONException {
            this(json.getBoolean("preferred"),
                    json.getString("name"),
                    null,
                    json.getString("url")
            );
        }

        WebLink(String name, @Nullable String subText, String url) {
            this(false, name, subText, url);
        }

        WebLink(boolean preferred, String name, @Nullable String subText, String url) {
            this.preferred = preferred;
            String localizedNameKey = webLinkNameReplacements.get(name.toLowerCase(Locale.US));
            this.name = (localizedNameKey != null) ? str(localizedNameKey) : name;
            this.subText = subText;
            this.url = url;
        }

        @NonNull
        @Override
        public String toString() {
            return "WebLink{" +
                    "preferred=" + preferred +
                    ", name='" + name + '\'' +
                    ", subText='" + subText + '\'' +
                    ", url='" + url + '\'' +
                    '}';
        }
    }

    public static void showVancedAsPastContributor(boolean includeVanced) {
        MorpheCreditsDialog.showVancedAsPastContributor = includeVanced;
    }

    // Dummy url
    static final String CREDITS_LINK_PLACEHOLDER_URL = "https://morphe.software/credits/";

    static final WebLink CREDITS_LINK = new WebLink("credits", null, CREDITS_LINK_PLACEHOLDER_URL);

    private static String useNonBreakingHyphens(String text) {
        // Replace any dashes with non-breaking dashes, so the English text 'pre-release'
        // and the dev release number does not break and cover two lines.
        return text.replace("-", "&#8209;"); // #8209 = non-breaking hyphen.
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
        StringBuilder html = new StringBuilder(AboutDialogStyle.documentStart());

        html.append("<div class=\"dialog-header\">");

        // The logo is fetched over the network, so the container is skipped entirely when offline.
        if (Utils.isNetworkConnected()) {
            html.append(String.format("""
                    <div class="app-logo">
                        <div class="app-logo-inner">
                            <img src="%s" onerror="this.parentElement.parentElement.style.display='none';" />
                        </div>
                    </div>
                    """, AboutRoutes.aboutLogoUrl));
        }

        html.append("<div class=\"app-name\">Morphe</div>");

        String appPatchesVersion = Utils.getPatchesReleaseVersion();
        final boolean isUpToDate = currentVersion == null || appPatchesVersion.equalsIgnoreCase(currentVersion);

        // Version status card.
        html.append(String.format("""
                        <div class="info-card">
                            <h3>%s</h3>
                            <p>%s</p>
                        </div>
                        """,
                useNonBreakingHyphens(isUpToDate
                        ? getString("morphe_settings_about_links_dev_header_up_to_date")
                        : getString("morphe_settings_about_links_dev_header_update_available")),
                useNonBreakingHyphens(isUpToDate
                        ? getString("morphe_settings_about_links_body_version_current",
                                AboutDialogStyle.isolateLtr(appPatchesVersion))
                        : getString("morphe_settings_about_links_body_version_outdated",
                                AboutDialogStyle.isolateLtr(appPatchesVersion),
                                AboutDialogStyle.isolateLtr(currentVersion)))
        ));

        // Dev note card.
        if (Utils.isPreReleasePatches()) {
            html.append(String.format("""
                            <div class="info-card">
                                <h3>%s</h3>
                                <p>%s</p>
                            </div>
                            """,
                    useNonBreakingHyphens(getString("morphe_settings_about_links_dev_header")),
                    getString("morphe_settings_about_links_dev_body")
            ));
        }

        html.append("</div>"); // end .dialog-header

        // Links section.
        html.append("<div class=\"section\">")
                .append(AboutDialogStyle.sectionTitle(getString("morphe_settings_about_links_header")))
                .append("<div class=\"settings-group\">");

        for (WebLink link : aboutLinks) {
            html.append("<a href=\"").append(link.url).append("\" class=\"settings-item\">")
                    .append("<span class=\"item-icon\">")
                    .append(AboutDialogStyle.linkIcon(link.url))
                    .append("</span>")
                    .append("<div class=\"item-text\"><div class=\"item-title\">")
                    .append(link.name)
                    .append("</div></div>")
                    .append(AboutDialogStyle.chevron())
                    .append("</a>");
        }

        html.append("</div></div>").append(AboutDialogStyle.DOCUMENT_END);

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
            new AboutWebViewDialog(getContext(), htmlDialog).show();
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
