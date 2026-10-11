/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.jam;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Live session controls using the host application's dialog theme. */
final class JamPanel {

    static String role(JSONObject view) {
        JSONObject s = view.optJSONObject("session");
        return s == null ? "Idle" : s.optString("role", "Idle");
    }

    static String title(JSONObject view) {
        String r = role(view);
        return "Host".equals(r)
            ? str("morphe_music_jam_your_jam")
            : "Participant".equals(r)
              ? str("morphe_music_jam_listening_together")
              : "Joining".equals(r)
                ? str("morphe_music_jam_joining_jam")
                : str("morphe_music_jam_listen_together");
    }

    static String status(JSONObject view) {
        JSONObject s = view.optJSONObject("session");
        String r = role(view);
        if ("Joining".equals(r)) return str("morphe_music_jam_finding_host");
        if (JamUI.pending > 0) return str("morphe_music_jam_updating");
        if ("Host".equals(r)) {
            assert s != null;
            int n = s.optInt("peers");
            return (
                (n == 0
                    ? str("morphe_music_jam_waiting_people")
                    : String.format(
                          str(
                              n == 1
                                  ? "morphe_music_jam_one_connected"
                                  : "morphe_music_jam_many_connected"
                          ),
                          n
                      )) +
                " · " +
                (s.optBoolean("allowGuestEdits", true)
                    ? str("morphe_music_jam_edits_open")
                    : str("morphe_music_jam_edits_locked"))
            );
        }
        if ("Participant".equals(r)) {
            if (view.has("error")) return view.optString("error");
            assert s != null;
            String t = s.optString("transport");
            return "Aware".equals(t)
                ? str("morphe_music_jam_connected_aware")
                : "LAN".equals(t)
                  ? str("morphe_music_jam_connected_wifi")
                  : "BLE".equals(t)
                    ? str("morphe_music_jam_connected_bluetooth")
                    : str("morphe_music_jam_reconnecting_host");
        }
        return s == null && view.has("error")
            ? view.optString("error")
            : str("morphe_music_jam_share_prompt");
    }

    static boolean waiting(JSONObject v) {
        String r = role(v);
        JSONObject s = v.optJSONObject("session");
        if (JamUI.pending > 0 ||
                "Joining".equals(r)) return true;
        if (!"Participant".equals(r)) return false;
        assert s != null;
        return (
                !"Aware".equals(s.optString("transport")) &&
                        !"BLE".equals(s.optString("transport")) &&
                        !"LAN".equals(s.optString("transport"))
        );
    }

