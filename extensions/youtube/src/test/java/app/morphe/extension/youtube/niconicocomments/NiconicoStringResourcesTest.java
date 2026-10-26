package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

public class NiconicoStringResourcesTest {
    @Test
    public void preservesEnglishDialogAndFormattedValues() throws Exception {
        check("values", "Danmaku quick settings", "Font size: 24sp", "Opacity: 80%");
    }

    @Test
    public void preservesKoreanDialogAndFormattedValues() throws Exception {
        check("values-ko-rKR", "탄막 간이 설정", "폰트 크기: 24sp", "불투명도: 80%");
    }

    @Test
    public void preservesJapaneseDialogAndFormattedValues() throws Exception {
        check("values-ja-rJP", "弾幕簡易設定", "フォントサイズ: 24sp", "不透明度: 80%");
    }

    private static void check(String locale, String title, String font, String opacity) throws Exception {
        File resources = new File("../../patches/src/main/resources/addresources/" + locale + "/youtube/strings.xml");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList elements = factory.newDocumentBuilder().parse(resources).getElementsByTagName("string");
        Map<String, String> strings = new HashMap<>();
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.getAttribute("name").startsWith("morphe_niconico_")) {
                strings.put(element.getAttribute("name"), element.getTextContent());
            }
        }
        assertEquals(title, strings.get("morphe_niconico_quick_settings_title"));
        assertEquals(font, String.format(Locale.ROOT, strings.get("morphe_niconico_font_size_value"), 24));
        assertEquals(opacity, String.format(Locale.ROOT, strings.get("morphe_niconico_opacity_value"), 80));
        assertNotNull(strings.get("morphe_niconico_save"));
        assertNotNull(strings.get("morphe_niconico_button_on"));
        assertNotNull(strings.get("morphe_niconico_button_off"));
    }
}
