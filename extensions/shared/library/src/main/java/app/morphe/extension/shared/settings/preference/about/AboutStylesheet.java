/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2221
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference.about;

/**
 * The stylesheet of the pages {@link AboutDialogStyle} builds, with the colors of the theme left
 * as placeholders for it to fill in.
 * <p>
 * Surfaces are drawn with neutral gray overlays rather than tints of the foreground color, so a
 * single stylesheet reads correctly against both the light and the dark app backgrounds.
 */
final class AboutStylesheet {

    private AboutStylesheet() {
    }

    /**
     * What every page is made of: the screen with its header and footer, the About page itself,
     * and the rows and license texts of the lists.
     */
    static final String PAGE = """
            * {
                margin: 0;
                padding: 0;
                box-sizing: border-box;
            }
            body {
                background: {bg};
                color: {fg};
                /* The font of the device, which Morphe itself is set in. */
                font-family: sans-serif;
                -webkit-tap-highlight-color: transparent;
                -webkit-touch-callout: none;
                -webkit-user-select: none;
                user-select: none;
            }

            /* A page as tall as the screen, its content over a footer that stays in place as it scrolls. */
            .screen {
                min-height: 100vh;
                display: flex;
                flex-direction: column;
                opacity: 0;
                transition: opacity 220ms ease-out;
            }
            /* Set once the page is laid out at its final size, so it eases in where it will stay. */
            .screen.ready {
                opacity: 1;
            }
            .page {
                flex: 1;
            }
            /* One block in the middle of the screen, its footer right under its content. */
            .screen.centered {
                justify-content: center;
            }
            .screen.centered .page {
                flex: none;
            }
            .screen.centered .footer {
                position: static;
                padding-top: 4px;
                background: none;
            }
            /* One column on a wide screen, the width Morphe itself keeps its dialogs to. */
            .dialog-header, .section, .footer {
                width: 100%;
                max-width: 560px;
                margin-left: auto;
                margin-right: auto;
            }
            .footer {
                position: sticky;
                bottom: 0;
                /* Over the pictures, which stack above plain content while they animate. */
                z-index: 2;
                display: flex;
                flex-direction: column;
                gap: 8px;
                padding: 20px 16px 16px;
                background: linear-gradient(to bottom, transparent, {bg} 20px);
            }
            .close {
                display: flex;
                align-items: center;
                justify-content: center;
                height: 52px;
                border-radius: 999px;
                border: 1px solid rgba(128, 128, 128, 0.45);
                color: inherit;
                font-size: 14px;
                font-weight: 500;
                text-decoration: none;
            }
            .close:active {
                background: rgba(128, 128, 128, 0.12);
            }
            /* Header. The About dialog fills it with the logo beside the name, the people dialogs with a title. */
            .dialog-header {
                padding: 20px 16px 0;
            }
            .about-header {
                display: flex;
                align-items: center;
                gap: 16px;
            }
            .app-logo {
                width: 64px;
                height: 64px;
                flex-shrink: 0;
                border-radius: 20px;
                /* Leaves the mark as large in its tile as Morphe itself draws it. */
                padding: 12px;
                background: rgba(128, 128, 128, 0.12);
            }
            .app-logo svg {
                display: block;
                width: 100%;
                height: 100%;
            }
            .app-name {
                font-size: 24px;
                font-weight: 700;
                line-height: 30px;
            }
            .app-component {
                font-size: 14px;
                line-height: 20px;
                opacity: 0.7;
            }
            .dialog-title {
                font-size: 20px;
                font-weight: 700;
            }
            .dialog-subtitle {
                font-size: 14px;
                opacity: 0.7;
                margin-top: 2px;
            }
            /* The head of a list: its icon on a disc of the accent, beside what the list is. */
            .list-header {
                display: flex;
                align-items: center;
                gap: 12px;
                /* Stays in place as the list scrolls on under it, fading the rows out as they reach it. */
                position: sticky;
                top: 0;
                z-index: 2;
                /* As far under the status bar as Morphe itself sets the head of a list. */
                padding-top: 12px;
                padding-bottom: 20px;
                background: linear-gradient(to top, transparent, {bg} 16px);
            }
            .list-header + .section {
                padding-top: 0;
            }
            .header-icon {
                display: flex;
                align-items: center;
                justify-content: center;
                width: 44px;
                height: 44px;
                flex-shrink: 0;
                border-radius: 50%;
                background: {accent}33;
            }
            .header-icon svg {
                width: 24px;
                height: 24px;
                fill: {accent};
            }

            /* The version, over what is known about a newer one. */
            .version-card {
                margin-top: 16px;
                padding: 16px;
                border-radius: 20px;
                background: rgba(128, 128, 128, 0.12);
            }
            .version-label-row {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 12px;
                min-height: 24px;
            }
            .version-label {
                font-size: 14px;
                font-weight: 500;
                opacity: 0.7;
            }
            .version {
                font-size: 24px;
                font-weight: 600;
                line-height: 32px;
                margin-top: 8px;
                overflow-wrap: anywhere;
            }
            .version-status {
                margin-top: 10px;
                padding-top: 10px;
                border-top: 1px solid rgba(128, 128, 128, 0.20);
                font-size: 14px;
                font-weight: 600;
                line-height: 20px;
            }
            .version-status.update {
                display: flex;
                align-items: center;
                gap: 12px;
                color: {accent};
            }
            .version-status-text {
                flex: 1;
                min-width: 0;
            }
            .version-hint {
                font-size: 13px;
                font-weight: 400;
                line-height: 18px;
                color: {fg};
                opacity: 0.7;
                margin-top: 2px;
            }
            .update-button {
                flex-shrink: 0;
                height: 40px;
                line-height: 40px;
                padding: 0 18px;
                border-radius: 999px;
                background: {accent};
                color: {on-accent};
                font-size: 14px;
                font-weight: 600;
                text-decoration: none;
                white-space: nowrap;
            }
            .badge {
                flex-shrink: 0;
                font-size: 12px;
                font-weight: 500;
                line-height: 16px;
                padding: 3px 10px;
                border-radius: 999px;
                border: 1px solid rgba(128, 128, 128, 0.35);
                white-space: nowrap;
            }

            /* Links and lists of people, two to a row and each named in words. */
            .group-label {
                font-size: 14px;
                font-weight: 500;
                opacity: 0.7;
                padding: 0 4px;
                margin-bottom: 8px;
            }
            .tiles {
                display: grid;
                grid-template-columns: repeat(2, minmax(0, 1fr));
                gap: 8px;
            }
            .tile {
                display: flex;
                align-items: center;
                gap: 8px;
                min-height: 52px;
                padding: 6px 12px;
                border-radius: 16px;
                background: rgba(128, 128, 128, 0.12);
                text-decoration: none;
                color: inherit;
                font-size: 14px;
                font-weight: 500;
            }
            .tile:active {
                background: rgba(128, 128, 128, 0.20);
            }
            .tile svg {
                width: 24px;
                height: 24px;
                flex-shrink: 0;
                fill: {accent};
            }
            /* A second line for the languages a name runs long in, rather than cutting it short. */
            .tile-title {
                flex: 1;
                min-width: 0;
                line-height: 19px;
                display: -webkit-box;
                -webkit-box-orient: vertical;
                -webkit-line-clamp: 2;
                overflow: hidden;
                overflow-wrap: anywhere;
            }
            /* What keeps the project going leads the links on a row of its own. */
            .tile.wide {
                grid-column: 1 / -1;
                background: {accent}1F;
            }

            /* A heading followed by the card holding that section's rows. */
            .section {
                padding: 16px 16px 12px;
            }
            .section + .section {
                padding-top: 4px;
            }
            .section-title {
                font-size: 13px;
                font-weight: 600;
                opacity: 0.7;
                padding: 0 4px;
                margin-bottom: 8px;
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
            """;

