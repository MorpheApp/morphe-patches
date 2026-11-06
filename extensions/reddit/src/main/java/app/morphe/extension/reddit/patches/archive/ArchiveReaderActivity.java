package app.morphe.extension.reddit.patches.archive;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.morphe.extension.reddit.settings.Settings;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Private native reader. Intent/saved state contain identifiers, never large archived bodies. */
public final class ArchiveReaderActivity extends Activity {
    private static final Map<String, Entry> RECENT = new LinkedHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Reddit-archive-reader"); thread.setDaemon(true); return thread;
    });
    private ArchiveTheme theme;
    private Entry entry;
    private ScrollView scroll;
    private LinearLayout content;
    private ArchiveClient.Cancellation cancellation;
    private Future<?> pending;
    private ArchiveImageLoader.Request imageRequest;
    private int generation, scrollY, galleryIndex;
    private boolean needsDetails;

    private static final class Entry {
        final ArchiveItem item;
        final ArchivePostDetails details;
        final String status;
        Entry(ArchiveItem item, ArchivePostDetails details, String status) {
            this.item = item; this.details = details; this.status = status;
        }
    }
    public static void open(Context context, ArchiveItem item, ArchivePostDetails details, String status) {
        String token = UUID.randomUUID().toString();
        synchronized (RECENT) {
            RECENT.put(token, new Entry(item, details, status));
            int characters = 0;
            for (Entry value : RECENT.values()) characters += value.item.text.length() + value.item.title.length()
                    + (value.details == null ? 0 : value.details.characters());
            while (!RECENT.isEmpty() && (RECENT.size() > 4 || characters > 500000)) {
                Entry removed = RECENT.remove(RECENT.keySet().iterator().next());
                characters -= removed.item.text.length() + removed.item.title.length()
                        + (removed.details == null ? 0 : removed.details.characters());
            }
        }
        Intent intent = new Intent(context, ArchiveReaderActivity.class).putExtra("token", token)
                .putExtra("author", item.author).putExtra("id", item.id).putExtra("comments", item.comments)
                .putExtra("status", status).putExtra("colors", new ArchiveTheme(context).colors());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        theme = new ArchiveTheme(this, getIntent().getIntArrayExtra("colors"));
        int lightBars = android.graphics.Color.luminance(theme.background) > .5f
                ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0;
        getWindow().getDecorView().setSystemUiVisibility(lightBars);
        getWindow().setStatusBarColor(theme.background); getWindow().setNavigationBarColor(theme.background);
        if (state != null) { scrollY = state.getInt("scroll"); galleryIndex = state.getInt("gallery"); }
        Object retained = getLastNonConfigurationInstance();
        synchronized (RECENT) { entry = retained instanceof Entry ? (Entry) retained : RECENT.get(getIntent().getStringExtra("token")); }
        needsDetails = entry == null || entry.details == null;
        render();
    }
    @Override public Object onRetainNonConfigurationInstance() { return entry; }
    @Override protected void onStart() {
        super.onStart();
        if (!Settings.PROFILE_ARCHIVE.get()) { finish(); return; }
        render();
        if (entry == null || needsDetails) load();
    }
    @Override protected void onStop() {
        scrollY = scroll == null ? scrollY : scroll.getScrollY();
        generation++;
        if (cancellation != null) cancellation.cancel();
        if (pending != null) pending.cancel(true);
        pending = null; cancellation = null;
        if (imageRequest != null) imageRequest.cancel();
        imageRequest = null;
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("scroll", scroll == null ? scrollY : scroll.getScrollY()); state.putInt("gallery", galleryIndex);
        super.onSaveInstanceState(state);
    }
    private void load() {
        if (pending != null) return;
        final int request = ++generation;
        final ArchiveClient.Cancellation token = new ArchiveClient.Cancellation(); cancellation = token;
        final Entry current = entry;
        pending = WORKER.submit(() -> {
            try {
                ArchiveClient client = new ArchiveClient();
                ArchiveItem item = current == null ? client.record(getIntent().getStringExtra("author"),
                        getIntent().getStringExtra("id"), getIntent().getBooleanExtra("comments", false), token) : current.item;
                ArchivePostDetails details = current == null ? null : current.details;
                if (details == null) {
                    try { details = client.details(java.util.List.of(item.comments ? item.linkId : item.id), token)
                            .get(item.comments ? item.linkId : item.id); }
                    catch (java.io.IOException ignored) { token.check(); }
                }
                if (!item.comments && details != null && !item.author.equalsIgnoreCase(details.author)) details = null;
                token.check();
                Entry result = new Entry(item, details, getIntent().getStringExtra("status"));
                runOnUiThread(() -> {
                    if (generation != request || isFinishing()) return;
                    entry = result; needsDetails = false; pending = null; cancellation = null;
                    scrollY = scroll.getScrollY(); render();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (generation != request || isFinishing()) return;
                    pending = null; cancellation = null;
                    if (entry == null) {
                        content.removeAllViews(); text("This archived item is unavailable.", 16, false);
                        button("Retry", this::load);
                    }
                });
            }
        });
    }
    private void render() {
        if (imageRequest != null) imageRequest.cancel(); imageRequest = null;
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.background);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView close = theme.text(16, theme.foreground, theme.semibold);
        close.setText("‹  Archived history"); close.setContentDescription("Close archived reader");
        close.setGravity(Gravity.CENTER_VERTICAL); close.setPadding(theme.dp(16), 0, theme.dp(16), 0);
        close.setMinHeight(theme.dp(56)); close.setOnClickListener(v -> finish()); root.addView(close);
        scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(theme.dp(16), theme.dp(12), theme.dp(16), theme.dp(24));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); root.requestApplyInsets();
        if (entry == null) { text("Loading archived item…", 16, false); return; }
        ArchiveItem item = entry.item; ArchivePostDetails detail = entry.details;
        String status = "Deleted".equals(entry.status) || "Removed".equals(entry.status) ? entry.status : "Archived";
        text("r/" + item.subreddit + " · u/" + item.author, 12, false);
        text(status + " · Arctic Shift · " + android.text.format.DateUtils.formatDateTime(this, item.createdUtc * 1000,
                android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_YEAR), 12, false);
        String title = item.comments ? detail == null ? "Archived comment" : detail.title : item.title;
        text(title, 20, true);
        String body = item.text.isBlank() || !ArchiveNativeItem.status(item.text).isEmpty()
                ? "The archive did not capture this item's text." : item.text;
        TextView bodyView = text(body, 16, false); bodyView.setTextColor(theme.foreground); bodyView.setTextIsSelectable(true);
        if (!item.comments) media(detail, item);
        text("Archived content and counts may be incomplete or outdated.", 12, false);
        button("Open original discussion", () -> startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse(item.discussionUrl())).setPackage(getPackageName())));
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }
    private void media(ArchivePostDetails detail, ArchiveItem item) {
        if (item.nsfw || item.spoiler || detail != null && (detail.nsfw || detail.spoiler)) {
            text("Hidden sensitive or spoiler preview · Open in Reddit", 14, false); return;
        }
        if (detail == null) {
            if (item.kind.contains("image") || item.kind.contains("video")) text("Media unavailable", 14, false);
            return;
        }
        int count = detail.gallery.size();
        galleryIndex = Math.max(0, Math.min(galleryIndex, Math.max(0, count - 1)));
        ArchivePostDetails.Preview preview = count > 0 ? detail.gallery.get(galleryIndex) : detail.preview;
        if (preview != null) {
            ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMinimumHeight(theme.dp(180)); image.setContentDescription(detail.video ? "Archived video poster" : "Archived image");
            content.addView(image, new LinearLayout.LayoutParams(-1, -2));
            TextView unavailable = text("Loading image…", 12, false);
            imageRequest = ArchiveImageLoader.load(preview.url, bitmap -> {
                image.setImageBitmap(bitmap); unavailable.setText(bitmap == null ? "Media unavailable" : "");
                unavailable.setVisibility(bitmap == null ? View.VISIBLE : View.GONE);
            });
        } else if (count > 0) text("This gallery image is unavailable.", 14, false);
        else if (detail.video || item.kind.contains("image") || item.kind.contains("video")) text("Media unavailable", 14, false);
        if (count > 1) {
            text("Image " + (galleryIndex + 1) + " of " + count, 12, false);
            if (galleryIndex > 0) button("Previous image", () -> { scrollY = scroll.getScrollY(); galleryIndex--; render(); });
            if (galleryIndex + 1 < count) button("Next image", () -> { scrollY = scroll.getScrollY(); galleryIndex++; render(); });
        }
        if (detail.video) text("Open the original discussion to play video.", 14, false);
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = theme.text(size, bold ? theme.foreground : theme.secondary, bold ? theme.bold : theme.regular);
        view.setText(value); view.setPadding(0, 0, 0, theme.dp(14)); content.addView(view); return view;
    }
    private void button(String label, Runnable action) {
        TextView view = text(label, 14, true); view.setMinHeight(theme.dp(48)); view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackground(theme.ripple(theme.surface, 12)); view.setPadding(theme.dp(12), theme.dp(12), theme.dp(12), theme.dp(12));
        view.setOnClickListener(v -> action.run());
    }
}
