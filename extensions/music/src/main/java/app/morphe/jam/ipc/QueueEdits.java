package app.morphe.jam.ipc;

import java.util.*;
import org.json.*;

/** Main-thread optimistic edit journal. Old polls cannot undo acknowledged edits. */
public final class QueueEdits {

  private JSONObject base, deferred;
  private final ArrayDeque<JSONObject> pending = new ArrayDeque<>();

  public boolean busy() {
    return !pending.isEmpty();
  }

  public void clear() {
    base = null;
    deferred = null;
    pending.clear();
  }

  public static boolean older(JSONObject candidate, JSONObject reference) {
    if (reference == null) return false;
    String a = candidate.optString("revision"),
      b = reference.optString("revision");
    int x = a.lastIndexOf(':'),
      y = b.lastIndexOf(':');
    if (
      x < 0 || y < 0 || !a.substring(0, x).equals(b.substring(0, y))
    ) return false;
    try {
      return (
        Long.parseLong(a.substring(x + 1)) < Long.parseLong(b.substring(y + 1))
      );
    } catch (NumberFormatException e) {
      return false;
    }
  }

  private static JSONObject copy(JSONObject value) throws JSONException {
    return new JSONObject(value.toString());
  }

  public void accept(JSONObject value) throws JSONException {
    if (!value.has("items") || older(value, base)) return;
    if (busy()) {
      if (deferred == null || !older(value, deferred)) deferred = copy(value);
    } else base = copy(value);
  }

  public void add(JSONObject edit) {
    pending.add(edit);
  }

  private static int find(JSONArray rows, String id) throws JSONException {
    for (int i = 0; i < rows.length(); i++) if (
      id.equals(rows.getJSONObject(i).getString("id"))
    ) return i;
    return -1;
  }

  private static String lane(JSONObject edit) {
    return edit.optInt("lane") == 1 ? "autoplay" : "items";
  }

  public JSONObject view() throws JSONException {
    if (base == null) return null;
    JSONObject result = copy(base);
    for (JSONObject edit : pending) {
      JSONArray rows = result.getJSONArray(lane(edit));
      int from = find(rows, edit.getString("item"));
      if (from < 0) continue;
      List<JSONObject> list = new ArrayList<>();
      for (int i = 0; i < rows.length(); i++) list.add(rows.getJSONObject(i));
      JSONObject item = list.remove(from);
      if ("MOVE".equals(edit.getString("op"))) {
        int anchor = find(new JSONArray(list), edit.getString("anchor"));
        if (anchor < 0) {
          list.add(from, item);
        } else list.add(anchor + (edit.optBoolean("after") ? 1 : 0), item);
      }
      result.put(lane(edit), new JSONArray(list));
    }
    return result;
  }

  public JSONObject next() throws JSONException {
    if (base == null || pending.isEmpty()) return null;
    JSONObject edit = pending.peek();
    JSONArray rows = base.getJSONArray(lane(edit));
    int from = find(rows, edit.getString("item"));
    if (from < 0) throw new IllegalStateException(
      "This track was removed from the host queue"
    );
    JSONObject request = copy(edit).put("revision", base.getString("revision"));
    request.remove("anchor");
    request.remove("after");
    if ("MOVE".equals(edit.getString("op"))) {
      int anchor = find(rows, edit.getString("anchor"));
      if (anchor < 0) throw new IllegalStateException(
        "The destination track was removed"
      );
      int target =
        anchor - (from < anchor ? 1 : 0) + (edit.optBoolean("after") ? 1 : 0);
      request.put(
        "target",
        rows
          .getJSONObject(Math.max(0, Math.min(rows.length() - 1, target)))
          .getString("id")
      );
    }
    return request;
  }

  public void complete(JSONObject response) throws JSONException {
    if (pending.isEmpty()) return;
    pending.remove();
    JSONObject value = response.has("items")
      ? response
      : response.optJSONObject("snapshot");
    if (value != null && !older(value, base)) base = copy(value);
    if (deferred != null && !older(deferred, base)) base = deferred;
    deferred = null;
  }
}
