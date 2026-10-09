/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.ui.SheetBottomDialog;

/** Small native-view sheet using the host palette and standard Android accessibility. */
final class NativeSheet {
    final Dialog dialog;
    private Runnable dismissed = () -> {};

    void onDismiss(Runnable listener) {
        dismissed = listener;
    }

    final LinearLayout body, footer;

    NativeSheet(Context context, String title) {
        LinearLayout panel = SheetBottomDialog.createMainLayout(context, null);
        int inset = HistoryUi.dp(20);
        panel.setPadding(inset, HistoryUi.dp(12), inset, inset);
        TextView heading = text(context, title, 20);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        panel.addView(heading);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        footer = new LinearLayout(context);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, HistoryUi.dp(12), 0, 0);
        panel.addView(footer);
        dialog = SheetBottomDialog.createSlideDialog(context, panel, 200);
        Activity owner = HistoryUi.activity(context);
        if (owner != null) {
            dialog.setOwnerActivity(owner);
            Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
                public void onActivityDestroyed(android.app.Activity a) {
                    if (a == owner) dialog.dismiss();
                }

                public void onActivityCreated(
                        android.app.Activity a, android.os.Bundle b) {
                }

                public void onActivityStarted(android.app.Activity a) {
                }

                public void onActivityResumed(android.app.Activity a) {
                }

                public void onActivityPaused(android.app.Activity a) {
                }

                public void onActivityStopped(android.app.Activity a) {
                }

                public void onActivitySaveInstanceState(
                        android.app.Activity a, android.os.Bundle b) {
                }
            };
            dialog.setOnShowListener(d ->
                    owner.getApplication().registerActivityLifecycleCallbacks(lifecycle));
            dialog.setOnDismissListener(d -> {
                owner.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
                dismissed.run();
            });
        } else {
            dialog.setOnDismissListener(d -> dismissed.run());
        }
    }

    TextView message(String text) {
        TextView label = text(body.getContext(), text, 14);
        label.setTextColor(HistoryUi.secondary());
        body.addView(label);
        return label;
    }

    TextView action(String title, Runnable work, boolean primary) {
        TextView button = button(body.getContext(), title, work, primary);
        footer.addView(button);
        return button;
    }

    void show() {
        dialog.show();
    }

    static TextView text(Context c, String title, int size) {
        TextView text = new TextView(c);
        text.setText(UiText.get(title));
        text.setTextColor(HistoryUi.foreground());
        text.setTextSize(size);
        text.setPadding(0, HistoryUi.dp(8), 0, HistoryUi.dp(8));
        return text;
    }

    static TextView button(Context c, String title, Runnable work, boolean primary) {
        TextView button = text(c, title, 14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(HistoryUi.dp(48));
        button.setPadding(HistoryUi.dp(16), 0, HistoryUi.dp(16), 0);
        button.setBackground(HistoryUi.ripple(primary));
        button.setFocusable(true);
        button.setOnClickListener(v -> work.run());
        return button;
    }

    static void confirm(Context c, String title, String message, String action, Runnable work) {
        NativeSheet sheet = new NativeSheet(c, title);
        sheet.message(message);
        sheet.action("morphe_series_tracker_ui_cancel", sheet.dialog::dismiss, false);
        sheet.action(
                action,
                () -> {
                    sheet.dialog.dismiss();
                    work.run();
                },
                true);
        sheet.show();
    }
}
