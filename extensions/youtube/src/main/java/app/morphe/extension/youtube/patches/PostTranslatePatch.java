/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4163
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.ReplacementSpan;
import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.shared.translation.TextTranslator;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Translates the text of posts.
 * <p>
 * Posts are drawn by Litho, so a post has no view of its own. The element of a post includes
 * its whole text, while a long text is shown cut off. When the element is parsed, its texts are
 * kept. When the menu of a post is opened, the text of the post is found from the texts the post
 * shows, and a Translate item is added to the menu.
 * <p>
 * The translation is shown in place of the text, by giving the text drawable of the post a layout
 * of the translation with the same size. Litho can mount the drawable again, such as after the
 * post is scrolled away, so the translation is given again whenever the post is laid out.
 * Texts that Litho creates again are replaced with their translation as well.
 * <p>
 * If the text can not be translated, it is sent to the translation app of the device.
 */
@SuppressWarnings("unused")
public final class PostTranslatePatch {

    public static final boolean ENABLED = isPatchIncluded() && Settings.POST_TRANSLATE.get();

    private static final boolean USE_EXPERIMENTAL_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.31.00");

    private static final String[] TRANSLATE_ICONS = USE_EXPERIMENTAL_ICONS
            ? new String[]{"yt_outline_experimental_translate_vd_theme_24", "yt_outline_translate_black_24"}
            : new String[]{"yt_outline_translate_black_24", "yt_outline_experimental_translate_vd_theme_24"};

    private static final String GOOGLE_TRANSLATE_PACKAGE = "com.google.android.apps.translate";

    private static final int MIN_TEXT_LENGTH = 2;
    /** A shown text cut off this far or further is the start of a post text. */
    private static final int MIN_CUT_OFF_LENGTH = 20;
    /** Dates and counts of a post, such as "2 days ago", are not longer than this. */
    private static final int MAX_METADATA_LENGTH = 30;
    private static final int MAX_TEXTS = 3000;
    private static final int MAX_TRANSLATIONS = 100;
    /** Depth of the fields of a host that are searched for its text drawables. */
    private static final int MAX_FIELD_DEPTH = 5;
    /** Limits the search of the text drawables of a host, which can reference many objects. */
    private static final int MAX_SEARCHED_OBJECTS = 2000;
    /** Texts that overlap the first text by more than this are in the header of the post. */
    private static final int HEADER_TOLERANCE_DP = 2;

    /**
     * Included in the identifier of the post elements.
     */
    private static final String POST = "post";

    /** The shown text can join the lines of the post text, or show invisible format characters. */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("[\\s\\p{Cf}]+");
    private static final Pattern LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern DIGIT_PATTERN = Pattern.compile("\\p{Nd}");

    /** Normalized text of a post element -> the text with its line breaks. Oldest texts are removed first. */
    @GuardedBy("itself")
    private static final Map<String, String> postTexts = Utils.createSizeRestrictedMap(MAX_TEXTS, true);

    /** Normalized original text -> its translation. */
    @GuardedBy("itself")
    private static final Map<String, String> translations = Utils.createSizeRestrictedMap(MAX_TRANSLATIONS, true);

    /** Normalized translation -> the normalized original text. */
    @GuardedBy("translations")
    private static final Map<String, String> originals = new HashMap<>();

    /** Drawables that show a translation -> what they showed before. Used only on the main thread. */
    private static final Map<Drawable, TranslatedDrawable> translatedDrawables = new WeakHashMap<>();

    /** Fields of the text drawable classes, found once for each class. Used only on the main thread. */
    private static final Map<Class<?>, DrawableFields> drawableFields = new HashMap<>();

    /** Normalized original texts that are being translated. Used only on the main thread. */
    private static final Set<String> pendingTranslations = Collections.newSetFromMap(new HashMap<>());

    /**
     * A text that a post shows.
     *
     * @param drawable The drawable that shows the text, or null if it is not known.
     * @param bounds   The bounds of the text in the root host, or null if they are not known.
     */
    private record ShownText(String text, @Nullable Drawable drawable, @Nullable Rect bounds) {
    }

