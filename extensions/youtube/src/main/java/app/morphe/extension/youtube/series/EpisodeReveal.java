package app.morphe.extension.youtube.series;

import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ScrollView;

import java.util.List;

/** A one-time navigation intent; ordinary refreshes keep the reader's scroll position. */
final class EpisodeReveal {
    private boolean requested;
    private int generation;

    void request() {
        requested = true;
        generation++;
    }

    void cancel() {
        requested = false;
        generation++;
    }

    int beginRender() {
        return ++generation;
    }

    int targetIndex(ResumePlanner.Plan plan, List<TrackerModels.Episode> visible) {
        if (!requested) return -1;
        if (!plan.playable()) {
            requested = false;
            return -1;
        }
        for (int i = 0; i < visible.size(); i++) {
            TrackerModels.Episode episode = visible.get(i);
            if (episode.ordinal == plan.ordinal && episode.videoId.equals(plan.videoId)) return i;
        }
        // A filtered, unavailable or ambiguous episode must not trigger a later surprise jump.
        requested = false;
        return -1;
    }

    void restoreAfterLayout(ScrollView scroll, int y, int render) {
        afterLayout(scroll, render, () -> scroll.scrollTo(0, y));
    }

    void revealAfterLayout(ScrollView scroll, View row, int render) {
        afterLayout(
                scroll,
                render,
                () -> {
                    if (!requested || !row.isAttachedToWindow()) return;
                    requested = false;
                    scroll.scrollTo(0, row.getTop());
                });
    }

    private void afterLayout(ScrollView scroll, int render, Runnable work) {
        ViewTreeObserver observer = scroll.getViewTreeObserver();
        observer.addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
                        if (generation == render && scroll.isAttachedToWindow() && scroll.isShown())
                            work.run();
                        return true;
                    }
                });
    }
}
