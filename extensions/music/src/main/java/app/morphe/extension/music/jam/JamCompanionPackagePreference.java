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

/** Player-settings entry for selecting the approved local Jam Layer package. */
@SuppressWarnings("deprecation")
public final class JamCompanionPackagePreference extends Preference {

  public JamCompanionPackagePreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    setPersistent(false);
  }

  @Override
  protected void onClick() {
    JamUi.configureCompanion(getContext());
  }
}
