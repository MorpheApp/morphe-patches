package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.view.View;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/** Patching adds the native feed-section interface and its key/content methods. */
public final class ArchiveSection {
    final ArchiveController controller;
    final ArchiveSnapshot.Row row;
    ArchiveSection(ArchiveController controller, ArchiveSnapshot.Row row) { this.controller = controller; this.row = row; }
    public String key() { return row.key; }
    public void render(Object composer) {
        ProfileArchivePatch.androidView(new Factory(this), new Update(this), composer);
    }
    private static final class Factory implements Function1<Context, View> {
        private final ArchiveSection section;
        Factory(ArchiveSection section) { this.section = section; }
        @Override public View invoke(Context context) { return new ArchiveRowView(context, section); }
    }
    private static final class Update implements Function1<View, Unit> {
        private final ArchiveSection section;
        Update(ArchiveSection section) { this.section = section; }
        @Override public Unit invoke(View view) { ((ArchiveRowView) view).bind(section); return ProfileArchivePatch.unit(); }
    }
}
