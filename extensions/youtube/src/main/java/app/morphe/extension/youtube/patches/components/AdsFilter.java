/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.ByteTrieSearch.convertStringsToBytes;

import android.view.View;

import java.util.List;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;

@SuppressWarnings("unused")
public final class AdsFilter extends Filter {

    private static final String[] PLAYER_POPUP_AD_PANEL_IDS = {
            "PAproduct", // Shopping.
            "jumpahead" // Premium promotion.
    };

    // https://encrypted-tbn0.gstatic.com/shopping?q=abc
    private static final String STORE_BANNER_DOMAIN = "gstatic.com/shopping";
    private static final boolean HIDE_END_SCREEN_STORE_BANNER =
            Settings.HIDE_END_SCREEN_STORE_BANNER.get();

    private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
    private static final ByteTrieSearch statementBannerSearch = new ByteTrieSearch(
            convertStringsToBytes("statement_banner"));
    private static final ByteTrieSearch yoodleSearch = new ByteTrieSearch(
            convertStringsToBytes("EgliaWd5b29kbGU")); // Base64 chunk that decodes to 'bigyoodle'

    private final StringTrieSearch exceptions = new StringTrieSearch();

    private final StringFilterGroup buyMovieAd;
    private final ByteArrayFilterGroup buyMovieAdBuffer;

    public AdsFilter() {
        exceptions.addPatterns(
                "home_video_with_context", // Don't filter anything in the home page video component.
                "related_video_with_context", // Don't filter anything in the related video component.
                "comment_thread", // Don't filter anything in the comments.
                "|comment.", // Don't filter anything in the comments replies.
                "library_recent_shelf"
        );

        // Identifiers.

        final var carouselAd = new StringFilterGroup(
                Settings.HIDE_GENERAL_ADS,
                "carousel_ad"
        );
        addIdentifierCallbacks(carouselAd);

        // Paths.

        final var generalAds = new StringFilterGroup(
                Settings.HIDE_GENERAL_ADS,
                "_ad_with",
                "_buttoned_layout",
                "ads_video_with_context",
                "banner_text_icon",
                "brand_video_shelf",
                "brand_video_singleton",
                "carousel_footered_layout",
                "carousel_headered_layout",
                "compact_landscape_image_layout", // Tablet layout search results.
                "composite_concurrent_carousel_layout",
                "full_width_portrait_image_layout",
                "full_width_square_image_carousel_layout",
                "full_width_square_image_layout",
                "hero_promo_image",
                // text_image_button_group_layout, landscape_image_button_group_layout, full_width_square_image_button_group_layout
                "image_button_group_layout",
                "landscape_image_carousel_layout",
                "landscape_image_wide_button_layout",
                "primetime_promo",
                "product_details",
                "square_image_layout",
                "text_image_button_layout",
                "text_image_no_button_layout", // Tablet layout search results.
                "video_display_button_group_layout",
                "video_display_carousel_button_group_layout",
                "video_display_carousel_buttoned_short_dr_layout",
                "video_display_full_buttoned_short_dr_layout",
                "video_display_full_layout",
                "watch_metadata_app_promo",
                "shopping_timely_shelf." // Injection point below hides the empty space.
        );

        final var movieAds = new StringFilterGroup(
                Settings.HIDE_MOVIES_SECTION,
                "browsy_bar",
                "compact_movie",
                "compact_tvfilm_item",
                "horizontal_movie_shelf",
                "movie_and_show_upsell_card",
                "offer_module_root"
        );

        buyMovieAd = new StringFilterGroup(
                Settings.HIDE_MOVIES_SECTION,
                "video_lockup_with_attachment.e"
        );

        buyMovieAdBuffer =  new ByteArrayFilterGroup(
                null,
                "FEstorefront"
        );

        final var viewProducts = new StringFilterGroup(
                Settings.HIDE_VIEW_PRODUCTS_BANNER,
                "product_item",
                "products_in_video",
                "shopping_overlay.e" // Video player overlay shopping links.
        );

        final var shoppingLinks = new StringFilterGroup(
                Settings.HIDE_SHOPPING_LINKS,
                "shopping_description_shelf.e"
        );

        final var merchandise = new StringFilterGroup(
                Settings.HIDE_MERCHANDISE_BANNERS,
                "product_carousel",
                "shopping_carousel.e" // Channel profile shopping shelf.
        );

        final var selfSponsor = new StringFilterGroup(
                Settings.HIDE_SELF_SPONSOR,
                "cta_shelf_card"
        );

        addPathCallbacks(
                buyMovieAd,
                generalAds,
                merchandise,
                movieAds,
                selfSponsor,
                shoppingLinks,
                viewProducts
        );
    }

    @Override
    boolean isFiltered(ContextInterface contextInterface,
                       String identifier,
                       String accessibility,
                       String path,
                       byte[] buffer,
                       StringFilterGroup matchedGroup,
                       FilterContentType contentType,
                       int contentIndex) {
        if (matchedGroup == buyMovieAd) {
            return contentIndex == 0 && buyMovieAdBuffer.check(buffer).isFiltered();
        }

        return !exceptions.matches(path);
    }

    /**
     * Injection point.
     */
    public static byte[] hideStatementBanner(byte[] bytes) {
        try {
            if (statementBannerSearch.matches(bytes)) {
                final boolean isDoodle = yoodleSearch.matches(bytes);

                if (isDoodle) {
                    if (Settings.HIDE_YOUTUBE_DOODLES.get()) {
                        Logger.printDebug(() -> "Hiding YouTube Doodles");
                        return EMPTY_BYTE_ARRAY;
                    }
                } else {
                    if (Settings.HIDE_YOUTUBE_PREMIUM_PROMOTIONS.get()) {
                        Logger.printDebug(() -> "Hiding YouTube Premium promotions");
                        return EMPTY_BYTE_ARRAY;
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideStatementBanner failure", ex);
        }

        return bytes;
    }

    /**
     * Injection point.
     */
    public static boolean hideAds() {
        return Settings.HIDE_GENERAL_ADS.get();
    }

    /**
     * Injection point.
     */
    public static String hideAds(String osName) {
        return Settings.HIDE_GENERAL_ADS.get()
                ? "Android Automotive"
                : osName;
    }

    /**
     * Hide the view, which shows ads in the homepage.
     *
     * @param view The view, which shows ads.
     */
    public static void hideAdAttributionView(View view) {
        Utils.hideViewBy0dpUnderCondition(Settings.HIDE_GENERAL_ADS, view);
    }

    /**
     * Injection point.
     *
     * @param elementsList List of components of the end screen container.
     * @param protobufList Component (ProtobufList).
     */
    public static void hideEndScreenStoreBanner(List<Object> elementsList, Object protobufList) {
        if (HIDE_END_SCREEN_STORE_BANNER && protobufList.toString().contains(STORE_BANNER_DOMAIN)) {
            Logger.printDebug(() -> "Hiding store banner");
            return;
        }

        elementsList.add(protobufList);
    }

    /**
     * Injection point.
     */
    public static boolean hideGetPremiumView() {
        return Settings.HIDE_YOUTUBE_PREMIUM_PROMOTIONS.get();
    }

    /**
     * Injection point.
     */
    public static boolean hidePlayerPopupAds(String panelId) {
        return Settings.HIDE_PLAYER_POPUP_ADS.get()
                && Utils.containsAny(panelId, PLAYER_POPUP_AD_PANEL_IDS);
    }
}
