/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.dearrow.DeArrowVoteRequest.Branding;
import app.morphe.extension.youtube.patches.dearrow.DeArrowVoteRequest.ThumbnailSubmission;
import app.morphe.extension.youtube.patches.dearrow.DeArrowVoteRequest.TitleSubmission;
import app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch;
import app.morphe.extension.youtube.shared.PipDismissHelper;
import app.morphe.extension.youtube.shared.VideoState;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

/**
 * Sheet of the DeArrow titles and thumbnails of the video in the player, to vote on them
 * or submit a new title or the current frame as a thumbnail.
 * <p>
 * The original title and thumbnail are listed first, as done by the DeArrow extension.
 * The original title is the title as set by the uploader, since YouTube can show a translated title instead.
 */
final class DeArrowVoteDialog {

    private static final int UPVOTE_ICON = getVoteIcon("thumb_up");
    private static final int DOWNVOTE_ICON = getVoteIcon("thumb_down");

    /**
     * Opacity of the line under each title, which shows its score.
     */
    private static final float DETAILS_ALPHA = 0.7f;

    /**
     * Lines shown of a new title, before the text field scrolls.
     */
    private static final int NEW_TITLE_MAX_LINES = 4;

    /**
     * Share of the screen height of the list. Less than the default, since the sheet
     * also has a title and buttons.
     */
    private static final int LIST_MAX_HEIGHT_PERCENT = 55;

    private static final int THUMBNAIL_WIDTH = Dim.dp(112);
    private static final int THUMBNAIL_HEIGHT = Dim.dp(63);

    private static final int THUMBNAIL_TIMEOUT_MILLISECONDS = 10_000;

    /**
     * Score of a new title of a user who is not yet verified, which is shown as unverified.
     */
    private static final int UNVERIFIED_TITLE_SCORE = -1;

    /**
     * Titles with this score or lower are not shown, except the original title.
     */
    private static final int HIDDEN_TITLE_MAX_SCORE = -2;

    /**
     * A title listed in the sheet.
     *
     * @param title     The title as submitted, or the original title if no one submitted it.
     * @param original  If the title is the original title.
     * @param submitted If the title was submitted, so it can be downvoted.
     * @param score     The score of the title, if submitted.
     * @param locked    If a VIP locked the title.
     */
    private record ListedTitle(String title, boolean original, boolean submitted, int score, boolean locked) {
        String shownTitle() {
            return DeArrowBrandingRequest.formatTitle(title);
        }
    }

    /**
     * A thumbnail listed in the sheet.
     *
     * @param timestamp Time of the frame in seconds, as submitted. Not used for the original thumbnail.
     * @param original  If the thumbnail is the original thumbnail.
     * @param submitted If the thumbnail was submitted, so it can be downvoted.
     * @param score     The score of the thumbnail, if submitted.
     * @param locked    If a VIP locked the thumbnail.
     */
    private record ListedThumbnail(double timestamp, boolean original, boolean submitted, int score, boolean locked) {
    }

    private DeArrowVoteDialog() {
    }

    static void show(Context context) {
        Utils.verifyOnMainThread();

        String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty()) {
            return;
        }
        final long videoLength = VideoInformation.getVideoLength();
        final int foregroundColor = ThemeUtils.getAppForegroundColor();

        SheetBottomDialog.DraggableLinearLayout mainLayout = SheetBottomDialog.createMainLayout(
                context, LegacyPlayerControlButton.getDialogBackgroundColor());

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
        contentLayout.addView(SheetBottomDialog.createTitle(context,
                str("morphe_dearrow_vote_title"), foregroundColor));

        LinearLayout listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.addView(createMessage(context, str("morphe_dearrow_vote_loading"), foregroundColor));

        // Capped so the buttons below the list stay on screen, including in landscape.
        ScrollView scrollView = SheetBottomDialog.createCappedScrollView(context, LIST_MAX_HEIGHT_PERCENT);
        scrollView.addView(listLayout);
        contentLayout.addView(scrollView);
        mainLayout.addView(contentLayout);

