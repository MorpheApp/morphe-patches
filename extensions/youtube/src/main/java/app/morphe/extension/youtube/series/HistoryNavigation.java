/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Native pivot construction is supplied by the patch, so endpoints and selection remain
 * YouTube-owned.
 */
@SuppressWarnings("unused")
public final class HistoryNavigation {
    private static final Map<Object, String> kinds =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Object series;
    private static byte[] home;
    private static String seriesTitle = "";

    public static Object buildNative(byte[] renderer) {
        throw new IllegalStateException("Native navigation bridge missing");
    }

    /**
     * Injection point.
     */
    public static synchronized void capture(Object nativeItem, MessageLite proto) {
        if (!SeriesTrackerPatch.NAVIGATION_BUTTON) return;
        try {
            byte[] data = proto.toByteArray();
            String kind = NavigationProto.kind(data);
            kinds.put(nativeItem, kind);
            if (kind.equals("home")) {
                String title = UiText.get("morphe_series_tracker_ui_series");
                // A language/theme change can recreate the Activity without restarting the process.
                if (series == null || !title.equals(seriesTitle) || !Arrays.equals(home, data)) {
                    Object replacement = buildNative(NavigationProto.series(data, title));
                    home = data;
                    seriesTitle = title;
                    series = replacement;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "capture failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static synchronized List<Object> navigation(List<Object> items) {
        if (!SeriesTrackerPatch.NAVIGATION_BUTTON || series == null) return items;
        try {
            int index = items.size();
            for (int i = 0, size = items.size(); i < size; i++) {
                if ("profile".equals(kinds.get(items.get(i)))) {
                    index = i;
                    break;
                }
            }
            List<Object> result = new ArrayList<>(items);
            result.add(index, series);
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "navigation failure", ex);
            return items;
        }
    }

    private HistoryNavigation() {}
}
