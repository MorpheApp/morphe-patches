/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference.about;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Who backs Morphe with a donation, in the order the API ranks them and with its Mega Supporters
 * in a pulsing ring, over a way to join them.
 */
class MorpheSupportersDialog extends AboutWebViewDialog {

    /**
     * Where the supporters past the ones listed are, and every anonymous one.
     */
    private static final String SUPPORTERS_URL = "https://opencollective.com/morpheapp";

    private static final String DONATE_URL = "https://morphe.software/donate";

    private static String createDialogHtml(Context context, @Nullable AboutRoutes.People people) {
        StringBuilder html = new StringBuilder(AboutDialogStyle.documentStart(context))
                .append(AboutDialogStyle.pageStart(false));

        List<AboutRoutes.Person> supporters = people == null ? List.of() : people.supporters();
        // Every backer, of whom only the named ones are listed.
        final int total = people == null ? 0 : people.supportersTotal();

        String subtitle = str("morphe_settings_about_supporters_summary");
        if (total > 0) subtitle += " · " + total;
        html.append(AboutDialogStyle.peopleHeader(
                AboutDialogStyle.linkIcon(MorpheAboutPreference.SUPPORTERS_LINK_PLACEHOLDER_URL),
                str("morphe_settings_about_supporters"),
                subtitle));

        html.append("<div class=\"section\">");
        if (supporters.isEmpty()) {
            html.append("<div class=\"empty\">").append(str("morphe_settings_about_people_unavailable")).append("</div>");
        } else {
            AboutDialogStyle.appendPeople(html, supporters);
        }
        html.append("</div>");

        final int unlisted = total - supporters.size();
        if (!supporters.isEmpty() && unlisted > 0) {
            html.append("<div class=\"section\"><div class=\"settings-group\">")
                    .append("<a href=\"").append(SUPPORTERS_URL).append("\" class=\"settings-item\">")
                    .append("<span class=\"item-icon\">")
                    .append(AboutDialogStyle.linkIcon(MorpheAboutPreference.SUPPORTERS_LINK_PLACEHOLDER_URL))
                    .append("</span><div class=\"item-text\"><div class=\"item-title\">")
                    .append(str("morphe_settings_about_people_more", unlisted))
                    .append("</div></div>")
                    .append(AboutDialogStyle.chevron())
                    .append("</a></div></div>");
        }

        // Stays in place over the button closing the dialog, however long the list above runs.
        String becomeSupporter = "<a href=\"" + DONATE_URL + "\" class=\"cta\">"
                + AboutDialogStyle.donateIcon()
                + "<span>" + str("morphe_settings_about_supporters_become") + "</span></a>";

        html.append(AboutDialogStyle.pageEnd(str("morphe_settings_about_close"), becomeSupporter))
                .append(AboutDialogStyle.DOCUMENT_END);

        return html.toString();
    }

    /**
     * @param people The people behind Morphe, or null if they could not be fetched.
     */
    MorpheSupportersDialog(Context context, @Nullable AboutRoutes.People people) {
        super(context, createDialogHtml(context, people), false);
    }
}
