package app.morphe.extension.shared.settings.preference.about;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

class AboutLinksWebClient extends WebViewClient {
    final Context context;
    final Dialog dialog;

    public AboutLinksWebClient(Context context, Dialog dialog) {
        this.context = context;
        this.dialog = dialog;
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        try {
            if (MorpheAboutPreference.CREDITS_LINK_PLACEHOLDER_URL.equals(url)) {
                new MorpheCreditsDialog(context).show();
                dialog.dismiss();
                return true;
            }

            if (MorpheCreditsDialog.ABOUT_LICENSE.url.equals(url)) {
                new LicensesDialog(context).show();
                return true;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "shouldOverrideUrlLoading failure", ex);
        }

        // Dismiss the dialog using a delay,
        // otherwise without a delay the UI looks hectic with the dialog dismissing
        // to show the settings while simultaneously a web browser is opening.
        Utils.runOnMainThreadDelayed(dialog::dismiss, 500);
        return true;
    }
}
