package app.morphe.extension.shared.settings.preference.about;

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

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Displays HTML content as a dialog. Any links a user taps on are opened in an external browser.
 */
class AboutWebViewDialog extends Dialog {

    private final String htmlContent;

    public AboutWebViewDialog(@NonNull Context context, @NonNull String htmlContent) {
        super(context);
        this.htmlContent = htmlContent;
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
