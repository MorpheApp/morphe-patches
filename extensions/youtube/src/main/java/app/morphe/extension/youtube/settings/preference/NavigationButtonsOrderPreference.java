/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4374
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.settings.preference.ColorPickerPreference.DISABLED_ALPHA;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.NavigationBarPatch;
import app.morphe.extension.youtube.patches.utils.LocalQueueSheet;
import app.morphe.extension.youtube.series.HistoryNavigation;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Sorts the navigation buttons, or the toolbar buttons, by dragging their handles.
 * The list has the buttons seen in the navigation bar or toolbar, so a button shows up
 * after it was shown once, such as after it was turned on by its setting.
 */
@SuppressWarnings({"unused", "deprecation"})
public class NavigationButtonsOrderPreference extends Preference {

    public NavigationButtonsOrderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onClick() {
        Context context = getContext();
        final boolean toolbar = Settings.TOOLBAR_BUTTONS_ORDER.key.equals(getKey());
        final StringSetting setting = toolbar ? Settings.TOOLBAR_BUTTONS_ORDER : Settings.NAVIGATION_BUTTONS_ORDER;
        Map<String, String> discoveredButtons = toolbar
                ? NavigationBarPatch.discoveredToolbarButtons
                : NavigationBarPatch.discoveredNavigationButtons;
        synchronized (discoveredButtons) {
            discoveredButtons = new LinkedHashMap<>(discoveredButtons);
        }
        List<String> shownButtons = toolbar
                ? NavigationBarPatch.toolbarButtons
                : NavigationBarPatch.navigationButtons;

        // The saved order, or the order of the buttons shown if the order of the server is used,
        // then the other buttons in the order they were seen.
        List<String> order = new ArrayList<>();
        for (String id : setting.get().isEmpty() ? shownButtons : Arrays.asList(setting.get().split(","))) {
            if (discoveredButtons.containsKey(id) && !order.contains(id)) {
                order.add(id);
            }
        }
        for (String id : discoveredButtons.keySet()) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }

        final int foregroundColor = ThemeUtils.getAppForegroundColor();
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        // Finger position of the drag, moved by the height of each row the dragged row passes.
        final float[] dragStartY = new float[1];

        for (String id : order) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setTag(id);

            TextView label = new TextView(context);
            // The buttons added by Morphe are labeled in the current language,
            // the buttons of the app with the label of the server.
            label.setText(switch (id) {
                case NavigationBarPatch.SEARCH_PIVOT_IDENTIFIER -> str("menu_search");
                case NavigationBarPatch.SETTINGS_PIVOT_IDENTIFIER,
                     NavigationBarPatch.SETTING_BUTTON_ENUM_NAME -> str("menu_settings");
                case NavigationBarPatch.WATCH_LATER_PIVOT_IDENTIFIER -> str("morphe_watch_later_navigation_label");
                case HistoryNavigation.PIVOT_IDENTIFIER -> str("morphe_series_tracker_ui_series");
                default -> discoveredButtons.get(id);
            });
            label.setTextColor(foregroundColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            // Buttons that are not shown.
            if (!shownButtons.isEmpty() && !shownButtons.contains(id)) {
                label.setAlpha(DISABLED_ALPHA);
            }
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            View handle = new LocalQueueSheet.DragHandleView(context, foregroundColor);
            handle.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        row.getParent().requestDisallowInterceptTouchEvent(true);
                        dragStartY[0] = event.getRawY();
                        row.setAlpha(0.7f);
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float offset = event.getRawY() - dragStartY[0];
                        final int index = rows.indexOfChild(row);
                        // The other row is moved, because the dragged row would lose the touch
                        // if it's removed from the layout.
                        if (offset > 0 && index < rows.getChildCount() - 1) {
                            View next = rows.getChildAt(index + 1);
                            if (offset > next.getHeight() / 2f) {
                                rows.removeView(next);
                                rows.addView(next, index);
                                dragStartY[0] += next.getHeight();
                                offset -= next.getHeight();
                            }
                        } else if (offset < 0 && index > 0) {
                            View previous = rows.getChildAt(index - 1);
                            if (-offset > previous.getHeight() / 2f) {
                                rows.removeView(previous);
                                rows.addView(previous, index);
                                dragStartY[0] -= previous.getHeight();
                                offset += previous.getHeight();
                            }
                        }
                        row.setTranslationY(offset);
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        row.animate().translationY(0).setDuration(150).start();
                        row.setAlpha(1);
                    }
                    default -> {
                        return false;
                    }
                }
                return true;
            });
            row.addView(handle, new LinearLayout.LayoutParams(Dim.dp48, Dim.dp48));

            rows.addView(row);
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle(),
                null,
                null,
                null,
                () -> {
                    List<String> names = new ArrayList<>(rows.getChildCount());
                    for (int i = 0, count = rows.getChildCount(); i < count; i++) {
                        names.add((String) rows.getChildAt(i).getTag());
                    }
                    setting.save(String.join(",", names));
                },
                () -> {},
                str("morphe_settings_reset"),
                setting::resetToDefault,
                true
        );

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(rows);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollParams.bottomMargin = Dim.dp12;
        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1, scrollParams);

        dialogPair.first.show();
    }
}
