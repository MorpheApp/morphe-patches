/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.Episode;
import static app.morphe.extension.youtube.series.TrackerModels.Progress;
import static app.morphe.extension.youtube.series.TrackerModels.Series;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/** Activity-scoped native UI. Catalog pages contain at most 100 views. */
final class LibraryDialog {
    private final Activity activity;
    private final TrackerService service;
    private final LinearLayout root;
    private final TextView heading, addAction, moreAction;
    private final ImageButton backAction;
    private final LinearLayout content;
    private final ScrollView scroll;
    private final LinearLayout searchBar;
    private final EditText search;
    private List<Series> libraryRows = Collections.emptyList();
    private final EpisodeReveal episodeReveal = new EpisodeReveal();
    private int renderGeneration;
    private final List<Dialog> children = new ArrayList<>();
    private final Runnable listener = this::reload;
    private String selected = "";
    private int page, request, restoreScroll = -1;
    private boolean closed, continuing, resumed = true;
    private int continueToken;
    private final Runnable syncTick =
            new Runnable() {
                public void run() {
                    if (!alive()) return;
                    if (resumed && root.hasWindowFocus() && HistoryUi.libraryExposed())
                        service.syncYouTube(false, () -> {});
                    root.postDelayed(this, 30_000);
                }
            };
    private long undoUntil;
    private final Application.ActivityLifecycleCallbacks lifecycle =
            new Application.ActivityLifecycleCallbacks() {
                public void onActivityDestroyed(@NonNull Activity a) {
                    if (a == activity) close();
                }

                public void onActivityCreated(@NonNull Activity a, Bundle b) {}

                public void onActivityStarted(@NonNull Activity a) {}

                public void onActivityResumed(@NonNull Activity a) {
                    if (a == activity) {
                        resumed = true;
                        if (alive() && HistoryUi.libraryExposed())
                            service.syncYouTube(false, () -> {});
                        root.removeCallbacks(syncTick);
                        root.postDelayed(syncTick, 30_000);
                    }
                }

                public void onActivityPaused(@NonNull Activity a) {
                    if (a == activity) {
                        resumed = false;
                        root.removeCallbacks(syncTick);
                        cancelContinue();
                    }
                }

                public void onActivityStopped(@NonNull Activity a) {}

                public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            };

    static LibraryDialog embedded(Activity activity) {
        return new LibraryDialog(activity);
    }

    View view() {
        return root;
    }

    void refresh() {
        reload();
        service.refreshStale();
        if (resumed) service.syncYouTube(false, () -> {});
    }

    void openSeries(String id) {
        hideSearchKeyboard();
        cancelContinue();
        selected = id;
        episodeReveal.request();
        page = 0;
        scroll.scrollTo(0, 0);
        service.acknowledgeEpisodes(id);
        reload();
    }

    String query() {
        return search.getText().toString();
    }

    void setQuery(String query) {
        search.setText(query);
    }

