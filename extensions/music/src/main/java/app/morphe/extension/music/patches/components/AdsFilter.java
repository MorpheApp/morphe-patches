/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.components;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;

@SuppressWarnings("unused")
public final class AdsFilter extends Filter {
    private final StringFilterGroup compactBanner;
    private final ByteArrayFilterGroup circleIconButton;

    public AdsFilter() {
        final StringFilterGroup alertBannerPromo = new StringFilterGroup(
                Settings.HIDE_MUSIC_PREMIUM_PROMOTIONS,
                "alert_banner_promo.e"
        );

        final StringFilterGroup paidPromotionLabel = new StringFilterGroup(
                Settings.HIDE_MUSIC_PREMIUM_PROMOTIONS,
                "music_paid_content_overlay.e"
        );

        addIdentifierCallbacks(alertBannerPromo, paidPromotionLabel);

        compactBanner = new StringFilterGroup(
                Settings.HIDE_MUSIC_PREMIUM_PROMOTIONS,
                "music_compact_banner.e"
        );

        circleIconButton = new ByteArrayFilterGroup(
                Settings.HIDE_MUSIC_PREMIUM_PROMOTIONS,
                "music_circle_icon_button.e"
        );

        final StringFilterGroup statementBanner = new StringFilterGroup(
                Settings.HIDE_MUSIC_PREMIUM_PROMOTIONS,
                "statement_banner"
        );

        addPathCallbacks(compactBanner, statementBanner);
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        if (matchedGroup == compactBanner) {
            return contentIndex == 0 && circleIconButton.check(buffer).isFiltered();
        }

        return true;
    }
}
