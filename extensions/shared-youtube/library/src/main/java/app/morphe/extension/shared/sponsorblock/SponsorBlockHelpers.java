/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.sponsorblock;

import android.util.Patterns;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.UUID;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Stateless SponsorBlock helpers (user-ID generation, validators, color migration).
 */
public final class SponsorBlockHelpers {

    /**
     * Minimum length an SB user ID must be, as set by SB API.
     */
    private static final int SB_PRIVATE_USER_ID_MINIMUM_LENGTH = 30;

    private SponsorBlockHelpers() {}

    /**
     * @return Whether the user has ever voted, created any segment, or imported existing SB settings.
     */
    public static boolean userHasSBPrivateID() {
        StringSetting setting = SponsorBlockApi.config().settings().privateUserId();
        return setting != null && !setting.get().isEmpty();
    }

    /**
     * Returns the user's SB private ID, generating and persisting one if missing.
     * Only call when a private ID is required (segment submission, voting).
     */
    @NonNull
    public static String getOrGenerateSBPrivateUserID() {
        StringSetting setting = SponsorBlockApi.config().settings().privateUserId();
        if (setting == null) {
            throw new IllegalStateException("Host app does not provide privateUserId setting");
        }
        String uuid = setting.get();
        if (uuid.isEmpty()) {
            uuid = (UUID.randomUUID().toString()
                    + UUID.randomUUID().toString()
                    + UUID.randomUUID().toString())
                    .replace("-", "");
            setting.save(uuid);
        }
        return uuid;
    }

    public static boolean isValidSBUserID(@NonNull String userID) {
        return !userID.isEmpty() && userID.length() >= SB_PRIVATE_USER_ID_MINIMUM_LENGTH;
    }

    /**
     * A non-comprehensive check if an SB API server address is valid.
     */
    public static boolean isValidSBServerAddress(@NonNull String serverAddress) {
        if (!Patterns.WEB_URL.matcher(serverAddress).matches()) {
            return false;
        }
        // Verify url is only the server address and does not contain a path such as: "https://sponsor.ajay.app/api/"
        // Could use Patterns.compile, but this is simpler.
        final int lastDotIndex = serverAddress.lastIndexOf('.');
        return lastDotIndex > 0 && !serverAddress.substring(lastDotIndex).contains("/");
    }

    /**
     * Migrate legacy 6-digit color strings to 8-digit (with alpha) form using the supplied opacity.
     */
    @NonNull
    public static String migrateOldColorString(@NonNull String colorString, float opacity) {
        if (colorString.length() >= 8) {
            return colorString;
        }

        if (colorString.startsWith("#")) {
            colorString = colorString.substring(1);
        }

        String alphaHex = String.format(Locale.US, "%02X", (int) (opacity * 255));
        String argbColorString = '#' + alphaHex + colorString.substring(0, 6);
        Logger.printDebug(() -> "Migrating old color string with default opacity: " + argbColorString);
        return argbColorString;
    }
}
