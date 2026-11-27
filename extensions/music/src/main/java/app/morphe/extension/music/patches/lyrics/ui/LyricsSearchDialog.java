/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.derive.QuickLRCClient;
import app.morphe.extension.music.patches.lyrics.derive.SectionDetector;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.shared.ui.SheetBottomDialog.SlideDialog;

/**
 * The dialog opened by long-pressing the refresh button. It edits the search terms and can
 * either hand them to the enabled providers or open them as a Google web search, so it lives on
 * its own rather than as another block inside the panel view.
 */
final class LyricsSearchDialog {

    private static final int SLIDE_ANIMATION_MILLIS = 180;

    /**
     * The panel operations the dialog reaches back for, since the transcribe and section buttons
     * act on the panel's current lyrics and transcription state rather than on the dialog itself.
     */
    interface Callbacks {
        Lyrics getCurrentLyrics();

        TrackInfo getCurrentTrack();

        Lyrics getPendingAlignment();

        void applyPendingAlignment();

        void cancelTranscription(boolean announce);

        void startTranscription(@Nullable String lyricsText);

        void setDialogAlignButton(TextView button);

        void setDialogSectionButton(TextView button);

        void updateSectionButton();

        void onSearchDialogDismiss();

        void styleButton(TextView button);

        void toastError(String message);

        void onConfirmSearch(String title, String artist, boolean defaultTerms);
    }

    private LyricsSearchDialog() {
    }

    @SuppressWarnings("deprecation")
    static void show(Context context, LyricsManager manager, Callbacks callbacks) {
        TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            return;
        }
        if (!manager.hasSearchProviders()) {
            callbacks.toastError(str("morphe_music_lyrics_search_no_providers"));
            return;
        }
        if (context == null) {
            return;
        }

        AutoCompleteTextView titleInput = LyricsSearchUI.createSearchInput(context,
                str("morphe_music_lyrics_search_title_hint"));
        AutoCompleteTextView artistInput = LyricsSearchUI.createSearchInput(context,
                str("morphe_music_lyrics_search_artist_hint"));

