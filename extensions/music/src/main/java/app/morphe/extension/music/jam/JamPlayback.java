/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONObject;

/** Native command choices and native now-playing adapter refresh. No local audio mirror. */
public final class JamPlayback {

  public interface Router {
    void patch_jamDispatch(Object command, Object context);
    void patch_jamWatch(byte[] command);
  }

  public interface NowUi {
    void patch_jamRefreshNow();
  }

  private static WeakReference<Router> router = new WeakReference<>(null);
  private static final Set<NowUi> views = Collections.newSetFromMap(
    new WeakHashMap<>()
  );
  private static boolean dialog, refreshing, refreshQueued;

  static boolean claimDialog() {
    if (dialog) return false;
    dialog = true;
    return true;
  }

  static void releaseDialog() {
    dialog = false;
  }

  public static void capture(Router value) {
    router = new WeakReference<>(value);
  }

  public static void observe(NowUi value) {
    if (views.add(value) && JamMirror.active()) refresh();
  }

  public static Object chooseItem(Object local) {
    Object host = JamMirror.now();
    return host == null ? local : host;
  }

  public static void refresh() {
    if (refreshQueued) return;
    refreshQueued = true;
    Utils.runOnMainThread(() -> {
      refreshQueued = false;
      if (refreshing) return;
      refreshing = true;
      try {
        for (NowUi view : new ArrayList<>(views)) {
          try {
            view.patch_jamRefreshNow();
          } catch (Exception error) {
            android.util.Log.w(
              "MorpheJam",
              "Now-playing refresh failed",
              error
            );
          }
        }
        JamMetadata.refresh();
      } finally {
        refreshing = false;
      }
    });
  }

  private static boolean participant() {
    JSONObject session = JamUi.latest.optJSONObject("session");
    return (
      JamMirror.active() ||
      (session != null && "Participant".equals(session.optString("role")))
    );
  }

  public static boolean offer(
    Router owner,
    Object endpoint,
    Object map,
    byte[] bytes
  ) {
    try {
      return offerPlayback(owner, endpoint, map, bytes);
    } catch (Exception error) {
      Logger.printInfo(() -> "Could not inspect Jam playback command", error);
      return false;
    }
  }

  private static boolean offerPlayback(
    Router owner,
    Object endpoint,
    Object map,
    byte[] bytes
  ) {
    capture(owner);
    String video = QueueCommand.watchVideo(bytes);
    if (video == null || !participant()) return false;
    Utils.runOnMainThread(() ->
      choices(video, null, () -> owner.patch_jamDispatch(endpoint, map), false)
    );
    return true;
  }

  public static boolean queueTap(Object item) {
    if (!participant()) return false;
    if (JamMirror.selection(item) < 0) {
      Utils.runOnMainThread(() -> {
        Activity activity = JamUi.activity(null);
        if (activity != null) Utils.showToastLong(
          str("morphe_music_jam_waiting_host_queue")
        );
      });
      return true;
    }
    try {
      YtmBridge.QueueAccess a = YtmBridge.access();
      String video = a.patch_jamVideoId(item),
        id = Long.toString(a.patch_jamItemId(item));
      Utils.runOnMainThread(() -> choices(video, id, () -> local(video), true));
      return true;
    } catch (Exception e) {
      Logger.printInfo(() -> "Could not open Jam queue choice", e);
      return true;
    }
  }

  public static boolean playButton(View view) {
    try {
      return playButtonSafely(view);
    } catch (Exception error) {
      Logger.printInfo(() -> "Could not handle Jam player control", error);
      // A failed remote command must not fall through to the participant's audio player.
      return participant();
    }
  }

  private static boolean playButtonSafely(View view) {
    if (!participant()) return false;
    String name = buttonName(view);
    if (
      "player_control_next_button".equals(name) ||
      "player_control_previous_button".equals(name) ||
      "mini_player_next_button".equals(name) ||
      "mini_player_previous_button".equals(name)
    ) {
      String operation = name.endsWith("next_button") ? "SKIP_NEXT" : "SKIP_PREVIOUS";
      JamUi.edit(view.getContext(), JamUi.command(operation));
      return true;
    }
    if (!isPlayPauseButton(view)) return false;
    Boolean playing = JamClock.hostPlaying();
    if (playing == null) {
      Utils.showToastLong(str("morphe_music_jam_waiting_host_queue"));
      return true;
    }
    if (!JamClock.canControlHostPlayback()) {
      Utils.showToastLong(str("morphe_music_jam_update_host_controls"));
      return true;
    }
    try {
      Object item = JamMirror.now();
      if (item == null) {
        Utils.showToastLong(str("morphe_music_jam_waiting_host_queue"));
        return true;
      }
      YtmBridge.QueueAccess access = YtmBridge.access();
      // PLAY retains queue selection semantics unless an explicit desired state is supplied.
      // The existing Companion forwards these fields unchanged.
      JSONObject command = JamUi.command("PLAY")
        .put("videoId", access.patch_jamVideoId(item))
        .put("item", Long.toString(access.patch_jamItemId(item)))
        .put("playing", !playing);
      JamUi.edit(view.getContext(), command);
    } catch (Exception error) {
      Logger.printInfo(() -> "Could not send Jam playback state", error);
      Utils.showToastLong(str("morphe_music_jam_waiting_host_queue"));
    }
    return true;
  }

