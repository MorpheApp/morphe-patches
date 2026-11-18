/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.ui;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.ui.Dim.dp;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Optional;

import app.morphe.extension.music.patches.soundproof.SoundProofManager;
import app.morphe.extension.music.patches.soundproof.SoundProofManager.LiveState;
import app.morphe.extension.music.patches.soundproof.domain.ArtistAssessment;
import app.morphe.extension.music.patches.soundproof.domain.ArtistRecord;
import app.morphe.extension.music.patches.soundproof.domain.ArtistStatus;
import app.morphe.extension.music.patches.soundproof.domain.VoteMajority;
import app.morphe.extension.music.patches.soundproof.domain.VoteMajority.VoteSide;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * Bottom sheet with the community votes for the artist of the current track, the vote buttons,
 * and a switch for the automatic skip.
 * <p>
 * It follows the current track while open. The player is always dark, so the colors are fixed.
 */
final class SoundProofSheet implements SoundProofManager.StateListener {

    private static final int SHEET_COLOR = 0xFF212121;
    private static final int CHIP_BORDER_COLOR = 0xFF5F5F5F;
    private static final int PRIMARY_TEXT_COLOR = Color.WHITE;
    private static final int SECONDARY_TEXT_COLOR = 0xFFB3B3B3;
    private static final int TERTIARY_TEXT_COLOR = 0xFFA8A8A8;
    private static final int CHIP_TEXT_COLOR = 0xFFD0D0D0;
    private static final int EMPTY_BAR_COLOR = 0xFF424242;
    private static final int SWITCH_OFF_THUMB_COLOR = 0xFFBDBDBD;
    private static final int SWITCH_OFF_TRACK_COLOR = 0xFF5F5F5F;
    private static final int TINT_ALPHA = 41; // 16 %
    private static final float DISABLED_ALPHA = 0.4f;
    private static final int SLIDE_ANIMATION_MILLIS = 200;
    private static final String CHECK_MARK = "✓ ";

    private final Context context;
    private final Dialog dialog;
    /** Artist and votes, rebuilt for each assessment. */
    private final LinearLayout content;
    /** Kept out of {@link #content}, so that rebuilding it does not cut the switch animation. */
    private final Switch skipSwitch;

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
        skipSwitch = skipSwitch();

        LinearLayout sheet = SheetBottomDialog.createMainLayout(context, SHEET_COLOR);
        sheet.setPadding(dp(24), 0, dp(24), dp(24));
        sheet.addView(content, withTopMargin(dp(16)));
        sheet.addView(skipRow(skipSwitch), withTopMargin(dp(20)));
        sheet.addView(text(str("morphe_music_soundproof_sheet_source"), 12, TERTIARY_TEXT_COLOR, false),
                withTopMargin(dp(16)));

