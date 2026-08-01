/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference.about;

import java.util.Locale;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * HTML scaffolding shared by the About and Credits dialogs, styled to match Morphe.
 * <p>
 * Both dialogs render into a WebView, so the look has to be rebuilt in CSS. Holding the
 * stylesheet, the icon set and the document direction in one place keeps them consistent.
 */
final class AboutDialogStyle {

    private AboutDialogStyle() {
    }

    /**
     * Morphe logo gradient.
     */
    private static final String MORPHE_BLUE = "#1E5AA8";
    private static final String MORPHE_TEAL = "#00AFAE";

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
    private static final String PATH_CHEVRON = "M504-480 320-664l56-56 240 240-240 240-56-56 184-184Z";
    private static final String PATH_CODE =
            "M320-240 80-480l240-240 57 57-184 184 183 183-56 56Zm320 0-57-57 184-184-183-183 56-56 240 240-240 240Z";
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

    /**
     * @return The accent used for icons and highlights, picked from the logo gradient so it stays
     *         legible against the current dialog background.
     */
    private static String accentColor() {
        return Utils.isDarkModeEnabled() ? MORPHE_TEAL : MORPHE_BLUE;
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
        return materialIcon(PATH_PUBLIC);
    }

    static String chevron() {
        return "<span class=\"item-chevron\">" + materialIcon(PATH_CHEVRON) + "</span>";
    }

