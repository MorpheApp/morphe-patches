package app.morphe.extension.youtube.series;

import android.app.Activity;
import android.view.View;
import android.widget.*;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** The player stays in place while public matches are found and explicitly selected. */
final class PlaylistDiscoverySheet {
    private final Activity activity;
    private final String video;
    private final Consumer<String> done;
    private final NativeSheet sheet;
    private Future<?> request;
    private boolean finished;

    static void show(Activity activity, String video, Consumer<String> done) {
        new PlaylistDiscoverySheet(activity, video, done, null);
    }

    static void saved(
            Activity activity,
            String video,
            Consumer<String> done,
            List<PlaylistDiscovery.Match> matches) {
        new PlaylistDiscoverySheet(activity, video, done, matches);
    }

    private PlaylistDiscoverySheet(
            Activity activity,
            String video,
            Consumer<String> done,
            List<PlaylistDiscovery.Match> saved) {
        this.activity = activity;
        this.video = video;
        this.done = done;
        sheet = new NativeSheet(activity, "series_tracker_follow");
        sheet.message("series_tracker_finding_playlists");
        sheet.action("series_tracker_ui_cancel", this::dismiss, false);
        sheet.action("series_tracker_paste_playlist", () -> choose(""), false);
        sheet.onDismiss(
                () -> {
                    finished = true;
                    if (request != null) request.cancel(true);
                });
        sheet.show();
        if (saved != null) {
            results(saved);
            return;
        }
        request =
                TrackerService.get(activity)
                        .discover(
                                video,
                                this::results,
                                ignored -> results(java.util.Collections.emptyList()));
    }

    private boolean active() {
        return !finished
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && video.equals(PlayerSeriesAction.activeVideo());
    }

    private void dismiss() {
        finished = true;
        if (request != null) request.cancel(true);
        sheet.dialog.dismiss();
    }

    private void choose(String playlist) {
        if (!active()) {
            dismiss();
            return;
        }
        dismiss();
        FollowFlow.fromPlayer(activity, playlist, video, done);
    }

    private void results(List<PlaylistDiscovery.Match> matches) {
        if (!active()) {
            dismiss();
            return;
        }
        if (matches.size() == 1) {
            choose(matches.get(0).id);
            return;
        }
        if (matches.isEmpty()) {
            choose("");
            return;
        }
        sheet.body.removeAllViews();
        for (PlaylistDiscovery.Match match : matches) {
            LinearLayout row = new LinearLayout(activity);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, HistoryUi.dp(activity, 8), 0, HistoryUi.dp(activity, 8));
            row.setBackground(HistoryUi.ripple(activity, false));
            row.setFocusable(true);
            row.setContentDescription(
                    match.title + (match.owner.isEmpty() ? "" : ", " + match.owner));
            ImageView thumbnail = new ImageView(activity);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(
                    thumbnail,
                    new LinearLayout.LayoutParams(
                            HistoryUi.dp(activity, 96), HistoryUi.dp(activity, 54)));
            ThumbnailLoader.load(thumbnail, match.thumbnailVideo);
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(HistoryUi.dp(activity, 12), 0, 0, 0);
            labels.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            TextView title = NativeSheet.text(activity, match.title, 16);
            title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(title);
            if (!match.owner.isEmpty()) {
                TextView owner = NativeSheet.text(activity, match.owner, 12);
                owner.setTextColor(HistoryUi.secondary(activity));
                owner.setMaxLines(1);
                labels.addView(owner);
            }
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> choose(match.id));
            sheet.body.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
