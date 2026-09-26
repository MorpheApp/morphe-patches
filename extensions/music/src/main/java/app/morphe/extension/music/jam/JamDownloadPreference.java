/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;
import app.morphe.extension.shared.Utils;

/** Opens the latest Jam Layer release in the user's browser. */
@SuppressWarnings("deprecation")
public final class JamDownloadPreference extends Preference {

  private static final Uri RELEASES = Uri.parse(
    "https://github.com/AgentKosticka/Jam-Layer/releases/latest"
  );

  public JamDownloadPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
    setPersistent(false);
  }

  @Override
  protected void onClick() {
    try {
      getContext().startActivity(new Intent(Intent.ACTION_VIEW, RELEASES));
    } catch (RuntimeException error) {
      Utils.showToastLong(str("morphe_music_jam_no_browser"));
    }
  }
}
