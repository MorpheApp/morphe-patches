package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.view.View;
import app.morphe.extension.reddit.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.LinkedHashMap;
import java.time.Instant;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Typed host bridges are generated against the verified Reddit APK at patch time. */
public final class ProfileArchivePatch {
    private ProfileArchivePatch() {}
    private static final Map<Object, ArchiveProfileContext> PROFILES = new WeakHashMap<>();
    private static final Map<Object, ArchiveNativeItem> SECTIONS = new WeakHashMap<>();
    private static final LinkedHashMap<String, ArchiveNativeItem> POSTS = new LinkedHashMap<>(128, .75f, true);
    public static boolean isPatchIncluded() { return false; }
    private static String profileName(Object model) { throw new UnsupportedOperationException(); }
    private static Enum<?> feedType(Object model) { throw new UnsupportedOperationException(); }
    private static boolean isFiltered(Object model) { throw new UnsupportedOperationException(); }
    private static boolean isFlairFiltered(Object model) { throw new UnsupportedOperationException(); }
    private static Object sectionModel(Object section) { throw new UnsupportedOperationException(); }
    private static List<?> feedSections(Object feed) { throw new UnsupportedOperationException(); }
    private static Object normalizeFeed(Object state, Object host) { throw new UnsupportedOperationException(); }
    private static boolean feedVisible(Object feed) { throw new UnsupportedOperationException(); }
    static boolean feedMore(Object feed) { throw new UnsupportedOperationException(); }
    private static boolean feedRefreshing(Object feed) { throw new UnsupportedOperationException(); }
    private static String feedSort(Object feed) { throw new UnsupportedOperationException(); }
    private static String elementId(Object element) { throw new UnsupportedOperationException(); }
    private static ArchiveNativeItem commentItem(Object element) { throw new UnsupportedOperationException(); }
    static String sectionKey(Object section) { throw new UnsupportedOperationException(); }
    private static Object newSort() { throw new UnsupportedOperationException(); }
    private static Object hostSort(Object host) { throw new UnsupportedOperationException(); }
    private static void refreshHost(Object host) { throw new UnsupportedOperationException(); }
    public static void captureTime(Object context, Object time) { throw new UnsupportedOperationException(); }
    public static void captureStatus(Object element) { throw new UnsupportedOperationException(); }
    public static void capturePost(Object element) { throw new UnsupportedOperationException(); }
    public static void bindHost(Object profile) { throw new UnsupportedOperationException(); }
    public static void captureRequest(Object source, Object request) { throw new UnsupportedOperationException(); }
    private static void bindSortHost(Object host) { throw new UnsupportedOperationException(); }
    private static Object copyVisible(Object original, int index, String key) { throw new UnsupportedOperationException(); }
    private static Object copyFeed(Object feed, List<?> sections) { throw new UnsupportedOperationException(); }
    static List<?> immutableList(List<?> rows) { throw new UnsupportedOperationException(); }
    static Unit unit() { throw new UnsupportedOperationException(); }
    static int[] themeColors(Object composer) { throw new UnsupportedOperationException(); }
    static Object newState(Object initial) { throw new UnsupportedOperationException(); }
    static Object readState(Object state) { throw new UnsupportedOperationException(); }
    static void writeState(Object state, Object value) { throw new UnsupportedOperationException(); }
    static void begin(Object composer) { throw new UnsupportedOperationException(); }
    static Object remembered(Object composer) { throw new UnsupportedOperationException(); }
    static void remember(Object composer, Object value) { throw new UnsupportedOperationException(); }
    static void end(Object composer) { throw new UnsupportedOperationException(); }
    private static String visibleKey(Object item) { throw new UnsupportedOperationException(); }
    static void androidView(Function1<Context, View> factory, Function1<View, Unit> update,
                            Object composer) { throw new UnsupportedOperationException(); }

