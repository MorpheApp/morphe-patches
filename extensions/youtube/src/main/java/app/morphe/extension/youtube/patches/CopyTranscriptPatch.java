/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5226
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.AutoTranslateCaptionsPatch.getLanguageKey;

import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.requests.CaptionTrackRequest;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;

/**
 * Copies the transcript of a video, from a button in the transcript panel
 * and from an item in the Shorts menus.
 */
@SuppressWarnings("unused")
public final class CopyTranscriptPatch {

    /**
     * Panel with chapters and transcript.
     */
    private static final String TIMELINE_PANEL_ID = "engagement-panel-timeline-view-consolidated";

    private static final String COPY_BUTTON_TAG = "morphe_copy_transcript_button";

    private static final int ID_CLOSE_BUTTON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.ID, "close_button");

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    private static final String COPY_ICON = USE_EXPERIMENTAL_ICONS
            ? "yt_outline_experimental_copy_vd_theme_24"
            : "yt_outline_copy_black_24";

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @param panel Layout of an engagement panel, whose header is set up.
     *              Panels reuse their header, so this is called for every panel.
     */
    public static void onPanelHeaderSetUp(@Nullable View panel) {
        if (panel == null || !Settings.COPY_TRANSCRIPT.get()) return;

        // The panel is opened after its header is set up.
        panel.post(() -> {
            try {
                updateCopyButton(panel);
            } catch (Exception ex) {
                Logger.printException(() -> "updateCopyButton failure", ex);
            }
        });
    }

    private static void updateCopyButton(View panel) {
        ImageView closeButton = panel.findViewById(ID_CLOSE_BUTTON);
        if (closeButton == null) {
            Logger.printDebug(() -> "Panel has no close button");
            return;
        }

        LinearLayout header = (LinearLayout) closeButton.getParent();
        View copyButton = header.findViewWithTag(COPY_BUTTON_TAG);
        if (copyButton == null) {
            copyButton = createCopyButton(header, closeButton);
        }
        copyButton.setVisibility(isTranscriptPanel(EngagementPanel.getLastOpenedPanelId())
                ? View.VISIBLE
                : View.GONE);
    }

    private static boolean isTranscriptPanel(@Nullable String panelId) {
        // Such as PAmodern_transcript_view.
        return panelId != null && (panelId.contains("transcript") || panelId.equals(TIMELINE_PANEL_ID));
    }

    /**
     * @return A button that looks like the close button, added before it.
     */
    private static View createCopyButton(LinearLayout header, ImageView closeButton) {
        ImageView button = new ImageView(header.getContext());
        button.setTag(COPY_BUTTON_TAG);
        button.setContentDescription(str("morphe_copy_transcript_title"));
        button.setImageDrawable(ResourceUtils.getDrawable(COPY_ICON));
        button.setImageTintList(closeButton.getImageTintList());
        button.setScaleType(closeButton.getScaleType());
        button.setPadding(closeButton.getPaddingLeft(), closeButton.getPaddingTop(),
                closeButton.getPaddingRight(), closeButton.getPaddingBottom());
        Drawable background = closeButton.getBackground();
        if (background != null && background.getConstantState() != null) {
            button.setBackground(background.getConstantState().newDrawable().mutate());
        }
        button.setOnClickListener(view -> copyTranscript(VideoInformation.getVideoId()));

        header.addView(button, header.indexOfChild(closeButton),
                new LinearLayout.LayoutParams((LinearLayout.LayoutParams) closeButton.getLayoutParams()));
        return button;
    }

    /**
     * Called by {@link FlyoutUtils} for the menus of Shorts.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, String videoId) {
        if (!Settings.COPY_TRANSCRIPT.get() || !Settings.COPY_TRANSCRIPT_SHORTS_FLYOUT.get()
                || videoId.isEmpty()) {
            return nextButtonIndex;
        }

        return FlyoutUtils.addFlyoutButton(
                flyoutPanel,
                ResourceUtils.getDrawable(COPY_ICON),
                str("morphe_copy_transcript_title"),
                v -> {
                    FlyoutUtils.dismissFlyout();
                    copyTranscript(videoId);
                },
                nextButtonIndex
        );
    }

    private static void copyTranscript(String videoId) {
        final boolean withTimestamps = Settings.COPY_TRANSCRIPT_TIMESTAMPS.get();
        final String language = transcriptLanguage();

        Utils.runOnBackgroundThread(() -> {
            try {
                String trackUrl = selectTrackUrl(CaptionTrackRequest.fetchCaptions(videoId), language);
                List<CaptionTrackRequest.Line> lines = trackUrl == null
                        ? List.of()
                        : CaptionTrackRequest.fetchLines(trackUrl);
                String text = format(lines, withTimestamps);

                Utils.runOnMainThread(() -> {
                    if (text.isEmpty()) {
                        Utils.showSnackbarShort(str("morphe_copy_transcript_empty"));
                        return;
                    }

                    Utils.setClipboard(text);
                    // Android 13+ shows its own message.
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        Utils.showSnackbarShort(str("morphe_copy_transcript_copied"));
                    }
                });
            } catch (Exception ex) {
                Logger.printException(() -> "copyTranscript failure", ex);
                Utils.runOnMainThread(() -> Utils.showSnackbarShort(str("morphe_copy_transcript_error")));
            }
        });
    }

    /**
     * @return Language of the captions shown in the player, otherwise the preferred caption language,
     *         or null to use the default caption track.
     */
    @Nullable
    private static String transcriptLanguage() {
        String selected = PreferredCaptionLanguagePatch.getSelectedLanguage();
        if (selected != null) return selected;

        String preferred = Settings.PREFERRED_CAPTION_LANGUAGE.get();
        if ("off".equalsIgnoreCase(preferred)) return null;
        return "default".equalsIgnoreCase(preferred)
                ? Requester.getAppLocale().toLanguageTag()
                : preferred;
    }

    /**
     * @return Url of the uploaded or auto-generated track of the language, or a translation of
     *         the default track to the language, or the default track.
     */
    @Nullable
    private static String selectTrackUrl(CaptionTrackRequest.Captions captions, @Nullable String language) {
        List<CaptionTrackRequest.Track> tracks = captions.tracks();
        if (tracks.isEmpty()) return null;

        CaptionTrackRequest.Track defaultTrack = tracks.get(Math.max(captions.defaultIndex(), 0));
        if (language == null) return defaultTrack.url();

        String key = getLanguageKey(language);
        CaptionTrackRequest.Track automatic = null;
        for (CaptionTrackRequest.Track track : tracks) {
            if (!key.equals(getLanguageKey(track.languageCode()))) continue;
            if (!track.automatic()) return track.url();
            if (automatic == null || (automatic.gemini() && !track.gemini())) automatic = track;
        }
        if (automatic != null) return automatic.url();

        String translation = AutoTranslateCaptionsPatch.translationLanguage(language);
        return translation != null && defaultTrack.translatable()
                ? defaultTrack.url() + "&tlang=" + translation
                : defaultTrack.url();
    }

    private static String format(List<CaptionTrackRequest.Line> lines, boolean withTimestamps) {
        StringBuilder builder = new StringBuilder();
        for (CaptionTrackRequest.Line line : lines) {
            String text = line.text();
            // Sound descriptions, such as [Music].
            if ((text.startsWith("[") && text.endsWith("]"))
                    || (text.startsWith("(") && text.endsWith(")"))) continue;

            if (builder.length() > 0) builder.append('\n');
            if (withTimestamps) builder.append(formatTimestamp(line.startMs())).append(' ');
            builder.append(text);
        }
        return builder.toString();
    }

    private static String formatTimestamp(long timeMs) {
        final long totalSeconds = timeMs / 1000;
        final long hours = totalSeconds / 3600;
        final long minutes = (totalSeconds % 3600) / 60;
        final long seconds = totalSeconds % 60;
        return hours > 0
                ? String.format(Locale.US, "[%d:%02d:%02d]", hours, minutes, seconds)
                : String.format(Locale.US, "[%02d:%02d]", minutes, seconds);
    }
}
