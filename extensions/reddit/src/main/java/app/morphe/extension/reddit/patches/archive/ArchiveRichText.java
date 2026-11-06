package app.morphe.extension.reddit.patches.archive;

import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Converts archive Markdown into Reddit's existing rich-text document format. No WebView. */
public final class ArchiveRichText {
    private ArchiveRichText() {}
    private static final Parser PARSER = Parser.builder().build();
    public static String preview(String markdown) {
        return TextContentRenderer.builder().build().render(PARSER.parse(markdown));
    }
    public static String json(String markdown) {
        try { return new JSONObject().put("document", blocks(PARSER.parse(markdown), 0)).toString(); }
        catch (JSONException error) { throw new IllegalArgumentException("Could not convert archived text", error); }
    }
    private static JSONArray blocks(Node parent, int depth) throws JSONException {
        JSONArray result = new JSONArray();
        if (depth > 64) return result;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            JSONObject block;
            if (node instanceof BlockQuote) block = new JSONObject().put("e", "blockquote").put("c", blocks(node, depth + 1));
            else if (node instanceof BulletList || node instanceof OrderedList) {
                JSONArray items = new JSONArray();
                for (Node child = node.getFirstChild(); child != null; child = child.getNext())
                    items.put(new JSONObject().put("e", "li").put("c", blocks(child, depth + 1)));
                block = new JSONObject().put("e", "list").put("o", node instanceof OrderedList).put("c", items);
                if (node instanceof OrderedList) block.put("s", ((OrderedList) node).getMarkerStartNumber());
            } else if (node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock) {
                String code = node instanceof FencedCodeBlock ? ((FencedCodeBlock) node).getLiteral() : ((IndentedCodeBlock) node).getLiteral();
                block = new JSONObject().put("e", "code").put("c", new JSONArray().put(text(code, 0)));
            } else if (node instanceof ThematicBreak) block = new JSONObject().put("e", "hr");
            else {
                JSONArray inline = new JSONArray();
                if (node instanceof HtmlBlock) inline.put(text(((HtmlBlock) node).getLiteral(), 0));
                else inline(node, inline, 0, depth + 1);
                block = new JSONObject().put("e", node instanceof Heading ? "h" : "par").put("c", inline);
                if (node instanceof Heading) block.put("l", ((Heading) node).getLevel());
            }
            result.put(block);
        }
        return result;
    }
    private static void inline(Node parent, JSONArray output, int format, int depth) throws JSONException {
        if (depth > 64) return;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Text) output.put(text(((Text) node).getLiteral(), format));
            else if (node instanceof Code) output.put(text(((Code) node).getLiteral(), format | 16));
            else if (node instanceof SoftLineBreak) output.put(text("\n", format));
            else if (node instanceof HardLineBreak) output.put(new JSONObject().put("e", "br"));
            else if (node instanceof HtmlInline) output.put(text(((HtmlInline) node).getLiteral(), format));
            else if (node instanceof Link) {
                Link link = (Link) node;
                String label = label(node, 0);
                String url = link.getDestination();
                if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("/r/") || url.startsWith("/u/"))
                    output.put(new JSONObject().put("e", "link").put("t", label).put("u", url).put("a", link.getTitle()));
                else output.put(text(label, format));
            } else if (node instanceof Image) {
                // Text restoration never fetches or introduces archived media.
                output.put(text(label(node, 0), format));
            } else inline(node, output, format | (node instanceof StrongEmphasis ? 1 : node instanceof Emphasis ? 2 : 0), depth + 1);
        }
    }
    private static String label(Node parent, int depth) {
        if (depth > 64) return "";
        StringBuilder value = new StringBuilder();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text) value.append(((Text) child).getLiteral());
            else if (child instanceof Code) value.append(((Code) child).getLiteral());
            else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) value.append(' ');
            else value.append(label(child, depth + 1));
        }
        return value.toString();
    }
    private static JSONObject text(String value, int format) throws JSONException {
        JSONObject node = new JSONObject().put("e", "text").put("t", value);
        if (format != 0 && !value.isEmpty()) node.put("f", new JSONArray().put(new JSONArray().put(format).put(0).put(value.length())));
        return node;
    }
    public static String plain(String json) {
        if (json == null || !json.startsWith("{")) return json;
        try { return plainNodes(new JSONObject(json).optJSONArray("document"), 0).trim(); }
        catch (JSONException ignored) { return json; }
    }
    private static String plainNodes(JSONArray nodes, int depth) {
        if (nodes == null || depth > 64) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            result.append(node.optString("t", "")).append(plainNodes(node.optJSONArray("c"), depth + 1));
            if (node.optString("e").equals("par")) result.append('\n');
        }
        return result.toString();
    }
}
