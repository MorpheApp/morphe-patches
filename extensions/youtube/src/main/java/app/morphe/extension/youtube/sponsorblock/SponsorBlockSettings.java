package app.morphe.extension.youtube.sponsorblock;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.util.Pair;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.sponsorblock.SponsorBlockHelpers;
import app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("NewApi")
public class SponsorBlockSettings {

    public static final Setting.ImportExportCallback SB_IMPORT_EXPORT_CALLBACK = new Setting.ImportExportCallback() {
        @Override
        public void settingsImported(@Nullable Activity context) {
            SegmentCategory.loadAllCategoriesFromSettings();
        }
        @Override
        public void settingsExported(@Nullable Activity context) {
            showExportWarningIfNeeded(context);
        }
    };

    public static void importDesktopSettings(@NonNull String json) {
        Utils.verifyOnMainThread();
        try {
            JSONObject settingsJson = new JSONObject(json);
            JSONObject barTypesObject = settingsJson.getJSONObject("barTypes");
            JSONArray categorySelectionsArray = settingsJson.getJSONArray("categorySelections");

            for (SegmentCategory category : SegmentCategory.categoriesWithoutUnsubmitted()) {
                // Clear existing behavior, as browser plugin exports no behavior for ignored categories.
                category.setBehaviour(CategoryBehaviour.IGNORE);
                if (barTypesObject.has(category.keyValue)) {
                    JSONObject categoryObject = barTypesObject.getJSONObject(category.keyValue);
                    // Older Morphe SB exports lack an opacity value.
                    if (categoryObject.has("color") && categoryObject.has("opacity")) {
                        category.setColorWithOpacity(categoryObject.getString("color"));
                        category.setOpacity((float) categoryObject.getDouble("opacity"));
                    }
                }
            }

            for (int i = 0, length = categorySelectionsArray.length(); i < length; i++) {
                JSONObject categorySelectionObject = categorySelectionsArray.getJSONObject(i);

                String categoryKey = categorySelectionObject.getString("name");
                SegmentCategory category = SegmentCategory.byCategoryKey(categoryKey);
                if (category == null) {
                    continue; // Unsupported category, ignore.
                }

                final int desktopValue = categorySelectionObject.getInt("option");
                CategoryBehaviour behaviour = CategoryBehaviour.byDesktopKeyValue(desktopValue);
                if (behaviour == null) {
                    Utils.showToastLong(categoryKey + " unknown behavior key: " + categoryKey);
                } else if (category == SegmentCategory.HIGHLIGHT && behaviour == CategoryBehaviour.SKIP_AUTOMATICALLY_ONCE) {
                    Utils.showToastLong("Skip-once behavior not allowed for " + category.keyValue);
                    category.setBehaviour(CategoryBehaviour.SKIP_AUTOMATICALLY); // Use the closest match.
                } else {
                    category.setBehaviour(behaviour);
                }
            }
            SegmentCategory.updateEnabledCategories();

            if (settingsJson.has("userID")) {
                // User id does not exist if user never voted or created any segments.
                String userID = settingsJson.getString("userID");
                if (SponsorBlockHelpers.isValidSBUserID(userID)) {
                    Settings.SB_PRIVATE_USER_ID.save(userID);
                }
            }
            Settings.SB_USER_IS_VIP.save(settingsJson.getBoolean("isVip"));
            Settings.SB_TOAST_ON_SKIP.save(!settingsJson.getBoolean("dontShowNotice"));
            Settings.SB_TRACK_SKIP_COUNT.save(settingsJson.getBoolean("trackViewCount"));
            Settings.SB_VIDEO_LENGTH_WITHOUT_SEGMENTS.save(settingsJson.getBoolean("showTimeWithSkips"));

            String serverAddress = settingsJson.getString("serverAddress");
            if (SponsorBlockHelpers.isValidSBServerAddress(serverAddress)) {
                Settings.SB_API_URL.save(serverAddress);
            }

            final float minDuration = (float) settingsJson.getDouble("minDuration");
            if (minDuration < 0) {
                throw new IllegalArgumentException("invalid minDuration: " + minDuration);
            }
            Settings.SB_SEGMENT_MIN_DURATION.save(minDuration);

            if (settingsJson.has("skipCount")) {
                int skipCount = settingsJson.getInt("skipCount");
                if (skipCount < 0) {
                    throw new IllegalArgumentException("invalid skipCount: " + skipCount);
                }
                Settings.SB_LOCAL_TIME_SAVED_NUMBER_SEGMENTS.save(skipCount);
            }

            if (settingsJson.has("minutesSaved")) {
                final double minutesSaved = settingsJson.getDouble("minutesSaved");
                if (minutesSaved < 0) {
                    throw new IllegalArgumentException("invalid minutesSaved: " + minutesSaved);
                }
                Settings.SB_LOCAL_TIME_SAVED_MILLISECONDS.save((long) (minutesSaved * 60 * 1000));
            }

            Utils.showToastLong(str("morphe_sb_settings_import_successful"));
        } catch (Exception ex) {
            Logger.printInfo(() -> "failed to import settings", ex); // Use info level, as we are showing our own toast.
            Utils.showToastLong(str("morphe_sb_settings_import_failed", ex.getMessage()));
        }
    }

