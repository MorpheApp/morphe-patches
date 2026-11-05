package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.view.View;
import app.morphe.extension.reddit.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Typed host bridges are generated against the verified Reddit APK at patch time. */
public final class ProfileArchivePatch {
    private ProfileArchivePatch() {}
    public static boolean isPatchIncluded() { return false; }
    private static String profileName(Object model) { throw new UnsupportedOperationException(); }
    private static Enum<?> feedType(Object model) { throw new UnsupportedOperationException(); }
    private static boolean isFiltered(Object model) { throw new UnsupportedOperationException(); }
    private static boolean isFlairFiltered(Object model) { throw new UnsupportedOperationException(); }
    private static Object sectionModel(Object section) { throw new UnsupportedOperationException(); }
    private static List<?> feedSections(Object feed) { throw new UnsupportedOperationException(); }
    private static boolean feedVisible(Object feed) { throw new UnsupportedOperationException(); }
    private static Object copyFeed(Object feed, List<?> sections) { throw new UnsupportedOperationException(); }
    static List<?> immutableList(List<?> rows) { throw new UnsupportedOperationException(); }
    static Unit unit() { throw new UnsupportedOperationException(); }
    static Object newState(Object initial) { throw new UnsupportedOperationException(); }
    static Object readState(Object state) { throw new UnsupportedOperationException(); }
    static void writeState(Object state, Object value) { throw new UnsupportedOperationException(); }
    private static void begin(Object composer) { throw new UnsupportedOperationException(); }
    private static Object remembered(Object composer) { throw new UnsupportedOperationException(); }
    private static void remember(Object composer, Object value) { throw new UnsupportedOperationException(); }
    private static void end(Object composer) { throw new UnsupportedOperationException(); }
    private static String visibleKey(Object item) { throw new UnsupportedOperationException(); }
    static void androidView(Function1<Context, View> factory, Function1<View, Unit> update,
                            Object composer) { throw new UnsupportedOperationException(); }

    /** Called inside the host's feed-state composition, before it publishes sections. */
    public static Object decorate(Object feed, Object composer) {
        List<?> original = feedSections(feed);
        begin(composer);
        try {
            // Consume the same remember slot in every branch. Clearing it explicitly
            // disposes an archive session when this feed becomes ordinary/ineligible.
            Object previous = remembered(composer);
            for (int i = 0; i < original.size(); i++) {
                Object model = sectionModel(original.get(i));
                if (model == null) continue;
                String tab = feedType(model).name();
                String username = ArchiveEligibility.username(profileName(model), tab, isFiltered(model),
                        isFlairFiltered(model), Settings.PROFILE_ARCHIVE.get());
                if (username == null) continue;
                boolean comments = tab.equals("VISITOR_COMMENT");
                String key = ArchiveSnapshot.prefix(username, comments);
                ArchiveController controller;
                if (previous instanceof ArchiveController && ((ArchiveController) previous).key.equals(key)) {
                    controller = (ArchiveController) previous;
                } else {
                    controller = new ArchiveController(username, comments);
                    remember(composer, controller);
                }
                controller.visibility(feedVisible(feed));
                return copyFeed(feed, controller.sections(original, i));
            }
            remember(composer, null);
            return feed;
        } finally { end(composer); }
    }

    /** Uses the host's actual visible-item events, never precomposed row attachment. */
    public static void onVisibleItems(List<?> items) {
        ArrayList<String> keys = new ArrayList<>();
        for (Object item : items) {
            String key = visibleKey(item);
            if (key != null && key.startsWith("morphe_archive|")) keys.add(key);
        }
        if (!keys.isEmpty()) ArchiveController.visibleItems(keys);
    }
}
