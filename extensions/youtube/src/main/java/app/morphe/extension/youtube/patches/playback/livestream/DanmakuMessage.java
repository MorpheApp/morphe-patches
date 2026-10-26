/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback.livestream;

/**
 * One live chat message to render as danmaku.
 * This class has no Android dependencies, so the rendering policy
 * (text, author, highlight colour) can be tested independently of the UI.
 */
public final class DanmakuMessage {

    public final String text;
    public final String author;
    public final boolean superChat;
    /** ARGB highlight colour, or 0 for the default danmaku style. */
    public final int highlightColor;

    public DanmakuMessage(String text, String author, boolean superChat, int highlightColor) {
        this.text = text == null ? "" : text;
        this.author = author == null ? "" : author;
        this.superChat = superChat;
        this.highlightColor = highlightColor;
    }

    public DanmakuMessage(String text, String author) {
        this(text, author, false, 0);
    }

    /** Text actually drawn on screen, honouring the show-author setting. */
    public String displayText(boolean showAuthor) {
        if (showAuthor && !author.isEmpty()) {
            return author + " " + text;
        }
        return text;
    }

    public boolean isEmpty() {
        return text.trim().isEmpty();
    }
}
