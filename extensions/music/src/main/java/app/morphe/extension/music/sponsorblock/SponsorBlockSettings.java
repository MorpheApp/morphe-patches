package app.morphe.extension.music.sponsorblock;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.settings.Setting;

/**
 * Manages SponsorBlock user settings for YouTube Music.
 *
 * <p>Settings values (SB_ENABLED, SB_TOAST_ON_SKIP, etc.) are defined in
 * {@link app.morphe.extension.music.settings.Settings} and persisted via the
 * Morphe shared settings framework.
 *
 * <p>This class is responsible for:
 * <ul>
 *   <li>Generating and persisting a private SponsorBlock user ID (used for voting/reporting)</li>
 *   <li>Triggering a reload of all segment-category settings after an import/export</li>
 *   <li>Initialising the enabled-category cache on first use per video</li>
 * </ul>
 */
@SuppressWarnings("unused")
public class SponsorBlockSettings {

    /**
     * Callback registered with the settings framework so that segment categories
     * are reloaded whenever the user imports a settings backup.
     */
    public static final Setting.ImportExportCallback SB_IMPORT_EXPORT_CALLBACK =
            new Setting.ImportExportCallback() {
                @Override
                public void settingsImported(@Nullable Context context) {
                    SegmentCategory.loadAllCategoriesFromSettings();
                }

                @Override
                public void settingsExported(@Nullable Context context) {
                    // Nothing extra needed on export.
                }
            };

    private static boolean initialized;

    /**
     * Returns {@code true} if the user has ever voted, created a segment,
     * or imported existing SponsorBlock settings (i.e. a private user ID exists).
     */
    public static boolean userHasSBPrivateId() {
        return !Settings.SB_PRIVATE_USER_ID.get().isEmpty();
    }

    /**
     * Returns the user's private SponsorBlock ID.
     * If one does not yet exist it is generated, saved, and returned.
     *
     * <p>Only call this when an ID is actually required (voting, segment creation).
     */
    @NonNull
    public static String getSBPrivateUserID() {
        String uuid = Settings.SB_PRIVATE_USER_ID.get();
        if (uuid.isEmpty()) {
            uuid = (UUID.randomUUID().toString()
                    + UUID.randomUUID().toString()
                    + UUID.randomUUID().toString())
                    .replace("-", "");
            Settings.SB_PRIVATE_USER_ID.save(uuid);
        }
        return uuid;
    }

    /**
     * Called by {@link SegmentPlaybackController#clearData()} at the start of each new video.
     * Ensures the enabled-category cache in {@link SegmentCategory} is up to date.
     */
    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        SegmentCategory.updateEnabledCategories();
    }
}
