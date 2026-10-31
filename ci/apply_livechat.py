#!/usr/bin/env python3
"""Merge the live chat translation additions onto a Morphe checkout.

The CI builds from an upstream release tag and then adds the live chat patch, so
the two shared files (Settings.java, strings.xml) cannot be replaced wholesale:
the tag already carries settings and strings the rest of the tree needs. This
script inserts only our entries, and is idempotent so reruns are safe.

Usage: apply_livechat.py [tag]
"""

import sys

SETTINGS = "extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java"
STRINGS = "patches/src/main/resources/addresources/values/youtube/strings.xml"
ARRAYS = "patches/src/main/resources/addresources/values/youtube/arrays.xml"

TARGET_BLOCK = """
            AppTarget(
                version = "21.20.405",
                minSdk = 28
            ),"""

ARRAYS_BLOCK = """
    <!-- layout.livechat.liveChatTranslatePatch -->
    <string-array name="morphe_live_chat_translate_style_entries">
        <item>@string/morphe_live_chat_translate_style_translated_only</item>
        <item>@string/morphe_live_chat_translate_style_original_and_translated</item>
        <item>@string/morphe_live_chat_translate_style_translated_and_original</item>
    </string-array>
    <string-array name="morphe_live_chat_translate_style_entry_values">
        <item>TRANSLATED_ONLY</item>
        <item>ORIGINAL_AND_TRANSLATED</item>
        <item>TRANSLATED_AND_ORIGINAL</item>
    </string-array>
"""

SETTINGS_BLOCK = """
    // Live chat translation
    public static final BooleanSetting LIVE_CHAT_TRANSLATE =
            new BooleanSetting("morphe_live_chat_translate", FALSE);
    public static final StringSetting LIVE_CHAT_TRANSLATE_TARGET =
            new StringSetting("morphe_live_chat_translate_target", "", true, parent(LIVE_CHAT_TRANSLATE));
    /** Source language code, or empty/auto to let the translator detect it. */
    public static final StringSetting LIVE_CHAT_TRANSLATE_SOURCE =
            new StringSetting("morphe_live_chat_translate_source", "", true, parent(LIVE_CHAT_TRANSLATE));
    /** Comma separated language codes that are left untranslated, such as messages already in your language. */
    public static final StringSetting LIVE_CHAT_TRANSLATE_EXCLUDE =
            new StringSetting("morphe_live_chat_translate_exclude", "", true, parent(LIVE_CHAT_TRANSLATE));
    /**
     * Only translates rows that are on screen, so a busy chat or a replay backlog does not
     * queue hundreds of requests and make the chat stutter.
     */
    public static final BooleanSetting LIVE_CHAT_TRANSLATE_ONLY_VISIBLE =
            new BooleanSetting("morphe_live_chat_translate_only_visible", TRUE, parent(LIVE_CHAT_TRANSLATE));
    /** Whether the row shows only the translation, or the original with it in parentheses. */
    public static final EnumSetting<TranslationStyle> LIVE_CHAT_TRANSLATE_STYLE =
            new EnumSetting<>("morphe_live_chat_translate_style",
                    TranslationStyle.ORIGINAL_AND_TRANSLATED, true, parent(LIVE_CHAT_TRANSLATE));
    public static final BooleanSetting LIVE_CHAT_TRANSLATE_BOLD =
            new BooleanSetting("morphe_live_chat_translate_bold", FALSE, parent(LIVE_CHAT_TRANSLATE));
    public static final BooleanSetting LIVE_CHAT_TRANSLATE_USE_COLOR =
            new BooleanSetting("morphe_live_chat_translate_use_color", TRUE, parent(LIVE_CHAT_TRANSLATE));
    public static final StringSetting LIVE_CHAT_TRANSLATE_COLOR =
            new StringSetting("morphe_live_chat_translate_color", "#38BDF8",
                    parentsAll(LIVE_CHAT_TRANSLATE, LIVE_CHAT_TRANSLATE_USE_COLOR));
    /** Shows a copy button on chat rows, so a message can be copied without a long press. */
    public static final BooleanSetting LIVE_CHAT_TRANSLATE_COPY_BUTTON =
            new BooleanSetting("morphe_live_chat_translate_copy_button", TRUE, parent(LIVE_CHAT_TRANSLATE));
    /** Messages longer than this are shown untranslated. Zero means no limit. */
    public static final IntegerSetting LIVE_CHAT_TRANSLATE_MAX_LENGTH =
            new IntegerSetting("morphe_live_chat_translate_max_length", 500, parent(LIVE_CHAT_TRANSLATE));
"""

IMPORT_ANCHOR = "import app.morphe.extension.youtube.patches.PlaybackBufferPatch.PlaybackBufferSize;\n"
IMPORT_LINE = IMPORT_ANCHOR + \
    "import app.morphe.extension.youtube.patches.LiveChatTranslatePatch.TranslationStyle;\n"

SEEKBAR_BLOCK = """
        // Live chat messages are short; 0 is the unlimited sentinel the extension checks.
        SeekBarPreference.register(new SeekBarConfig(LIVE_CHAT_TRANSLATE_MAX_LENGTH,
                0, 500, 20, " chars"));
"""

