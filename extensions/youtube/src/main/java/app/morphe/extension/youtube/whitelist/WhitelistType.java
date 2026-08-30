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
    ADS(Settings.ADS_CHANNEL_WHITELIST, "morphe_ads_channel_whitelist_title"),
    PLAYBACK_SPEED(Settings.PLAYBACK_SPEED_CHANNEL_WHITELIST, "morphe_playback_speed_channel_whitelist_title"),
    SPONSOR_BLOCK(Settings.SB_CHANNEL_WHITELIST, "morphe_sb_channel_whitelist_title");

    public final StringSetting setting;
    private final String titleKey;

    WhitelistType(StringSetting setting, String titleKey) {
        this.setting = setting;
        this.titleKey = titleKey;
    }

    public String getTitle() {
        return str(titleKey);
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
