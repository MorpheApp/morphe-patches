/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.patches.CaptionXml.children;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Remove orphan ASR append events, never normalize sampled caption strings. */
public final class TranslatedRollingCaptionPolicy {
    private TranslatedRollingCaptionPolicy() {}

    public static boolean isTranslatedAsr(String vss, String url) {
        if (vss == null || !vss.startsWith("ta.")) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"https".equals(uri.getScheme())
                    || host == null
                    || !(host.equals("youtube.com") || host.endsWith(".youtube.com"))
                    || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !"/api/timedtext".equals(uri.getPath())
                    || uri.getRawQuery() == null) return false;
            Map<String, String> values = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                int at = part.indexOf('=');
                String key = URLDecoder.decode(at < 0 ? part : part.substring(0, at), "UTF-8");
                if (!key.equals("kind") && !key.equals("tlang")) continue;
                if (values.containsKey(key)) return false;
                values.put(key, URLDecoder.decode(at < 0 ? "" : part.substring(at + 1), "UTF-8"));
            }
            String target = values.get("tlang");
            return "asr".equals(values.get("kind"))
                    && target != null
                    && target.matches("[a-z]{2,8}(-[A-Za-z0-9]{2,8})*")
                    && vss.endsWith("." + target);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static byte[] repair(byte[] input) {
        return repair(input, CaptionXml.read(input));
    }

    static byte[] repair(byte[] input, Document doc) {
        try {
            if (doc == null) return input;
            Element root = doc.getDocumentElement();
            Element head = onlyChild(root, "head"), body = onlyChild(root, "body");
            if (head == null || body == null) return input;
            Map<String, Boolean> styles = new HashMap<>();
            Set<String> duplicateStyles = new HashSet<>();
            for (Element e : children(head))
                if (e.getTagName().equals("ws")) {
                    String id = e.getAttribute("id");
                    if (id.trim().isEmpty()) continue;
                    if (styles.put(id, "2".equals(e.getAttribute("mh"))) != null)
                        duplicateStyles.add(id);
                }
            Map<String, String> windows = new HashMap<>();
            Set<String> duplicateWindows = new HashSet<>();
            Map<String, List<Event>> events = new HashMap<>();
            for (Element e : children(body)) {
                if (e.getTagName().equals("w")) {
                    String id = e.getAttribute("id");
                    if (id.trim().isEmpty()) continue;
                    if (windows.put(id, e.getAttribute("ws")) != null) duplicateWindows.add(id);
                } else if (e.getTagName().equals("p")) {
                    String id = e.getAttribute("w");
                    if (!id.isEmpty())
                        events.computeIfAbsent(id, k -> new ArrayList<>()).add(new Event(e));
                }
            }
            List<Element> remove = new ArrayList<>();
            for (Map.Entry<String, List<Event>> entry : events.entrySet()) {
                String window = entry.getKey(), style = windows.get(window);
                if (style == null || style.trim().isEmpty()) continue;
                if (duplicateWindows.contains(window)
                        || duplicateStyles.contains(style)
                        || !Boolean.TRUE.equals(styles.get(style))) continue;
                List<Event> list = entry.getValue();
                boolean valid = true;
                long previous = -1;
                // Untimed/unknown events are local barriers, not grounds to disable unrelated valid
                // runs.
                for (Event e : list)
                    if (e.valid) {
                        if (e.start < previous) {
                            valid = false;
                            break;
                        }
                        previous = e.start;
                    }
                if (!valid) continue;
                int i = 1;
                while (i < list.size()) {
                    Event prior = list.get(i - 1);
                    if (!prior.text || !list.get(i).newline) {
                        i++;
                        continue;
                    }
                    int end = i;
                    while (end < list.size() && list.get(end).newline) end++;
                    if (end - i >= 2 && end < list.size()) {
                        Event next = list.get(end), first = list.get(i), last = list.get(end - 1);
                        if (next.text
                                && prior.end <= next.start
                                && first.end == prior.end
                                && prior.start < first.start
                                && last.start < next.start
                                && last.end > next.start) {
                            for (int n = i; n < end; n++) remove.add(list.get(n).element);
                        }
                    }
                    i = end;
                }
            }
            if (remove.isEmpty()) return input;
            for (Element e : remove) body.removeChild(e);
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception | LinkageError ignored) {
            return input;
        }
    }

    private static Element onlyChild(Element parent, String tag) {
        Element result = null;
        for (Element e : children(parent))
            if (e.getTagName().equals(tag)) {
                if (result != null) return null;
                result = e;
            }
        return result;
    }

    private static final class Event {
        final Element element;
        long start, end;
        boolean valid, text, newline;

        Event(Element element) {
            this.element = element;
            try {
                start = Long.parseLong(element.getAttribute("t"));
                long duration = Long.parseLong(element.getAttribute("d"));
                if (start < 0 || duration <= 0 || start > Long.MAX_VALUE - duration) return;
                end = start + duration;
                valid = true;
                String content = element.getTextContent(), append = element.getAttribute("a");
                text = (append.isEmpty() || append.equals("0")) && !content.trim().isEmpty();
                newline = append.equals("1") && content.equals("\n") && children(element).isEmpty();
                NamedNodeMap attrs = element.getAttributes();
                for (int i = 0; i < attrs.getLength(); i++) {
                    String name = attrs.item(i).getNodeName();
                    if (!name.equals("t")
                            && !name.equals("d")
                            && !name.equals("w")
                            && !name.equals("a")) newline = false;
                }
            } catch (RuntimeException ignored) {
                valid = false;
            }
        }
    }
}
