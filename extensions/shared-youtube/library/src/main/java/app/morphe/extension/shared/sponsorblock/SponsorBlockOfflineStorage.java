/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4140
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.sponsorblock;

import android.util.AtomicFile;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.SettingsAdapter;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester;

/**
 * Segments of downloaded videos, so they can be skipped while offline.
 */
public final class SponsorBlockOfflineStorage {

    private static final String DIRECTORY_NAME = "morphe_sb_offline";
    private static final String JSON_KEY_CATEGORY = "category";
    private static final String JSON_KEY_UUID = "uuid";
    private static final String JSON_KEY_START = "start";
    private static final String JSON_KEY_END = "end";
    private static final String JSON_KEY_LOCKED = "locked";

    private SponsorBlockOfflineStorage() {}

    private static SettingsAdapter settings() {
        return SponsorBlockApi.config().settings();
    }

    private static boolean isEnabled() {
        BooleanSetting setting = settings().offlineSegments();
        return setting != null && settings().sbEnabled().get() && setting.get();
    }

    private static AtomicFile segmentsFile(String videoId) {
        File directory = new File(Utils.getContext().getFilesDir(), DIRECTORY_NAME);
        return new AtomicFile(new File(directory, videoId + ".json"));
    }

    /**
     * @return If segments are saved for the video and saving segments is enabled.
     */
    public static boolean hasSavedSegments(String videoId) {
        return isEnabled() && segmentsFile(videoId).getBaseFile().exists();
    }

    /**
     * Fetches the segments of a video that is downloaded, and saves them.
     */
    public static void fetchAndSaveSegments(String videoId) {
        if (!isEnabled() || !Utils.isNetworkConnected()) {
            return;
        }

        // The categories to fetch are loaded when the player is created,
        // which has not happened yet if the video is downloaded from the feed.
        SegmentCategory.loadAllCategoriesFromSettings();

        Utils.runOnBackgroundThread(() -> {
            try {
                SponsorSegment[] segments = SBRequester.getSegments(videoId);
                if (segments.length > 0) {
                    saveSegments(videoId, segments);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "fetchAndSaveSegments failure", ex);
            }
        });
    }

    /**
     * Must be called off main thread.
     */
    public static void saveSegments(String videoId, SponsorSegment[] segments) {
        Utils.verifyOffMainThread();
        AtomicFile file = segmentsFile(videoId);
        FileOutputStream stream = null;
        try {
            JSONArray array = new JSONArray();
            for (SponsorSegment segment : segments) {
                JSONObject object = new JSONObject();
                object.put(JSON_KEY_CATEGORY, segment.category.keyValue);
                object.put(JSON_KEY_UUID, segment.UUID);
                object.put(JSON_KEY_START, segment.start);
                object.put(JSON_KEY_END, segment.end);
                object.put(JSON_KEY_LOCKED, segment.isLocked);
                array.put(object);
            }

            File directory = file.getBaseFile().getParentFile();
            if (directory != null && !directory.exists() && !directory.mkdirs()) {
                throw new IOException("Could not create directory: " + directory);
            }

            stream = file.startWrite();
            stream.write(array.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
            Logger.printDebug(() -> "Saved " + segments.length + " segments for: " + videoId);
        } catch (Exception ex) {
            if (stream != null) {
                file.failWrite(stream);
            }
            Logger.printException(() -> "saveSegments failure", ex);
        }
    }

    /**
     * Must be called off main thread.
     *
     * @return The saved segments, or an empty array if none are saved.
     */
    @NonNull
    public static SponsorSegment[] loadSegments(String videoId) {
        Utils.verifyOffMainThread();
        if (!hasSavedSegments(videoId)) {
            return new SponsorSegment[0];
        }

        AtomicFile file = segmentsFile(videoId);
        try {
            JSONArray array = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            List<SponsorSegment> segments = new ArrayList<>(array.length());
            for (int i = 0, length = array.length(); i < length; i++) {
                JSONObject object = array.getJSONObject(i);
                String categoryKey = object.getString(JSON_KEY_CATEGORY);
                SegmentCategory category = SegmentCategory.byCategoryKey(categoryKey);
                if (category == null) {
                    Logger.printException(() -> "Unknown saved category: " + categoryKey);
                    continue;
                }

                segments.add(new SponsorSegment(
                        category,
                        object.isNull(JSON_KEY_UUID) ? null : object.getString(JSON_KEY_UUID),
                        object.getLong(JSON_KEY_START),
                        object.getLong(JSON_KEY_END),
                        object.getBoolean(JSON_KEY_LOCKED)
                ));
            }

            Logger.printDebug(() -> "Loaded " + segments.size() + " saved segments for: " + videoId);
            return segments.toArray(new SponsorSegment[0]);
        } catch (Exception ex) {
            Logger.printException(() -> "loadSegments failure", ex);
            return new SponsorSegment[0];
        }
    }

    /**
     * Deletes the saved segments of a video that is removed from the downloads.
     */
    public static void deleteSegments(String videoId) {
        AtomicFile file = segmentsFile(videoId);
        if (file.getBaseFile().exists()) {
            file.delete();
            Logger.printDebug(() -> "Deleted saved segments for: " + videoId);
        }
    }
}
