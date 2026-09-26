/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Sanitized native display model: content ID and public display metadata only. */
public final class QueueModel {

  // Protobuf field numbers verified against the YT Music 9.15.51 InnerTube wire schema.
  // The large values are extension numbers, not Java/DEX names.
  private static final int WATCH_ENDPOINT_FIELD = 48_687_757;
  private static final int MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_FIELD =
    51_779_701;
  private static final int LABEL_TEXT_FIELD = 4;
  private static final int RENDERER_TITLE_FIELD = 1;
  private static final int RENDERER_SUBTITLE_FIELD = 2;
  private static final int RENDERER_THUMBNAIL_FIELD = 3;
  private static final int RENDERER_NAVIGATION_ENDPOINT_FIELD = 7;
  private static final int RENDERER_VIDEO_ID_FIELD = 10;
  private static final int RENDERER_SECONDARY_SUBTITLE_FIELD = 12;
  private static final int WRAPPER_RENDERER_FIELD = 1;
  private static final int WATCH_VIDEO_ID_FIELD = 1;
  private static final int THUMBNAIL_LIST_FIELD = 1;
  private static final int THUMBNAIL_URL_FIELD = 1;

  private static byte[] field(int number, byte[] value) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    number(out, ((long) number << 3) | 2);
    number(out, value.length);
    out.write(value, 0, value.length);
    return out.toByteArray();
  }

  private static byte[] text(int number, String value) {
    return field(number, value.getBytes(StandardCharsets.UTF_8));
  }

  private static void number(ByteArrayOutputStream out, long value) {
    while ((value & ~127L) != 0) {
      out.write(((int) value & 127) | 128);
      value >>>= 7;
    }
    out.write((int) value);
  }

  private static byte[] concat(byte[]... values) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] value : values) out.write(value, 0, value.length);
    return out.toByteArray();
  }

  private static byte[] label(String value) {
    return text(LABEL_TEXT_FIELD, value);
  }

  public static byte[] encode(String video, String title, String artist) {
    return encode(video, title, artist, "");
  }

  public static String thumbnail(String url, String video) {
    if (url != null && url.length() < 2048) try {
      java.net.URI uri = java.net.URI.create(url);
      String host = uri.getHost();
      if (
        "https".equals(uri.getScheme()) &&
        uri.getUserInfo() == null &&
        uri.getPort() == -1 &&
        host != null &&
        (host.equals("i.ytimg.com") ||
          host.equals("lh3.googleusercontent.com") ||
          host.equals("lh3.ggpht.com"))
      ) return url;
    } catch (IllegalArgumentException ignored) {
      // Invalid artwork URLs use the known YouTube thumbnail fallback.
    }
    return "https://i.ytimg.com/vi/" + video + "/hqdefault.jpg";
  }

  public static byte[] encode(
    String video,
    String title,
    String artist,
    String picture
  ) {
    if (!video.matches("[A-Za-z0-9_-]{11}")) throw new IllegalArgumentException(
      "Invalid video ID"
    );
    /*
     * Native music-responsive-list-item shape used by the queue-item
     * constructor resolved by the patch.  The title/subtitle fields are
     * text runs; field 12 mirrors the artist in the secondary subtitle
     * slot used by this target; field 3 carries the thumbnail; field 7 is
     * its watch navigation endpoint; field 10 is the stable video ID.
     */
    byte[] watch = field(
      WATCH_ENDPOINT_FIELD,
      text(WATCH_VIDEO_ID_FIELD, video)
    );
    byte[] thumbnail = field(
      THUMBNAIL_LIST_FIELD,
      text(THUMBNAIL_URL_FIELD, thumbnail(picture, video))
    );
    byte[] renderer = concat(
      field(RENDERER_TITLE_FIELD, label(title)),
      field(RENDERER_SUBTITLE_FIELD, label(artist)),
      field(RENDERER_SECONDARY_SUBTITLE_FIELD, label(artist)),
      field(RENDERER_THUMBNAIL_FIELD, thumbnail),
      field(RENDERER_NAVIGATION_ENDPOINT_FIELD, watch),
      text(RENDERER_VIDEO_ID_FIELD, video)
    );
    return field(
      WRAPPER_RENDERER_FIELD,
      field(MUSIC_RESPONSIVE_LIST_ITEM_RENDERER_FIELD, renderer)
    );
  }
}
