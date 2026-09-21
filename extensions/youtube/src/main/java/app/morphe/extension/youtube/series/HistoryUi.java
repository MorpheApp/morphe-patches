package app.morphe.extension.youtube.series;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;

import java.lang.ref.WeakReference;

/** Switches between the complete native History page and the compact Series page. */
public final class HistoryUi extends LinearLayout {
    private static WeakReference<HistoryUi> current = new WeakReference<>(null);
    private static long openSeriesUntil;
    private static String openSeriesId = "";
    private final View watch;
    private final FrameLayout pane;
    private final TextView watchTab, seriesTab;
    private final HistorySwitcher switcher;
    private LibraryDialog library;
    private boolean series;
    private BackRegistration backRegistration;
    private ViewTreeObserver observer;
    // App-bar offsets can move the browse pane without causing a global layout pass.
    private final ViewTreeObserver.OnPreDrawListener layout =
            () -> {
                updateLayout();
                return true;
            };

    static int dp(Context c, float n) {
        return app.morphe.extension.shared.ui.Dim.dp(n);
    }

    static boolean dark(Context c) {
        return app.morphe.extension.shared.Utils.isDarkModeEnabled();
    }

    static int foreground(Context c) {
        return app.morphe.extension.shared.theme.ThemeUtils.getAppForegroundColor();
    }

    static int secondary(Context c) {
        return dark(c) ? 0xffaaaaaa : 0xff606060;
    }

    static int surface(Context c) {
        return app.morphe.extension.shared.theme.ThemeUtils.getAppBackgroundColor();
    }

    static int control(Context c) {
        return dark(c) ? 0xff272727 : 0xfff2f2f2;
    }

