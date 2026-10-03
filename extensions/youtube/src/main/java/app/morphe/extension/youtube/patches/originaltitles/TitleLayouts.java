/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.ProtoNode;

/**
 * Learns where the elements of each component show the title of the video,
 * for the elements that do not include an accessibility label with the title,
 * such as the video lockups of some languages.
 * <p>
 * The field paths of the title depend on the component and on the app version, so they are learned
 * from the titles found in other ways, and from the titles that are not translated, which are
 * the same as the original title. Elements can show the title more than once, such as a visible
 * title and a title used by a command.
 * <p>
 * Components can have different layouts, such as a layout that shows a summary of the video
 * where other layouts show the title. A layout is the set of the field paths of the texts
 * of the element that are shown, and not keys or urls, as commands can include optional keys.
 * The title paths are learned for each layout, and a path is used for a layout if the title was
 * at the path in an element with the layout, no element with the layout had another text at the path,
 * and the title was at the path for different videos of the component.
 */
final class TitleLayouts {

    /**
     * Number of different videos of the component with the title at a field path before the path is used.
     */
    private static final int MIN_CONFIRMATIONS = 2;

    private static final class PathStats {
        int confirmations;
        int contradictions;
    }

    private static final class ComponentPaths {
        /**
         * Field path -> number of videos with the title at the path, in any layout.
         */
        final Map<String, Integer> confirmations = new HashMap<>();
        /**
         * Layout -> field path -> how often the field was the title,
         * or another text of an element whose title is known.
         */
        final Map<Set<String>, Map<String, PathStats>> layouts = new HashMap<>();
    }

    private static final Map<String, ComponentPaths> components = new HashMap<>();

    /**
     * Video ids and components that were counted, so a video is counted once for each component.
     */
    private static final Map<String, Boolean> countedElements =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Texts of an element whose title is not known, to find the title when the original title is fetched.
     */
    private record Candidates(String component, List<String> paths, List<String> texts) {
    }