STRINGS_BLOCK = """
    <string name="morphe_live_chat_translate_screen_title">Live chat translation</string>
    <string name="morphe_live_chat_translate_screen_summary">Translate live chat and live chat replay messages</string>
    <string name="morphe_live_chat_translate_title">Translate live chat</string>
    <string name="morphe_live_chat_translate_summary">Translates live chat and live chat replay messages</string>
    <string name="morphe_live_chat_translate_target_title">Translate to</string>
    <string name="morphe_live_chat_translate_target_summary">Target language code such as id, en, ja. Leave empty to follow the device language</string>
    <string name="morphe_live_chat_translate_source_title">Translate from</string>
    <string name="morphe_live_chat_translate_source_summary">Source language code such as ja, ko, en. Leave empty to detect it automatically</string>
    <string name="morphe_live_chat_translate_exclude_title">Excluded source languages</string>
    <string name="morphe_live_chat_translate_exclude_summary">Comma separated codes such as id,en. Detected messages in these languages are left as-is</string>
    <string name="morphe_live_chat_translate_only_visible_title">Translate only visible messages</string>
    <string name="morphe_live_chat_translate_only_visible_summary">Translates a message once it is on screen. Keeps busy chats and replays smooth</string>
    <string name="morphe_live_chat_translate_style_title">Translation style</string>
    <string name="morphe_live_chat_translate_style_summary">How the translation is shown in each chat row</string>
    <string name="morphe_live_chat_translate_style_translated_only">Translated only</string>
    <string name="morphe_live_chat_translate_style_original_and_translated">Original (translated)</string>
    <string name="morphe_live_chat_translate_style_translated_and_original">(Translated) original</string>
    <string name="morphe_live_chat_translate_copy_button_title">Show copy message button</string>
    <string name="morphe_live_chat_translate_copy_button_summary">Adds a small copy button to every chat message</string>
    <string name="morphe_live_chat_translate_bold_title">Bold translated text</string>
    <string name="morphe_live_chat_translate_bold_summary">Shows the translated text in bold</string>
    <string name="morphe_live_chat_translate_use_color_title">Custom translated text color</string>
    <string name="morphe_live_chat_translate_use_color_summary">Applies a custom color to the translated text</string>
    <string name="morphe_live_chat_translate_color_title">Translated text color</string>
    <string name="morphe_live_chat_translate_color_summary">Set the hex color of the translated text</string>
    <string name="morphe_live_chat_translate_max_length_title">Maximum message length</string>
    <string name="morphe_live_chat_translate_max_length_summary">Longer messages are shown untranslated. Zero means no limit</string>
"""

# Every key written by an earlier version of this script, so a rerun upgrades the
# entries instead of appending a second, conflicting block.
STALE_STRING_KEYS = [
    "morphe_live_chat_translate_show_original_title",
    "morphe_live_chat_translate_show_original_summary",
]


def read(path):
    return open(path, encoding="utf-8").read()


def write(path, text):
    open(path, "w", encoding="utf-8").write(text)


def merge_settings():
    text = read(SETTINGS)
    if "LIVE_CHAT_TRANSLATE_ONLY_VISIBLE" in text:
        print("settings: already merged")
        return
    # Settings live next to the other interaction flags; the Comments block is a stable anchor.
    anchor = "    // Comments\n"
    if anchor not in text:
        sys.exit("settings: Comments anchor not found, tag layout changed")
    start = text.find("    // Live chat translation\n")
    end = text.find(anchor)
    if start >= 0 and start < end:
        # Replace the block written by an older version of this script.
        text = text[:start] + SETTINGS_BLOCK.lstrip("\n") + "\n" + text[end:]
    else:
        text = text.replace(anchor, SETTINGS_BLOCK + "\n" + anchor, 1)
    if "LiveChatTranslatePatch.TranslationStyle" not in text:
        if IMPORT_ANCHOR not in text:
            sys.exit("settings: import anchor not found, tag layout changed")
        text = text.replace(IMPORT_ANCHOR, IMPORT_LINE, 1)
    write(SETTINGS, text)
    print("settings: merged")


def merge_seekbar():
    text = read(SETTINGS)
    if "SeekBarConfig(LIVE_CHAT_TRANSLATE_MAX_LENGTH" in text:
        print("seekbar: already merged")
        return
    anchor = "    static {\n"
    if anchor not in text:
        sys.exit("seekbar: registration block not found, tag layout changed")
    index = text.index(anchor) + len(anchor)
    text = text[:index] + SEEKBAR_BLOCK.lstrip("\n") + text[index:]
    write(SETTINGS, text)
    print("seekbar: merged")


def merge_strings():
    text = read(STRINGS)
    if 'name="morphe_live_chat_translate_only_visible_title"' in text:
        print("strings: already merged")
        return
    for key in STALE_STRING_KEYS:
        marker = '    <string name="%s">' % key
        start = text.find(marker)
        if start < 0:
            continue
        end = text.find("\n", text.find("</string>", start))
        text = text[:start] + text[end + 1:]
    anchor = "</resources>"
    if anchor not in text:
        sys.exit("strings: closing tag not found")
    text = text.replace(anchor, STRINGS_BLOCK + anchor, 1)
    write(STRINGS, text)
    print("strings: merged")


def merge_arrays():
    text = read(ARRAYS)
    if "morphe_live_chat_translate_style_translated_and_original" in text:
        print("arrays: already merged")
        return
    # Drop a style array written by an older version of this script.
    start = text.find('<string-array name="morphe_live_chat_translate_style_entries">')
    if start >= 0:
        block_start = text.rfind("    <!--", 0, start)
        block_end = text.find("</string-array>", text.find(
            '<string-array name="morphe_live_chat_translate_style_entry_values">', start))
        if block_start >= 0 and block_end >= 0:
            block_end = text.find("\n", block_end)
            text = text[:block_start] + text[block_end + 1:]
    anchor = "</resources>"
    if anchor not in text:
        sys.exit("arrays: closing tag not found")
    text = text.replace(anchor, ARRAYS_BLOCK + anchor, 1)
    write(ARRAYS, text)
    print("arrays: merged")


merge_settings()
merge_seekbar()
merge_strings()
merge_arrays()
