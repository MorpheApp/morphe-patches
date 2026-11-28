/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference.about;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.util.TypedValue;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * HTML scaffolding shared by the About dialog and the dialogs listing people, styled to match Morphe.
 * <p>
 * The dialogs render into a WebView, so the look has to be rebuilt in CSS, see
 * {@link AboutStylesheet}. Holding the page scaffolding, the icon set and the document direction
 * in one place keeps them consistent.
 */
final class AboutDialogStyle {

    private AboutDialogStyle() {
    }

    /**
     * Morphe logo gradient, which the logo is drawn in and an app without a usable accent of its
     * own takes its accent from.
     */
    private static final String MORPHE_BLUE = "#1E5AA8";
    private static final String MORPHE_TEAL = "#00AFAE";

    // The yellow of a Mega Supporter, the warning color of Morphe's own dark and light themes.
    private static final String MEGA_DARK = "#F5BD48";
    private static final String MEGA_LIGHT = "#7C5800";

    private static final int AVATAR_STAGGER_MILLIS = 30;
    private static final int AVATAR_STAGGERED = 32;

    // Width of the picture of a person in the grid, in CSS pixels, which are dips.
    private static final int PERSON_AVATAR_DIPS = 56;

    // The size a GitHub picture is served at, which the API sets for a smaller picture than ours.
    private static final Pattern GITHUB_AVATAR_SIZE =
            Pattern.compile("^(https://avatars\\.githubusercontent\\.com/.*[?&]s=)\\d+$");

    // Least difference in luma, out of 255, at which an accent still shows on the background
    private static final int MIN_ACCENT_CONTRAST = 60;

