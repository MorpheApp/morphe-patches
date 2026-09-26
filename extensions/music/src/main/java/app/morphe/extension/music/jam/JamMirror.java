/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.jam.ipc.QueueEdits;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native adapters display authoritative state plus unacknowledged local gestures. */
public final class JamMirror {

  public interface AutoplayUi {
    void patch_jamAutoplayLimit(int count);
    void patch_jamRefreshAutoplayUi();
  }

  private static final Set<AutoplayUi> autoplayViews =
    Collections.newSetFromMap(new WeakHashMap<>());

  public static void autoplayUi(AutoplayUi view) {
    autoplayViews.add(view);
    NativeQueueList lane = autoplay;
    if (mirror != null && lane != null) view.patch_jamAutoplayLimit(
      lane.size()
    );
  }

  private static void refreshAutoplayUi() {
    for (AutoplayUi view : new ArrayList<>(autoplayViews))
      view.patch_jamRefreshAutoplayUi();
  }

  private static volatile NativeQueueList mirror, autoplay;
  private static Object original, originalAutoplay;
  private static YtmBridge.QueueAccess owner;
  private static Context context;
  private static volatile JSONObject snapshot;
  private static String rendered = "";
  private static volatile int current = -1;
  private static final QueueEdits edits = new QueueEdits();
  private static boolean sending;
  private static long epoch;

  public static void accept(Context c, JSONObject view) {
    context = c;
    JSONObject session = view.optJSONObject("session");
    if (session == null) return;
    boolean participant = "Participant".equals(session.optString("role"));
    YtmBridge.QueueAccess access;
    try {
      access = YtmBridge.access();
    } catch (Exception e) {
      Logger.printDebug(() -> "Jam queue bridge is not ready", e);
      return;
    }
    access.patch_jamViewThread(() -> {
      try {
        if (!participant) {
          if (
            owner == access &&
            mirror != null &&
            access.patch_jamDisplayedList() == mirror
          ) access.patch_jamDisplayedList(original);
          if (
            owner == access &&
            autoplay != null &&
            access.patch_jamDisplayedAutoplay() == autoplay
          ) access.patch_jamDisplayedAutoplay(originalAutoplay);
          boolean wasActive = mirror != null;
          mirror = null;
          autoplay = null;
          original = null;
          originalAutoplay = null;
          owner = null;
          snapshot = null;
          rendered = "";
          current = -1;
          edits.clear();
          sending = false;
          epoch++;
          if (wasActive) {
            refreshAutoplayUi();
            JamArtwork.clear();
            JamPlayback.refresh();
          }
          return;
        }
        if (!view.has("items")) return;
        if (owner != access) {
          mirror = null;
          autoplay = null;
          rendered = "";
          owner = access;
          original = access.patch_jamDisplayedList();
          originalAutoplay = access.patch_jamDisplayedAutoplay();
          edits.clear();
          sending = false;
          epoch++;
        }
        if (original == null) {
          original = access.patch_jamDisplayedList();
          if (original == null) return;
        }
        if (mirror == null) {
          originalAutoplay = access.patch_jamDisplayedAutoplay();
          mirror = new NativeQueueList();
          autoplay = new NativeQueueList();
        }
        edits.accept(view);
        render();
      } catch (Exception e) {
        Logger.printException(() -> "Native mirror failed", e);
      }
    });
  }

  private static void render() throws Exception {
    JSONObject view = edits.view();
    if (view == null || owner == null) return;
    JSONArray rows = view.getJSONArray("items"),
      suggested = view.optJSONArray("autoplay");
    String signature = rows.toString() + String.valueOf(suggested);
    int playing = -1;
    for (int i = 0; i < rows.length(); i++) if (
      rows.getJSONObject(i).optBoolean("current")
    ) {
      playing = i;
      break;
    }
    boolean changed = !signature.equals(rendered),
      currentChanged = current != playing;
    snapshot = view;
    current = playing;
    if (changed) {
      List<Object> next = new ArrayList<>(),
        future = new ArrayList<>();
      for (int i = 0; i < rows.length(); i++) next.add(
        item(rows.getJSONObject(i))
      );
      if (suggested != null) for (
        int i = 0;
        i < suggested.length();
        i++
      ) future.add(item(suggested.getJSONObject(i)));
      mirror.replace(next);
      autoplay.replace(future);
      rendered = signature;
    }
    boolean refreshed = changed;
    if (owner.patch_jamDisplayedList() != mirror) {
      owner.patch_jamDisplayedList(mirror);
      refreshed = true;
    } else if (changed) owner.patch_jamRefreshDisplay();
    if (owner.patch_jamDisplayedAutoplay() != autoplay) {
      owner.patch_jamDisplayedAutoplay(autoplay);
      refreshed = true;
    } else if (changed) owner.patch_jamRefreshAutoplay();
    if (playing >= 0) {
      JSONObject row = rows.getJSONObject(playing);
      JamArtwork.update(
        QueueModel.thumbnail(
          row.optString("thumbnail"),
          row.getString("videoId")
        )
      );
    } else JamArtwork.clear();
    if (refreshed || currentChanged) {
      refreshAutoplayUi();
      JamPlayback.refresh();
    }
  }

