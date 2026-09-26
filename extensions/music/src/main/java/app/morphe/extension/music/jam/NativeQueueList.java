/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3014
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.jam;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

/** Patched to implement YTM's observable-list ABI. Items are genuine native queue models. */
public final class NativeQueueList extends AbstractList<Object> {

  private final List<Object> items = new ArrayList<>();

  public synchronized Object get(int index) {
    return items.get(index);
  }

  public synchronized int size() {
    return items.size();
  }

  public synchronized List<Object> subList(int from, int to) {
    return new ArrayList<>(items.subList(from, to));
  }

  public synchronized void replace(List<Object> next) {
    items.clear();
    items.addAll(next);
  }

  @Override
  public Object remove(int index) {
    Object item = get(index);
    JamMirror.remove(item);
    return item;
  }

  public void move(int from, int to) {
    JamMirror.move(this, from, to);
  }

  public void addListener(Object listener) {}

  public void removeListener(Object listener) {}
}
