/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4149
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import app.morphe.extension.shared.Logger;

/** Bounded, fail-closed srv3 reader shared by source capture and translated-caption repair. */
final class CaptionXml {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern ENCODING =
            Pattern.compile(
                    "^\\s*<\\?xml\\s+[^?]*\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1",
                    Pattern.CASE_INSENSITIVE);

    private CaptionXml() {}

    static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element) elements.add((Element) node);
        return elements;
    }

    static Document read(byte[] input) {
        if (input == null || input.length == 0 || input.length > MAX_BYTES) return null;
        try {
            String source =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(input))
                            .toString();
            if (source.startsWith("\uFEFF")) source = source.substring(1);
            Matcher encoding = ENCODING.matcher(source);
            //noinspection ConstantConditions
            if (encoding.find()
                    && !encoding.group(2).equalsIgnoreCase("UTF-8")
                    && !encoding.group(2).equalsIgnoreCase("UTF8")) return null;
            if (source.indexOf('\0') >= 0
                    || source.contains("<!DOCTYPE")
                    || source.contains("<!ENTITY")) return null;
            var builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            builder.setEntityResolver(
                    (publicId, systemId) -> {
                        throw new SAXException("External caption entity");
                    });
            builder.setErrorHandler(
                    new DefaultHandler() {
                        @Override
                        public void error(SAXParseException error) throws SAXException {
                            throw error;
                        }

                        @Override
                        public void fatalError(SAXParseException error) throws SAXException {
                            throw error;
                        }
                    });
            // Parse the same validated characters; the declaration cannot reinterpret bytes.
            Document document = builder.parse(new InputSource(new StringReader(source)));
            Element root = document.getDocumentElement();
            return root.getTagName().equals("timedtext") && root.getAttribute("format").equals("3")
                    ? document
                    : null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Unsupported caption document", ex);
            return null;
        }
    }
}
