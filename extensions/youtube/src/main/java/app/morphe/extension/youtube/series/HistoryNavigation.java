package app.morphe.extension.youtube.series;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

import com.google.protobuf.MessageLite;

import java.util.*;

/**
 * Native pivot construction is supplied by the patch, so endpoints and selection remain
 * YouTube-owned.
 */
public final class HistoryNavigation {
    private static final Map<Object, String> kinds =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Object history;
    private static byte[] home;
    private static String historyTitle = "";

    public static Object buildNative(byte[] renderer) {
        throw new IllegalStateException("Native navigation bridge missing");
    }

    public static synchronized void capture(MessageLite proto, Object nativeItem) {
        try {
            byte[] data = proto.toByteArray();
            String kind = NavigationProto.kind(data);
            kinds.put(nativeItem, kind);
            if (kind.equals("home") && Settings.SERIES_TRACKER_HISTORY_TAB.get()) {
                String title = UiText.get(Utils.getContext(), "series_tracker_ui_history");
                // A language/theme change can recreate the Activity without restarting the process.
                if (history == null || !title.equals(historyTitle) || !Arrays.equals(home, data)) {
                    Object replacement = buildNative(NavigationProto.history(data, title));
                    home = data;
                    historyTitle = title;
                    history = replacement;
                }
            }
        } catch (RuntimeException error) {
            Logger.printException(() -> "Cannot prepare History navigation", error);
        }
    }

    public static boolean keepButton(Enum<?> button) {
        if (!Settings.SERIES_TRACKER_HISTORY_TAB.get()) return false;
        String name = button.name();
        return name.equals("HOME")
                || name.equals("SUBSCRIPTIONS")
                || name.equals("NOTIFICATIONS")
                || name.equals("LIBRARY");
    }

    public static synchronized List<Object> navigation(List<Object> items) {
        if (!Settings.SERIES_TRACKER_HISTORY_TAB.get() || history == null) return items;
        Map<String, Object> present = new HashMap<>();
        for (Object item : items) {
            String kind = kinds.get(item);
            if (kind != null && !present.containsKey(kind)) present.put(kind, item);
        }
        Object profile = present.get("profile"), nativeHome = present.get("home");
        if (profile == null || nativeHome == null) return items;
        try {
            boolean cairo = NavigationProto.icon(home) >= 1000;
            if (!present.containsKey("subscriptions"))
                present.put(
                        "subscriptions",
                        buildNative(
                                NavigationProto.destination(
                                        home,
                                        "FEsubscriptions",
                                        UiText.get(
                                                Utils.getContext(),
                                                "series_tracker_ui_subscriptions"),
                                        cairo ? 1155 : 408)));
            if (!present.containsKey("notifications"))
                present.put(
                        "notifications",
                        buildNative(
                                NavigationProto.destination(
                                        home,
                                        "FEactivity",
                                        UiText.get(
                                                Utils.getContext(),
                                                "series_tracker_ui_notifications"),
                                        cairo ? 1156 : 355)));
        } catch (RuntimeException error) {
            Logger.printException(() -> "Cannot prepare five-tab navigation", error);
            return items;
        }
        List<Object> result = new ArrayList<>();
        for (String kind : Arrays.asList("home", "subscriptions", "notifications")) {
            Object item = present.get(kind);
            if (item == null) return items;
            result.add(item);
        }
        result.add(history);
        result.add(profile);
        return result;
    }

    private HistoryNavigation() {}
}
