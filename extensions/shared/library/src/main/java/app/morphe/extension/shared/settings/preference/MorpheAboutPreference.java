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
import java.util.List;

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
    protected String getString(String key, Object ... args) {
        return str(key, args);
    }

    private String createDialogHtml(WebLink[] aboutLinks) {
        final boolean isNetworkConnected = Utils.isNetworkConnected();

        StringBuilder builder = new StringBuilder();
        builder.append("<html>");
        builder.append("<head>");
        builder.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        builder.append("</head>");
        builder.append("<body>");

        // Get theme colors.
        String foregroundColorHex = Utils.getColorHexString(Utils.getAppForegroundColor());
        String backgroundColorHex = Utils.getColorHexString(Utils.getDialogBackgroundColor());

        // Morphe brand colors from logo.
        String morpheBlue = "#1E5AA8";
        String morpheTeal = "#00AFAE";

        // Apply Morphe-style CSS.
        builder.append("<style>");
        builder.append("* { margin: 0; padding: 0; box-sizing: border-box; }");
        builder.append("body { ");
        builder.append("  background: ").append(backgroundColorHex).append(";");
        builder.append("  color: ").append(foregroundColorHex).append(";");
        builder.append("  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;");
        builder.append("  padding: 24px;");
        builder.append("  text-align: center;");
        builder.append("}");

        // Logo container with Morphe gradient border.
        builder.append(".logo-container {");
        builder.append("  margin: 0 auto 24px;");
        builder.append("  width: 120px;");
        builder.append("  height: 120px;");
        builder.append("  border-radius: 28px;");
        builder.append("  background: linear-gradient(135deg, ").append(morpheBlue).append(" 0%, ").append(morpheTeal).append(" 100%);");
        builder.append("  padding: 3px;");
        builder.append("  display: inline-block;");
        builder.append("  box-shadow: 0 8px 24px rgba(30, 90, 168, 0.2);");
        builder.append("}");

        builder.append(".logo-inner {");
        builder.append("  width: 100%;");
        builder.append("  height: 100%;");
        builder.append("  border-radius: 26px;");
        builder.append("  background: ").append(backgroundColorHex).append(";");
        builder.append("  display: flex;");
        builder.append("  align-items: center;");
        builder.append("  justify-content: center;");
        builder.append("  overflow: hidden;");
        builder.append("  padding: 16px;");
        builder.append("}");

        builder.append("img {");
        builder.append("  width: 100%;");
        builder.append("  height: 100%;");
        builder.append("  object-fit: contain;");
        builder.append("}");

        // Title styling with Morphe gradient.
        builder.append("h1 {");
        builder.append("  font-size: 32px;");
        builder.append("  font-weight: 700;");
        builder.append("  margin-bottom: 8px;");
        builder.append("  background: linear-gradient(135deg, ").append(morpheBlue).append(" 0%, ").append(morpheTeal).append(" 100%);");
        builder.append("  -webkit-background-clip: text;");
        builder.append("  -webkit-text-fill-color: transparent;");
        builder.append("  background-clip: text;");
        builder.append("}");

        // Description text.
        builder.append("p {");
        builder.append("  font-size: 14px;");
        builder.append("  line-height: 1.6;");
        builder.append("  margin-bottom: 16px;");
        builder.append("  opacity: 0.8;");
        builder.append("}");

        // Dev warning banner.
        builder.append(".dev-warning {");
        builder.append("  background: rgba(239, 68, 68, 0.1);");
        builder.append("  border: 1px solid rgba(239, 68, 68, 0.3);");
        builder.append("  border-radius: 12px;");
        builder.append("  padding: 12px 16px;");
        builder.append("  margin: 16px 0;");
        builder.append("}");

        builder.append(".dev-warning h3 {");
        builder.append("  color: #EF4444;");
        builder.append("  font-size: 16px;");
        builder.append("  font-weight: 600;");
        builder.append("  margin-bottom: 6px;");
        builder.append("}");

        builder.append(".dev-warning p {");
        builder.append("  color: #EF4444;");
        builder.append("  margin: 0;");
        builder.append("  font-size: 13px;");
        builder.append("}");

        // Links section.
        builder.append(".links-section {");
        builder.append("  margin-top: 32px;");
        builder.append("}");

        builder.append("h2 {");
        builder.append("  font-size: 18px;");
        builder.append("  font-weight: 600;");
        builder.append("  margin-bottom: 16px;");
        builder.append("  opacity: 0.9;");
        builder.append("}");

        // Link buttons with Morphe gradient accent.
        builder.append(".link-button {");
        builder.append("  display: block;");
        builder.append("  text-decoration: none;");
        builder.append("  color: ").append(foregroundColorHex).append(";");
        builder.append("  background: linear-gradient(135deg, rgba(30, 90, 168, 0.08) 0%, rgba(0, 175, 174, 0.08) 100%);");
        builder.append("  border: 1px solid rgba(30, 90, 168, 0.2);");
        builder.append("  border-radius: 12px;");
        builder.append("  padding: 14px 20px;");
        builder.append("  margin-bottom: 10px;");
        builder.append("  font-size: 15px;");
        builder.append("  font-weight: 500;");
        builder.append("  transition: all 0.2s ease;");
        builder.append("  position: relative;");
        builder.append("  overflow: hidden;");
        builder.append("  -webkit-tap-highlight-color: transparent;");
        builder.append("  -webkit-touch-callout: none;");
        builder.append("  -webkit-user-select: none;");
        builder.append("  user-select: none;");
        builder.append("}");

        // Add ripple effect overlay.
        builder.append(".link-button::after {");
        builder.append("  content: '';");
        builder.append("  position: absolute;");
        builder.append("  top: 50%;");
        builder.append("  left: 50%;");
        builder.append("  width: 0;");
        builder.append("  height: 0;");
        builder.append("  border-radius: 50%;");
        builder.append("  background: rgba(30, 90, 168, 0.3);");
        builder.append("  transform: translate(-50%, -50%);");
        builder.append("  transition: width 0.3s, height 0.3s;");
        builder.append("  pointer-events: none;");
        builder.append("}");

        builder.append(".link-button:active {");
        builder.append("  transform: scale(0.98);");
        builder.append("  background: linear-gradient(135deg, rgba(30, 90, 168, 0.15) 0%, rgba(0, 175, 174, 0.15) 100%);");
        builder.append("  border-color: rgba(30, 90, 168, 0.4);");
        builder.append("  outline: none;");
        builder.append("}");

        builder.append(".link-button:active::after {");
        builder.append("  width: 300px;");
        builder.append("  height: 300px;");
        builder.append("}");

        builder.append(".link-button:focus {");
        builder.append("  outline: none;");
        builder.append("}");

        builder.append("</style>");

        builder.append("</head><body>");

        // Logo with Morphe gradient border.
        if (isNetworkConnected) {
            builder.append("<div class=\"logo-container\">");
            builder.append("<div class=\"logo-inner\">");
            builder.append("<img ");
            builder.append("onerror=\"this.parentElement.parentElement.style.display='none';\" ");
            builder.append("src=\"").append(AboutLinksRoutes.aboutLogoUrl).append("\" />");
            builder.append("</div>");
            builder.append("</div>");
        }

        String patchesVersion = Utils.getPatchesReleaseVersion();

        // Title with gradient.
        builder.append("<h1>Morphe</h1>");

        // Description.
        builder.append("<p>")
                // Replace hyphens with non breaking dashes so the version number does not break lines.
                .append(useNonBreakingHyphens(getString("morphe_settings_about_links_body", patchesVersion)))
                .append("</p>");

        // Dev warning banner.
        if (patchesVersion.contains("dev")) {
            builder.append("<div class=\"dev-warning\">");
            builder.append("<h3>")
                    // English text 'Pre-release' can break lines.
                    .append(useNonBreakingHyphens(getString("morphe_settings_about_links_dev_header")))
                    .append("</h3>");
            builder.append("<p>")
                    .append(getString("morphe_settings_about_links_dev_body"))
                    .append("</p>");
            builder.append("</div>");
        }

        // Links section.
        builder.append("<div class=\"links-section\">");
        builder.append("<h2>")
                .append(getString("morphe_settings_about_links_header"))
                .append("</h2>");

        // Link buttons.
        for (WebLink link : aboutLinks) {
            builder.append("<a href=\"").append(link.url).append("\" class=\"link-button\">");
            builder.append(link.name);
            builder.append("</a>");
        }
        builder.append("</div>");

        builder.append("</body></html>");
        return builder.toString();
    }

    {
        setOnPreferenceClickListener(pref -> {
            Context context = pref.getContext();

            // Show a progress spinner if the social links are not fetched yet.
            if (!AboutLinksRoutes.hasFetchedLinks() && Utils.isNetworkConnected()) {
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
        WebLink[] links = AboutLinksRoutes.fetchAboutLinks();
        String htmlDialog = createDialogHtml(links);

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
    final boolean preferred;
    String name;
    final String url;

    WebLink(JSONObject json) throws JSONException {
        this(json.getBoolean("preferred"),
                json.getString("name"),
                json.getString("url")
        );
    }

    WebLink(boolean preferred, String name, String url) {
        this.preferred = preferred;
        this.name = name;
        this.url = url;
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

class AboutLinksRoutes {
    /**
     * Backup icon url if the API call fails.
     */
    public static volatile String aboutLogoUrl = "https://morphe.software/favicon.ico";

    /**
     * Links to use if fetch links api call fails.
     */
    private static final WebLink[] NO_CONNECTION_STATIC_LINKS = {
            new WebLink(true, "Morphe", "https://morphe.software")
    };

    // TODO
    private static final String SOCIAL_LINKS_PROVIDER = "https://software.morphi.app/v1";
    private static final Route.CompiledRoute GET_SOCIAL = new Route(GET, "/about").compile();

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

            JSONObject json;

            if (true) {
                json = new JSONObject(ABOUT_JSON_TEMPORARY);
            } else {
                HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(SOCIAL_LINKS_PROVIDER, GET_SOCIAL);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                Logger.printDebug(() -> "Fetching social links from: " + connection.getURL());


                // Do not show an exception toast if the server is down
                final int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    Logger.printDebug(() -> "Failed to get social links. Response code: " + responseCode);
                    return NO_CONNECTION_STATIC_LINKS;
                }

                json = Requester.parseJSONObjectAndDisconnect(connection);
            }

            aboutLogoUrl = json.getJSONObject("branding").getString("logo");

            List<WebLink> links = new ArrayList<>();

            JSONArray donations = json.getJSONObject("donations").getJSONArray("links");
            for (int i = 0, length = donations.length(); i < length; i++) {
                WebLink link = new WebLink(donations.getJSONObject(i));
                if (link.preferred) {
                    link.name = str("morphe_settings_about_donate");
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
            Logger.printInfo(() -> "Could not fetch social links", ex); // No toast.
        } catch (JSONException ex) {
            Logger.printException(() -> "Could not parse about information", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get about information", ex);
        }

        return NO_CONNECTION_STATIC_LINKS;
    }

    // TODO: Eventually move this to a web server.
    private static final String ABOUT_JSON_TEMPORARY = """
        {
          "name": "Morphe",
          "branding": {
            "logo": "https://raw.githubusercontent.com/MorpheApp/morphe-branding/main/assets/morphe-logo/morphe_logo_with_frame.svg"
          },
          "contact": {
            "email": "na"
          },
          "socials": [
            {
              "name": "Website",
              "url": "https://Morphe.software",
              "preferred": true
            },
            {
              "name": "GitHub",
              "url": "https://github.com/MorpheApp",
              "preferred": false
            },
            {
              "name": "Twitter",
              "url": "https://twitter.com/MorpheApp",
              "preferred": false
            },
            {
              "name": "Reddit",
              "url": "https://www.reddit.com/r/Morphe",
              "preferred": false
            }
          ],
          "donations": {
            "wallets": [
            {
                "network": "Ethereum",
                "currency_code": "ETH",
                "address": "XXX",
                "preferred": true
              },
              {
                "network": "Bitcoin",
                "currency_code": "BTC",
                "address": "XXX",
                "preferred": false
              },
              {
                "network": "Dogecoin",
                "currency_code": "DOGE",
                "address": "XXX",
                "preferred": false
              },
              {
                "network": "Litecoin",
                "currency_code": "LTC",
                "address": "XXX",
                "preferred": false
              },
              {
                "network": "Monero",
                "currency_code": "XMR",
                "address": "XXX",
                "preferred": false
              }
            ],
            "links": [
              {
                "name": "Donate",
                "url": "https://morphe.software/donate",
                "preferred": true
              }
            ]
          }
        }
    """;
}
