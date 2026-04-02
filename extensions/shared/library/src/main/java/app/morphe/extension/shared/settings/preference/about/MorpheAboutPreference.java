/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
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

    /**
     * Returns an SVG icon string based on the link URL pattern.
     * Matching by URL avoids issues with localized link names.
     */
    private static String getLinkIcon(String url) {
        // Globe - website / generic
        final String iconGlobe =
                "<svg viewBox='0 0 16 16'><circle cx='8' cy='8' r='6'/>" +
                        "<ellipse cx='8' cy='8' rx='2.8' ry='6'/>" +
                        "<line x1='2' y1='8' x2='14' y2='8'/></svg>";
        // Heart - donate
        final String iconHeart =
                "<svg viewBox='0 0 16 16'><path d='M8 13s-5-3.5-5-7a3 3 0 0 1 5-2.24A3 3 0 0 1 13 6c0 3.5-5 7-5 7z'/></svg>";
        // Bubble + A - translations
        final String iconTranslate =
                "<svg viewBox='0 0 16 16'>" +
                        "<path d='M2 2.5A1.5 1.5 0 0 1 3.5 1h9A1.5 1.5 0 0 1 14 2.5v6A1.5 1.5 0 0 1 12.5 10H9l-3 3v-3H3.5A1.5 1.5 0 0 1 2 8.5v-6z'/>" +
                        "<path d='M5.5 7.5L7.5 3l2 4.5M6.2 6h2.6' stroke-width='1.3'/>" +
                        "</svg>";
        // Person - credits
        final String iconPerson =
                "<svg viewBox='0 0 16 16'><circle cx='8' cy='5' r='2.5'/>" +
                        "<path d='M3 13c0-2.76 2.24-5 5-5s5 2.24 5 5'/></svg>";
        // GitHub mark
        final String iconGitHub =
                "<svg viewBox='0 0 16 16'><path d='M8 1a7 7 0 0 0-2.21 13.64c.35.06.48-.15.48-.34v-1.2C4.07 13.54 3.67 12 3.67 12c-.32-.81-.78-1.02-.78-1.02-.63-.43.05-.42.05-.42.7.05 1.07.72 1.07.72.62 1.06 1.63.75 2.03.58.06-.45.24-.75.44-.92C5 10.79 3.37 10.17 3.37 7.5c0-.75.27-1.36.71-1.84-.07-.18-.31-.87.07-1.82 0 0 .58-.18 1.9.71A6.6 6.6 0 0 1 8 4.18c.59 0 1.18.08 1.73.23 1.31-.89 1.9-.71 1.9-.71.38.95.14 1.64.07 1.82.44.48.71 1.09.71 1.84 0 2.68-1.63 3.28-3.19 3.45.25.22.47.65.47 1.31v1.95c0 .19.13.4.48.34A7 7 0 0 0 8 1z'/></svg>";
        // X / Twitter
        final String iconX =
                "<svg viewBox='0 0 16 16'><path d='M2 2l5 5.5L2 13h2l3.5-4 3.5 4h2L8.5 7.5 13 2h-2L8 5.5 5 2z'/></svg>";
        // Reddit - alien head
        final String iconReddit =
                "<svg viewBox='0 0 16 16'>" +
                        "<path d='M3 10.5C3 7.46 5.24 5 8 5s5 2.46 5 5.5'/>" +
                        "<path d='M3 10.5c0 1.93 2.24 3.5 5 3.5s5-1.57 5-3.5'/>" +
                        "<circle cx='5.8' cy='9.5' r='.9' fill='currentColor' stroke='none'/>" +
                        "<circle cx='10.2' cy='9.5' r='.9' fill='currentColor' stroke='none'/>" +
                        "<path d='M6 11.8c.5.7 3.5.7 4 0'/>" +
                        "<path d='M8 5c.3-1.5 1.5-2 3-1.5'/>" +
                        "<circle cx='11.5' cy='3.8' r='.9' fill='currentColor' stroke='none'/>" +
                        "</svg>";
        // External link - fallback
        final String iconExternal =
                "<svg viewBox='0 0 16 16'><path d='M6 3H3v10h10v-3M9 2h5v5M14 2l-6 6'/></svg>";

        if (url == null) return iconExternal;
        String u = url.toLowerCase(Locale.US);
        if (u.contains("github.com")) return iconGitHub;
        if (u.contains("reddit.com")) return iconReddit;
        if (u.contains("twitter.com") || u.contains("x.com")) return iconX;
        if (u.contains("crowdin") || u.contains("translate")) return iconTranslate;
        if (u.contains("donate") || u.contains("donat")) return iconHeart;
        if (u.equals("https://credits/")) return iconPerson;
        return iconGlobe;
    }

    // Dummy url
    static final String CREDITS_LINK_PLACEHOLDER_URL = "https://morphe.software/credits/";

    static final WebLink CREDITS_LINK = new WebLink("credits", null, CREDITS_LINK_PLACEHOLDER_URL);

    private static String useNonBreakingHyphens(String text) {
        // Replace any dashes with non-breaking dashes, so the English text 'pre-release'
        // and the dev release number does not break and cover two lines.
        return text.replace("-", "&#8209;"); // #8209 = non breaking hyphen.
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
        final boolean isNetworkConnected = Utils.isNetworkConnected();

        // Get theme colors.
        String foregroundColorHex = Utils.getColorHexString(Utils.getAppForegroundColor());
        String backgroundColorHex = Utils.getColorHexString(Utils.getDialogBackgroundColor());

        // Morphe brand colors from logo.
        String morpheBlue = "#1E5AA8";
        String morpheTeal = "#00AFAE";

        StringBuilder html = new StringBuilder(String.format("""
                         <html>
                         <head>
                             <meta name="viewport" content="width=device-width, initial-scale=1.0">
                         </head>
                         <body>
                         <style>
                             * {
                                 margin: 0;
                                 padding: 0;
                                 box-sizing: border-box;
                             }
                             body {
                                 background: %s;
                                 color: %s;
                                 font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                 padding: 0;
                             }
                             /* Header */
                             .about-header {
                                 padding: 28px 20px 20px;
                                 text-align: center;
                                 border-bottom: 1px solid rgba(128, 128, 128, 0.12);
                             }
                             .logo-container {
                                 margin: 0 auto 14px;
                                 width: 72px;
                                 height: 72px;
                                 border-radius: 18px;
                                 background: linear-gradient(135deg, %s 0%%, %s 100%%);
                                 padding: 2px;
                                 display: inline-block;
                             }
                             .logo-inner {
                                 width: 100%%;
                                 height: 100%%;
                                 border-radius: 16px;
                                 background: #EEEEEE;
                                 display: flex;
                                 align-items: center;
                                 justify-content: center;
                                 overflow: hidden;
                             }
                             img {
                                 width: 100%%;
                                 height: 100%%;
                                 object-fit: contain;
                             }
                             .app-name {
                                 font-size: 18px;
                                 font-weight: 600;
                                 color: %s;
                                 margin-bottom: 10px;
                             }
                             /* Info card - version + dev-note, centered, no icon */
                             .info-card {
                                 text-align: center;
                                 margin-bottom: 8px;
                                 padding: 10px 14px;
                                 border-radius: 10px;
                                 background: rgba(30, 90, 168, 0.06);
                                 border: 1px solid rgba(30, 90, 168, 0.15);
                             }
                             .info-card:last-child {
                                 margin-bottom: 0;
                             }
                             .info-card h3 {
                                 font-size: 13px;
                                 font-weight: 600;
                                 color: %s;
                                 margin-bottom: 2px;
                             }
                             .info-card p {
                                 margin: 0;
                                 font-size: 12px;
                                 color: %s;
                                 opacity: 0.75;
                                 line-height: 1.4;
                             }
                             /* Links */
                             .links-section {
                                 padding: 16px;
                             }
                             .section-label {
                                 font-size: 11px;
                                 font-weight: 600;
                                 color: %s;
                                 opacity: 0.5;
                                 text-transform: uppercase;
                                 letter-spacing: 0.07em;
                                 margin-bottom: 8px;
                                 padding: 0 4px;
                             }
                             .link-button {
                                 display: flex;
                                 align-items: center;
                                 gap: 10px;
                                 text-decoration: none;
                                 color: %s;
                                 background: linear-gradient(135deg, rgba(30, 90, 168, 0.07) 0%%, rgba(0, 175, 174, 0.07) 100%%);
                                 border: 1px solid rgba(30, 90, 168, 0.2);
                                 border-radius: 12px;
                                 padding: 11px 14px;
                                 margin-bottom: 6px;
                                 font-size: 14px;
                                 font-weight: 500;
                                 -webkit-tap-highlight-color: transparent;
                                 -webkit-touch-callout: none;
                                 -webkit-user-select: none;
                                 user-select: none;
                             }
                             .link-button:last-child {
                                 margin-bottom: 0;
                             }
                             .link-button:active {
                                 background: linear-gradient(135deg, rgba(30, 90, 168, 0.14) 0%%, rgba(0, 175, 174, 0.14) 100%%);
                                 border-color: rgba(30, 90, 168, 0.35);
                             }
                             .link-icon {
                                 width: 28px;
                                 height: 28px;
                                 border-radius: 8px;
                                 background: linear-gradient(135deg, rgba(30, 90, 168, 0.15) 0%%, rgba(0, 175, 174, 0.15) 100%%);
                                 display: flex;
                                 align-items: center;
                                 justify-content: center;
                                 flex-shrink: 0;
                                 font-size: 15px;
                             }
                             .link-icon svg {
                                 width: 16px;
                                 height: 16px;
                                 fill: none;
                                 stroke: %s;
                                 stroke-width: 1.6;
                                 stroke-linecap: round;
                                 stroke-linejoin: round;
                             }
                             .link-label {
                                 flex: 1;
                             }
                             .link-chevron {
                                 font-size: 24px;
                                 opacity: 0.3;
                                 line-height: 1;
                             }
                         </style>
                        """, backgroundColorHex, foregroundColorHex,
                morpheBlue, morpheTeal,
                foregroundColorHex,
                morpheBlue, foregroundColorHex,
                foregroundColorHex, foregroundColorHex, foregroundColorHex
        ));

        // Header section.
        html.append("<div class=\"about-header\">");

        // Logo with Morphe gradient border.
        if (isNetworkConnected) {
            html.append(String.format("""
                    <div class="logo-container">
                        <div class="logo-inner">
                            <img src="%s" onerror="this.parentElement.parentElement.style.display='none';" />
                        </div>
                    </div>
                    """, AboutRoutes.aboutLogoUrl));
        }

        // App name.
        html.append("<div class=\"app-name\">Morphe</div>");

        String appPatchesVersion = Utils.getPatchesReleaseVersion();

        // Version info card.
        boolean isUpToDate = currentVersion == null || appPatchesVersion.equalsIgnoreCase(currentVersion);
        String versionTitle = isUpToDate
                ? getString("morphe_settings_about_links_dev_header_up_to_date")
                : getString("morphe_settings_about_links_dev_header_update_available");
        html.append(String.format("""
                <div class="info-card">
                    <h3>%s</h3>
                    <p>%s</p>
                </div>
                """,
                useNonBreakingHyphens(versionTitle),
                useNonBreakingHyphens(isUpToDate
                        ? getString("morphe_settings_about_links_body_version_current", appPatchesVersion)
                        : getString("morphe_settings_about_links_body_version_outdated", appPatchesVersion, currentVersion)
                )
        ));

        // Dev note card.
        if (Utils.isPreReleasePatches()) {
            html.append(String.format("""
                            <div class="info-card">
                                <h3>%s</h3>
                                <p>%s</p>
                            </div>
                            """, useNonBreakingHyphens(getString("morphe_settings_about_links_dev_header")),
                    getString("morphe_settings_about_links_dev_body")
            ));
        }

        html.append("</div>"); // end .about-header

        // Links section.
        html.append(String.format("""
                <div class="links-section">
                    <div class="section-label">%s</div>
                """, getString("morphe_settings_about_links_header")));

        // Link buttons with per-URL SVG icons.
        for (WebLink link : aboutLinks) {
            String icon = getLinkIcon(link.url);
            html.append("<a href=\"").append(link.url).append("\" class=\"link-button\">")
                    .append("<span class=\"link-icon\">").append(icon).append("</span>")
                    .append("<span class=\"link-label\">").append(link.name).append("</span>")
                    .append("<span class=\"link-chevron\">&#x203A;</span>")
                    .append("</a>");
        }

        html.append("""
                </div>
                </body>
                </html>
                """);

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
