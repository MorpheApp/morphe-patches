/*
 * A reminder to anyone using any code from this software project:
 *
 * Refer to GPLv3 Section 5(d) regarding the preservation of
 * interactive notices such as in-app notices or in-app credits.
 */

package app.morphe.extension.shared.settings.preference.about;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.webkit.WebView;

import java.util.List;

import app.morphe.extension.shared.Utils;

/**
 * Shows the notices of a single bundled dependency.
 */
@SuppressWarnings({"deprecation", "RedundantSuppression"})
class LicenseTextDialog extends Dialog {

    private final List<LicenseContent> content;

    LicenseTextDialog(Context context, List<LicenseContent> content) {
        super(context, android.R.style.Theme_DeviceDefault_NoActionBar);
        this.content = content;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        // The window fills the screen, so its own background and the navigation bar are painted the
        // same color the page is, rather than whatever the device theme happens to be.
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Utils.getDialogBackgroundColor()));
            window.setNavigationBarColor(Utils.getDialogBackgroundColor());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.setNavigationBarContrastEnforced(true);
            }
        }

        WebView webView = new WebView(getContext());
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        webView.getSettings().setJavaScriptEnabled(false);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        // A WebView paints white until the page is parsed, which flashes as the dialog opens.
        webView.setBackgroundColor(Utils.getDialogBackgroundColor());
        webView.setWebViewClient(new AboutLinksWebClient(getContext(), this));

        webView.loadDataWithBaseURL(null, buildHtml(), "text/html", "UTF-8", null);

        setContentView(webView);
        applyInsetsToContentView();
    }

    /**
     * Applies window insets to the content root view.
     */
    private void applyInsetsToContentView() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;

        Window window = getWindow();
        if (window == null) return;

        ViewGroup rootView = (ViewGroup) window.getDecorView()
                .findViewById(android.R.id.content)
                .getParent();

        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets statusInsets = insets.getInsets(WindowInsets.Type.statusBars());
            Insets navInsets = insets.getInsets(WindowInsets.Type.navigationBars());
            Insets cutoutInsets = insets.getInsets(WindowInsets.Type.displayCutout());

            v.setPadding(
                    cutoutInsets.left,
                    statusInsets.top,
                    cutoutInsets.right,
                    navInsets.bottom
            );
            return insets;
        });
    }

    private String buildHtml() {
        StringBuilder html = new StringBuilder(AboutDialogStyle.documentStart());

        // The notices are pre-formatted English whose alignment only holds left to right, so they
        // stay unmirrored even when the surrounding app language reads the other way.
        html.append("<div class=\"license-body\" dir=\"ltr\">");
        for (LicenseContent item : content) {
            html.append("<h2>").append(AboutDialogStyle.escapeHtml(item.title())).append("</h2>");
            html.append("<pre>").append(AboutDialogStyle.linkifyHtml(item.content())).append("</pre>");
        }
        html.append("</div>").append(AboutDialogStyle.DOCUMENT_END);

        return html.toString();
    }
}
