/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.shared.spoof.js;

public enum JavaScriptVariant {
    // Used in Google Drive, this variant is the only one that does not have player ad-related code.
    // In theory, it seems like the best variant, but it hasn't been tested enough.
    HOUSE_BRAND("https://youtube.googleapis.com/s/player/%s/house_brand_player.vflset/en_US/base.js"),
    PHONE("https://m.youtube.com/s/player/%s/player-plasma-ias-phone-en_US.vflset/base.js"),
    TV_ES6("https://www.youtube.com/s/player/%s/tv-player-es6.vflset/tv-player-es6.js"),
    WEB("https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js"),
    WEB_ES6("https://www.youtube.com/s/player/%s/player_es6.vflset/en_US/base.js"),
    WEB_EMBED("https://www.youtube.com/s/player/%s/player_embed.vflset/en_US/base.js");

    public final String format;

    JavaScriptVariant(String format) {
        this.format = format;
    }
}
