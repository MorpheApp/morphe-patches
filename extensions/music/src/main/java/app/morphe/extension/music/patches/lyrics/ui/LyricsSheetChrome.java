/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.ui.Dim;

/** The header, padding and action dock shared by the lyrics slide-up sheets. */
final class LyricsSheetChrome {

    static final int BACKGROUND_COLOR = 0xFF0D0D10;
    /** Fixed ink for the always-dark sheets, independent of the custom lyrics text colour. */
    static final int DIALOG_BUTTON_TEXT_COLOR = 0xFFFFFFFF;

    private LyricsSheetChrome() {
    }

    static LinearLayout createTopBar(Context context, String title, Runnable onBack) {
        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Dim.dp8, Dim.dp8, Dim.dp16, Dim.dp4);

        ImageView back = new ImageView(context);
        back.setImageDrawable(new BackArrowDrawable());
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(str("morphe_music_lyrics_share_back"));
        back.setFocusable(true);
        attachInteractiveBackground(back, null, Dim.dp(24), 0x33FFFFFF);
        back.setOnClickListener(v -> onBack.run());
        topBar.addView(back, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(DIALOG_BUTTON_TEXT_COLOR);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        titleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = Dim.dp8;
        topBar.addView(titleView, params);
        return topBar;
    }

    /** Wraps sheet content with the same side and bottom breathing room as the share card. */
    static LinearLayout createContentHost(Context context) {
        LinearLayout host = new LinearLayout(context);
        host.setOrientation(LinearLayout.VERTICAL);
        host.setPadding(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp12);
        return host;
    }

    static LinearLayout createBottomDock(Context context) {
        LinearLayout dock = new LinearLayout(context);
        dock.setOrientation(LinearLayout.VERTICAL);
        dock.setPadding(Dim.dp16, Dim.dp(14), Dim.dp16, Dim.dp(18));
        return dock;
    }

    /**
     * The sheet is always dark, so its primary action keeps the white pill of the share sheet
     * even when the app theme reports the light colors of the surrounding dialog.
     */
    static void stylePrimaryButton(Button button) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        final float radius = Dim.dp(20);
        background.setCornerRadius(radius);
        background.setColor(0xFFFFFFFF);
        attachInteractiveBackground(button, background, radius, 0x22000000);
        button.setTextColor(0xFF0F0F14);
    }

    /** Transparent at rest, outlined when focused, rippled when pressed. */
    static void attachInteractiveBackground(View view, @Nullable Drawable baseDrawable,
                                            float focusCornerRadiusPx, int rippleColor) {
        GradientDrawable focusOverlay = new GradientDrawable();
        focusOverlay.setShape(GradientDrawable.RECTANGLE);
        focusOverlay.setCornerRadius(focusCornerRadiusPx);
        focusOverlay.setColor(Color.TRANSPARENT);
        focusOverlay.setStroke(Dim.dp(2), 0xFFFFFFFF);

        StateListDrawable states = new StateListDrawable();
        if (baseDrawable != null) {
            states.addState(new int[]{android.R.attr.state_focused},
                    new LayerDrawable(new Drawable[]{baseDrawable, focusOverlay}));
            states.addState(new int[0], baseDrawable);
        } else {
            states.addState(new int[]{android.R.attr.state_focused}, focusOverlay);
            states.addState(new int[0], new GradientDrawable());
        }

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(focusCornerRadiusPx);
        mask.setColor(0xFFFFFFFF);

        view.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), states, mask));
    }

    private static final class BackArrowDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        BackArrowDrawable() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFFFFFFFF);
            paint.setStrokeWidth(Math.max(3f, Dim.dp(2.2f)));
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float s = Dim.dp(8);
            path.reset();
            path.moveTo(cx + s, cy);
            path.lineTo(cx - s, cy);
            path.moveTo(cx - s * 0.1f, cy - s * 0.85f);
            path.lineTo(cx - s, cy);
            path.lineTo(cx - s * 0.1f, cy + s * 0.85f);
            canvas.drawPath(path, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Deprecated
        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
