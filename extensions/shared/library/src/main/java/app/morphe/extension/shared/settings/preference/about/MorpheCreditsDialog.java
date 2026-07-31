/*
 * A reminder to anyone using any code from this software project:
 *
 * Refer to GPLv3 Section 5(d) regarding the preservation of
 * interactive notices such as in-app notices or in-app credits.
 */

package app.morphe.extension.shared.settings.preference.about;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class MorpheCreditsDialog extends AboutWebViewDialog {

    private static final List<MorpheAboutPreference.WebLink> WORKS_LINKS_CURRENT = List.of(
            new MorpheAboutPreference.WebLink("Morphe",
                    str("morphe_settings_about_links_morphe"),
                    "https://github.com/morpheapp/morphe-patches/graphs/contributors"
            )
    );

    private static final List<MorpheAboutPreference.WebLink> WORKS_LINKS_PRIOR = List.of(
            new MorpheAboutPreference.WebLink("RVX",
                    str("morphe_settings_about_links_rvx"),
                    "https://github.com/inotia00/revanced-patches/graphs/contributors?from=3%2F1%2F2022&to=12%2F1%2F2025"
            ),
            new MorpheAboutPreference.WebLink("ReVanced",
                    str("morphe_settings_about_links_rv"),
                    "https://revanced.app/contributors"
            )
    );

    private static final MorpheAboutPreference.WebLink WORKS_VANCED = new MorpheAboutPreference.WebLink("Vanced",
            str("morphe_settings_about_links_vanced"),
            "https://github.com/TeamVanced"
    );

    static final MorpheAboutPreference.WebLink ABOUT_LICENSE = new MorpheAboutPreference.WebLink("license",
            str("morphe_settings_about_links_licenses"),
            "https://license/"
    );

    static boolean showVancedAsPastContributor = true;

    private static List<MorpheAboutPreference.WebLink> getWorksLinksPrior() {
        List<MorpheAboutPreference.WebLink> prior = new ArrayList<>(WORKS_LINKS_PRIOR);
        if (showVancedAsPastContributor) {
            prior.add(WORKS_VANCED);
        }
        return prior;
    }

    private static String createDialogHtml() {
        StringBuilder html = new StringBuilder(AboutDialogStyle.documentStart());

        html.append("<div class=\"dialog-header\"><div class=\"dialog-title\">")
                .append(str("morphe_settings_about_links_credits"))
                .append("</div></div>");

        appendContributors(html, str("morphe_settings_about_contributors_current"), WORKS_LINKS_CURRENT);
        appendContributors(html, str("morphe_settings_about_contributors_prior"), getWorksLinksPrior());

        // In-app user-facing attribution of licenses and notices (Apache 2.0 criteria).
        html.append("<div class=\"section\"><div class=\"settings-group\">")
                .append("<a href=\"").append(ABOUT_LICENSE.url).append("\" class=\"settings-item\">")
                .append("<span class=\"item-icon\">")
                .append(AboutDialogStyle.linkIcon(ABOUT_LICENSE.url))
                .append("</span>")
                .append("<div class=\"item-text\"><div class=\"item-title\">")
                .append(str("morphe_settings_about_links_licenses"))
                .append("</div></div>")
                .append(AboutDialogStyle.chevron())
                .append("</a>")
                .append("</div></div>");

        html.append(AboutDialogStyle.DOCUMENT_END);

        return html.toString();
    }

    private static void appendContributors(StringBuilder html, String title,
                                           List<MorpheAboutPreference.WebLink> links) {
        html.append("<div class=\"section\">")
                .append(AboutDialogStyle.sectionTitle(title))
                .append("<div class=\"settings-group\">");

        for (MorpheAboutPreference.WebLink link : links) {
            String initial = link.name.substring(0, 1).toUpperCase(Locale.getDefault());
            html.append("<a href=\"").append(link.url).append("\" class=\"settings-item\">")
                    .append("<span class=\"avatar\">").append(initial).append("</span>")
                    .append("<div class=\"item-text\"><div class=\"item-title\">")
                    .append(link.name)
                    .append("</div>");
            if (link.subText != null) {
                html.append("<div class=\"item-subtitle\">").append(link.subText).append("</div>");
            }
            html.append("</div>")
                    .append(AboutDialogStyle.chevron())
                    .append("</a>");
        }

        html.append("</div></div>");
    }

    MorpheCreditsDialog(Context context) {
        super(context, createDialogHtml());
    }
}