        SheetBottomDialog.SlideDialog dialog = SheetBottomDialog.createSlideDialog(
                context, mainLayout, LegacyPlayerControlButton.fadeInDuration);
        PipDismissHelper.dismissOnPip(dialog);

        // The original title is filled in when fetched.
        final String[] originalTitle = {null};
        Button newTitleButton = CustomDialog.createButton(context, null,
                str("morphe_dearrow_vote_new_title"),
                () -> showNewTitleDialog(context, dialog, videoId, originalTitle[0], videoLength),
                true, false);
        Button currentFrameButton = CustomDialog.createButton(context, null,
                str("morphe_dearrow_vote_current_frame"),
                () -> showCurrentFrameDialog(context, dialog, videoId, videoLength),
                false, false);

        LinearLayout buttonsLayout = new LinearLayout(context);
        buttonsLayout.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams newTitleParams = new LinearLayout.LayoutParams(0, Dim.dp36, 1.0f);
        newTitleParams.setMarginEnd(Dim.dp8);
        buttonsLayout.addView(newTitleButton, newTitleParams);
        buttonsLayout.addView(currentFrameButton, new LinearLayout.LayoutParams(0, Dim.dp36, 1.0f));

        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.setMargins(0, Dim.dp16, 0, 0);
        contentLayout.addView(buttonsLayout, buttonsParams);

        dialog.show();

