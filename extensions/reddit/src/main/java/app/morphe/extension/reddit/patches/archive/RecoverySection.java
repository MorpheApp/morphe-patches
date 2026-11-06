package app.morphe.extension.reddit.patches.archive;

/** Native lazy item: re-runs Reddit's mapper with restored text, then delegates its renderer. */
public final class RecoverySection {
    final Object original, element, converter;
    private final RecoveryTarget target;
    private Object projected, rendered;
    RecoverySection(Object original, Object element, Object converter, RecoveryTarget target) {
        this.original = original; this.element = element; this.converter = converter; this.target = target;
    }
    public String key() { return RecoveryPatch.key(original); }
    public void render(Object scope, Object composer, int flags) {
        RecoveryPatch.observe(target, composer);
        Object next = RecoveryPatch.project(element, target);
        Object section = original;
        if (next != element) {
            if (!next.equals(projected)) { projected = next; rendered = RecoveryPatch.remap(converter, next); }
            if (rendered != null) section = rendered;
        }
        RecoveryPatch.render(section, scope, composer, 0);
    }
}
