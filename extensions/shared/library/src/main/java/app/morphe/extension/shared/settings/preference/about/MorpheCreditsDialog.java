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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who wrote the patches: everyone who committed to Morphe, for all time or a recent period, over
 * the projects Morphe grew out of.
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

    /**
     * A period the contributors are listed for, picked from a menu in the header.
     */
    private record Period(String label, List<AboutRoutes.Person> contributors) {
    }

    /**
     * Opens and closes the menu of periods, and lays out the one list for the period picked. Each
     * person holds their place in every period, so a change of period only reorders and hides the
     * people already drawn: a list made anew is blank until it is drawn, the first time it is
     * shown. Everyone listed before moves from where they stood to their new place, and only
     * someone not listed before comes in as the list does on opening. A move from far down is cut
     * to a few rows, as in Morphe Manager.
     */
    private static final String PERIOD_SCRIPT = """
            <script>(function () {
                var MAX_ROWS = 6;
                var reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
                var picker = document.querySelector('.period-picker');
                var button = picker.querySelector('.period-button');
                var grid = document.querySelector('.period-list .people-grid');
                var people = Array.prototype.slice.call(grid.querySelectorAll('.person'));
                people.forEach(function (person) {
                    person.periodPlaces = person.getAttribute('data-places').split(',').map(Number);
                });

                function setMenuOpen(open) {
                    picker.classList.toggle('open', open);
                    button.setAttribute('aria-expanded', open);
                }

                button.addEventListener('click', function () {
                    setMenuOpen(!picker.classList.contains('open'));
                });

                document.querySelectorAll('.period-option').forEach(function (option) {
                    option.addEventListener('click', function () {
                        setMenuOpen(false);
                    });
                });

                // A tap past the open menu only closes it, as a menu of the app does, rather than
                // also opening the page of whoever is under it.
                document.addEventListener('click', function (event) {
                    if (!picker.classList.contains('open') || picker.contains(event.target)) return;
                    event.preventDefault();
                    event.stopPropagation();
                    setMenuOpen(false);
                }, true);

                // Where everyone shown stands in the grid. Someone still moving is where they are drawn.
                function places() {
                    var origin = grid.getBoundingClientRect();
                    var result = new Map();
                    people.forEach(function (person) {
                        if (person.style.display === 'none') return;
                        var rect = person.getBoundingClientRect();
                        result.set(person, { x: rect.left - origin.left, y: rect.top - origin.top });
                    });
                    return result;
                }

                document.querySelectorAll('.period-input').forEach(function (input) {
                    var period = Number(input.id.substring('period-'.length));
                    input.addEventListener('change', function () {
                        var before = places();
                        people.forEach(function (person) {
                            var place = person.periodPlaces[period];
                            person.style.display = place < 0 ? 'none' : '';
                            person.style.order = place;
                        });
                        var after = places();
                        var first = people.find(function (person) { return after.has(person); });
                        var maxShift = first ? MAX_ROWS * (first.offsetHeight + parseFloat(getComputedStyle(grid).rowGap)) : 0;
                        after.forEach(function (to, person) {
                            var from = before.get(person);
                            person.classList.toggle('moved', !!from);
                            // Whoever is new comes in with the others moving, not in the wave of opening.
                            person.querySelector('.avatar').style.animationDelay = '0ms';
                            if (!from || reduceMotion.matches) return;
                            var dx = from.x - to.x;
                            var dy = Math.max(-maxShift, Math.min(maxShift, from.y - to.y));
                            if (dx === 0 && dy === 0) return;
                            person.animate([
                                { transform: 'translate(' + dx + 'px, ' + dy + 'px)' },
                                { transform: 'none' }
                            ], { duration: 450, easing: 'cubic-bezier(0.2, 0.9, 0.3, 1.05)' });
                        });
                    });
                });
            })();</script>
            """;

    private static void addPeriod(List<Period> periods, String labelKey, List<AboutRoutes.Person> contributors) {
        // A period with no one in it, or that the API does not serve, is not offered.
        if (!contributors.isEmpty()) periods.add(new Period(str(labelKey), contributors));
    }

    private static String createDialogHtml(Context context, @Nullable AboutRoutes.People people) {
        StringBuilder html = new StringBuilder(AboutDialogStyle.documentStart(context))
                .append(AboutDialogStyle.pageStart(false));

        List<Period> periods = new ArrayList<>();
        if (people != null) {
            addPeriod(periods, "morphe_settings_about_contributors_all_time", people.contributors());
            addPeriod(periods, "morphe_settings_about_contributors_6_months", people.contributors6Months());
            addPeriod(periods, "morphe_settings_about_contributors_3_months", people.contributors3Months());
        }

        String icon = AboutDialogStyle.linkIcon(MorpheAboutPreference.CREDITS_LINK_PLACEHOLDER_URL);
        String title = str("morphe_settings_about_contributors");
        if (periods.isEmpty()) {
            html.append(AboutDialogStyle.peopleHeader(icon, title, str("morphe_settings_about_people_unavailable")));
            // The people of the project itself, where the list cannot name them.
            appendContributors(html, str("morphe_settings_about_contributors_current"), WORKS_LINKS_CURRENT);
        } else {
            appendPeriods(html, icon, title, periods);
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

        html.append(AboutDialogStyle.pageEnd(str("morphe_settings_close"), null))
                .append(AboutDialogStyle.DOCUMENT_END);

        return html.toString();
    }

    /**
     * Appends the header, with a button opening a menu of the periods, and the list. The periods
     * are radio buttons that come before everything they switch, so the stylesheet shows the count
     * and menu entry of the checked one. A script opens the menu and lays out the list.
     */
    private static void appendPeriods(StringBuilder html, String icon, String title, List<Period> periods) {
        final int size = periods.size();
        for (int i = 0; i < size; i++) {
            html.append("<input type=\"radio\" name=\"period\" class=\"period-input\" id=\"period-")
                    .append(i).append(i == 0 ? "\" checked>" : "\">");
        }

        // Switching the period says what the list now holds.
        StringBuilder subtitle = new StringBuilder();
        for (int i = 0; i < size; i++) {
            Period period = periods.get(i);
            subtitle.append("<span class=\"period-count period-").append(i).append("\">")
                    .append(str("morphe_settings_about_contributors_count", period.contributors().size()))
                    .append(" · ").append(period.label())
                    .append("</span>");
        }

        String actions = null;
        if (size > 1) {
            // The menu hangs off the button, so it opens under the end of the header.
            StringBuilder picker = new StringBuilder("<div class=\"period-picker\">")
                    .append("<button type=\"button\" class=\"period-button\" aria-haspopup=\"menu\" aria-expanded=\"false\" aria-label=\"")
                    .append(AboutDialogStyle.escapeHtml(str("morphe_settings_about_contributors_period_menu")))
                    .append("\">").append(AboutDialogStyle.dateRangeIcon())
                    .append("<span class=\"period-mark\"></span></button>")
                    .append("<div class=\"period-menu\" role=\"menu\">");
            for (int i = 0; i < size; i++) {
                picker.append("<label for=\"period-").append(i).append("\" class=\"period-option period-").append(i)
                        .append("\" role=\"menuitemradio\"><span>").append(periods.get(i).label()).append("</span>")
                        .append(AboutDialogStyle.checkIcon()).append("</label>");
            }
            actions = picker.append("</div></div>").toString();
        }
        html.append(AboutDialogStyle.peopleHeader(icon, title, subtitle.toString(), actions));

        appendPeriodList(html, periods);

        if (size > 1) {
            html.append(PERIOD_SCRIPT);
        }
    }

    /**
     * Appends everyone listed for any of the periods as one grid, in their order for the first
     * period, each with their place in every period (-1 where they are not listed), see
     * {@link #PERIOD_SCRIPT}. Anyone not listed for the first period starts hidden.
     */
    private static void appendPeriodList(StringBuilder html, List<Period> periods) {
        final int size = periods.size();
        List<Map<String, Integer>> placesByPeriod = new ArrayList<>(size);
        Map<String, AboutRoutes.Person> everyone = new LinkedHashMap<>();
        for (Period period : periods) {
            Map<String, Integer> places = new HashMap<>();
            List<AboutRoutes.Person> contributors = period.contributors();
            for (int i = 0, count = contributors.size(); i < count; i++) {
                AboutRoutes.Person person = contributors.get(i);
                places.putIfAbsent(person.url(), i);
                everyone.putIfAbsent(person.url(), person);
            }
            placesByPeriod.add(places);
        }

        html.append("<div class=\"section period-list\"><div class=\"people-grid\">");
        for (AboutRoutes.Person person : everyone.values()) {
            StringBuilder attributes = new StringBuilder(" data-places=\"");
            for (int i = 0; i < size; i++) {
                if (i > 0) attributes.append(',');
                attributes.append(placesByPeriod.get(i).getOrDefault(person.url(), -1));
            }
            attributes.append('"');
            Integer firstPlace = placesByPeriod.get(0).get(person.url());
            if (firstPlace == null) attributes.append(" style=\"display: none\"");
            AboutDialogStyle.appendPerson(html, person, firstPlace == null ? Integer.MAX_VALUE : firstPlace,
                    attributes.toString());
        }
        html.append("</div></div>");
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
