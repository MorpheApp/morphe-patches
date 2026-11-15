/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.notifications;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.utils.ProtoBytes;
import app.morphe.extension.youtube.patches.utils.ProtoNode;

/**
 * Replaces the grouped notifications of the notifications response with one notification per video.
 * <p>
 * A group carries a full notification entry for each of its videos, in the same shape as the entry
 * of a single notification. Each entry is put into a copy of a single notification of the same response.
 */
public final class InboxResponseFlattener {

    private static final String GROUP_TEMPLATE_PREFIX = "expandable_inbox_notification.eml";
    private static final String ROW_TEMPLATE_PREFIX = "inbox_notification.eml";

    private static final int[] TEMPLATE_NAME = {1, 168777401, 3, 172035250};
    private static final int[] ROW_MODEL = {1, 168777401, 5, 363936123};
    private static final int[] GROUP_MODEL = {1, 168777401, 5, 457625295};
    private static final int[] NOTIFICATION_ID = {7, 169495254, 78414307, 1, 2, 14, 1};
    private static final int[][] VIDEO_IDS = {
            {5, 169495254, 48687757},
            {5, 169495254, 139608561},
    };

    private static final int MODEL = 1;
    private static final int GROUP_ENTRY = 1;
    private static final int GROUP_HEADER = 6;
    private static final int SEEN = 1;
    private static final int AVATAR = 2;
    private static final int TEXTS = 3;
    private static final int CHANNEL_NAME = 4;
    private static final int ENTITY_KEYS = 15;
    private static final int SEEN_ENTITY_KEY = 1;

    private static final int MAX_ITEM_DEPTH = 8;

    /**
     * @return The flattened response, or the same array if nothing was replaced.
     */
    public static byte[] flatten(byte[] body) throws IOException {
        List<ProtoNode> root = ProtoNode.parse(body);
        if (root == null) {
            return body;
        }

        List<ProtoNode> elements = new ArrayList<>();
        collectElements(root, elements);
        SeenEntities seenEntities = new SeenEntities(root);
        InboxVideos videos = new InboxVideos();
        addNotifications(elements, seenEntities, videos);
        byte[] flattened = ungroupAll(body, root, elements, seenEntities, videos);
        videos.saveInBackground();
        return flattened;
    }

    private static byte[] ungroupAll(byte[] body, List<ProtoNode> root, List<ProtoNode> elements,
                                     SeenEntities seenEntities, InboxVideos videos) throws IOException {
        int itemDepth = itemDepth(elements);
        if (itemDepth < 0) {
            return body;
        }

        Map<ProtoNode, Integer> itemElementCounts = new IdentityHashMap<>();
        for (ProtoNode element : elements) {
            ProtoNode item = ancestor(element, itemDepth);
            if (item != null) {
                itemElementCounts.merge(item, 1, Integer::sum);
            }
        }

        ProtoNode rowItem = null;
        Map<ProtoNode, ProtoNode> groupElements = new LinkedHashMap<>();
        for (ProtoNode element : elements) {
            String template = templateName(element.children);
            ProtoNode item = ancestor(element, itemDepth);
            if (item == null) {
                continue;
            }
            if (template.startsWith(GROUP_TEMPLATE_PREFIX)) {
                if (itemElementCounts.get(item) > 1) {
                    // The group shares its list item: the list is nested differently than expected.
                    return body;
                }
                groupElements.put(item, element);
            } else if (rowItem == null && template.startsWith(ROW_TEMPLATE_PREFIX)
                    && itemElementCounts.get(item) == 1) {
                rowItem = item;
            }
        }
        if (groupElements.isEmpty()) {
            return body;
        }
        if (rowItem == null) {
            Logger.printDebug(() -> "No single notification to show the grouped notifications in");
            return body;
        }
        RowTemplate rowTemplate = RowTemplate.of(rowItem);
        if (rowTemplate == null) {
            return body;
        }

        Map<ProtoNode, List<Row>> replacements = new IdentityHashMap<>();
        Set<ProtoNode> lists = new LinkedHashSet<>();
        for (Map.Entry<ProtoNode, ProtoNode> group : groupElements.entrySet()) {
            ProtoNode list = group.getKey().getParent();
            if (list == null) {
                continue;
            }
            List<Row> rows = ungroup(group.getValue(), rowTemplate, seenEntities, videos);
            if (!rows.isEmpty()) {
                replacements.put(group.getKey(), rows);
                lists.add(list);
            }
        }
        if (replacements.isEmpty()) {
            return body;
        }

        List<ProtoNode> innermostListsFirst = new ArrayList<>(lists);
        innermostListsFirst.sort((a, b) -> Integer.compare(parentCount(b), parentCount(a)));
        for (ProtoNode list : innermostListsFirst) {
            List<Row> items = new ArrayList<>();
            for (ProtoNode item : list.children) {
                List<Row> replacement = replacements.get(item);
                if (replacement != null) {
                    items.addAll(replacement);
                } else {
                    items.add(new Row(raw(item), sentTime(item)));
                }
            }
            list.setPayload(sortedBySentTime(items));
        }
        seenEntities.write();
        return ProtoNode.write(root);
    }