    /**
     * Video id -> texts of the element of the video whose title was not found,
     * until the original title is fetched.
     */
    private static final Map<String, Candidates> pendingCandidates =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(200));

    /**
     * Text of an element whose title is not found -> video id of the element,
     * of the texts that can be the title.
     */
    private static final Map<String, String> candidateTextVideoIds =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(2000));

    /**
     * Video id -> texts of the element of the video whose title is translated and not found,
     * until a title path of the layout of the element is learned. Elements are parsed when loaded,
     * such as all the Shorts of a shelf, which can be before the title path is learned.
     */
    private static final Map<String, Candidates> unresolvedCandidates =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(200));

    private TitleLayouts() {
    }

    /**
     * Clears the texts of the elements whose title is not found, such as when the language is changed.
     * The learned title paths do not depend on the language, and are kept.
     */
    static void clearCandidates() {
        pendingCandidates.clear();
        candidateTextVideoIds.clear();
        unresolvedCandidates.clear();
    }

    /**
     * @return The field numbers from the root message to each text node.
     */
    static List<String> fieldPaths(List<ProtoNode> textNodes) {
        List<String> paths = new ArrayList<>(textNodes.size());
        for (ProtoNode node : textNodes) {
            List<Integer> path = new ArrayList<>();
            for (ProtoNode field = node; field != null; field = field.getParent()) {
                path.add(field.getFieldNumber());
            }
            Collections.reverse(path);
            paths.add(path.toString());
        }
        return paths;
    }

    private static Set<String> layout(List<String> paths, List<String> texts) {
        Set<String> layout = new HashSet<>();
        for (int i = 0, size = paths.size(); i < size; i++) {
            if (RestoreOriginalTitlesPatch.isLabelCandidate(texts.get(i))) {
                layout.add(paths.get(i));
            }
        }
        return layout;
    }

    /**
     * Saves the field paths of a title found in another way, or of a title that is not translated.
     *
     * @param paths Field paths of the text nodes.
     * @param texts Texts of the text nodes, without title markers.
     */
    static void confirmTitle(String component, String videoId, List<String> paths,
                             List<String> texts, String title) {
        if (!texts.contains(title) || countedElements.put(videoId + component, Boolean.TRUE) != null) {
            return;
        }

        synchronized (components) {
            ComponentPaths componentPaths = components.computeIfAbsent(component, key -> new ComponentPaths());
            Map<String, PathStats> layoutPaths = componentPaths.layouts.computeIfAbsent(
                    layout(paths, texts), key -> new HashMap<>());

            // A path of more than one text is the title only if all its texts are the title.
            Map<String, Boolean> elementPaths = new HashMap<>();
            for (int i = 0, size = paths.size(); i < size; i++) {
                final boolean isTitle = texts.get(i).equals(title);
                Boolean pathIsTitle = elementPaths.get(paths.get(i));
                elementPaths.put(paths.get(i), pathIsTitle == null ? isTitle : pathIsTitle && isTitle);
            }

            for (Map.Entry<String, Boolean> entry : elementPaths.entrySet()) {
                String path = entry.getKey();
                PathStats stats = layoutPaths.computeIfAbsent(path, key -> new PathStats());
                if (entry.getValue()) {
                    stats.confirmations++;
                    final int confirmations = componentPaths.confirmations.merge(path, 1, Integer::sum);
                    if (confirmations == MIN_CONFIRMATIONS) {
                        Logger.printDebug(() -> "Learned title path of component: " + component + " path: " + path);
                    }
                } else {
                    stats.contradictions++;
                }
            }
        }

        resolveCandidates(component);
    }

    /**
     * @param paths Field paths of the text nodes.
     * @param texts Texts of the text nodes, without title markers.
     * @return The title at the learned field paths of the layout of the element, or null if the
     *         paths are not known, or the texts at the paths are different.
     */
    @Nullable
    static String findTitle(String component, List<String> paths, List<String> texts) {
        Set<String> titlePaths = new HashSet<>();
        synchronized (components) {
            ComponentPaths componentPaths = components.get(component);
            Map<String, PathStats> layoutPaths = componentPaths == null
                    ? null
                    : componentPaths.layouts.get(layout(paths, texts));
            if (layoutPaths == null) {
                return null;
            }
            for (Map.Entry<String, PathStats> entry : layoutPaths.entrySet()) {
                PathStats stats = entry.getValue();
                Integer confirmations = componentPaths.confirmations.get(entry.getKey());
                if (stats.confirmations > 0 && stats.contradictions == 0
                        && confirmations != null && confirmations >= MIN_CONFIRMATIONS) {
                    titlePaths.add(entry.getKey());
                }
            }
        }
        if (titlePaths.isEmpty()) {
            return null;
        }

        String title = null;
        for (int i = 0, size = paths.size(); i < size; i++) {
            if (!titlePaths.contains(paths.get(i))) {
                continue;
            }
            String text = texts.get(i);
            if (title == null) {
                title = text;
            } else if (!title.equals(text)) {
                return null;
            }
        }
        return title;
    }

    /**
     * Saves the texts of an element whose title was not found. When the original title is fetched,
     * the texts that are the same as the original title are the title, which is not translated.
     *
     * @param paths Field paths of the text nodes.
     * @param texts Texts of the text nodes, without title markers.
     */
    static void addCandidates(String component, String videoId, List<String> paths, List<String> texts) {
        if (countedElements.containsKey(videoId + component)) {
            return;
        }
        pendingCandidates.put(videoId, new Candidates(component, paths, texts));
        for (String text : texts) {
            if (RestoreOriginalTitlesPatch.isLabelCandidate(text)) {
                candidateTextVideoIds.put(text, videoId);
            }
        }
    }

    /**
     * @return The video id of an element whose title is not yet found and that has the text,
     *         or null if none.
     */
    @Nullable
    static String findUnresolvedVideoId(String text) {
        String videoId = candidateTextVideoIds.get(text);
        return videoId != null && (pendingCandidates.containsKey(videoId) || unresolvedCandidates.containsKey(videoId))
                ? videoId
                : null;
    }

    /**
     * Called when the original title of a video is fetched.
     */
    static void originalTitleFetched(String videoId, @Nullable String originalTitle) {
        Candidates candidates = pendingCandidates.remove(videoId);
        if (candidates == null || originalTitle == null) {
            return;
        }
        String title = originalTitle.trim();
        if (candidates.texts().contains(title)) {
            confirmTitle(candidates.component(), videoId, candidates.paths(), candidates.texts(), title);
        } else {
            unresolvedCandidates.put(videoId, candidates);
            resolveCandidates(candidates.component());
        }
    }

    /**
     * Finds the titles of the elements of the component that were parsed before
     * the title paths of their layouts were learned.
     */
    private static void resolveCandidates(String component) {
        Map<String, Candidates> resolved = new HashMap<>();
        synchronized (unresolvedCandidates) {
            for (Map.Entry<String, Candidates> entry : unresolvedCandidates.entrySet()) {
                if (entry.getValue().component().equals(component)) {
                    resolved.put(entry.getKey(), entry.getValue());
                }
            }
        }
        for (Map.Entry<String, Candidates> entry : resolved.entrySet()) {
            Candidates candidates = entry.getValue();
            String title = findTitle(component, candidates.paths(), candidates.texts());
            if (title != null) {
                unresolvedCandidates.remove(entry.getKey());
                RestoreOriginalTitlesPatch.titleFound(entry.getKey(), title, candidates.texts());
            }
        }
    }
}
