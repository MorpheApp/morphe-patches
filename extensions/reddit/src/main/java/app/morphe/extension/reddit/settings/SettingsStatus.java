package app.morphe.extension.reddit.settings;

@SuppressWarnings("unused")
public class SettingsStatus {
    public static boolean aboutShelfEnabled = false;
    public static boolean gamesOnRedditShelfEnabled = false;
    public static boolean generalAdsEnabled = false;
    public static boolean navigationButtonsEnabled = false;
    public static boolean openLinksDirectlyEnabled = false;
    public static boolean openLinksExternallyEnabled = false;
    public static boolean recentlyVisitedShelfEnabled = false;
    public static boolean recommendedCommunitiesShelfEnabled = false;
    public static boolean redditProShelfEnabled = false;
    public static boolean resourcesShelfEnabled = false;
    public static boolean sanitizeUrlQueryEnabled = false;
    public static boolean screenshotPopupEnabled = false;
    public static boolean subRedditDialogEnabled = false;
    public static boolean toolBarButtonEnabled = false;
    public static boolean trendingTodayShelfEnabled = false;

    public static void enableAboutShelf() {
        aboutShelfEnabled = true;
    }

    public static void enableGamesOnRedditShelf() {
        gamesOnRedditShelfEnabled = true;
    }

    public static void enableGeneralAds() {
        generalAdsEnabled = true;
    }

    public static void enableRedditProShelf() {
        redditProShelfEnabled = true;
    }

    public static void enableNavigationButtons() {
        navigationButtonsEnabled = true;
    }

    public static void enableOpenLinksDirectly() {
        openLinksDirectlyEnabled = true;
    }

    public static void enableOpenLinksExternally() {
        openLinksExternallyEnabled = true;
    }

    public static void enableRecentlyVisitedShelf() {
        recentlyVisitedShelfEnabled = true;
    }

    public static void enableRecommendedCommunitiesShelf() {
        recommendedCommunitiesShelfEnabled = true;
    }

    public static void enableResourcesShelf() {
        resourcesShelfEnabled = true;
    }

    public static void enableSubRedditDialog() {
        subRedditDialogEnabled = true;
    }

    public static void enableSanitizeUrlQuery() {
        sanitizeUrlQueryEnabled = true;
    }

    public static void enableScreenshotPopup() {
        screenshotPopupEnabled = true;
    }

    public static void enableToolBarButton() {
        toolBarButtonEnabled = true;
    }

    public static void enableTrendingTodayShelf() {
        trendingTodayShelfEnabled = true;
    }

    public static boolean adsCategoryEnabled() {
        return generalAdsEnabled;
    }

    public static boolean layoutCategoryEnabled() {
        return gamesOnRedditShelfEnabled ||
                navigationButtonsEnabled ||
                recentlyVisitedShelfEnabled ||
                recommendedCommunitiesShelfEnabled ||
                redditProShelfEnabled ||
                screenshotPopupEnabled ||
                subRedditDialogEnabled ||
                toolBarButtonEnabled ||
                trendingTodayShelfEnabled;
    }

    public static boolean miscellaneousCategoryEnabled() {
        return openLinksDirectlyEnabled ||
                openLinksExternallyEnabled ||
                sanitizeUrlQueryEnabled;
    }

    public static void load() {

    }
}