    /**
     * Adds the videos of all notifications, the videos of a seen group as seen.
     */
    private static void addNotifications(List<ProtoNode> elements, SeenEntities seenEntities,
                                         InboxVideos videos) {
        for (ProtoNode element : elements) {
            String template = templateName(element.children);
            if (template == null) {
                continue;
            }
            if (template.startsWith(GROUP_TEMPLATE_PREFIX)) {
                addGroup(element, seenEntities, videos);
                continue;
            }
            if (!template.startsWith(ROW_TEMPLATE_PREFIX)) {
                continue;
            }
            ProtoNode model = ProtoNode.field(ProtoNode.message(element.children, ROW_MODEL), MODEL);
            if (model == null || model.children == null) {
                continue;
            }
            videos.add(videoId(model.children), notificationUnseen(model.children, seenEntities));
        }
    }

    private static void addGroup(ProtoNode groupElement, SeenEntities seenEntities, InboxVideos videos) {
        List<ProtoNode> group = ProtoNode.message(groupElement.children, GROUP_MODEL);
        ProtoNode header = ProtoNode.field(group, GROUP_HEADER);
        if (header == null || header.children == null) {
            return;
        }
        boolean headerUnseen = notificationUnseen(header.children, seenEntities);
        for (ProtoNode entry : group) {
            if (entry.getFieldNumber() == GROUP_ENTRY && entry.children != null) {
                String videoId = videoId(entry.children);
                Boolean entryUnseen = seenEntities.isUnseen(entryKey(entry.children));
                videos.add(videoId, entryUnseen != null ? entryUnseen : localUnseen(videoId, headerUnseen));
            }
        }
    }

    /**
     * @return The unseen state of the entity of the notification, otherwise of its seen flag,
     *         which is left out when it's 0.
     */
    private static boolean notificationUnseen(List<ProtoNode> model, SeenEntities seenEntities) {
        ProtoNode key = ProtoNode.field(ProtoNode.message(model, ENTITY_KEYS), SEEN_ENTITY_KEY);
        Boolean entityUnseen = seenEntities.isUnseen(key != null && key.isText() ? key.getText() : null);
        if (entityUnseen != null) {
            return entityUnseen;
        }
        ProtoNode seen = ProtoNode.field(model, SEEN);
        Long varint = seen == null ? null : seen.getVarint();
        return varint == null || varint == 0;
    }

    private static final class Row {
        final byte[] bytes;
        @Nullable
        final Long sentTime;

        Row(byte[] bytes, @Nullable Long sentTime) {
            this.bytes = bytes;
            this.sentTime = sentTime;
        }
    }

    /**
     * A copy of a single notification, which gets the entries of the videos of the groups.
     */
    private static final class RowTemplate {
        private final List<ProtoNode> item;
        private final ProtoNode model;

        private RowTemplate(List<ProtoNode> item, ProtoNode model) {
            this.item = item;
            this.model = model;
        }

        @Nullable
        static RowTemplate of(ProtoNode rowItem) throws IOException {
            List<ProtoNode> item = ProtoNode.parse(raw(rowItem));
            if (item == null) {
                return null;
            }
            ProtoNode model = rowModel(item);
            return model == null ? null : new RowTemplate(item, model);
        }

        byte[] withModel(byte[] model) throws IOException {
            this.model.setPayload(model);
            return ProtoNode.write(item);
        }
    }

