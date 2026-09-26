/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.jam;

import android.view.View;
import app.morphe.extension.shared.Logger;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Supplies host state to the native icon renderer, including its accessibility labels. */
public final class JamPlayerState {
  public interface Icon {
    View patch_jamView();
    Object patch_jamState(boolean playing);
    void patch_jamRender(Object state);
  }

  private static final Map<Icon, Object> localStates = new WeakHashMap<>();

  private JamPlayerState() {}

  public static Object model(Icon icon, Object local) {
    if (!JamPlayback.isPlayPauseButton(icon.patch_jamView())) return local;
    localStates.put(icon, local);
    Boolean playing = JamClock.hostPlaying();
    return playing == null ? local : icon.patch_jamState(playing);
  }

  static void refresh() {
    Boolean playing = JamClock.hostPlaying();
    for (Map.Entry<Icon, Object> entry : new ArrayList<>(localStates.entrySet())) {
      try {
        Icon icon = entry.getKey();
        icon.patch_jamRender(playing == null ? entry.getValue() : icon.patch_jamState(playing));
      } catch (Exception error) {
        Logger.printInfo(() -> "Could not refresh Jam playback icon", error);
      }
    }
  }
}
