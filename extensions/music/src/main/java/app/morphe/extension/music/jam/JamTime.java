/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

/** Monotonic extrapolation is bounded when the connection stops delivering samples. */
public final class JamTime {

  static boolean valid(long position, long duration, double speed) {
    return (
      duration > 0 &&
      duration <= 86400000 &&
      position >= 0 &&
      position <= duration &&
      Double.isFinite(speed) &&
      speed >= 0 &&
      speed <= 8
    );
  }

  static long position(
    long position,
    long duration,
    double speed,
    boolean playing,
    long age
  ) {
    if (!valid(position, duration, speed)) return 0;
    if (playing) position += (long) (Math.min(4000, Math.max(0, age)) * speed);
    return Math.max(0, Math.min(duration, position));
  }
}