    /**
     * The text of a post.
     *
     * @param original          The whole text, with its line breaks.
     * @param key               The normalized whole text.
     * @param showsTranslation  If the shown text is the translation.
     */
    private record PostText(ShownText shown, String original, String key, boolean showsTranslation) {
    }

    /**
     * Fields of a text drawable, found by their type as their names are obfuscated.
     */
    private record DrawableFields(Field layout, Field text, List<Field> spans) {
    }

    /**
     * What a drawable that shows a translation showed before.
     *
     * @param originalSpans Span field -> the spans of the original text.
     */
    private record TranslatedDrawable(Layout originalLayout, CharSequence originalText,
                                      Map<Field, Object> originalSpans, Layout translatedLayout, String key) {
    }

    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    private PostTranslatePatch() {
    }

    private static String normalize(CharSequence text) {
        return WHITESPACE_PATTERN.matcher(text).replaceAll(" ").trim();
    }

    private static boolean isPostText(String text) {
        return text.length() >= MIN_TEXT_LENGTH
                && !text.contains(".eml")
                && !text.startsWith("http")
                && LETTER_PATTERN.matcher(text).find();
    }

    /**
     * @return If the text is a short text with a number, such as the date of the post
     *         or the count of its likes, which is shown in the language of the app.
     */
    private static boolean isMetadataText(String text) {
        return text.length() <= MAX_METADATA_LENGTH && DIGIT_PATTERN.matcher(text).find();
    }

    /**
     * @return The start of a text cut off with an ellipsis, such as a long post text
     *         followed by Read more, or null if the text is not cut off.
     */
    @Nullable
    private static String getCutOffStart(String text) {
        int index = text.indexOf("...");
        if (index < 0) {
            index = text.indexOf('…');
        }
        return index >= MIN_CUT_OFF_LENGTH ? normalize(text.substring(0, index)) : null;
    }

    // region Elements

    /**
     * Injection point.
     * <p>
     * Called when an element is parsed. The element is not changed.
     */
    public static byte[] onElementLoaded(byte[] bytes) {
        if (!ENABLED || bytes == null || !FeedVideoCardsPatch.emlTextMayContain(bytes, POST)) {
            return bytes;
        }
        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }

            // Post texts with line breaks are not parsed as text fields.
            List<String> texts = ProtoNode.texts(root);

            String identifier = null;
            for (String text : texts) {
                if (text.contains(".eml")) {
                    identifier = text;
                    break;
                }
            }
            if (identifier == null || !identifier.contains(POST)) {
                return bytes;
            }