    static GradientDrawable rounded(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    static android.graphics.drawable.Drawable ripple(Context c, boolean pill) {
        return new RippleDrawable(
                android.content.res.ColorStateList.valueOf(dark(c) ? 0x33ffffff : 0x22000000),
                pill ? rounded(c, control(c), 24) : null,
                rounded(c, Color.WHITE, pill ? 24 : 4));
    }

    static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            Context n = ((ContextWrapper) c).getBaseContext();
            if (n == c) break;
            c = n;
        }
        return null;
    }

    public static void openSeries(Context c, String id) {
        openSeriesId = id;
        // A retained History view may still report isShown() beneath a native playlist.
        // Explicit playlist actions must navigate before selecting the series.
        navigate(c);
    }

    static boolean libraryExposed() {
        HistoryUi ui = current.get();
        return ui != null && ui.series && ui.isShown() && pageExposed();
    }

    public static void open(Context c) {
        HistoryUi visible = current.get();
        if (visible != null && visible.isShown() && pageExposed()) {
            visible.select(true);
            return;
        }
        navigate(c);
    }

    private static void navigate(Context c) {
        openSeriesUntil = android.os.SystemClock.uptimeMillis() + 15000;
        // YouTube deduplicates repeated History intents even after leaving the page.
        // A local fragment distinguishes requests without changing the History endpoint.
        Uri destination =
                Uri.parse("https://www.youtube.com/feed/history")
                        .buildUpon()
                        .fragment("series-tracker-" + java.util.UUID.randomUUID())
                        .build();
        try {
            c.startActivity(
                    new Intent(Intent.ACTION_VIEW, destination)
                            .setPackage(c.getPackageName())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            openSeriesUntil = 0;
            openSeriesId = "";
            Toast.makeText(
                            c,
                            UiText.get(
                                    c,
                                    "series_tracker_ui_open_history_from_you_to_view_your_series"),
                            Toast.LENGTH_LONG)
                    .show();
        }
    }

    public static View wrap(View view, String browseId) {

        PlaylistMenu.bind(view, browseId);
        if (!"FEhistory".equals(browseId) || activity(view.getContext()) == null) return view;
        return new HistoryUi(view);
    }

    private HistoryUi(View view) {
        super(view.getContext());
        setId(
                getResources()
                        .getIdentifier(
                                "series_tracker_history_root",
                                "id",
                                getContext().getPackageName()));
        watch = view;
        setOrientation(VERTICAL);
        setBackgroundColor(surface(getContext()));
        FrameLayout tabs = new FrameLayout(getContext());
        tabs.setPadding(
                dp(getContext(), 16), dp(getContext(), 4),
                dp(getContext(), 16), dp(getContext(), 4));
        watchTab = tab(UiText.get(getContext(), "series_tracker_ui_watch_history"), false);
        seriesTab = tab(UiText.get(getContext(), "series_tracker_ui_series"), true);
        switcher = new HistorySwitcher(getContext(), watchTab, seriesTab);
        tabs.addView(switcher, new FrameLayout.LayoutParams(-1, -2));
        pane = new FrameLayout(getContext());
        addView(pane, new LayoutParams(-1, 0, 1));
        addView(tabs, new LayoutParams(-1, -2));
        pane.addView(watch, new FrameLayout.LayoutParams(-1, -1));
        watchTab.setOnClickListener(v -> select(false));
        seriesTab.setOnClickListener(
                v -> {
                    // Reselecting the active tab returns to its root, like native navigation.
                    if (series && library != null && library.back()) return;
                    select(true);
                });
        select(android.os.SystemClock.uptimeMillis() < openSeriesUntil);
        openSeriesUntil = 0;
        if (library != null && !openSeriesId.isEmpty()) {
            library.openSeries(openSeriesId);
            openSeriesId = "";
        }
        addOnAttachStateChangeListener(
                new OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View v) {
                        current = new WeakReference<>(HistoryUi.this);
                        observer = getViewTreeObserver();
                        observer.addOnPreDrawListener(layout);
                        updateLayout();
                    }

                    public void onViewDetachedFromWindow(View v) {
                        if (observer != null && observer.isAlive())
                            observer.removeOnPreDrawListener(layout);
                        observer = null;
                        if (backRegistration != null) backRegistration.release();
                        if (current.get() == HistoryUi.this) current.clear();
                    }
                });
    }

    private TextView tab(String title, boolean collection) {
        TextView t = new HistorySwitcher.Label(getContext(), collection);
        t.setText(title);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", 0));
        Context c = getContext();
        t.setBackground(
                new RippleDrawable(
                        android.content.res.ColorStateList.valueOf(0x227f7f7f),
                        null,
                        rounded(c, Color.WHITE, 22)));
        t.setPaddingRelative(dp(c, 38), dp(c, 10), dp(c, 12), dp(c, 10));
        t.setMinHeight(dp(c, 48));
        t.setMaxLines(2);
        t.setFocusable(true);
        return t;
    }

    private void select(boolean selected) {
        if (!selected && library != null) library.hideSearchKeyboard();
        series = selected;
        if (selected && library == null) {
            library = LibraryDialog.embedded(activity(getContext()));
            pane.addView(library.view(), new FrameLayout.LayoutParams(-1, -1));
        }
        watch.setVisibility(selected ? GONE : VISIBLE);
        if (library != null) library.view().setVisibility(selected ? VISIBLE : GONE);
        watchTab.setSelected(!selected);
        seriesTab.setSelected(selected);
        switcher.select(selected);
        if (selected && library != null) library.refresh();
        updateBack();
    }

    private void updateLayout() {
        updateBack();
        if (!isAttachedToWindow() || getHeight() == 0) return;
        int[] position = new int[2];
        getLocationOnScreen(position);
        int bottom = position[1] + getHeight();
        View window = getRootView();
        window.getLocationOnScreen(position);
        int availableBottom = position[1] + window.getHeight();
        WindowInsets insets = getRootWindowInsets();
        if (insets != null) availableBottom -= insets.getStableInsetBottom();
        Activity activity = activity(getContext());
        int id =
                getResources()
                        .getIdentifier("bottom_bar_container", "id", getContext().getPackageName());
        View navigation = activity == null || id == 0 ? null : activity.findViewById(id);
        if (navigation != null && navigation.isShown()) {
            navigation.getLocationOnScreen(position);
            availableBottom = Math.min(availableBottom, position[1]);
        }
        // The native browse pane extends behind bottom navigation. Keep the fixed switcher
        // above that overlay, and leave the whole remaining pane available to either page.
        int covered = Math.min(getHeight(), Math.max(0, bottom - availableBottom));
        if (getPaddingBottom() != covered) setPadding(0, 0, 0, covered);
    }

    private void updateBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (backRegistration == null) backRegistration = new BackRegistration(this);
        backRegistration.update(isAttachedToWindow() && isShown() && series && pageExposed());
    }

    /** Android 13+ dispatches Back through the window, bypassing Activity.onBackPressed. */
    private static final class BackRegistration {
        final HistoryUi view;
        android.window.OnBackInvokedDispatcher dispatcher;
        final android.window.OnBackInvokedCallback callback;

        BackRegistration(HistoryUi view) {
            this.view = view;
            callback =
                    () -> {
                        if (!HistoryUi.onBack()) {
                            release();
                            Activity a = activity(view.getContext());
                            if (a != null) a.onBackPressed();
                        }
                    };
        }

        void update(boolean enabled) {
            android.window.OnBackInvokedDispatcher next =
                    enabled ? view.findOnBackInvokedDispatcher() : null;
            if (next == dispatcher) return;
            release();
            if (next != null) {
                dispatcher = next;
                next.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            }
        }

        void release() {
            if (dispatcher != null) {
                dispatcher.unregisterOnBackInvokedCallback(callback);
                dispatcher = null;
            }
        }
    }

    @Override
    protected android.os.Parcelable onSaveInstanceState() {
        Saved saved = new Saved(super.onSaveInstanceState());
        saved.series = series;
        if (library != null) {
            saved.selected = library.selected();
            saved.query = library.query();
            saved.page = library.page();
            saved.scroll = library.scrollY();
        }
        return saved;
    }

    @Override
    protected void onRestoreInstanceState(android.os.Parcelable state) {
        if (!(state instanceof Saved)) {
            super.onRestoreInstanceState(state);
            return;
        }
        Saved saved = (Saved) state;
        super.onRestoreInstanceState(saved.getSuperState());
        if (saved.series || !saved.selected.isEmpty()) {
            select(true);
            library.setQuery(saved.query);
            library.restore(saved.selected, saved.page, saved.scroll);
        }
        select(saved.series);
    }

    public static final class Saved extends View.BaseSavedState {
        boolean series;
        String selected = "", query = "";
        int page, scroll;

        Saved(android.os.Parcelable parent) {
            super(parent);
        }

        Saved(android.os.Parcel in) {
            super(in);
            series = in.readInt() != 0;
            selected = in.readString();
            page = in.readInt();
            scroll = in.readInt();
            query = in.readString();
        }

        @Override
        public void writeToParcel(android.os.Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(series ? 1 : 0);
            out.writeString(selected);
            out.writeInt(page);
            out.writeInt(scroll);
            out.writeString(query);
        }

        public static final android.os.Parcelable.Creator<Saved> CREATOR =
                new android.os.Parcelable.Creator<Saved>() {
                    public Saved createFromParcel(android.os.Parcel in) {
                        return new Saved(in);
                    }

                    public Saved[] newArray(int size) {
                        return new Saved[size];
                    }
                };
    }

    static boolean pageExposed() {
        String type = app.morphe.extension.youtube.shared.PlayerType.getCurrent().name();
        return type.equals("NONE")
                || type.equals("INLINE_MINIMAL")
                || type.equals("WATCH_WHILE_MINIMIZED");
    }

    public static boolean onBack() {
        if (!pageExposed()) return false;
        HistoryUi ui = current.get();
        if (ui == null || !ui.isShown() || !ui.series) return false;
        if (ui.library != null && ui.library.back()) return true;
        ui.select(false);
        return true;
    }
}
