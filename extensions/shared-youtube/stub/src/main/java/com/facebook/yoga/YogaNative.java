package com.facebook.yoga;

public class YogaNative {
    /**
     * @param node  Pointer to the native node.
     * @param edge  YGEdge, where left is 0, top 1, right 2 and bottom 3.
     * @param value Margin in pixels.
     */
    public static native void jni_YGNodeStyleSetMarginJNI(long node, int edge, float value);
}
