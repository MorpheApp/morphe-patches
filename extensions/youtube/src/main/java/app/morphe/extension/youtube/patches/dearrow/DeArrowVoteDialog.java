/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
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

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.dearrow.DeArrowVoteRequest.TitleSubmission;
import app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch;
import app.morphe.extension.youtube.shared.PipDismissHelper;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

/**
 * Sheet of the DeArrow titles of the video in the player, to vote on them or submit a new title.
 * <p>
 * The original title is listed first, as done by the DeArrow extension. It is the title as set
 * by the uploader, since YouTube can show a translated title instead.
 */
final class DeArrowVoteDialog {

    private static final int UPVOTE_ICON = ResourceUtils.getDrawableIdentifier("yt_outline_thumb_up_black_24");
    private static final int DOWNVOTE_ICON = ResourceUtils.getDrawableIdentifier("yt_outline_thumb_down_black_24");

    /**
     * Opacity of the line under each title, which shows its votes.
     */
    private static final float DETAILS_ALPHA = 0.7f;

    /**
     * Lines shown of a new title, before the text field scrolls.
     */
    private static final int NEW_TITLE_MAX_LINES = 4;

    /**
     * A title listed in the sheet.
     *
     * @param title     The title as submitted, or the original title if no one submitted it.
     * @param original  If the title is the original title.
     * @param submitted If the title was submitted, so it can be downvoted.
     * @param votes     The votes of the title, if submitted.
     * @param locked    If a VIP locked the title.
     */
    private record ListedTitle(String title, boolean original, boolean submitted, int votes, boolean locked) {
        String shownTitle() {
            return DeArrowBrandingRequest.formatTitle(title);
        }
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

        LinearLayout titlesLayout = new LinearLayout(context);
        titlesLayout.setOrientation(LinearLayout.VERTICAL);
        titlesLayout.addView(createMessage(context, str("morphe_dearrow_vote_loading"), foregroundColor));

        // Capped so the buttons stay reachable in landscape, where height is limited.
        ScrollView scrollView = SheetBottomDialog.createCappedScrollView(context);
        scrollView.addView(titlesLayout);
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
        LinearLayout.LayoutParams newTitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp36);
        newTitleParams.setMargins(0, Dim.dp16, 0, 0);
        contentLayout.addView(newTitleButton, newTitleParams);

        dialog.show();

        Utils.runOnBackgroundThread(() -> {
            List<TitleSubmission> submissions;
            try {
                submissions = DeArrowVoteRequest.fetchTitles(videoId);
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not fetch DeArrow titles of: " + videoId, ex);
                Utils.runOnMainThread(() -> {
                    titlesLayout.removeAllViews();
                    titlesLayout.addView(createMessage(context,
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

            List<ListedTitle> titles = listTitles(submissions, original);
            String fetchedOriginal = original;
            Utils.runOnMainThread(() -> {
                originalTitle[0] = fetchedOriginal;
                titlesLayout.removeAllViews();
                if (titles.isEmpty()) {
                    titlesLayout.addView(createMessage(context,
                            str("morphe_dearrow_vote_no_titles"), foregroundColor));
                    return;
                }
                for (ListedTitle title : titles) {
                    titlesLayout.addView(createTitleRow(context, dialog, videoId, title,
                            videoLength, foregroundColor));
                }
            });
        });
    }

    /**
     * @param originalTitle The original title, or null if it failed to fetch.
     * @return The original title first, then the submitted titles in the order of the server.
     */
    private static List<ListedTitle> listTitles(List<TitleSubmission> submissions, @Nullable String originalTitle) {
        List<ListedTitle> titles = new ArrayList<>(submissions.size() + 1);
        ListedTitle original = null;
        for (TitleSubmission submission : submissions) {
            ListedTitle title = new ListedTitle(submission.title(), submission.original(),
                    true, submission.votes(), submission.locked());
            if (submission.original() && original == null) {
                original = title;
            } else {
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
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Dim.dp8, 0, Dim.dp8);

        LinearLayout textLayout = new LinearLayout(context);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        row.addView(textLayout, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView titleView = new TextView(context);
        titleView.setText(title.shownTitle());
        titleView.setTextSize(16);
        titleView.setTextColor(foregroundColor);
        textLayout.addView(titleView);

        List<String> details = new ArrayList<>(3);
        if (title.original()) {
            details.add(str("morphe_dearrow_vote_original_title"));
        }
        if (title.submitted()) {
            details.add(str("morphe_dearrow_vote_votes", title.votes()));
        }
        if (title.locked()) {
            details.add(str("morphe_dearrow_vote_locked"));
        }
        if (!details.isEmpty()) {
            TextView detailsView = new TextView(context);
            detailsView.setText(TextUtils.join(" · ", details));
            detailsView.setTextSize(13);
            detailsView.setTextColor(foregroundColor);
            detailsView.setAlpha(DETAILS_ALPHA);
            textLayout.addView(detailsView);
        }

        row.addView(createVoteButton(context, UPVOTE_ICON, str("morphe_dearrow_vote_upvote"), foregroundColor,
                () -> vote(context, dialog, videoId, title.title(), title.original(), false, videoLength)));
        // Only submitted titles can be downvoted.
        if (title.submitted()) {
            row.addView(createVoteButton(context, DOWNVOTE_ICON, str("morphe_dearrow_vote_downvote"), foregroundColor,
                    () -> vote(context, dialog, videoId, title.title(), title.original(), true, videoLength)));
        }
        return row;
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
                        vote(context, sheet, videoId, title, title.equals(originalTitle), false, videoLength);
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

    private static void vote(Context context, SheetBottomDialog.SlideDialog sheet, String videoId, String title,
                             boolean original, boolean downvote, long videoLength) {
        // The sheet is closed first, so a vote is not sent twice.
        sheet.dismiss();

        Utils.runOnBackgroundThread(() -> {
            String error = DeArrowVoteRequest.vote(videoId, title, original, downvote, videoLength);
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
