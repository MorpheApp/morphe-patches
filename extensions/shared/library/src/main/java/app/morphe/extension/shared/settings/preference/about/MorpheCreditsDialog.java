/*
 * A reminder to anyone using any code from this software project:
 *
 * Refer to GPLv3 Section 5(d) regarding the preservation of
 * interactive notices such as in-app notices or in-app credits.
 */

package app.morphe.extension.shared.settings.preference.about;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Who wrote the patches: everyone who committed to Morphe over the last year, over the projects
 * Morphe grew out of.
 */
class MorpheCreditsDialog extends AboutWebViewDialog {

    /**
     * A project the patches are, or grew out of, credited by a link to the people who wrote it.
     *
     * @param period When the project was worked on.
     * @param owner  GitHub account whose picture the project goes by. Null for a project of
     *               others, which goes by its initial: their logos are not ours to show.
     */
    private record Project(String name, String period, String url, @Nullable String owner) {
        @Nullable
        String avatarUrl() {
            return owner == null ? null : "https://github.com/" + owner + ".png?size=96";
        }
    }

    private static final List<Project> WORKS_LINKS_CURRENT = List.of(
            new Project("Morphe",
                    str("morphe_settings_about_links_morphe"),
                    "https://github.com/morpheapp/morphe-patches/graphs/contributors",
                    "MorpheApp"
            )
    );

    private static final List<Project> WORKS_LINKS_PRIOR = List.of(
            new Project("RVX",
                    str("morphe_settings_about_links_rvx"),
                    "https://github.com/inotia00/revanced-patches/graphs/contributors?from=3%2F1%2F2022&to=12%2F1%2F2025",
                    null
            ),
            new Project("ReVanced",
                    str("morphe_settings_about_links_rv"),
                    "https://revanced.app/contributors",
                    null
            )
    );

    private static final Project WORKS_VANCED = new Project("Vanced",
            str("morphe_settings_about_links_vanced"),
            "https://github.com/TeamVanced",
            null
    );

    // Dummy url. Tapping it opens the license notices rather than a page.
    static final String LICENSES_LINK_PLACEHOLDER_URL = "https://license/";

    static boolean showVancedAsPastContributor = true;

    private static String createDialogHtml(Context context, @Nullable AboutRoutes.People people) {
        StringBuilder html = new StringBuilder(AboutDialogStyle.documentStart(context))
                .append(AboutDialogStyle.pageStart(false));

        List<AboutRoutes.Person> contributors = people == null ? List.of() : people.contributors();

        String subtitle = str("morphe_settings_about_contributors_period");
        if (!contributors.isEmpty()) subtitle += " · " + contributors.size();
        html.append(AboutDialogStyle.peopleHeader(
                AboutDialogStyle.linkIcon(MorpheAboutPreference.CREDITS_LINK_PLACEHOLDER_URL),
                str("morphe_settings_about_contributors"),
                subtitle));

        if (contributors.isEmpty()) {
            // The people of the project itself, where the list cannot name them.
            appendContributors(html, str("morphe_settings_about_contributors_current"), WORKS_LINKS_CURRENT);
        } else {
            html.append("<div class=\"section\">");
            AboutDialogStyle.appendPeople(html, contributors);
            html.append("</div>");
        }

        List<Project> prior = new ArrayList<>(WORKS_LINKS_PRIOR);
        if (showVancedAsPastContributor) {
            prior.add(WORKS_VANCED);
        }
        appendContributors(html, str("morphe_settings_about_contributors_prior"), prior);

        // In-app user-facing attribution of licenses and notices (Apache 2.0 criteria).
        html.append("<div class=\"section\"><div class=\"settings-group\">")
                .append("<a href=\"").append(LICENSES_LINK_PLACEHOLDER_URL).append("\" class=\"settings-item\">")
                .append("<span class=\"item-icon\">")
                .append(AboutDialogStyle.linkIcon(LICENSES_LINK_PLACEHOLDER_URL))
                .append("</span>")
                .append("<div class=\"item-text\"><div class=\"item-title\">")
                .append(str("morphe_settings_about_links_licenses"))
                .append("</div></div>")
                .append(AboutDialogStyle.chevron())
                .append("</a>")
                .append("</div></div>");

        html.append(AboutDialogStyle.pageEnd(str("morphe_settings_about_close"), null))
                .append(AboutDialogStyle.DOCUMENT_END);

        return html.toString();
    }

    /**
     * Appends projects under a heading, each a row as wide as the dialog led by the round picture
     * the people above go by.
     */
    private static void appendContributors(StringBuilder html, String title, List<Project> projects) {
        html.append("<div class=\"section\">")
                .append(AboutDialogStyle.sectionTitle(title))
                .append("<div class=\"settings-group\">");

        for (int i = 0, size = projects.size(); i < size; i++) {
            Project project = projects.get(i);
            html.append("<a href=\"").append(project.url()).append("\" class=\"settings-item\">")
                    .append(AboutDialogStyle.avatar(project.name(), project.avatarUrl(), i))
                    .append("<div class=\"item-text\"><div class=\"item-title\">")
                    .append(project.name())
                    .append("</div><div class=\"item-subtitle\">").append(project.period()).append("</div></div>")
                    .append(AboutDialogStyle.chevron())
                    .append("</a>");
        }

        html.append("</div></div>");
    }

    /**
     * @param people The people behind Morphe, or null if they could not be fetched. The dialog
     *               then credits the project by a link to its contributors.
     */
    MorpheCreditsDialog(Context context, @Nullable AboutRoutes.People people) {
        super(context, createDialogHtml(context, people), false);
    }
}