    // Material Symbols outlined, 24dp. The same icons Morphe shows for these entries.
    private static final String PATH_PUBLIC =
            "M480-80q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 "
                    + "31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm-40-82v-78q-33 "
                    + "0-56.5-23.5T360-320v-40L168-552q-3 18-5.5 36t-2.5 36q0 121 79.5 212T440-162Zm276-102q20-22 "
                    + "36-47.5t26.5-53q10.5-27.5 16-56.5t5.5-59q0-98-54.5-179T600-776v16q0 33-23.5 56.5T520-680h-80v80q0 "
                    + "17-11.5 28.5T400-560h-80v80h240q17 0 28.5 11.5T600-440v120h40q26 0 47 15.5t29 40.5Z";
    private static final String PATH_FAVORITE =
            "m480-120-58-52q-101-91-167-157T150-447.5Q111-500 95.5-544T80-634q0-94 63-157t157-63q52 0 99 22t81 62q34-40 "
                    + "81-62t99-22q94 0 157 63t63 157q0 46-15.5 90T810-447.5Q771-395 705-329T538-172l-58 52Zm0-108q96-86 "
                    + "158-147.5t98-107q36-45.5 50-81t14-70.5q0-60-40-100t-100-40q-47 0-87 26.5T518-680h-76q-15-41-55-67.5T300-774q-60 "
                    + "0-100 40t-40 100q0 35 14 70.5t50 81q36 45.5 98 107T480-228Zm0-273Z";
    private static final String PATH_TRANSLATE =
            "m476-80 182-480h84L924-80h-84l-43-122H603L560-80h-84ZM160-200l-56-56 202-202q-35-35-63.5-80T190-640h84q20 39 "
                    + "40 68t48 58q33-33 68.5-92.5T484-720H40v-80h280v-80h80v80h280v80H564q-21 72-63 148t-83 116l96 98-30 "
                    + "82-122-125-202 201Zm468-72h144l-72-204-72 204Z";
    private static final String PATH_GROUP =
            "M40-160v-112q0-34 17.5-62.5T104-378q62-31 126-46.5T360-440q66 0 130 15.5T616-378q29 15 46.5 43.5T680-272v112H40Zm720 "
                    + "0v-120q0-44-24.5-84.5T666-434q51 6 96 20.5t84 35.5q36 20 55 44.5t19 53.5v120H760ZM360-480q-66 "
                    + "0-113-47t-47-113q0-66 47-113t113-47q66 0 113 47t47 113q0 66-47 113t-113 47Zm400-160q0 66-47 "
                    + "113t-113 47q-11 0-28-2.5t-28-5.5q27-32 41.5-71t14.5-81q0-42-14.5-81T544-792q14-5 28-6.5t28-1.5q66 "
                    + "0 113 47t47 113ZM120-240h480v-32q0-11-5.5-20T580-306q-54-27-109-40.5T360-360q-56 0-111 13.5T140-306q-9 "
                    + "5-14.5 14t-5.5 20v32Zm240-320q33 0 56.5-23.5T440-640q0-33-23.5-56.5T360-720q-33 0-56.5 23.5T280-640q0 "
                    + "33 23.5 56.5T360-560Zm0 320Zm0-400Z";
    private static final String PATH_DESCRIPTION =
            "M320-240h320v-80H320v80Zm0-160h320v-80H320v80ZM240-80q-33 0-56.5-23.5T160-160v-640q0-33 23.5-56.5T240-880h320l240 "
                    + "240v480q0 33-23.5 56.5T720-80H240Zm280-520v-200H240v640h480v-440H520ZM240-800v200-200 640-640Z";
    private static final String PATH_DATE_RANGE = "M320-400q-17 0-28.5-11.5T280-440q0-17 11.5-28.5T320-480q17 0 28.5 11.5T360-440q0 17-11.5 28.5T320-400Zm160 0q-17 0-28.5-11.5T440-440q0-17 11.5-28.5T480-480q17 0 28.5 11.5T520-440q0 17-11.5 28.5T480-400Zm160 0q-17 0-28.5-11.5T600-440q0-17 11.5-28.5T640-480q17 0 28.5 11.5T680-440q0 17-11.5 28.5T640-400ZM200-80q-33 0-56.5-23.5T120-160v-560q0-33 23.5-56.5T200-800h40v-40q0-17 11.5-28.5T280-880q17 0 28.5 11.5T320-840v40h320v-40q0-17 11.5-28.5T680-880q17 0 28.5 11.5T720-840v40h40q33 0 56.5 23.5T840-720v560q0 33-23.5 56.5T760-80H200Zm0-80h560v-400H200v400Zm0-480h560v-80H200v80Zm0 0v-80v80Z";
    private static final String PATH_CHECK = "M382-354l339-339q12-12 28-12t28 12q12 12 12 28.5T777-636L410-268q-12 12-28 12t-28-12L182-440q-12-12-11.5-28.5T183-497q12-12 28.5-12t28.5 12l142 143Z";
    private static final String PATH_DOWNLOAD = "M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z";
    private static final String PATH_CHEVRON = "M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z";
    private static final String PATH_CODE =
            "M320-240 80-480l240-240 57 57-184 184 183 183-56 56Zm320 0-57-57 184-184-183-183 56-56 240 240-240 240Z";
    private static final String PATH_VOLUNTEER =
            "M640-440 474-602q-31-30-52.5-66.5T400-748q0-55 38.5-93.5T532-880q32 0 60 13.5t48 36.5q20-23 48-36.5t60-13.5q55 "
                    + "0 93.5 38.5T880-748q0 43-21 79.5T807-602L640-440Zm0-112 109-107q19-19 35-40.5t16-48.5q0-22-15-37t-37-15q-14 "
                    + "0-26.5 5.5T700-778l-60 72-60-72q-9-11-21.5-16.5T532-800q-22 0-37 15t-15 37q0 27 16 48.5t35 40.5l109 "
                    + "107ZM280-220l278 76 238-74q-5-9-14.5-15.5T760-240H558q-27 0-43-2t-33-8l-93-31 22-78 81 27q17 5 40 8t68 4q0-11-6.5-21T578-354l-234-86h-64v220ZM40-80v-440h304q7 "
                    + "0 14 1.5t13 3.5l235 87q33 12 53.5 42t20.5 66h80q50 0 85 33t35 87v40L560-60l-280-78v58H40Zm80-80h80v-280h-80v280Zm520-546Z";
    private static final String PATH_FORUM =
            "M280-240q-17 0-28.5-11.5T240-280v-80h520v-360h80q17 0 28.5 11.5T880-680v600L720-240H280ZM80-280v-560q0-17 "
                    + "11.5-28.5T120-880h520q17 0 28.5 11.5T680-840v360q0 17-11.5 28.5T640-440H240L80-280Zm520-240v-280H160v280h440Zm-440 "
                    + "0v-280 280Z";