        String rawTitle = manager.getCurrentRawTitle();
        String rawArtist = manager.getCurrentRawArtist();
        String defaultTitle = rawTitle != null && !rawTitle.trim().isEmpty()
                ? rawTitle.trim() : track.title();
        String defaultArtist = rawArtist != null && !rawArtist.trim().isEmpty()
                ? rawArtist.trim() : track.artist();
        String[] remembered = manager.rememberedSearchTerms();
        String initialTitle = remembered != null && remembered[0] != null
                ? remembered[0] : defaultTitle;
        String initialArtist = remembered != null && remembered[1] != null
                ? remembered[1] : defaultArtist;
        titleInput.setText(initialTitle);
        titleInput.setSelection(initialTitle.length());
        artistInput.setText(initialArtist);
        artistInput.setSelection(initialArtist.length());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(titleInput, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        artistParams.topMargin = Dim.dp8;
        content.addView(artistInput, artistParams);

        // Opens the terms as a Google web search, so the user can hunt for lyrics no provider found.
        TextView googleButton = new TextView(context);
        callbacks.styleButton(googleButton);
        LinearLayout.LayoutParams googleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        googleParams.topMargin = Dim.dp16;
        content.addView(googleButton, googleParams);

        googleButton.setText(str("morphe_music_lyrics_search_google"));
        googleButton.setOnClickListener(view -> {
            String title = titleInput.getText().toString().trim();
            String artist = artistInput.getText().toString().trim();
            if (title.isEmpty() || artist.isEmpty()) {
                Utils.showToastShort(str("morphe_music_lyrics_search_empty"));
                return;
            }
            try {
                String url = "https://www.google.com/search?q="
                        + Uri.encode(title + " by " + artist + " lyrics");
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Google search failure", ex);
            }
        });

        final SlideDialog[] dialogRef = new SlideDialog[1];

        // Sits with the terms rather than in the button row, which this dialog leaves empty: it
        // reuses the same two fields rather than searching on its own. Read at open time, so the
        // button follows the key on disk rather than whatever was there when the class loaded.
        // Transcribe needs a video id to align against.
        if (QuickLRCClient.isConfigured() && !VideoInformation.getVideoId().isEmpty()) {
            TextView transcribeButton = new TextView(context);
            callbacks.styleButton(transcribeButton);
            LinearLayout.LayoutParams transcribeParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            transcribeParams.topMargin = Dim.dp16;
            content.addView(transcribeButton, transcribeParams);

            Lyrics pendingAlignment = callbacks.getPendingAlignment();
            transcribeButton.setText(pendingAlignment != null
                    ? str("morphe_music_lyrics_transcribe_apply_align")
                    : QuickLRCClient.isRunning()
                      ? str("morphe_music_lyrics_transcribe_aligning")
                      : str("morphe_music_lyrics_transcribe_align"));
            callbacks.setDialogAlignButton(transcribeButton);

            transcribeButton.setOnClickListener(view -> {
                if (pendingAlignment != null) {
                    callbacks.applyPendingAlignment();
                    if (dialogRef[0] != null) dialogRef[0].dismissImmediately();
                    return;
                }
                if (QuickLRCClient.isRunning()) {
                    callbacks.cancelTranscription(true);
                    return;
                }
                Lyrics current = callbacks.getCurrentLyrics();
                String text = null;
                if (current != null && !current.isEmpty() && current != Lyrics.NOT_FOUND) {
                    StringBuilder joined = createJoined(current, callbacks.getCurrentTrack());
                    text = joined.toString();
                }
                if (text == null || text.trim().isEmpty()) {
                    Utils.showToastShort(str("morphe_music_lyrics_transcribe_no_text"));
                    text = null;
                }
                callbacks.startTranscription(text);
            });
        }

        // Lets the user detect sections when automatic detection missed them or was off when the track loaded.
        if (Settings.LYRICS_USE_AI_SECTION.get()
                && SectionDetector.needsDetection(callbacks.getCurrentLyrics())) {
            TextView sectionButton = new TextView(context);
            callbacks.styleButton(sectionButton);
            LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            sectionParams.topMargin = Dim.dp16;
            content.addView(sectionButton, sectionParams);

            callbacks.setDialogSectionButton(sectionButton);
            callbacks.updateSectionButton();

            sectionButton.setOnClickListener(view -> {
                if (manager.hasPendingSections()) {
                    manager.applyPendingSections();
                    if (dialogRef[0] != null) dialogRef[0].dismissImmediately();
                } else {
                    Lyrics current = callbacks.getCurrentLyrics();
                    if (current == null || current.isEmpty() || current == Lyrics.NOT_FOUND) {
                        Utils.showToastShort(str("morphe_music_lyrics_ai_section_no_text"));
                    } else {
                        manager.requestSectionDetection();
                    }
                }
            });
        }

        LinearLayout sheet = SheetBottomDialog.createMainLayout(
                context, LyricsSheetChrome.BACKGROUND_COLOR);
        SlideDialog dialog = SheetBottomDialog.createSlideDialog(context, sheet, SLIDE_ANIMATION_MILLIS);
        dialogRef[0] = dialog;
        dialog.setOnDismissListener(d -> {
            dialogRef[0] = null;
            callbacks.onSearchDialogDismiss();
        });

        LinearLayout sheetRoot = new LinearLayout(context);
        sheetRoot.setOrientation(LinearLayout.VERTICAL);
        sheetRoot.addView(LyricsSheetChrome.createTopBar(context,
                str("morphe_music_lyrics_search_dialog_title"), dialog::dismissImmediately),
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = SheetBottomDialog.createCappedScrollView(context, 70);
        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout contentHost = LyricsSheetChrome.createContentHost(context);
        contentHost.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        // Weighted so the content shrinks before the dock is pushed off a short screen.
        sheetRoot.addView(contentHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button okButton = CustomDialog.createButton(context, dialog,
                ResourceUtils.getSystemStringByLocale("ok",
                        BaseSettings.MORPHE_LANGUAGE.get().getLocale()),
                () -> {
                    String title = titleInput.getText().toString().trim();
                    String artist = artistInput.getText().toString().trim();
                    if (title.isEmpty() || artist.isEmpty()) {
                        Utils.showToastShort(str("morphe_music_lyrics_search_empty"));
                        return;
                    }
                    boolean defaultTerms = title.equals(defaultTitle)
                            && artist.equals(defaultArtist);
                    callbacks.onConfirmSearch(title, artist, defaultTerms);
                }, true, true);
        LyricsSheetChrome.stylePrimaryButton(okButton);
        LinearLayout dock = LyricsSheetChrome.createBottomDock(context);
        dock.addView(okButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp(36)));
        sheetRoot.addView(dock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        sheet.addView(sheetRoot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        titleInput.requestFocus();

        // One round per dialog, fetched once it is on screen so the first keystroke never
        // waits for MusicBrainz.
        String titleSuggestion = titleInput.getText().toString();
        String artistSuggestion = artistInput.getText().toString();
        Utils.runOnBackgroundThread(() -> {
            List<String> titles = LyricsRequests.MusicBrainzClient.suggestTitles(titleSuggestion);
            List<String> artists = LyricsRequests.MusicBrainzClient.suggestArtists(artistSuggestion);
            Utils.runOnMainThread(() -> {
                if (titles.isEmpty() && artists.isEmpty()) {
                    return;
                }
                if (dialogRef[0] != dialog || !dialog.isShowing()) {
                    return;
                }
                if (!titles.isEmpty()) {
                    LyricsSearchUI.setSuggestionAdapter(context, titleInput, titles);
                }
                if (!artists.isEmpty()) {
                    LyricsSearchUI.setSuggestionAdapter(context, artistInput, artists);
                }
            });
        });
    }

    private static StringBuilder createJoined(Lyrics current, TrackInfo track) {
        StringBuilder joined = new StringBuilder();
        for (String line : LyricsTextUtils.visibleLines(current, track)) {
            joined.append(line).append('\n');
        }
        return joined;
    }
}
