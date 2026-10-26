package app.morphe.extension.youtube.niconicocomments;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import app.morphe.extension.youtube.videoplayer.PlayerOverlayButton;

import java.lang.ref.WeakReference;
import java.util.Locale;

/** Adds a persistent on/off control beside Morphe's lower-right player buttons. */
public final class NiconicoCommentsToggleButton {
    private static final String LOG_TAG = "NiconicoComments";
    private static final int ENABLED_COLOR = 0xffffffff;
    private static final int DISABLED_COLOR = 0x66ffffff;

    private static WeakReference<View> sourceButtonRef = new WeakReference<>(null);
    private static WeakReference<TextView> buttonRef = new WeakReference<>(null);

    private NiconicoCommentsToggleButton() {
    }

    /** Injection point called with YouTube's fullscreen button as the layout/style anchor. */
    public static void initializeButton(View sourceButton) {
        if (sourceButton == null) {
            return;
        }
        try {
            TextView existing = buttonRef.get();
            if (sourceButtonRef.get() == sourceButton
                    && existing != null
                    && existing.isAttachedToWindow()) {
                updateState(existing);
                return;
            }

            NiconicoCommentsController.loadPreference(sourceButton.getContext());
            View.OnClickListener clickListener = view -> {
                NiconicoCommentsController.toggleCommentsEnabled(view.getContext());
                updateState((TextView) view);
            };
            View.OnLongClickListener longClickListener = view -> {
                showSettingsDialog(view);
                return true;
            };

            TextView button = PlayerOverlayButton.addButtonWithTextOverlay(
                    sourceButton,
                    clickListener,
                    longClickListener
            );
            if (button == null) {
                return;
            }

            configureButton(button);
            sourceButtonRef = new WeakReference<>(sourceButton);
            buttonRef = new WeakReference<>(button);
        } catch (Exception exception) {
            Log.e(LOG_TAG, "Toggle button initialization failed", exception);
        }
    }

    private static void configureButton(TextView button) {
        button.setText("弾");
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        updateState(button);
    }

    @SuppressLint("SetTextI18n")
    private static void showSettingsDialog(View anchor) {
        Context context = anchor.getContext();
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        QuickSettingsStrings strings = QuickSettingsStrings.forLocale(locale);
        int currentSize = NiconicoCommentsController.getCommentFontSizeSp(context);
        int currentOpacity = NiconicoCommentsController.getCommentOpacityPercent(context);
        int[] selectedSize = {currentSize};
        int[] selectedOpacity = {currentOpacity};
        int densityPadding = Math.round(24 * context.getResources().getDisplayMetrics().density);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(densityPadding, densityPadding / 2, densityPadding, 0);

        TextView value = new TextView(context);
        value.setTextSize(16);
        value.setText(strings.formatFontSize(currentSize));
        content.addView(value);

        SeekBar slider = new SeekBar(context);
        slider.setMax(
                NiconicoCommentsController.MAX_FONT_SIZE_SP
                        - NiconicoCommentsController.MIN_FONT_SIZE_SP
        );
        slider.setProgress(currentSize - NiconicoCommentsController.MIN_FONT_SIZE_SP);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedSize[0] = NiconicoCommentsController.MIN_FONT_SIZE_SP + progress;
                value.setText(strings.formatFontSize(selectedSize[0]));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        content.addView(slider);

        TextView opacityValue = new TextView(context);
        opacityValue.setTextSize(16);
        opacityValue.setText(strings.formatOpacity(currentOpacity));
        content.addView(opacityValue);

        SeekBar opacitySlider = new SeekBar(context);
        opacitySlider.setMax(
                NiconicoCommentsController.MAX_OPACITY_PERCENT
                        - NiconicoCommentsController.MIN_OPACITY_PERCENT
        );
        opacitySlider.setProgress(
                currentOpacity - NiconicoCommentsController.MIN_OPACITY_PERCENT
        );
        opacitySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedOpacity[0] = NiconicoCommentsController.MIN_OPACITY_PERCENT + progress;
                opacityValue.setText(strings.formatOpacity(selectedOpacity[0]));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        content.addView(opacitySlider);

        new AlertDialog.Builder(context)
                .setTitle(strings.title)
                .setView(content)
                .setNegativeButton(strings.cancel, null)
                .setPositiveButton(
                        strings.save,
                        (dialog, which) -> {
                            NiconicoCommentsController.setCommentFontSizeSp(
                                    context,
                                    selectedSize[0]
                            );
                            NiconicoCommentsController.setCommentOpacityPercent(
                                    context,
                                    selectedOpacity[0]
                            );
                        }
                )
                .show();
    }

    private static void updateState(TextView button) {
        boolean enabled = NiconicoCommentsController.isCommentsEnabled();
        button.setTextColor(enabled ? ENABLED_COLOR : DISABLED_COLOR);
        String state = enabled ? "on" : "off";
        button.setContentDescription("Niconico comments " + state);
        button.setTooltipText("Niconico comments: " + state);
    }
}
