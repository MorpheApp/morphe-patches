package app.morphe.extension.reddit.patches.archive;

import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

/** A composition local keeps the profile policy across independent child recompositions. */
public final class NativeProfileSection {
    private static final Object LOCAL = newLocal(new DefaultProfile());
    private final Object original;
    private final ArchiveProfileContext profile;

    NativeProfileSection(Object original, ArchiveProfileContext profile) {
        this.original = original; this.profile = profile;
    }

    private static Object newLocal(Function0<Object> defaultValue) { throw new UnsupportedOperationException(); }
    private static Object current(Object composer) { throw new UnsupportedOperationException(); }
    private static void provide(Object local, Object value, Function2<Object, Integer, Unit> content,
                                Object composer) { throw new UnsupportedOperationException(); }
    private static Object bindScreen(Object screen) { throw new UnsupportedOperationException(); }
    private static void screenContent(Object screen, Object composer, int flags) { throw new UnsupportedOperationException(); }

    /** Scope the screen before it constructs feeds or renders independently composed children. */
    public static void renderScreen(Object screen, Object composer, int flags) {
        ArchiveProfileContext profile = ProfileArchivePatch.profile(bindScreen(screen));
        provide(LOCAL, profile, new ScreenContent(screen), composer);
    }

    static boolean suppressed(Object composer) {
        ArchiveProfileContext profile = context(composer);
        return profile != null && !profile.archiveAllowed();
    }

    static ArchiveProfileContext context(Object composer) {
        ArchiveProfileContext bound = (ArchiveProfileContext) current(composer);
        if (bound == null) return null;
        // Child restart scopes can outlive a session change without recomposing
        // the screen. Refresh ownership, but never adopt a different profile.
        ArchiveProfileContext latest = ProfileArchivePatch.profile(bound.host.get());
        return latest != null && latest.comments == bound.comments
                && latest.username.equalsIgnoreCase(bound.username) ? latest : bound;
    }

    public String key() { return RecoveryPatch.key(original); }

    public void render(Object scope, Object composer, int flags) {
        provide(LOCAL, profile, new Content(original, scope), composer);
    }

    // Keep an explicit Kotlin callback: extension R8 can strip a synthetic SAM's
    // invoke() when the host Kotlin interface is only present at patch time.
    private static final class DefaultProfile implements Function0<Object> {
        @Override public Object invoke() { return null; }
    }

    private static final class Content implements Function2<Object, Integer, Unit> {
        private final Object original, scope;
        Content(Object original, Object scope) { this.original = original; this.scope = scope; }
        @Override public Unit invoke(Object composer, Integer flags) {
            RecoveryPatch.render(original, scope, composer, 0);
            return ProfileArchivePatch.unit();
        }
    }

    private static final class ScreenContent implements Function2<Object, Integer, Unit> {
        private final Object screen;
        ScreenContent(Object screen) { this.screen = screen; }
        @Override public Unit invoke(Object composer, Integer flags) {
            screenContent(screen, composer, 0);
            return ProfileArchivePatch.unit();
        }
    }
}