    /**
     * Consecutive rows are sorted newest first, other items keep their position.
     */
    private static byte[] sortedBySentTime(List<Row> items) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Row> rows = new ArrayList<>();
        for (Row item : items) {
            if (item.sentTime != null) {
                rows.add(item);
                continue;
            }
            writeSorted(rows, out);
            out.write(item.bytes);
        }
        writeSorted(rows, out);
        return out.toByteArray();
    }

    private static void writeSorted(List<Row> rows, ByteArrayOutputStream out) throws IOException {
        rows.sort((a, b) -> Long.compareUnsigned(b.sentTime, a.sentTime));
        for (Row row : rows) {
            out.write(row.bytes);
        }
        rows.clear();
    }

    @Nullable
    private static Long sentTime(ProtoNode item) {
        ProtoNode model = rowModel(List.of(item));
        return model == null || model.children == null ? null : notificationId(model.children);
    }

    /**
     * @return The model of the first single notification in the message.
     */
    @Nullable
    private static ProtoNode rowModel(List<ProtoNode> message) {
        List<ProtoNode> elements = new ArrayList<>();
        collectElements(message, elements);
        return elements.isEmpty()
                ? null
                : ProtoNode.field(ProtoNode.message(elements.get(0).children, ROW_MODEL), MODEL);
    }

    /**
     * @return The id of the notification, which is the time it was sent, in microseconds.
     */
    @Nullable
    private static Long notificationId(List<ProtoNode> model) {
        ProtoNode notificationId = ProtoNode.field(ProtoNode.message(model, NOTIFICATION_ID), 1);
        return notificationId == null ? null : notificationId.getVarint();
    }

    private static void collectElements(List<ProtoNode> message, List<ProtoNode> elements) {
        for (ProtoNode node : message) {
            if (node.children == null) {
                continue;
            }
            String template = templateName(node.children);
            if (template != null && template.contains(ROW_TEMPLATE_PREFIX)) {
                elements.add(node);
            } else {
                collectElements(node.children, elements);
            }
        }
    }

    @Nullable
    private static String templateName(List<ProtoNode> element) {
        ProtoNode name = ProtoNode.field(ProtoNode.message(element, TEMPLATE_NAME), 1);
        return name != null && name.isText() ? name.getText() : null;
    }

    /**
     * The list items are the nearest ancestors of the elements that have siblings with elements
     * in the same repeated field. The depth is taken from the lists with more than one item,
     * so a section with a single notification gives the same depth.
     *
     * @return The number of parents from an element to its list item, or -1 if there is no list.
     */
    private static int itemDepth(List<ProtoNode> elements) {
        Set<ProtoNode> withElements = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ProtoNode element : elements) {
            for (ProtoNode node = element; node != null; node = node.getParent()) {
                withElements.add(node);
            }
        }
        for (int depth = 0; depth <= MAX_ITEM_DEPTH; depth++) {
            for (ProtoNode element : elements) {
                ProtoNode item = ancestor(element, depth);
                ProtoNode list = item == null ? null : item.getParent();
                if (list == null) {
                    continue;
                }
                for (ProtoNode sibling : list.children) {
                    if (sibling != item && sibling.getFieldNumber() == item.getFieldNumber()
                            && withElements.contains(sibling)) {
                        return depth;
                    }
                }
            }
        }
        return -1;
    }

    private static int parentCount(ProtoNode node) {
        int count = 0;
        for (ProtoNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
            count++;
        }
        return count;
    }

    @Nullable
    private static ProtoNode ancestor(ProtoNode node, int depth) {
        for (int i = 0; i < depth && node != null; i++) {
            node = node.getParent();
        }
        return node;
    }

    /**
     * @return The list items of the videos of the group.
     */
    private static List<Row> ungroup(ProtoNode groupElement, RowTemplate rowTemplate, SeenEntities seenEntities,
                                     InboxVideos videos) throws IOException {
        List<Row> rows = new ArrayList<>();
        List<ProtoNode> group = ProtoNode.message(groupElement.children, GROUP_MODEL);
        ProtoNode header = ProtoNode.field(group, GROUP_HEADER);
        if (header == null || header.children == null) {
            return rows;
        }
        GroupHeader groupHeader = new GroupHeader(header.children, seenEntities);
        for (ProtoNode entry : group) {
            if (entry.getFieldNumber() == GROUP_ENTRY && entry.children != null) {
                byte[] model = entryModel(entry, groupHeader, seenEntities, videos);
                rows.add(new Row(rowTemplate.withModel(model), notificationId(entry.children)));
            }
        }
        return rows;
    }

    private static final class GroupHeader {
        final List<ProtoNode> fields;
        @Nullable
        final ProtoNode seenKey;
        final boolean unseen;

        GroupHeader(List<ProtoNode> fields, SeenEntities seenEntities) {
            this.fields = fields;
            seenKey = ProtoNode.field(ProtoNode.message(fields, ENTITY_KEYS), SEEN_ENTITY_KEY);
            unseen = notificationUnseen(fields, seenEntities);
        }
    }

    /**
     * The entry of a video in a group has no avatar and no channel name, which the group header has,
     * and has no unseen state of its own. The entry gets the key of its own unseen entity,
     * so seeing it removes only its dot.
     */
    private static byte[] entryModel(ProtoNode entry, GroupHeader header, SeenEntities seenEntities,
                                     InboxVideos videos) throws IOException {
        List<ProtoNode> model = ProtoNode.parse(entry.getPayload());
        if (model == null) {
            return entry.getPayload();
        }

        ProtoNode texts = ProtoNode.field(model, TEXTS);
        ProtoNode channelName = ProtoNode.field(ProtoNode.message(header.fields, TEXTS), CHANNEL_NAME);
        if (texts != null && texts.children != null && channelName != null
                && ProtoNode.field(texts.children, CHANNEL_NAME) == null) {
            texts.setPayload(ProtoBytes.concat(texts.getPayload(), raw(channelName)));
        }

        // The entry keeps its own id, which its menu commands such as hiding the notification refer to.
        ProtoNode entityKeys = ProtoNode.field(model, ENTITY_KEYS);
        ProtoNode ownSeenKey = entityKeys == null || entityKeys.children == null
                ? null
                : ProtoNode.field(entityKeys.children, SEEN_ENTITY_KEY);
        String key = entryKey(model);

        String videoId = videoId(model);
        byte[] addedSeenKey = null;
        boolean unseen;
        if (key != null && seenEntities.canAdd()) {
            unseen = isUnseen(key, videoId, header, seenEntities);
            seenEntities.add(key, unseen);
            if (ownSeenKey == null) {
                addedSeenKey = ProtoBytes.string(SEEN_ENTITY_KEY, key);
            }
        } else {
            unseen = header.unseen;
            if (ownSeenKey == null && header.seenKey != null) {
                addedSeenKey = raw(header.seenKey);
            }
        }
        videos.add(videoId, unseen);

        ProtoNode seen = ProtoNode.field(model, SEEN);
        byte[] missingSeen = null;
        if (seen != null) {
            if (seen.getVarint() != null) {
                seen.setVarint(unseen ? 0 : 1);
            }
        } else {
            missingSeen = ProtoBytes.encode(output -> output.writeUInt64(SEEN, unseen ? 0 : 1));
        }

        byte[] missingEntityKeys = null;
        if (addedSeenKey != null) {
            if (entityKeys == null) {
                missingEntityKeys = ProtoBytes.message(ENTITY_KEYS, addedSeenKey);
            } else if (!isUnreadable(entityKeys)) {
                entityKeys.setPayload(ProtoBytes.concat(entityKeys.getPayload(), addedSeenKey));
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ProtoNode.write(model));
        ProtoNode avatar = ProtoNode.field(header.fields, AVATAR);
        if (avatar != null && ProtoNode.field(model, AVATAR) == null) {
            out.write(raw(avatar));
        }
        if (missingEntityKeys != null) {
            out.write(missingEntityKeys);
        }
        if (missingSeen != null) {
            out.write(missingSeen);
        }
        return out.toByteArray();
    }

    /**
     * @return The key of the unseen entity of the entry, its own or one built from its notification id,
     *         or null if its keys can't be read.
     */
    @Nullable
    private static String entryKey(List<ProtoNode> model) {
        ProtoNode entityKeys = ProtoNode.field(model, ENTITY_KEYS);
        if (entityKeys != null && isUnreadable(entityKeys)) {
            return null;
        }
        ProtoNode ownSeenKey = entityKeys == null || entityKeys.children == null
                ? null
                : ProtoNode.field(entityKeys.children, SEEN_ENTITY_KEY);
        if (ownSeenKey == null) {
            return ownKey(model);
        }
        return ownSeenKey.isText() ? ownSeenKey.getText() : null;
    }

    /**
     * @return Whether the field has content that is not a message.
     */
    private static boolean isUnreadable(ProtoNode field) {
        return field.children == null && field.getPayload().length > 0;
    }

    /**
     * @return The key of the unseen entity of the notification of the entry, built from its notification id.
     */
    @Nullable
    private static String ownKey(List<ProtoNode> model) {
        Long notificationId = notificationId(model);
        return notificationId == null ? null : SeenEntities.key(notificationId);
    }

    /**
     * @return The state from the server if there is one, otherwise the state of {@link #localUnseen}.
     */
    private static boolean isUnseen(String key, @Nullable String videoId, GroupHeader header,
                                    SeenEntities seenEntities) {
        Boolean serverUnseen = seenEntities.isUnseen(key);
        return serverUnseen != null ? serverUnseen : localUnseen(videoId, header.unseen);
    }

    /**
     * The unseen state of the group is the state of its newest video,
     * so a video that was shown as unseen stays unseen when only the newest video is seen.
     */
    private static boolean localUnseen(@Nullable String videoId, boolean groupUnseen) {
        return (groupUnseen || SeenVideos.wasShownUnseen(videoId)) && !SeenVideos.contains(videoId);
    }

    private static byte[] raw(ProtoNode node) throws IOException {
        return ProtoNode.write(List.of(node));
    }

    @Nullable
    private static String videoId(List<ProtoNode> model) {
        for (int[] path : VIDEO_IDS) {
            ProtoNode videoId = ProtoNode.field(ProtoNode.message(model, path), 1);
            if (videoId != null && videoId.isText()) {
                return videoId.getText();
            }
        }
        return null;
    }
}
