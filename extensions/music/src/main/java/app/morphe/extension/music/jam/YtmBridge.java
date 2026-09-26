/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/** Native operations run only on YouTube Music's own queue executor. */
public final class YtmBridge {

  public interface QueueAccess {
    void patch_jamEnqueue(byte[] command);
    Executor patch_jamExecutor();

    default Object[] patch_jamItems() {
      return patch_jamLaneItems(0);
    }

    default Object[] patch_jamLaneItems(int lane) {
      throw new UnsupportedOperationException();
    }

    default String patch_jamVideoId(Object item) {
      throw new UnsupportedOperationException();
    }

    default String patch_jamTitle(Object item) {
      return item instanceof MetadataAccess
        ? ((MetadataAccess) item).patch_jamTitle()
        : patch_jamVideoId(item);
    }

    default long patch_jamItemId(Object item) {
      throw new UnsupportedOperationException();
    }

    default int patch_jamCurrent() {
      throw new UnsupportedOperationException();
    }

    default boolean patch_jamLocal() {
      throw new UnsupportedOperationException();
    }

    default void patch_jamRemove(int index) {
      patch_jamRemoveFrom(0, index);
    }

    default void patch_jamMove(int from, int to) {
      patch_jamMoveFrom(0, from, to);
    }

    default String patch_jamArtist(Object item) {
      return item instanceof MetadataAccess
        ? ((MetadataAccess) item).patch_jamArtist()
        : "";
    }

    default java.util.concurrent.Future<?> patch_jamRequestMenu(
      byte[] command
    ) {
      throw new UnsupportedOperationException();
    }

    default Object[] patch_jamMenuItems(Object response) {
      throw new UnsupportedOperationException();
    }

    default Object patch_jamCreateItem(byte[] data, long id) {
      throw new UnsupportedOperationException();
    }

    default Object patch_jamDisplayedList() {
      throw new UnsupportedOperationException();
    }

    default void patch_jamDisplayedList(Object list) {
      throw new UnsupportedOperationException();
    }

    default void patch_jamRefreshDisplay() {
      throw new UnsupportedOperationException();
    }

    default void patch_jamViewThread(Runnable task) {
      throw new UnsupportedOperationException();
    }

    default Object[] patch_jamAutoplayItems() {
      return patch_jamLaneItems(1);
    }

    default Object patch_jamDisplayedAutoplay() {
      throw new UnsupportedOperationException();
    }

    default void patch_jamDisplayedAutoplay(Object list) {
      throw new UnsupportedOperationException();
    }

    default void patch_jamRefreshAutoplay() {
      throw new UnsupportedOperationException();
    }

    default void patch_jamRemoveItem(Object item) {
      throw new UnsupportedOperationException();
    }

    default void patch_jamMoveLane(int lane, int from, int to) {
      throw new UnsupportedOperationException();
    }

    default void patch_jamNotifyMove(Object item, Object predecessor) {
      throw new UnsupportedOperationException();
    }

    default void patch_jamRemoveFrom(int lane, int index) {
      checkLane(lane);
      patch_jamRemoveItem(patch_jamLaneItems(lane)[index]);
    }

    default void patch_jamMoveFrom(int lane, int from, int to) {
      checkLane(lane);
      int size = patch_jamLaneItems(lane).length;
      if (from < 0 || to < 0 || from >= size || to >= size) {
        throw new IndexOutOfBoundsException("Queue changed; retry the move");
      }
      if (from == to) return;
      patch_jamMoveLane(lane, from, to);
      Object[] reordered = patch_jamLaneItems(lane);
      patch_jamNotifyMove(reordered[to], to == 0 ? null : reordered[to - 1]);
    }
  }

  private static void checkLane(int lane) {
    if (lane != 0 && lane != 1) throw new IllegalArgumentException(
      "Invalid queue lane"
    );
  }

  /** Stable projections added to concrete native queue-item implementations. */
  public interface ItemAccess {
    Object patch_jamArtwork();
    Object patch_jamMenuPayload();
  }

  /** Optional metadata capability; fallback choices stay in Java. */
  public interface MetadataAccess {
    String patch_jamTitle();
    String patch_jamArtist();
  }

  public interface ArtworkAccess {
    Object[] patch_jamThumbnailEntries();
  }

  public interface ThumbnailAccess {
    String patch_jamThumbnailUrl();
  }

  public interface DispatchCallback {
    void complete(String error);
  }

  private static volatile WeakReference<QueueAccess> queue =
    new WeakReference<>(null);

  private YtmBridge() {}

  static QueueAccess access() {
    QueueAccess access = queue.get();
    if (access == null) throw new IllegalStateException(
      "Start a song in YouTube Music first"
    );
    return access;
  }

  static String thumbnail(Object item) {
    if (!(item instanceof ItemAccess)) return "";
    Object artwork = ((ItemAccess) item).patch_jamArtwork();
    if (!(artwork instanceof ArtworkAccess)) return "";
    Object[] entries = ((ArtworkAccess) artwork).patch_jamThumbnailEntries();
    if (entries == null) return "";
    for (int index = entries.length - 1; index >= 0; index--) {
      Object entry = entries[index];
      if (!(entry instanceof ThumbnailAccess)) continue;
      String url = ((ThumbnailAccess) entry).patch_jamThumbnailUrl();
      if (url != null && !url.isEmpty()) return url;
    }
    return "";
  }

  // Called only after the native operations-manager constructor finishes.
  public static void capture(QueueAccess access) {
    queue = new WeakReference<>(access);
  }

  /** Completion means dispatch, NOT server resolution or a confirmed queue mutation. */
  public static void enqueue(
    String videoId,
    boolean playNext,
    DispatchCallback callback
  ) {
    byte[] command = QueueCommand.encode(videoId, playNext);
    QueueAccess access = queue.get();
    if (access == null) {
      callback.complete("Queue bridge not ready. Start playing a song first.");
      return;
    }
    try {
      access.patch_jamExecutor().execute(() -> {
        if (queue.get() != access) {
          callback.complete("Player changed. Retry from the current player.");
          return;
        }
        try {
          access.patch_jamEnqueue(command);
          callback.complete(null);
        } catch (RuntimeException error) {
          callback.complete(
            "Native queue dispatch failed: " + error.getClass().getSimpleName()
          );
        }
      });
    } catch (RuntimeException error) {
      callback.complete(
        "Player executor unavailable: " + error.getClass().getSimpleName()
      );
    }
  }
}