        dialog = SheetBottomDialog.createSlideDialog(context, sheet, SLIDE_ANIMATION_MILLIS);
        dialog.setOnDismissListener(d -> SoundProofManager.getInstance().removeListener(this));
    }

    private void open() {
        SoundProofManager manager = SoundProofManager.getInstance();
        manager.reloadLiveStatus();
        onStateChanged();
        manager.addListener(this);
        dialog.show();
    }

    @Override
    public void onStateChanged() {
        SoundProofManager manager = SoundProofManager.getInstance();
        // Does nothing when the switch was the one that changed.
        skipSwitch.setChecked(manager.isSkipEnabled());
        // Also follows the track while the sheet is open.
        manager.loadLiveStatus();

        ArtistAssessment assessment = manager.getCurrentAssessment();
        content.removeAllViews();
        String credit = manager.getCurrentCredit();
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

        if (manager.getVoteTarget().isPresent()) {
            content.addView(voteSection(manager), withTopMargin(dp(20)));
        }
    }

    // region Sections

    private View header(ArtistStatus status, @Nullable String artistName, boolean isVerified) {
        LinearLayout row = horizontalLayout();
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconCircle = new FrameLayout(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(tinted(StatusAppearance.colorOf(status)));
        iconCircle.setBackground(circle);
        ImageView iconView = new ImageView(context);
        iconView.setImageDrawable(StatusAppearance.iconOf(status));
        iconCircle.addView(iconView, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
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
        TextView chip = text(CHECK_MARK + str("morphe_music_soundproof_sheet_verified"), 12, CHIP_TEXT_COLOR, false);
        GradientDrawable border = new GradientDrawable();
        border.setCornerRadius(dp(12));
        border.setStroke(dp(1), CHIP_BORDER_COLOR);
        chip.setBackground(border);
        chip.setPadding(dp(8), dp(3), dp(8), dp(3));
        return chip;
    }

    private View votes(ArtistRecord artist, VoteMajority majority) {
        LinearLayout section = verticalLayout();

        boolean isAiMajority = majority.side() == VoteSide.AI;
        LinearLayout headline = horizontalLayout();
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
        bar.setBackground(barShape(Color.TRANSPARENT));
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

    private GradientDrawable barShape(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(5));
        shape.setColor(color);
        return shape;
    }

    private View emptyState(ArtistStatus status) {
        boolean isUnavailable = status == ArtistStatus.UNAVAILABLE;
        LinearLayout section = verticalLayout();
        section.addView(text(str(isUnavailable
                        ? "morphe_music_soundproof_sheet_unavailable_title"
                        : "morphe_music_soundproof_sheet_no_votes_title"),
                16, PRIMARY_TEXT_COLOR, true));
        if (isUnavailable) {
            TextView body = text(str("morphe_music_soundproof_sheet_unavailable_summary"),
                    14, SECONDARY_TEXT_COLOR, false);
            body.setLineSpacing(0, 1.3f);
            section.addView(body, withTopMargin(dp(8)));
        } else {
            // Empty bar where the proportion bar of a voted artist sits.
            View emptyBar = new View(context);
            emptyBar.setBackground(barShape(EMPTY_BAR_COLOR));
            section.addView(emptyBar, barParams(dp(10), dp(10)));
        }
        return section;
    }

    private View voteSection(SoundProofManager manager) {
        LiveState liveState = manager.getLiveState();
        if (liveState == LiveState.FAILED) {
            return text(str("morphe_music_soundproof_sheet_live_failed"), 13, SECONDARY_TEXT_COLOR, false);
        }
        if (manager.isVoteLocked()) {
            return text(str("morphe_music_soundproof_sheet_voting_locked"), 13, SECONDARY_TEXT_COLOR, false);
        }

        // The current vote is only known once the server answered.
        boolean isEnabled = liveState == LiveState.READY && !manager.isVoting();
        Optional<VoteSide> myVote = manager.getMyVote();
        LinearLayout buttons = horizontalLayout();
        buttons.addView(voteButton(VoteSide.HUMAN, myVote.orElse(null), isEnabled), weighted(1, 0));
        buttons.addView(voteButton(VoteSide.AI, myVote.orElse(null), isEnabled), weighted(1, dp(12)));
        LinearLayout section = verticalLayout();
        section.addView(buttons, barParams(0, dp(44)));
        return section;
    }

    /**
     * Tinted when not voted for. The side voted for gets a check mark and an outline, and a tap
     * on it removes the vote.
     */
    private View voteButton(VoteSide side, @Nullable VoteSide myVote, boolean isEnabled) {
        boolean isHuman = side == VoteSide.HUMAN;
        int color = isHuman ? StatusAppearance.HUMAN_COLOR : StatusAppearance.AI_COLOR;
        boolean isVoted = side == myVote;
        String label = str(isHuman ? "morphe_music_soundproof_sheet_vote_human" : "morphe_music_soundproof_sheet_vote_ai");

        TextView button = text(isVoted ? CHECK_MARK + label : label, 15, color, true);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(22));
        background.setColor(tinted(color));
        if (isVoted) {
            background.setStroke(dp(2), color);
        }
        button.setBackground(background);
        button.setEnabled(isEnabled);
        button.setAlpha(isEnabled ? 1f : DISABLED_ALPHA);
        button.setOnClickListener(view -> SoundProofManager.getInstance().vote(side));
        return button;
    }

    private Switch skipSwitch() {
        Switch toggle = new Switch(context);
        int[][] states = {{android.R.attr.state_checked}, {}};
        toggle.setThumbTintList(new ColorStateList(states, new int[]{StatusAppearance.HUMAN_COLOR, SWITCH_OFF_THUMB_COLOR}));
        toggle.setTrackTintList(new ColorStateList(states, new int[]{StatusAppearance.HUMAN_COLOR, SWITCH_OFF_TRACK_COLOR}));
        toggle.setOnCheckedChangeListener((button, isChecked) ->
                SoundProofManager.getInstance().setSkipEnabled(isChecked));
        return toggle;
    }

    private View skipRow(Switch toggle) {
        LinearLayout row = horizontalLayout();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        TextView label = text(str("morphe_music_soundproof_skip_ai_artists_title"), 16, PRIMARY_TEXT_COLOR, false);
        // The whole row toggles, not only the small switch.
        label.setOnClickListener(view -> toggle.toggle());
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(toggle);
        return row;
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

    private static int tinted(int color) {
        return Color.argb(TINT_ALPHA, Color.red(color), Color.green(color), Color.blue(color));
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

    private static LinearLayout.LayoutParams weighted(int weight, int startMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        params.setMarginStart(startMargin);
        return params;
    }

    // endregion
}
