/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import app.morphe.jam.ipc.BridgeProtocol;
import app.morphe.jam.ipc.IJamBridge;
import app.morphe.jam.ipc.Trust;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

public final class JamBridgeService extends Service {

  private static final Object LOCK = new Object();
  private static final Map<String, String> results = new HashMap<>(),
    requests = new HashMap<>();
  private static final String generation = UUID.randomUUID().toString();
  private static String signature = "";
  private static long revision;
  private final IJamBridge.Stub binder = new IJamBridge.Stub() {
    public String call(String capability, String request) {
      Trust.caller(
        JamBridgeService.this,
        getSharedPreferences("jam", 0).getString(
          "companionPackage",
          Trust.COMPANION
        )
      );
      Trust.capability(
        getSharedPreferences("jam", 0).getString("cap", null),
        capability
      );
      if (
        request == null || request.length() > 32768
      ) throw new IllegalArgumentException("Request size");
      synchronized (LOCK) {
        try {
          return BridgeProtocol.advertise(
            execute(BridgeProtocol.validate(new JSONObject(request)))
          ).toString();
        } catch (Exception e) {
          return error(e.getMessage()).toString();
        }
      }
    }
  };

  @Override
  public IBinder onBind(Intent intent) {
    return JamUi.enabled() ? binder : null;
  }

