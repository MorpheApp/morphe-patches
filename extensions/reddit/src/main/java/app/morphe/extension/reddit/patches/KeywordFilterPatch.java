/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceCategory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import app.morphe.extension.reddit.settings.preference.RuleListPreference;
import app.morphe.extension.shared.Logger;

public final class KeywordFilterPatch implements ContentFilterPatch.Filter {
    private static final KeywordFilterPatch INSTANCE = new KeywordFilterPatch();
    private static String previous;
    private static List<Pattern> compiled = List.of();
    private KeywordFilterPatch() {}
    public static boolean isPatchIncluded() { return false; }
    public static void register() { ContentFilterPatch.registerFilter(INSTANCE); }
    @Override public boolean isEnabled() { return !ContentFilterPatch.rules(ContentFilterPatch.KEYWORDS).trim().isEmpty(); }
    @Override public boolean needsFullPostData() { return isEnabled(); }
    @Override public boolean matches(ContentFilterPatch.Post post) { return matches(post.text); }
    @Override public void addPreferences(Context context, PreferenceCategory category) {
        category.addPreference(new RuleListPreference(context, ContentFilterPatch.KEYWORDS,
                str("morphe_keyword_filters_title"), str("morphe_keyword_filters_summary"),
                str("morphe_keyword_filters_hint"), value -> {
                    try { Pattern.compile(value); return null; }
                    catch (PatternSyntaxException error) { return error.getDescription(); }
                }));
    }

    static synchronized boolean matches(Collection<String> text) {
        String current = ContentFilterPatch.rules(ContentFilterPatch.KEYWORDS);
        if (!current.equals(previous)) {
            List<Pattern> next = new ArrayList<>();
            for (String line : current.split("\\r?\\n")) if (!line.trim().isEmpty()) {
                try { next.add(Pattern.compile(line.trim(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)); }
                catch (PatternSyntaxException error) {
                    Logger.printException(() -> "Invalid keyword filter expression", error);
                }
            }
            compiled = next;
            previous = current;
        }
        for (Pattern pattern : compiled) for (String value : text) if (pattern.matcher(value).find()) return true;
        return false;
    }
}
