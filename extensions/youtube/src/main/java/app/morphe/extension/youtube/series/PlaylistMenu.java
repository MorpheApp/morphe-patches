package app.morphe.extension.youtube.series;

import android.app.Activity;
import android.view.*;

import java.lang.ref.WeakReference;

/** Adds Follow series to the native playlist toolbar. */
public final class PlaylistMenu {
    public interface ToolbarSource {
        Menu seriesTrackerMenu();
    }

    private static final int ITEM_ID = 0x53745231;
    private static WeakReference<View> page = new WeakReference<>(null);
    private static WeakReference<Menu> menu = new WeakReference<>(null);
    private static String playlist = "";

    static void bind(View view, String browseId) {
        String id = browseId != null && browseId.startsWith("VL") ? browseId.substring(2) : "";
        if (!PlaylistInput.suggestible(id)) return;
        view.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    ViewTreeObserver observer;
                    final ViewTreeObserver.OnGlobalLayoutListener layout =
                            () -> {
                                if (page.get() == view) update();
                            };

                    public void onViewAttachedToWindow(View v) {
                        page = new WeakReference<>(v);
                        playlist = id;
                        update();
                        observer = v.getViewTreeObserver();
                        observer.addOnGlobalLayoutListener(layout);
                    }

                    public void onViewDetachedFromWindow(View v) {
                        if (observer != null && observer.isAlive())
                            observer.removeOnGlobalLayoutListener(layout);
                        observer = null;
                        if (page.get() == v) {
                            page.clear();
                            playlist = "";
                            update();
                        }
                    }
                });
    }

    private static Menu toolbarMenu(View view) {
        int id =
                view.getResources()
                        .getIdentifier("toolbar", "id", view.getContext().getPackageName());
        for (View node = view;
                node != null;
                node = node.getParent() instanceof View ? (View) node.getParent() : null) {
            View toolbar = node.findViewById(id);
            if (toolbar instanceof ToolbarSource)
                return ((ToolbarSource) toolbar).seriesTrackerMenu();
        }
        return null;
    }

    private static void update() {
        View view = page.get();
        Menu previous = menu.get();
        if (view == null
                || !view.isAttachedToWindow()
                || !view.isShown()
                || !HistoryUi.pageExposed()) {
            if (previous != null && previous.findItem(ITEM_ID) != null)
                previous.removeItem(ITEM_ID);
            return;
        }
        Menu target = toolbarMenu(view);
        if (target == null) return;
        if (previous != null && previous != target) previous.removeItem(ITEM_ID);
        menu = new WeakReference<>(target);
        if (target.findItem(ITEM_ID) != null) return;
        Activity activity = HistoryUi.activity(view.getContext());
        if (activity == null) return;
        String id = playlist;
        MenuItem item = target.add(0, ITEM_ID, 0, UiText.get(activity, "series_tracker_follow"));
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        item.setOnMenuItemClickListener(
                clicked -> {
                    FollowFlow.show(
                            activity, id, selected -> HistoryUi.openSeries(activity, selected));
                    return true;
                });
        TrackerService.get(activity)
                .library(
                        rows -> {
                            if (page.get() != view
                                    || menu.get() != target
                                    || target.findItem(ITEM_ID) != item) return;
                            for (TrackerModels.Series s : rows)
                                if (s.id.equals(id)) {
                                    item.setTitle(
                                            UiText.get(activity, "series_tracker_view_series"));
                                    item.setOnMenuItemClickListener(
                                            clicked -> {
                                                HistoryUi.openSeries(activity, id);
                                                return true;
                                            });
                                    break;
                                }
                        },
                        message -> {});
    }

    private PlaylistMenu() {}
}
