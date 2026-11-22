/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5226
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.copytranscript.TranscriptCopyFetcher;
import app.morphe.extension.youtube.patches.copytranscript.TranscriptCopyFetcher.TranscriptLine;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;

/**
 * Adds a copy button to the transcript engagement panel header.
 * One tap copies the transcript in the user's current caption language;
 * timestamps are controlled by a setting. Shorts can get the same action
 * via an optional flyout item (off by default).
 */
@SuppressWarnings("unused")
public final class CopyTranscriptPatch {

    private static final String COPY_BUTTON_TAG = "morphe_copy_transcript_button";
    private static final String TRANSCRIPT_PANEL_ID =
            "engagement-panel-timeline-view-consolidated";

    /**
     * Time for the Shorts flyout to close before copying starts.
     */
    private static final long SHORTS_MENU_DELAY_MILLISECONDS = 100;

    private static final int ID_CLOSE_BUTTON =
            ResourceUtils.getIdentifier(ResourceType.ID, "close_button");

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Called by {@link FlyoutUtils} for Shorts menus.
     */
    public static boolean showShortsFlyoutButton() {
        return Settings.COPY_TRANSCRIPT.get()
                && Settings.COPY_TRANSCRIPT_SHORTS_FLYOUT.get();
    }

    /**
     * Called by {@link FlyoutUtils} when {@link #showShortsFlyoutButton()} is true.
     */
    public static int addShortsFlyoutButton(
            Object flyoutPanel,
            int nextButtonIndex,
            String videoId
    ) {
        try {
            final String id = videoId == null ? "" : videoId;
            return FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable("morphe_copy_transcript_button"),
                    str("morphe_copy_transcript_title"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        Utils.runOnMainThreadDelayed(
                                () -> copyTranscript(id),
                                SHORTS_MENU_DELAY_MILLISECONDS
                        );
                    },
                    nextButtonIndex
            );
        } catch (Exception ex) {
            Logger.printException(() -> "addShortsFlyoutButton failure", ex);
            return nextButtonIndex;
        }
    }

    /**
     * Injection point.
     *
     * @param panel The layout of the panel, which holds its header.
     */
    public static void onPanelHeaderSetUp(View panel) {
        try {
            if (panel == null || !Settings.COPY_TRANSCRIPT.get() || ID_CLOSE_BUTTON == 0) {
                return;
            }

            // The panel is opened after its header is set up.
            panel.post(() -> {
                try {
                    updateCopyButton(panel);
                } catch (Exception ex) {
                    Logger.printException(() -> "updateCopyButton failure", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onPanelHeaderSetUp failure", ex);
        }
    }

    private static void updateCopyButton(View panel) {
        View closeButton = panel.findViewById(ID_CLOSE_BUTTON);
        if (closeButton == null || !(closeButton.getParent() instanceof ViewGroup header)) {
            Logger.printDebug(() -> "Panel close button not found");
            return;
        }

        View copyButton = header.findViewWithTag(COPY_BUTTON_TAG);
        if (copyButton == null) {
            copyButton = createCopyButton(header, closeButton);
            if (copyButton == null) {
                return;
            }
        }

        copyButton.setVisibility(isTranscriptPanel() ? View.VISIBLE : View.GONE);
    }

    private static boolean isTranscriptPanel() {
        String panelId = EngagementPanel.getLastOpenedPanelId();
        if (panelId == null || panelId.isEmpty()) {
            return false;
        }
        return TRANSCRIPT_PANEL_ID.equals(panelId) || panelId.contains("transcript");
    }

    @Nullable
    private static View createCopyButton(ViewGroup header, View closeButton) {
        Context context = header.getContext();
        Drawable icon = ResourceUtils.getDrawable("morphe_copy_transcript_button");
        if (icon == null) {
            Logger.printException(() -> "Copy transcript drawable not found");
            return null;
        }

        ViewGroup.LayoutParams params;
        if (header instanceof LinearLayout) {
            LinearLayout.LayoutParams linearParams = new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48);
            linearParams.gravity = Gravity.CENTER_VERTICAL;
            params = linearParams;
        } else if (header instanceof RelativeLayout && closeButton.getId() != View.NO_ID) {
            RelativeLayout.LayoutParams relativeParams =
                    new RelativeLayout.LayoutParams(Dim.dp48, Dim.dp48);
            relativeParams.addRule(RelativeLayout.START_OF, closeButton.getId());
            relativeParams.addRule(RelativeLayout.CENTER_VERTICAL);
            params = relativeParams;
        } else {
            Logger.printDebug(() -> "Unsupported panel header layout: " + header.getClass().getName());
            return null;
        }

        FrameLayout button = new FrameLayout(context);
        button.setId(View.generateViewId());
        button.setTag(COPY_BUTTON_TAG);
        button.setContentDescription(str("morphe_copy_transcript_title"));
        button.setClickable(true);
        button.setFocusable(true);

        ImageView iconView = new ImageView(context);
        iconView.setImageDrawable(icon.mutate());
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setTint(ThemeUtils.getAppForegroundColor());
        button.addView(iconView, new FrameLayout.LayoutParams(Dim.dp24, Dim.dp24, Gravity.CENTER));
        button.setOnClickListener(view -> copyTranscript(VideoInformation.getVideoId()));

        header.addView(button, Math.max(header.indexOfChild(closeButton), 0), params);
        return button;
    }

    private static void copyTranscript(@Nullable String videoId) {
        final String id = videoId == null || videoId.isEmpty()
                ? VideoInformation.getVideoId()
                : videoId;
        if (id == null || id.isEmpty()) {
            Utils.showSnackbarShort(str("morphe_copy_transcript_empty"));
            return;
        }

        final boolean withTimestamps = Settings.COPY_TRANSCRIPT_TIMESTAMPS.get();
        Utils.runOnBackgroundThread(() -> {
            try {
                List<TranscriptLine> lines = TranscriptCopyFetcher.fetch(id);
                if (lines.isEmpty()) {
                    Utils.runOnMainThread(() ->
                            Utils.showSnackbarShort(str("morphe_copy_transcript_empty")));
                    return;
                }

                final String text = TranscriptCopyFetcher.format(lines, withTimestamps);
                Utils.runOnMainThread(() -> copyToClipboard(text, withTimestamps));
            } catch (Exception ex) {
                Logger.printException(() -> "copyTranscript failure", ex);
                Utils.runOnMainThread(() ->
                        Utils.showSnackbarShort(str("morphe_copy_transcript_error")));
            }
        });
    }

    private static void copyToClipboard(String text, boolean withTimestamps) {
        Utils.setClipboard(text);
        // Do not show a message if using Android 13+ as it shows its own toast,
        // matching Copy video link. Always toast when timestamps are included.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 || withTimestamps) {
            Utils.showSnackbarShort(withTimestamps
                    ? str("morphe_copy_transcript_copied_timestamps")
                    : str("morphe_copy_transcript_copied"));
        }
    }
}
