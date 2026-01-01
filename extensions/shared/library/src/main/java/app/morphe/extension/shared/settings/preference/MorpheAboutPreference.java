package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.requests.Route.Method.GET;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.shared.ui.Dim;

/**
 * Opens a dialog showing official links.
 */
@SuppressWarnings({"unused", "deprecation"})
public class MorpheAboutPreference extends Preference {

    private static String useNonBreakingHyphens(String text) {
        // Replace any dashes with non breaking dashes, so the English text 'pre-release'
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

    private String createDialogHtml(WebLink[] aboutLinks, @Nullable String currentVersion) {
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
                                 padding: 24px;
                                 text-align: center;
                             }
                             .logo-container {
                                 margin: 0 auto 24px;
                                 width: 120px;
                                 height: 120px;
                                 border-radius: 28px;
                                 background: linear-gradient(135deg, %s 0%%, %s 100%%);
                                 padding: 3px;
                                 display: inline-block;
                                 box-shadow: 0 8px 24px rgba(30, 90, 168, 0.2);
                             }
                             .logo-inner {
                                 width: 100%%;
                                 height: 100%%;
                                 border-radius: 26px;
                                 background: %s;
                                 display: flex;
                                 align-items: center;
                                 justify-content: center;
                                 overflow: hidden;
                                 padding: 0px;
                             }
                             .logo-bg {
                                 width: 100%%;
                                 height: 100%%;
                                 border-radius: 24px;
                                 background: #EEEEEE;
                                 display: flex;
                                 align-items: center;
                                 justify-content: center;
                                 padding: 12px;
                             }
                             img {
                                 width: 100%%;
                                 height: 100%%;
                                 object-fit: contain;
                             }
                             p {
                                 font-size: 14px;
                                 line-height: 1.6;
                                 margin-bottom: 16px;
                                 opacity: 0.8;
                             }
                             .dev-note {
                                 background: rgba(30, 90, 168, 0.08);
                                 border: 1px solid rgba(30, 90, 168, 0.2);
                                 border-radius: 12px;
                                 padding: 12px 16px;
                                 margin: 16px 0;
                             }
                             .dev-note h3 {
                                 font-size: 16px;
                                 font-weight: 600;
                                 margin-bottom: 6px;
                                 opacity: 0.9;
                             }
                             .dev-note p {
                                 margin: 0;
                                 font-size: 13px;
                                 opacity: 0.8;
                             }
                             .links-section {
                                 margin-top: 32px;
                             }
                             h2 {
                                 font-size: 18px;
                                 font-weight: 600;
                                 margin-bottom: 16px;
                                 opacity: 0.9;
                             }
                             .link-button {
                                 display: block;
                                 text-decoration: none;
                                 color: %s;
                                 background: linear-gradient(135deg, rgba(30, 90, 168, 0.08) 0%%, rgba(0, 175, 174, 0.08) 100%%);
                                 border: 1px solid rgba(30, 90, 168, 0.2);
                                 border-radius: 12px;
                                 padding: 14px 20px;
                                 margin-bottom: 10px;
                                 font-size: 15px;
                                 font-weight: 500;
                                 transition: all 0.2s ease;
                                 position: relative;
                                 overflow: hidden;
                                 -webkit-tap-highlight-color: transparent;
                                 -webkit-touch-callout: none;
                                 -webkit-user-select: none;
                                 user-select: none;
                             }
                             .link-button::after {
                                 content: '';
                                 position: absolute;
                                 top: 50%%;
                                 left: 50%%;
                                 width: 0;
                                 height: 0;
                                 border-radius: 50%%;
                                 background: rgba(30, 90, 168, 0.3);
                                 transform: translate(-50%%, -50%%);
                                 transition: width 0.3s, height 0.3s;
                                 pointer-events: none;
                             }
                             .link-button:active {
                                 transform: scale(0.98);
                                 background: linear-gradient(135deg, rgba(30, 90, 168, 0.15) 0%%, rgba(0, 175, 174, 0.15) 100%%);
                                 border-color: rgba(30, 90, 168, 0.4);
                                 outline: none;
                             }
                             .link-button:active::after {
                                 width: 300px;
                                 height: 300px;
                             }
                             .link-button:focus {
                                 outline: none;
                             }
                         </style>
                        """, backgroundColorHex, foregroundColorHex,
                morpheBlue, morpheTeal,
                backgroundColorHex,
                morpheBlue, morpheTeal,
                foregroundColorHex
        ));

        // Logo with Morphe gradient border.
        if (isNetworkConnected) {
            html.append(String.format("""
                    <div class="logo-container">
                        <div class="logo-inner">
                            <div class="logo-bg">
                                <img src="%s" onerror="this.parentElement.parentElement.parentElement.style.display='none';" />
                            </div>
                        </div>
                    </div>
                    """, AboutRoutes.aboutLogoUrl));
        }

        String appPatchesVersion = Utils.getPatchesReleaseVersion();

        // Description.
        html.append("<p>").append(
                useNonBreakingHyphens(currentVersion == null || appPatchesVersion.equalsIgnoreCase(currentVersion)
                        ? getString("morphe_settings_about_links_body_version_current", appPatchesVersion)
                        : getString("morphe_settings_about_links_body_version_outdated", appPatchesVersion, currentVersion)
                )
        ).append("</p>");

        // Dev note banner.
        if (Utils.isPreReleasePatches()) {
            html.append(String.format("""
                            <div class="dev-note">
                                <h3>%s</h3>
                                <p>%s</p>
                            </div>
                            """, useNonBreakingHyphens(getString("morphe_settings_about_links_dev_header")),
                    getString("morphe_settings_about_links_dev_body")
            ));
        }

        // Links section.
        html.append(String.format("""
                <div class="links-section">
                    <h2>%s</h2>
                """, getString("morphe_settings_about_links_header")));

        // Link buttons.
        for (WebLink link : aboutLinks) {
            html.append("<a href=\"").append(link.url).append("\" class=\"link-button\">")
                    .append(link.name).append("</a>");
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
        WebLink[] links = AboutRoutes.fetchAboutLinks();
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
            // about dialog and immediately press back before the dialog can show.
            if (context instanceof Activity activity) {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    Logger.printDebug(() -> "Not showing about dialog, activity is closed");
                    return;
                }
            }

            if (progress != null && progress.isShowing()) {
                progress.dismiss();
            }
            new WebViewDialog(getContext(), htmlDialog).show();
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

/**
 * Displays html content as a dialog. Any links a user taps on are opened in an external browser.
 */
class WebViewDialog extends Dialog {

    private final String htmlContent;

    public WebViewDialog(@NonNull Context context, @NonNull String htmlContent) {
        super(context);
        this.htmlContent = htmlContent;
    }

    // JS required to hide any broken images. No remote javascript is ever loaded.
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE); // Remove default title bar.

        // Create main layout.
        LinearLayout mainLayout = new LinearLayout(getContext());
        mainLayout.setOrientation(LinearLayout.VERTICAL);

        mainLayout.setPadding(Dim.dp10, Dim.dp10, Dim.dp10, Dim.dp10);
        // Set rounded rectangle background.
        ShapeDrawable mainBackground = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(28), null, null));
        mainBackground.getPaint().setColor(Utils.getDialogBackgroundColor());
        mainLayout.setBackground(mainBackground);

        // Create WebView.
        WebView webView = new WebView(getContext());
        webView.setVerticalScrollBarEnabled(false); // Disable the vertical scrollbar.
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.setWebViewClient(new OpenLinksExternallyWebClient());
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "utf-8", null);

        // Add WebView to layout.
        mainLayout.addView(webView);

        setContentView(mainLayout);

        // Set dialog window attributes.
        Window window = getWindow();
        if (window != null) {
            Utils.setDialogWindowParameters(window, Gravity.CENTER, 0, 90, false);
        }
    }

    private class OpenLinksExternallyWebClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                getContext().startActivity(intent);
            } catch (Exception ex) {
                Logger.printException(() -> "Open link failure", ex);
            }
            // Dismiss the about dialog using a delay,
            // otherwise without a delay the UI looks hectic with the dialog dismissing
            // to show the settings while simultaneously a web browser is opening.
            Utils.runOnMainThreadDelayed(WebViewDialog.this::dismiss, 500);
            return true;
        }
    }
}

class WebLink {

    /**
     * Localized name replacements for links.
     */
    private static final Map<String, String> webLinkNameReplacements = new HashMap<>() {
        {
            put("website", "morphe_settings_about_links_website");
            put("donate", "morphe_settings_about_links_donate");
            put("translations", "morphe_settings_about_links_translations");
        }
    };

    final boolean preferred;
    final String name;
    final String url;

    WebLink(JSONObject json) throws JSONException {
        this(json.getBoolean("preferred"),
                json.getString("name"),
                json.getString("url")
        );
    }

    WebLink(boolean preferred, String name, String url) {
        this.preferred = preferred;
        this.url = url;
        String localizedNameKey = webLinkNameReplacements.get(name.toLowerCase(Locale.US));
        this.name = (localizedNameKey != null) ? str(localizedNameKey) : name;
    }

    @NonNull
    @Override
    public String toString() {
        return "WebLink{" +
                "preferred=" + preferred +
                ", name='" + name + '\'' +
                ", url='" + url + '\'' +
                '}';
    }
}

class AboutRoutes {
    /**
     * Backup icon url if the API call fails.
     */
    public static volatile String aboutLogoUrl = "https://morphe.software/favicon.svg";

    /**
     * Links to use if fetch links api call fails.
     */
    private static final WebLink[] NO_CONNECTION_STATIC_LINKS = {
            new WebLink(true, "Website", "https://morphe.software")
    };

    private static final String API_URL = "https://api.morphe.software/v2";
    private static final Route.CompiledRoute API_ROUTE_ABOUT = new Route(GET, "/about").compile();
    private static final Route.CompiledRoute API_ROUTE_PATCHES = new Route(GET,
            (Utils.isPreReleasePatches() ? "/patches/prerelease" : "/patches")
    ).compile();

    @Nullable
    private static volatile String latestPatchesVersion;
    private static volatile long latestPatchesVersionLastCheckedTime;

    static boolean hasFetchedPatchersVersion() {
        final long updateCheckFrequency = 10 * 60 * 1000; // 10 minutes.
        final long now = System.currentTimeMillis();

        return latestPatchesVersion != null && (now - latestPatchesVersionLastCheckedTime) < updateCheckFrequency;
    }

    @Nullable
    static String getLatestPatchesVersion() {
        String version = latestPatchesVersion;
        if (version != null) return version;

        if (!Utils.isNetworkConnected()) return null;

        try {
            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(API_URL, API_ROUTE_PATCHES);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            Logger.printDebug(() -> "Fetching latest patches version links from: " + connection.getURL());

            // Do not show an exception toast if the server is down
            final int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "Failed to get patches bundle. Response code: " + responseCode);
                return null;
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
            version = json.getString("version");
            if (version.startsWith("v")) {
                version = version.substring(1);
            }
            latestPatchesVersion = version;
            latestPatchesVersionLastCheckedTime = System.currentTimeMillis();

            return version;
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Could not fetch patches version", ex); // No toast.
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not parse about information", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get patches version", ex);
        }

        return null;
    }

    @Nullable
    private static volatile WebLink[] fetchedLinks;

    static boolean hasFetchedLinks() {
        return fetchedLinks != null;
    }

    static WebLink[] fetchAboutLinks() {
        try {
            if (hasFetchedLinks()) return fetchedLinks;

            // Check if there is no internet connection.
            if (!Utils.isNetworkConnected()) return NO_CONNECTION_STATIC_LINKS;

            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(API_URL, API_ROUTE_ABOUT);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            Logger.printDebug(() -> "Fetching social links from: " + connection.getURL());


            // Do not show an exception toast if the server is down
            final int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "Failed to get about information. Response code: " + responseCode);
                return NO_CONNECTION_STATIC_LINKS;
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);

            aboutLogoUrl = json.getJSONObject("branding").getString("logo");

            List<WebLink> links = new ArrayList<>();

            JSONArray donations = json.getJSONObject("donations").getJSONArray("links");
            for (int i = 0, length = donations.length(); i < length; i++) {
                WebLink link = new WebLink(donations.getJSONObject(i));
                if (link.preferred) {
                    links.add(link);
                }
            }

            JSONArray socials = json.getJSONArray("socials");
            for (int i = 0, length = socials.length(); i < length; i++) {
                WebLink link = new WebLink(socials.getJSONObject(i));
                links.add(link);
            }

            Logger.printDebug(() -> "links: " + links);

            return fetchedLinks = links.toArray(new WebLink[0]);

        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Could not fetch about information", ex); // No toast.
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not parse about information", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get about information", ex);
        }

        return NO_CONNECTION_STATIC_LINKS;
    }
}