    /**
     * @return The text with the characters that would otherwise be read as markup escaped.
     */
    static String escapeHtml(String text) {
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /**
     * @return The escaped text with any urls in it turned into links, so the addresses named inside
     *         a license can be opened.
     */
    static String linkifyHtml(String text) {
        return escapeHtml(text)
                .replaceAll("(https?://[^\\s<>\"]+)", "<a href='$1'>$1</a>");
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
    static String documentStart() {
        return DOCUMENT_START
                .replace("{dir}", Utils.isRightToLeftLocale(
                        BaseSettings.MORPHE_LANGUAGE.get().getLocale()) ? "rtl" : "ltr")
                .replace("{bg}", Utils.getColorHexString(Utils.getDialogBackgroundColor()))
                .replace("{fg}", Utils.getColorHexString(Utils.getAppForegroundColor()))
                .replace("{accent}", accentColor())
                .replace("{blue}", MORPHE_BLUE)
                .replace("{teal}", MORPHE_TEAL);
    }

    static final String DOCUMENT_END = "</body></html>";

    /**
     * Surfaces are drawn with neutral gray overlays rather than tints of the foreground color, so a
     * single stylesheet reads correctly against both the light and the AMOLED dialog backgrounds.
     */
    private static final String DOCUMENT_START = """
            <html dir="{dir}">
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    * {
                        margin: 0;
                        padding: 0;
                        box-sizing: border-box;
                    }
                    body {
                        background: {bg};
                        color: {fg};
                        font-family: Roboto, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                        -webkit-tap-highlight-color: transparent;
                        -webkit-touch-callout: none;
                        -webkit-user-select: none;
                        user-select: none;
                    }

                    /* Header. The About dialog fills it with the logo, the Credits dialog with a title. */
                    .dialog-header {
                        padding: 24px 20px 4px;
                        text-align: center;
                    }
                    .app-logo {
                        width: 96px;
                        height: 96px;
                        margin: 0 auto 14px;
                        border-radius: 24px;
                        background: linear-gradient(135deg, {blue} 0%, {teal} 100%);
                        padding: 8px;
                    }
                    .app-logo-inner {
                        width: 100%;
                        height: 100%;
                        border-radius: 18px;
                        background: #EEEEEE;
                        overflow: hidden;
                    }
                    .app-logo img {
                        width: 100%;
                        height: 100%;
                        object-fit: contain;
                    }
                    .app-name {
                        font-size: 22px;
                        font-weight: 700;
                    }
                    .dialog-title {
                        font-size: 18px;
                        font-weight: 700;
                    }

                    /* Version status and dev build notes. */
                    .info-card {
                        margin-top: 14px;
                        padding: 12px 14px;
                        border-radius: 14px;
                        text-align: center;
                        background: rgba(128, 128, 128, 0.10);
                        border: 1px solid rgba(128, 128, 128, 0.16);
                    }
                    .info-card h3 {
                        font-size: 13px;
                        font-weight: 600;
                        color: {accent};
                        margin-bottom: 3px;
                    }
                    .info-card p {
                        font-size: 12px;
                        opacity: 0.7;
                        line-height: 1.45;
                    }

                    /* A heading followed by the card holding that section's rows. */
                    .section {
                        padding: 20px 16px 16px;
                    }
                    .section + .section {
                        padding-top: 4px;
                    }
                    .section-title {
                        font-size: 15px;
                        font-weight: 700;
                        margin-bottom: 12px;
                        text-align: center;
                    }

                    .settings-group {
                        border-radius: 18px;
                        background: rgba(128, 128, 128, 0.10);
                        border: 1px solid rgba(128, 128, 128, 0.16);
                        overflow: hidden;
                    }
                    .settings-item {
                        position: relative;
                        display: flex;
                        align-items: center;
                        gap: 12px;
                        padding: 14px 16px;
                        text-decoration: none;
                        color: inherit;
                    }
                    .settings-item:active {
                        background: rgba(128, 128, 128, 0.12);
                    }
                    /* Drawn as a pseudo element rather than a border, so it stays inset from the card edges. */
                    .settings-item + .settings-item::before {
                        content: '';
                        position: absolute;
                        top: 0;
                        left: 16px;
                        right: 16px;
                        height: 1px;
                        background: rgba(128, 128, 128, 0.20);
                    }
                    .item-icon {
                        display: flex;
                        flex-shrink: 0;
                    }
                    .item-icon svg {
                        width: 22px;
                        height: 22px;
                        fill: {accent};
                    }
                    .item-text {
                        flex: 1;
                        min-width: 0;
                    }
                    .item-title {
                        font-size: 15px;
                        font-weight: 500;
                    }
                    .item-subtitle {
                        font-size: 12px;
                        opacity: 0.6;
                        margin-top: 2px;
                    }
                    .item-chevron {
                        display: flex;
                        flex-shrink: 0;
                        opacity: 0.55;
                    }
                    .item-chevron svg {
                        width: 20px;
                        height: 20px;
                        fill: {accent};
                    }
                    /* Material ships no mirrored chevron, so flip it for right to left layouts. */
                    html[dir="rtl"] .item-chevron svg {
                        transform: scaleX(-1);
                    }

                    /* Bundled license text. Kept selectable, since users do copy legal notices. */
                    .license-body {
                        padding: 16px;
                        -webkit-user-select: text;
                        user-select: text;
                    }
                    .license-body h2 {
                        font-size: 13px;
                        font-weight: 600;
                        color: {accent};
                        word-break: break-all;
                        border-bottom: 1px solid rgba(128, 128, 128, 0.20);
                        padding-bottom: 6px;
                        margin-top: 24px;
                    }
                    .license-body h2:first-of-type {
                        margin-top: 0;
                    }
                    .license-body pre {
                        margin: 0;
                        font-family: monospace;
                        font-size: 13px;
                        line-height: 1.6;
                        white-space: pre;
                    }
                    .license-body a {
                        color: {accent};
                    }

                    /* Credits contributor initial. */
                    .avatar {
                        width: 30px;
                        height: 30px;
                        border-radius: 50%;
                        background: linear-gradient(135deg, {blue} 0%, {teal} 100%);
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        font-size: 13px;
                        font-weight: 700;
                        color: #FFFFFF;
                        flex-shrink: 0;
                    }
                </style>
            </head>
            <body>
            """;
}
