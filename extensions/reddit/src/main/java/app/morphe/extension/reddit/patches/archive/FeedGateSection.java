package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import kotlin.jvm.functions.Function1;

/** One native section spanning only a removed post's content, with explicit feed context. */
public final class FeedGateSection {
    final String id;
    final List<?> content;
    final List<?> blurredContent;
    private final List<RecoverySection> headers;
    private final RecoveryTarget target;
    FeedGateSection(String id, List<?> content) {
        this.id = id; this.content = List.copyOf(content);
        List<Object> blurred = new ArrayList<>();
        headers = new ArrayList<>();
        for (Object child : content) {
            if (child instanceof RecoverySection && ((RecoverySection) child).target.kind == 4) {
                RecoverySection classic = (RecoverySection) child;
                headers.add(classic.classicPart(2));
                blurred.add(classic.classicPart(1));
            } else blurred.add(child);
        }
        blurredContent = List.copyOf(blurred);
        target = new RecoveryTarget(13, id, null, null, null, null, false);
    }
    public static Object prepare(Object group, Object mapper) { throw new UnsupportedOperationException(); }
    static int role(Object element) { throw new UnsupportedOperationException(); }
    static View contentView(Context context, Function2<Object, Integer, Unit> content) { throw new UnsupportedOperationException(); }

    public static List<?> group(List<?> sections, String name, boolean removed, Enum<?> feedType) {
        String id = ArchiveRecovery.fullname(name, false);
        if (id == null || feedType == null) return sections;
        if (feedType.name().equals("SUBMITTED_POSTS") || feedType.name().equals("SUBMITTED_COMMENTS")
                || feedType.name().equals("PDP") || feedType.name().equals("FBP")) {
            return surface(sections, false);
        }
        boolean eligible = removed;
        int first = -1, last = -1;
        for (int i = 0; i < sections.size(); i++) {
            Object section = sections.get(i);
            Object element = RecoveryPatch.element(section);
            RecoveryTarget target = element == null ? null : RecoveryPatch.describe(element);
            if (target != null && id.equals(target.id)) eligible |= RecoveryPatch.removed(target)
                    || ArchiveRecovery.placeholder(target.title) || ArchiveRecovery.placeholder(target.body);
            if (element != null && role(element) == 0) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (!eligible || first < 0) return sections;
        if (removed) RecoveryPatch.evidence(id, null, true);
        sections = surface(sections, true);
        List<Object> result = new ArrayList<>(sections.subList(0, first));
        result.add(new FeedGateSection(id, sections.subList(first, last + 1)));
        result.addAll(sections.subList(last + 1, sections.size()));
        return result;
    }
    private static List<?> surface(List<?> sections, boolean manual) {
        List<Object> result = null;
        for (int i = 0; i < sections.size(); i++) if (sections.get(i) instanceof RecoverySection) {
            RecoverySection original = (RecoverySection) sections.get(i), next = original.withManual(manual);
            if (next != original) {
                if (result == null) result = new ArrayList<>(sections);
                result.set(i, next);
            }
        }
        return result == null ? sections : result;
    }
    public String key() { return "morphe_undelete|" + id; }
    boolean restored() {
        if (!RecoveryPatch.STORE.revealed(id)) return false;
        ArchiveItem item = RecoveryPatch.STORE.peek(id);
        // A removed media card can already have its original title locally. A
        // matching archive with readable content is still a successful reveal.
        return item != null && ArchiveMerge.recoverable(item);
    }
    public void render(Object scope, Object composer, int flags) {
        RecoveryPatch.observe(target, composer, true);
        if (!RecoveryPatch.enabled() || restored()) {
            for (Object section : content) RecoveryPatch.render(section, scope, composer, 0);
            return;
        }
        for (RecoverySection header : headers) header.render(scope, composer, 0);
        int[] colors = ProfileArchivePatch.themeColors(composer);
        ProfileArchivePatch.androidView(new Factory(this, scope, colors), new Update(this, scope, colors), composer);
    }
    // Explicit bridges survive extension R8 compilation against the host's Kotlin interfaces.
    private static final class Factory implements Function1<Context, View> {
        final FeedGateSection section; final Object scope; final int[] colors;
        Factory(FeedGateSection section, Object scope, int[] colors) { this.section=section; this.scope=scope; this.colors=colors; }
        @Override public View invoke(Context context) { return new FeedGateView(context, section, scope, colors); }
    }
    private static final class Update implements Function1<View, Unit> {
        final FeedGateSection section; final Object scope; final int[] colors;
        Update(FeedGateSection section, Object scope, int[] colors) { this.section=section; this.scope=scope; this.colors=colors; }
        @Override public Unit invoke(View view) {
            ((FeedGateView) view).bind(section, scope, colors); return ProfileArchivePatch.unit();
        }
    }
}
