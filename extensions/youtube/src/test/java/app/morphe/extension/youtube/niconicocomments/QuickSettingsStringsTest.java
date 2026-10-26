package app.morphe.extension.youtube.niconicocomments;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;

public class QuickSettingsStringsTest {
    @Test
    public void selectsKoreanJapaneseAndEnglishWithEnglishFallback() {
        QuickSettingsStrings korean = QuickSettingsStrings.forLocale(Locale.KOREAN);
        assertEquals("탄막 간이 설정", korean.title);
        assertEquals("폰트 크기: 24sp", korean.formatFontSize(24));
        assertEquals("투명도: 80%", korean.formatOpacity(80));
        assertEquals("취소", korean.cancel);
        assertEquals("저장", korean.save);

        QuickSettingsStrings japanese = QuickSettingsStrings.forLocale(Locale.JAPANESE);
        assertEquals("弾幕簡易設定", japanese.title);
        assertEquals("フォントサイズ: 24sp", japanese.formatFontSize(24));
        assertEquals("不透明度: 80%", japanese.formatOpacity(80));
        assertEquals("キャンセル", japanese.cancel);
        assertEquals("保存", japanese.save);

        QuickSettingsStrings english = QuickSettingsStrings.forLocale(Locale.ENGLISH);
        assertEquals("Danmaku quick settings", english.title);
        assertEquals("Font size: 24sp", english.formatFontSize(24));
        assertEquals("Opacity: 80%", english.formatOpacity(80));
        assertEquals("Cancel", english.cancel);
        assertEquals("Save", english.save);

        QuickSettingsStrings fallback = QuickSettingsStrings.forLocale(Locale.FRENCH);
        assertEquals("Danmaku quick settings", fallback.title);
        assertEquals("Font size: 24sp", fallback.formatFontSize(24));
        assertEquals("Opacity: 80%", fallback.formatOpacity(80));
        assertEquals("Cancel", fallback.cancel);
        assertEquals("Save", fallback.save);
    }
}
