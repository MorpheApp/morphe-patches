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
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.View;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Host clock samples are separate from queue revisions and use local monotonic time. */
public final class JamClock {

  public interface Bar {
    Object patch_jamCreateModel(
      long position,
      long duration,
      int color,
      boolean labelEnabled
    );

    default Object patch_jamModel(long position, long duration) {
      return patch_jamCreateModel(position, duration, 0xffffffff, true);
    }

    boolean patch_jamDragging();
    void patch_jamRestore(Object model);

    default void patch_jamClock(long position, long duration) {
      if (!patch_jamDragging()) patch_jamRestore(
        patch_jamModel(position, duration)
      );
    }
  }

  private static volatile MediaController controller;
  private static final Map<Bar, Object> bars = new WeakHashMap<>();
  private static JSONObject sample;
  private static long received,
    sequence = -1;
  private static String stream = "";
  private static boolean ticking;
  private static final String generation = UUID.randomUUID().toString();
  private static long serial;

  public static void capture(MediaSession session) {
    controller = session.getController();
  }

  /** YTM's MediaSession resolves this persistent ID in both native queue lanes. */
  static void playHost(long itemId) throws Exception {
    MediaController current = controller;
    if (current == null) throw new IllegalStateException(
      "Open the host player first"
    );
    PlaybackState state = current.getPlaybackState();
    if (state == null) throw new IllegalStateException(
      "Host player is not ready"
    );
    MediaController.TransportControls controls = current.getTransportControls();
    CompletableFuture<Void> playing = new CompletableFuture<>();
    Runnable confirm = new Runnable() {
      private boolean resumed;

      @Override
      public void run() {
        if (playing.isDone()) return;
        if (controller != current) {
          playing.completeExceptionally(
            new IllegalStateException("Host player changed; retry")
          );
          return;
        }
        try {
          PlaybackState next = current.getPlaybackState();
          if (next != null && next.getActiveQueueItemId() == itemId) {
            if (next.getState() == PlaybackState.STATE_PLAYING) {
              playing.complete(null);
              return;
            }
            if (!resumed && next.getState() == PlaybackState.STATE_PAUSED) {
              resumed = true;
              controls.play();
            }
          }
          JamUi.main.postDelayed(this, 100);
        } catch (Exception error) {
          playing.completeExceptionally(error);
        }
      }
    };
    // Never fall back to an enqueue endpoint. Wait off the native queue
    // executor so selection and MediaSession publication can finish.
    if (state.getActiveQueueItemId() != itemId) controls.skipToQueueItem(
      itemId
    );
    // Keep the callback on the handler used to cancel it in the finally block.
    JamUi.main.post(confirm);
    try {
      playing.get(8, TimeUnit.SECONDS);
    } catch (TimeoutException error) {
      throw new IllegalStateException(
        "Host did not confirm playback; check the host player"
      );
    } finally {
      playing.cancel(false);
      JamUi.main.removeCallbacks(confirm);
    }
  }

  /** Resolve skip behavior on the host; a guest's local player must never advance. */
  static void skipHost(boolean next) throws Exception {
    FutureTask<Void> task = new FutureTask<>(() -> {
      MediaController current = controller;
      if (current == null) throw new IllegalStateException(
        "Open the host player first"
      );
      PlaybackState state = current.getPlaybackState();
      long action = next
        ? PlaybackState.ACTION_SKIP_TO_NEXT
        : PlaybackState.ACTION_SKIP_TO_PREVIOUS;
      if (
        state == null || (state.getActions() & action) == 0
      ) throw new IllegalStateException(
        next ? "The host cannot skip forward" : "The host cannot skip backward"
      );
      if (next) current.getTransportControls().skipToNext();
      else current.getTransportControls().skipToPrevious();
      return null;
    });
    Utils.runOnMainThread(task);
    try {
      task.get(5, TimeUnit.SECONDS);
    } finally {
      task.cancel(false);
    }
  }

