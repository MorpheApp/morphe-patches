package app.morphe.extension.reddit.patches.archive;

import app.morphe.test.ArchiveProbe;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Real 2026.40.0 card models/sections. Reflection and fixtures never enter the release bundle. */
public final class ArchiveMixedFixture {
    private static final Object HOST = new Object();
    private static String identity, cardsIdentity;
    private static List<?> cards;
    public static List<?> lastSections;
    public static int page = 1;
    public static String sort = "NEW";
    public static boolean empty;
    public static Object decorate(String username, boolean comments, boolean visible, Object composer) {
        try {
            String next = username + comments;
            if (!next.equals(identity)) {
                identity = next; cardsIdentity = null;
                ((Map<Object, ArchiveProfileContext>) staticField(ProfileArchivePatch.class, "PROFILES"))
                        .put(HOST, new ArchiveProfileContext(HOST, username, comments));
            }
            String cardKey = next + page + empty;
            if (!cardKey.equals(cardsIdentity)) {
                ArrayList<Object> result = new ArrayList<>();
                if (!empty) {
                    if (!comments) result.add(nativeCard(false, 72, true));
                    for (int i = 0; i < Math.min(73, page * 27 + 1); i += 3) result.add(nativeCard(comments, i, false));
                }
                cards = result; cardsIdentity = cardKey;
            }
            Object feed = construct("d6j", 11, ProfileArchivePatch.immutableList(cards), null, page < 3 && !empty,
                    false, false, construct("c6j", 1, -1), null, false,
                    construct("vvf0", 2, enumValue("com.reddit.listing.model.sort.SortType", sort), null),
                    visible, construct("y4j", 2, false, false));
            Object merged = ProfileArchivePatch.decorateHost(feed, composer, HOST);
            lastSections = ArchiveProbe.sections(merged); return merged;
        } catch (Exception error) { throw new AssertionError(error); }
    }
    public static void refresh(String username, boolean comments) {
        ProfileArchivePatch.request(username, comments, null, true, new Object());
    }
    public static void sortChanged(String value) {
        sort = value;
        ArchiveProfileContext context = (ArchiveProfileContext) ((Map<?, ?>) staticField(ProfileArchivePatch.class, "PROFILES")).get(HOST);
        context.generation++; ArchiveController.invalidate(context);
    }
    public static int liveCount() { return (int) lastSections.stream().filter(x -> !(x instanceof ArchiveSection)).count(); }
    public static int recoveredCount() { return (int) lastSections.stream().filter(x -> x instanceof ArchiveSection && ((ArchiveSection)x).row.item != null).count(); }
    public static boolean hasStatus(String value) {
        return lastSections.stream().anyMatch(x -> x instanceof ArchiveSection && ((ArchiveSection)x).row.provenance.equals(value));
    }
    public static boolean hasIndicator() { return lastSections.stream().anyMatch(x -> x instanceof ArchiveSection && ((ArchiveSection)x).row.kind == ArchiveSnapshot.INDICATOR); }
    public static void verifyIdentityAndMapping() {
        java.util.Set<String> unique = new java.util.HashSet<>();
        Object factory;
        try { factory = construct("gvi", 2, construct("v1j", 5, true,
                (kotlin.jvm.functions.Function2<Object,Object,Object>)(a, b) -> null, 0f, null, false), 0); }
        catch (Exception error) { throw new AssertionError(error); }
        for (Object section : lastSections) {
            String key = ProfileArchivePatch.stableKey(section, true);
            if (!unique.add(key)) throw new AssertionError("Duplicate final lazy key " + key);
            try {
                java.lang.reflect.Method invoke = factory.getClass().getMethod("invoke", Object.class, Object.class);
                if (!key.equals(invoke.invoke(factory, 0, section)) || !key.equals(invoke.invoke(factory, 123, section)))
                    throw new AssertionError("Native final key changes during insertion");
            } catch (Exception error) { throw new AssertionError(error); }
            if (section instanceof ArchiveNativeSection) {
                int index = ArchiveController.originalIndex(key);
                if (index < 0 || !ArchiveController.originalKey(key).equals(ProfileArchivePatch.sectionKey(((ArchiveNativeSection)section).original) + "_" + index))
                    throw new AssertionError("Incorrect original visibility mapping");
            }
        }
    }
    public static void openReader(android.content.Context context) {
        for (Object section : lastSections) if (section instanceof ArchiveSection && ((ArchiveSection)section).row.item != null) {
            ArchiveSnapshot.Row row = ((ArchiveSection)section).row;
            ArchiveReaderActivity.open(context, row.item, row.details, row.provenance); return;
        }
        throw new AssertionError("No recovered card to open");
    }
    public static String firstKey(int index) { return index == 0 ? "probe_header" : ProfileArchivePatch.stableKey(lastSections.get(index - 1), true); }
    public static void verifyProjection() throws Exception {
        // Load patched host classes too: Android performs register/type verification here.
        for (String name : List.of("com.reddit.feeds.impl.ui.RedditFeedViewModel", "k32", "gvi", "mc20", "qvu", "os8", "b7f", "zuu", "as8",
                "com.reddit.profile.submittedpostsfeed.viewmodel.SubmittedPostsViewModel",
                "com.reddit.profile.submittedcommentsfeed.viewmodel.SubmittedCommentsViewModel")) Class.forName(name);
        ProfileArchivePatch.postRecord("t3_probe", java.time.Instant.ofEpochSecond(100), "[deleted]", null, true);
        ProfileArchivePatch.postStatus("t3_probe", List.of());
        ArchiveNativeItem post = (ArchiveNativeItem)((Map<?, ?>)staticField(ProfileArchivePatch.class, "POSTS")).get("t3_probe");
        if (!post.pinned || !post.placeholder || !post.status.equals("Deleted") || post.createdUtc != 100) throw new AssertionError("Post metadata lost");
        ProfileArchivePatch.postRecord("t3_probe", java.time.Instant.ofEpochSecond(100), "Readable text", enumValue("com.reddit.type.RemovedByCategory", "MODERATOR"), false);
        post = (ArchiveNativeItem)((Map<?, ?>)staticField(ProfileArchivePatch.class, "POSTS")).get("t3_probe");
        if (post.placeholder) throw new AssertionError("Readable removed card must stay native");
        Object host = new Object(), provider = new Object();
        ((Map<Object, ArchiveProfileContext>) staticField(ProfileArchivePatch.class, "PROFILES"))
                .put(host, new ArchiveProfileContext(host, "sort_probe", false));
        ProfileArchivePatch.bindSort(host, provider, "sort_probe");
        Object top = construct("vvf0", 2, enumValue("com.reddit.listing.model.sort.SortType", "TOP"), null);
        Object newest = construct("vvf0", 2, enumValue("com.reddit.listing.model.sort.SortType", "NEW"), null);
        if (!newest.equals(ProfileArchivePatch.sort(provider, top))) throw new AssertionError("Posts session does not default to New");
        ProfileArchivePatch.savedSort("sort_probe", top);
        ProfileArchivePatch.bindSort(host, provider, "sort_probe");
        if (ProfileArchivePatch.sort(provider, null) != top) throw new AssertionError("Explicit sort was overwritten");
    }
    private static Object nativeCard(boolean comments, int index, boolean pinned) throws Exception {
        String id = (comments ? "t1_" : "t3_") + Integer.toString(1000 + index, 36);
        String body = index == 6 ? "[deleted]" : "Native comment " + index + ". This is Reddit's own card and renderer.";
        Object section, element;
        if (comments) {
            Object raw = construct("rf50", 10, id, java.time.Instant.ofEpochSecond(1790000000L - index * 60), 42f, body,
                    "ArchiveTest", "t3_" + Integer.toString(1000 + index, 36), "Native discussion " + index, null, false, null);
            element = construct("mw9", 3, id, raw, null);
            Object meta = construct("z1a", 4, text("r/ArchiveTest"), "2h", "42", false);
            Object video = Class.forName("ch20").getMethod("T", Class.forName("zau")).invoke(null, new Object[]{null});
            Object state = construct("tf50", 5, text("Native discussion " + index), meta, text(body), false, video);
            section = construct("nw9", 3, state, "t3_" + Integer.toString(1000 + index, 36), id);
            section = construct("nw9", 4, id, section, null, 0);
        } else {
            Object indicators = construct("kxp", 9, id, id, false, null, 0, List.of(), false, false, List.of());
            Object flair = construct("ly20", 5, id, id, false, null, ProfileArchivePatch.immutableList(List.of()));
            element = construct("os8", 33, id, id, false, null, "", null, "r/ArchiveTest", "2h", "Two hours ago", null,
                    indicators, "Native post " + index, false, null, flair, List.of(), List.of(), null, null,
                    false, true, false, false, enumValue("com.reddit.feeds.model.PostTranslationIndicatorState", "None"),
                    enumValue("com.reddit.feeds.caching.data.DataSourceType", "FRESH_FROM_REMOTE"), false,
                    enumValue("com.reddit.useridentity.ProfileVerificationStatus", "NOT_VERIFIED"), null, false,
                    ProfileArchivePatch.immutableList(List.of()), false, false, false);
            section = construct("rs8", 18, id, element, false, false, false, true,
                    enumValue("com.reddit.feeds.data.FeedType", "SUBMITTED_POSTS"), "profile", "archive_probe", false,
                    false, false, false, false, false, null, false, false);
            ProfileArchivePatch.postRecord(id, java.time.Instant.ofEpochSecond(1790000000L - index * 60),
                    index == 6 ? "[deleted]" : "Native readable content", null, pinned);
        }
        section = ArchiveProbe.wrapSections(section);
        ProfileArchivePatch.converted(section, element);
        return section;
    }
    private static Object text(String text) throws Exception { return Class.forName("ij20").getMethod("e0", String.class).invoke(null, text); }
    private static Enum<?> enumValue(String type, String value) throws Exception { return (Enum<?>)Class.forName(type).getField(value).get(null); }
    private static Object construct(String type, int count, Object... arguments) throws Exception {
        for (Constructor<?> constructor : Class.forName(type).getConstructors()) if (constructor.getParameterCount() == count) return constructor.newInstance(arguments);
        throw new AssertionError("Constructor missing: " + type);
    }
    private static Object staticField(Class<?> type, String name) {
        try { java.lang.reflect.Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(null); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
