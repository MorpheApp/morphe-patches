/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.ui;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.ui.Dim.dp;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Optional;

import app.morphe.extension.music.patches.soundproof.SoundProofManager;
import app.morphe.extension.music.patches.soundproof.domain.ArtistAssessment;
import app.morphe.extension.music.patches.soundproof.domain.ArtistRecord;
import app.morphe.extension.music.patches.soundproof.domain.ArtistStatus;
import app.morphe.extension.music.patches.soundproof.domain.VoteMajority;
import app.morphe.extension.music.patches.soundproof.domain.VoteMajority.VoteSide;
import app.morphe.extension.shared.Logger;

/**
 * Bottom sheet with the community votes for the artist of the current track. Read only.
 * <p>
 * It follows the current track while open. The player is always dark, so the colors are fixed.
 */
final class SoundProofSheet implements SoundProofManager.AssessmentListener {

    private static final int SHEET_COLOR = 0xFF212121;
    private static final int HANDLE_COLOR = 0xFF5F5F5F;
    private static final int PRIMARY_TEXT_COLOR = Color.WHITE;
    private static final int SECONDARY_TEXT_COLOR = 0xFFB3B3B3;
    private static final int TERTIARY_TEXT_COLOR = 0xFFA8A8A8;
    private static final int CHIP_TEXT_COLOR = 0xFFD0D0D0;
    private static final float SCRIM_DIM_AMOUNT = 0.6f;
    private static final int ICON_BACKGROUND_ALPHA = 41; // 16 %

    /** Share of the sheet height a downward drag must reach to close it. */
    private static final float DISMISS_DRAG_FRACTION = 0.25f;
    private static final long SETTLE_DURATION_MS = 150;

    private final Context context;
    private final Dialog dialog;
    private final LinearLayout sheet;
    /** Everything below the drag handle, rebuilt for each assessment. */
    private final LinearLayout content;

    static void show(Context context) {
        try {
            new SoundProofSheet(context).open();
        } catch (Exception ex) {
            Logger.printException(() -> "SoundProof sheet failure", ex);
        }
    }

