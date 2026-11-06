package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.view.View;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Patching adds the native feed-section interface and its key/content methods. */
public final class ArchiveSection {
    final ArchiveController controller;
    final ArchiveSnapshot.Row row;
    Object eventContext;
    ArchiveSection(ArchiveController controller, ArchiveSnapshot.Row row) { this.controller = controller; this.row = row; }
    public String key() { return row.key; }
    public void render(Object eventContext, Object composer) {
        this.eventContext = eventContext;
        int[] colors = ProfileArchivePatch.themeColors(composer);
        ProfileArchivePatch.androidView(new Factory(this, colors), new Update(this, colors), composer);
    }
    private static final class Factory implements Function1<Context, View> {
        private final ArchiveSection section;
        private final int[] colors;
        Factory(ArchiveSection section, int[] colors) { this.section = section; this.colors = colors; }
        @Override public View invoke(Context context) { return new ArchiveRowView(context, section, colors); }
    }
    private static final class Update implements Function1<View, Unit> {
        private final ArchiveSection section;
        private final int[] colors;
        Update(ArchiveSection section, int[] colors) { this.section = section; this.colors = colors; }
        @Override public Unit invoke(View view) { ((ArchiveRowView) view).bind(section, colors); return ProfileArchivePatch.unit(); }
    }
}
