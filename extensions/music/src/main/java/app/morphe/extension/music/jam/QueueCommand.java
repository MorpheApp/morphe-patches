/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal protobuf adapter for the native YT Music queue endpoints used by Jam.
 *
 * <p>The large values below are protobuf extension field numbers from the
 * YT Music 9.15.51 InnerTube wire schema, not obfuscated Java identifiers.  The adapter keeps
 * the wire surface deliberately small: it reads/writes only the fields Jam
 * needs and rejects duplicate, unexpected-wire-type, or malformed fields.
 */
public final class QueueCommand {

  /** NavigationEndpoint.watchEndpoint extension field in the 9.15.51 wire schema. */
  private static final int WATCH_ENDPOINT_FIELD = 48_687_757;

  /**
   * Queue mutation endpoint extension observed in YT Music 9.15.51.
   *
   * <pre>
   * endpoint[QUEUE_EDIT_ENDPOINT_FIELD] {
   *   target[1] { video_id[1] }
   *   mode[2] = 1 (play next) | 2 (add to queue)
   * }
   * </pre>
   */
  private static final int QUEUE_EDIT_ENDPOINT_FIELD = 163_162_354;
  private static final int VIDEO_ID_FIELD = 1;
  private static final int QUEUE_EDIT_TARGET_FIELD = 1;
  private static final int QUEUE_EDIT_MODE_FIELD = 2;
  private static final int MODE_PLAY_NEXT = 1;
  private static final int MODE_ADD = 2;

  private QueueCommand() {}

  public static String watchVideo(byte[] data) {
    try {
      if (data == null || data.length > 65536) return null;
      byte[] video = field(field(data, WATCH_ENDPOINT_FIELD), VIDEO_ID_FIELD);
      String id = new String(video, StandardCharsets.US_ASCII);
      return id.matches("[A-Za-z0-9_-]{11}") ? id : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static byte[] watch(String video) {
    if (
      video == null || !video.matches("[A-Za-z0-9_-]{11}")
    ) throw new IllegalArgumentException("Invalid video ID");
    ByteArrayOutputStream body = new ByteArrayOutputStream(),
      endpoint = new ByteArrayOutputStream();
    bytes(body, VIDEO_ID_FIELD, video.getBytes(StandardCharsets.US_ASCII));
    bytes(endpoint, WATCH_ENDPOINT_FIELD, body.toByteArray());
    return endpoint.toByteArray();
  }

  /** Native music/get_queue context used to load the selected track's participant-side options. */
  public static byte[] menuRequest(String video) {
    if (
      video == null || !video.matches("[A-Za-z0-9_-]{11}")
    ) throw new IllegalArgumentException("Invalid video ID");
    ByteArrayOutputStream context = new ByteArrayOutputStream(),
      request = new ByteArrayOutputStream(),
      endpoint = new ByteArrayOutputStream();
    bytes(context, VIDEO_ID_FIELD, video.getBytes(StandardCharsets.US_ASCII));
    bytes(request, QUEUE_EDIT_TARGET_FIELD, context.toByteArray());
    bytes(endpoint, QUEUE_EDIT_ENDPOINT_FIELD, request.toByteArray());
    return endpoint.toByteArray();
  }

  /** Read only a single-track endpoint; playlists and unknown wire forms stay native. */
  public static String[] decode(byte[] data) {
    try {
      if (data == null || data.length > 65536) return null;
      byte[] operation = field(data, QUEUE_EDIT_ENDPOINT_FIELD);
      byte[] target = field(operation, QUEUE_EDIT_TARGET_FIELD);
      byte[] video = field(target, VIDEO_ID_FIELD);
      if (video == null) return null;
      String id = new String(video, StandardCharsets.US_ASCII);
      if (!id.matches("[A-Za-z0-9_-]{11}")) return null;
      int mode = integer(operation, QUEUE_EDIT_MODE_FIELD);
      return mode == MODE_PLAY_NEXT || mode == MODE_ADD
        ? new String[] { id, mode == MODE_PLAY_NEXT ? "PLAY_NEXT" : "ADD" }
        : null;
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  private static long read(byte[] data, int[] at) {
    long value = 0;
    for (int shift = 0; shift < 64; shift += 7) {
      if (at[0] >= data.length) throw new IllegalArgumentException();
      int b = data[at[0]++] & 255;
      value |= (long) (b & 127) << shift;
      if ((b & 128) == 0) return value;
    }
    throw new IllegalArgumentException();
  }

  private static Object find(byte[] data, int wanted, int wire) {
    if (data == null) throw new IllegalArgumentException();
    int[] at = { 0 };
    Object found = null;
    while (at[0] < data.length) {
      long tag = read(data, at);
      int kind = (int) (tag & 7);
      long number = tag >>> 3;
      if (number == 0) throw new IllegalArgumentException();
      Object value = null;
      if (kind == 0) value = read(data, at);
      else if (kind == 2) {
        long length = read(data, at);
        if (
          length < 0 || length > data.length - at[0]
        ) throw new IllegalArgumentException();
        if (number == wanted) value = java.util.Arrays.copyOfRange(
          data,
          at[0],
          at[0] + (int) length
        );
        at[0] += (int) length;
      } else if (kind == 1) at[0] += 8;
      else if (kind == 5) at[0] += 4;
      else throw new IllegalArgumentException();
      if (at[0] > data.length) throw new IllegalArgumentException();
      if (number == wanted) {
        if (kind != wire || found != null) throw new IllegalArgumentException();
        found = value;
      }
    }
    return found;
  }

  private static byte[] field(byte[] data, int field) {
    return (byte[]) find(data, field, 2);
  }

  private static int integer(byte[] data, int field) {
    Object value = find(data, field, 0);
    return value == null ? 0 : ((Long) value).intValue();
  }

  public static byte[] encode(String videoId, boolean playNext) {
    if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
      throw new IllegalArgumentException(
        "Enter an 11-character YouTube video ID"
      );
    }
    ByteArrayOutputStream target = new ByteArrayOutputStream();
    bytes(target, VIDEO_ID_FIELD, videoId.getBytes(StandardCharsets.US_ASCII));
    // Do not set target field 3: that selects the downloaded/local-media route.
    ByteArrayOutputStream operation = new ByteArrayOutputStream();
    bytes(operation, QUEUE_EDIT_TARGET_FIELD, target.toByteArray());
    varint(operation, QUEUE_EDIT_MODE_FIELD << 3);
    varint(operation, playNext ? MODE_PLAY_NEXT : MODE_ADD);
    ByteArrayOutputStream endpoint = new ByteArrayOutputStream();
    bytes(endpoint, QUEUE_EDIT_ENDPOINT_FIELD, operation.toByteArray());
    return endpoint.toByteArray();
  }

  private static void bytes(
    ByteArrayOutputStream out,
    int field,
    byte[] value
  ) {
    varint(out, (field << 3) | 2);
    varint(out, value.length);
    out.write(value, 0, value.length);
  }

  private static void varint(ByteArrayOutputStream out, int value) {
    while ((value & ~0x7f) != 0) {
      out.write((value & 0x7f) | 0x80);
      value >>>= 7;
    }
    out.write(value);
  }
}
