package app.morphe.extension.youtube.niconicocomments;

import java.util.Locale;

/** Localized labels for the resource-independent quick-settings dialog. */
final class QuickSettingsStrings {
    final String title;
    final String fontSize;
    final String opacity;
    final String cancel;
    final String save;

    private QuickSettingsStrings(
            String title,
            String fontSize,
            String opacity,
            String cancel,
            String save
    ) {
        this.title = title;
        this.fontSize = fontSize;
        this.opacity = opacity;
        this.cancel = cancel;
        this.save = save;
    }

    static QuickSettingsStrings forLocale(Locale locale) {
        String language = locale == null ? "" : locale.getLanguage();
        if ("ko".equals(language)) {
            return new QuickSettingsStrings(
                    "탄막 간이 설정",
                    "폰트 크기",
                    "투명도",
                    "취소",
                    "저장"
            );
        }
        if ("ja".equals(language)) {
            return new QuickSettingsStrings(
                    "弾幕簡易設定",
                    "フォントサイズ",
                    "不透明度",
                    "キャンセル",
                    "保存"
            );
        }
        return new QuickSettingsStrings(
                "Danmaku quick settings",
                "Font size",
                "Opacity",
                "Cancel",
                "Save"
        );
    }

    String formatFontSize(int sizeSp) {
        return fontSize + ": " + sizeSp + "sp";
    }

    String formatOpacity(int opacityPercent) {
        return opacity + ": " + opacityPercent + "%";
    }
}
