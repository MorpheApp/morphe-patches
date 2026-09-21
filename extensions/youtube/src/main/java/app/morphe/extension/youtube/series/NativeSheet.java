package app.morphe.extension.youtube.series;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;

/** Small native-view sheet using the host palette and standard Android accessibility. */
final class NativeSheet {
    final Dialog dialog;
    private Runnable dismissed = () -> {};

    void onDismiss(Runnable listener) {
        dismissed = listener;
    }

    final LinearLayout body, footer;

    NativeSheet(Context context, String title) {
        LinearLayout panel =
                app.morphe.extension.shared.ui.SheetBottomDialog.createMainLayout(context, null);
        int inset = HistoryUi.dp(context, 20);
        panel.setPadding(inset, HistoryUi.dp(context, 12), inset, inset);
        TextView heading = text(context, title, 20);
        heading.setTypeface(Typeface.create("sans-serif-medium", 0));
        panel.addView(heading);
        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1));
        footer = new LinearLayout(context);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setPadding(0, HistoryUi.dp(context, 12), 0, 0);
        panel.addView(footer);
        dialog =
                app.morphe.extension.shared.ui.SheetBottomDialog.createSlideDialog(
                        context, panel, 200);
        android.app.Activity owner = HistoryUi.activity(context);
        if (owner != null) {
            dialog.setOwnerActivity(owner);
            android.app.Application.ActivityLifecycleCallbacks lifecycle =
                    new android.app.Application.ActivityLifecycleCallbacks() {
                        public void onActivityDestroyed(android.app.Activity a) {
                            if (a == owner) dialog.dismiss();
                        }

                        public void onActivityCreated(
                                android.app.Activity a, android.os.Bundle b) {}

                        public void onActivityStarted(android.app.Activity a) {}

                        public void onActivityResumed(android.app.Activity a) {}

                        public void onActivityPaused(android.app.Activity a) {}

                        public void onActivityStopped(android.app.Activity a) {}

                        public void onActivitySaveInstanceState(
                                android.app.Activity a, android.os.Bundle b) {}
                    };
            dialog.setOnShowListener(
                    d -> owner.getApplication().registerActivityLifecycleCallbacks(lifecycle));
            dialog.setOnDismissListener(
                    d -> {
                        owner.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
                        dismissed.run();
                    });
        } else {
            dialog.setOnDismissListener(d -> dismissed.run());
        }
    }

    TextView message(String text) {
        TextView label = text(body.getContext(), text, 14);
        label.setTextColor(HistoryUi.secondary(body.getContext()));
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
        text.setText(UiText.get(c, title));
        text.setTextColor(HistoryUi.foreground(c));
        text.setTextSize(size);
        text.setPadding(0, HistoryUi.dp(c, 8), 0, HistoryUi.dp(c, 8));
        return text;
    }

    static TextView button(Context c, String title, Runnable work, boolean primary) {
        TextView button = text(c, title, 14);
        button.setTypeface(Typeface.create("sans-serif-medium", 0));
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(HistoryUi.dp(c, 48));
        button.setPadding(HistoryUi.dp(c, 16), 0, HistoryUi.dp(c, 16), 0);
        button.setBackground(HistoryUi.ripple(c, primary));
        button.setFocusable(true);
        button.setOnClickListener(v -> work.run());
        return button;
    }

    static void confirm(Context c, String title, String message, String action, Runnable work) {
        NativeSheet sheet = new NativeSheet(c, title);
        sheet.message(message);
        sheet.action("series_tracker_ui_cancel", sheet.dialog::dismiss, false);
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
