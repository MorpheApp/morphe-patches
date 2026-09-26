/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import java.util.function.Consumer;
import org.json.JSONObject;

/** A real child of YTM's player bottom sheet, never an overlay. */
public final class JamBar extends LinearLayout {

  private final TextView title, subtitle, action;
  private final ProgressBar progress;
  private final ImageView people;
  private final Consumer<JSONObject> observer = this::render;

  public JamBar(Context c, AttributeSet attrs) {
    super(c, attrs);
    if (!JamUi.enabled()) {
      setVisibility(GONE);
      title = null;
      subtitle = null;
      action = null;
      progress = null;
      people = null;
      return;
    }
    setGravity(Gravity.CENTER_VERTICAL);
    setPadding(JamUi.dp(c, 16), 0, JamUi.dp(c, 8), 0);
    FrameLayout icon = new FrameLayout(c);
    addView(icon, new LayoutParams(JamUi.dp(c, 24), JamUi.dp(c, 24)));
    people = new ImageView(c);
    int id = c
      .getResources()
      .getIdentifier(
        "yt_outline_people_vd_theme_24",
        "drawable",
        c.getPackageName()
      );
    if (id != 0) people.setImageResource(id);
    people.setColorFilter(0xffeeeeee);
    icon.addView(people, new FrameLayout.LayoutParams(-1, -1));
    progress = new ProgressBar(c);
    progress.setIndeterminateTintList(
      android.content.res.ColorStateList.valueOf(0xffeeeeee)
    );
    icon.addView(progress, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout labels = new LinearLayout(c);
    labels.setOrientation(VERTICAL);
    labels.setGravity(Gravity.CENTER_VERTICAL);
    labels.setPadding(JamUi.dp(c, 12), 0, JamUi.dp(c, 8), 0);
    addView(labels, new LayoutParams(0, -1, 1));
    title = new TextView(c);
    title.setTextColor(0xffeeeeee);
    title.setTextSize(14);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setSingleLine();
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    labels.addView(title);
    subtitle = new TextView(c);
    subtitle.setTextColor(0xffbbbbbb);
    subtitle.setTextSize(11);
    subtitle.setSingleLine();
    subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    labels.addView(subtitle);
    action = new TextView(c);
    action.setTextSize(13);
    action.setTextColor(0xffeeeeee);
    action.setGravity(Gravity.CENTER);
    action.setPadding(JamUi.dp(c, 12), 0, JamUi.dp(c, 12), 0);
    addView(action, new LayoutParams(-2, -1));
    android.util.TypedValue selectable = new android.util.TypedValue();
    c.getTheme().resolveAttribute(
      android.R.attr.selectableItemBackground,
      selectable,
      true
    );
    setBackgroundResource(selectable.resourceId);
    setOnClickListener(v -> JamUi.open(c));
    setFocusable(true);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    labels.setImportantForAccessibility(
      IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    );
    action.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    render(JamUi.latest);
  }

  private void render(JSONObject view) {
    String role = JamPanel.role(view);
    title.setText(JamPanel.title(view));
    subtitle.setText(JamPanel.status(view));
    action.setText(
      "Host".equals(role)
        ? str("morphe_music_jam_manage")
        : "Participant".equals(role)
          ? str("morphe_music_jam_details")
          : "Joining".equals(role)
            ? str("morphe_music_jam_cancel")
            : str("morphe_music_jam_open")
    );
    boolean busy = JamPanel.waiting(view);
    progress.setVisibility(busy ? VISIBLE : GONE);
    people.setVisibility(busy ? GONE : VISIBLE);
    setContentDescription(
      title.getText() + ". " + subtitle.getText() + ". " + action.getText()
    );
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (JamUi.enabled()) JamUi.observe(getContext(), observer);
  }

  @Override
  protected void onDetachedFromWindow() {
    if (JamUi.enabled()) JamUi.unobserve(observer);
    super.onDetachedFromWindow();
  }
}