  static JSONObject snapshot() throws Exception {
    MediaController c = controller;
    JSONObject out = new JSONObject()
      .put("generation", generation)
      .put("playbackControl", true)
      .put("sequence", ++serial)
      .put("sampledAt", SystemClock.elapsedRealtime())
      .put("videoId", VideoInformation.getVideoId());
    if (c == null) return out;
    PlaybackState state = c.getPlaybackState();
    MediaMetadata metadata = c.getMetadata();
    if (state == null || metadata == null) return out;
    long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
      position = state.getPosition();
    boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
    if (playing) position += (long) (Math.max(
      0,
      SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()
    ) * state.getPlaybackSpeed());
    return out
      .put("position", Math.max(0, Math.min(duration, position)))
      .put("duration", duration)
      .put("speed", state.getPlaybackSpeed())
      .put("playing", playing);
  }

  static void accept(JSONObject view) {
    JSONObject session = view.optJSONObject("session");
    if (session == null) return;
    if (!"Participant".equals(session.optString("role"))) {
      clear();
      return;
    }
    JSONObject next = view.optJSONObject("clock");
    if (next == null || !next.has("duration")) return;
    long duration = next.optLong("duration"),
      position = next.optLong("position", -1);
    double speed = next.optDouble("speed", 1);
    if (!JamTime.valid(position, duration, speed)) return;
    String video = next.optString("videoId");
    JSONArray rows = view.optJSONArray("items");
    boolean matches = false;
    if (rows != null) for (int i = 0; i < rows.length(); i++) {
      JSONObject row = rows.optJSONObject(i);
      if (
        row != null &&
        row.optBoolean("current") &&
        video.equals(row.optString("videoId"))
      ) matches = true;
    }
    if (!matches) return;
    String g = next.optString("generation");
    long seq = next.optLong("sequence", -1);
    if (g.equals(stream) && seq <= sequence) return;
    stream = g;
    sequence = seq;
    sample = next;
    received =
      next.optLong("receivedAt", SystemClock.elapsedRealtime()) -
      Math.min(4000, Math.max(0, next.optLong("age")));
    JamPlayerState.refresh();
    if (!ticking) {
      ticking = true;
      Utils.runOnMainThread(tick);
    }
  }

  static long position() {
    return sample == null
      ? 0
      : JamTime.position(
          sample.optLong("position"),
          sample.optLong("duration"),
          sample.optDouble("speed", 1),
          sample.optBoolean("playing"),
          SystemClock.elapsedRealtime() - received
        );
  }

  static Boolean hostPlaying() {
    return sample == null ? null : sample.optBoolean("playing");
  }

  static boolean canControlHostPlayback() {
    return sample != null && sample.optBoolean("playbackControl");
  }

  /** Apply an explicit state to the current host item without selecting or restarting it. */
  static void setHostPlaying(String video, long itemId, boolean playing) throws Exception {
    FutureTask<Void> task = new FutureTask<>(() -> {
      MediaController current = controller;
      PlaybackState state = current == null ? null : current.getPlaybackState();
      if (state == null || state.getActiveQueueItemId() != itemId ||
          !video.equals(VideoInformation.getVideoId())) {
        throw new IllegalStateException("Host track changed; refresh before controlling playback");
      }
      int desired = playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
      if (state.getState() == desired) return null;
      long action = playing ? PlaybackState.ACTION_PLAY : PlaybackState.ACTION_PAUSE;
      if ((state.getActions() & (action | PlaybackState.ACTION_PLAY_PAUSE)) == 0) {
        throw new IllegalStateException("The host cannot change playback state");
      }
      if (playing) current.getTransportControls().play();
      else current.getTransportControls().pause();
      return null;
    });
    Utils.runOnMainThread(task);
    try {
      task.get(5, TimeUnit.SECONDS);
    } finally {
      task.cancel(false);
    }
  }

  public static Object model(Object view, Object local) {
    if (!(view instanceof Bar)) return local;
    Bar bar = (Bar) view;
    bars.put(bar, local);
    return sample == null
      ? local
      : bar.patch_jamModel(position(), sample.optLong("duration"));
  }