    public static synchronized void bind(Object host, String username, boolean comments, String owner) {
        String normalized = ArchiveEligibility.username(username, comments ? "VISITOR_COMMENT" : "VISITOR_POST", true, false, true);
        if (normalized == null || normalized.equalsIgnoreCase(owner)) return;
        ArchiveProfileContext previous = PROFILES.get(host);
        if (previous == null || !previous.username.equalsIgnoreCase(normalized) || previous.comments != comments)
            PROFILES.put(host, new ArchiveProfileContext(host, normalized, comments));
        if (!comments) bindSortHost(host);
    }
    public static synchronized void bindSort(Object host, Object provider, String name) {
        ArchiveProfileContext profile = PROFILES.get(host);
        if (profile == null || profile.sortProvider != null) return;
        profile.sortProvider = new java.lang.ref.WeakReference<>(provider); profile.sortName = name;
        profile.sortOverride = newSort();
    }
    public static synchronized Object sort(Object provider, Object original) {
        if (Settings.PROFILE_ARCHIVE.get()) for (ArchiveProfileContext profile : PROFILES.values())
            if (profile.sortProvider != null && profile.sortProvider.get() == provider && profile.sortOverride != null)
                return profile.sortOverride;
        return original;
    }
    public static synchronized void savedSort(String name, Object sort) {
        for (ArchiveProfileContext profile : PROFILES.values()) if (name != null && name.equals(profile.sortName)) {
            profile.sortOverride = sort; profile.generation++;
            ArchiveController.invalidate(profile);
        }
    }
    static synchronized void selectNew(Object host) {
        ArchiveProfileContext profile = PROFILES.get(host);
        if (profile == null) return;
        profile.sortOverride = newSort(); profile.generation++; ArchiveController.invalidate(profile); refreshHost(host);
    }
    public static String stableKey(Object section, boolean includeIndex) {
        ArchiveController owner = section instanceof ArchiveSection ? ((ArchiveSection) section).controller
                : section instanceof ArchiveNativeSection ? ((ArchiveNativeSection) section).controller : null;
        if (owner != null) owner.includeNativeIndex = includeIndex;
        return section instanceof ArchiveSection ? ((ArchiveSection) section).key()
                : section instanceof ArchiveNativeSection ? ((ArchiveNativeSection) section).key() : null;
    }
    public static ArrayList<?> mapVisible(ArrayList<?> items) {
        onVisibleItems(items);
        ArrayList<Object> result = new ArrayList<>();
        java.util.Set<String> mapped = new java.util.HashSet<>();
        for (Object item : items) {
            String key = visibleKey(item);
            int index = key == null ? -1 : ArchiveController.originalIndex(key);
            if (index >= 0) {
                String nativeKey = ArchiveController.originalKey(key);
                if (mapped.add(index + "|" + nativeKey)) result.add(copyVisible(item, index, nativeKey));
            }
            else if (key == null || !key.startsWith("morphe_archive|")) result.add(item);
        }
        return result;
    }
    public static synchronized void request(String username, boolean comments, String flair, boolean refresh, Object request) {
        for (ArchiveProfileContext profile : PROFILES.values()) {
            if (profile.comments != comments || !profile.username.equalsIgnoreCase(username)) continue;
            boolean filtered = flair != null && !flair.isEmpty();
            if (profile.flairFiltered != filtered || refresh && profile.lastRequest != request) {
                profile.generation++; ArchiveController.invalidate(profile);
            }
            profile.flairFiltered = filtered; profile.lastRequest = request;
        }
    }
    public static synchronized void postTime(String id, Instant time) {
        if (!Settings.PROFILE_ARCHIVE.get() || id == null || time == null) return;
        id = fullname(id, false);
        if (id == null) return;
        ArchiveNativeItem old = POSTS.get(id);
        POSTS.put(id, new ArchiveNativeItem(id, time.getEpochSecond(), old != null && old.pinned,
                old == null ? "" : old.status, old != null && old.placeholder));
        trimPosts();
    }
    public static synchronized void postStatus(String id, List<?> indicators) {
        if (!Settings.PROFILE_ARCHIVE.get() || (id = fullname(id, false)) == null) return;
        boolean pinned = false; String status = "";
        if (indicators != null) for (Object indicator : indicators) if (indicator instanceof Enum<?>) {
            String name = ((Enum<?>) indicator).name();
            if (name.equals("PINNED") || name.equals("STICKIED")) pinned = true;
            if (name.equals("REMOVED") || name.equals("SPAM")) status = "Removed";
            if (name.equals("DELETED")) status = "Deleted";
        }
        ArchiveNativeItem old = POSTS.get(id);
        if (status.isEmpty() && old != null) status = old.status;
        POSTS.put(id, new ArchiveNativeItem(id, old == null ? 0 : old.createdUtc,
                pinned || old != null && old.pinned, status, old != null && old.placeholder)); trimPosts();
    }
    public static synchronized void postRecord(String id, Instant time, String body, Enum<?> category, boolean pinned) {
        if (!Settings.PROFILE_ARCHIVE.get() || (id = fullname(id, false)) == null || time == null) return;
        String status = ArchiveNativeItem.status(body);
        if (category != null && !category.name().equals("UNKNOWN__"))
            status = category.name().equals("AUTHOR") || category.name().equals("DELETED") ? "Deleted" : "Removed";
        boolean readable = body != null && !body.isBlank() && ArchiveNativeItem.status(body).isEmpty();
        RecoveryPatch.evidence(id, body, !status.isEmpty());
        POSTS.put(id, new ArchiveNativeItem(id, time.getEpochSecond(), pinned, status, !readable && !status.isEmpty()));
        trimPosts();
    }
    private static void trimPosts() { while (POSTS.size() > 1024) POSTS.remove(POSTS.keySet().iterator().next()); }
    private static String fullname(String id, boolean comments) {
        if (id == null) return null;
        String prefix = comments ? "t1_" : "t3_";
        if (id.startsWith(prefix)) id = id.substring(3);
        return id.matches("[A-Za-z0-9]{1,16}") ? prefix + id.toLowerCase(java.util.Locale.ROOT) : null;
    }
    public static ArchiveNativeItem comment(String id, Instant time, String text) {
        String name = fullname(id, true);
        return name == null || time == null ? null : new ArchiveNativeItem(name, time.getEpochSecond(), false, ArchiveNativeItem.status(text));
    }
    public static synchronized void converted(Object section, Object element) {
        if (!Settings.PROFILE_ARCHIVE.get() || section == null) return;
        ArchiveNativeItem item = commentItem(element);
        if (item == null) {
            String id = fullname(elementId(element), false);
            if (id != null) item = POSTS.getOrDefault(id, new ArchiveNativeItem(id, 0, false, ""));
        }
        if (item != null) SECTIONS.put(section, item);
    }
    static synchronized ArchiveNativeItem nativeItem(Object section) {
        return SECTIONS.get(section instanceof RecoverySection ? ((RecoverySection) section).original : section);
    }
    static boolean emptySection(Object section) { return sectionModel(section) != null; }

