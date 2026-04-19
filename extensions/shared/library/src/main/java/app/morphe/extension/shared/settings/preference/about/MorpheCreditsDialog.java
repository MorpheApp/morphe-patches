package app.morphe.extension.shared.settings.preference.about;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference.WebLink;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

class MorpheCreditsDialog extends Dialog {

    private static final List<WebLink> WORKS_LINKS_CURRENT = List.of(
            new WebLink("Morphe",
                    str("morphe_settings_about_links_morphe"),
                    "https://github.com/morpheapp/morphe-patches/graphs/contributors"
            )
    );

    private static final List<WebLink> WORKS_LINKS_PRIOR = List.of(
            new WebLink("RVX",
                    str("morphe_settings_about_links_rvx"),
                    "https://github.com/inotia00/revanced-patches/graphs/contributors?from=3%2F1%2F2022&to=12%2F1%2F2025"
            ),
            new WebLink("ReVanced",
                    str("morphe_settings_about_links_rv"),
                    "https://github.com/ReVanced/revanced-patches/graphs/contributors?from=3%2F1%2F2022&to=12%2F1%2F2025"
            )
    );

    private static final WebLink WORKS_VANCED = new WebLink("Vanced",
            str("morphe_settings_about_links_vanced"),
            "https://github.com/TeamVanced"
    );

    static final WebLink ABOUT_LICENSE = new WebLink("license",
            str("morphe_settings_about_links_licenses"),
            "https://license/"
    );

    static boolean showVancedAsPastContributor = true;

    private static List<WebLink> getWorksLinksPrior() {
        List<WebLink> prior = new ArrayList<>(WORKS_LINKS_PRIOR);
        if (showVancedAsPastContributor) {
            prior.add(WORKS_VANCED);
        }
        return prior;
    }

    private String createDialogHtml() {
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
                             .credits-header {
                                 padding: 22px 20px 16px;
                                 text-align: center;
                                 border-bottom: 1px solid rgba(128, 128, 128, 0.12);
                             }
                             .credits-title {
                                 font-size: 17px;
                                 font-weight: 600;
                                 color: %s;
                             }
                             /* Sections */
                             .credits-section {
                                 padding: 14px 16px;
                             }
                             .credits-section + .credits-section {
                                 border-top: 1px solid rgba(128, 128, 128, 0.12);
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
                             /* Contributor rows - same style as About */
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
                             .avatar {
                                 width: 28px;
                                 height: 28px;
                                 border-radius: 50%%;
                                 background: linear-gradient(135deg, %s 0%%, %s 100%%);
                                 display: flex;
                                 align-items: center;
                                 justify-content: center;
                                 font-size: 12px;
                                 font-weight: 700;
                                 color: #ffffff;
                                 flex-shrink: 0;
                             }
                             .contributor-info {
                                 flex: 1;
                             }
                             .contributor-name {
                                 font-size: 14px;
                                 font-weight: 500;
                                 color: %s;
                             }
                             .contributor-role {
                                 font-size: 11px;
                                 color: %s;
                                 opacity: 0.5;
                                 margin-top: 1px;
                             }
                             .link-chevron {
                                 font-size: 24px;
                                 color: %s;
                                 opacity: 0.3;
                                 line-height: 1;
                             }
                             .link-icon {
                                 width: 28px;
                                 height: 28px;
                                 border-radius: 8px;
                                 background: linear-gradient(135deg, rgba(30, 90, 168, 0.12) 0%%, rgba(0, 175, 174, 0.12) 100%%);
                                 display: flex;
                                 align-items: center;
                                 justify-content: center;
                                 flex-shrink: 0;
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
                         </style>
                        """,
                backgroundColorHex, foregroundColorHex,
                foregroundColorHex, foregroundColorHex,
                foregroundColorHex, morpheBlue, morpheTeal,
                foregroundColorHex, foregroundColorHex, foregroundColorHex,
                foregroundColorHex
        ));

        // Header.
        html.append(String.format("""
                        <div class="credits-header">
                            <div class="credits-title">%s</div>
                        </div>
                        """,
                StringRef.str("morphe_settings_about_links_credits")
        ));

        // Current contributors section.
        html.append(String.format("""
                <div class="credits-section">
                    <div class="section-label">%s</div>
                """, StringRef.str("morphe_settings_about_contributors_current")));
        for (WebLink link : WORKS_LINKS_CURRENT) {
            String initial = link.name.substring(0, 1).toUpperCase();
            html.append("<a href=\"").append(link.url).append("\" class=\"link-button\">")
                    .append("<div class=\"avatar\">").append(initial).append("</div>")
                    .append("<div class=\"contributor-info\">")
                    .append("<div class=\"contributor-name\">").append(link.name).append("</div>")
                    .append("<div class=\"contributor-role\">").append(link.subText).append("</div>")
                    .append("</div>")
                    .append("<span class=\"link-chevron\">&#x203A;</span>")
                    .append("</a>");
        }
        html.append("</div>");

        // Prior contributors section.
        html.append(String.format("""
                <div class="credits-section">
                    <div class="section-label">%s</div>
                """, StringRef.str("morphe_settings_about_contributors_prior")));
        for (WebLink link : getWorksLinksPrior()) {
            String initial = link.name.substring(0, 1).toUpperCase();
            html.append("<a href=\"").append(link.url).append("\" class=\"link-button\">")
                    .append("<div class=\"avatar\">").append(initial).append("</div>")
                    .append("<div class=\"contributor-info\">")
                    .append("<div class=\"contributor-name\">").append(link.name).append("</div>");
            if (link.subText != null) {
                html.append("<div class=\"contributor-role\">").append(link.subText).append("</div>");
            }
            html.append("</div>")
                    .append("<span class=\"link-chevron\">&#x203A;</span>")
                    .append("</a>");
        }
        html.append("</div>");

        // In-app user-facing attribution of licenses and notices (Apache 2.0 criteria).
        html.append("<div class=\"credits-section\">");
        html.append("<a href=\"").append(ABOUT_LICENSE.url).append("\" class=\"link-button\">")
                .append("<span class=\"link-icon\">")
                .append("<svg viewBox='0 0 16 16'><path d='M4 1h6l3 3v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1z'/><path d='M10 1v3h3'/><line x1='5' y1='7' x2='11' y2='7'/><line x1='5' y1='10' x2='11' y2='10'/><line x1='5' y1='13' x2='8' y2='13'/></svg>")
                .append("</span>")
                .append("<div class=\"contributor-info\">")
                .append("<div class=\"contributor-name\">")
                .append(str("morphe_settings_about_links_licenses"))
                .append("</div>");
        html.append("</div>")
                .append("<span class=\"link-chevron\">&#x203A;</span>")
                .append("</a>");
        html.append("</div>");

        html.append("""
                </body>
                </html>
            """);

        return html.toString();
    }

    private final String htmlContent;

    public MorpheCreditsDialog(Context context) {
        super(context);
        this.htmlContent = createDialogHtml();
    }

    // JS required to hide any broken images. No remote JavaScript is ever loaded.
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
        webView.setWebViewClient(new AboutLinksWebClient(getContext(), this));
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
}
