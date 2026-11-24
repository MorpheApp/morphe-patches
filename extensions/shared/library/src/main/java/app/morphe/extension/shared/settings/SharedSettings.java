package app.morphe.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.parentNot;

import app.morphe.extension.shared.patches.PatchesUpdateNoticePatch.UpdateNoticeType;

public class SharedSettings extends BaseSettings {
    public static final IntegerSetting CHECK_ENVIRONMENT_WARNINGS_ISSUED = new IntegerSetting("morphe_check_environment_warnings_issued", 0, true, false);

    /** Use the icons declared in the preferences created during patching. If no icons or styles are declared then this setting does nothing. */
    public static final BooleanSetting SHOW_MENU_ICONS = new BooleanSetting("morphe_show_menu_icons", TRUE, true);

    /** Name shown for the Morphe settings: a preset entry value, or a name the user typed. */
    public static final StringSetting SETTINGS_NAME = new StringSetting("morphe_settings_name", "DEFAULT", true);

    public static final StringSetting EXPERIMENTAL_APP_CONFIRMED = new StringSetting("morphe_experimental_app_target_confirmed", "", false, false);

    public static final EnumSetting<UpdateNoticeType> PATCHES_UPDATE_NOTICE = new EnumSetting<>("morphe_patches_update_notice", UpdateNoticeType.OFF);
    public static final StringSetting PATCHES_UPDATE_NOTICE_DISMISSED_VERSION = new StringSetting("morphe_patches_update_notice_dismissed_version", "", false, false);

    /** If the GmsCore battery optimization dialogs should be shown. */
    public static final BooleanSetting GMS_CORE_BATTERY_OPTIMIZATION_DIALOG = new BooleanSetting("morphe_gms_core_battery_optimization_dialog", TRUE, true, "morphe_gms_core_battery_optimization_dialog_user_dialog_message");

    /** Used by YouTube, YouTube Music and Reddit. */
    public static final BooleanSetting OPEN_SYSTEM_SHARE_SHEET = new BooleanSetting("morphe_open_system_share_sheet", FALSE);

    // Feature flags manager. Used by YouTube, YouTube Music and Reddit.
    public static final StringSetting DISABLED_FEATURE_FLAGS = new StringSetting("morphe_disabled_feature_flags", "", true, parentNot(DEBUG_LEVEL, DebugLevel.DISABLED));
    public static final StringSetting FORCED_FEATURE_FLAGS = new StringSetting("morphe_forced_feature_flags", "", true, parentNot(DEBUG_LEVEL, DebugLevel.DISABLED));
    public static final StringSetting FEATURE_FLAGS_BISECT = new StringSetting("morphe_feature_flags_bisect", "", false, false, null, parentNot(DEBUG_LEVEL, DebugLevel.DISABLED));

}