    @NonNull
    public static String exportDesktopSettings() {
        Utils.verifyOnMainThread();
        try {
            Logger.printDebug(() -> "Creating SponsorBlock export settings string");
            JSONObject json = new JSONObject();

            JSONObject barTypesObject = new JSONObject(); // Categories' colors.
            JSONArray categorySelectionsArray = new JSONArray(); // Categories' behavior.

            SegmentCategory[] categories = SegmentCategory.categoriesWithoutUnsubmitted();
            for (SegmentCategory category : categories) {
                JSONObject categoryObject = new JSONObject();
                String categoryKey = category.keyValue;
                // SB settings use separate color and opacity.
                categoryObject.put("color", category.getColorStringWithoutOpacity());
                categoryObject.put("opacity", category.getOpacity());
                barTypesObject.put(categoryKey, categoryObject);

                if (category.behaviour != CategoryBehaviour.IGNORE) {
                    JSONObject behaviorObject = new JSONObject();
                    behaviorObject.put("name", categoryKey);
                    behaviorObject.put("option", category.behaviour.desktopKeyValue);
                    categorySelectionsArray.put(behaviorObject);
                }
            }
            if (SponsorBlockHelpers.userHasSBPrivateID()) {
                json.put("userID", Settings.SB_PRIVATE_USER_ID.get());
            }
            json.put("isVip", Settings.SB_USER_IS_VIP.get());
            json.put("serverAddress", Settings.SB_API_URL.get());
            json.put("dontShowNotice", !Settings.SB_TOAST_ON_SKIP.get());
            json.put("showTimeWithSkips", Settings.SB_VIDEO_LENGTH_WITHOUT_SEGMENTS.get());
            json.put("minDuration", Settings.SB_SEGMENT_MIN_DURATION.get());
            json.put("trackViewCount", Settings.SB_TRACK_SKIP_COUNT.get());
            json.put("skipCount", Settings.SB_LOCAL_TIME_SAVED_NUMBER_SEGMENTS.get());
            json.put("minutesSaved", Settings.SB_LOCAL_TIME_SAVED_MILLISECONDS.get() / (60f * 1000));

            json.put("categorySelections", categorySelectionsArray);
            json.put("barTypes", barTypesObject);

            return json.toString(2);
        } catch (Exception ex) {
            Logger.printInfo(() -> "failed to export settings", ex); // Use info level, as we are showing our own toast.
            Utils.showToastLong(str("morphe_sb_settings_export_failed", ex));
            return "";
        }
    }

    /**
     * Export the categories using flatten JSON (no embedded dictionaries or arrays).
     */
    private static void showExportWarningIfNeeded(@Nullable Activity activity) {
        Utils.verifyOnMainThread();
        initialize();

        // If user has a SponsorBlock user ID then show a warning.
        if (activity != null && SponsorBlockHelpers.userHasSBPrivateID()
                && !Settings.SB_HIDE_EXPORT_WARNING.get()) {
            // Create the custom dialog.
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    null, // No title.
                    str("morphe_sb_settings_morphe_export_user_id_warning"), // Message.
                    null, // No EditText.
                    null, // OK button text.
                    () -> {}, // OK button action (dismiss only).
                    null, // No cancel button action.
                    str("morphe_sb_settings_morphe_export_user_id_warning_dismiss"), // Neutral button text.
                    () -> Settings.SB_HIDE_EXPORT_WARNING.save(true), // Neutral button action.
                    true // Dismiss dialog when onNeutralClick.
            );

            Utils.showDialog(activity, dialogPair.first, false, null);
        }
    }

    private static boolean initialized;

    public static void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;

        SegmentCategory.updateEnabledCategories();
    }
}