  static boolean isPlayPauseButton(View view) {
    String name = buttonName(view);
    return "player_control_play_pause_replay_button".equals(name) ||
      "mini_player_play_pause_replay_button".equals(name);
  }

  private static String buttonName(View view) {
    if (view == null) return "";
    try {
      return view.getResources().getResourceEntryName(view.getId());
    } catch (android.content.res.Resources.NotFoundException ignored) {
      // Some native controls have IDs without a resource entry name.
    }
    return "";
  }

  private static Router hostRouter() {
    Router value = router.get();
    if (value != null) return value;
    for (NowUi view : new ArrayList<>(views))
      if (view instanceof Router) {
        value = (Router) view;
        capture(value);
        return value;
      }
    return null;
  }

  private static void local(String video) {
    Router r = hostRouter();
    if (r == null) throw new IllegalStateException(
      str("morphe_music_jam_open_song_first")
    );
    r.patch_jamWatch(QueueCommand.watch(video));
  }

  private static void choices(
    String video,
    String item,
    Runnable playLocal,
    boolean queue
  ) {
    Activity activity = JamUi.activity(null);
    if (activity == null || activity.isFinishing() || !claimDialog()) return;
    String[] labels = queue
      ? new String[] {
          str("morphe_music_jam_play_on_host"),
          str("morphe_music_jam_quit_and_play"),
        }
      : new String[] {
          str("morphe_music_jam_play_next"),
          str("morphe_music_jam_add_to_queue"),
          str("morphe_music_jam_quit_and_play"),
        };
    AlertDialog popup = new AlertDialog.Builder(activity)
      .setTitle(
        queue
          ? str("morphe_music_jam_play_track")
          : str("morphe_music_jam_choose_playback")
      )
      .setItems(labels, (d, index) -> {
        if (index == labels.length - 1) {
          JamUi.call(activity, JamUi.command("END"), response -> {
            if (!response.optBoolean("ok")) {
              Utils.showToastLong(response.optString("error"));
              return;
            }
            try {
              JSONObject idle = new JSONObject().put(
                "session",
                new JSONObject().put("role", "Idle")
              );
              JamUi.latest = idle;
              JamClock.clear();
              JamMirror.accept(activity, idle);
              Utils.runOnMainThread(() -> {
                try {
                  playLocal.run();
                } catch (Exception e) {
                  Utils.showToastLong(e.getMessage());
                }
              });
            } catch (Exception e) {
              Utils.showToastLong(e.getMessage());
            }
          });
          return;
        }
        try {
          JSONObject command = JamUi.command(
            queue ? "PLAY" : index == 0 ? "PLAY_NEXT" : "ADD"
          ).put("videoId", video);
          if (item != null) command.put("item", item);
          JamUi.call(activity, command, response -> {
            if (!response.optBoolean("ok")) Utils.showToastLong(
              response.optString("error")
            );
          });
        } catch (Exception e) {
          Utils.showToastLong(e.getMessage());
        }
      })
      .setNegativeButton(str("morphe_music_jam_cancel"), null)
      .setOnDismissListener(d -> releaseDialog())
      .create();
    popup.show();
    JamUi.styleDialog(popup);
  }

  static void leaveAndPlay(Activity activity, String video, long position) {
    JamUi.call(activity, JamUi.command("END"), response -> {
      if (!response.optBoolean("ok")) {
        Utils.showToastLong(response.optString("error"));
        return;
      }
      try {
        JSONObject idle = new JSONObject().put(
          "session",
          new JSONObject().put("role", "Idle")
        );
        JamUi.latest = idle;
        JamClock.clear();
        JamMirror.accept(activity, idle);
        Utils.runOnMainThread(() -> {
          try {
            local(video);
            Utils.runOnMainThreadDelayed(
              () -> JamClock.seekLocalWhenReady(video, position, 50),
              300
            );
          } catch (Exception e) {
            Utils.showToastLong(e.getMessage());
          }
        });
      } catch (Exception e) {
        Utils.showToastLong(e.getMessage());
      }
    });
  }
}
