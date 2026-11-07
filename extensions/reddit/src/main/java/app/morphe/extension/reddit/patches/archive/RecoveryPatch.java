package app.morphe.extension.reddit.patches.archive;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.settings.Setting;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.lang.ref.WeakReference;

/** Display overlays only: original Reddit objects and action/permission state remain intact. */
public final class RecoveryPatch {
    private RecoveryPatch() {}
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ArchiveRecoveryStore STORE = new ArchiveRecoveryStore(new ArchiveClient()::records,
            Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "Reddit-undelete"); t.setDaemon(true); return t; }),
            MAIN::post, SystemClock::elapsedRealtime);
    private static final RecoveryOriginals ORIGINALS = new RecoveryOriginals();
    private static final RecoveryOriginals ELEMENTS = new RecoveryOriginals();
    private static final RecoveryOriginals MANUAL_MODELS = new RecoveryOriginals();
    private static final RecoveryOriginals CLASSIC_CONTENT_MODELS = new RecoveryOriginals();
    private static final LinkedHashMap<String, WeakReference<ArchiveItem>> RECOVERED = new LinkedHashMap<>();
    private static final LinkedHashMap<String, Boolean> REMOVED = new LinkedHashMap<>();
    private static final ThreadLocal<Boolean> MAPPING = ThreadLocal.withInitial(() -> false);
    private static final Object ENABLED_STATE = ProfileArchivePatch.newState(0);
    private static int settingRevision;
    // Keep the listener strongly referenced: SharedPreferences holds listeners weakly.
    private static final android.content.SharedPreferences.OnSharedPreferenceChangeListener SETTING_LISTENER = (prefs, key) -> {
        if (key == null || key.equals(Settings.PROFILE_ARCHIVE.key)) MAIN.post(() -> {
            STORE.clear();
            synchronized (RECOVERED) { RECOVERED.clear(); }
            ProfileArchivePatch.writeState(ENABLED_STATE, ++settingRevision);
        });
    };
    static { Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(SETTING_LISTENER); }
    static boolean enabled() { return Settings.PROFILE_ARCHIVE.get(); }
    public static RecoveryTarget describe(Object model) { throw new UnsupportedOperationException(); }
    public static RecoveryTarget profileTarget(Object section, Object state) { throw new UnsupportedOperationException(); }
    private static Object copy(Object model, ArchiveRecovery fields, boolean tag) { throw new UnsupportedOperationException(); }
    static Object convert(Object converter, Object model) { throw new UnsupportedOperationException(); }
    static void render(Object section, Object scope, Object composer, int flags) { throw new UnsupportedOperationException(); }
    static String key(Object section) { throw new UnsupportedOperationException(); }
    static Object classicModel(Object section) { throw new UnsupportedOperationException(); }
    static Object classicHeader(Object model) { throw new UnsupportedOperationException(); }
    public static boolean hideClassicHeader(Object section) {
        Object model = classicModel(section);
        synchronized (CLASSIC_CONTENT_MODELS) { return Boolean.TRUE.equals(CLASSIC_CONTENT_MODELS.getOrDefault(model, false)); }
    }
    public static Object richElements(String json) { throw new UnsupportedOperationException(); }

    public static synchronized void evidence(String id, String body, boolean removed) {
        id = ArchiveRecovery.fullname(id, false);
        if (id == null) return;
        REMOVED.put(id, removed || ArchiveRecovery.placeholder(body));
        while (REMOVED.size() > 1024) REMOVED.remove(REMOVED.keySet().iterator().next());
    }
    static synchronized boolean removed(RecoveryTarget target) {
        return target.removed || Boolean.TRUE.equals(REMOVED.get(target.id));
    }
    static void observe(RecoveryTarget target, Object composer) {
        observe(target, composer, false);
    }
    static void observe(RecoveryTarget target, Object composer, boolean manual) {
        ProfileArchivePatch.begin(composer);
        try {
            ProfileArchivePatch.readState(ENABLED_STATE);
            Object remembered = ProfileArchivePatch.remembered(composer);
            if (target == null || target.id == null || !enabled() || NativeProfileSection.suppressed(composer)) {
                if (remembered instanceof RecoveryBinding) ((RecoveryBinding) remembered).onForgotten();
                ProfileArchivePatch.remember(composer, null);
                return;
            }
            boolean lookup = !manual && ArchiveRecovery.eligible(target.title, target.body, target.author, removed(target));
            RecoveryBinding binding;
            if (remembered instanceof RecoveryBinding && ((RecoveryBinding) remembered).id.equals(target.id)
                    && ((RecoveryBinding) remembered).generation == settingRevision
                    && ((RecoveryBinding) remembered).manual == manual) {
                binding = (RecoveryBinding) remembered; binding.lookup(lookup);
            } else {
                binding = new RecoveryBinding(target.id, lookup, settingRevision, manual); ProfileArchivePatch.remember(composer, binding);
            }
            ProfileArchivePatch.readState(binding.state);
        } finally { ProfileArchivePatch.end(composer); }
    }
    public static Object recover(Object model, Object composer) {
        Object original;
        synchronized (ORIGINALS) { original = ORIGINALS.getOrDefault(model, model); }
        if (NativeProfileSection.suppressed(composer)) {
            observe(null, composer);
            return original;
        }
        RecoveryTarget target = describe(original);
        boolean manual;
        synchronized (MANUAL_MODELS) { manual = Boolean.TRUE.equals(MANUAL_MODELS.getOrDefault(original, false)); }
        observe(target, composer, manual);
        Object result = project(original, target, manual);
        if (result != original) synchronized (ORIGINALS) { ORIGINALS.put(result, original); }
        return result;
    }
    public static Object recoverProfile(Object section, Object model, Object composer) {
        Object original;
        synchronized (ORIGINALS) { original = ORIGINALS.getOrDefault(model, model); }
        if (NativeProfileSection.suppressed(composer)) {
            observe(null, composer);
            return original;
        }
        RecoveryTarget target = profileTarget(section, original);
        observe(target, composer);
        Object result = project(original, target);
        if (result != original) synchronized (ORIGINALS) { ORIGINALS.put(result, original); }
        return result;
    }
    static Object project(Object original, RecoveryTarget target) {
        return project(original, target, false);
    }
    static Object project(Object original, RecoveryTarget target, boolean manual) {
        if (!enabled() || target == null || target.id == null) return original;
        if (manual && !STORE.revealed(target.id)) return original;
        ArchiveItem item = STORE.peek(target.id);
        if (item == null) return original;
        ArchiveRecovery fields = ArchiveRecovery.resolve(target.id, target.parent, target.title, target.body,
                target.author, removed(target), item);
        boolean tag;
        synchronized (RECOVERED) {
            WeakReference<ArchiveItem> previous = RECOVERED.get(target.id);
            tag = previous != null && previous.get() == item;
            if (fields.changed && !tag) {
                RECOVERED.put(target.id, new WeakReference<>(item));
                while (RECOVERED.size() > 256) RECOVERED.remove(RECOVERED.keySet().iterator().next());
                MAIN.post(() -> STORE.signal(target.id));
                tag = true;
            }
        }
        if (!fields.changed && !tag) return original;
        Object result = copy(original, fields, tag);
        if (result != original) synchronized (ORIGINALS) { ORIGINALS.put(result, original); }
        return result;
    }
    public static Object wrap(Object section, Object element, Object converter) {
        if (section == null || MAPPING.get()) return section;
        synchronized (ELEMENTS) { ELEMENTS.put(section, element); }
        RecoveryTarget target = describe(element);
        return target == null || target.id == null ? section : new RecoverySection(section, element, converter, target);
    }
    static Object element(Object section) {
        if (section instanceof RecoverySection) return ((RecoverySection) section).element;
        synchronized (ELEMENTS) { return ELEMENTS.getOrDefault(section, null); }
    }
    static Object isolate(Object element, RecoveryTarget target, int classicPart) {
        Object result = classicPart == 2 ? classicHeader(element) : copy(element, ArchiveRecovery.resolve(target.id, target.parent, target.title,
                target.body, target.author, false, null), false);
        synchronized (MANUAL_MODELS) { MANUAL_MODELS.put(result, true); }
        if (classicPart == 1) synchronized (CLASSIC_CONTENT_MODELS) { CLASSIC_CONTENT_MODELS.put(result, true); }
        return result;
    }
    static Object remap(Object converter, Object model) {
        MAPPING.set(true);
        try {
            Object section = convert(converter, model);
            synchronized (MANUAL_MODELS) {
                if (Boolean.TRUE.equals(MANUAL_MODELS.getOrDefault(model, false))) {
                    // Reddit's classic mapper copies its input before constructing the
                    // renderer. Its child restart scope must retain the same policy.
                    Object mapped = classicModel(section);
                    if (mapped != null) MANUAL_MODELS.put(mapped, true);
                    synchronized (CLASSIC_CONTENT_MODELS) {
                        if (mapped != null && Boolean.TRUE.equals(CLASSIC_CONTENT_MODELS.getOrDefault(model, false)))
                            CLASSIC_CONTENT_MODELS.put(mapped, true);
                    }
                }
            }
            return section;
        }
        finally { MAPPING.set(false); }
    }
    public static String field(String original, String replacement) { return replacement == null ? original : replacement; }
    public static String author(String original, String replacement) {
        String value = original != null && original.startsWith("u/") ? original.substring(2) : original;
        return replacement == null || value != null && !value.isBlank() && !ArchiveRecovery.placeholder(value)
                ? original : (original != null && original.startsWith("u/") ? "u/" : "") + replacement;
    }
    public static String authorText(String author, String details) {
        String value = details != null && details.startsWith("u/") ? details.substring(2) : details;
        return ArchiveRecovery.placeholder(value) ? details : author;
    }
    public static String stamp(String original, boolean recovered) {
        return recovered && !"Undeleted".equals(original) && (original == null || !original.endsWith(" · Undeleted"))
                ? (original == null || original.isEmpty() ? "Undeleted" : original + " · Undeleted") : original;
    }
    public static String preview(String original, String body) { return body == null ? original : ArchiveRichText.preview(body); }
    public static String richText(String original, String body) { return body == null ? original : ArchiveRichText.json(body); }
    public static String bodyText(String plain, String rich) {
        String rendered = ArchiveRichText.plain(rich);
        if (plain != null && !plain.isBlank() && !ArchiveRecovery.placeholder(plain)) return plain;
        return rendered != null && !rendered.isBlank() && !ArchiveRecovery.placeholder(rendered) ? rendered : plain;
    }
    public static void seed(ArchiveItem item) { if (enabled()) STORE.seed(item); }
}