    /**
     * Wraps text in Unicode isolate marks so a left to right token such as a version name keeps its
     * internal order when placed inside right to left text. Without this the bidi algorithm splits
     * {@code 1.37.0-dev.4} at the hyphen and renders it as {@code dev.4-1.37.0}.
     */
    static String isolateLtr(String text) {
        return '\u2066' + text + '\u2069';
    }

    private static String materialIcon(String path) {
        return "<svg viewBox='0 -960 960 960'><path d='" + path + "'/></svg>";
    }

    /**
     * @return An icon for a link, matched on the URL because link names arrive localized.
     */
    static String linkIcon(String url) {
        if (url == null) return materialIcon(PATH_PUBLIC);

        String lower = url.toLowerCase(Locale.US);
        if (lower.contains("github.com")) return materialIcon(PATH_CODE);
        if (lower.contains("reddit.com")) return materialIcon(PATH_FORUM);
        if (lower.contains("crowdin") || lower.contains("translate")) return materialIcon(PATH_TRANSLATE);
        if (lower.contains("donat")) return materialIcon(PATH_FAVORITE);
        if (lower.startsWith("https://license")) return materialIcon(PATH_DESCRIPTION);
        if (lower.contains("credits")) return materialIcon(PATH_GROUP);
        if (lower.contains("supporters")) return materialIcon(PATH_VOLUNTEER);
        return materialIcon(PATH_PUBLIC);
    }

    static String donateIcon() {
        return materialIcon(PATH_FAVORITE);
    }

    static String chevron() {
        return "<span class=\"item-chevron\">" + materialIcon(PATH_CHEVRON) + "</span>";
    }

    /**
     * @return The text with the characters that would otherwise be read as markup escaped, safe
     *         both between tags and inside a quoted attribute.
     */
    static String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /**
     * Shows the page once it has been laid out at the size of its window. A WebView first lays a
     * page out before it has a size of its own, so content shown from the start is seen to jump.
     */
    private static final String PAGE_REVEAL_SCRIPT = "<script>requestAnimationFrame(function () {"
            + "requestAnimationFrame(function () {"
            + "document.querySelector('.screen').classList.add('ready');"
            + "});});</script>";

    /**
     * The mark of the Morphe logo, drawn in place so it needs no network and is there from the start.
     */
    static final String LOGO = "<svg viewBox='63 88 386 340'>"
            + "<defs><linearGradient id='morphe-mark' gradientUnits='userSpaceOnUse' x1='83' y1='216' x2='429' y2='216'>"
            + "<stop offset='0' stop-color='" + MORPHE_BLUE + "'/><stop offset='1' stop-color='" + MORPHE_TEAL + "'/>"
            + "</linearGradient></defs>"
            + "<path fill='" + MORPHE_BLUE + "' d='M123,140 c-21,0 -39,17 -40,38 v192 c1,21 19,38 40,38 21,0 39,-17 "
            + "40,-38 V178 c-1,-21 -19,-38 -40,-38 z'/>"
            + "<path fill='" + MORPHE_TEAL + "' d='M349,285 v85 c1,21 19,38 40,38 21,0 39,-17 40,-38 V182 c-11,-14 "
            + "-74,63 -80,103 z'/>"
            + "<path fill='url(#morphe-mark)' d='M127,108 c-34,0 -44,25 -44,40 v54 c30,-33 75,27 80,33 28,32 44,87 "
            + "93,89 48,-2 67,-56 93,-89 0,0 45,-74 80,-80 0,-28 -11,-47 -44,-47 -34,0 -58,50 -75,72 -17,22 -25,46 "
            + "-54,46 -29,0 -38,-25 -54,-46 -17,-22 -41,-72 -75,-72 z'/>"
            + "</svg>";

    /**
     * Dummy url of the button closing a dialog.
     */
    static final String CLOSE_LINK_PLACEHOLDER_URL = "https://morphe.software/close/";