  private static final Runnable tick = new Runnable() {
    public void run() {
      if (sample == null) {
        ticking = false;
        return;
      }
      for (Bar bar : new ArrayList<>(bars.keySet()))
        try {
          bar.patch_jamClock(position(), sample.optLong("duration"));
        } catch (Exception error) {
          Logger.printDebug(() -> "Could not update Jam time bar", error);
        }
      Utils.runOnMainThreadDelayed(this, 200);
    }
  };

  static void clear() {
    boolean wasMirroring = sample != null;
    sample = null;
    stream = "";
    sequence = -1;
    JamPlayerState.refresh();
    if (wasMirroring) for (Map.Entry<Bar, Object> e : new ArrayList<>(
      bars.entrySet()
    ))
      try {
        e.getKey().patch_jamRestore(e.getValue());
      } catch (Exception error) {
        Logger.printInfo(() -> "Could not restore Jam time bar", error);
      }
    // Idle polls must not forget existing time bars. A paused guest can join
    // without another native model callback; the host clock still needs to
    // update that bar. Weak keys release detached views without a session reset.
  }

  public static boolean offerSeek(long target) {
    if (!JamMirror.active()) return false;
    if (sample == null) return true;
    long duration = sample.optLong("duration");
    if (duration <= 0) return true;
    long at = Math.max(0, Math.min(duration - 1, target));
    String video = sample.optString("videoId");
    Utils.runOnMainThread(() -> {
      Activity a = JamUi.activity(null);
      if (a == null || a.isFinishing() || !JamPlayback.claimDialog()) return;
      String time = String.format(
        Locale.ROOT,
        "%d:%02d",
        at / 60000,
        (at / 1000) % 60
      );
      AlertDialog d = new AlertDialog.Builder(a)
        .setTitle(String.format(str("morphe_music_jam_seek_to"), time))
        .setItems(
          new String[] {
            str("morphe_music_jam_move_host_here"),
            str("morphe_music_jam_quit_and_play"),
          },
          (w, index) -> {
            if (index == 0) {
              try {
                JamUi.edit(
                  a,
                  JamUi.command("SEEK")
                    .put("videoId", video)
                    .put("position", at)
                );
              } catch (Exception error) {
                Logger.printInfo(() -> "Could not send Jam seek", error);
              }
            } else JamPlayback.leaveAndPlay(a, video, at);
          }
        )
        .setNegativeButton(str("morphe_music_jam_cancel"), null)
        .setOnDismissListener(w -> JamPlayback.releaseDialog())
        .create();
      d.show();
      JamUi.styleDialog(d);
    });
    return true;
  }

  static void seekHost(String video, long position) throws Exception {
    FutureTask<Void> task = new FutureTask<>(() -> {
      MediaController c = controller;
      if (
        c == null || !video.equals(VideoInformation.getVideoId())
      ) throw new IllegalStateException("The host changed songs; try again");
      MediaMetadata m = c.getMetadata();
      long duration =
        m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
      if (
        position < 0 || position >= duration
      ) throw new IllegalArgumentException("Position is outside this song");
      c.getTransportControls().seekTo(position);
      return null;
    });
    Utils.runOnMainThread(task);
    task.get(5, TimeUnit.SECONDS);
  }

  static void seekLocalWhenReady(String video, long position, int attempts) {
    if (
      video.equals(VideoInformation.getVideoId()) &&
      controller != null &&
      controller.getPlaybackState() != null &&
      controller.getPlaybackState().getState() == PlaybackState.STATE_PLAYING
    ) {
      controller.getTransportControls().seekTo(position);
      return;
    }
    if (attempts > 0) Utils.runOnMainThreadDelayed(
      () -> seekLocalWhenReady(video, position, attempts - 1),
      200
    );
    else {
      Activity a = JamUi.activity(null);
      if (a != null) Utils.showToastLong(
        str("morphe_music_jam_seek_not_ready")
      );
    }
  }
}