  private static Object item(JSONObject row) throws Exception {
    return owner.patch_jamCreateItem(
      QueueModel.encode(
        row.getString("videoId"),
        row.optString("title"),
        row.optString("artist"),
        row.optString("thumbnail")
      ),
      Long.parseLong(row.getString("id"))
    );
  }

  public static Object now() {
    NativeQueueList list = mirror;
    int index = current;
    return list != null && index >= 0 && index < list.size()
      ? list.get(index)
      : null;
  }

  static String title() {
    return nowText("title");
  }

  static String artist() {
    return nowText("artist");
  }

  private static String nowText(String key) {
    JSONObject view = snapshot;
    int index = current;
    JSONArray rows = view == null ? null : view.optJSONArray("items");
    JSONObject row =
      rows == null || index < 0 ? null : rows.optJSONObject(index);
    return row == null ? null : row.optString(key, "");
  }

  public static int current(Object list) {
    return list == mirror ? current : list == autoplay ? -1 : -2;
  }

  public static int selection(Object item) {
    NativeQueueList main = mirror,
      future = autoplay;
    if (main == null) return -1;
    int index = main.indexOf(item);
    return index >= 0
      ? index == current
        ? 1
        : 0
      : future != null && future.contains(item)
        ? 0
        : -1;
  }

  public static boolean active() {
    return mirror != null;
  }

  public static boolean move(Object list, int from, int to) {
    return (
      (list == mirror || list == autoplay) &&
      move(list == autoplay ? 1 : 0, from, to)
    );
  }

  public static boolean remove(Object nativeItem) {
    if (
      mirror == null ||
      owner == null ||
      (!mirror.contains(nativeItem) && !autoplay.contains(nativeItem))
    ) return false;
    try {
      queue(
        JamUi.command("REMOVE")
          .put("item", Long.toString(owner.patch_jamItemId(nativeItem)))
          .put("lane", autoplay.contains(nativeItem) ? 1 : 0)
      );
      return true;
    } catch (Exception e) {
      Logger.printInfo(() -> "Could not remove Jam queue item", e);
      return false;
    }
  }

  public static boolean move(int lane, int from, int to) {
    if (mirror == null) return false;
    try {
      JSONArray rows = snapshot.getJSONArray(lane == 0 ? "items" : "autoplay");
      if (from != to) queue(
        JamUi.command("MOVE")
          .put("item", rows.getJSONObject(from).getString("id"))
          .put("anchor", rows.getJSONObject(to).getString("id"))
          .put("after", from < to)
          .put("lane", lane)
      );
    } catch (Exception e) {
      Utils.showToastLong(str("morphe_music_jam_queue_changed_gesture"));
    }
    return true;
  }

  private static void queue(JSONObject command) throws Exception {
    edits.add(command);
    // Publish the optimistic order after the native gesture callback has returned.
    owner.patch_jamViewThread(() -> {
      try {
        render();
        sendNext();
      } catch (Exception e) {
        Utils.showToastLong(e.getMessage());
      }
    });
  }

  private static void sendNext() {
    if (sending || !edits.busy()) return;
    final long generation = epoch;
    try {
      JSONObject request = edits.next();
      sending = true;
      JamUi.call(context, request, response -> {
        if (generation != epoch) return;
        sending = false;
        try {
          edits.complete(response);
          render();
        } catch (Exception e) {
          Logger.printException(() -> "Edit acknowledgement failed", e);
        }
        if (!response.optBoolean("ok")) Utils.showToastLong(
          response.optString("error")
        );
        sendNext();
      });
    } catch (Exception e) {
      try {
        edits.complete(new JSONObject());
        render();
      } catch (Exception cleanupError) {
        Logger.printInfo(
          () -> "Could not roll back Jam queue edit",
          cleanupError
        );
      }
      Utils.showToastLong(e.getMessage());
      sendNext();
    }
  }
}