    /**
     * The people of the Contributors and Supporters lists: their pictures, the grid they stand in
     * and how a picture comes in.
     */
    static final String PEOPLE = """
            /* A person: their picture over the initial that stands in for one. */
            .avatar {
                position: relative;
                width: 36px;
                height: 36px;
                border-radius: 50%;
                background: rgba(128, 128, 128, 0.16);
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 15px;
                font-weight: 600;
                color: {accent};
                flex-shrink: 0;
                overflow: hidden;
                animation: avatar-in 220ms cubic-bezier(0.34, 1.56, 0.64, 1) both;
            }
            @keyframes avatar-in {
                from {
                    opacity: 0;
                    transform: scale(0.6);
                }
            }
            /* Nothing moves for someone who turned animations off. */
            @media (prefers-reduced-motion: reduce) {
                .avatar, .person.mega .avatar {
                    animation: none;
                }
            }
            /* The initial goes once the picture is there, or it would show through a clear background. */
            .avatar.loaded {
                color: transparent;
            }
            .avatar.loaded img {
                opacity: 1;
            }
            .avatar img {
                opacity: 0;
                transition: opacity 220ms;
                position: absolute;
                left: 0;
                top: 0;
                width: 100%;
                height: 100%;
                object-fit: cover;
            }
            .people-grid {
                display: grid;
                /* As many to a row as Morphe itself fits, which gives a person the same least width. */
                grid-template-columns: repeat(auto-fill, minmax(84px, 1fr));
                gap: 14px 0;
            }
            .person {
                display: flex;
                flex-direction: column;
                align-items: center;
                gap: 6px;
                min-width: 0;
                text-decoration: none;
                color: inherit;
            }
            .person .avatar {
                width: 56px;
                height: 56px;
                font-size: 20px;
            }
            /* A Mega Supporter, in the yellow ring Morphe itself draws, with softer ones forever
               widening out of it and fading, one setting off as the one before it is halfway out. */
            .person.mega .avatar {
                box-shadow: 0 0 0 2px {bg}, 0 0 0 4px {mega};
                animation: avatar-in 220ms cubic-bezier(0.34, 1.56, 0.64, 1) both, ring-pulse 2400ms linear infinite;
            }
            @keyframes ring-pulse {
                0% {
                    box-shadow: 0 0 0 2px {bg}, 0 0 0 4px {mega}, 0 0 0 4px {mega}CC, 0 0 0 8px {mega}66;
                }
                50% {
                    box-shadow: 0 0 0 2px {bg}, 0 0 0 4px {mega}, 0 0 0 8px {mega}66, 0 0 0 12px {mega}00;
                }
                /* The wave that has faded out sets off again from the ring. */
                50.01% {
                    box-shadow: 0 0 0 2px {bg}, 0 0 0 4px {mega}, 0 0 0 8px {mega}66, 0 0 0 4px {mega}CC;
                }
                100% {
                    box-shadow: 0 0 0 2px {bg}, 0 0 0 4px {mega}, 0 0 0 12px {mega}00, 0 0 0 8px {mega}66;
                }
            }
            .person-name {
                max-width: 100%;
                font-size: 12px;
                font-weight: 500;
                line-height: 16px;
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }
            .empty {
                text-align: center;
                font-size: 14px;
                opacity: 0.7;
                padding: 8px 16px;
            }
            .cta {
                display: flex;
                align-items: center;
                justify-content: center;
                gap: 10px;
                height: 50px;
                border-radius: 999px;
                background: {accent};
                color: {on-accent};
                font-size: 15px;
                font-weight: 600;
                text-decoration: none;
            }
            .cta svg {
                width: 20px;
                height: 20px;
                fill: {on-accent};
            }
            """;
}