    private SoundProofSheet(Context context) {
        this.context = context;

        content = verticalLayout();
        sheet = verticalLayout();
        GradientDrawable sheetBackground = new GradientDrawable();
        sheetBackground.setColor(SHEET_COLOR);
        float radius = dp(28);
        sheetBackground.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(sheetBackground);
        sheet.setPadding(dp(24), dp(12), dp(24), dp(32));
        sheet.addView(dragHandle(), centered(dp(32), dp(4), dp(20)));
        sheet.addView(content, matchWidth());
        enableDragToDismiss();

        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> SoundProofManager.getInstance().removeListener(this));
    }

    private void open() {
        SoundProofManager manager = SoundProofManager.getInstance();
        onAssessmentChanged(manager.getCurrentAssessment());
        manager.addListener(this);
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(SCRIM_DIM_AMOUNT);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
    }

    @Override
    public void onAssessmentChanged(ArtistAssessment assessment) {
        content.removeAllViews();
        String credit = SoundProofManager.getInstance().getCurrentCredit();
        Optional<ArtistRecord> artist = assessment.artist();
        String artistName = artist.map(ArtistRecord::name).orElse(credit);

        content.addView(header(assessment.status(), artistName, artist.map(ArtistRecord::verified).orElse(false)),
                matchWidth());

        Optional<VoteMajority> majority = artist.flatMap(VoteMajority::of);
        if (majority.isPresent()) {
            content.addView(votes(artist.get(), majority.get()), withTopMargin(dp(20)));
        } else {
            content.addView(emptyState(assessment.status()), withTopMargin(dp(20)));
        }

        content.addView(text(str("morphe_music_soundproof_sheet_source"), 12, TERTIARY_TEXT_COLOR, false),
                withTopMargin(dp(20)));
    }

    // region Sections

    private View header(ArtistStatus status, String artistName, boolean isVerified) {
        LinearLayout row = horizontalLayout();
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconCircle = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        int color = StatusAppearance.colorOf(status);
        circle.setColor(Color.argb(ICON_BACKGROUND_ALPHA, Color.red(color), Color.green(color), Color.blue(color)));
        iconCircle.setBackground(circle);
        Drawable icon = StatusAppearance.iconOf(status);
        if (icon != null) {
            ImageView iconView = new ImageView(context);
            iconView.setImageDrawable(icon);
            iconCircle.addView(iconView, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        }
        row.addView(iconCircle, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = verticalLayout();
        TextView label = text(str("morphe_music_soundproof_screen_title"), 12, TERTIARY_TEXT_COLOR, false);
        label.setAllCaps(true);
        label.setLetterSpacing(0.06f);
        titles.addView(label);
        if (artistName != null) {
            TextView name = text(artistName, 20, PRIMARY_TEXT_COLOR, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            titles.addView(name);
        }
        LinearLayout.LayoutParams titlesParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titlesParams.setMarginStart(dp(14));
        row.addView(titles, titlesParams);

        if (isVerified) {
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.setMarginStart(dp(8));
            row.addView(verifiedChip(), chipParams);
        }
        return row;
    }

    private View verifiedChip() {
        TextView chip = text("\u2713 " + str("morphe_music_soundproof_sheet_verified"), 12, CHIP_TEXT_COLOR, false);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(12));
        border.setStroke(dp(1), HANDLE_COLOR);
        chip.setBackground(border);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        return chip;
    }

    private View votes(ArtistRecord artist, VoteMajority majority) {
        LinearLayout section = verticalLayout();

        boolean isAiMajority = majority.side() == VoteSide.AI;
        LinearLayout headline = horizontalLayout();
        headline.setBaselineAligned(true);
        TextView percent = text(str(isAiMajority
                        ? "morphe_music_soundproof_sheet_percent_ai"
                        : "morphe_music_soundproof_sheet_percent_human", majority.percent()),
                40, isAiMajority ? StatusAppearance.AI_COLOR : StatusAppearance.HUMAN_COLOR, true);
        headline.addView(percent);
        String totalVotes = artist.totalVotes() == 1
                ? str("morphe_music_soundproof_sheet_one_vote")
                : str("morphe_music_soundproof_sheet_votes", artist.totalVotes());
        LinearLayout.LayoutParams totalParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        totalParams.setMarginStart(dp(10));
        headline.addView(text(totalVotes, 14, SECONDARY_TEXT_COLOR, false), totalParams);
        section.addView(headline);

        section.addView(proportionBar(artist), barParams(dp(10), dp(10)));

        LinearLayout counts = horizontalLayout();
        counts.addView(text(str("morphe_music_soundproof_sheet_human_votes", artist.humanVotes()),
                        14, StatusAppearance.HUMAN_COLOR, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView aiVotes = text(str("morphe_music_soundproof_sheet_ai_votes", artist.aiVotes()),
                14, StatusAppearance.AI_COLOR, false);
        aiVotes.setGravity(Gravity.END);
        counts.addView(aiVotes, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        section.addView(counts, withTopMargin(dp(10)));
        return section;
    }

    private View proportionBar(ArtistRecord artist) {
        LinearLayout bar = horizontalLayout();
        GradientDrawable clip = new GradientDrawable();
        clip.setCornerRadius(dp(5));
        clip.setColor(Color.TRANSPARENT);
        bar.setBackground(clip);
        bar.setClipToOutline(true);

        if (artist.humanVotes() > 0) {
            bar.addView(segment(StatusAppearance.HUMAN_COLOR), weighted(artist.humanVotes(), 0));
        }
        if (artist.aiVotes() > 0) {
            int gap = artist.humanVotes() > 0 ? dp(2) : 0;
            bar.addView(segment(StatusAppearance.AI_COLOR), weighted(artist.aiVotes(), gap));
        }
        return bar;
    }

    private View emptyState(ArtistStatus status) {
        boolean isUnavailable = status == ArtistStatus.UNAVAILABLE;
        LinearLayout section = verticalLayout();
        section.addView(text(str(isUnavailable
                        ? "morphe_music_soundproof_sheet_unavailable_title"
                        : "morphe_music_soundproof_sheet_no_votes_title"),
                16, PRIMARY_TEXT_COLOR, true));
        // A listed artist can have no vote, it is then not "missing from the database".
        if (isUnavailable || status == ArtistStatus.UNKNOWN) {
            TextView body = text(str(isUnavailable
                            ? "morphe_music_soundproof_sheet_unavailable_summary"
                            : "morphe_music_soundproof_sheet_no_votes_summary"),
                    14, SECONDARY_TEXT_COLOR, false);
            body.setLineSpacing(0, 1.3f);
            section.addView(body, withTopMargin(dp(8)));
        }
        return section;
    }

    private View dragHandle() {
        View handle = new View(context);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(2));
        shape.setColor(HANDLE_COLOR);
        handle.setBackground(shape);
        return handle;
    }

    // endregion

    // region Drag to dismiss

    @SuppressLint("ClickableViewAccessibility")
    private void enableDragToDismiss() {
        float[] downRawY = {0};
        sheet.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> downRawY[0] = event.getRawY();
                case MotionEvent.ACTION_MOVE ->
                        view.setTranslationY(Math.max(0, event.getRawY() - downRawY[0]));
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (view.getTranslationY() > view.getHeight() * DISMISS_DRAG_FRACTION) {
                        dialog.dismiss();
                    } else {
                        view.animate().translationY(0).setDuration(SETTLE_DURATION_MS).start();
                    }
                }
                default -> {
                    return false;
                }
            }
            return true;
        });
    }

    // endregion

    // region View helpers

    private TextView text(String value, float sizeSp, int color, boolean isBold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        if (isBold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private View segment(int color) {
        View segment = new View(context);
        segment.setBackgroundColor(color);
        return segment;
    }

    private LinearLayout verticalLayout() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontalLayout() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams withTopMargin(int topMargin) {
        LinearLayout.LayoutParams params = matchWidth();
        params.topMargin = topMargin;
        return params;
    }

    private static LinearLayout.LayoutParams barParams(int topMargin, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = topMargin;
        return params;
    }

    private static LinearLayout.LayoutParams centered(int width, int height, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = bottomMargin;
        return params;
    }

    private static LinearLayout.LayoutParams weighted(int weight, int startMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.setMarginStart(startMargin);
        return params;
    }

    // endregion
}
