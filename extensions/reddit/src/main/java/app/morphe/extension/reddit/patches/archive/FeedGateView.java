package app.morphe.extension.reddit.patches.archive;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.Arrays;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

/** Native content remains below a separate, accessible button; only content is blurred. */
final class FeedGateView extends FrameLayout {
    private final BlurredContent content;
    private final View scrim;
    private final LinearLayout controls;
    private final Button button;
    private final ProgressBar progress;
    private final TextView message;
    private FeedGateSection section;
    private Object scope;
    private int[] palette;

    FeedGateView(Context context, FeedGateSection section, Object scope, int[] colors) {
        super(context);
        setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        content = new BlurredContent(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        content.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        // Compose's AndroidView interop exposes embedded views directly through its
        // virtual accessibility tree. Hide those descendants as they are attached too.
        content.getViewTreeObserver().addOnGlobalLayoutListener(() -> hideContentAccessibility(content));
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        scrim = new View(context);
        scrim.setClickable(true); // Swallows card clicks; the parent can still intercept a scroll.
        scrim.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(scrim, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setGravity(Gravity.CENTER);
        progress = new ProgressBar(context);
        button = new Button(context);
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setOnClickListener(view -> {
            if (!RecoveryPatch.enabled() || this.section == null) return;
            RecoveryPatch.STORE.request(this.section.id);
            updateStatus();
        });
        message = new TextView(context);
        message.setGravity(Gravity.CENTER);
        message.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        controls.addView(progress);
        controls.addView(button);
        controls.addView(message);
        addView(controls, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        bind(section, scope, colors);
    }

    private static void hideContentAccessibility(View view) {
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hideContentAccessibility(group.getChildAt(i));
        }
    }
    void bind(FeedGateSection section, Object scope, int[] colors) {
        boolean rebuild = this.section != section || this.scope != scope || !Arrays.equals(palette, colors);
        this.section = section; this.scope = scope; palette = colors.clone();
        ArchiveTheme theme = new ArchiveTheme(getContext(), colors);
        setMinimumHeight(theme.dp(112));
        content.setMinimumHeight(theme.dp(112));
        content.setBackgroundColor(theme.background);
        if (Build.VERSION.SDK_INT >= 31) content.setRenderEffect(
                RenderEffect.createBlurEffect(theme.dp(12), theme.dp(12), Shader.TileMode.CLAMP));
        scrim.setBackgroundColor((theme.background & 0x00ffffff) | 0x66000000);
        button.setTextColor(theme.background);
        button.setTypeface(theme.semibold);
        button.setTextSize(14);
        button.setMinHeight(theme.dp(48));
        button.setMinimumHeight(theme.dp(48));
        button.setPadding(theme.dp(24), theme.dp(10), theme.dp(24), theme.dp(10));
        button.setBackground(theme.ripple(theme.foreground, 28));
        message.setTextColor(theme.foreground);
        message.setTypeface(theme.semibold);
        message.setTextSize(14);
        message.setPadding(theme.dp(16), theme.dp(8), theme.dp(16), theme.dp(8));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(theme.foreground));
        progress.setLayoutParams(new LinearLayout.LayoutParams(theme.dp(20), theme.dp(20)));
        if (rebuild) {
            content.removeAllViews();
            Context activity = getContext();
            while (activity instanceof ContextWrapper && !(activity instanceof android.app.Activity))
                activity = ((ContextWrapper) activity).getBaseContext();
            for (Object child : section.blurredContent) {
                View view = FeedGateSection.contentView(activity, new Content(child, scope));
                content.addView(view, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            }
        }
        updateStatus();
    }

    private static final class Content implements Function2<Object, Integer, Unit> {
        private final Object section, scope;
        Content(Object section, Object scope) { this.section = section; this.scope = scope; }
        @Override public Unit invoke(Object composer, Integer flags) {
            RecoveryPatch.render(section, scope, composer, 0);
            return ProfileArchivePatch.unit();
        }
    }
    private void updateStatus() {
        ArchiveRecoveryStore.Status status = RecoveryPatch.STORE.feedStatus(section.id);
        boolean loading = status == ArchiveRecoveryStore.Status.LOADING;
        boolean unavailable = status == ArchiveRecoveryStore.Status.MISSING || status == ArchiveRecoveryStore.Status.READY;
        button.setText(loading ? "Undeleting…" : status == ArchiveRecoveryStore.Status.FAILED ? "Retry" : "Undelete");
        button.setEnabled(!loading);
        button.setVisibility(unavailable ? GONE : VISIBLE);
        progress.setVisibility(loading ? VISIBLE : GONE);
        message.setText(unavailable ? "No archived content found" : status == ArchiveRecoveryStore.Status.FAILED
                ? "Couldn’t load archived content" : "");
        message.setVisibility(message.getText().length() == 0 ? GONE : VISIBLE);
    }

    /** Downsampled two-pass box blur for Android 9–11; never draws an unblurred frame. */
    private static final class BlurredContent extends LinearLayout {
        private Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect bounds = new Rect();
        BlurredContent(Context context) { super(context); }
        @Override protected void dispatchDraw(Canvas canvas) {
            if (Build.VERSION.SDK_INT >= 31) { super.dispatchDraw(canvas); return; }
            int width = Math.max(1, getWidth() / 12), height = Math.max(1, getHeight() / 12);
            if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                if (bitmap != null) bitmap.recycle();
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            }
            bitmap.eraseColor(android.graphics.Color.TRANSPARENT);
            Canvas snapshot = new Canvas(bitmap);
            snapshot.scale(width / (float) Math.max(1, getWidth()), height / (float) Math.max(1, getHeight()));
            super.dispatchDraw(snapshot);
            int[] pixels = new int[width * height], blurred = new int[pixels.length];
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int a = 0, r = 0, g = 0, b = 0;
                    for (int offset = -2; offset <= 2; offset++) {
                        int xx = pass == 0 ? Math.max(0, Math.min(width - 1, x + offset)) : x;
                        int yy = pass == 1 ? Math.max(0, Math.min(height - 1, y + offset)) : y;
                        int c = pixels[yy * width + xx];
                        a += c >>> 24; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
                    }
                    blurred[y * width + x] = (a / 5 << 24) | (r / 5 << 16) | (g / 5 << 8) | b / 5;
                }
                int[] swap = pixels; pixels = blurred; blurred = swap;
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            bounds.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(bitmap, null, bounds, paint);
        }
        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (bitmap != null) { bitmap.recycle(); bitmap = null; }
        }
    }
}
