package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import static app.morphe.extension.shared.StringRef.str;

/** Hide the native community root until its content label is known, including cached models. */
public final class NsfwCommunityGate {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Accessed only on the main thread. Weak roots do not retain discarded navigation screens.
    private static final Map<View, State> VIEWS = new WeakHashMap<>();
    private static final Map<String, Boolean> LABELS = new HashMap<>();

    private NsfwCommunityGate() {}

    private static String key(String name) {
        String result = name.toLowerCase(Locale.ROOT);
        return result.startsWith("r/") ? result.substring(2) : result;
    }

    private static void main(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else MAIN.post(action);
    }

    public static void bind(String name, View root) {
        if (!PermanentNsfwPatch.isEnabled() || name == null) return;
        main(() -> {
            State state = new State(key(name), root.getVisibility());
            VIEWS.put(root, state);
            apply(root, state);
        });
    }

    public static void classify(String name, Boolean adult) {
        if (!PermanentNsfwPatch.isEnabled() || name == null) return;
        main(() -> {
            // An absent label is not evidence that the content is safe.
            if (adult != null) LABELS.put(key(name), adult);
            for (Map.Entry<View, State> entry : VIEWS.entrySet()) {
                if (entry.getValue().name.equals(key(name))) apply(entry.getKey(), entry.getValue());
            }
        });
    }

    /** Called for a rejected direct community response before throwing its transport error. */
    public static void blockedPath(String path) {
        String[] segments = path.split("/");
        if (segments.length > 2 && "r".equals(segments[1])) classify(segments[2], true);
    }

    private static void apply(View root, State state) {
        Boolean adult = LABELS.get(state.name);
        if (Boolean.FALSE.equals(adult)) {
            root.setVisibility(state.visibility);
            if (state.dialog != null) state.dialog.dismiss();
            return;
        }
        // The native tree (including Compose, headers and images) cannot draw behind the message.
        root.setVisibility(View.INVISIBLE);
        if (!Boolean.TRUE.equals(adult) || state.dialog != null || !root.isAttachedToWindow()) {
            if (!root.isAttachedToWindow() && !state.waiting) {
                state.waiting = true;
                root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    public void onViewAttachedToWindow(View view) {
                        view.removeOnAttachStateChangeListener(this);
                        state.waiting = false;
                        apply(view, state);
                    }
                    public void onViewDetachedFromWindow(View view) {}
                });
            }
            return;
        }
        Context context = root.getContext();
        while (context instanceof ContextWrapper wrapper && !(context instanceof Activity)) context = wrapper.getBaseContext();
        if (!(context instanceof Activity activity) || activity.isFinishing()) return;
        Dialog dialog = new Dialog(activity);
        state.dialog = dialog;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(32, 32, 32, 32);
        content.setBackgroundColor(Color.rgb(24, 24, 24));
        TextView message = new TextView(activity);
        message.setText(str("morphe_permanent_nsfw_blocked_community"));
        message.setTextColor(Color.WHITE);
        message.setTextSize(20);
        message.setGravity(Gravity.CENTER);
        content.addView(message);
        Button back = new Button(activity);
        back.setText(str("morphe_permanent_nsfw_go_back"));
        content.addView(back);
        back.setOnClickListener(view -> { dialog.dismiss(); activity.onBackPressed(); });
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(ignored -> activity.onBackPressed());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        WeakReference<Dialog> reference = new WeakReference<>(dialog);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) {}
            public void onViewDetachedFromWindow(View view) {
                Dialog current = reference.get();
                if (current != null) current.dismiss();
                view.removeOnAttachStateChangeListener(this);
            }
        });
    }

    private static final class State {
        final String name;
        final int visibility;
        Dialog dialog;
        boolean waiting;
        State(String name, int visibility) { this.name = name; this.visibility = visibility; }
    }
}
