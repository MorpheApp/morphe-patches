/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

/**
 * The words the word table does not have: read one character at a time they are wrong, since a
 * character changes its reading before the next, or the reading it is given alone is not the
 * one the word wants.
 */
final class PinyinWords {

    private PinyinWords() {
    }

    static final String[] READINGS = {
            "一个人=yí gè rén", "重新开始=chóng xīn kāi shǐ"
    };
}
