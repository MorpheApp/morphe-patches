/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Pair;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;

/**
 * Interactive dialogs for Ghost Play quick controls and on-device ghost history.
 */
public final class GhostHistoryDialog {

    private static final String GHOST_SETTINGS_SCREEN_KEY = "morphe_ghost_play_screen";

    private GhostHistoryDialog() {
    }

    /**
     * Resolves an active Activity context so dialogs display reliably from overlay views.
     */
    @Nullable
    public static Context resolveActivityContext(@Nullable Context raw) {
        Context cur = raw;
        while (cur instanceof ContextWrapper wrapper) {
            if (cur instanceof Activity activity) {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    return activity;
                }
                break;
            }
            cur = wrapper.getBaseContext();
        }
        Activity fallback = Utils.getActivity();
        if (fallback != null && !fallback.isFinishing() && !fallback.isDestroyed()) {
            return fallback;
        }
        return raw;
    }

    /**
     * Shows the Ghost Play quick controls dialog (long-press on player or toolbar button).
     */
    public static void showQuickMenu(@Nullable Context rawContext) {
        try {
            Context context = resolveActivityContext(rawContext);
            if (context == null) {
                Logger.printDebug(() -> "showQuickMenu: no valid Activity context");
                return;
            }

            Pair<Dialog, LinearLayout> result = CustomDialog.create(
                    context,
                    str("morphe_ghost_play_dialog_title"),
                    null,
                    null,
                    str("morphe_ghost_play_close"),
                    () -> {},
                    null,
                    str("morphe_ghost_play_open_settings"),
                    () -> YouTubeActivityHook.openSettings(context, GHOST_SETTINGS_SCREEN_KEY),
                    true
            );
            Dialog dialog = result.first;
            LinearLayout mainLayout = result.second;

            QuickMenuController controller = new QuickMenuController(context, dialog);
            int insertIdx = Math.max(0, mainLayout.getChildCount() - 1);
            mainLayout.addView(controller.root, insertIdx);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showQuickMenu failure", ex);
        }
    }

    private static final class QuickMenuController {
        final Context context;
        final Dialog dialog;
        final LinearLayout root;

        final LinearLayout powerCard;
        final FrameLayout powerOrb;
        final ImageView powerGhostIcon;
        final ImageView powerRingIcon;
        final TextView powerBadge;

        final LinearLayout timerCard;
        final ImageView timerIcon;
        final TextView timerTitle;
        final TextView timerSub;
        final LinearLayout timerActiveActions;
        final LinearLayout timerPickerArea;
        boolean timerPickerOpen = false;

        final LinearLayout channelContent;

        QuickMenuController(Context context, Dialog dialog) {
            this.context = context;
            this.dialog = dialog;

            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, Dim.dp4, 0, Dim.dp4);

            // Power toggle section.
            powerCard = new LinearLayout(context);
            powerCard.setOrientation(LinearLayout.HORIZONTAL);
            powerCard.setGravity(Gravity.CENTER_VERTICAL);
            powerCard.setPadding(Dim.dp12, Dim.dp12, Dim.dp12, Dim.dp12);
            LinearLayout.LayoutParams powerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            powerLp.bottomMargin = Dim.dp10;
            powerCard.setLayoutParams(powerLp);

            powerOrb = new FrameLayout(context);
            int orbSize = Dim.dp(50);
            powerOrb.setLayoutParams(new LinearLayout.LayoutParams(orbSize, orbSize));

            powerGhostIcon = new ImageView(context);
            powerGhostIcon.setLayoutParams(new FrameLayout.LayoutParams(Dim.dp(28), Dim.dp(28), Gravity.CENTER));
            powerOrb.addView(powerGhostIcon);

            powerRingIcon = new ImageView(context);
            FrameLayout.LayoutParams rLp = new FrameLayout.LayoutParams(Dim.dp(15), Dim.dp(15), Gravity.BOTTOM | Gravity.END);
            rLp.bottomMargin = Dim.dp(3);
            rLp.rightMargin = Dim.dp(3);
            powerRingIcon.setLayoutParams(rLp);
            setDrawable(powerRingIcon, "morphe_ghost_play_power");
            powerOrb.addView(powerRingIcon);

            powerCard.addView(powerOrb);

            LinearLayout powerTexts = new LinearLayout(context);
            powerTexts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams ptLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ptLp.leftMargin = Dim.dp12;
            ptLp.rightMargin = Dim.dp8;
            powerTexts.setLayoutParams(ptLp);

            TextView powerTitle = new TextView(context);
            powerTitle.setText(str("morphe_ghost_play_mode_active_title"));
            powerTitle.setTextColor(ThemeUtils.getAppForegroundColor());
            powerTitle.setTextSize(15f);
            powerTitle.setTypeface(null, Typeface.BOLD);
            powerTexts.addView(powerTitle);

            TextView powerHint = new TextView(context);
            powerHint.setText(str("morphe_ghost_play_quick_power_hint"));
            powerHint.setTextColor(GhostTheme.mutedText());
            powerHint.setTextSize(11.5f);
            powerTexts.addView(powerHint);

            powerCard.addView(powerTexts);

            powerBadge = new TextView(context);
            powerBadge.setTextSize(12f);
            powerBadge.setTypeface(null, Typeface.BOLD);
            powerBadge.setPadding(Dim.dp10, Dim.dp6, Dim.dp10, Dim.dp6);
            powerCard.addView(powerBadge);

            powerCard.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                GhostPlayPatch.toggleSessionGhostMode();
                renderAll(true);
            });
            root.addView(powerCard);

            // Timer section.
            timerCard = new LinearLayout(context);
            timerCard.setOrientation(LinearLayout.VERTICAL);
            timerCard.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp10);
            LinearLayout.LayoutParams timerLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            timerLp.bottomMargin = Dim.dp10;
            timerCard.setLayoutParams(timerLp);

            LinearLayout timerHeader = new LinearLayout(context);
            timerHeader.setOrientation(LinearLayout.HORIZONTAL);
            timerHeader.setGravity(Gravity.CENTER_VERTICAL);

            timerIcon = new ImageView(context);
            timerIcon.setLayoutParams(new LinearLayout.LayoutParams(Dim.dp(22), Dim.dp(22)));
            setDrawable(timerIcon, "morphe_ghost_play_timer");
            timerHeader.addView(timerIcon);

            LinearLayout timerLabels = new LinearLayout(context);
            timerLabels.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tlLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tlLp.leftMargin = Dim.dp10;
            timerLabels.setLayoutParams(tlLp);

            timerTitle = new TextView(context);
            timerTitle.setTextColor(ThemeUtils.getAppForegroundColor());
            timerTitle.setTextSize(13.5f);
            timerTitle.setTypeface(null, Typeface.BOLD);
            timerLabels.addView(timerTitle);

            timerSub = new TextView(context);
            timerSub.setTextColor(GhostTheme.mutedText());
            timerSub.setTextSize(11.5f);
            timerLabels.addView(timerSub);

            timerHeader.addView(timerLabels);
            timerHeader.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                timerPickerOpen = !timerPickerOpen;
                GhostTheme.playTimerNudge(timerIcon);
                renderTimer();
            });
            timerCard.addView(timerHeader);

            timerActiveActions = new LinearLayout(context);
            timerActiveActions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams taLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            taLp.topMargin = Dim.dp8;
            timerActiveActions.setLayoutParams(taLp);
            timerCard.addView(timerActiveActions);

            timerPickerArea = new LinearLayout(context);
            timerPickerArea.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tpLp.topMargin = Dim.dp8;
            timerPickerArea.setLayoutParams(tpLp);
            timerCard.addView(timerPickerArea);

            root.addView(timerCard);

            // Auto-ghost channel section.
            LinearLayout channelCard = new LinearLayout(context);
            channelCard.setOrientation(LinearLayout.VERTICAL);
            channelCard.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp10);
            channelCard.setBackground(GhostTheme.roundedRect(Dim.dp12, GhostTheme.cardSurface()));
            channelContent = new LinearLayout(context);
            channelContent.setOrientation(LinearLayout.HORIZONTAL);
            channelContent.setGravity(Gravity.CENTER_VERTICAL);
            channelCard.addView(channelContent);
            root.addView(channelCard);

            renderAll(false);
        }

        private void renderAll(boolean animatePower) {
            renderPower(animatePower);
            renderTimer();
            renderChannel();
        }

        private void renderPower(boolean animate) {
            boolean active = GhostPlayPatch.isSessionGhostActive();
            long rem = GhostPlayPatch.getTimedGhostRemainingMinutes();

            if (active) {
                powerCard.setBackground(GhostTheme.outlinedRect(
                        Dim.dp(14), GhostTheme.accentSoft(), Dim.dp(1), GhostTheme.accentBorder()));
                powerOrb.setBackground(GhostTheme.morpheGradientCircle(
                        Dim.dp(1), GhostTheme.accentBorder()));
                setDrawable(powerGhostIcon, "morphe_ghost_play_button_on");
                powerGhostIcon.clearColorFilter();
                powerRingIcon.setColorFilter(GhostTheme.onAccent(), PorterDuff.Mode.SRC_IN);

                powerBadge.setText(rem > 0
                        ? str("morphe_ghost_play_toolbar_timed_badge", rem)
                        : "ON");
                powerBadge.setTextColor(GhostTheme.onAccent());
                powerBadge.setBackground(GhostTheme.morpheGradientRect(Dim.dp12));
            } else {
                powerCard.setBackground(GhostTheme.outlinedRect(
                        Dim.dp(14), GhostTheme.cardSurface(), Dim.dp(1), GhostTheme.subtleBorder()));
                powerOrb.setBackground(GhostTheme.circle(
                        GhostTheme.subtleSurface(), Dim.dp(1), GhostTheme.subtleBorder()));
                setDrawable(powerGhostIcon, "morphe_ghost_play_button_off");
                powerGhostIcon.setColorFilter(
                        GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.72f),
                        PorterDuff.Mode.SRC_IN);
                powerRingIcon.setColorFilter(GhostTheme.mutedText(), PorterDuff.Mode.SRC_IN);

                powerBadge.setText("OFF");
                powerBadge.setTextColor(GhostTheme.mutedText());
                powerBadge.setBackground(GhostTheme.roundedRect(Dim.dp12, GhostTheme.subtleSurface()));
            }

            if (animate) {
                GhostTheme.playCuteGhostBounce(powerGhostIcon, active);
                if (GhostTheme.areAnimationsEnabled(context)) {
                    Drawable d = powerGhostIcon.getDrawable();
                    if (d instanceof Animatable anim) {
                        anim.start();
                    }
                }
            }
        }

        private void renderTimer() {
            long rem = GhostPlayPatch.getTimedGhostRemainingMinutes();
            boolean timed = rem > 0L;

            timerCard.setBackground(timed
                    ? GhostTheme.outlinedRect(Dim.dp12, GhostTheme.accentSoft(), Dim.dp(1), GhostTheme.accentBorder())
                    : GhostTheme.roundedRect(Dim.dp12, GhostTheme.cardSurface()));
            timerIcon.setColorFilter(
                    timed ? GhostTheme.accent() : ThemeUtils.getAppForegroundColor(),
                    PorterDuff.Mode.SRC_IN);

            if (timed) {
                timerTitle.setText(str("morphe_ghost_play_quick_timer_running", rem));
                timerSub.setText(str("morphe_ghost_play_quick_timer_running_sub"));
            } else {
                timerTitle.setText(str("morphe_ghost_play_quick_timer_title"));
                timerSub.setText(str("morphe_ghost_play_quick_timer_sub"));
            }

            timerActiveActions.removeAllViews();
            if (timed) {
                timerActiveActions.setVisibility(View.VISIBLE);

                TextView changeBtn = buildChip(
                        str("morphe_ghost_play_quick_timer_change"), false);
                ((LinearLayout.LayoutParams) changeBtn.getLayoutParams()).rightMargin = Dim.dp6;
                changeBtn.setOnClickListener(v -> {
                    timerPickerOpen = !timerPickerOpen;
                    renderTimer();
                });

                TextView keepOnBtn = buildChip(
                        str("morphe_ghost_play_quick_timer_cancel_keep_on"), true);
                keepOnBtn.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    timerPickerOpen = false;
                    GhostPlayPatch.cancelTimerKeepGhostOn();
                    renderAll(true);
                });

                timerActiveActions.addView(changeBtn);
                timerActiveActions.addView(keepOnBtn);
            } else {
                timerActiveActions.setVisibility(View.GONE);
            }

            timerPickerArea.removeAllViews();
            if (timerPickerOpen) {
                timerPickerArea.setVisibility(View.VISIBLE);

                LinearLayout chipsRow = new LinearLayout(context);
                chipsRow.setOrientation(LinearLayout.HORIZONTAL);
                int[] presets = new int[]{15, 30, 60, 120};
                for (int i = 0; i < presets.length; i++) {
                    int mins = presets[i];
                    TextView chip = buildChip(str("morphe_ghost_play_timer_minutes_chip", mins), false);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    if (i < presets.length - 1) lp.rightMargin = Dim.dp6;
                    chip.setLayoutParams(lp);
                    chip.setGravity(Gravity.CENTER);
                    chip.setOnClickListener(v -> applyTimerMinutes(mins));
                    chipsRow.addView(chip);
                }
                timerPickerArea.addView(chipsRow);

                LinearLayout customRow = new LinearLayout(context);
                customRow.setOrientation(LinearLayout.HORIZONTAL);
                customRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                crLp.topMargin = Dim.dp8;
                customRow.setLayoutParams(crLp);

                EditText customInput = CustomDialog.createEditText(context);
                customInput.setHint(str("morphe_ghost_play_timer_custom_hint"));
                customInput.setInputType(InputType.TYPE_CLASS_NUMBER);
                customInput.setSingleLine(true);
                customInput.setTextSize(13f);
                LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                ciLp.rightMargin = Dim.dp6;
                customInput.setLayoutParams(ciLp);

                TextView setBtn = new TextView(context);
                setBtn.setText(str("morphe_ghost_play_timer_custom_set"));
                setBtn.setTextColor(GhostTheme.onAccent());
                setBtn.setTextSize(12f);
                setBtn.setTypeface(null, Typeface.BOLD);
                setBtn.setPadding(Dim.dp12, Dim.dp8, Dim.dp12, Dim.dp8);
                setBtn.setBackground(GhostTheme.morpheGradientRect(Dim.dp8));
                setBtn.setOnClickListener(v -> {
                    String raw = customInput.getText() == null ? "" : customInput.getText().toString().trim();
                    try {
                        int mins = Integer.parseInt(raw);
                        if (mins >= 1 && mins <= 720) {
                            applyTimerMinutes(mins);
                        } else {
                            Utils.showSnackbarShort(str("morphe_ghost_play_timer_invalid_toast"));
                        }
                    } catch (NumberFormatException ignored) {
                        Utils.showSnackbarShort(str("morphe_ghost_play_timer_invalid_toast"));
                    }
                });

                customRow.addView(customInput);
                customRow.addView(setBtn);
                timerPickerArea.addView(customRow);
            } else {
                timerPickerArea.setVisibility(View.GONE);
            }
        }

        private void applyTimerMinutes(int minutes) {
            timerPickerOpen = false;
            GhostPlayPatch.enableTimedGhostMode(minutes);
            GhostTheme.playTimerNudge(timerIcon);
            Utils.showSnackbarShort(str("morphe_ghost_play_timer_set_toast", minutes));
            renderAll(true);
        }

        private void renderChannel() {
            channelContent.removeAllViews();

            String channelName = VideoInformation.getChannelName();
            String channelId = VideoInformation.getChannelId();
            int totalCount = GhostChannelStore.count();

            ImageView chIcon = new ImageView(context);
            chIcon.setLayoutParams(new LinearLayout.LayoutParams(Dim.dp(22), Dim.dp(22)));
            setDrawable(chIcon, "morphe_ghost_play_channels");
            boolean listed = Utils.isNotEmpty(channelName) && GhostChannelStore.contains(channelId, channelName);
            chIcon.setColorFilter(
                    listed ? GhostTheme.accent() : ThemeUtils.getAppForegroundColor(),
                    PorterDuff.Mode.SRC_IN);
            channelContent.addView(chIcon);

            LinearLayout textCol = new LinearLayout(context);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tcLp.leftMargin = Dim.dp10;
            tcLp.rightMargin = Dim.dp8;
            textCol.setLayoutParams(tcLp);

            TextView title = new TextView(context);
            title.setTextColor(ThemeUtils.getAppForegroundColor());
            title.setTextSize(13.5f);
            title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);

            TextView sub = new TextView(context);
            sub.setTextColor(GhostTheme.mutedText());
            sub.setTextSize(11.5f);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);

            if (Utils.isNotEmpty(channelName)) {
                title.setText(channelName);
                sub.setText(str(listed
                        ? "morphe_ghost_play_quick_channel_state_on"
                        : "morphe_ghost_play_quick_channel_state_off"));
            } else {
                title.setText(str("morphe_ghost_play_manage_channels_title"));
                sub.setText(str("morphe_ghost_play_quick_channel_count", totalCount));
            }
            textCol.addView(title);
            textCol.addView(sub);
            channelContent.addView(textCol);

            if (Utils.isNotEmpty(channelName)) {
                TextView toggleBtn = new TextView(context);
                toggleBtn.setText(listed ? "ON" : "OFF");
                toggleBtn.setTextColor(listed ? GhostTheme.onAccent() : GhostTheme.mutedText());
                toggleBtn.setTextSize(11.5f);
                toggleBtn.setTypeface(null, Typeface.BOLD);
                toggleBtn.setPadding(Dim.dp10, Dim.dp6, Dim.dp10, Dim.dp6);
                toggleBtn.setBackground(listed
                        ? GhostTheme.morpheGradientRect(Dim.dp10)
                        : GhostTheme.roundedRect(Dim.dp10, GhostTheme.subtleSurface()));
                LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tbLp.rightMargin = Dim.dp6;
                toggleBtn.setLayoutParams(tbLp);
                toggleBtn.setOnClickListener(v -> {
                    v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    if (GhostChannelStore.contains(channelId, channelName)) {
                        GhostChannelStore.remove(channelId, channelName);
                        Utils.showSnackbarShort(str("morphe_ghost_play_channel_removed_toast", channelName));
                    } else {
                        GhostChannelStore.ChannelEntry base = new GhostChannelStore.ChannelEntry(
                                channelId != null ? channelId : "",
                                channelName,
                                "", "", "", "", Collections.emptyList(), System.currentTimeMillis()
                        );
                        GhostChannelStore.addOrUpdate(base);
                        GhostPlayPatch.markCurrentVideoIfMatchesChannel();
                        Utils.showSnackbarShort(str("morphe_ghost_play_channel_added_toast", channelName));
                        GhostChannelSearchApi.fetchDetailsAsync(base, updated -> {
                            if (updated != null && GhostChannelStore.contains(updated.channelId(), updated.name())) {
                                GhostChannelStore.addOrUpdate(updated);
                            }
                        });
                    }
                    renderChannel();
                });
                channelContent.addView(toggleBtn);
            }

            TextView manageBtn = new TextView(context);
            manageBtn.setText(str("morphe_ghost_play_quick_channel_manage_btn", totalCount));
            manageBtn.setTextColor(ThemeUtils.getAppForegroundColor());
            manageBtn.setTextSize(11.5f);
            manageBtn.setTypeface(null, Typeface.BOLD);
            manageBtn.setPadding(Dim.dp10, Dim.dp6, Dim.dp10, Dim.dp6);
            manageBtn.setBackground(GhostTheme.outlinedRect(
                    Dim.dp10, GhostTheme.subtleSurface(), Dim.dp(1), GhostTheme.subtleBorder()));
            manageBtn.setOnClickListener(v -> {
                dialog.dismiss();
                GhostChannelManagerDialog.show(context, null);
            });
            channelContent.addView(manageBtn);
        }

        private TextView buildChip(String text, boolean accented) {
            TextView tv = new TextView(context);
            tv.setText(text);
            tv.setTextColor(accented ? GhostTheme.onAccent() : ThemeUtils.getAppForegroundColor());
            tv.setTextSize(12f);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setPadding(Dim.dp10, Dim.dp6, Dim.dp10, Dim.dp6);
            tv.setBackground(accented
                    ? GhostTheme.morpheGradientRect(Dim.dp8)
                    : GhostTheme.roundedRect(Dim.dp8, GhostTheme.subtleSurface()));
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return tv;
        }
    }

    private static void setDrawable(@NonNull ImageView iv, @NonNull String name) {
        int id = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, name);
        if (id != 0) {
            iv.setImageResource(id);
        }
    }

    /**
     * Shows the on-device Ghost Play history dialog using a recycled ListView and live filter.
     */
    public static void showHistoryDialog(@Nullable Context rawContext, @Nullable Runnable onDismiss) {
        try {
            Context context = resolveActivityContext(rawContext);
            if (context == null) return;

            List<GhostHistoryStore.Entry> allEntries = GhostHistoryStore.getAll();
            boolean hasEntries = !allEntries.isEmpty();
            String sizeText = GhostHistoryStore.formatSize(GhostHistoryStore.getApproxSizeBytes());

            Pair<Dialog, LinearLayout> result = CustomDialog.create(
                    context,
                    str("morphe_ghost_play_history_title"),
                    hasEntries
                            ? str("morphe_ghost_play_history_subtitle_size", allEntries.size(), sizeText)
                            : str("morphe_ghost_play_history_empty"),
                    null,
                    str("morphe_ghost_play_close"),
                    () -> {},
                    null,
                    hasEntries ? str("morphe_ghost_play_history_clear_all") : null,
                    hasEntries ? () -> showClearHistoryConfirm(context, onDismiss) : null,
                    true
            );
            Dialog dialog = result.first;
            LinearLayout mainLayout = result.second;
            if (onDismiss != null) {
                dialog.setOnDismissListener(d -> onDismiss.run());
            }

            if (hasEntries) {
                LinearLayout container = new LinearLayout(context);
                container.setOrientation(LinearLayout.VERTICAL);

                EditText filterInput = CustomDialog.createEditText(context);
                filterInput.setHint(str("morphe_ghost_play_history_search_hint"));
                filterInput.setSingleLine(true);
                filterInput.setTextSize(13.5f);
                LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fLp.bottomMargin = Dim.dp8;
                filterInput.setLayoutParams(fLp);
                container.addView(filterInput);

                List<GhostHistoryStore.Entry> filtered = new ArrayList<>(allEntries);
                TextView emptyView = new TextView(context);
                emptyView.setText(str("morphe_ghost_play_history_no_match"));
                emptyView.setTextColor(GhostTheme.mutedText());
                emptyView.setTextSize(13f);
                emptyView.setGravity(Gravity.CENTER);
                emptyView.setPadding(Dim.dp12, Dim.dp20, Dim.dp12, Dim.dp20);
                emptyView.setVisibility(View.GONE);

                HistoryAdapter adapter = new HistoryAdapter(context, filtered);
                ListView listView = new ListView(context);
                listView.setDivider(null);
                listView.setDividerHeight(Dim.dp6);
                listView.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        Math.min(Dim.dp(340), Math.max(Dim.dp(140), allEntries.size() * Dim.dp(74)))
                ));
                listView.setAdapter(adapter);
                listView.setOnItemClickListener((parent, view, position, id) -> {
                    if (position < 0 || position >= filtered.size()) return;
                    GhostHistoryStore.Entry entry = filtered.get(position);
                    dialog.dismiss();
                    GhostPlayPatch.playVideoInGhostMode(entry.videoId(), entry.positionMs());
                });
                listView.setOnItemLongClickListener((parent, view, position, id) -> {
                    if (position < 0 || position >= filtered.size()) return false;
                    GhostHistoryStore.Entry entry = filtered.get(position);
                    GhostHistoryStore.remove(entry.videoId());
                    allEntries.removeIf(e -> e.videoId().equals(entry.videoId()));
                    filtered.remove(position);
                    adapter.notifyDataSetChanged();
                    emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                    Utils.showSnackbarShort(str("morphe_ghost_play_history_item_removed_toast"));
                    return true;
                });

                container.addView(listView);
                container.addView(emptyView);

                filterInput.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    @Override
                    public void afterTextChanged(Editable s) {
                        String q = s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
                        filtered.clear();
                        for (GhostHistoryStore.Entry entry : allEntries) {
                            if (entry.matchesFilter(q)) {
                                filtered.add(entry);
                            }
                        }
                        adapter.notifyDataSetChanged();
                        emptyView.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                    }
                });

                int insertIdx = Math.max(0, mainLayout.getChildCount() - 1);
                mainLayout.addView(container, insertIdx);
            }

            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showHistoryDialog failure", ex);
        }
    }

    private static void showClearHistoryConfirm(@NonNull Context context, @Nullable Runnable onCleared) {
        Pair<Dialog, LinearLayout> confirm = CustomDialog.create(
                context,
                str("morphe_ghost_play_history_clear_confirm_title"),
                str("morphe_ghost_play_history_clear_confirm_message"),
                null,
                str("morphe_ghost_play_history_clear_all"),
                () -> {
                    GhostHistoryStore.clear();
                    Utils.showSnackbarShort(str("morphe_ghost_play_history_cleared_toast"));
                    if (onCleared != null) onCleared.run();
                },
                () -> {},
                null,
                null,
                true
        );
        confirm.first.show();
    }

    private static final class HistoryAdapter extends BaseAdapter {
        private final Context context;
        private final List<GhostHistoryStore.Entry> items;

        HistoryAdapter(Context context, List<GhostHistoryStore.Entry> items) {
            this.context = context;
            this.items = items;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public GhostHistoryStore.Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            View row;
            if (convertView == null) {
                LinearLayout layout = new LinearLayout(context);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(Dim.dp12, Dim.dp10, Dim.dp12, Dim.dp10);
                layout.setBackground(GhostTheme.roundedRect(Dim.dp10, GhostTheme.cardSurface()));

                TextView titleView = new TextView(context);
                titleView.setTextColor(ThemeUtils.getAppForegroundColor());
                titleView.setTextSize(13.5f);
                titleView.setTypeface(null, Typeface.BOLD);
                titleView.setMaxLines(2);
                titleView.setEllipsize(TextUtils.TruncateAt.END);
                layout.addView(titleView);

                TextView metaView = new TextView(context);
                metaView.setTextColor(GhostTheme.mutedText());
                metaView.setTextSize(11.5f);
                metaView.setPadding(0, Dim.dp2, 0, Dim.dp4);
                metaView.setMaxLines(1);
                metaView.setEllipsize(TextUtils.TruncateAt.END);
                layout.addView(metaView);

                LinearLayout track = new LinearLayout(context);
                track.setOrientation(LinearLayout.HORIZONTAL);
                track.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp(3)));
                track.setBackgroundColor(GhostTheme.adjustAlpha(ThemeUtils.getAppForegroundColor(), 0.16f));

                View fill = new View(context);
                fill.setBackgroundColor(GhostTheme.accent());
                track.addView(fill);

                View rest = new View(context);
                track.addView(rest);
                layout.addView(track);

                holder = new RowHolder(titleView, metaView, track, fill, rest);
                layout.setTag(holder);
                row = layout;
            } else {
                row = convertView;
                holder = (RowHolder) convertView.getTag();
            }

            GhostHistoryStore.Entry entry = items.get(position);
            holder.titleView.setText(entry.title().isEmpty() ? entry.videoId() : entry.title());

            StringBuilder meta = new StringBuilder();
            if (!entry.channelName().isEmpty()) {
                meta.append(entry.channelName());
            }
            if (entry.positionMs() > 0L) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(formatDuration(entry.positionMs()));
                if (entry.durationMs() > 0L) {
                    meta.append(" / ").append(formatDuration(entry.durationMs()));
                }
            }
            holder.metaView.setText(meta.toString());

            int pct = entry.progressPercent();
            if (pct > 0) {
                holder.track.setVisibility(View.VISIBLE);
                holder.fill.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, pct));
                holder.rest.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0, 100 - pct)));
            } else {
                holder.track.setVisibility(View.GONE);
            }

            return row;
        }
    }

    private record RowHolder(
            TextView titleView,
            TextView metaView,
            LinearLayout track,
            View fill,
            View rest
    ) {}

    static String formatDuration(long millis) {
        long totalSec = Math.max(0L, millis / 1000L);
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
