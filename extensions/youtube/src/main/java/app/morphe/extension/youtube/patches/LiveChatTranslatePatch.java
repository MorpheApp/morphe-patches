/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4222
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.translation.TextTranslator;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Translates live chat and live chat replay messages.
 * <p>
 * Chat rows are Litho texts that combine the author name, the badges and the message. A row that is
 * not translated yet gets a {@link PendingChatSpan}. Rows with this span that are on screen are
 * translated in the background, and {@link LithoRelayoutPatch} then lays out the row again, which
 * loads the text again as the translated row. Rows that are never on screen make no requests, as a
 * busy chat or a replay creates far more rows than it shows.
 */
@SuppressWarnings("unused")
public final class LiveChatTranslatePatch {

    public enum TranslationStyle {
        /** Only the translation is shown. */
        TRANSLATED_ONLY,
        /** The original, then the translation in parentheses: {@code original (translated)}. */
        ORIGINAL_AND_TRANSLATED,
        /** The translation in parentheses, then the original: {@code (translated) original}. */
        TRANSLATED_AND_ORIGINAL,
    }

    /** Included in the start of the component path of every chat row. */
    private static final String LIVE_CHAT = "live_chat";
    /** A chat row has it at the start of the path, so a match deeper in the path is not a chat row. */
    private static final int LIVE_CHAT_MAX_PATH_INDEX = 160;

    /**
     * Chat components whose texts are not messages. Everything else in the chat is a candidate, so a
     * row is not missed because its component is not known. {@code live_chat_header} is the header
     * of the chat, while {@code live_chat_item_header} is an ancestor of every Super Chat card.
     */
    private static final String[] SKIPPED_PATHS = {
            "ticker",
            "live_chat_header",
            "filter_mode",
            "filter_menu",
            "input_panel",
            "input_action",
            "context_menu",
            "dialog",
            "overlay",
            "button_inner",
            "avatar.eml",
            "emoji_picker",
            "super_chat_item_header_subtext",
    };

    /**
     * Inside a Super Chat card {@link #ATTRIBUTED_TEXT} holds the author name, and the message is
     * the text of the card itself.
     */
    private static final String SUPER_CHAT_ITEM = "super_chat_item.eml";
    private static final String ATTRIBUTED_TEXT = "live_chat_attributed_text";

    private static final int MIN_TEXT_LENGTH = 2;
    /** Letters a row without an author and body structure needs to not be a lone author name. */
    private static final int MIN_NOTICE_LETTERS = 16;

    private static final Pattern EMOTE_SHORTCODE = Pattern.compile(":[^:\\s]{1,20}:");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private static final int MAX_TRANSLATIONS = 2000;
    private static final int MAX_ROW_RESULTS = 1024;
    /** Value of {@link #translations} for a message that is shown as it is. */
    private static final String NOT_TRANSLATED = "";

    private static final int WORKER_THREADS = 2;
    private static final int WORK_QUEUE_CAPACITY = 24;
    private static final long WORKER_KEEP_ALIVE_SECONDS = 30;
    /** The endpoint answers 429 above about one request per second. */
    private static final long MIN_REQUEST_INTERVAL_MILLISECONDS = 700;
    /** Doubles per failure, and resets after a success. */
    private static final long MIN_BACKOFF_MILLISECONDS = 5_000;
    private static final long MAX_BACKOFF_MILLISECONDS = 120_000;
    private static final long VISIBLE_SCAN_DELAY_MILLISECONDS = 350;

    private static final String COPY_ICON = "yt_outline_copy_black_24";
    private static final int COPY_ICON_SIZE_DP = 14;
    private static final int COPY_ICON_COLOR = 0xFFAAAAAA;
    /** Replaced by the copy icon when drawn. */
    private static final char COPY_ICON_PLACEHOLDER = '-';