    void hideSearchKeyboard() {
        search.clearFocus();
        InputMethodManager keyboard = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    private void follow() {
        FollowFlow.show(activity, "", this::openSeries);
    }

    String selected() {
        return selected;
    }

    int page() {
        return page;
    }

    int scrollY() {
        return scroll.getScrollY();
    }

    void restore(String id, int value, int y) {
        cancelContinue();
        episodeReveal.cancel();
        selected = id;
        page = Math.max(0, value);
        restoreScroll = Math.max(0, y);
        reload();
    }

    boolean back() {
        if (selected.isEmpty()) return false;
        cancelContinue();
        selected = "";
        episodeReveal.cancel();
        page = 0;
        scroll.scrollTo(0, 0);
        reload();
        return true;
    }

    private LibraryDialog(Activity activity) {
        this.activity = activity;
        service = TrackerService.get(activity);
        TrackerRuntime.flush();
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(HistoryUi.surface());
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(
                HistoryUi.dp(4),
                HistoryUi.dp(4),
                HistoryUi.dp(4),
                HistoryUi.dp(4));
        backAction = new ImageButton(activity);
        backAction.setImageDrawable(
                new UiIcon(
                        UiIcon.BACK, HistoryUi.foreground(), HistoryUi.dp(24)));
        backAction.setBackground(HistoryUi.ripple(false));
        backAction.setOnClickListener(v -> HistoryUi.onBack());
        toolbar.addView(
                backAction,
                new LinearLayout.LayoutParams(
                        HistoryUi.dp(48), HistoryUi.dp(48)));
        heading = new TextView(activity);
        heading.setTextSize(20);
        heading.setMaxLines(1);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        heading.setTextColor(HistoryUi.foreground());
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        toolbar.addView(heading, new LinearLayout.LayoutParams(0, HistoryUi.dp(48), 1));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        addAction = action("morphe_series_tracker_add_icon", this::follow, false);
        toolbar.addView(addAction);
        moreAction = action("morphe_series_tracker_ui_more_options", this::menu, false);
        toolbar.addView(moreAction);
        root.addView(toolbar);
        // Match native History's full-width, 48dp search surface and leading icon.
        root.setFocusableInTouchMode(true);
        searchBar = new LinearLayout(activity);
        searchBar.setGravity(Gravity.CENTER_VERTICAL);
        searchBar.setBackgroundColor(HistoryUi.dark() ? 0xff191919 : 0xfff2f2f2);
        ImageView searchIcon = new ImageView(activity);
        searchIcon.setImageDrawable(
                new UiIcon(
                        UiIcon.SEARCH, HistoryUi.secondary(), HistoryUi.dp(24)));
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchIcon.setPadding(HistoryUi.dp(16), 0, HistoryUi.dp(12), 0);
        searchBar.addView(
                searchIcon,
                new LinearLayout.LayoutParams(
                        HistoryUi.dp(52), HistoryUi.dp(48)));
        search = new EditText(activity);
        search.setSingleLine(true);
        search.setTextSize(16);
        search.setTextColor(HistoryUi.foreground());
        search.setHintTextColor(HistoryUi.secondary());
        search.setHint(UiText.get("morphe_series_tracker_search_series"));
        search.setContentDescription(UiText.get("morphe_series_tracker_search_series"));
        search.setBackground(null);
        search.setPadding(HistoryUi.dp(4), 0, 0, 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        search.setMinHeight(HistoryUi.dp(48));
        searchBar.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton clear = new ImageButton(activity);
        clear.setImageDrawable(new UiIcon(UiIcon.CLOSE, HistoryUi.foreground(), HistoryUi.dp(24)));
        clear.setBackground(HistoryUi.ripple(false));
        clear.setContentDescription(UiText.get("morphe_series_tracker_clear_search"));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> search.setText(""));
        searchBar.addView(
                clear,
                new LinearLayout.LayoutParams(HistoryUi.dp(48), HistoryUi.dp(48)));
        searchBar.setPadding(0, 0, HistoryUi.dp(16), 0);
        root.addView(searchBar, new LinearLayout.LayoutParams(-1, -2));
        content = column();
        scroll = new SeriesScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setClipToPadding(false);
        search.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) {
                return false;
            }
            hideSearchKeyboard();
            return true;
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
                //noinspection SizeReplaceableByIsEmpty
                clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                if (selected.isEmpty() && alive()) {
                    scroll.scrollTo(0, 0);
                    renderLibrary(libraryRows);
                }
            }

