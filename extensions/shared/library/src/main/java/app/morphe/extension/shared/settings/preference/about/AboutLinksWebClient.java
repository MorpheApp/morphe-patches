package app.morphe.extension.shared.settings.preference.about;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.PatchesUpdateNoticePatch;

class AboutLinksWebClient extends WebViewClient {
    final Context context;
    final Dialog dialog;

    public AboutLinksWebClient(Context context, Dialog dialog) {
        this.context = context;
        this.dialog = dialog;
    }

    /**
     * Opens one of the lists of people over the dialog, which closing the list comes back to,
     * once the lists are fetched. They are fetched once, so only the first list opened waits on
     * the network.
     *
     * @param credits Whether to list the contributors rather than the supporters.
     */
    private void showPeople(boolean credits) {
        Utils.runOnBackgroundThread(() -> {
            AboutRoutes.People people = AboutRoutes.fetchPeople();

            Utils.runOnMainThreadNowOrLater(() -> {
                // The dialog was closed while the lists were on their way.
                if (!dialog.isShowing()) return;

                if (credits) {
                    new MorpheCreditsDialog(context, people).show();
                } else {
                    new MorpheSupportersDialog(context, people).show();
                }
            });
        });
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        final String url = request.getUrl().toString();
        try {
            if (AboutDialogStyle.CLOSE_LINK_PLACEHOLDER_URL.equals(url)) {
                dialog.dismiss();
                return true;
            }

            final boolean credits = MorpheAboutPreference.CREDITS_LINK_PLACEHOLDER_URL.equals(url);
            if (credits || MorpheAboutPreference.SUPPORTERS_LINK_PLACEHOLDER_URL.equals(url)) {
                showPeople(credits);
                return true;
            }

            if (MorpheAboutPreference.MANAGER_LINK_PLACEHOLDER_URL.equals(url)) {
                PatchesUpdateNoticePatch.openManager(context);
                dialog.dismiss();
                return true;
            }

            if (MorpheCreditsDialog.LICENSES_LINK_PLACEHOLDER_URL.equals(url)) {
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
