package app.morphe.extension.shared.spoof.js;

public enum JavaScriptVariant {
    HOUSE_BRAND("https://youtube.googleapis.com/s/player/%s/house_brand_player.vflset/en_US/base.js"),
    PHONE("https://m.youtube.com/s/player/%s/player-plasma-ias-phone-en_US.vflset/base.js"),
    TV("https://www.youtube.com/s/player/%s/tv-player-ias.vflset/tv-player-ias.js"),
    TV_ES6("https://www.youtube.com/s/player/%s/tv-player-es6.vflset/tv-player-es6.js"),
    WEB("https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js"),
    WEB_ES6("https://www.youtube.com/s/player/%s/player_es6.vflset/en_US/base.js"),
    WEB_EMBED("https://www.youtube.com/s/player/%s/player_embed.vflset/en_US/base.js");

    public final String format;

    JavaScriptVariant(String format) {
        this.format = format;
    }
}
