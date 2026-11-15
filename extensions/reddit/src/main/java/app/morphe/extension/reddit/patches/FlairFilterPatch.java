/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.content.Context;
import android.preference.PreferenceCategory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.reddit.settings.preference.FlairFilterPreferences;

public final class FlairFilterPatch implements ContentFilterPatch.Filter {
    public static final String RULES = ContentFilterPatch.FLAIRS;
    public static final String COLLECT = "morphe_collect_filter_flairs";
    private static final int MAX_OBSERVED_FLAIRS = 1000;
    private static final FlairFilterPatch INSTANCE = new FlairFilterPatch();
    private static final Set<String> OBSERVED = new LinkedHashSet<>();
    private static String previous;
    private static Set<String> blocked = Set.of();
    private FlairFilterPatch() {}
    public static boolean isPatchIncluded() { return false; }
    public static void register() { ContentFilterPatch.registerFilter(INSTANCE); }
    public static boolean collectFlairs() {
        Context context = Utils.getContext();
        return context != null && ContentFilterPatch.preferences(context).getBoolean(COLLECT, true);
    }
    @Override public boolean isEnabled() { return needsFullPostData() || collectFlairs(); }
    @Override public boolean needsFullPostData() { return !ContentFilterPatch.rules(RULES).trim().isEmpty(); }
    @Override public boolean matches(ContentFilterPatch.Post post) { return matches(post.flairs); }
    @Override public void addPreferences(Context context, PreferenceCategory category) {
        FlairFilterPreferences.addPreferences(context, category);
    }
    @Override public void observe(ContentFilterPatch.Post post) {
        if (!collectFlairs()) return;
        synchronized (OBSERVED) {
            for (String value : post.flairs) {
                OBSERVED.add(value);
                if (OBSERVED.size() > MAX_OBSERVED_FLAIRS) OBSERVED.remove(OBSERVED.iterator().next());
            }
        }
    }
    public static List<String> observedFlairs() {
        synchronized (OBSERVED) { return new ArrayList<>(OBSERVED); }
    }

    static synchronized boolean matches(Collection<String> flairs) {
        String current = ContentFilterPatch.rules(ContentFilterPatch.FLAIRS);
        if (!current.equals(previous)) {
            Set<String> next = new HashSet<>();
            for (String line : current.split("\\r?\\n")) {
                int split = line.indexOf("::");
                if (split <= 0) continue;
                String owner = ContentFilterPatch.community(line.substring(0, split));
                String label = ContentFilterPatch.flair(line.substring(split + 2));
                if (!owner.isEmpty() && !label.isEmpty()) next.add((owner + "::" + label).toLowerCase(Locale.ROOT));
            }
            blocked = next;
            previous = current;
        }
        for (String flair : flairs) if (blocked.contains(flair.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