    /**
     * @param centered Whether the content and its footer stand together in the middle of the
     *                 screen, as a dialog that is one block does. A list starts at the top instead,
     *                 over a footer that stays at the bottom as it scrolls.
     * @return The opening of a page that fills the screen, see {@link #pageEnd}.
     */
    static String pageStart(boolean centered) {
        return centered ? "<div class=\"screen centered\"><div class=\"page\">" : "<div class=\"screen\"><div class=\"page\">";
    }

    /**
     * @param closeLabel  Label of the button closing the dialog.
     * @param footerExtra What stands over that button, or null for the button alone.
     * @return The closing of a page, with the footer that stays in place under its content.
     */
    static String pageEnd(String closeLabel, @Nullable String footerExtra) {
        return "</div><div class=\"footer\">" + (footerExtra == null ? "" : footerExtra)
                + "<a href=\"" + CLOSE_LINK_PLACEHOLDER_URL + "\" class=\"close\">" + closeLabel + "</a></div></div>"
                + PAGE_REVEAL_SCRIPT;
    }

    static String dateRangeIcon() {
        return materialIcon(PATH_DATE_RANGE);
    }

    static String checkIcon() {
        return materialIcon(PATH_CHECK);
    }

    static String downloadIcon() {
        return materialIcon(PATH_DOWNLOAD);
    }

    /**
     * Sets the head of a list on a band once the list scrolls on under it, so it reads as standing
     * over the list rather than as a line of it.
     */
    private static final String HEADER_BAND_SCRIPT = "<script>(function () {"
            + "var header = document.querySelector('.list-header');"
            + "function update() { header.classList.toggle('scrolled', window.scrollY > 0); }"
            + "window.addEventListener('scroll', update, { passive: true });"
            + "update();"
            + "})();</script>";

    /**
     * @param icon Icon of the list, on a disc beside its title, see {@link #linkIcon}.
     * @return The head of a dialog listing people: what the list is, over a line summing it up.
     */
    static String peopleHeader(String icon, String title, String subtitle) {
        return peopleHeader(icon, title, subtitle, null);
    }

    /**
     * @param actions What stands at the end of the header, such as a button opening a menu, or
     *                null for nothing.
     */
    static String peopleHeader(String icon, String title, String subtitle, @Nullable String actions) {
        return "<div class=\"dialog-header list-header\"><span class=\"header-icon\">" + icon
                + "</span><div class=\"header-text\"><div class=\"dialog-title\">" + title
                + "</div><div class=\"dialog-subtitle\">" + subtitle + "</div></div>"
                + (actions == null ? "" : actions) + "</div>" + HEADER_BAND_SCRIPT;
    }

    /**
     * @param imageUrl Picture drawn over the initial of the name, which shows until the picture
     *                 loads and stays if it never does. Null for the initial alone.
     * @param order    Place in the wave the pictures on screen come in by, each a moment after
     *                 the one before it.
     * @return A round picture of someone, or of a project, going by that name.
     */
    static String avatar(String name, @Nullable String imageUrl, int order) {
        // Pictures past the first screenful come in at once, as they are scrolled to.
        StringBuilder html = new StringBuilder("<span class=\"avatar\" style=\"animation-delay:")
                .append(order < AVATAR_STAGGERED ? order * AVATAR_STAGGER_MILLIS : 0)
                .append("ms\">");
        if (!name.isEmpty()) {
            html.append(escapeHtml(name.substring(0, name.offsetByCodePoints(0, 1))
                    .toUpperCase(Locale.getDefault())));
        }
        if (imageUrl != null) {
            html.append("<img loading=\"lazy\" src=\"").append(escapeHtml(imageUrl))
                    .append("\" onload=\"this.parentElement.classList.add('loaded');\" onerror=\"this.remove();\" />");
        }
        return html.append("</span>").toString();
    }

    /**
     * @return The url of a GitHub picture at as many pixels as a picture of a person is drawn
     *         across on this screen, or the url as it is if it is not one.
     */
    @Nullable
    private static String personAvatarUrl(@Nullable String url) {
        if (url == null) return null;
        Matcher matcher = GITHUB_AVATAR_SIZE.matcher(url);
        if (!matcher.matches()) return url;
        final int pixels = (int) Math.ceil(PERSON_AVATAR_DIPS * Resources.getSystem().getDisplayMetrics().density);
        return matcher.group(1) + pixels;
    }