    static void show(Context context) {
        Activity activity = JamUI.activity(context);
        if (activity == null || activity.isFinishing()) return;
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, JamUI.dp(activity, 4), 0, JamUI.dp(activity, 8));
        TextView heading = new TextView(activity);
        heading.setTextSize(24);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setTextColor(ThemeUtils.getAppForegroundColor());
        body.addView(heading);
        TextView status = new TextView(activity);
        status.setTextSize(14);
        status.setTextColor(ThemeUtils.getAppForegroundColor());
        status.setPadding(0, JamUI.dp(activity, 8), 0, JamUI.dp(activity, 20));
        status.setAccessibilityLiveRegion(
            View.ACCESSIBILITY_LIVE_REGION_POLITE
        );
        body.addView(status);
        ProgressBar progress = new ProgressBar(
                activity,
            null,
            android.R.attr.progressBarStyleHorizontal
        );
        progress.setIndeterminate(true);
        body.addView(
            progress,
            new LinearLayout.LayoutParams(-1, JamUI.dp(activity, 3))
        );
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.VERTICAL);
        body.addView(actions);
        Dialog dialog = JamUI.createDialog(
                activity,
            null,
            null,
            body,
            str("morphe_music_jam_done")
        ).first;
        final String[] previous = { "" };
        Consumer<JSONObject> observer = v -> {
            heading.setText(title(v));
            status.setText(status(v));
            progress.setVisibility(waiting(v) ? View.VISIBLE : View.GONE);
            String role = role(v);
            JSONObject state = v.optJSONObject("session");
            boolean allowed =
                    state == null || state.optBoolean("allowGuestEdits", true),
                busy = JamUI.pending > 0;
            String key =
                role +
                allowed +
                busy +
                (state != null && state.optBoolean("paired"));
            if (key.equals(previous[0])) return;
            previous[0] = key;
            actions.removeAllViews();
            if ("Host".equals(role)) {
                button(
                        activity,
                    actions,
                    str("morphe_music_jam_invite_people"),
                    false,
                    !busy,
                    () -> JamUI.invite(activity)
                );
                Switch edits = new Switch(activity);
                edits.setText(str("morphe_music_jam_guest_edits"));
                edits.setTextColor(ThemeUtils.getAppForegroundColor());
                edits.setTextSize(15);
                edits.setMinHeight(JamUI.dp(activity, 56));
                edits.setChecked(allowed);
                edits.setEnabled(!busy);
                actions.addView(edits, new LinearLayout.LayoutParams(-1, -2));
                edits.setOnCheckedChangeListener((b, checked) -> {
                    edits.setEnabled(false);
                    try {
                        JamUI.edit(
                                activity,
                            JamUI.command("GUEST_EDITS").put("allow", checked)
                        );
                    } catch (Exception error) {
                        Logger.printInfo(
                            () -> "Could not send Jam guest edit choice",
                            error
                        );
                        edits.setEnabled(true);
                    }
                });
                button(
                        activity,
                    actions,
                    str("morphe_music_jam_end_jam"),
                    true,
                    !busy,
                    () -> JamUI.edit(activity, JamUI.command("END"))
                );
            } else if ("Participant".equals(role) || "Joining".equals(role)) {
                TextView help = new TextView(activity);
                help.setText(
                    "Participant".equals(role)
                        ? str("morphe_music_jam_participant_help")
                        : str("morphe_music_jam_joining_help")
                );
                help.setTextColor(ThemeUtils.getAppForegroundColor());
                help.setTextSize(14);
                help.setPadding(0, JamUI.dp(activity, 16), 0, JamUI.dp(activity, 12));
                actions.addView(help);
                button(
                        activity,
                    actions,
                    "Joining".equals(role)
                        ? str("morphe_music_jam_cancel_joining")
                        : str("morphe_music_jam_leave_jam"),
                    true,
                    true,
                    () -> JamUI.edit(activity, JamUI.command("END"))
                );
            } else {
                boolean paired = state != null && state.optBoolean("paired");
                if (paired) {
                    button(
                            activity,
                        actions,
                        str("morphe_music_jam_start_jam"),
                        false,
                        !busy,
                        () -> JamUI.host(activity)
                    );
                    button(
                            activity,
                        actions,
                        str("morphe_music_jam_join_with_code"),
                        false,
                        !busy,
                        () -> {
                            dialog.dismiss();
                            JamUI.join(activity);
                        }
                    );
                } else button(
                        activity,
                    actions,
                    str("morphe_music_jam_setup_layer"),
                    false,
                    !busy,
                    () -> {
                        dialog.dismiss();
                        JamUI.pair(activity);
                    }
                );
            }
        };
        dialog.setOnDismissListener(d -> JamUI.unobserve(observer));
        dialog.show();
        JamUI.observe(activity, observer);
    }

    private static void button(
        Context c,
        LinearLayout parent,
        String label,
        boolean destructive,
        boolean enabled,
        Runnable action
    ) {
        Button b = CustomDialog.createButton(
            c,
            null,
            label,
            action,
            false,
            false
        );
        b.setSingleLine(false);
        b.setEllipsize(null);
        b.setMinHeight(JamUI.dp(c, 52));
        b.setTextSize(15);
        if (destructive) b.setTextColor(
            Utils.isDarkModeEnabled() ? 0xffffb4ab : 0xffa4262c
        );
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(JamUI.dp(c, 16), 0, JamUI.dp(c, 16), 0);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : .45f);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = JamUI.dp(c, 8);
        parent.addView(b, p);
    }
}
