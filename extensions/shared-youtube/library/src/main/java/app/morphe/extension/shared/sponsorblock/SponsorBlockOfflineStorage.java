/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4140
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.sponsorblock;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.shared.sponsorblock.requests.SBRequester;

public final class SponsorBlockOfflineStorage {

    private static final String DIRECTORY_NAME = "morphe_sb_offline";
    private static final String JSON_KEY_CATEGORY = "category";
    private static final String JSON_KEY_UUID = "uuid";
    private static final String JSON_KEY_START = "start";
    private static final String JSON_KEY_END = "end";
    private static final String JSON_KEY_LOCKED = "locked";

    private static final Map<String, Long> activeFetches = new ConcurrentHashMap<>();

    private SponsorBlockOfflineStorage() {}

    public static boolean isOfflineEnabled() {
        try {
            BooleanSetting setting = SponsorBlockApi.config().settings().sbOfflineEnabled();
            return setting != null && setting.get();
        } catch (Exception ex) {
            return false;
        }
    }

    @Nullable
    private static File getStorageDirectory() {
        try {
            Context context = Utils.getContext();
            if (context == null) return null;
            File dir = new File(context.getFilesDir(), DIRECTORY_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            return dir;
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to get SponsorBlock offline storage directory", ex);
            return null;
        }
    }

    @Nullable
    private static File getSegmentFile(@Nullable String videoId) {
        if (videoId == null || videoId.isBlank()) return null;
        File dir = getStorageDirectory();
        if (dir == null) return null;
        return new File(dir, Uri.encode(videoId) + ".json");
    }

    public static boolean hasSegments(@Nullable String videoId) {
        File file = getSegmentFile(videoId);
        return file != null && file.exists() && file.length() > 0;
    }

    public static void saveSegments(@NonNull String videoId, @NonNull SponsorSegment[] segments) {
        if (videoId.isBlank()) return;
        File file = getSegmentFile(videoId);
        if (file == null) return;

        try {
            JSONArray array = new JSONArray();
            for (SponsorSegment segment : segments) {
                JSONObject obj = new JSONObject();
                obj.put(JSON_KEY_CATEGORY, segment.category.keyValue);
                obj.put(JSON_KEY_UUID, segment.UUID != null ? segment.UUID : "");
                obj.put(JSON_KEY_START, segment.start);
                obj.put(JSON_KEY_END, segment.end);
                obj.put(JSON_KEY_LOCKED, segment.isLocked);
                array.put(obj);
            }

            File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                fos.write(array.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            if (!tempFile.renameTo(file)) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tempFile.renameTo(file);
            }
            Logger.printDebug(() -> "Saved " + segments.length + " offline SponsorBlock segments for " + videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save offline SponsorBlock segments for " + videoId, ex);
        }
    }

    @Nullable
    public static SponsorSegment[] loadSegments(@NonNull String videoId) {
        File file = getSegmentFile(videoId);
        if (file == null || !file.exists() || file.length() == 0) {
            return null;
        }

        try (FileInputStream fis = new FileInputStream(file);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONArray array = new JSONArray(sb.toString());
            List<SponsorSegment> list = new ArrayList<>();
            for (int i = 0, len = array.length(); i < len; i++) {
                JSONObject obj = array.getJSONObject(i);
                String categoryKey = obj.getString(JSON_KEY_CATEGORY);
                SegmentCategory category = SegmentCategory.byCategoryKey(categoryKey);
                if (category == null) {
                    continue;
                }
                String uuid = obj.optString(JSON_KEY_UUID, "");
                long start = obj.getLong(JSON_KEY_START);
                long end = obj.getLong(JSON_KEY_END);
                boolean locked = obj.optBoolean(JSON_KEY_LOCKED, false);

                list.add(new SponsorSegment(category, uuid.isEmpty() ? null : uuid, start, end, locked));
            }
            Logger.printDebug(() -> "Loaded " + list.size() + " offline SponsorBlock segments for " + videoId);
            return list.toArray(new SponsorSegment[0]);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load offline SponsorBlock segments for " + videoId, ex);
            return null;
        }
    }

    public static boolean deleteSegments(@Nullable String videoId) {
        if (videoId != null) {
            activeFetches.remove(videoId);
        }
        File file = getSegmentFile(videoId);
        if (file != null && file.exists()) {
            boolean deleted = file.delete();
            Logger.printDebug(() -> "Deleted offline SponsorBlock segments for " + videoId + ": " + deleted);
            return deleted;
        }
        return false;
    }

    public static void fetchAndSaveSegments(@Nullable String videoId) {
        if (videoId == null || videoId.isBlank() || !isOfflineEnabled()) {
            return;
        }
        long fetchToken = System.nanoTime();
        activeFetches.put(videoId, fetchToken);
        Utils.runOnBackgroundThread(() -> {
            try {
                if (!Utils.isNetworkConnected()) {
                    activeFetches.remove(videoId, fetchToken);
                    return;
                }
                SponsorSegment[] segments = SBRequester.getSegments(videoId);
                if (activeFetches.remove(videoId, fetchToken) && segments.length > 0) {
                    saveSegments(videoId, segments);
                }
            } catch (Exception ex) {
                activeFetches.remove(videoId, fetchToken);
                Logger.printException(() -> "Failed to fetch and save offline segments for " + videoId, ex);
            }
        });
    }

    /**
     * Clears all offline SponsorBlock segment files.
     */
    public static void clearAll() {
        try {
            File dir = getStorageDirectory();
            if (dir != null && dir.exists()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to clear offline SponsorBlock storage", ex);
        }
    }
}