            public void afterTextChanged(Editable s) {
            }
        });
        ViewTreeObserver.OnGlobalLayoutListener layout = () -> {
            final int id = ResourceUtils.getIdIdentifier("bottom_bar_container");
            View bar = activity.findViewById(id);
            int padding = HistoryUi.dp(24);
            if (bar != null && bar.isShown()) {
                int[] top = new int[2], bottom = new int[2];
                bar.getLocationOnScreen(top);
                scroll.getLocationOnScreen(bottom);
                padding += Math.max(0, bottom[1] + scroll.getHeight() - top[1]);
            }
            if (scroll.getPaddingBottom() != padding) scroll.setPadding(0, 0, 0, padding);
        };
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            private ViewTreeObserver observer;

            public void onViewAttachedToWindow(View v) {
                observer = root.getViewTreeObserver();
                observer.addOnGlobalLayoutListener(layout);
                activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
                service.listen(listener);
                reload();
                root.post(syncTick);
            }

            public void onViewDetachedFromWindow(View v) {
                cancelContinue();
                if (observer != null && observer.isAlive())
                    observer.removeOnGlobalLayoutListener(layout);
                observer = null;
                activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
                service.unlisten(listener);
                root.removeCallbacks(syncTick);
                for (Dialog child : new ArrayList<>(children)) child.dismiss();
            }
        });
        label(content, UiText.get("morphe_series_tracker_ui_loading_saved_series"), 16);
    }

    private void close() {
        closed = true;
        root.removeCallbacks(syncTick);
        service.unlisten(listener);
        activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
        for (Dialog child : new ArrayList<>(children)) child.dismiss();
        children.clear();
    }

    private boolean alive() {
        return !closed
                && root.isAttachedToWindow()
                && !activity.isDestroyed()
                && !activity.isFinishing();
    }

    private void reload() {
        if (!alive()) return;
        int version = ++request;
        if (selected.isEmpty()) {
            service.library(rows -> {
                        if (alive() && version == request) renderLibrary(rows);
                    },
                    this::readError);
        } else {
            service.series(
                    selected,
                    s -> {
                        if (alive() && version == request) renderSeries(s);
                    },
                    message -> {
                        if (!alive() || version != request) return;
                        selected = "";
                        episodeReveal.cancel();
                        page = 0;
                        error(message);
                        reload();
                    });
        }
    }

    private void startRender(String title) {
        renderGeneration = episodeReveal.beginRender();
        final int y = restoreScroll >= 0 ? restoreScroll : scroll.getScrollY();
        restoreScroll = -1;
        content.removeAllViews();
        heading.setText(title);
        searchBar.setVisibility(selected.isEmpty() ? View.VISIBLE : View.GONE);
        heading.setTextSize(20);
        backAction.setContentDescription(
                UiText.get(selected.isEmpty()
                                ? "morphe_series_tracker_ui_watch_history"
                                : "morphe_series_tracker_ui_all_series"));
        addAction.setVisibility(selected.isEmpty() ? View.VISIBLE : View.GONE);
        moreAction.setVisibility(selected.isEmpty() ? View.VISIBLE : View.GONE);
        episodeReveal.restoreAfterLayout(scroll, y, renderGeneration);
        if (!service.storageError().isEmpty()) {
            label(content, service.storageError(), 14);
            button(
                    content,
                    "morphe_series_tracker_ui_retry_saving",
                    () -> service.retryStorage(this::error));
        }
        if (service.canUndo() && SystemClock.uptimeMillis() < undoUntil) {
            LinearLayout notice = new LinearLayout(activity);
            notice.setGravity(Gravity.CENTER_VERTICAL);
            notice.addView(
                    label(null, "morphe_series_tracker_marked", 14),
                    new LinearLayout.LayoutParams(0, -2, 1));
            notice.addView(action("morphe_series_tracker_undo", () ->
                            service.undo(() -> {
                                        undoUntil = 0;
                                        reload();
                                    },
                                    this::error),
                    false));
            content.addView(notice);
        }
    }

    private void pausedNotice() {
        String status = service.syncStatus();
        if (!status.isEmpty()) label(content, status, 14);
        if (RecordingPrivacy.allowsRecording()) return;
        LinearLayout notice = new LinearLayout(activity);
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.addView(
                label(null, "morphe_series_tracker_tracking_paused", 14),
                new LinearLayout.LayoutParams(0, -2, 1));
        notice.addView(action(
                "morphe_series_tracker_enable_short",
                () -> RecordingPreference.requestEnable(activity, this::reload),
                false));
        content.addView(notice);
    }

    private void renderLibrary(List<Series> rows) {
        libraryRows = rows;
        startRender(UiText.get("morphe_series_tracker_ui_series"));
        if (rows.isEmpty()) {
            addAction.setVisibility(View.GONE);
            moreAction.setVisibility(View.GONE);
            label(content, "morphe_series_tracker_empty_library", 14);
            button(content, "morphe_series_tracker_follow", this::follow);
            return;
        }
        pausedNotice();
        List<Series> ordered = new ArrayList<>();
        String query = searchKey(query());
        for (Series row : rows) if (searchKey(row.name).contains(query)) ordered.add(row);
        if (ordered.isEmpty()) label(content, "morphe_series_tracker_no_search_results", 14);
        ordered.sort(Comparator.comparingInt(
                s -> ResumePlanner.plan(s).kind == ResumePlanner.Kind.CAUGHT_UP ? 1 : 0));
        for (Series s : ordered) {
            ResumePlanner.Plan plan = ResumePlanner.plan(s);
            Episode cover = null;
            for (Episode e : s.episodes)
                if (e.videoId.equals(plan.videoId)) {
                    cover = e;
                    break;
                }
            if (cover == null && !s.episodes.isEmpty()) cover = s.episodes.get(0);
            String subtitle = plan.playable()
                    ? (plan.ordinal > 0
                       ? UiText.format("morphe_series_tracker_episode_position",
                    s.episodeNumber(plan.ordinal),
                    ResumePlanner.time(plan.positionMs))
                       : UiText.format("morphe_series_tracker_saved_position",
                    ResumePlanner.time(plan.positionMs)))
                    : UiText.get(plan.messageKey);
            if (s.newEpisodeCount > 0 && plan.playable() && plan.positionMs == 0)
                subtitle = s.newEpisodeCount == 1
                        ? UiText.get("morphe_series_tracker_new_episode")
                        : UiText.format("morphe_series_tracker_new_episodes", s.newEpisodeCount);
            if (cover != null && cover.available) {
                String releaseAge = EpisodeInfo.releaseAge(activity, cover.videoInfo, s.fetchedAt);
                if (!releaseAge.isEmpty()) subtitle += " · " + releaseAge;
            }
            LinearLayout row = mediaRow(
                    cover == null ? s.bookmarkId : cover.videoId,
                    s.name,
                    "",
                    subtitle,
                    () -> openSeries(s.id),
                    () -> seriesMenu(s),
                    plan.positionMs,
                    cover == null ? 0 : cover.durationMs,
                    true);
            if (plan.playable()) resumeThumbnail((FrameLayout) row.getChildAt(0), s);
            else if (plan.kind == ResumePlanner.Kind.CAUGHT_UP) {
                FrameLayout thumbnail = (FrameLayout) row.getChildAt(0);
                thumbnailBadge(thumbnail, UiIcon.CHECK);
                thumbnail.setContentDescription(
                        s.name + ", " + UiText.get(plan.messageKey));
            }
            content.addView(row);
        }
    }

    private static String searchKey(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    private void thumbnailBadge(FrameLayout thumbnail, int icon) {
        ImageView play = new ImageView(activity);
        play.setImageDrawable(new UiIcon(icon, Color.WHITE, HistoryUi.dp(24)));
        play.setPadding(
                HistoryUi.dp(6),
                HistoryUi.dp(6),
                HistoryUi.dp(6),
                HistoryUi.dp(6));
        play.setBackground(HistoryUi.rounded(0xaa000000, 18));
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        thumbnail.addView(
                play,
                new FrameLayout.LayoutParams(
                        HistoryUi.dp(36),
                        HistoryUi.dp(36),
                        Gravity.CENTER));
    }

    private void resumeThumbnail(FrameLayout thumbnail, Series series) {
        thumbnailBadge(thumbnail, UiIcon.PLAY);
        thumbnail.setForeground(HistoryUi.ripple(false));
        thumbnail.setFocusable(true);
        thumbnail.setContentDescription(UiText.format(
                "morphe_series_tracker_continue_series", series.name));
        thumbnail.setAccessibilityDelegate(
                new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(
                            @NonNull View host, @NonNull AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Button.class.getName());
                    }
                });
        thumbnail.setOnClickListener(v -> continueSeries(series.id));
    }

    private void renderSeries(Series s) {
        startRender(UiText.get("morphe_series_tracker_ui_all_series"));
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.TOP);
        TextView title = label(null, s.name, 20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(
                action("morphe_series_tracker_ui_more_options", () -> seriesMenu(s), false),
                new LinearLayout.LayoutParams(
                        HistoryUi.dp(48), HistoryUi.dp(48)));
        content.addView(header);
        ResumePlanner.Plan plan = ResumePlanner.plan(s);
        label(
                content,
                plan.kind == ResumePlanner.Kind.CAUGHT_UP
                        ? UiText.get(plan.messageKey)
                        : s.complete()
                                ? UiText.format("morphe_series_tracker_watched_count",
                                        s.watchedCount(),
                                        s.playableCount())
                                : UiText.get("morphe_series_tracker_ui_playlist_bookmark"),
                14);
        if (plan.playable()) {
            content.addView(
                    continueButton(() -> continueSeries(s.id)),
                    new LinearLayout.LayoutParams(-2, -2));
        } else if (plan.kind != ResumePlanner.Kind.CAUGHT_UP) label(content, plan.messageKey, 14);
        pausedNotice();
        if (!s.bookmarkId.isEmpty() && (!s.complete() || plan.kind == ResumePlanner.Kind.CHOOSE))
            button(
                    content,
                    "morphe_series_tracker_ui_open_saved_video",
                    () -> play(s, s.bookmarkId, -1, s.progress(s.bookmarkId).positionMs, false));
        if (s.status.equals("loading"))
            label(content, UiText.get("morphe_series_tracker_ui_refreshing_episodes"), 12);
        if (!s.error.isEmpty()) label(content, s.error, 12);
        if (!s.complete()) {
            label(content, "morphe_series_tracker_catalog_unavailable", 14);
            return;
        }
        Switch hide = new Switch(activity);
        hide.setText(UiText.get("morphe_series_tracker_hide_watched"));
        hide.setTextSize(14);
        hide.setTextColor(HistoryUi.foreground());
        hide.setMinHeight(HistoryUi.dp(48));
        hide.setSwitchPadding(HistoryUi.dp(16));
        hide.setChecked(s.hideWatched);
        hide.setOnCheckedChangeListener(
                (button, checked) -> {
                    episodeReveal.cancel();
                    hide.setEnabled(false);
                    service.seriesOptions(
                            s,
                            s.reverseOrder,
                            checked,
                            () -> {
                                page = 0;
                                scroll.scrollTo(0, 0);
                                reload();
                            },
                            message -> {
                                reload();
                                error(message);
                            });
                });
        content.addView(hide, new LinearLayout.LayoutParams(-1, -2));
        List<Episode> available = s.visibleEpisodes();
        int currentIndex = episodeReveal.targetIndex(plan, available);
        if (available.isEmpty()) {
            if (s.playableCount() == 0) label(content, "morphe_series_tracker_no_available_episodes", 14);
            return;
        }
        if (currentIndex >= 0) page = currentIndex / 100;
        page = Math.min(page, (available.size() - 1) / 100);
        int from = page * 100, to = Math.min(available.size(), from + 100);
        if (available.size() > 100)
            label(
                    content,
                    UiText.format("morphe_series_tracker_episode_range",
                            from + 1,
                            to,
                            available.size()),
                    14);
        if (page > 0)
            button(
                    content,
                    "morphe_series_tracker_ui_previous_100",
                    () -> {
                        episodeReveal.cancel();
                        page--;
                        scroll.scrollTo(0, 0);
                        reload();
                    });
        for (int i = from; i < to; i++) {
            Episode e = available.get(i);
            Progress p = s.progress(e.videoId);
            LinearLayout row =
                    mediaRow(
                            e.videoId,
                            UiText.episodeTitle(e),
                            EpisodeInfo.format(activity, e.videoInfo, s.fetchedAt),
                            "",
                            () ->
                                    play(
                                            s,
                                            e.videoId,
                                            e.ordinal,
                                            p.watched() ? 0 : p.positionMs,
                                            p.watched()),
                            () -> episodeMenu(s, e),
                            p.watched() ? e.durationMs : p.positionMs,
                            e.durationMs,
                            false);
            if (p.watched()) {
                FrameLayout thumbnail = (FrameLayout) row.getChildAt(0);
                thumbnailBadge(thumbnail, UiIcon.CHECK);
                thumbnail.setContentDescription(
                        UiText.episodeTitle(e)
                                + ", "
                                + UiText.get("morphe_series_tracker_watched"));
            }
            content.addView(row);
            if (i == currentIndex) episodeReveal.revealAfterLayout(scroll, row, renderGeneration);
        }
        if (to < available.size())
            button(
                    content,
                    "morphe_series_tracker_ui_next_100",
                    () -> {
                        episodeReveal.cancel();
                        page++;
                        scroll.scrollTo(0, 0);
                        reload();
                    });
    }

    private void episodeMenu(Series s, Episode e) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        Progress p = s.progress(e.videoId);
        if (e.available) {
            labels.add(
                    p.watched()
                            ? "morphe_series_tracker_ui_play_again_from_0_00"
                            : UiText.format("morphe_series_tracker_resume_at",
                                    ResumePlanner.time(p.positionMs)));
            actions.add(
                    () ->
                            play(
                                    s,
                                    e.videoId,
                                    e.ordinal,
                                    p.watched() ? 0 : p.positionMs,
                                    p.watched()));
            if (!p.watched() && p.positionMs > 0) {
                labels.add("morphe_series_tracker_ui_restart_from_0_00");
                actions.add(() -> play(s, e.videoId, e.ordinal, 0, true));
            }
        }
        if (e.available) {
            labels.add("morphe_series_tracker_start_here");
            actions.add(
                    () -> {
                        TrackerRuntime.flush();
                        service.startHere(
                                s, e, () -> toast("morphe_series_tracker_start_saved"), this::error);
                    });
        }
        labels.add(
                p.watched()
                        ? "morphe_series_tracker_ui_mark_unwatched"
                        : "morphe_series_tracker_ui_mark_watched");
        actions.add(
                () ->
                        mark(
                                e,
                                p.watched()
                                        ? TrackerModels.Override.UNWATCHED
                                        : TrackerModels.Override.WATCHED));
        if (p.override != TrackerModels.Override.AUTO) {
            labels.add("morphe_series_tracker_ui_use_automatic_status");
            actions.add(() -> mark(e, TrackerModels.Override.AUTO));
        }
        labels.add("morphe_series_tracker_watched_to_here");
        actions.add(
                () -> {
                    TrackerRuntime.flush();
                    service.through(
                            s,
                            e.ordinal,
                            () -> {
                                undoUntil = SystemClock.uptimeMillis() + 8000;
                                reload();
                                root.postDelayed(
                                        () -> {
                                            if (alive()) reload();
                                        },
                                        8100);
                            },
                            this::error);
                });
        choice(
                s.episodeNumber(e.ordinal) + ". " + UiText.episodeTitle(e),
                labels,
                actions);
    }

    private void mark(Episode e, TrackerModels.Override value) {
        TrackerRuntime.flush();
        service.mark(e.videoId, value, () -> toast("morphe_series_tracker_ui_status_saved"), this::error);
    }

    private void cancelContinue() {
        continueToken++;
        continuing = false;
    }

    private void continueSeries(String id) {
        if (continuing) return;
        continuing = true;
        int token = ++continueToken;
        TrackerRuntime.flush();
        service.syncYouTube(
                true,
                () -> {
                    if (!alive() || token != continueToken) {
                        continuing = false;
                        return;
                    }
                    service.series(
                            id,
                            fresh -> {
                                continuing = false;
                                if (!alive() || token != continueToken) return;
                                ResumePlanner.Plan plan = ResumePlanner.plan(fresh);
                                if (plan.playable())
                                    play(fresh, plan.videoId, plan.ordinal, plan.positionMs, false);
                                else {
                                    reload();
                                    toast(plan.messageKey);
                                }
                            },
                            message -> {
                                continuing = false;
                                if (alive()) error(message);
                            });
                });
    }

    private void play(Series s, String id, int ordinal, long position, boolean restart) {
        continueToken++;
        continuing = false;
        TrackerRuntime.prepareLaunch();
        service.select(
                s,
                id,
                ordinal,
                restart,
                () -> {
                    if (!alive()) {
                        TrackerRuntime.cancelLaunch();
                        return;
                    }
                    try {
                        ResumeLauncher.launch(activity, new LaunchRequest(id, s.id, position));
                    } catch (RuntimeException failure) {
                        Logger.printException(() -> "Could not open episode", failure);
                        toast("morphe_series_tracker_open_failed");
                    }
                },
                message -> {
                    TrackerRuntime.cancelLaunch();
                    error(message);
                });
    }

    private void openPlaylist(Series s) {
        try {
            activity.startActivity(
                    new Intent(
                                    Intent.ACTION_VIEW,
                                    new Uri.Builder()
                                            .scheme("https")
                                            .authority("www.youtube.com")
                                            .path("playlist")
                                            .appendQueryParameter("list", s.id)
                                            .build())
                            .setPackage(activity.getPackageName()));
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not open playlist", failure);
            error("morphe_series_tracker_playlist_failed");
        }
    }

    private void seriesMenu(Series s) {
        choice(
                "morphe_series_tracker_ui_series_options",
                Arrays.asList(
                        "morphe_series_tracker_episode_order",
                        "morphe_series_tracker_ui_refresh_episodes",
                        "morphe_series_tracker_ui_rename",
                        "morphe_series_tracker_ui_open_playlist_in_youtube",
                        "morphe_series_tracker_ui_remove_from_library"),
                Arrays.asList(
                        () -> orderMenu(s),
                        () -> service.refresh(s.id, this::error),
                        () -> rename(s),
                        () -> openPlaylist(s),
                        () ->
                                confirm(
                                        UiText.format("morphe_series_tracker_remove_confirm", s.name),
                                        "morphe_series_tracker_remove_detail",
                                        "morphe_series_tracker_ui_remove",
                                        () ->
                                                service.remove(
                                                        s.id,
                                                        () -> {
                                                            selected = "";
                                                            episodeReveal.cancel();
                                                            page = 0;
                                                            reload();
                                                        },
                                                        this::error))));
    }

    private void orderMenu(Series s) {
        NativeSheet sheet = new NativeSheet(activity, "morphe_series_tracker_episode_order");
        RadioGroup options = new RadioGroup(activity);
        String[] labels = {"morphe_series_tracker_playlist_order", "morphe_series_tracker_reverse_order"};
        for (int i = 0; i < labels.length; i++) {
            final boolean reverse = i == 1;
            RadioButton option = new RadioButton(activity);
            option.setText(UiText.get(labels[i]));
            option.setTextColor(HistoryUi.foreground());
            option.setTextSize(16);
            option.setMinHeight(HistoryUi.dp(48));
            option.setChecked(s.reverseOrder == reverse);
            option.setOnClickListener(v ->
                    service.seriesOptions(
                            s,
                            reverse,
                            s.hideWatched,
                            () -> {
                                sheet.dialog.dismiss();
                                episodeReveal.cancel();
                                page = 0;
                                scroll.scrollTo(0, 0);
                                reload();
                            },
                            this::error));
            options.addView(option, new RadioGroup.LayoutParams(-1, -2));
        }
        sheet.body.addView(options);
        sheet.action("morphe_series_tracker_ui_cancel", sheet.dialog::dismiss, false);
        children.add(sheet.dialog);
        sheet.onDismiss(() -> children.remove(sheet.dialog));
        sheet.show();
    }

    private void rename(Series s) {
        NativeSheet sheet = new NativeSheet(activity, "morphe_series_tracker_ui_series_name");
        EditText name = field(sheet.body, "morphe_series_tracker_ui_series_name", s.name);
        sheet.action("morphe_series_tracker_ui_cancel", sheet.dialog::dismiss, false);
        sheet.action(
                "morphe_series_tracker_ui_save",
                () -> service.rename(
                        s.id,
                        name.getText().toString(),
                        sheet.dialog::dismiss,
                        this::error),
                true);
        children.add(sheet.dialog);
        sheet.onDismiss(() -> children.remove(sheet.dialog));
        sheet.show();
    }

    private void menu() {
        choice(
                "morphe_series_tracker_ui_library_options",
                List.of("morphe_series_tracker_ui_clear_all_local_progress"),
                List.of(() -> confirm(
                        "morphe_series_tracker_clear_progress_title",
                        "morphe_series_tracker_clear_progress_message",
                        "morphe_series_tracker_ui_clear_history",
                        () -> service.clearHistory(
                                () -> toast("morphe_series_tracker_ui_local_progress_cleared"),
                                this::error))));
    }

    private void choice(String title, List<String> labels, List<Runnable> actions) {
        if (!alive()) return;
        NativeSheet sheet = new NativeSheet(activity, title);
        sheet.footer.setVisibility(View.GONE);
        for (int i = 0, labelsSize = labels.size(); i < labelsSize; i++) {
            final int index = i;
            TextView item = action(
                            labels.get(i),
                            () -> {
                                sheet.dialog.dismiss();
                                actions.get(index).run();
                            },
                            false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            sheet.body.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        children.add(sheet.dialog);
        sheet.onDismiss(() -> children.remove(sheet.dialog));
        sheet.show();
    }

    private void confirm(String title, String message, String action, Runnable work) {
        if (!alive()) return;
        NativeSheet sheet = new NativeSheet(activity, title);
        sheet.message(message);
        sheet.action("morphe_series_tracker_ui_cancel", sheet.dialog::dismiss, false);
        sheet.action(
                action,
                () -> {
                    sheet.dialog.dismiss();
                    work.run();
                },
                true);
        children.add(sheet.dialog);
        sheet.onDismiss(() -> children.remove(sheet.dialog));
        sheet.show();
    }

    private void readError(String message) {
        if (!alive()) return;
        content.removeAllViews();
        label(content, message, 16);
        button(
                content,
                "morphe_series_tracker_ui_retry_saving",
                () -> {
                    service.retryStorage(this::error);
                    reload();
                });
    }

    private void error(String message) {
        if (alive()) toast(message);
    }

    private void toast(String message) {
        Utils.showToastLong(UiText.get(message));
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        final int p = (int) (16 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(p, p / 2, p, p / 2);
        return box;
    }

    private TextView label(LinearLayout parent, String text, int size) {
        TextView v = new TextView(activity);
        v.setText(UiText.get(text));
        v.setTextSize(size);
        v.setTextColor(size <= 14 ? HistoryUi.secondary() : HistoryUi.foreground());
        v.setPadding(0, HistoryUi.dp(4), 0, HistoryUi.dp(4));
        if (parent != null) parent.addView(v);
        return v;
    }

    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(activity);
        b.setText(UiText.get(text));
        b.setTextSize(14);
        b.setTextColor(HistoryUi.foreground());
        b.setAllCaps(false);
        b.setBackground(HistoryUi.ripple(true));
        b.setMinHeight(HistoryUi.dp(48));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = HistoryUi.dp(8);
        lp.bottomMargin = HistoryUi.dp(8);
        parent.addView(b, lp);
        return b;
    }

    private Button continueButton(Runnable work) {
        Button button = new Button(activity);
        int ink = HistoryUi.surface();
        button.setText(UiText.get("morphe_series_tracker_continue"));
        button.setTextSize(14);
        button.setTextColor(ink);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(HistoryUi.dp(48));
        button.setMinimumHeight(HistoryUi.dp(48));
        button.setStateListAnimator(null);
        button.setBackgroundTintList(null);
        Drawable pill = new RippleDrawable(
                ColorStateList.valueOf(HistoryUi.dark() ? 0x22000000 : 0x33ffffff),
                HistoryUi.rounded(HistoryUi.foreground(), 24),
                HistoryUi.rounded(Color.WHITE, 24));
        // A 36 dp visible pill keeps the full 48 dp touch target.
        button.setBackground(
                new InsetDrawable(pill, 0, HistoryUi.dp(6), 0, HistoryUi.dp(6)));
        button.setPadding(
                HistoryUi.dp(12),
                HistoryUi.dp(8),
                HistoryUi.dp(16),
                HistoryUi.dp(8));
        button.setCompoundDrawablesRelative(
                new UiIcon(UiIcon.PLAY, ink, HistoryUi.dp(20)), null, null, null);
        button.setCompoundDrawablePadding(HistoryUi.dp(6));
        button.setOnClickListener(v -> work.run());
        return button;
    }

    private TextView action(String title, Runnable work, boolean pill) {
        TextView v = NativeSheet.button(activity, title, work, pill);
        v.setMinWidth(HistoryUi.dp(48));
        if (title.equals("morphe_series_tracker_ui_more_options")
                || title.equals("morphe_series_tracker_add_icon")) {
            v.setText("");
            v.setPadding(HistoryUi.dp(12), 0, HistoryUi.dp(12), 0);
            v.setCompoundDrawables(
                    new UiIcon(
                            title.equals("morphe_series_tracker_add_icon") ? UiIcon.ADD : UiIcon.MORE,
                            HistoryUi.foreground(),
                            HistoryUi.dp(24)),
                    null,
                    null,
                    null);
            v.setContentDescription(
                    UiText.get(title.equals("morphe_series_tracker_add_icon")
                                    ? "morphe_series_tracker_follow"
                                    : title));
        }
        return v;
    }

    private LinearLayout mediaRow(
            String id,
            String title,
            String metadata,
            String subtitle,
            Runnable primary,
            Runnable more,
            long position,
            long duration,
            boolean compact) {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, HistoryUi.dp(compact ? 8 : 12), 0, HistoryUi.dp(8));
        FrameLayout thumbnail = new FrameLayout(activity);
        final int width = compact
                ? 120
                : Math.min(128, (int) (activity.getResources().getDisplayMetrics().widthPixels
                                       / activity.getResources().getDisplayMetrics().density * .34));
        row.addView(thumbnail,
                new LinearLayout.LayoutParams(HistoryUi.dp(width), HistoryUi.dp(width * 9f / 16)));
        thumbnail.setBackground(HistoryUi.rounded(HistoryUi.control(), 8));
        thumbnail.setClipToOutline(true);
        ImageView picture = new ImageView(activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumbnail.addView(picture, new FrameLayout.LayoutParams(-1, -1));
        ThumbnailLoader.load(picture, id);
        if (duration > 0 && !compact) {
            TextView badge = label(null, ResumePlanner.time(duration), 12);
            badge.setTextColor(0xfff1f1f1);
            badge.setBackground(HistoryUi.rounded(0xbb000000, 4));
            badge.setPadding(HistoryUi.dp(4), 0, HistoryUi.dp(4), 0);
            FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                    -2, -2, Gravity.BOTTOM | Gravity.END);
            bp.setMargins(0, 0, HistoryUi.dp(4), HistoryUi.dp(6));
            thumbnail.addView(badge, bp);
        }
        if (position > 0 && duration > 0) {
            View bar = new View(activity);
            bar.setBackgroundColor(0xffff0033);
            thumbnail.addView(
                    bar,
                    new FrameLayout.LayoutParams(
                            HistoryUi.dp(width * Math.min(1f, (float) position / duration)),
                            HistoryUi.dp(3),
                            Gravity.BOTTOM));
        }
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(HistoryUi.dp(12), 0, 0, 0);
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = label(info, title, compact ? 16 : 14);
        name.setTextColor(HistoryUi.foreground());
        name.setPadding(0, 0, 0, 0);
        name.setMaxLines(compact ? 2 : 3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        if (!metadata.isEmpty()) {
            TextView stats = label(info, metadata, 12);
            stats.setMaxLines(2);
            stats.setEllipsize(TextUtils.TruncateAt.END);
        }
        if (!subtitle.isEmpty()) label(info, subtitle, 12);
        TextView menu = action("morphe_series_tracker_ui_more_options", more, false);
        LinearLayout.LayoutParams mp =
                new LinearLayout.LayoutParams(
                        HistoryUi.dp(48), HistoryUi.dp(48));
        menu.setMinWidth(0);
        menu.setPadding(HistoryUi.dp(12), 0, 0, 0);
        row.addView(menu, mp);
        thumbnail.setOnClickListener(v -> primary.run());
        info.setOnClickListener(v -> primary.run());
        info.setBackground(HistoryUi.ripple(false));
        info.setFocusable(true);
        thumbnail.setContentDescription(title);
        return row;
    }

    private EditText field(LinearLayout parent, String title, String value) {
        label(parent, title, 14);
        EditText edit = new EditText(activity);
        edit.setSingleLine();
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setContentDescription(UiText.get(title));
        edit.setText(value);
        parent.addView(edit);
        return edit;
    }

    private void divider(LinearLayout parent) {
        View line = new View(activity);
        line.setBackgroundColor(0x40888888);
        parent.addView(line, new LinearLayout.LayoutParams(-1, 1));
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
}