            synchronized (postTexts) {
                for (String text : texts) {
                    String key = normalize(text);
                    if (isPostText(key)) {
                        postTexts.put(key, text);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
        }
        return bytes;
    }

    /**
     * @return The whole text of a post the shown text is, or the start of, or null if none.
     */
    @Nullable
    private static String findPostText(String shown) {
        String text = normalize(shown);
        synchronized (postTexts) {
            String original = postTexts.get(text);
            if (original != null) {
                return original;
            }
            // A long post text is cut off and shows only its start.
            String start = getCutOffStart(shown);
            if (start == null) {
                return null;
            }
            String found = null;
            for (Map.Entry<String, String> entry : postTexts.entrySet()) {
                if (entry.getKey().startsWith(start)
                        && (found == null || entry.getValue().length() > found.length())) {
                    found = entry.getValue();
                }
            }
            return found;
        }
    }

    // endregion

    // region Texts

    /**
     * Injection point.
     * <p>
     * Called when Litho creates a text, such as when a post is shown again.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence text) {
        if (!ENABLED || text == null) {
            return text;
        }
        try {
            String translation;
            synchronized (translations) {
                if (translations.isEmpty()) {
                    return text;
                }
                translation = translations.get(normalize(text));
            }
            if (translation != null) {
                Logger.printDebug(() -> "Replaced post text with its translation");
                return createTranslationText(text, translation);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }
        return text;
    }

    /**
     * @return The translation with the styles of the original text, such as its color,
     *         which the app gives to the text as spans and not to the paint.
     *         Links and images are not used, as they are not in the translation.
     */
    private static SpannableString createTranslationText(CharSequence original, String translation) {
        SpannableString text = new SpannableString(translation);
        if (!(original instanceof Spanned spanned) || translation.isEmpty()) {
            return text;
        }
        final int length = spanned.length();
        final int end = translation.length();
        boolean hasColor = false;
        for (CharacterStyle span : spanned.getSpans(0, length, CharacterStyle.class)) {
            if (isLinkOrImage(span) || spanned.getSpanStart(span) > 0 || spanned.getSpanEnd(span) < length) {
                continue;
            }
            text.setSpan(span, 0, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
            hasColor |= span instanceof ForegroundColorSpan;
        }
        if (!hasColor) {
            // The color of the first letter, if the text has many colors, such as the color of links.
            for (int i = 0; i < length; i++) {
                if (!Character.isLetter(spanned.charAt(i))) {
                    continue;
                }
                for (ForegroundColorSpan span : spanned.getSpans(i, i + 1, ForegroundColorSpan.class)) {
                    if (!isLinkOrImage(span)) {
                        text.setSpan(span, 0, end, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                        break;
                    }
                }
                break;
            }
        }
        return text;
    }

    private static boolean isLinkOrImage(Object span) {
        return span instanceof ClickableSpan || span instanceof ReplacementSpan;
    }

    @Nullable
    private static String getTranslation(String key) {
        synchronized (translations) {
            return translations.get(key);
        }
    }

    /**
     * @return The normalized original text of the shown translation, or null if it is not a translation.
     */
    @Nullable
    private static String getOriginalKey(String shown) {
        synchronized (translations) {
            return originals.get(normalize(shown));
        }
    }

    private static boolean hasTranslations() {
        synchronized (translations) {
            return !translations.isEmpty();
        }
    }

    // endregion

    // region Drawables

    @Nullable
    private static DrawableFields getDrawableFields(Drawable drawable) {
        Class<?> drawableClass = drawable.getClass();
        if (drawableFields.containsKey(drawableClass)) {
            return drawableFields.get(drawableClass);
        }

        List<CharSequence> items = ((TextContent) drawable).getTextItems();
        CharSequence shownText = items == null || items.isEmpty() ? null : items.get(0);

        Field layoutField = null;
        Field textField = null;
        List<Field> spanFields = new ArrayList<>();
        try {
            for (Class<?> type = drawableClass; type != null && !isFrameworkClass(type); type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    Class<?> fieldType = field.getType();
                    if (Layout.class.isAssignableFrom(fieldType)) {
                        field.setAccessible(true);
                        if (layoutField == null || field.get(drawable) != null) {
                            layoutField = field;
                        }
                    } else if (fieldType == CharSequence.class) {
                        field.setAccessible(true);
                        // The field of the shown text, if the drawable has other texts.
                        if (textField == null || (shownText != null && field.get(drawable) == shownText)) {
                            textField = field;
                        }
                    } else if (fieldType == ClickableSpan[].class || fieldType == ImageSpan[].class) {
                        field.setAccessible(true);
                        spanFields.add(field);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not find the fields of the text drawable", ex);
            layoutField = null;
        }

        DrawableFields fields = layoutField == null || textField == null
                ? null
                : new DrawableFields(layoutField, textField, spanFields);
        final Field foundLayout = layoutField;
        final Field foundText = textField;
        Logger.printDebug(() -> "Text drawable " + drawableClass.getName()
                + " layout: " + foundLayout + " text: " + foundText);
        drawableFields.put(drawableClass, fields);
        return fields;
    }

    private static boolean isFrameworkClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("android.") || name.startsWith("kotlin.");
    }

    /**
     * Collects the texts of the host and its child hosts, with the drawables that show them.
     */
    private static void collectShownTexts(ComponentHost root, ComponentHost host, List<ShownText> texts) {
        List<Drawable> drawables = new ArrayList<>();
        try {
            findTextDrawables(host, drawables);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not find the text drawables", ex);
        }

        List<CharSequence> drawableTexts = new ArrayList<>();
        for (Drawable drawable : drawables) {
            List<CharSequence> items = ((TextContent) drawable).getTextItems();
            if (items == null || items.isEmpty() || items.get(0) == null) {
                continue;
            }
            CharSequence text = items.get(0);
            drawableTexts.add(text);
            Rect bounds = new Rect(drawable.getBounds());
            root.offsetDescendantRectToMyCoords(host, bounds);
            texts.add(new ShownText(text.toString(), drawable, bounds));
        }

        // Texts whose drawable was not found.
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text != null && !drawableTexts.contains(text)) {
                    texts.add(new ShownText(text.toString(), null, null));
                }
            }
        }

        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            View child = host.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && child instanceof ComponentHost childHost) {
                collectShownTexts(root, childHost, texts);
            }
        }
    }

    /**
     * Finds the text drawables mounted in the host. The host keeps its mounted contents
     * in obfuscated fields, so the fields are searched for drawables that show texts,
     * the nearest fields first.
     */
    private static void findTextDrawables(ComponentHost host, List<Drawable> drawables) throws Exception {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> level = new ArrayList<>();
        addFieldValues(host, ComponentHost.class, level);

        for (int depth = 1; depth <= MAX_FIELD_DEPTH && !level.isEmpty(); depth++) {
            List<Object> next = new ArrayList<>();
            for (Object object : level) {
                if (object == null || object instanceof View || object instanceof Context
                        || object instanceof CharSequence || object instanceof Number
                        || object instanceof Class || !visited.add(object)) {
                    continue;
                }
                if (visited.size() > MAX_SEARCHED_OBJECTS) {
                    Logger.printDebug(() -> "Stopped searching the text drawables of a host");
                    return;
                }
                if (object instanceof Drawable drawable) {
                    // A mounted drawable is drawn by its host.
                    if (drawable instanceof TextContent && drawable.getCallback() == host
                            && !drawables.contains(drawable)) {
                        drawables.add(drawable);
                    }
                } else if (object instanceof Object[] array) {
                    next.addAll(Arrays.asList(array));
                } else if (object instanceof Iterable<?> iterable) {
                    for (Object item : iterable) {
                        next.add(item);
                    }
                } else if (object instanceof Map<?, ?> map) {
                    next.addAll(map.values());
                } else if (object instanceof SparseArray<?> sparseArray) {
                    for (int i = 0, size = sparseArray.size(); i < size; i++) {
                        next.add(sparseArray.valueAt(i));
                    }
                } else if (!object.getClass().isArray()) {
                    for (Class<?> type = object.getClass(); type != null && !isFrameworkClass(type);
                         type = type.getSuperclass()) {
                        addFieldValues(object, type, next);
                    }
                }
            }
            level = next;
        }
    }

    private static void addFieldValues(Object object, Class<?> type, List<Object> values) throws Exception {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            field.setAccessible(true);
            values.add(field.get(object));
        }
    }

    /**
     * @return A layout of the text with the size of the layout, cut off with an ellipsis if it is longer.
     */
    private static Layout createLayout(Layout layout, CharSequence text) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), layout.getPaint(), Math.max(1, layout.getWidth()))
                .setAlignment(layout.getAlignment())
                .setLineSpacing(layout.getSpacingAdd(), layout.getSpacingMultiplier())
                .setIncludePad(layout.getTopPadding() != 0 || layout.getBottomPadding() != 0)
                .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                .setMaxLines(Math.max(1, layout.getLineCount()))
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    /**
     * Shows the translation in the drawable.
     *
     * @return If the translation is shown.
     */
    private static boolean showTranslation(Drawable drawable, String key, String translation) {
        try {
            DrawableFields fields = getDrawableFields(drawable);
            if (fields == null) {
                return false;
            }
            Layout layout = (Layout) fields.layout().get(drawable);
            CharSequence text = (CharSequence) fields.text().get(drawable);
            if (layout == null || text == null) {
                return false;
            }
            TranslatedDrawable translated = translatedDrawables.get(drawable);
            if (translated != null && translated.translatedLayout() == layout) {
                return true; // Already shown.
            }
            // The drawable can be used again by another post while the text is translated.
            String original = findPostText(text.toString());
            if (original == null || !normalize(original).equals(key)) {
                Logger.printDebug(() -> "The drawable shows another text");
                return false;
            }

            SpannableString translationText = createTranslationText(text, translation);
            Layout translatedLayout = createLayout(layout, translationText);
            Map<Field, Object> originalSpans = new HashMap<>();
            for (Field spanField : fields.spans()) {
                originalSpans.put(spanField, spanField.get(drawable));
                // The links and images of the original text are not in the translation.
                spanField.set(drawable, Array.newInstance(spanField.getType().getComponentType(), 0));
            }
            fields.layout().set(drawable, translatedLayout);
            fields.text().set(drawable, translationText);
            translatedDrawables.put(drawable,
                    new TranslatedDrawable(layout, text, originalSpans, translatedLayout, key));
            drawable.invalidateSelf();
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the translation", ex);
        }
        return false;
    }

    /**
     * Shows the original text in the drawable.
     */
    private static void showOriginal(Drawable drawable, String original) {
        try {
            DrawableFields fields = getDrawableFields(drawable);
            if (fields == null) {
                return;
            }
            Layout layout = (Layout) fields.layout().get(drawable);
            if (layout == null) {
                return;
            }
            TranslatedDrawable translated = translatedDrawables.remove(drawable);
            if (translated != null && translated.translatedLayout() == layout) {
                fields.layout().set(drawable, translated.originalLayout());
                fields.text().set(drawable, translated.originalText());
                for (Map.Entry<Field, Object> entry : translated.originalSpans().entrySet()) {
                    entry.getKey().set(drawable, entry.getValue());
                }
            } else {
                // The translation was created by Litho.
                SpannableString originalText = createTranslationText(
                        (CharSequence) fields.text().get(drawable), original);
                fields.layout().set(drawable, createLayout(layout, originalText));
                fields.text().set(drawable, originalText);
            }
            drawable.invalidateSelf();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the original text", ex);
        }
    }

    // endregion

    // region Posts

    /**
     * @return The text of the post that contains the view, or null if the post has no text.
     */
    @Nullable
    private static PostText findPostText(View sender, ComponentHost root) {
        // The hosts around the view are checked from the inside out, as a root host
        // can show many posts, such as a shelf of posts.
        ViewParent parent = sender instanceof ComponentHost host ? host : sender.getParent();
        while (parent instanceof View view) {
            if (view instanceof ComponentHost host) {
                PostText postText = findPostTextOfHost(host);
                if (postText != null || host == root) {
                    return postText;
                }
            }
            parent = view.getParent();
        }
        return null;
    }

    /**
     * @return The text of the post the host shows, or null if it does not show the text of a post.
     */
    @Nullable
    private static PostText findPostTextOfHost(ComponentHost root) {
        List<ShownText> shownTexts = new ArrayList<>();
        collectShownTexts(root, root, shownTexts);

        List<PostText> candidates = new ArrayList<>();
        for (ShownText shown : shownTexts) {
            String text = shown.text().trim();
            String original;
            String key = getOriginalKey(text);
            final boolean showsTranslation = key != null;
            if (showsTranslation) {
                synchronized (postTexts) {
                    original = postTexts.get(key);
                }
                if (original == null) {
                    original = key;
                }
            } else {
                if (!isPostText(normalize(text))) {
                    continue;
                }
                original = findPostText(text);
                if (original == null || isMetadataText(original)) {
                    continue;
                }
                key = normalize(original);
            }
            candidates.add(new PostText(shown, original, key, showsTranslation));
        }
        if (candidates.isEmpty()) {
            return null;
        }

        // The name of the channel and the date are in the header, above the text of the post.
        Rect header = null;
        for (ShownText shown : shownTexts) {
            Rect bounds = shown.bounds();
            if (bounds != null && LETTER_PATTERN.matcher(shown.text()).find()
                    && (header == null || bounds.top < header.top)) {
                header = bounds;
            }
        }

        PostText best = null;
        for (PostText candidate : candidates) {
            Rect bounds = candidate.shown().bounds();
            if (header != null) {
                if (bounds == null || bounds.top < header.bottom - Dim.dp(HEADER_TOLERANCE_DP)) {
                    continue;
                }
                // The text of the post is the first text below the header, such as above a poll.
                //noinspection ConstantConditions
                if (best == null || bounds.top < best.shown().bounds().top) {
                    best = candidate;
                }
            } else if (best == null || candidate.original().length() > best.original().length()) {
                best = candidate;
            }
        }

        if (BaseSettings.DEBUG.get()) {
            for (PostText candidate : candidates) {
                final boolean isBest = candidate == best;
                Logger.printDebug(() -> (isBest ? "Post text: " : "Other post text: ")
                        + candidate.shown().bounds() + " drawable: " + (candidate.shown().drawable() != null)
                        + " text: "
                        + candidate.original().substring(0, Math.min(40, candidate.original().length())));
            }
        }
        return best;
    }

    /**
     * Called by {@link FeedVideoCardsPatch} once a root host was laid out.
     * Shows the translations again, as Litho can mount the drawables of the post again.
     * Must be called on the main thread.
     */
    public static void onRootLaidOut(ComponentHost root) {
        if (!ENABLED || !hasTranslations()) {
            return;
        }
        try {
            if (!showsTranslatedText(root)) {
                return;
            }
            List<ShownText> shownTexts = new ArrayList<>();
            collectShownTexts(root, root, shownTexts);
            for (ShownText shown : shownTexts) {
                if (shown.drawable() == null || getOriginalKey(shown.text()) != null) {
                    continue;
                }
                String original = findPostText(shown.text());
                if (original == null) {
                    continue;
                }
                String key = normalize(original);
                String translation = getTranslation(key);
                if (translation != null) {
                    showTranslation(shown.drawable(), key, translation);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onRootLaidOut failure", ex);
        }
    }

    /**
     * @return If the host or its child hosts show an original text that has a translation.
     */
    private static boolean showsTranslatedText(ComponentHost host) {
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text == null || text.length() < MIN_TEXT_LENGTH) {
                    continue;
                }
                String original = findPostText(text.toString());
                if (original != null && getTranslation(normalize(original)) != null) {
                    return true;
                }
            }
        }
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            if (host.getChildAt(i) instanceof ComponentHost child && showsTranslatedText(child)) {
                return true;
            }
        }
        return false;
    }

    // endregion

    // region Menu

    @Nullable
    private static Drawable getTranslateIcon() {
        for (String name : TRANSLATE_ICONS) {
            if (ResourceUtils.getDrawableIdentifier(name) != 0) {
                return ResourceUtils.getDrawable(name);
            }
        }
        return null;
    }

    /**
     * Adds Translate or Show original to the menu of a post with a text.
     *
     * @param sender The view that opened the menu.
     */
    public static int addFlyoutButton(Object flyoutPanel, int index, @Nullable View sender) {
        if (!ENABLED || sender == null) {
            return index;
        }
        try {
            ComponentHost root = FeedVideoCardsPatch.findTappedRootHost(sender);
            if (root == null) {
                return index;
            }
            PostText postText = findPostText(sender, root);
            if (postText == null) {
                return index;
            }

            final boolean showsTranslation = postText.showsTranslation();
            final int nextIndex = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    getTranslateIcon(),
                    str(showsTranslation
                            ? "morphe_post_translate_flyout_original"
                            : "morphe_post_translate_flyout"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        if (showsTranslation) {
                            showOriginal(postText);
                        } else {
                            translate(postText, sender.getContext());
                        }
                    },
                    index
            );
            return nextIndex < 0 ? index : nextIndex;
        } catch (Exception ex) {
            Logger.printException(() -> "addFlyoutButton failure", ex);
        }
        return index;
    }

    // endregion

    // region Translation

    /**
     * @return The language the texts are translated to, as a Google Translate language code.
     */
    private static String getTargetLanguage() {
        String language;
        if (Settings.POST_TRANSLATE_LANGUAGE.isSetToDefault()) {
            Configuration configuration = Utils.getContext().getResources().getConfiguration();
            Locale locale = configuration.getLocales().isEmpty()
                    ? Locale.getDefault()
                    : configuration.getLocales().get(0);
            language = getLanguageCode(locale);
        } else {
            language = Settings.POST_TRANSLATE_LANGUAGE.get();
        }
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "fil" -> "tl";
            case "nb", "nn" -> "no";
            case "in" -> "id";
            case "iw" -> "he";
            case "ji" -> "yi";
            default -> language;
        };
    }

    private static String getLanguageCode(Locale locale) {
        String language = locale.getLanguage();
        if ("zh".equals(language)) {
            String country = locale.getCountry();
            return "Hant".equals(locale.getScript()) || "TW".equals(country)
                    || "HK".equals(country) || "MO".equals(country) ? "zh-TW" : "zh-CN";
        }
        if ("pt".equals(language) && "PT".equals(locale.getCountry())) {
            return "pt-PT";
        }
        return language.isEmpty() ? "en" : language;
    }

    private static void translate(PostText postText, Context context) {
        final String key = postText.key();
        String translation = getTranslation(key);
        if (translation != null) {
            showTranslation(postText, translation, context);
            return;
        }
        if (!pendingTranslations.add(key)) {
            return;
        }
        Utils.showToastShort(str("morphe_post_translate_started"));

        final String original = postText.original();
        final String language = getTargetLanguage();
        Utils.runOnBackgroundThread(() -> {
            String result = null;
            try {
                List<String> lines = Arrays.asList(original.split("\n", -1));
                StringBuilder builder = new StringBuilder();
                for (List<String> batch : TextTranslator.splitByCharacterBudget(
                        lines, TextTranslator.MAXIMUM_BATCH_CHARACTERS)) {
                    for (String line : TextTranslator.translate(batch, language)) {
                        //noinspection SizeReplaceableByIsEmpty
                        if (builder.length() > 0) {
                            builder.append('\n');
                        }
                        builder.append(line);
                    }
                }
                result = builder.toString().trim();
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not translate the post to: " + language, ex);
            }

            final String translated = result;
            Utils.runOnMainThread(() -> {
                pendingTranslations.remove(key);
                if (translated == null || translated.isEmpty()) {
                    Utils.showToastShort(str("morphe_post_translate_failed"));
                    openTranslationApp(context, original);
                    return;
                }
                if (normalize(translated).equals(key)) {
                    Logger.printDebug(() -> "Translation to " + language + " is the same text: " + key);
                    Utils.showToastShort(str("morphe_post_translate_same_language"));
                    return;
                }
                synchronized (translations) {
                    String previous = translations.put(key, translated);
                    if (previous != null) {
                        originals.remove(normalize(previous));
                    }
                    originals.put(normalize(translated), key);
                    // The oldest translations are removed from the size restricted map.
                    originals.values().retainAll(translations.keySet());
                }
                showTranslation(postText, translated, context);
            });
        });
    }

    private static void showTranslation(PostText postText, String translation, Context context) {
        Drawable drawable = postText.shown().drawable();
        if (drawable == null || getDrawableFields(drawable) == null) {
            Logger.printDebug(() -> "The translation can not be shown in place");
            showTranslationDialog(context, translation);
            return;
        }
        // If the post is not shown anymore, the translation is shown once it is laid out again.
        showTranslation(drawable, postText.key(), translation);
    }

    private static void showOriginal(PostText postText) {
        final String key = postText.key();
        synchronized (translations) {
            String translation = translations.remove(key);
            if (translation != null) {
                originals.remove(normalize(translation));
            }
        }
        Drawable drawable = postText.shown().drawable();
        if (drawable != null) {
            showOriginal(drawable, postText.original());
        }
        // Other drawables that show the translation, such as the same post in another feed.
        for (Map.Entry<Drawable, TranslatedDrawable> entry : new ArrayList<>(translatedDrawables.entrySet())) {
            if (entry.getValue().key().equals(key)) {
                showOriginal(entry.getKey(), postText.original());
            }
        }
    }

    private static void showTranslationDialog(Context context, String translation) {
        try {
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_post_translate_flyout"), // Title.
                    translation, // Message.
                    null, // No EditText.
                    null, // OK button text.
                    () -> {}, // OK button action (dismiss only).
                    null, // No cancel button.
                    null, // No neutral button.
                    null, // No neutral button action.
                    true  // Dismiss dialog when onNeutralClick.
            );
            dialogPair.first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the translation", ex);
        }
    }

    /**
     * Sends the text to the translation app of the device, or else to any app that takes text.
     */
    private static void openTranslationApp(Context context, String text) {
        Intent processText = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .setPackage(GOOGLE_TRANSLATE_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(processText);
            return;
        } catch (ActivityNotFoundException ex) {
            Logger.printDebug(() -> "Google Translate is not installed");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not open Google Translate", ex);
        }

        try {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text);
            context.startActivity(Intent.createChooser(send, str("morphe_post_translate_flyout"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not send the text to a translation app", ex);
        }
    }

    // endregion
}
