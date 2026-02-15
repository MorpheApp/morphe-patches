package app.morphe.extension.reddit.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

public class Settings extends BaseSettings {
    // Ads
    public static final BooleanSetting HIDE_COMMENT_ADS = new BooleanSetting("morphe_hide_comment_ads", TRUE, true);
    public static final BooleanSetting HIDE_POST_ADS = new BooleanSetting("morphe_hide_post_ads", TRUE, true);

    // Layout
    public static final BooleanSetting DISABLE_SCREENSHOT_POPUP = new BooleanSetting("morphe_disable_screenshot_popup", TRUE, true);
    public static final BooleanSetting HIDE_ANSWERS_BUTTON = new BooleanSetting("morphe_hide_answers_button", FALSE, true);
    public static final BooleanSetting HIDE_CHAT_BUTTON = new BooleanSetting("morphe_hide_chat_button", FALSE, true);
    public static final BooleanSetting HIDE_CREATE_BUTTON = new BooleanSetting("morphe_hide_create_button", FALSE, true);
    public static final BooleanSetting HIDE_DISCOVER_BUTTON = new BooleanSetting("morphe_hide_discover_button", FALSE, true);
    public static final BooleanSetting HIDE_GAMES_BUTTON = new BooleanSetting("morphe_hide_games_button", FALSE, true);
    public static final BooleanSetting HIDE_ABOUT_SHELF = new BooleanSetting("morphe_hide_about_shelf", FALSE, true);
    public static final BooleanSetting HIDE_GAMES_ON_REDDIT_SHELF = new BooleanSetting("morphe_hide_games_on_reddit_shelf", FALSE, true);
    public static final BooleanSetting HIDE_RECENTLY_VISITED_SHELF = new BooleanSetting("morphe_hide_recently_visited_shelf", FALSE, true);
    public static final BooleanSetting HIDE_RESOURCES_SHELF = new BooleanSetting("morphe_hide_resources_shelf", FALSE, true);
    public static final BooleanSetting HIDE_REDDIT_PRO_SHELF = new BooleanSetting("morphe_hide_reddit_pro_shelf", FALSE, true);
    public static final BooleanSetting HIDE_RECOMMENDED_COMMUNITIES_SHELF = new BooleanSetting("morphe_hide_recommended_communities_shelf", FALSE, true);
    public static final BooleanSetting HIDE_TRENDING_TODAY_SHELF = new BooleanSetting("morphe_hide_trending_today_shelf", FALSE, true);
    public static final BooleanSetting REMOVE_NSFW_DIALOG = new BooleanSetting("morphe_remove_nsfw_dialog", FALSE, true);
    public static final BooleanSetting REMOVE_NOTIFICATION_DIALOG = new BooleanSetting("morphe_remove_notification_dialog", FALSE, true);

    // Miscellaneous
    public static final BooleanSetting OPEN_LINKS_DIRECTLY = new BooleanSetting("morphe_open_links_directly", TRUE);
    public static final BooleanSetting OPEN_LINKS_EXTERNALLY = new BooleanSetting("morphe_open_links_externally", TRUE);
    public static final BooleanSetting SANITIZE_URL_QUERY = new BooleanSetting("morphe_sanitize_url_query", TRUE);

    static {
        // region Migration

        SharedPrefCategory oldPrefs = new SharedPrefCategory("reddit_morphe");
        for (Setting<?> setting : Setting.allLoadedSettings()) {
            Setting.migrateFromOldPreferences(oldPrefs, setting);
        }

        // endregion
    }
}
