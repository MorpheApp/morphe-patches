/*
 * Portions Copyright (C) 2023-2024 Anil Beesetti (Next Player)
 * https://github.com/anilbeesetti/nextplayer
 * Licensed under the GNU General Public License v3.0 (GPLv3).
 *
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.accessibility.CaptioningManager;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.CaptionStylePatch;
import app.morphe.extension.youtube.patches.CaptionViewportPatch;

/**
 * Shows a sample subtitle that follows the caption style settings.
 * <p>
 * Displays live updates for System default, Next Player appearance
 * (font, bold, background, font scale), or the Reference preset.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class CaptionStylePreviewPreference extends Preference {

    private static final float BASE_TEXT_SIZE_SP = 18f;

    private TextView captionView;
    private FrameLayout windowView;

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (sharedPreferences, key) -> Utils.runOnMainThread(this::updateUI);

    public CaptionStylePreviewPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public CaptionStylePreviewPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public CaptionStylePreviewPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptionStylePreviewPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setSelectable(false);
        setPersistent(false);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
        updateUI();
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    @SuppressLint("MissingSuperCall")
    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();

        // Dark backdrop, similar to video content.
        FrameLayout backdrop = new FrameLayout(context);
        GradientDrawable backdropDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3A4A5C, 0xFF1B222B});
        backdropDrawable.setCornerRadius(Dim.dp8);
        backdrop.setBackground(backdropDrawable);
        backdrop.setPadding(Dim.dp16, Dim.dp16, Dim.dp16, Dim.dp16);

        windowView = new FrameLayout(context);
        windowView.setPadding(Dim.dp8, Dim.dp4, Dim.dp8, Dim.dp4);

        captionView = new TextView(context);
        captionView.setText(str("morphe_caption_style_preview_text"));
        captionView.setGravity(Gravity.CENTER);
        captionView.setPadding(Dim.dp4, Dim.dp2, Dim.dp4, Dim.dp2);
        windowView.addView(captionView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams windowParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        backdrop.addView(windowView, windowParams);

        FrameLayout container = new FrameLayout(context);
        container.setPadding(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp8);
        container.addView(backdrop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, Dim.dp(110)));

        updateUI();
        return container;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        updateUI();
    }

    private void updateUI() {
        try {
            if (captionView == null || windowView == null) return;

            CaptioningManager manager = (CaptioningManager) getContext()
                    .getSystemService(Context.CAPTIONING_SERVICE);
            CaptioningManager.CaptionStyle systemStyle = manager == null ? null : manager.getUserStyle();
            int textColor = CaptionStylePatch.getForegroundColor(systemStyle == null
                    ? Color.WHITE : systemStyle.foregroundColor);
            int backgroundColor = CaptionStylePatch.getBackgroundColor(systemStyle == null
                    ? Color.BLACK : systemStyle.backgroundColor);
            int windowColor = CaptionStylePatch.getWindowColor(systemStyle == null
                    ? Color.TRANSPARENT : systemStyle.windowColor);
            Typeface typeface = CaptionStylePatch.getTypeface(systemStyle == null
                    ? Typeface.DEFAULT : systemStyle.getTypeface());
            float fontScale = manager == null ? 1f : manager.getFontScale();
            boolean hasDropShadow = CaptionStylePatch.getEdgeType(systemStyle == null
                    ? CaptioningManager.CaptionStyle.EDGE_TYPE_NONE : systemStyle.edgeType)
                    == CaptioningManager.CaptionStyle.EDGE_TYPE_DROP_SHADOW;
            int edgeColor = CaptionStylePatch.getEdgeColor(systemStyle == null
                    ? Color.BLACK : systemStyle.edgeColor);

            captionView.setTextColor(textColor);
            captionView.setBackgroundColor(backgroundColor);
            windowView.setBackgroundColor(windowColor);

            captionView.setTextSize(TypedValue.COMPLEX_UNIT_SP,
                    CaptionViewportPatch.scaleFontSize(BASE_TEXT_SIZE_SP * fontScale));
            captionView.setTypeface(typeface);

            if (hasDropShadow) {
                captionView.setShadowLayer(3f, 3f, 3f, edgeColor);
            } else {
                captionView.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateUI failure", ex);
        }
    }
}
