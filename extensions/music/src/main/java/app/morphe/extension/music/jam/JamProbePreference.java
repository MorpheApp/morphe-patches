/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/** Secondary entry point for session setup before opening the player. */
@SuppressWarnings("deprecation")
public final class JamProbePreference extends Preference {

  public JamProbePreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    setPersistent(false);
  }

  @Override
  protected void onClick() {
    JamUi.open(getContext());
  }
}
