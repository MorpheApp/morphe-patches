/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.function.Consumer;

/** One follow action resolves the catalog and its title. No rename or manual loading step. */
final class FollowFlow {
    private final Activity activity;
    private final TrackerService service;
    private final Consumer<String> done;
    private final NativeSheet sheet;
    private final EditText link;
    private final TextView status, follow;
    private final CheckBox record;
    private boolean busy, finished;
    private final long consentGeneration;
    private java.util.concurrent.Future<?> preview;
    private final String startVideo;
    private String id = "";
    private CatalogClient.Catalog catalog;
    private CheckBox watchedBefore;
    private TextView order, resume;
    private boolean reverse, orderChosen;

    static void show(Activity activity, String playlist, Consumer<String> done) {
        new FollowFlow(activity, playlist, "", done);
    }

    static void fromPlayer(
            Activity activity, String playlist, String video, Consumer<String> done) {
        new FollowFlow(activity, playlist, video, done);
    }

    private FollowFlow(Activity activity, String playlist, String video, Consumer<String> done) {
        startVideo = video;
        this.activity = activity;
        this.done = done;
        service = TrackerService.get(activity);
        sheet = new NativeSheet(activity, "morphe_series_tracker_follow");
        if (playlist.isEmpty()) {
            link = new EditText(activity);
            link.setSingleLine();
            link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            link.setTextColor(HistoryUi.foreground());
            link.setHintTextColor(HistoryUi.secondary());
            link.setHint(UiText.get("morphe_series_tracker_ui_playlist_link_or_id"));
            link.setContentDescription(
                    UiText.get("morphe_series_tracker_ui_playlist_link_or_id"));
            sheet.body.addView(link);
        } else {
            link = null;
            id = PlaylistInput.parse(playlist);
        }
        boolean explain =
                !activity.getSharedPreferences("morphe_series_tracker_ui", Context.MODE_PRIVATE)
                        .getBoolean("follow_explained", false);
        if (explain) sheet.message("morphe_series_tracker_follow_consent");
        if (!RecordingPrivacy.allowsRecording() && (explain || !startVideo.isEmpty())) {
            record = new CheckBox(activity);
            record.setText(UiText.get("morphe_series_tracker_record_short"));
            record.setTextColor(HistoryUi.foreground());
            record.setChecked(false);
            record.setEnabled(RecordingPrivacy.canEnable());
            sheet.body.addView(record);
        } else record = null;
        consentGeneration = RecordingPrivacy.generation();
        status = sheet.message("");
        status.setVisibility(android.view.View.GONE);
        sheet.action("morphe_series_tracker_ui_cancel", sheet.dialog::dismiss, false);
        follow = sheet.action("morphe_series_tracker_follow", this::follow, true);
        sheet.show();
        sheet.onDismiss(
                () -> {
                    finished = true;
                    if (preview != null) preview.cancel(true);
                });
        if (link == null && (!explain || !startVideo.isEmpty())) follow();
    }

