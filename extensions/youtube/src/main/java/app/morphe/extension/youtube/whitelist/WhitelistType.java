/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.whitelist;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.youtube.settings.Settings;

/**
 * A feature that can be turned off for individual channels.
 */
public enum WhitelistType {
    ADS(Settings.ADS_CHANNEL_WHITELIST),
    PLAYBACK_SPEED(Settings.PLAYBACK_SPEED_CHANNEL_WHITELIST),
    SPONSOR_BLOCK(Settings.SB_CHANNEL_WHITELIST);

    public final StringSetting setting;

    WhitelistType(StringSetting setting) {
        this.setting = setting;
    }

    public String getTitle() {
        return str(setting.key + "_title");
    }

    public String getFlyoutTitle(boolean isWhitelisted) {
        return str(setting.key + (isWhitelisted ? "_flyout_remove" : "_flyout_add"));
    }

    /**
     * @return The type of the preference with this key, which is also its setting key.
     */
    @Nullable
    public static WhitelistType fromPreferenceKey(@Nullable String key) {
        for (WhitelistType type : values()) {
            if (type.setting.key.equals(key)) {
                return type;
            }
        }
        return null;
    }
}
