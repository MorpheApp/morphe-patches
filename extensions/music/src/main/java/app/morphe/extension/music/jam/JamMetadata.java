/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import android.content.res.Resources;
import android.text.TextUtils;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Mirrors the native full-player and mini-player labels on the UI thread. */
public final class JamMetadata {

  private static final Map<TextView, Label> labels = new WeakHashMap<>();

  private static final class Label {

    final boolean title;
    CharSequence local;

    Label(boolean title, CharSequence local) {
      this.title = title;
      this.local = local;
    }
  }

  private JamMetadata() {}

  /** Replaces only setText calls inside the fingerprinted native player presenter. */
  public static void setText(TextView view, CharSequence text) {
    Label label = labels.get(view);
    if (label == null) {
      String name = "";
      try {
        name = view.getResources().getResourceEntryName(view.getId());
      } catch (android.content.res.Resources.NotFoundException ignored) {
        // Native labels can have generated IDs; they are not player title or artist views.
      }
      boolean title = "title".equals(name) || "mini_player_title".equals(name);
      boolean artist =
        "artist".equals(name) || "mini_player_subtitle".equals(name);
      if (title || artist) {
        label = new Label(title, TextUtils.stringOrSpannedString(text));
        labels.put(view, label);
      }
    }
    if (label == null) {
      view.setText(text);
      return;
    }
    // Participant native rebinds may themselves contain mirrored data. Retain
    // the last local value until leaving triggers a fresh native rebind.
    if (!JamMirror.active()) label.local = TextUtils.stringOrSpannedString(
      text
    );
    view.setText(value(label));
  }

  private static CharSequence value(Label label) {
    if (JamMirror.active()) {
      String remote = label.title ? JamMirror.title() : JamMirror.artist();
      if (remote != null) return remote;
    }
    return label.local;
  }

  static void refresh() {
    for (Map.Entry<TextView, Label> entry : new ArrayList<>(
      labels.entrySet()
    )) {
      TextView view = entry.getKey();
      if (view == null) continue;
      CharSequence text = value(entry.getValue());
      if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }
  }
}
