package app.morphe.extension.reddit.patches.archive;

/** Native lazy item: re-runs Reddit's mapper with restored text, then delegates its renderer. */
public final class RecoverySection {
    final Object original, element, converter;
    final RecoveryTarget target;
    final boolean manual;
    private final int classicPart; // 0: full card, 1: blurred content, 2: clear header.
    private Object isolated;
    private Object projected, rendered;
    RecoverySection(Object original, Object element, Object converter, RecoveryTarget target) {
        this(original, element, converter, target, false);
    }
    private RecoverySection(Object original, Object element, Object converter, RecoveryTarget target, boolean manual) {
        this(original, element, converter, target, manual, 0);
    }
    private RecoverySection(Object original, Object element, Object converter, RecoveryTarget target, boolean manual, int classicPart) {
        this.original = original; this.element = element; this.converter = converter; this.target = target; this.manual = manual;
        this.classicPart = classicPart;
    }
    RecoverySection withManual(boolean manual) {
        return this.manual == manual ? this : new RecoverySection(original, element, converter, target, manual);
    }
    public String key() { return RecoveryPatch.key(original); }
    RecoverySection classicPart(int part) { return new RecoverySection(original, element, converter, target, true, part); }
    public void render(Object scope, Object composer, int flags) {
        if (NativeProfileSection.suppressed(composer)) {
            RecoveryPatch.observe(null, composer, manual);
            RecoveryPatch.render(original, scope, composer, 0);
            return;
        }
        RecoveryPatch.observe(target, composer, manual);
        // Classic cards also have a child restart hook. Give it a presentation owned
        // by this section, so a shared profile model cannot change the feed policy.
        Object source = element;
        if (manual && target.kind == 4) {
            if (isolated == null) isolated = RecoveryPatch.isolate(element, target, classicPart);
            source = isolated;
        }
        Object next = RecoveryPatch.project(source, target, manual);
        Object section = original;
        if (next != element) {
            if (!next.equals(projected)) { projected = next; rendered = RecoveryPatch.remap(converter, next); }
            if (rendered != null) section = rendered;
        }
        RecoveryPatch.render(section, scope, composer, 0);
    }
}