    /** Called inside the host's feed-state composition, before it publishes sections. */
    public static Object decorate(Object feed, Object composer) {
        return decorateHost(feed, composer, null);
    }
    public static Object decorateHost(Object feed, Object composer, Object host) {
        Object originalState = feed;
        begin(composer);
        try {
            // Consume the same remember slot in every branch. Clearing it explicitly
            // disposes an archive session when this feed becomes ordinary/ineligible.
            Object previous = remembered(composer);
            ArchiveProfileContext profile;
            synchronized (ProfileArchivePatch.class) { profile = PROFILES.get(host); }
            if (profile != null && Settings.PROFILE_ARCHIVE.get() && !profile.flairFiltered)
                feed = normalizeFeed(feed, host);
            List<?> original = feed == null ? null : feedSections(feed);
            if (original == null) { remember(composer, null); return originalState; }
            if (profile != null && Settings.PROFILE_ARCHIVE.get() && !profile.flairFiltered) {
                boolean eligible = true;
                for (Object section : original) {
                    Object model = sectionModel(section);
                    if (model != null && (isFlairFiltered(model) || isFiltered(model))) eligible = false;
                }
                if (eligible) {
                    String identity = ArchiveSnapshot.prefix(profile.username, profile.comments) + profile.generation;
                    ArchiveController controller = previous instanceof ArchiveController
                            && ((ArchiveController) previous).sessionIdentity.equals(identity)
                            ? (ArchiveController) previous : new ArchiveController(profile, identity);
                    if (controller != previous) remember(composer, controller);
                    String sort = feedSort(feed);
                    controller.nativeVisibility(feedVisible(feed));
                    controller.visibility(feedVisible(feed) && !feedRefreshing(feed) && (profile.comments || "NEW".equals(sort)));
                    return copyFeed(feed, controller.mixedSections(original, feedMore(feed), profile.comments || "NEW".equals(sort)));
                }
            }
            for (int i = 0; i < original.size(); i++) {
                Object model = sectionModel(original.get(i));
                if (model == null) continue;
                String tab = feedType(model).name();
                String username = ArchiveEligibility.username(profileName(model), tab, isFiltered(model),
                        isFlairFiltered(model), Settings.PROFILE_ARCHIVE.get());
                if (username == null) continue;
                boolean comments = tab.equals("VISITOR_COMMENT");
                String key = ArchiveSnapshot.prefix(username, comments);
                String identity = profile == null ? key : key + profile.generation;
                ArchiveController controller;
                if (previous instanceof ArchiveController && ((ArchiveController) previous).sessionIdentity.equals(identity)) {
                    controller = (ArchiveController) previous;
                } else {
                    controller = profile == null ? new ArchiveController(username, comments) : new ArchiveController(profile, identity);
                    remember(composer, controller);
                }
                if (profile != null && !comments && !"NEW".equals(feedSort(feed))) {
                    controller.nativeVisibility(feedVisible(feed));
                    controller.visibility(false);
                    return copyFeed(feed, controller.mixedSections(original, feedMore(feed), false));
                }
                controller.visibility(feedVisible(feed) && !feedRefreshing(feed));
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