    private boolean active() {
        return !finished
                && sheet.dialog.isShowing()
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    private void follow() {
        if (busy || !active()) return;
        if (link != null) {
            String parsed = PlaylistInput.parseOrNull(link.getText().toString());
            if (parsed == null) {
                failure("morphe_series_tracker_error_playlist_input");
                return;
            }
            id = parsed;
        }
        catalog = null;
        busy = true;
        follow.setEnabled(false);
        if (link != null) link.setEnabled(false);
        status.setVisibility(android.view.View.VISIBLE);
        status.setText(UiText.get("morphe_series_tracker_ui_loading_episodes"));
        service.library(
                rows -> {
                    if (!active()) return;
                    for (TrackerModels.Series s : rows)
                        if (s.id.equals(id)) {
                            if (startVideo.isEmpty()) complete();
                            else {
                                sheet.dialog.dismiss();
                                HistoryUi.openSeries(activity, id);
                            }
                            return;
                        }
                    preview = service.preview(
                            id,
                            value -> {
                                if (active()) {
                                    catalog = value;
                                    if (!orderChosen)
                                        reverse =
                                                EpisodeOrder.infer(value.episodes)
                                                        == EpisodeOrder.Direction.REVERSE;
                                    if (startVideo.isEmpty()) save();
                                    else if (value.episodes.stream()
                                            .noneMatch(e -> e.videoId.equals(startVideo))) {
                                        failure("morphe_series_tracker_video_not_in_playlist");
                                    } else {
                                        busy = false;
                                        startingOptions();
                                        status.setText(
                                                value.title.isEmpty() ? id : value.title);
                                        follow.setEnabled(true);
                                        follow.setOnClickListener(
                                                v -> {
                                                    if (!busy) {
                                                        busy = true;
                                                        follow.setEnabled(false);
                                                        save();
                                                    }
                                                });
                                    }
                                }
                            },
                            message -> {
                                if (active()) {
                                    if (!startVideo.isEmpty()) {
                                        failure(message);
                                        return;
                                    }
                                    busy = false;
                                    status.setText(UiText.get("morphe_series_tracker_follow_unavailable"));
                                    follow.setText(UiText.get("morphe_series_tracker_ui_save_bookmark"));
                                    follow.setEnabled(true);
                                    follow.setOnClickListener(v -> {
                                        if (!busy) {
                                            busy = true;
                                            follow.setEnabled(false);
                                            save();
                                        }
                                    });
                                }
                            });
                },
                this::failure);
    }

    private void save() {
        if (record != null && record.isChecked() && !RecordingPrivacy.enable(consentGeneration)) {
            failure("morphe_series_tracker_identity_unavailable");
            return;
        }
        String name = catalog != null && !catalog.title.isEmpty() ? catalog.title : id;
        Runnable saved = () -> {
            activity.getSharedPreferences("morphe_series_tracker_ui", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("follow_explained", true)
                    .apply();
            if (active()) complete();
        };
        if (startVideo.isEmpty()) {
            service.saveFollow(
                    id,
                    name,
                    catalog,
                    new FollowStart("", -1, reverse, false),
                    -1,
                    saved,
                    this::failure);
        } else {
            final long generation = RecordingPrivacy.generation();
            PlaybackBridge.Source source = PlaybackBridge.source();
            long position = RecordingPrivacy.acceptsManualPosition(generation)
                            ? FollowStart.position(source, startVideo)
                            : -1;
            if (source != PlaybackBridge.source()) position = -1;
            service.saveFollow(
                    id,
                    name,
                    catalog,
                    new FollowStart(
                            startVideo,
                            position,
                            reverse,
                            watchedBefore != null && watchedBefore.isChecked()),
                    generation,
                    saved,
                    this::failure);
        }
    }

    private void startingOptions() {
        if (watchedBefore == null) {
            resume = sheet.message("");
            order = NativeSheet.button(
                    activity, "morphe_series_tracker_playlist_order", this::chooseOrder, false);
            order.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            order.setPadding(0, HistoryUi.dp(6), 0, HistoryUi.dp(6));
            order.setCompoundDrawablesRelative(
                    null,
                    null,
                    new UiIcon(
                            UiIcon.EXPAND,
                            HistoryUi.foreground(),
                            HistoryUi.dp(20)),
                    null);
            order.setCompoundDrawablePadding(HistoryUi.dp(8));
            order.setMaxLines(2);
            order.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sheet.body.addView(order);
            watchedBefore = new CheckBox(activity);
            watchedBefore.setText(UiText.get("morphe_series_tracker_watch_previous"));
            watchedBefore.setTextColor(HistoryUi.foreground());
            watchedBefore.setTextSize(14);
            watchedBefore.setMinHeight(HistoryUi.dp(48));
            watchedBefore.setChecked(false);
            sheet.body.addView(watchedBefore);
        }
        order.setVisibility(android.view.View.VISIBLE);
        long position =
                RecordingPrivacy.canEnable()
                        ? FollowStart.position(PlaybackBridge.source(), startVideo)
                        : -1;
        resume.setVisibility(position >= 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (position >= 0) {
            resume.setText(UiText.format(
                    "morphe_series_tracker_resume_at", ResumePlanner.time(position)));
        }
        updateOrder();
    }

    private void updateOrder() {
        TrackerModels.Episode first = EpisodeOrder.first(catalog.episodes, reverse);
        order.setText(UiText.format("morphe_series_tracker_order_starts_with",
                first == null ? "" : UiText.episodeTitle(first)));
        final boolean previous = !FollowStart.previous(catalog.episodes, startVideo, reverse).isEmpty();
        watchedBefore.setVisibility(previous ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!previous) watchedBefore.setChecked(false);
    }

    // Joins two already localized texts on separate lines.
    @SuppressLint("SetTextI18n")
    private void chooseOrder() {
        if (busy || catalog == null) return;
        NativeSheet choices = new NativeSheet(activity, "morphe_series_tracker_episode_order");
        String[] labels = {"morphe_series_tracker_playlist_order", "morphe_series_tracker_reverse_order"};
        for (int i = 0, length = labels.length; i < length; i++) {
            final boolean selected = i == 1;
            RadioButton option = new RadioButton(activity);
            TrackerModels.Episode first = EpisodeOrder.first(catalog.episodes, selected);
            option.setText(UiText.get(labels[i])
                    + (first == null ? "" : "\n" + UiText.episodeTitle(first)));
            option.setMaxLines(3);
            option.setEllipsize(android.text.TextUtils.TruncateAt.END);
            option.setPadding(0, HistoryUi.dp(8), 0, HistoryUi.dp(8));
            option.setTextColor(HistoryUi.foreground());
            option.setTextSize(16);
            option.setMinHeight(HistoryUi.dp(48));
            option.setChecked(reverse == selected);
            option.setOnClickListener(v -> {
                reverse = selected;
                orderChosen = true;
                updateOrder();
                choices.dialog.dismiss();
            });
            choices.body.addView(option);
        }
        choices.action("morphe_series_tracker_ui_cancel", choices.dialog::dismiss, false);
        choices.show();
    }

    private void complete() {
        sheet.dialog.dismiss();
        done.accept(id);
    }

    private void failure(String message) {
        if (!active()) return;
        busy = false;
        catalog = null;
        if (watchedBefore != null) {
            watchedBefore.setChecked(false);
            watchedBefore.setVisibility(android.view.View.GONE);
            order.setVisibility(android.view.View.GONE);
            resume.setVisibility(android.view.View.GONE);
        }
        follow.setText(UiText.get("morphe_series_tracker_follow"));
        follow.setOnClickListener(v -> follow());
        follow.setEnabled(true);
        if (link != null) link.setEnabled(true);
        status.setVisibility(android.view.View.VISIBLE);
        status.setText(UiText.get(message));
    }
}