    /**
     * Appends people as a grid of pictures, each over a name and opening that person's page.
     */
    static void appendPeople(StringBuilder html, List<AboutRoutes.Person> people) {
        html.append("<div class=\"people-grid\">");
        for (int i = 0, size = people.size(); i < size; i++) {
            appendPerson(html, people.get(i), i, "");
        }
        html.append("</div>");
    }

    /**
     * Appends someone of a grid of people, see {@link #appendPeople}.
     *
     * @param order      Place in the wave the pictures on screen come in by, see {@link #avatar}.
     * @param attributes Further attributes of the element, each led by a space.
     */
    static void appendPerson(StringBuilder html, AboutRoutes.Person person, int order, String attributes) {
        html.append("<a href=\"").append(escapeHtml(person.url()))
                .append(person.mega() ? "\" class=\"person mega\"" : "\" class=\"person\"")
                .append(attributes).append('>')
                .append(avatar(person.name(), personAvatarUrl(person.avatar()), order))
                .append("<span class=\"person-name\">").append(escapeHtml(person.name()))
                .append("</span></a>");
    }

    /**
     * @return A heading for the group of rows that follows it.
     */
    static String sectionTitle(String text) {
        return "<div class=\"section-title\">" + text + "</div>";
    }

    /**
     * @return Everything up to and including the opening body tag, with the document direction and
     *         theme colors already applied. The direction follows the Morphe language override
     *         rather than the device locale, since that is what the dialog strings are resolved to.
     */
    static String documentStart(Context context) {
        final int accent = accentColor(context);
        return DOCUMENT_START
                .replace("{dir}", Utils.isRightToLeftLocale(
                        BaseSettings.MORPHE_LANGUAGE.get().getLocale()) ? "rtl" : "ltr")
                .replace("{bg}", Utils.getColorHexString(ThemeUtils.getAppBackgroundColor()))
                .replace("{fg}", Utils.getColorHexString(ThemeUtils.getAppForegroundColor()))
                .replace("{accent}", String.format(Locale.US, "#%06X", accent & 0xFFFFFF))
                // Whichever of black and white stands out of the accent.
                .replace("{on-accent}", luma(accent) >= 128 ? "#101010" : "#FFFFFF")
                .replace("{mega}", Utils.isDarkModeEnabled() ? MEGA_DARK : MEGA_LIGHT);
    }

    /**
     * The accent of icons and highlights: the one the theme of the app gives its own controls,
     * which the sliders of the Morphe settings take too, so the page matches the settings it is
     * opened from. An app whose accent would not show on the background of the page gets a color
     * of the Morphe logo instead.
     */
    @ColorInt
    private static int accentColor(Context context) {
        @ColorInt int fallback = Color.parseColor(Utils.isDarkModeEnabled() ? MORPHE_TEAL : MORPHE_BLUE);

        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)) {
            return fallback;
        }

        @ColorInt int accent;
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            accent = value.data;
        } else if (value.resourceId != 0) {
            try {
                accent = context.getColor(value.resourceId);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not read the accent color of the app", ex);
                return fallback;
            }
        } else {
            return fallback;
        }

        final int contrast = Math.abs(luma(accent) - luma(ThemeUtils.getAppBackgroundColor()));
        return contrast < MIN_ACCENT_CONTRAST ? fallback : accent;
    }

    /**
     * @return Rec. 601 luma of a color from 0 to 255, which weights a channel the way the eye
     *         responds to it.
     */
    private static int luma(@ColorInt int color) {
        return (299 * Color.red(color) + 587 * Color.green(color) + 114 * Color.blue(color)) / 1000;
    }

    static final String DOCUMENT_END = "</body></html>";

    private static final String DOCUMENT_START = """
            <html dir="{dir}">
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
            """ + AboutStylesheet.PAGE + AboutStylesheet.PEOPLE + """
                </style>
            </head>
            <body>
            """;
}