        Utils.runOnBackgroundThread(() -> {
            Branding branding;
            try {
                branding = DeArrowVoteRequest.fetchBranding(videoId);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not fetch DeArrow submissions of: " + videoId, ex);
                Utils.runOnMainThread(() -> {
                    listLayout.removeAllViews();
                    listLayout.addView(createMessage(context,
                            str("morphe_dearrow_vote_fetch_failed"), foregroundColor));
                });
                return;
            }

            String original = null;
            try {
                original = RestoreOriginalTitlesPatch.fetchOriginalTitle(videoId).join();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not fetch original title of: " + videoId, ex);
            }

            List<ListedTitle> titles = listTitles(branding.titles(), original);
            List<ListedThumbnail> thumbnails = listThumbnails(branding.thumbnails());
            String fetchedOriginal = original;
            Utils.runOnMainThread(() -> {
                originalTitle[0] = fetchedOriginal;
                listLayout.removeAllViews();

                listLayout.addView(createSectionHeader(context, str("morphe_dearrow_vote_titles"), foregroundColor));
                if (titles.isEmpty()) {
                    listLayout.addView(createMessage(context,
                            str("morphe_dearrow_vote_no_titles"), foregroundColor));
                }
                for (ListedTitle title : titles) {
                    listLayout.addView(createTitleRow(context, dialog, videoId, title,
                            videoLength, foregroundColor));
                }

                listLayout.addView(createSectionHeader(context, str("morphe_dearrow_vote_thumbnails"), foregroundColor));
                for (ListedThumbnail thumbnail : thumbnails) {
                    listLayout.addView(createThumbnailRow(context, dialog, videoId, thumbnail,
                            videoLength, foregroundColor));
                }
            });
        });
    }

    /**
     * @param originalTitle The original title, or null if it failed to fetch.
     * @return The original title first, then the submitted titles in the order of the server.
     *         Titles that were downvoted below the unverified score are left out.
     */
    private static List<ListedTitle> listTitles(List<TitleSubmission> submissions, @Nullable String originalTitle) {
        List<ListedTitle> titles = new ArrayList<>(submissions.size() + 1);
        ListedTitle original = null;
        for (TitleSubmission submission : submissions) {
            ListedTitle title = new ListedTitle(submission.title(), submission.original(),
                    true, submission.score(), submission.locked());
            if (submission.original() && original == null) {
                original = title;
            } else if (submission.original() || submission.locked()
                    || submission.score() > HIDDEN_TITLE_MAX_SCORE) {
                titles.add(title);
            }
        }
        if (original == null && originalTitle != null) {
            original = new ListedTitle(originalTitle, true, false, 0, false);
        }
        if (original != null) {
            titles.add(0, original);
        }
        return titles;
    }

    /**
     * @return The original thumbnail first, then the submitted thumbnails in the order of the server.
     */
    private static List<ListedThumbnail> listThumbnails(List<ThumbnailSubmission> submissions) {
        List<ListedThumbnail> thumbnails = new ArrayList<>(submissions.size() + 1);
        ListedThumbnail original = null;
        for (ThumbnailSubmission submission : submissions) {
            ListedThumbnail thumbnail = new ListedThumbnail(submission.timestamp(), submission.original(),
                    true, submission.score(), submission.locked());
            if (submission.original() && original == null) {
                original = thumbnail;
            } else {
                thumbnails.add(thumbnail);
            }
        }
        // The original thumbnail can always be voted for.
        if (original == null) {
            original = new ListedThumbnail(0, true, false, 0, false);
        }
        thumbnails.add(0, original);
        return thumbnails;
    }

    private static TextView createSectionHeader(Context context, String text, int foregroundColor) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(foregroundColor);
        view.setAlpha(DETAILS_ALPHA);
        view.setPadding(0, Dim.dp16, 0, Dim.dp4);
        return view;
    }

    private static TextView createMessage(Context context, String message, int foregroundColor) {
        TextView view = new TextView(context);
        view.setText(message);
        view.setTextSize(14);
        view.setTextColor(foregroundColor);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, Dim.dp16, 0, Dim.dp16);
        return view;
    }

    private static View createTitleRow(Context context, SheetBottomDialog.SlideDialog dialog, String videoId,
                                       ListedTitle title, long videoLength, int foregroundColor) {
        List<String> details = new ArrayList<>(3);
        if (title.original()) {
            details.add(str("morphe_dearrow_vote_original_title"));
        }
        if (title.submitted() && title.score() == UNVERIFIED_TITLE_SCORE) {
            // Not a downvote, so the score is not shown.
            details.add(str("morphe_dearrow_vote_unverified"));
            addVoteDetails(details, false, 0, title.locked());
        } else {
            addVoteDetails(details, title.submitted(), title.score(), title.locked());
        }

        return createRow(context, null, title.shownTitle(), details, title.submitted(), foregroundColor,
                downvote -> vote(context, dialog, videoId, downvote, () -> DeArrowVoteRequest.voteTitle(
                        videoId, title.title(), title.original(), downvote, videoLength)));
    }

    private static View createThumbnailRow(Context context, SheetBottomDialog.SlideDialog dialog, String videoId,
                                           ListedThumbnail thumbnail, long videoLength, int foregroundColor) {
        ImageView imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        // Shown until the image loads, and if the thumbnail cache has no image of the frame yet.
        imageView.setBackgroundColor(foregroundColor);
        imageView.getBackground().setAlpha(0x20);
        imageView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        String imageUrl = thumbnail.original()
                ? "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg"
                : DeArrowPatch.getSubmittedThumbnailURL(videoId, thumbnail.timestamp());
        loadImage(imageView, imageUrl);

        String text = thumbnail.original()
                ? str("morphe_dearrow_vote_original_thumbnail")
                : str("morphe_dearrow_vote_thumbnail_time", formatTime(thumbnail.timestamp()));
        List<String> details = new ArrayList<>(2);
        addVoteDetails(details, thumbnail.submitted(), thumbnail.score(), thumbnail.locked());

        return createRow(context, imageView, text, details, thumbnail.submitted(), foregroundColor,
                downvote -> vote(context, dialog, videoId, downvote, () -> DeArrowVoteRequest.voteThumbnail(
                        videoId, thumbnail.original(), thumbnail.timestamp(), downvote, videoLength)));
    }

    private static void addVoteDetails(List<String> details, boolean submitted, int score, boolean locked) {
        if (submitted) {
            details.add(str("morphe_dearrow_vote_score", score));
        }
        if (locked) {
            details.add(str("morphe_dearrow_vote_locked"));
        }
    }

    private interface VoteAction {
        void vote(boolean downvote);
    }

    /**
     * @param image       Image shown at the start of the row, or null for none.
     * @param details     Shown under the text.
     * @param canDownvote If the row has a downvote button, which only submitted titles and thumbnails have.
     */
    private static View createRow(Context context, @Nullable View image, String text, List<String> details,
                                  boolean canDownvote, int foregroundColor, VoteAction voteAction) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Dim.dp8, 0, Dim.dp8);

        if (image != null) {
            LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
            imageParams.setMarginEnd(Dim.dp12);
            row.addView(image, imageParams);
        }

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        row.addView(textLayout, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView textView = new TextView(context);
        textView.setText(text);
        textView.setTextSize(16);
        textView.setTextColor(foregroundColor);
        textLayout.addView(textView);

        if (!details.isEmpty()) {
            TextView detailsView = new TextView(context);
            detailsView.setText(TextUtils.join(" · ", details));
            detailsView.setTextSize(13);
            detailsView.setTextColor(foregroundColor);
            detailsView.setAlpha(DETAILS_ALPHA);
            textLayout.addView(detailsView);
        }

        row.addView(createVoteButton(context, UPVOTE_ICON, str("morphe_dearrow_vote_upvote"), foregroundColor,
                () -> voteAction.vote(false)));
        if (canDownvote) {
            row.addView(createVoteButton(context, DOWNVOTE_ICON, str("morphe_dearrow_vote_downvote"), foregroundColor,
                    () -> voteAction.vote(true)));
        }
        return row;
    }

    /**
     * Loads the image on a background thread. Nothing is shown if the server has no image.
     */
    private static void loadImage(ImageView imageView, String url) {
        Utils.runOnBackgroundThread(() -> {
            try {
                HttpURLConnection connection = Requester.openConnection(url);
                connection.setConnectTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
                final int responseCode = connection.getResponseCode();
                if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                    // The thumbnail cache has not yet generated the image of the frame.
                    Logger.printDebug(() -> "No thumbnail image (status code: " + responseCode + "): " + url);
                    connection.disconnect();
                    return;
                }
                Bitmap bitmap;
                try (InputStream input = connection.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(input);
                }
                connection.disconnect();
                if (bitmap == null) {
                    Logger.printDebug(() -> "Could not decode thumbnail image: " + url);
                    return;
                }
                Utils.runOnMainThread(() -> imageView.setImageBitmap(bitmap));
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not load thumbnail image: " + url, ex);
            } catch (Exception ex) {
                Logger.printException(() -> "loadImage failure", ex);
            }
        });
    }

    /**
     * @return The time as minutes and seconds with hundredths, and hours if needed,
     *         since frames close to each other are different thumbnails.
     */
    private static String formatTime(double seconds) {
        final long wholeSeconds = (long) seconds;
        final long hours = wholeSeconds / 3600;
        final long minutes = (wholeSeconds % 3600) / 60;
        final double secondsOfMinute = seconds - hours * 3600 - minutes * 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%05.2f", hours, minutes, secondsOfMinute);
        }
        return String.format(Locale.US, "%d:%05.2f", minutes, secondsOfMinute);
    }

    /**
     * @param name "thumb_up" or "thumb_down".
     * @return The bold icon if the app uses bold icons and has it, otherwise the regular icon.
     */
    private static int getVoteIcon(String name) {
        if (Utils.appIsUsingBoldIcons()) {
            final int boldIcon = ResourceUtils.getDrawableIdentifier("yt_outline_experimental_" + name + "_black_24");
            if (boldIcon != 0) {
                return boldIcon;
            }
        }
        return ResourceUtils.getDrawableIdentifier("yt_outline_" + name + "_black_24");
    }

    private static ImageView createVoteButton(Context context, int icon, String description,
                                              int foregroundColor, Runnable onClick) {
        ImageView button = new ImageView(context);
        button.setImageResource(icon);
        Drawable drawable = button.getDrawable();
        if (drawable != null) {
            drawable.mutate().setTint(foregroundColor);
        }
        button.setContentDescription(description);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackgroundResource(ThemeUtils.getThemeResourceId(context,
                android.R.attr.selectableItemBackgroundBorderless));
        button.setOnClickListener(v -> onClick.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48));
        return button;
    }

    /**
     * @param originalTitle The original title, or null if not yet fetched.
     */
    private static void showNewTitleDialog(Context context, SheetBottomDialog.SlideDialog sheet, String videoId,
                                           @Nullable String originalTitle, long videoLength) {
        try {
            EditText editText = new EditText(context);
            // One line of text that wraps, so a long title is shown entirely.
            editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            editText.setHorizontallyScrolling(false);
            editText.setMaxLines(NEW_TITLE_MAX_LINES);
            editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(DeArrowVoteRequest.MAX_TITLE_LENGTH)});
            editText.setHint(str("morphe_dearrow_vote_new_title_hint"));
            // Starting from the original title makes a small change easy.
            if (originalTitle != null) {
                editText.setText(originalTitle);
                editText.setSelection(editText.length());
            }

            CustomDialog.create(context,
                    str("morphe_dearrow_vote_new_title"),
                    null,
                    editText,
                    str("morphe_dearrow_vote_submit"),
                    () -> {
                        String title = editText.getText().toString().trim();
                        if (title.isEmpty()) {
                            return;
                        }
                        final boolean original = title.equals(originalTitle);
                        vote(context, sheet, videoId, false, () -> DeArrowVoteRequest.voteTitle(
                                videoId, title, original, false, videoLength));
                    },
                    () -> {},
                    null,
                    null,
                    false
            ).first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showNewTitleDialog failure", ex);
        }
    }

    /**
     * Submits the time of the frame shown in the player as a new thumbnail.
     * The time is read when the button is tapped, so a playing video is submitted at that moment.
     */
    private static void showCurrentFrameDialog(Context context, SheetBottomDialog.SlideDialog sheet,
                                               String videoId, long videoLength) {
        try {
            final long videoTime = VideoInformation.getVideoTime();
            if (videoTime < 0) {
                Logger.printDebug(() -> "Video time is not available");
                return;
            }
            final double timestamp = videoTime / 1000.0;

            String message = str("morphe_dearrow_vote_current_frame_content", formatTime(timestamp));
            if (VideoState.getCurrent() == VideoState.PLAYING) {
                message += "\n\n" + str("morphe_dearrow_vote_current_frame_playing");
            }

            CustomDialog.create(context,
                    str("morphe_dearrow_vote_current_frame"),
                    message,
                    null,
                    str("morphe_dearrow_vote_submit"),
                    () -> vote(context, sheet, videoId, false, () -> DeArrowVoteRequest.voteThumbnail(
                            videoId, false, timestamp, false, videoLength)),
                    () -> {},
                    null,
                    null,
                    false
            ).first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showCurrentFrameDialog failure", ex);
        }
    }

    /**
     * @param request Sends the vote, and returns the error to show or null if the vote was saved.
     */
    private static void vote(Context context, SheetBottomDialog.SlideDialog sheet, String videoId,
                             boolean downvote, Supplier<String> request) {
        // The sheet is closed first, so a vote is not sent twice.
        sheet.dismiss();

        Utils.runOnBackgroundThread(() -> {
            String error = request.get();
            if (error == null) {
                Utils.showSnackbarShort(str(downvote
                        ? "morphe_dearrow_vote_downvoted"
                        : "morphe_dearrow_vote_upvoted"));
                RestoreOriginalTitlesPatch.fetchTitlesAgain(videoId);
                return;
            }
            // The reason can be about the account of the user, and too long for a snackbar.
            Utils.runOnMainThread(() -> {
                try {
                    CustomDialog.create(context, str("morphe_dearrow_vote_title"), error,
                            null, null, () -> {}, null, null, null, false).first.show();
                } catch (Exception ex) {
                    Logger.printException(() -> "vote failure", ex);
                }
            });
        });
    }
}