    /** Language and message text -> the translation, or {@link #NOT_TRANSLATED}. Oldest are removed first. */
    private static final Map<String, String> translations =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(MAX_TRANSLATIONS, true));

    /** Keys of {@link #translations} that are queued or being fetched. */
    private static final Set<String> pendingLookups = ConcurrentHashMap.newKeySet();

    /**
     * Results for the exact text instances Litho loads again, so a row is parsed once and
     * keeps showing the same text instance.
     */
    private static final Map<CharSequence, RowResult> rowResults =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private static final ThreadPoolExecutor executor = createExecutor();

    private static final AtomicBoolean visibleScanScheduled = new AtomicBoolean();

    private static final Object requestLock = new Object();
    /** Guarded by {@link #requestLock}. */
    private static long nextRequestTime;
    private static volatile long retryAfterFailureTime;
    private static volatile long backoffMilliseconds = MIN_BACKOFF_MILLISECONDS;

    /** Resource id -> if its name is a live chat view. Used only on the main thread. */
    private static final Map<Integer, Boolean> liveChatIds = new HashMap<>();

    @Nullable
    private static volatile Drawable copyIcon;

    private record RowResult(int configHash, CharSequence text) {
    }

    private static ThreadPoolExecutor createExecutor() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                WORKER_THREADS,
                WORKER_THREADS,
                WORKER_KEEP_ALIVE_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /**
     * Span of a chat row this patch has handled, with what is needed to build the row again.
     */
    private abstract static class ChatRowSpan implements LithoRelayoutPatch.RelayoutSpan {
        final CharSequence originalText;
        final int messageStart;
        final String message;

        ChatRowSpan(CharSequence originalText, int messageStart, String message) {
            this.originalText = originalText;
            this.messageStart = messageStart;
            this.message = message;
        }

        /**
         * A text drawable is registered globally, so a view recycled into another screen
         * can still hold this span. Laying out that view changes the other screen.
         */
        @Override
        public boolean canRelayoutHost(View host) {
            return host.isShown() && isInLiveChat(host);
        }
    }

    /**
     * A row whose message is not resolved yet.
     */
    private static final class PendingChatSpan extends ChatRowSpan {
        final String language;
        final String key;

        PendingChatSpan(CharSequence originalText, int messageStart, String message,
                        String language, String key) {
            super(originalText, messageStart, message);
            this.language = language;
            this.key = key;
        }

        boolean isResolved() {
            return translations.containsKey(key);
        }

        /**
         * Also called when the row is mounted, which is when it can be on screen.
         * This includes a row scrolled back on screen, whose text is not loaded again.
         */
        @Override
        public boolean isOutdated() {
            if (isResolved()) {
                return true;
            }
            scheduleVisibleScan();
            return false;
        }
    }

    /**
     * A translated row, which is built again if the settings change.
     */
    private static final class TranslatedChatSpan extends ChatRowSpan {
        final int configHash;

        TranslatedChatSpan(CharSequence originalText, int messageStart, String message, int configHash) {
            super(originalText, messageStart, message);
            this.configHash = configHash;
        }

        @Override
        public boolean isOutdated() {
            return configHash != configHash();
        }
    }

    /**
     * Copies the message when tapped. Text views handle clickable spans themselves, so this
     * does not conflict with the long press and tap actions the app gives to chat rows.
     */
    private static final class CopyMessageSpan extends ClickableSpan {
        private final String message;

        CopyMessageSpan(String message) {
            this.message = message;
        }

        @Override
        public void onClick(@NonNull View widget) {
            Utils.setClipboard(message);
            Utils.showToastShort(str("morphe_live_chat_translate_copied"));
        }

        @Override
        public void updateDrawState(@NonNull TextPaint paint) {
            paint.setColor(COPY_ICON_COLOR);
            paint.setUnderlineText(false);
        }
    }

    private LiveChatTranslatePatch() {
    }

    private static int configHash() {
        return Objects.hash(
                Settings.LIVE_CHAT_TRANSLATE_LANGUAGE.get(),
                Settings.LIVE_CHAT_TRANSLATE_EXCLUDE.get(),
                Settings.LIVE_CHAT_TRANSLATE_COPY_BUTTON.get(),
                Settings.LIVE_CHAT_TRANSLATE_STYLE.get(),
                Settings.LIVE_CHAT_TRANSLATE_BOLD.get(),
                Settings.LIVE_CHAT_TRANSLATE_USE_COLOR.get(),
                Settings.LIVE_CHAT_TRANSLATE_COLOR.get(),
                Settings.LIVE_CHAT_TRANSLATE_MAX_LENGTH.get()
        );
    }

    // region Texts

    /**
     * Injection point.
     * <p>
     * Called for every Litho text when it is created and when it is reused.
     */
    public static CharSequence onLithoTextLoaded(@Nullable ContextInterface contextInterface,
                                                 @Nullable CharSequence text) {
        if (!Settings.LIVE_CHAT_TRANSLATE.get() || contextInterface == null
                || text == null || text.length() < MIN_TEXT_LENGTH) {
            return text;
        }

        try {
            StringBuilder path = contextInterface.patch_getPathBuilder();
            if (path == null || !isMessagePath(path)) {
                return text;
            }

            final int configHash = configHash();
            RowResult cached = rowResults.get(text);
            if (cached != null && cached.configHash() == configHash && !isResolvedPending(cached.text())) {
                return cached.text();
            }

            CharSequence result = loadRow(text, configHash);
            if (rowResults.size() >= MAX_ROW_RESULTS) {
                rowResults.clear();
            }
            rowResults.put(text, new RowResult(configHash, result));
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
            return text;
        }
    }

    private static boolean isMessagePath(StringBuilder path) {
        final int index = path.indexOf(LIVE_CHAT);
        if (index < 0 || index > LIVE_CHAT_MAX_PATH_INDEX) {
            return false;
        }
        for (String skipped : SKIPPED_PATHS) {
            if (path.indexOf(skipped) >= 0) {
                return false;
            }
        }
        return path.indexOf(SUPER_CHAT_ITEM) < 0 || path.indexOf(ATTRIBUTED_TEXT) < 0;
    }

    private static boolean isResolvedPending(CharSequence text) {
        if (text instanceof Spanned spanned) {
            PendingChatSpan[] spans = spanned.getSpans(0, spanned.length(), PendingChatSpan.class);
            return spans.length > 0 && spans[0].isResolved();
        }
        return false;
    }

    /**
     * @return The row to show for the text.
     */
    private static CharSequence loadRow(CharSequence text, int configHash) {
        // The text can be a row built by this patch, as the app stores and reuses the returned text.
        ChatRowSpan rowSpan = null;
        if (text instanceof Spanned spanned) {
            ChatRowSpan[] spans = spanned.getSpans(0, spanned.length(), ChatRowSpan.class);
            if (spans.length > 0) {
                rowSpan = spans[0];
            } else if (spanned.getSpans(0, spanned.length(), CopyMessageSpan.class).length > 0) {
                return text;
            }
        }

        final CharSequence originalText;
        final int messageStart;
        final String message;
        if (rowSpan == null) {
            originalText = text;
            messageStart = text instanceof Spanned spanned ? findMessageStart(spanned) : 0;
            if (messageStart < 0) {
                return text;
            }
            message = extractMessage(text, messageStart);
            if (!shouldTranslate(message, messageStart > 0)) {
                return addCopyButton(text, message);
            }
        } else {
            if (rowSpan instanceof TranslatedChatSpan translatedSpan && translatedSpan.configHash == configHash) {
                return text;
            }
            originalText = rowSpan.originalText;
            messageStart = rowSpan.messageStart;
            message = rowSpan.message;
        }

        final String language = TextTranslator.resolveLanguage(Settings.LIVE_CHAT_TRANSLATE_LANGUAGE.get());
        final String key = language + ':' + message;
        final String translation = translations.get(key);
        if (translation == null) {
            if (rowSpan instanceof PendingChatSpan pendingSpan && pendingSpan.key.equals(key)) {
                return text;
            }
            SpannableString pending = new SpannableString(originalText);
            pending.setSpan(new PendingChatSpan(originalText, messageStart, message, language, key),
                    0, pending.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            scheduleVisibleScan();
            return pending;
        }
        if (translation.equals(NOT_TRANSLATED)) {
            return addCopyButton(originalText, message);
        }
        return buildTranslatedRow(originalText, messageStart, message, translation, configHash);
    }

    private static boolean shouldTranslate(String message, boolean isKnownMessage) {
        if (message.isEmpty() || !hasLetter(message)) {
            return false;
        }
        final int maxLength = Settings.LIVE_CHAT_TRANSLATE_MAX_LENGTH.get();
        if (maxLength < Settings.LIVE_CHAT_TRANSLATE_MAX_LENGTH_NO_LIMIT && message.length() > maxLength) {
            return false;
        }
        if (message.startsWith("http") && message.indexOf(' ') < 0) {
            return false;
        }
        if (isKnownMessage || message.length() >= MIN_TEXT_LENGTH) {
            return true;
        }
        // Without a known message the row might be an author name. A single letter is only a word
        // on its own in languages such as Chinese, and not when it is a Latin letter.
        return message.charAt(0) >= 0x80;
    }

    private static boolean hasLetter(String text) {
        for (int i = 0, length = text.length(); i < length; i++) {
            if (Character.isLetter(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds where the message starts in a row, which is the author name in a medium font,
     * then badges and spacers as {@link ReplacementSpan}s, then the message in a regular font.
     * The message can end with custom emojis, which are not covered by the font span of the words.
     *
     * @return The start of the message, or -1 if the row has no message.
     */
    private static int findMessageStart(Spanned row) {
        final int length = row.length();

        int authorEnd = -1;
        int regularStart = -1;
        int plainStart = -1;
        for (TypefaceSpan typeface : row.getSpans(0, length, TypefaceSpan.class)) {
            final int start = row.getSpanStart(typeface);
            final int end = row.getSpanEnd(typeface);
            final String family = typeface.getFamily();
            final boolean isRegular = family != null && family.contains("regular");
            // Rows with custom emojis use the plain font family instead of the regular one.
            final boolean isPlain = "sans-serif".equals(family);

            if (start == 0) {
                // The whole text is the message, such as in a Super Chat card
                // where the author name and the amount are separate components.
                if (isRegular || isPlain) {
                    return 0;
                }
                if (end < length && end > authorEnd) {
                    authorEnd = end;
                }
            } else if (isRegular) {
                if (regularStart < 0 || start < regularStart) {
                    regularStart = start;
                }
            } else if (isPlain && plainStart < 0) {
                plainStart = start;
            }
        }

        final int bodyStart = regularStart > 0 ? regularStart : plainStart;
        if (bodyStart <= 0 && authorEnd <= 0) {
            // No author and message structure, such as gift and membership notices.
            return hasWords(row) ? 0 : -1;
        }

        final int messageStart = skipLeadingDecorations(row, bodyStart > 0 ? bodyStart : authorEnd);
        // An author without a message after it must not be translated as if it were the message.
        return messageStart < length ? messageStart : -1;
    }

    /**
     * @return If a row without an author and message structure is a notice or a message,
     *         and not a lone author name or badge.
     */
    private static boolean hasWords(Spanned row) {
        final int length = row.length();
        int letters = 0;
        for (int i = 0; i < length; ) {
            final int codePoint = Character.codePointAt(row, i);
            i += Character.charCount(codePoint);
            // An author name can not have emojis, and a badge can not have spaces.
            if (codePoint == ' ' || isEmoji(codePoint)
                    || (Character.isLetter(codePoint) && ++letters >= MIN_NOTICE_LETTERS)) {
                return true;
            }
        }
        return row.getSpans(0, length, ReplacementSpan.class).length > 0
                || EMOTE_SHORTCODE.matcher(row).find();
    }

    private static int skipLeadingDecorations(Spanned row, int start) {
        final int length = row.length();
        final ReplacementSpan[] decorations = row.getSpans(0, length, ReplacementSpan.class);
        int position = start;
        while (position < length) {
            final int decorationEnd = findReplacementEnd(row, decorations, position);
            if (decorationEnd > position) {
                position = decorationEnd;
            } else if (row.charAt(position) == ' ') {
                position++;
            } else {
                break;
            }
        }
        return position;
    }

    /**
     * @return The end of the spans that cover the position, or -1 if none do.
     */
    private static int findReplacementEnd(Spanned text, ReplacementSpan[] spans, int position) {
        int end = -1;
        for (ReplacementSpan span : spans) {
            if (position >= text.getSpanStart(span) && position < text.getSpanEnd(span)) {
                end = Math.max(end, text.getSpanEnd(span));
            }
        }
        return end;
    }

    /**
     * @return The words of the message, without custom emojis that are drawn with
     *         {@link ReplacementSpan}s, emote shortcodes and Unicode emojis.
     */
    private static String extractMessage(CharSequence row, int messageStart) {
        final String message = row.subSequence(messageStart, row.length()).toString();
        if (!(row instanceof Spanned spanned)) {
            return stripEmojis(message);
        }

        final int length = row.length();
        final ReplacementSpan[] emojis = spanned.getSpans(messageStart, length, ReplacementSpan.class);
        if (emojis.length == 0) {
            return stripEmojis(message);
        }

        StringBuilder words = new StringBuilder(length - messageStart);
        int i = messageStart;
        while (i < length) {
            final int emojiEnd = findReplacementEnd(spanned, emojis, i);
            if (emojiEnd > i) {
                // A space keeps the words around the emoji apart.
                words.append(' ');
                i = emojiEnd;
            } else {
                words.append(row.charAt(i++));
            }
        }
        final String stripped = stripEmojis(words.toString());
        // Emoji spans can cover more than the emoji. If they cover everything, the words are in the text.
        return hasLetter(stripped) ? stripped : stripEmojis(message);
    }

    private static String stripEmojis(String text) {
        String noShortcodes = EMOTE_SHORTCODE.matcher(text).replaceAll(" ");
        StringBuilder words = new StringBuilder(noShortcodes.length());
        for (int i = 0; i < noShortcodes.length(); ) {
            final int codePoint = noShortcodes.codePointAt(i);
            i += Character.charCount(codePoint);
            if (isEmoji(codePoint)) {
                words.append(' ');
            } else {
                words.appendCodePoint(codePoint);
            }
        }
        return WHITESPACE_RUN.matcher(words).replaceAll(" ").trim();
    }

    private static boolean isEmoji(int codePoint) {
        return codePoint == 0x200D || codePoint == 0x20E3
                || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
                || (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
                || (codePoint >= 0xE0020 && codePoint <= 0xE007F)
                // Membership emojis are private use characters.
                || Character.getType(codePoint) == Character.PRIVATE_USE
                || (Character.getType(codePoint) == Character.OTHER_SYMBOL && codePoint > 0x2000);
    }

    // endregion

    // region Building rows

    /**
     * @return The row with the translation in the configured style. The author and the badges keep
     *         their spans, and the spans are copied after the text is added, as appending parts of
     *         a text with its spans cuts the spans that draw custom emojis.
     */
    private static CharSequence buildTranslatedRow(CharSequence originalText, int messageStart, String message,
                                                   String translation, int configHash) {
        final String original = originalText.toString();
        final int length = original.length();
        final TranslationStyle style = Settings.LIVE_CHAT_TRANSLATE_STYLE.get();

        SpannableStringBuilder row = new SpannableStringBuilder();
        final int translationStart;
        final int translationEnd;
        if (style == TranslationStyle.TRANSLATED_ONLY) {
            row.append(original, 0, messageStart);
            translationStart = row.length();
            row.append(translation);
            translationEnd = row.length();
            copySpans(originalText, 0, messageStart, row, 0);
        } else if (style == TranslationStyle.TRANSLATED_AND_ORIGINAL) {
            row.append(original, 0, messageStart).append(' ');
            translationStart = row.length();
            row.append('(').append(translation).append(')');
            translationEnd = row.length();
            row.append(' ');
            final int originalMessageStart = row.length();
            row.append(original, messageStart, length);
            copySpans(originalText, 0, messageStart, row, 0);
            copySpans(originalText, messageStart, length, row, originalMessageStart);
        } else {
            row.append(original).append(' ');
            translationStart = row.length();
            row.append('(').append(translation).append(')');
            translationEnd = row.length();
            copySpans(originalText, 0, length, row, 0);
        }

        applyTranslationStyle(row, translationStart, translationEnd);
        if (Settings.LIVE_CHAT_TRANSLATE_COPY_BUTTON.get()) {
            appendCopyButton(row, message);
        }
        row.setSpan(new TranslatedChatSpan(originalText, messageStart, message, configHash),
                0, row.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // A builder is a scratch buffer, and Litho can clear it or use it again.
        return new SpannableString(row);
    }

    /**
     * Copies the spans of the source range to the destination, clipped to the range.
     */
    private static void copySpans(CharSequence source, int sourceStart, int sourceEnd,
                                  SpannableStringBuilder destination, int destinationStart) {
        if (!(source instanceof Spanned spanned)) {
            return;
        }
        for (Object span : spanned.getSpans(sourceStart, sourceEnd, Object.class)) {
            final int start = Math.max(spanned.getSpanStart(span), sourceStart);
            final int end = Math.min(spanned.getSpanEnd(span), sourceEnd);
            if (start < end) {
                final int offset = destinationStart - sourceStart;
                destination.setSpan(span, start + offset, end + offset, spanned.getSpanFlags(span));
            }
        }
    }

    private static void applyTranslationStyle(SpannableStringBuilder row, int start, int end) {
        if (Settings.LIVE_CHAT_TRANSLATE_USE_COLOR.get()) {
            row.setSpan(new ForegroundColorSpan(getTranslationColor()), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (Settings.LIVE_CHAT_TRANSLATE_BOLD.get()) {
            row.setSpan(new TypefaceSpan("sans-serif-bold"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int getTranslationColor() {
        try {
            return Color.parseColor(Settings.LIVE_CHAT_TRANSLATE_COLOR.get());
        } catch (IllegalArgumentException ex) {
            Logger.printException(() -> "Invalid translation color", ex);
            Settings.LIVE_CHAT_TRANSLATE_COLOR.resetToDefault();
            return Color.parseColor(Settings.LIVE_CHAT_TRANSLATE_COLOR.defaultValue);
        }
    }

    /**
     * @return The row with the copy button, if enabled. Only rows that do not change
     *         again have the button, as a pending row is built again when it is translated.
     */
    private static CharSequence addCopyButton(CharSequence row, String message) {
        if (!Settings.LIVE_CHAT_TRANSLATE_COPY_BUTTON.get() || message.isEmpty()) {
            return row;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(row);
        appendCopyButton(builder, message);
        return new SpannableString(builder);
    }

    private static void appendCopyButton(SpannableStringBuilder row, String message) {
        row.append(' ');
        final int start = row.length();
        row.append(COPY_ICON_PLACEHOLDER);
        final int end = row.length();
        row.setSpan(new ImageSpan(getCopyIcon(), ImageSpan.ALIGN_CENTER), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        row.setSpan(new CopyMessageSpan(message), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static Drawable getCopyIcon() {
        Drawable icon = copyIcon;
        if (icon == null) {
            icon = ResourceUtils.getDrawable(COPY_ICON).mutate();
            icon.setTint(COPY_ICON_COLOR);
            final int size = Dim.dp(COPY_ICON_SIZE_DP);
            icon.setBounds(0, 0, size, size);
            copyIcon = icon;
        }
        return icon;
    }

    /**
     * @return If the view is inside the live chat. Must be called on the main thread.
     */
    private static boolean isInLiveChat(View view) {
        View current = view;
        while (current != null) {
            if (isLiveChatId(current)) {
                return true;
            }
            current = current.getParent() instanceof View parent ? parent : null;
        }
        return false;
    }

    private static boolean isLiveChatId(View view) {
        final int id = view.getId();
        if (id == View.NO_ID) {
            return false;
        }
        Boolean isLiveChat = liveChatIds.get(id);
        if (isLiveChat == null) {
            try {
                isLiveChat = view.getResources().getResourceEntryName(id).contains(LIVE_CHAT);
            } catch (Resources.NotFoundException ex) {
                // Generated view ids are not resources.
                Logger.printDebug(() -> "View id is not a resource: " + id);
                isLiveChat = false;
            }
            liveChatIds.put(id, isLiveChat);
        }
        return isLiveChat;
    }

    // endregion

    // region Translating

    /**
     * Can be called on any thread.
     */
    private static void scheduleVisibleScan() {
        if (visibleScanScheduled.compareAndSet(false, true)) {
            Utils.runOnMainThreadDelayed(LiveChatTranslatePatch::runVisibleScan, VISIBLE_SCAN_DELAY_MILLISECONDS);
        }
    }

    /**
     * Requests the translations of the pending rows on screen, and runs again while there are any,
     * as a request is not made while the queue is full or after a failure.
     */
    private static void runVisibleScan() {
        visibleScanScheduled.set(false);
        try {
            boolean rowsWaiting = false;
            for (PendingChatSpan span : LithoRelayoutPatch.findShownSpans(PendingChatSpan.class)) {
                if (!span.isResolved()) {
                    rowsWaiting = true;
                    requestTranslation(span);
                }
            }
            if (rowsWaiting) {
                // Kept running while a row is unresolved, so a row whose request failed or was
                // dropped by the rate limit is picked up again. Only the backoff delays it, and
                // that now expires on its own.
                scheduleVisibleScan();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "runVisibleScan failure", ex);
        }
    }

    private static void requestTranslation(PendingChatSpan span) {
        if (System.currentTimeMillis() < retryAfterFailureTime || !pendingLookups.add(span.key)) {
            return;
        }
        // A row that failed is not stored, so it stays pending and is asked for again by the next
        // scan. That is what makes a rate limited endpoint recover on its own instead of leaving
        // every row of that moment untranslated for the rest of the session.
        try {
            executor.execute(() -> {
                try {
                    fetchTranslation(span.message, span.language, span.key);
                } finally {
                    pendingLookups.remove(span.key);
                }
            });
        } catch (RejectedExecutionException ex) {
            // Requested again by the next scan if the row is still on screen.
            Logger.printDebug(() -> "Translation queue is full");
            pendingLookups.remove(span.key);
        }
    }

    /**
     * Runs on a worker thread.
     */
    private static void fetchTranslation(String message, String language, String key) {
        try {
            waitForRequestSlot();
            TextTranslator.Translation result = TextTranslator.translateText(message, language);
            final String translation = result.text().trim();
            final String detected = result.detectedLanguage();
            final boolean isTranslated = !translation.isEmpty() && !translation.equalsIgnoreCase(message)
                    && (detected == null || !(detected.equalsIgnoreCase(language) || isExcludedLanguage(detected)));
            translations.put(key, isTranslated ? translation : NOT_TRANSLATED);
            backoffMilliseconds = MIN_BACKOFF_MILLISECONDS;
            LithoRelayoutPatch.relayoutOutdatedTexts();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Logger.printInfo(() -> "Translation was interrupted", ex);
        } catch (Exception ex) {
            // The shared translator wraps every failure in a plain exception, so the status is read
            // from the message. Its own retry suits a patch that asks once; this one keeps asking.
            final boolean rateLimited = isRateLimited(ex);
            final long now = System.currentTimeMillis();
            if (now >= retryAfterFailureTime) {
                backoffMilliseconds = Math.min(
                        rateLimited ? backoffMilliseconds * 2 : backoffMilliseconds,
                        MAX_BACKOFF_MILLISECONDS);
                retryAfterFailureTime = now + backoffMilliseconds;
            }
            Logger.printInfo(() -> "Could not translate the chat message to: " + language, ex);
        }
    }

    /** @return If the failure was a rate limit, which the shared translator reports in the message. */
    private static boolean isRateLimited(@Nullable Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            final String message = t.getMessage();
            if (message != null && message.contains("429")) {
                return true;
            }
        }
        return false;
    }

    private static void waitForRequestSlot() throws InterruptedException {
        final long now = System.currentTimeMillis();
        final long requestTime;
        synchronized (requestLock) {
            requestTime = Math.max(now, nextRequestTime);
            nextRequestTime = requestTime + MIN_REQUEST_INTERVAL_MILLISECONDS;
        }
        if (requestTime > now) {
            Thread.sleep(requestTime - now);
        }
    }

    private static boolean isExcludedLanguage(String language) {
        for (String excluded : Settings.LIVE_CHAT_TRANSLATE_EXCLUDE.get().split(",")) {
            if (excluded.trim().equalsIgnoreCase(language)) {
                return true;
            }
        }
        return false;
    }

    // endregion
}
