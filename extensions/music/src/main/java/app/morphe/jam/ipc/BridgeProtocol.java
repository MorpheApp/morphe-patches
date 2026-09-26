package app.morphe.jam.ipc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Additive JSON envelope for the authenticated local Binder bridge, not the network wire. */
public final class BridgeProtocol {

  public static final int VERSION = 1;
  private static final String KEY = "bridgeProtocol";
  private static final String[] FEATURES = {
    "queue-revisions",
    "stable-item-ids",
    "stale-edit-rejection",
  };

  private BridgeProtocol() {}

  public static JSONObject advertise(JSONObject message) throws JSONException {
    return message.put(
      KEY,
      new JSONObject()
        .put("version", VERSION)
        .put("minimumVersion", VERSION)
        .put("features", new JSONArray(Arrays.asList(FEATURES)))
    );
  }

  /** Missing envelope means the original v1 bridge. A malformed envelope is never legacy. */
  public static JSONObject validate(JSONObject message) throws JSONException {
    if (!message.has(KEY)) return message;
    JSONObject protocol = message.getJSONObject(KEY);
    int version = positiveInteger(protocol, "version");
    int minimum = positiveInteger(protocol, "minimumVersion");
    if (minimum > version || minimum > VERSION || version < VERSION) {
      throw new IllegalArgumentException(
        "Incompatible Jam bridge version; update Jam Layer and the Jam patch together"
      );
    }
    Set<String> features = strings(protocol.getJSONArray("features"));
    if (!features.containsAll(Arrays.asList(FEATURES))) {
      throw new IllegalArgumentException(
        "Jam bridge is missing required queue capabilities"
      );
    }
    if (
      protocol.has("requires") &&
      !new HashSet<>(Arrays.asList(FEATURES)).containsAll(
        strings(protocol.getJSONArray("requires"))
      )
    ) {
      throw new IllegalArgumentException(
        "Jam bridge requires unsupported capabilities"
      );
    }
    return message;
  }

  private static int positiveInteger(JSONObject object, String key)
    throws JSONException {
    Object value = object.get(key);
    if (!(value instanceof Integer) || (Integer) value < 1) {
      throw new IllegalArgumentException("Invalid Jam bridge " + key);
    }
    return (Integer) value;
  }

  private static Set<String> strings(JSONArray array) throws JSONException {
    Set<String> values = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      Object value = array.get(i);
      if (!(value instanceof String) || ((String) value).isEmpty()) {
        throw new IllegalArgumentException("Invalid Jam bridge capability");
      }
      values.add((String) value);
    }
    return values;
  }
}