  static JSONObject error(String s) {
    try {
      return new JSONObject()
        .put("ok", false)
        .put("error", s == null ? "Native queue operation failed" : s);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static <T> T nativeCall(Callable<T> call) throws Exception {
    YtmBridge.QueueAccess access = YtmBridge.access();
    FutureTask<T> task = new FutureTask<>(() -> {
      if (YtmBridge.access() != access) throw new IllegalStateException(
        "Player changed"
      );
      return call.call();
    });
    access.patch_jamExecutor().execute(task);
    try {
      return task.get(8, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      task.cancel(false);
      throw new IllegalStateException(
        "Native operation timed out; refresh queue"
      );
    }
  }

  private static JSONObject snapshotOnExecutor(YtmBridge.QueueAccess a)
    throws Exception {
    if (!a.patch_jamLocal()) throw new IllegalStateException(
      "Jam supports local playback only; stop casting first"
    );
    Object[] items = a.patch_jamItems();
    if (items.length > 500) throw new IllegalStateException(
      "Queue exceeds 500-item Jam limit"
    );
    Object[] autoplay = a.patch_jamAutoplayItems();
    if (items.length + autoplay.length > 500) throw new IllegalStateException(
      "Queue exceeds 500-item Jam limit"
    );
    JSONArray rows = new JSONArray();
    StringBuilder key = new StringBuilder();
    int current = a.patch_jamCurrent();
    for (int i = 0; i < items.length; i++) {
      Object item = items[i];
      String id = Long.toString(a.patch_jamItemId(item));
      String video = a.patch_jamVideoId(item);
      String title = a.patch_jamTitle(item);
      String artist = a.patch_jamArtist(item);
      rows.put(
        new JSONObject()
          .put("id", id)
          .put("videoId", video)
          .put(
            "title",
            title == null
              ? video
              : title.substring(0, Math.min(title.length(), 200))
          )
          .put(
            "artist",
            artist == null
              ? ""
              : artist.substring(0, Math.min(artist.length(), 200))
          )
          .put("current", i == current)
      );
      key.append(id).append(':').append(video).append(';');
    }
    if (current >= 0 && current < items.length) rows
      .getJSONObject(current)
      .put(
        "thumbnail",
        QueueModel.thumbnail(
          YtmBridge.thumbnail(items[current]),
          a.patch_jamVideoId(items[current])
        )
      );
    JSONArray suggested = new JSONArray();
    key.append("|autoplay|");
    for (Object item : autoplay) {
      String id = Long.toString(a.patch_jamItemId(item)),
        video = a.patch_jamVideoId(item),
        title = a.patch_jamTitle(item),
        artist = a.patch_jamArtist(item);
      suggested.put(
        new JSONObject()
          .put("id", id)
          .put("videoId", video)
          .put(
            "title",
            title == null
              ? video
              : title.substring(0, Math.min(title.length(), 200))
          )
          .put(
            "artist",
            artist == null
              ? ""
              : artist.substring(0, Math.min(artist.length(), 200))
          )
          .put("current", false)
      );
      key.append(id).append(':').append(video).append(';');
    }
    key
      .append(rows.toString())
      .append(suggested.toString())
      .append('@')
      .append(current);
    String next = key.toString();
    if (!signature.equals(next)) {
      signature = next;
      revision++;
    }
    return new JSONObject()
      .put("ok", true)
      .put("revision", generation + ":" + revision)
      .put("items", rows)
      .put("autoplay", suggested)
      .put("clock", JamClock.snapshot());
  }

  private static JSONObject snapshot() throws Exception {
    return nativeCall(() -> snapshotOnExecutor(YtmBridge.access()));
  }

  private static JSONObject execute(JSONObject r) throws Exception {
    String op = r.getString("op");
    if ("HELLO".equals(op)) return new JSONObject().put("ok", true);
    if ("SNAPSHOT".equals(op)) return snapshot();
    if (
      !Arrays.asList(
        "ADD",
        "PLAY_NEXT",
        "REMOVE",
        "MOVE",
        "PLAY",
        "SEEK",
        "SKIP_NEXT",
        "SKIP_PREVIOUS"
      ).contains(op)
    ) return error("Unsupported operation");
    String id = r.getString("id");
    if (!UUID.fromString(id).toString().equals(id)) return error(
      "Invalid command id"
    );
    String body = r.toString();
    if (requests.containsKey(id)) return body.equals(requests.get(id))
      ? new JSONObject(results.get(id))
      : error("Command id reused");
    if (requests.size() >= 8192) return error(
      "Restart YouTube Music to reset command capacity"
    );
    requests.put(id, body);
    JSONObject result;
    try {
      if ("SKIP_NEXT".equals(op) || "SKIP_PREVIOUS".equals(op)) {
        snapshot(); // Validate the host's local queue before controlling playback.
        JamClock.skipHost("SKIP_NEXT".equals(op));
        result = snapshot().put("dispatched", true);
      } else if ("SEEK".equals(op)) {
        JamClock.seekHost(r.getString("videoId"), r.getLong("position"));
        result = snapshot().put("dispatched", true);
      } else if ("PLAY".equals(op)) {
        String video = r.getString("videoId"),
          itemId = r.getString("item");
        long selectedId = nativeCall(() -> {
          YtmBridge.QueueAccess a = YtmBridge.access();
          snapshotOnExecutor(a);
          Object selected = null;
          for (Object item : a.patch_jamItems())
            if (
              itemId.equals(Long.toString(a.patch_jamItemId(item))) &&
              video.equals(a.patch_jamVideoId(item))
            ) {
              selected = item;
              break;
            }
          if (selected == null) for (Object item : a.patch_jamAutoplayItems())
            if (
              itemId.equals(Long.toString(a.patch_jamItemId(item))) &&
              video.equals(a.patch_jamVideoId(item))
            ) {
              selected = item;
              break;
            }
          if (selected == null) throw new IllegalStateException(
            "Track is no longer in the host queue"
          );
          return a.patch_jamItemId(selected);
        });
        if (r.has("playing")) {
          JamClock.setHostPlaying(video, selectedId, r.getBoolean("playing"));
          result = snapshot().put("dispatched", true);
        } else {
          JamClock.playHost(selectedId);
          result = snapshot().put("confirmed", true);
        }
      } else if ("ADD".equals(op) || "PLAY_NEXT".equals(op)) {
        String video = r.getString("videoId");
        byte[] command = QueueCommand.encode(video, "PLAY_NEXT".equals(op));
        JamCompletion.Ticket ticket = nativeCall(() -> {
          YtmBridge.QueueAccess a = YtmBridge.access();
          snapshotOnExecutor(a);
          JamCompletion.Ticket pending = JamCompletion.begin();
          try {
            a.patch_jamEnqueue(command);
            return pending;
          } finally {
            JamCompletion.end();
          }
        });
        Set<String> inserted = ticket.result.get(10, TimeUnit.SECONDS);
        result = error(
          "Insertion outcome unknown; refresh before adding again"
        );
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(9);
        while (System.nanoTime() < deadline) {
          Thread.sleep(150);
          JSONObject s = snapshot();
          JSONArray rows = s.getJSONArray("items");
          boolean found = false;
          for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.getJSONObject(i);
            if (
              video.equals(item.optString("videoId")) &&
              inserted.contains(item.getString("id"))
            ) {
              found = true;
              break;
            }
          }
          if (found) {
            result = s.put("confirmed", true);
            break;
          }
        }
      } else result = nativeCall(() -> {
        YtmBridge.QueueAccess a = YtmBridge.access();
        JSONObject s = snapshotOnExecutor(a);
        if (
          !s.getString("revision").equals(r.getString("revision"))
        ) return error("Queue changed; refresh and retry").put("snapshot", s);
        int lane = r.optInt("lane", 0);
        if (lane != 0 && lane != 1) return error("Invalid queue lane");
        JSONArray rows = s.getJSONArray(lane == 0 ? "items" : "autoplay");
        int from = -1,
          to = -1;
        for (int i = 0; i < rows.length(); i++) {
          String item = rows.getJSONObject(i).getString("id");
          if (item.equals(r.getString("item"))) from = i;
          if (item.equals(r.optString("target"))) to = i;
        }
        if (from < 0 || ("MOVE".equals(op) && to < 0)) return error(
          "Queue item no longer exists"
        );
        if ("REMOVE".equals(op)) a.patch_jamRemoveFrom(lane, from);
        else a.patch_jamMoveFrom(lane, from, to);
        return snapshotOnExecutor(a).put("confirmed", true);
      });
    } catch (TimeoutException e) {
      result = error(
        "Insertion outcome unknown; wait for queue sync before adding again"
      );
    } catch (Exception e) {
      result = error(e.getMessage());
    }
    results.put(id, result.toString());
    return result;
  }
}
