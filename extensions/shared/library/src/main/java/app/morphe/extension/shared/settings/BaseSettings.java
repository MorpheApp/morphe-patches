package app.morphe.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.parent;

import app.morphe.extension.shared.Logger;

/**
 * Settings shared across multiple apps.
 * <p>
 * To ensure this class is loaded when the UI is created, app specific setting bundles should extend
 * or reference this class.
 */
public class BaseSettings {
    public static final BooleanSetting DEBUG = new BooleanSetting("morphe_debug", FALSE);
    public static final BooleanSetting DEBUG_STACKTRACE = new BooleanSetting("morphe_debug_stacktrace", FALSE, parent(DEBUG));
    public static final BooleanSetting DEBUG_TOAST_ON_ERROR = new BooleanSetting("morphe_debug_toast_on_error", TRUE, "morphe_debug_toast_on_error_user_dialog_message");

    public static final IntegerSetting CHECK_ENVIRONMENT_WARNINGS_ISSUED = new IntegerSetting("morphe_check_environment_warnings_issued", 0, true, false);

    public static final EnumSetting<AppLanguage> MORPHE_LANGUAGE = new EnumSetting<>("morphe_language", AppLanguage.DEFAULT, true, "morphe_language_user_dialog_message");

    /**
     * Use the icons declared in the preferences created during patching. If no icons or styles are declared then this setting does nothing.
     */
    public static final BooleanSetting SHOW_MENU_ICONS = new BooleanSetting("morphe_show_menu_icons", TRUE, true);

    /**
     * The first time the app was launched with no previous app data (either a clean install, or after wiping app data).
     */
    public static final LongSetting FIRST_TIME_APP_LAUNCHED = new LongSetting("morphe_last_time_app_was_launched", -1L, false, false);

    public static final StringSetting EXPERIMENTAL_APP_CONFIRMED = new StringSetting("morphe_experimental_app_target_confirmed", "", false, false);

    static {
        final long now = System.currentTimeMillis();

        if (FIRST_TIME_APP_LAUNCHED.get() < 0) {
            Logger.printInfo(() -> "First launch of installation with no prior app data");
            FIRST_TIME_APP_LAUNCHED.save(now);
        }
    }
}
