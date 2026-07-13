## [1.35.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.35.0-dev.2...v1.35.0-dev.3) (2026-07-13)

### 🐛 Bug Fixes

* **YouTube Music:** Fix 9.15.51 startup ([9e2c372](https://github.com/MorpheApp/morphe-patches/commit/9e2c37218aeb601d1d05d66fba4d89255d20c01f))

## [1.35.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.35.0-dev.1...v1.35.0-dev.2) (2026-07-13)

### 🐛 Bug Fixes

* **YouTube - Playback speed:** Support new native playback speed menu ([#1957](https://github.com/MorpheApp/morphe-patches/issues/1957)) ([a4641d6](https://github.com/MorpheApp/morphe-patches/commit/a4641d6731170e03b26abc4ecb37230193271673))
* **YouTube Music - Downloads:** Hide Premium dialog on external download ([#2009](https://github.com/MorpheApp/morphe-patches/issues/2009)) ([4cfe552](https://github.com/MorpheApp/morphe-patches/commit/4cfe552daa946c8d4ca946aeffffb63b62ad8370))

### ✨ New Features

* **YouTube Music:** Add `Disable dislike redirection` patch ([#1962](https://github.com/MorpheApp/morphe-patches/issues/1962)) ([e399e05](https://github.com/MorpheApp/morphe-patches/commit/e399e05835f40e00a5efb9f311eadbee9be599a9))

## [1.35.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.34.0...v1.35.0-dev.1) (2026-07-12)

### 🐛 Bug Fixes

* **Spoof video streams:** Check setting value instead of availability for JavaScript toggles ([1d92650](https://github.com/MorpheApp/morphe-patches/commit/1d926503bf9ef29d8e135786479ecef1de20adbc))
* **YouTube - Copy video link:** Copied video link is always shortened ([#1993](https://github.com/MorpheApp/morphe-patches/issues/1993)) ([ff0e8cc](https://github.com/MorpheApp/morphe-patches/commit/ff0e8ccb935ca3e267c625778ad93f2db213c367))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide gift animation and cards" and "Hide gift button" settings ([#1991](https://github.com/MorpheApp/morphe-patches/issues/1991)) ([83451d9](https://github.com/MorpheApp/morphe-patches/commit/83451d95249b4e951b466c3b7ff8a64b3d7f1a4d))
* **YouTube - Swipe controls:** Add playback speed step preference ([#1977](https://github.com/MorpheApp/morphe-patches/issues/1977)) ([25927d1](https://github.com/MorpheApp/morphe-patches/commit/25927d12ec5d5a1c0bba570a70ba09fe1e28497c))
* **YouTube Music:** Add `Spoof app version` patch ([#1948](https://github.com/MorpheApp/morphe-patches/issues/1948)) ([46a86d3](https://github.com/MorpheApp/morphe-patches/commit/46a86d3106e9e00d718e5410ef0c03fb6b228f90))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.28.0` ([9f18957](https://github.com/MorpheApp/morphe-patches/commit/9f18957acd350db0180cb2c66521e8b83b598558))

## [1.34.0](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0...v1.34.0) (2026-07-10)

### 🐛 Bug Fixes

* **GmsCore support:** Use custom provider package name when `Change package name` is used ([7d0d56d](https://github.com/MorpheApp/morphe-patches/commit/7d0d56d0652dbfd3bb6cd5b21dc21310e2b73e68))
* **Reddit - Settings:** Morphe language does not change after restarting ([#1950](https://github.com/MorpheApp/morphe-patches/issues/1950)) ([fc41c27](https://github.com/MorpheApp/morphe-patches/commit/fc41c2757c1c6897e314e35de3e227adede03939))
* **Spoof video streams:** Change default client to 'TV' ([ce3f496](https://github.com/MorpheApp/morphe-patches/commit/ce3f496f9013af2d4146dc0d0f86a629d9dd7d9d))
* **YouTube - Add to queue:** Allows tablet patches for versions 21.xx ([6ef2a3f](https://github.com/MorpheApp/morphe-patches/commit/6ef2a3f7aadb845b0e0ec5b839773d8e68504868))
* **YouTube - Add to queue:** Prevents the flyout video id reset blocking ([93d134f](https://github.com/MorpheApp/morphe-patches/commit/93d134f9df792e35f0b1e40ef933262bc40fa517))
* **YouTube - Miniplayer/Disable automatic reposition:** Support vertical position lock ([4adc714](https://github.com/MorpheApp/morphe-patches/commit/4adc71465bbee964112e45ffa93e685901fcaec5))
* **YouTube - Return YouTube Dislike:** Remove RYD Shorts due to YT removing Shorts dislike and RYD API no longer providing reliable Shorts dislikes data ([#1924](https://github.com/MorpheApp/morphe-patches/issues/1924)) ([fcb613a](https://github.com/MorpheApp/morphe-patches/commit/fcb613aacb28ccd319f7211282305d0c6a912874))
* **YouTube - Spoof video streams:** Mention Android Studio 720p limitation in settings UI ([387abbf](https://github.com/MorpheApp/morphe-patches/commit/387abbf4c543a4c5ed9b6574bf792e7ce3b1c949))
* **YouTube - Voice over translation:** Keep the user's original audio volume ratio after rewinding ([#1939](https://github.com/MorpheApp/morphe-patches/issues/1939)) ([d673d91](https://github.com/MorpheApp/morphe-patches/commit/d673d9181d7a203824bab2a83955d965d2423c3b))
* **YouTube Music - Downloads:** Add "Override feed/search flyout button" setting ([#1931](https://github.com/MorpheApp/morphe-patches/issues/1931)) ([af7cf55](https://github.com/MorpheApp/morphe-patches/commit/af7cf55a99c9ebf8e42649af4b931bb74cedf086))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide filter bar in comments" and "Sanitize highlighted search links" settings ([#1935](https://github.com/MorpheApp/morphe-patches/issues/1935)) ([1876708](https://github.com/MorpheApp/morphe-patches/commit/187670860225c5c8272a4f34d131a98448ca311c))
* **YouTube - Miniplayer:** Add "Disable automatic reposition" setting ([#1920](https://github.com/MorpheApp/morphe-patches/issues/1920)) ([e49c0b4](https://github.com/MorpheApp/morphe-patches/commit/e49c0b4053a2bcfd0f9b1140d1e5438e43d215c5))
* **YouTube - Miniplayer:** Add "Disable playback with horizontal drag" setting ([#1916](https://github.com/MorpheApp/morphe-patches/issues/1916)) ([186f060](https://github.com/MorpheApp/morphe-patches/commit/186f0603352f26e30fdfab100fe0b77eb22496d6))
* **YouTube - Theme:** Add "Modern YouTube" dark theme preset ([#1918](https://github.com/MorpheApp/morphe-patches/issues/1918)) ([0a381c8](https://github.com/MorpheApp/morphe-patches/commit/0a381c8c0cd80d1bd3cb1f339587e48798ced00f))
* **YouTube Music - Change miniplayer color:** Add "Change navigation bar color" setting ([#1908](https://github.com/MorpheApp/morphe-patches/issues/1908)) ([9b570cd](https://github.com/MorpheApp/morphe-patches/commit/9b570cd2cfcd0695fa38ea84541133940146e7cf))
* **YouTube Music - Settings:** Add Morphe language selector ([#1937](https://github.com/MorpheApp/morphe-patches/issues/1937)) ([443500c](https://github.com/MorpheApp/morphe-patches/commit/443500c66718d722a393707712c78ac3232e0fbd))
* **YouTube Music:** Add `Downloads` patch ([#1881](https://github.com/MorpheApp/morphe-patches/issues/1881)) ([d078a37](https://github.com/MorpheApp/morphe-patches/commit/d078a37cd1a8ef93f9546efb24e783a7b826a8b8))
* **YouTube Music:** Add `Hide flyout menu components` patch ([#1906](https://github.com/MorpheApp/morphe-patches/issues/1906)) ([dbc4ba0](https://github.com/MorpheApp/morphe-patches/commit/dbc4ba08c0e23838dafa17d7fd0cfc9d33ba6524))
* **YouTube Music:** Add `Hide layout components` patch with `Custom filter` ([#1919](https://github.com/MorpheApp/morphe-patches/issues/1919)) ([f6e9849](https://github.com/MorpheApp/morphe-patches/commit/f6e9849331a16eebf870338e75012a529915d3b8))
* **YouTube Music:** Add `Hide music action buttons` patch ([#1953](https://github.com/MorpheApp/morphe-patches/issues/1953)) ([b142f87](https://github.com/MorpheApp/morphe-patches/commit/b142f87e39cbe30e046d8731fa2997d6a1523bf7))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.27.0` ([4dabc41](https://github.com/MorpheApp/morphe-patches/commit/4dabc4142664669937d47ffad7d6f768aaa48f8e))

## [1.34.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.34.0-dev.6...v1.34.0-dev.7) (2026-07-09)

### 🐛 Bug Fixes

* **YouTube - Spoof video streams:** Mention Android Studio 720p limitation in settings UI ([387abbf](https://github.com/MorpheApp/morphe-patches/commit/387abbf4c543a4c5ed9b6574bf792e7ce3b1c949))

## [1.34.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.34.0-dev.5...v1.34.0-dev.6) (2026-07-09)

### 🐛 Bug Fixes

* **Spoof video streams:** Change default client to 'TV' ([ce3f496](https://github.com/MorpheApp/morphe-patches/commit/ce3f496f9013af2d4146dc0d0f86a629d9dd7d9d))

### ✨ New Features

* **YouTube Music:** Add `Hide music action buttons` patch ([#1953](https://github.com/MorpheApp/morphe-patches/issues/1953)) ([b142f87](https://github.com/MorpheApp/morphe-patches/commit/b142f87e39cbe30e046d8731fa2997d6a1523bf7))

## [1.34.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.34.0-dev.4...v1.34.0-dev.5) (2026-07-09)

### 🐛 Bug Fixes

* **Reddit - Settings:** Morphe language does not change after restarting ([#1950](https://github.com/MorpheApp/morphe-patches/issues/1950)) ([fc41c27](https://github.com/MorpheApp/morphe-patches/commit/fc41c2757c1c6897e314e35de3e227adede03939))

### ✨ New Features

* **YouTube Music - Change miniplayer color:** Add "Change navigation bar color" setting ([#1908](https://github.com/MorpheApp/morphe-patches/issues/1908)) ([9b570cd](https://github.com/MorpheApp/morphe-patches/commit/9b570cd2cfcd0695fa38ea84541133940146e7cf))

## [1.34.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.34.0-dev.3...v1.34.0-dev.4) (2026-07-08)

### 🐛 Bug Fixes

* **GmsCore support:** Use custom provider package name when `Change package name` is used ([7d0d56d](https://github.com/MorpheApp/morphe-patches/commit/7d0d56d0652dbfd3bb6cd5b21dc21310e2b73e68))
* **YouTube - Voice over translation:** Keep the user's original audio volume ratio after rewinding ([#1939](https://github.com/MorpheApp/morphe-patches/issues/1939)) ([d673d91](https://github.com/MorpheApp/morphe-patches/commit/d673d9181d7a203824bab2a83955d965d2423c3b))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide filter bar in comments" and "Sanitize highlighted search links" settings ([#1935](https://github.com/MorpheApp/morphe-patches/issues/1935)) ([1876708](https://github.com/MorpheApp/morphe-patches/commit/187670860225c5c8272a4f34d131a98448ca311c))
* **YouTube Music:** Add `Hide flyout menu components` patch ([#1906](https://github.com/MorpheApp/morphe-patches/issues/1906)) ([dbc4ba0](https://github.com/MorpheApp/morphe-patches/commit/dbc4ba08c0e23838dafa17d7fd0cfc9d33ba6524))
* **YouTube Music:** Add `Hide layout components` patch with `Custom filter` ([#1919](https://github.com/MorpheApp/morphe-patches/issues/1919)) ([f6e9849](https://github.com/MorpheApp/morphe-patches/commit/f6e9849331a16eebf870338e75012a529915d3b8))

## [1.34.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.34.0-dev.2...v1.34.0-dev.3) (2026-07-07)

### ✨ New Features

* **YouTube - Miniplayer:** Add "Disable automatic reposition" setting ([#1920](https://github.com/MorpheApp/morphe-patches/issues/1920)) ([e49c0b4](https://github.com/MorpheApp/morphe-patches/commit/e49c0b4053a2bcfd0f9b1140d1e5438e43d215c5))
* **YouTube Music - Settings:** Add Morphe language selector ([#1937](https://github.com/MorpheApp/morphe-patches/issues/1937)) ([443500c](https://github.com/MorpheApp/morphe-patches/commit/443500c66718d722a393707712c78ac3232e0fbd))

## [1.34.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.34.0-dev.1...v1.34.0-dev.2) (2026-07-05)

### 🐛 Bug Fixes

* **YouTube - Return YouTube Dislike:** Remove RYD Shorts due to YT removing Shorts dislike and RYD API no longer providing reliable Shorts dislikes data ([#1924](https://github.com/MorpheApp/morphe-patches/issues/1924)) ([fcb613a](https://github.com/MorpheApp/morphe-patches/commit/fcb613aacb28ccd319f7211282305d0c6a912874))
* **YouTube Music - Downloads:** Add "Override feed/search flyout button" setting ([#1931](https://github.com/MorpheApp/morphe-patches/issues/1931)) ([af7cf55](https://github.com/MorpheApp/morphe-patches/commit/af7cf55a99c9ebf8e42649af4b931bb74cedf086))

## [1.34.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0...v1.34.0-dev.1) (2026-07-05)

### 🐛 Bug Fixes

* **YouTube - Add to queue:** Patch not working for tablets on `21.xx` ([6ef2a3f](https://github.com/MorpheApp/morphe-patches/commit/6ef2a3f7aadb845b0e0ec5b839773d8e68504868))
* **YouTube - Add to queue:** Prevent the flyout video ID reset blocking ([93d134f](https://github.com/MorpheApp/morphe-patches/commit/93d134f9df792e35f0b1e40ef933262bc40fa517))

### ✨ New Features

* **YouTube - Miniplayer:** Add "Disable playback with horizontal drag" setting ([#1916](https://github.com/MorpheApp/morphe-patches/issues/1916)) ([186f060](https://github.com/MorpheApp/morphe-patches/commit/186f0603352f26e30fdfab100fe0b77eb22496d6))
* **YouTube - Theme:** Add "Modern YouTube" dark theme preset ([#1918](https://github.com/MorpheApp/morphe-patches/issues/1918)) ([0a381c8](https://github.com/MorpheApp/morphe-patches/commit/0a381c8c0cd80d1bd3cb1f339587e48798ced00f))
* **YouTube Music:** Add `Downloads` patch ([#1881](https://github.com/MorpheApp/morphe-patches/issues/1881)) ([d078a37](https://github.com/MorpheApp/morphe-patches/commit/d078a37cd1a8ef93f9546efb24e783a7b826a8b8))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.27.0` ([4dabc41](https://github.com/MorpheApp/morphe-patches/commit/4dabc4142664669937d47ffad7d6f768aaa48f8e))

## [1.33.0](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0...v1.33.0) (2026-07-03)

### 🐛 Bug Fixes

* **Change package name:** Add incompatible apps, update all permissions and providers ([#1847](https://github.com/MorpheApp/morphe-patches/issues/1847)) ([ff5359a](https://github.com/MorpheApp/morphe-patches/commit/ff5359ac6d61b7b7274a7d7ed7c06198c1ed3b7f))
* **Reddit - Hide communities shelf:** Communities shelf not being hidden on experimental versions ([#1828](https://github.com/MorpheApp/morphe-patches/issues/1828)) ([0f05846](https://github.com/MorpheApp/morphe-patches/commit/0f0584668a38f7ba721a2eb9c4daec9c00de8466))
* **Reddit - Hide Trending shelves:** Hide 'Trending communities' shelf in search suggestions ([#1785](https://github.com/MorpheApp/morphe-patches/issues/1785)) ([a9a58fb](https://github.com/MorpheApp/morphe-patches/commit/a9a58fb2069437dbcbe6613f740881ca21cfd45c))
* **Reddit - Hide Trending shelves:** Hide 'Trending on Reddit' shelf ([#1849](https://github.com/MorpheApp/morphe-patches/issues/1849)) ([5dfff49](https://github.com/MorpheApp/morphe-patches/commit/5dfff49c21f0aad61e997ad1ef31c65af58f85ab))
* **YouTube - Add to queue:** Show reload options when player is minimized ([#1879](https://github.com/MorpheApp/morphe-patches/issues/1879)) ([95c4ecf](https://github.com/MorpheApp/morphe-patches/commit/95c4ecfb4525ca0964a223fdf2c0f51a9d8d6da0))
* **YouTube - Add to queue:** Support tablet form factor ([#1891](https://github.com/MorpheApp/morphe-patches/issues/1891)) ([f55b720](https://github.com/MorpheApp/morphe-patches/commit/f55b7206f78e6b5dbe063e6f9e39093929047f34))
* **YouTube - Add to queue:** Use app activity for dialog to open dialog ([f3e2b2b](https://github.com/MorpheApp/morphe-patches/commit/f3e2b2b3f3350ff6bea937f9eb81da10aab14c34))
* **YouTube - Add to queue:** Video from a playlist does not open or reload within the playlist ([fbca3e7](https://github.com/MorpheApp/morphe-patches/commit/fbca3e755a67d89026064a6c04dfbaf1bb676caf))
* **YouTube - Bypass link redirects:** Fix potential crash and cleanup ([6a0b518](https://github.com/MorpheApp/morphe-patches/commit/6a0b518403228c5575f80abf1c3cf48159454563))
* **YouTube - Disable player popup panels:** Patch not working for some users ([#1821](https://github.com/MorpheApp/morphe-patches/issues/1821)) ([2d34625](https://github.com/MorpheApp/morphe-patches/commit/2d34625cb3e6635fa5381ded1e38c464b8d9e269))
* **YouTube - Hide layout components:**  Match `RD` playlist URLs to improve "Hide mix playlists" filter ([#1835](https://github.com/MorpheApp/morphe-patches/issues/1835)) ([1172ce0](https://github.com/MorpheApp/morphe-patches/commit/1172ce044f14cc842eb6390d997d7793f8e2b4c3))
* **YouTube - Open system share sheet:** Add full link format along with shortener setting check ([0259b4c](https://github.com/MorpheApp/morphe-patches/commit/0259b4c50ea5502f8c7f2457a9d8522cbee4915a))
* **YouTube - Open system share sheet:** Increase the opening speed of the system share sheet ([9cacff8](https://github.com/MorpheApp/morphe-patches/commit/9cacff89172fb05760bdae2dbbae275c0db5934c))
* **YouTube - Open system share sheet:** Prevent consecutive attempts to close the panel ([d9c6b1b](https://github.com/MorpheApp/morphe-patches/commit/d9c6b1b08fdc290ed5f59ad80056452ca5f0d52b))
* **YouTube - Open system share sheet:** Support older app versions ([d1ccae8](https://github.com/MorpheApp/morphe-patches/commit/d1ccae8dc396322d406134122e90fb13c6c3ae0c))
* **YouTube - Reload video:** Use the correct URL query parameters ([9b399ec](https://github.com/MorpheApp/morphe-patches/commit/9b399ec5d240f84d7a9f2d2ffc3cb7877df1d0da))
* **YouTube - Return YouTube Dislike:** Unnecessary RYD API requests in playlist ([#1815](https://github.com/MorpheApp/morphe-patches/issues/1815)) ([219114a](https://github.com/MorpheApp/morphe-patches/commit/219114aaa3ae0e67993457a9f706bb5f7c397257))
* **YouTube - SponsorBlock:** Resolve missing resource dependency when patch is excluded ([3721511](https://github.com/MorpheApp/morphe-patches/commit/3721511a7e89124fe92d529399f58f3ec39b26f3))
* **YouTube - SponsorBlock:** Send correct user agent when submitting segments ([#1903](https://github.com/MorpheApp/morphe-patches/issues/1903)) ([37cecf4](https://github.com/MorpheApp/morphe-patches/commit/37cecf4e5e87599a24178aaaa7ebcd6703591c95))
* **YouTube Music - Scrobbling:** Parse artist and track from video title ([#1885](https://github.com/MorpheApp/morphe-patches/issues/1885)) ([29c8d02](https://github.com/MorpheApp/morphe-patches/commit/29c8d021426abc5d04c9cea0ae7aa9139374b38e))
* **YouTube Music - SponsorBlock:** Support older app versions ([bb74726](https://github.com/MorpheApp/morphe-patches/commit/bb7472618b96cf6953292b8466c347a1ddd523df))

### ✨ New Features

* Add `Network proxy` patch ([#1823](https://github.com/MorpheApp/morphe-patches/issues/1823)) ([6bf4d9f](https://github.com/MorpheApp/morphe-patches/commit/6bf4d9f436a9056b4af7df700ef5b91499b2c1eb))
* **YouTube - Hide layout components:** Add "Sanitize video subtitle" setting ([#1277](https://github.com/MorpheApp/morphe-patches/issues/1277)) ([0cb4bc5](https://github.com/MorpheApp/morphe-patches/commit/0cb4bc5d59c18b3df9037914db2002942db127be))
* **YouTube Music:** Add `Scrobbling` patch ([#1856](https://github.com/MorpheApp/morphe-patches/issues/1856)) ([f9ca802](https://github.com/MorpheApp/morphe-patches/commit/f9ca802aa756d7eb78ab86df5f302c3abfe2d15b))
* **YouTube Music:** Add `SponsorBlock` patch ([#1842](https://github.com/MorpheApp/morphe-patches/issues/1842)) ([0e6e8f1](https://github.com/MorpheApp/morphe-patches/commit/0e6e8f1c0d53df205be8cf12f365224293128f1f))
* **YouTube:** Add `Add to queue` patch ([#1837](https://github.com/MorpheApp/morphe-patches/issues/1837)) ([36a1e6e](https://github.com/MorpheApp/morphe-patches/commit/36a1e6e6d6c529aa94fc607970c2cf067b0f6f4c))
* **YouTube:** Add `Voice over translation` patch ([#1685](https://github.com/MorpheApp/morphe-patches/issues/1685)) ([d811a32](https://github.com/MorpheApp/morphe-patches/commit/d811a3264878d01697eb75bd05185d9359603a4a))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.26.0` ([de01fad](https://github.com/MorpheApp/morphe-patches/commit/de01fadca647632390ecf4148986880d924434b5))
* **YouTube Music:** Add experimental support for `9.25.50` ([bc6d8aa](https://github.com/MorpheApp/morphe-patches/commit/bc6d8aa3517cfa7918d0ac5849ccff42c7340ad6))
* **YouTube Music:** Add experimental support for `9.26.51` ([0be25a1](https://github.com/MorpheApp/morphe-patches/commit/0be25a10bfe1afbcb8bbdc5ff25b0272ba468ab4))
* **YouTube:** Add experimental support for `21.26.360` ([197e9ce](https://github.com/MorpheApp/morphe-patches/commit/197e9ce272515002787a41c919ff7ea8ec698f30))

## [1.33.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.9...v1.33.0-dev.10) (2026-07-02)

### 🐛 Bug Fixes

* **YouTube - Open system share sheet:** Add full link format along with shortener setting check ([0259b4c](https://github.com/MorpheApp/morphe-patches/commit/0259b4c50ea5502f8c7f2457a9d8522cbee4915a))

### 🚀 Updated App Support

* **YouTube Music:** Add experimental support for `9.26.51` ([0be25a1](https://github.com/MorpheApp/morphe-patches/commit/0be25a10bfe1afbcb8bbdc5ff25b0272ba468ab4))

## [1.33.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.8...v1.33.0-dev.9) (2026-07-01)

### 🐛 Bug Fixes

* **YouTube - Add to queue:** Support tablet form factor ([#1891](https://github.com/MorpheApp/morphe-patches/issues/1891)) ([f55b720](https://github.com/MorpheApp/morphe-patches/commit/f55b7206f78e6b5dbe063e6f9e39093929047f34))

## [1.33.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.7...v1.33.0-dev.8) (2026-06-30)

### 🐛 Bug Fixes

* **YouTube - Add to queue:** Show reload options when player is minimized ([#1879](https://github.com/MorpheApp/morphe-patches/issues/1879)) ([95c4ecf](https://github.com/MorpheApp/morphe-patches/commit/95c4ecfb4525ca0964a223fdf2c0f51a9d8d6da0))
* **YouTube - Bypass link redirects:** Fix potential crash and cleanup ([6a0b518](https://github.com/MorpheApp/morphe-patches/commit/6a0b518403228c5575f80abf1c3cf48159454563))
* **YouTube - Reload video:** Use the correct URL query parameters ([9b399ec](https://github.com/MorpheApp/morphe-patches/commit/9b399ec5d240f84d7a9f2d2ffc3cb7877df1d0da))
* **YouTube - Open system share sheet:** Support older app versions ([d1ccae8](https://github.com/MorpheApp/morphe-patches/commit/d1ccae8dc396322d406134122e90fb13c6c3ae0c))
* **YouTube Music - Scrobbling:** Parse artist and track from video title ([#1885](https://github.com/MorpheApp/morphe-patches/issues/1885)) ([29c8d02](https://github.com/MorpheApp/morphe-patches/commit/29c8d021426abc5d04c9cea0ae7aa9139374b38e))

## [1.33.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.6...v1.33.0-dev.7) (2026-06-29)

### 🐛 Bug Fixes

* **YouTube - Open system share sheet:** Prevent consecutive attempts to close the panel ([d9c6b1b](https://github.com/MorpheApp/morphe-patches/commit/d9c6b1b08fdc290ed5f59ad80056452ca5f0d52b))
* **YouTube Music - SponsorBlock:** Support older app versions ([bb74726](https://github.com/MorpheApp/morphe-patches/commit/bb7472618b96cf6953292b8466c347a1ddd523df))

## [1.33.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.5...v1.33.0-dev.6) (2026-06-29)

### 🐛 Bug Fixes

* **YouTube - SponsorBlock:** Resolve missing resource dependency when patch is excluded ([3721511](https://github.com/MorpheApp/morphe-patches/commit/3721511a7e89124fe92d529399f58f3ec39b26f3))
* **YouTube - Open system share sheet:** Increase the opening speed of the system share sheet ([9cacff8](https://github.com/MorpheApp/morphe-patches/commit/9cacff89172fb05760bdae2dbbae275c0db5934c))
* **YouTube - Add to queue:** Video from a playlist does not open or reload within the playlist ([fbca3e7](https://github.com/MorpheApp/morphe-patches/commit/fbca3e755a67d89026064a6c04dfbaf1bb676caf))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Sanitize video subtitle" setting ([#1277](https://github.com/MorpheApp/morphe-patches/issues/1277)) ([0cb4bc5](https://github.com/MorpheApp/morphe-patches/commit/0cb4bc5d59c18b3df9037914db2002942db127be))
* **YouTube Music:** Add `SponsorBlock` patch ([#1842](https://github.com/MorpheApp/morphe-patches/issues/1842)) ([0e6e8f1](https://github.com/MorpheApp/morphe-patches/commit/0e6e8f1c0d53df205be8cf12f365224293128f1f))

## [1.33.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.4...v1.33.0-dev.5) (2026-06-28)

### 🐛 Bug Fixes

* **YouTube - Add to queue:** Use app activity for dialog to open dialog ([f3e2b2b](https://github.com/MorpheApp/morphe-patches/commit/f3e2b2b3f3350ff6bea937f9eb81da10aab14c34))

## [1.33.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.3...v1.33.0-dev.4) (2026-06-28)

### ✨ New Features

* **YouTube Music:** Add `Scrobbling` patch ([#1856](https://github.com/MorpheApp/morphe-patches/issues/1856)) ([f9ca802](https://github.com/MorpheApp/morphe-patches/commit/f9ca802aa756d7eb78ab86df5f302c3abfe2d15b))
* **YouTube:** Add `Add to queue` patch ([#1837](https://github.com/MorpheApp/morphe-patches/issues/1837)) ([36a1e6e](https://github.com/MorpheApp/morphe-patches/commit/36a1e6e6d6c529aa94fc607970c2cf067b0f6f4c))

## [1.33.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.2...v1.33.0-dev.3) (2026-06-27)

### 🐛 Bug Fixes

* **Change package name:** Add incompatible apps, update all permissions and providers ([#1847](https://github.com/MorpheApp/morphe-patches/issues/1847)) ([ff5359a](https://github.com/MorpheApp/morphe-patches/commit/ff5359ac6d61b7b7274a7d7ed7c06198c1ed3b7f))
* **Reddit - Hide Trending shelves:** Hide 'Trending on Reddit' shelf ([#1849](https://github.com/MorpheApp/morphe-patches/issues/1849)) ([5dfff49](https://github.com/MorpheApp/morphe-patches/commit/5dfff49c21f0aad61e997ad1ef31c65af58f85ab))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.26.0` ([de01fad](https://github.com/MorpheApp/morphe-patches/commit/de01fadca647632390ecf4148986880d924434b5))
* **YouTube:** Add experimental support for `21.26.360` ([197e9ce](https://github.com/MorpheApp/morphe-patches/commit/197e9ce272515002787a41c919ff7ea8ec698f30))

## [1.33.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.33.0-dev.1...v1.33.0-dev.2) (2026-06-26)

### 🐛 Bug Fixes

* **YouTube - Hide layout components:**  Match `RD` playlist URLs to improve "Hide mix playlists" filter ([#1835](https://github.com/MorpheApp/morphe-patches/issues/1835)) ([1172ce0](https://github.com/MorpheApp/morphe-patches/commit/1172ce044f14cc842eb6390d997d7793f8e2b4c3))

### ✨ New Features

* **YouTube:** Add `Voice over translation` patch ([#1685](https://github.com/MorpheApp/morphe-patches/issues/1685)) ([d811a32](https://github.com/MorpheApp/morphe-patches/commit/d811a3264878d01697eb75bd05185d9359603a4a))

## [1.33.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0...v1.33.0-dev.1) (2026-06-25)

### 🐛 Bug Fixes

* **Reddit - Hide communities shelf:** Communities shelf not being hidden on experimental versions ([#1828](https://github.com/MorpheApp/morphe-patches/issues/1828)) ([0f05846](https://github.com/MorpheApp/morphe-patches/commit/0f0584668a38f7ba721a2eb9c4daec9c00de8466))
* **Reddit - Hide Trending shelves:** Hide 'Trending communities' shelf in search suggestions ([#1785](https://github.com/MorpheApp/morphe-patches/issues/1785)) ([a9a58fb](https://github.com/MorpheApp/morphe-patches/commit/a9a58fb2069437dbcbe6613f740881ca21cfd45c))
* **YouTube - Disable player popup panels:** Patch not working for some users ([#1821](https://github.com/MorpheApp/morphe-patches/issues/1821)) ([2d34625](https://github.com/MorpheApp/morphe-patches/commit/2d34625cb3e6635fa5381ded1e38c464b8d9e269))
* **YouTube - Return YouTube Dislike:** Unnecessary RYD API requests in playlist ([#1815](https://github.com/MorpheApp/morphe-patches/issues/1815)) ([219114a](https://github.com/MorpheApp/morphe-patches/commit/219114aaa3ae0e67993457a9f706bb5f7c397257))

### ✨ New Features

* Add `Network proxy` patch ([#1823](https://github.com/MorpheApp/morphe-patches/issues/1823)) ([6bf4d9f](https://github.com/MorpheApp/morphe-patches/commit/6bf4d9f436a9056b4af7df700ef5b91499b2c1eb))

### 🚀 Updated App Support

* **YouTube Music:** Add experimental support for `9.25.50` ([bc6d8aa](https://github.com/MorpheApp/morphe-patches/commit/bc6d8aa3517cfa7918d0ac5849ccff42c7340ad6))

## [1.32.0](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0...v1.32.0) (2026-06-23)

### 🐛 Bug Fixes

* **YouTube - Bypass link redirects:** Resolve patch not working on community posts and video descriptions ([#1755](https://github.com/MorpheApp/morphe-patches/issues/1755)) ([c5c6376](https://github.com/MorpheApp/morphe-patches/commit/c5c6376546efdc90cbf89f7f5554880432161b22))
* **YouTube - Change form factor:** Prevent app crash when using tablet mode in `onResume` state ([#1803](https://github.com/MorpheApp/morphe-patches/issues/1803)) ([4b0de25](https://github.com/MorpheApp/morphe-patches/commit/4b0de256e74f6eeb279ba164146fb9a01f2f8176))
* **YouTube - Copy video link:** Wrong localization resource name ([dbddc1a](https://github.com/MorpheApp/morphe-patches/commit/dbddc1a55ea37e90bb7bfae221dd86a4a042b5fb))
* **YouTube - Disable player popup panels:** Deprecated the old way to disable popup panels ([709b4fb](https://github.com/MorpheApp/morphe-patches/commit/709b4fb5f5a98713903befbbc3a54e4a11e9a668))
* **YouTube - Disable player popup panels:** Patch doesn't work in some circumstances ([6d6018c](https://github.com/MorpheApp/morphe-patches/commit/6d6018cf4a4ee6f79a283001aebe5b9d21767741))
* **YouTube - Hide layout components:** Resolve "Hide horizontal shelves" hiding other components ([#930](https://github.com/MorpheApp/morphe-patches/issues/930)) ([3c53157](https://github.com/MorpheApp/morphe-patches/commit/3c5315774896dc78ab5ad4d7b44380f9a21d3312))
* **YouTube - Sanitize sharing links:** Live links are not sanitized ([ad91b37](https://github.com/MorpheApp/morphe-patches/commit/ad91b3759c42befbf4c32804cad5de5a48f14255))
* **YouTube - Navigation bar:** Prevent navigation bar animation when swiping to dismiss miniplayer ([#1800](https://github.com/MorpheApp/morphe-patches/issues/1800)) ([dcc4c0c](https://github.com/MorpheApp/morphe-patches/commit/dcc4c0c28ac600ca057c8720f5e71f8c712011ac))
* **YouTube - Override YouTube Music buttons:** Target app opens when clicking on 'YouTube Music' button inside explore menu ([#1707](https://github.com/MorpheApp/morphe-patches/issues/1707)) ([1f08df2](https://github.com/MorpheApp/morphe-patches/commit/1f08df24848bce582919e21b4b3e3f7bb6ebe5ab))
* **YouTube - Reload video:** App exits after pressing back button ([#1740](https://github.com/MorpheApp/morphe-patches/issues/1740)) ([743bc76](https://github.com/MorpheApp/morphe-patches/commit/743bc76743c529aa8f6da4ecffd9643088c7fd97))
* **YouTube - Remove viewer discretion dialog:** In-app video downloader does not work for Premium users ([#1756](https://github.com/MorpheApp/morphe-patches/issues/1756)) ([3353302](https://github.com/MorpheApp/morphe-patches/commit/3353302753352388dbeef9454bb7902cdeb9eae0))
* **YouTube - Spoof video streams:** Always allow details fetching ([6bc0eee](https://github.com/MorpheApp/morphe-patches/commit/6bc0eeefe02fa89a5d0669698ceb2fffc5bc51c7))
* **YouTube Music - Track crossfade:** Dismissing the queue does not stop playback, fix songs incorrectly skipped ([#1773](https://github.com/MorpheApp/morphe-patches/issues/1773)) ([206b84e](https://github.com/MorpheApp/morphe-patches/commit/206b84eb6b20f873edee53253fd8460a993a30ab))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide comments by keywords" setting ([#1759](https://github.com/MorpheApp/morphe-patches/issues/1759)) ([d076c2b](https://github.com/MorpheApp/morphe-patches/commit/d076c2b3b7c561952fa2ae94be5d4de3119439af))
* **YouTube - Hide video action buttons:** Add "Restore old video action bar", "Hide Connect button", and "Hide More button" settings ([#1780](https://github.com/MorpheApp/morphe-patches/issues/1780)) ([0481899](https://github.com/MorpheApp/morphe-patches/commit/0481899aef98c45aa3cc88c9e3ff5cc8387fb4c5))
* **YouTube - Navigation bar:** Restore "Hide Cast button" setting in toolbar ([c27e63e](https://github.com/MorpheApp/morphe-patches/commit/c27e63ecb464b1e85e6ff97f13e21cc641a99fe7))
* **YouTube - Navigation bar:** Add "Hide Chat button" setting in toolbar ([32ee079](https://github.com/MorpheApp/morphe-patches/commit/32ee07910d9d80a8f3eac3b6f0dd0968a6c6c81d))
* **YouTube - Open videos fullscreen:** Add landscape mode option ([#1770](https://github.com/MorpheApp/morphe-patches/issues/1770)) ([c2e78f1](https://github.com/MorpheApp/morphe-patches/commit/c2e78f14c4539479213623cb62587f02051ff421))
* **YouTube:** Add `Disable fullscreen gesture` patch ([#1724](https://github.com/MorpheApp/morphe-patches/issues/1724)) ([00ec593](https://github.com/MorpheApp/morphe-patches/commit/00ec59355d57c66f1189b779ab6806c7dcdd5fd3))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.24.0` ([eb47573](https://github.com/MorpheApp/morphe-patches/commit/eb4757326ef90cf6cf9865b12bd451603cb8a111))
* **Reddit:** Add experimental support for `2026.25.0` ([d1a8fcc](https://github.com/MorpheApp/morphe-patches/commit/d1a8fccf5dfbec51eec36d1f49cdf2a9fe50847d))
* **YouTube:** Add experimental support for `21.25.523` ([773b386](https://github.com/MorpheApp/morphe-patches/commit/773b386ba8d8bb0c831dee82880b0fcdbbd58b75))
* **YouTube Music:** Add support for `9.15.51` ([4ef13c7](https://github.com/MorpheApp/morphe-patches/commit/4ef13c7a0475c1b9f3aeac3613711df81806b461))
* **YouTube Music:** Add experimental support for `9.23.52` ([#1747](https://github.com/MorpheApp/morphe-patches/issues/1747)) ([31f0c4a](https://github.com/MorpheApp/morphe-patches/commit/31f0c4a503226ce7e6ffa58eb8f32af2f1f92f8c))
* **YouTube Music:** Add experimental support for `9.24.51` ([d30738a](https://github.com/MorpheApp/morphe-patches/commit/d30738a771222c8181623371a2f3b27b442dc478))

## [1.32.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0-dev.6...v1.32.0-dev.7) (2026-06-22)

### 🐛 Bug Fixes

* **YouTube - Change form factor:** Prevent app crash when using tablet mode in `onResume` state ([#1803](https://github.com/MorpheApp/morphe-patches/issues/1803)) ([4b0de25](https://github.com/MorpheApp/morphe-patches/commit/4b0de256e74f6eeb279ba164146fb9a01f2f8176))
* **YouTube - Hide layout components:** Resolve "Hide horizontal shelves" hiding other components ([#930](https://github.com/MorpheApp/morphe-patches/issues/930)) ([3c53157](https://github.com/MorpheApp/morphe-patches/commit/3c5315774896dc78ab5ad4d7b44380f9a21d3312))

## [1.32.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0-dev.5...v1.32.0-dev.6) (2026-06-22)

### 🐛 Bug Fixes

* **YouTube - Disable player popup panels:** Patch doesn't work in some circumstances ([6d6018c](https://github.com/MorpheApp/morphe-patches/commit/6d6018cf4a4ee6f79a283001aebe5b9d21767741))
* **YouTube - Spoof video streams:** Always allow details fetching ([6bc0eee](https://github.com/MorpheApp/morphe-patches/commit/6bc0eeefe02fa89a5d0669698ceb2fffc5bc51c7))

## [1.32.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0-dev.4...v1.32.0-dev.5) (2026-06-21)

### 🐛 Bug Fixes

* **YouTube - Navigation bar:** Prevent navigation bar animation when swiping to dismiss miniplayer ([#1800](https://github.com/MorpheApp/morphe-patches/issues/1800)) ([dcc4c0c](https://github.com/MorpheApp/morphe-patches/commit/dcc4c0c28ac600ca057c8720f5e71f8c712011ac))

### ✨ New Features

* **YouTube - Hide video action buttons:** Add "Restore old video action bar", "Hide Connect button", and "Hide More button" settings ([#1780](https://github.com/MorpheApp/morphe-patches/issues/1780)) ([0481899](https://github.com/MorpheApp/morphe-patches/commit/0481899aef98c45aa3cc88c9e3ff5cc8387fb4c5))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.25.0` ([d1a8fcc](https://github.com/MorpheApp/morphe-patches/commit/d1a8fccf5dfbec51eec36d1f49cdf2a9fe50847d))

## [1.32.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0-dev.3...v1.32.0-dev.4) (2026-06-20)

### ✨ New Features

* **YouTube - Open videos fullscreen:** Add landscape mode option ([#1770](https://github.com/MorpheApp/morphe-patches/issues/1770)) ([c2e78f1](https://github.com/MorpheApp/morphe-patches/commit/c2e78f14c4539479213623cb62587f02051ff421))

### 🚀 Updated App Support

* **YouTube:** Add experimental support for `21.25.523` ([773b386](https://github.com/MorpheApp/morphe-patches/commit/773b386ba8d8bb0c831dee82880b0fcdbbd58b75))

## [1.32.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0-dev.2...v1.32.0-dev.3) (2026-06-18)

### 🐛 Bug Fixes

* **YouTube Music - Track crossfade:** Dismissing the queue does not stop playback, fix songs incorrectly skipped ([#1773](https://github.com/MorpheApp/morphe-patches/issues/1773)) ([206b84e](https://github.com/MorpheApp/morphe-patches/commit/206b84eb6b20f873edee53253fd8460a993a30ab))

### 🚀 Updated App Support

* **YouTube Music:** Add experimental support for `9.24.51` ([d30738a](https://github.com/MorpheApp/morphe-patches/commit/d30738a771222c8181623371a2f3b27b442dc478))

## [1.32.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.32.0-dev.1...v1.32.0-dev.2) (2026-06-17)

### 🐛 Bug Fixes

* **YouTube - Bypass link redirects:** Resolve patch not working on community posts and video descriptions ([#1755](https://github.com/MorpheApp/morphe-patches/issues/1755)) ([c5c6376](https://github.com/MorpheApp/morphe-patches/commit/c5c6376546efdc90cbf89f7f5554880432161b22))
* **YouTube - Copy video link:** Wrong localization resource name ([dbddc1a](https://github.com/MorpheApp/morphe-patches/commit/dbddc1a55ea37e90bb7bfae221dd86a4a042b5fb))
* **YouTube - Override YouTube Music buttons:** Target app opens when clicking on 'YouTube Music' button inside explore menu ([#1707](https://github.com/MorpheApp/morphe-patches/issues/1707)) ([1f08df2](https://github.com/MorpheApp/morphe-patches/commit/1f08df24848bce582919e21b4b3e3f7bb6ebe5ab))
* **YouTube - Remove viewer discretion dialog:** In-app video downloader does not work for Premium users ([#1756](https://github.com/MorpheApp/morphe-patches/issues/1756)) ([3353302](https://github.com/MorpheApp/morphe-patches/commit/3353302753352388dbeef9454bb7902cdeb9eae0))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide comments by keywords" setting ([#1759](https://github.com/MorpheApp/morphe-patches/issues/1759)) ([d076c2b](https://github.com/MorpheApp/morphe-patches/commit/d076c2b3b7c561952fa2ae94be5d4de3119439af))
* **YouTube - Navigation bar:** Restore "Hide Cast button" setting in toolbar ([c27e63e](https://github.com/MorpheApp/morphe-patches/commit/c27e63ecb464b1e85e6ff97f13e21cc641a99fe7))
* **YouTube - Navigation bar:** Add "Hide Chat button" setting in toolbar ([32ee079](https://github.com/MorpheApp/morphe-patches/commit/32ee07910d9d80a8f3eac3b6f0dd0968a6c6c81d))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.24.0` ([eb47573](https://github.com/MorpheApp/morphe-patches/commit/eb4757326ef90cf6cf9865b12bd451603cb8a111))
* **YouTube Music:** Add support for `9.15.51` ([4ef13c7](https://github.com/MorpheApp/morphe-patches/commit/4ef13c7a0475c1b9f3aeac3613711df81806b461))

## [1.32.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0...v1.32.0-dev.1) (2026-06-15)

### 🐛 Bug Fixes

* **YouTube - Sanitize sharing links:** Live links are not sanitized ([ad91b37](https://github.com/MorpheApp/morphe-patches/commit/ad91b3759c42befbf4c32804cad5de5a48f14255))
* **YouTube - Reload video:** App exits after pressing back button ([#1740](https://github.com/MorpheApp/morphe-patches/issues/1740)) ([743bc76](https://github.com/MorpheApp/morphe-patches/commit/743bc76743c529aa8f6da4ecffd9643088c7fd97))

### ✨ New Features

* **YouTube:** Add `Disable fullscreen gesture` patch ([#1724](https://github.com/MorpheApp/morphe-patches/issues/1724)) ([00ec593](https://github.com/MorpheApp/morphe-patches/commit/00ec59355d57c66f1189b779ab6806c7dcdd5fd3))

### 🚀 Updated App Support

* **YouTube Music:** Add experimental support for `9.23.52` ([#1747](https://github.com/MorpheApp/morphe-patches/issues/1747)) ([31f0c4a](https://github.com/MorpheApp/morphe-patches/commit/31f0c4a503226ce7e6ffa58eb8f32af2f1f92f8c))

## [1.31.0](https://github.com/MorpheApp/morphe-patches/compare/v1.30.0...v1.31.0) (2026-06-13)

### 🐛 Bug Fixes

* **Reddit - Hide Ask button:** Update patch to support experimental app targets ([#1623](https://github.com/MorpheApp/morphe-patches/issues/1623)) ([7c4a9fb](https://github.com/MorpheApp/morphe-patches/commit/7c4a9fbfccd6c3bd80b06f67d49404cd1ddc7019))
* **Reddit - Hide Trending Today shelf:** Hide "Trending" header ([#1622](https://github.com/MorpheApp/morphe-patches/issues/1622)) ([00e1587](https://github.com/MorpheApp/morphe-patches/commit/00e1587633aea8cf5856dd89a951ced5be75da0a))
* **YouTube - Exit fullscreen mode:** Shorts regular player does not exit fullscreen mode ([2fb96a6](https://github.com/MorpheApp/morphe-patches/commit/2fb96a6918775d344c3208871e98a4c87805493c))
* **YouTube - Hide layout components:** Allow partial matches for "Enable Library / You bottom menu filter" setting ([59ea065](https://github.com/MorpheApp/morphe-patches/commit/59ea06565cfbd55a69be4494f42d6734a7c25668))
* **YouTube - Sanitize sharing links:** Exclude post links from shortening ([ce1dd46](https://github.com/MorpheApp/morphe-patches/commit/ce1dd46276e3cd830d8644774819b3395c355abb))
* **YouTube - Miniplayer:** Restore play/pause icons for minimal miniplayer on 21.17+ ([#1644](https://github.com/MorpheApp/morphe-patches/issues/1644)) ([9f4624f](https://github.com/MorpheApp/morphe-patches/commit/9f4624f64b701d7610c34d78a41f2288cf2fcc74))
* **YouTube - Open system share sheet:** Prevent PiP from being invoked ([#1635](https://github.com/MorpheApp/morphe-patches/issues/1635)) ([195e749](https://github.com/MorpheApp/morphe-patches/commit/195e749f5afd12e5bdb489a1115b2383651a0f51))
* **YouTube - Remove background playback restrictions:** Fix Shorts background playback not working ([#1629](https://github.com/MorpheApp/morphe-patches/issues/1629)) ([250f85e](https://github.com/MorpheApp/morphe-patches/commit/250f85e2a740197e6aaeff2acb24b2aad086cf51))
* **YouTube - Remove viewer discretion dialog:** Dialog cannot be dismissed ([#1710](https://github.com/MorpheApp/morphe-patches/issues/1710)) ([b962bd0](https://github.com/MorpheApp/morphe-patches/commit/b962bd0c485a5bea1b46bd55fee5943aefd797bd))
* **YouTube - Sanitize sharing links:** Resolve "Open system share sheet" not working ([#1599](https://github.com/MorpheApp/morphe-patches/issues/1599)) ([24c319a](https://github.com/MorpheApp/morphe-patches/commit/24c319a78593b8cd2def3cd98146dee1f2c09a0a))
* **YouTube - Theme:** Apply dark text color to "Material You" notification dot count ([#1615](https://github.com/MorpheApp/morphe-patches/issues/1615)) ([5289c6b](https://github.com/MorpheApp/morphe-patches/commit/5289c6b6a64df89fb85c3d6f563a24bd9c7f345e))
* **YouTube Music - Theme:** Apply "Material You" to notification dot ([#1657](https://github.com/MorpheApp/morphe-patches/issues/1657)) ([0355a17](https://github.com/MorpheApp/morphe-patches/commit/0355a17ac8ed19b161572ec2ea2b62d86bdb6803))

### ✨ New Features

* **YouTube - Change form factor:** Add "Enable tablet layout in player" setting ([#1695](https://github.com/MorpheApp/morphe-patches/issues/1695)) ([6376142](https://github.com/MorpheApp/morphe-patches/commit/6376142395508e76e64ce8188e4477bb181842ed))
* **YouTube - Hide ads:** Hide 'Includes paid promotion' label in miniplayer ([#1699](https://github.com/MorpheApp/morphe-patches/issues/1699)) ([c9ce526](https://github.com/MorpheApp/morphe-patches/commit/c9ce5269d1e205e84e6e4160712463b86966714d))
* **YouTube - Hide layout components:** Add "Hide 'Sync to video' button" setting ([#1694](https://github.com/MorpheApp/morphe-patches/issues/1694)) ([e870d1e](https://github.com/MorpheApp/morphe-patches/commit/e870d1eb3d008065e4700030e2a4c51f6cf4a8a4))
* **YouTube - Hide layout components:** Add "Hide Library / You bottom menu filter" setting ([#1583](https://github.com/MorpheApp/morphe-patches/issues/1583)) ([c093b60](https://github.com/MorpheApp/morphe-patches/commit/c093b60965e439e7a1b5b7cdf34717e6b159beb4))
* **YouTube - Hide layout components:** Add "Hide snackbar" setting ([#1658](https://github.com/MorpheApp/morphe-patches/issues/1658)) ([0e27c61](https://github.com/MorpheApp/morphe-patches/commit/0e27c611042dd75cf9c8c730df091eb440ea815b))
* **YouTube - Hide layout components:** Add path `&` syntax to custom filters ([#1693](https://github.com/MorpheApp/morphe-patches/issues/1693)) ([2ab0366](https://github.com/MorpheApp/morphe-patches/commit/2ab0366c8ae9dc6de3f361ed5a5d7ce446b4e8cd))
* **YouTube - Playback speed:** Add "Disable playback speed for music" setting ([#1691](https://github.com/MorpheApp/morphe-patches/issues/1691)) ([81c8f50](https://github.com/MorpheApp/morphe-patches/commit/81c8f50f408750e0d8434310d786c1e5fab59872))
* **YouTube - Save to watch later:** Add playlist queue manager ([#1648](https://github.com/MorpheApp/morphe-patches/issues/1648)) ([a5265ac](https://github.com/MorpheApp/morphe-patches/commit/a5265ac85cd758afc68172b6d073fc8e0ca4fba6))
* **YouTube - SponsorBlock:** Add channel whitelist ([#1661](https://github.com/MorpheApp/morphe-patches/issues/1661)) ([6d96cee](https://github.com/MorpheApp/morphe-patches/commit/6d96ceecb82c290b2d2545212bb7b80762fc98dc))
* **YouTube - SponsorBlock:** Add fade animation to the new segment panel ([#1577](https://github.com/MorpheApp/morphe-patches/issues/1577)) ([771f7fb](https://github.com/MorpheApp/morphe-patches/commit/771f7fbde1808c4129b01c0c95f0f0d08ff4ed4f))
* **YouTube Music - Settings:** Add icons to Morphe preference screen ([#1625](https://github.com/MorpheApp/morphe-patches/issues/1625)) ([d51c24f](https://github.com/MorpheApp/morphe-patches/commit/d51c24f846cfd0b028a1b2d31b69913ae1f7dadb))
* **YouTube Music:** Add `Enable swipe to dismiss miniplayer` patch ([#1420](https://github.com/MorpheApp/morphe-patches/issues/1420)) ([4795cc4](https://github.com/MorpheApp/morphe-patches/commit/4795cc4803cee0dc8aeefb0c0b6e968fff14698c))
* **YouTube Music - Track crossfade:** Add support for `9.x` ([#1582](https://github.com/MorpheApp/morphe-patches/issues/1582)) ([7150d06](https://github.com/MorpheApp/morphe-patches/commit/7150d06167b4a6ba699bbd48a4c5d1055d8d2789))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.22.0` ([3f443e2](https://github.com/MorpheApp/morphe-patches/commit/3f443e22ec39c3efdf98a562a0db376704212e4b))
* **Reddit:** Add experimental support for `2026.23.0` ([2d24e8d](https://github.com/MorpheApp/morphe-patches/commit/2d24e8d9ee109a83ac4ed774de5848aca38db952))
* **YouTube Music:** Add  support for `8.51.51` ([73d1d53](https://github.com/MorpheApp/morphe-patches/commit/73d1d53afbe9746bf3d54629553720eac4c89f19))
* **YouTube Music:** Add experimental support for `9.22.53` ([57f1bda](https://github.com/MorpheApp/morphe-patches/commit/57f1bdae0161748daeb6e3e4460b84938121b479))
* **YouTube:** Add experimental support for `21.22.164` ([b88b9ca](https://github.com/MorpheApp/morphe-patches/commit/b88b9cacc2af040fb2643a350d6b5caeaf19eea5))
* **YouTube:** Add experimental support for `21.23.480` ([9e0d0dd](https://github.com/MorpheApp/morphe-patches/commit/9e0d0dd66e73b4d361926637f2906237d1d0ad1d))
* **YouTube:** Add experimental support for `21.24.360` ([93f3acd](https://github.com/MorpheApp/morphe-patches/commit/93f3acd3af902edf1a5e063e4699d0471a0ef67d))


## [1.31.0-dev.13](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.12...v1.31.0-dev.13) (2026-06-12)

### 🐛 Bug Fixes

* **YouTube - Hide layout components:** Allow partial matches for "Enable Library / You bottom menu filter" setting ([59ea065](https://github.com/MorpheApp/morphe-patches/commit/59ea06565cfbd55a69be4494f42d6734a7c25668))
* **YouTube - Sanitize sharing links:** Exclude post links from shortening ([ce1dd46](https://github.com/MorpheApp/morphe-patches/commit/ce1dd46276e3cd830d8644774819b3395c355abb))

### ✨ New Features

* **YouTube - Hide ads:** Hide 'Includes paid promotion' label in miniplayer ([#1699](https://github.com/MorpheApp/morphe-patches/issues/1699)) ([c9ce526](https://github.com/MorpheApp/morphe-patches/commit/c9ce5269d1e205e84e6e4160712463b86966714d))

### 🚀 Updated App Support

* **YouTube:** Add experimental support for `21.24.360` ([93f3acd](https://github.com/MorpheApp/morphe-patches/commit/93f3acd3af902edf1a5e063e4699d0471a0ef67d))

## [1.31.0-dev.12](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.11...v1.31.0-dev.12) (2026-06-11)

### 🐛 Bug Fixes

* **YouTube - Save to watch later:** Fix black queue icon in legacy player ([7416c24](https://github.com/MorpheApp/morphe-patches/commit/7416c240ca9412b50fd7a26bb638f3ce5fd375cd))
* **YouTube - Remove viewer discretion dialog:** Dialog cannot be dismissed ([#1710](https://github.com/MorpheApp/morphe-patches/issues/1710)) ([b962bd0](https://github.com/MorpheApp/morphe-patches/commit/b962bd0c485a5bea1b46bd55fee5943aefd797bd))

### ✨ New Features

* **YouTube - Change form factor:** Add "Enable tablet layout in player" setting ([#1695](https://github.com/MorpheApp/morphe-patches/issues/1695)) ([6376142](https://github.com/MorpheApp/morphe-patches/commit/6376142395508e76e64ce8188e4477bb181842ed))

## [1.31.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.10...v1.31.0-dev.11) (2026-06-10)

### 🐛 Bug Fixes

* **YouTube - Save to watch later:** Handle saved playlist is no longer available ([#1701](https://github.com/MorpheApp/morphe-patches/issues/1701)) ([bf04fb7](https://github.com/MorpheApp/morphe-patches/commit/bf04fb7c1d5c7e570bb62162066470bbaf6c388a))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide 'Sync to video' button" setting ([#1694](https://github.com/MorpheApp/morphe-patches/issues/1694)) ([e870d1e](https://github.com/MorpheApp/morphe-patches/commit/e870d1eb3d008065e4700030e2a4c51f6cf4a8a4))

## [1.31.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.9...v1.31.0-dev.10) (2026-06-09)

### 🐛 Bug Fixes

* **YouTube - Theme:** Light color theme is not applied ([#1689](https://github.com/MorpheApp/morphe-patches/issues/1689)) ([5925a8e](https://github.com/MorpheApp/morphe-patches/commit/5925a8eb84c2c77903ffba5151730f61c38cd899))

### ✨ New Features

* **YouTube - Hide layout components:** Add path `&` syntax to custom filters ([#1693](https://github.com/MorpheApp/morphe-patches/issues/1693)) ([2ab0366](https://github.com/MorpheApp/morphe-patches/commit/2ab0366c8ae9dc6de3f361ed5a5d7ce446b4e8cd))
* **YouTube - Playback speed:** Add "Disable playback speed for music" setting ([#1691](https://github.com/MorpheApp/morphe-patches/issues/1691)) ([81c8f50](https://github.com/MorpheApp/morphe-patches/commit/81c8f50f408750e0d8434310d786c1e5fab59872))

## [1.31.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.8...v1.31.0-dev.9) (2026-06-08)

### 🐛 Bug Fixes

* **YouTube - Save to watch later:** Add `ScrollView` to playlist selection dialog ([#1692](https://github.com/MorpheApp/morphe-patches/issues/1692)) ([acf2326](https://github.com/MorpheApp/morphe-patches/commit/acf2326366c403bfca16c277bf2efd721d411bc2))

## [1.31.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.7...v1.31.0-dev.8) (2026-06-07)

### 🐛 Bug Fixes

* **YouTube - Save to watch later:** Error toast is shown if app is minimized then resumed before adding to playlist ([#1670](https://github.com/MorpheApp/morphe-patches/issues/1670)) ([18ac572](https://github.com/MorpheApp/morphe-patches/commit/18ac572c8c56f3eeac1849b31c0204bc76cdc901))
* **YouTube Music - Theme:** Apply "Material You" to notification dot ([#1657](https://github.com/MorpheApp/morphe-patches/issues/1657)) ([0355a17](https://github.com/MorpheApp/morphe-patches/commit/0355a17ac8ed19b161572ec2ea2b62d86bdb6803))

### ✨ New Features

* **YouTube - SponsorBlock:** Add channel whitelist ([#1661](https://github.com/MorpheApp/morphe-patches/issues/1661)) ([6d96cee](https://github.com/MorpheApp/morphe-patches/commit/6d96ceecb82c290b2d2545212bb7b80762fc98dc))
* **YouTube Music:** Add `Enable swipe to dismiss miniplayer` patch ([#1420](https://github.com/MorpheApp/morphe-patches/issues/1420)) ([4795cc4](https://github.com/MorpheApp/morphe-patches/commit/4795cc4803cee0dc8aeefb0c0b6e968fff14698c))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.23.0` ([2d24e8d](https://github.com/MorpheApp/morphe-patches/commit/2d24e8d9ee109a83ac4ed774de5848aca38db952))

## [1.31.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.6...v1.31.0-dev.7) (2026-06-06)

### 🐛 Bug Fixes

* **YouTube - Exit fullscreen mode:** Shorts regular player does not exit fullscreen mode ([2fb96a6](https://github.com/MorpheApp/morphe-patches/commit/2fb96a6918775d344c3208871e98a4c87805493c))
* **YouTube - Save to watch later:** Correctly save queue into selected playlist ([a8d1732](https://github.com/MorpheApp/morphe-patches/commit/a8d1732d41d4e7f007e8d1d6f95dc67d8ccdb6b5))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide snackbar" setting ([#1658](https://github.com/MorpheApp/morphe-patches/issues/1658)) ([0e27c61](https://github.com/MorpheApp/morphe-patches/commit/0e27c611042dd75cf9c8c730df091eb440ea815b))
* **YouTube - Save to watch later:** Add option to swap Save and Queue actions ([#1664](https://github.com/MorpheApp/morphe-patches/issues/1664)) ([db7cc5b](https://github.com/MorpheApp/morphe-patches/commit/db7cc5becce5a4893faef9f95deb4134114b213c))

## [1.31.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.5...v1.31.0-dev.6) (2026-06-05)

### ✨ New Features

* **YouTube - Save to watch later:** Add playlist queue manager ([#1648](https://github.com/MorpheApp/morphe-patches/issues/1648)) ([a5265ac](https://github.com/MorpheApp/morphe-patches/commit/a5265ac85cd758afc68172b6d073fc8e0ca4fba6))

### 🚀 Updated App Support

* **YouTube:** Add experimental support for `21.23.480` ([9e0d0dd](https://github.com/MorpheApp/morphe-patches/commit/9e0d0dd66e73b4d361926637f2906237d1d0ad1d))

## [1.31.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.4...v1.31.0-dev.5) (2026-06-04)

### 🐛 Bug Fixes

* **YouTube - Miniplayer:** Restore play/pause icons for minimal miniplayer on `21.17+` ([#1644](https://github.com/MorpheApp/morphe-patches/issues/1644)) ([9f4624f](https://github.com/MorpheApp/morphe-patches/commit/9f4624f64b701d7610c34d78a41f2288cf2fcc74))
* **YouTube - Sanitize sharing links:** Fix "Copy video URL button" having tracking query parameters ([23fa3b0](https://github.com/MorpheApp/morphe-patches/commit/23fa3b01f56e282a1dc6d171e2b936bd5020d27e))

## [1.31.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.3...v1.31.0-dev.4) (2026-06-03)

### 🐛 Bug Fixes

* **YouTube - Hide layout components:** "Hide Library / You tab bottom menu" doesn't work on some versions ([1551524](https://github.com/MorpheApp/morphe-patches/commit/1551524475f0f86ad05cf5689129a6bf6a0cc08f))
* **YouTube - Open system share sheet:** Prevent PiP from being invoked ([#1635](https://github.com/MorpheApp/morphe-patches/issues/1635)) ([195e749](https://github.com/MorpheApp/morphe-patches/commit/195e749f5afd12e5bdb489a1115b2383651a0f51))
* **YouTube - Theme:** Apply dark text color to "Material You" notification dot count ([#1615](https://github.com/MorpheApp/morphe-patches/issues/1615)) ([5289c6b](https://github.com/MorpheApp/morphe-patches/commit/5289c6b6a64df89fb85c3d6f563a24bd9c7f345e))

### ✨ New Features

* **YouTube Music - Settings:** Add icons to Morphe preference screen ([#1625](https://github.com/MorpheApp/morphe-patches/issues/1625)) ([d51c24f](https://github.com/MorpheApp/morphe-patches/commit/d51c24f846cfd0b028a1b2d31b69913ae1f7dadb))

### 🚀 Updated App Support

* **YouTube Music:** Add  support for `8.51.51` ([73d1d53](https://github.com/MorpheApp/morphe-patches/commit/73d1d53afbe9746bf3d54629553720eac4c89f19))
* **YouTube Music:** Add experimental support for `9.22.53` ([57f1bda](https://github.com/MorpheApp/morphe-patches/commit/57f1bdae0161748daeb6e3e4460b84938121b479))

## [1.31.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.2...v1.31.0-dev.3) (2026-06-02)

### 🐛 Bug Fixes

* **Reddit - Hide Ask button:** Update patch to support experimental app targets ([#1623](https://github.com/MorpheApp/morphe-patches/issues/1623)) ([7c4a9fb](https://github.com/MorpheApp/morphe-patches/commit/7c4a9fbfccd6c3bd80b06f67d49404cd1ddc7019))
* **Reddit - Hide Trending Today shelf:** Hide "Trending" header ([#1622](https://github.com/MorpheApp/morphe-patches/issues/1622)) ([00e1587](https://github.com/MorpheApp/morphe-patches/commit/00e1587633aea8cf5856dd89a951ced5be75da0a))
* **YouTube - Remove background playback restrictions:** Fix Shorts background playback not working ([#1629](https://github.com/MorpheApp/morphe-patches/issues/1629)) ([250f85e](https://github.com/MorpheApp/morphe-patches/commit/250f85e2a740197e6aaeff2acb24b2aad086cf51))
* **YouTube - Sanitize sharing links:** Resolve "Open system share sheet" not working ([#1599](https://github.com/MorpheApp/morphe-patches/issues/1599)) ([24c319a](https://github.com/MorpheApp/morphe-patches/commit/24c319a78593b8cd2def3cd98146dee1f2c09a0a))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide Library / You bottom menu filter" setting ([#1583](https://github.com/MorpheApp/morphe-patches/issues/1583)) ([c093b60](https://github.com/MorpheApp/morphe-patches/commit/c093b60965e439e7a1b5b7cdf34717e6b159beb4))
* **YouTube Music - Track crossfade:** Add support for `9.x` ([#1582](https://github.com/MorpheApp/morphe-patches/issues/1582)) ([7150d06](https://github.com/MorpheApp/morphe-patches/commit/7150d06167b4a6ba699bbd48a4c5d1055d8d2789))

## [1.31.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.31.0-dev.1...v1.31.0-dev.2) (2026-05-31)

### 🐛 Bug Fixes

* **Reddit:** Resolve rare app startup crash when "Show view count" is enabled ([6cd6af1](https://github.com/MorpheApp/morphe-patches/commit/6cd6af147ec56eb4e9ba07b5b4ea6d54271669ae))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.22.0` ([3f443e2](https://github.com/MorpheApp/morphe-patches/commit/3f443e22ec39c3efdf98a562a0db376704212e4b))

## [1.31.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.30.0...v1.31.0-dev.1) (2026-05-30)

### 🐛 Bug Fixes

* **YouTube - Loop video:** Correctly reset range if preferences are not saved ([a328ed8](https://github.com/MorpheApp/morphe-patches/commit/a328ed872f64a9fd6f3d4100d573dc3ca8a8ff2e))
* **YouTube - Loop video:** Reset loop range before set a new one ([7aa767a](https://github.com/MorpheApp/morphe-patches/commit/7aa767ab256e0ef9da9238ffc68f4ec8263f416f))

### ✨ New Features

* **YouTube - SponsorBlock:** Add fade animation to the new segment panel ([#1577](https://github.com/MorpheApp/morphe-patches/issues/1577)) ([771f7fb](https://github.com/MorpheApp/morphe-patches/commit/771f7fbde1808c4129b01c0c95f0f0d08ff4ed4f))

### 🚀 Updated App Support

* **YouTube:** Add experimental support for `21.22.164` ([b88b9ca](https://github.com/MorpheApp/morphe-patches/commit/b88b9cacc2af040fb2643a350d6b5caeaf19eea5))

## [1.30.0](https://github.com/MorpheApp/morphe-patches/compare/v1.29.0...v1.30.0) (2026-05-29)

### 🐛 Bug Fixes

* **YouTube - Disable DRC audio:** Resolve patch not working on `21.19+` ([#1561](https://github.com/MorpheApp/morphe-patches/issues/1561)) ([8608270](https://github.com/MorpheApp/morphe-patches/commit/8608270c1b7a517b1f863ee5f3471153b2ad3574))
* **YouTube - Hide ads:** Fix player crash ([d67d1e1](https://github.com/MorpheApp/morphe-patches/commit/d67d1e1fddcada5ca5310294b6be03a32ba636d3))
* **YouTube - Hide layout components:** Resolve "Hide mix playlists" leaving empty space ([#1526](https://github.com/MorpheApp/morphe-patches/issues/1526)) ([d2eabee](https://github.com/MorpheApp/morphe-patches/commit/d2eabeec1728bd0666e6ec611e5dbeb4062b8960))
* **YouTube - Hide Shorts components:** Hide new type of Shorts in search results ([893f2bd](https://github.com/MorpheApp/morphe-patches/commit/893f2bd68adffb5f9e8b82b4781670c16e61ff2b))
* **YouTube - Open channel of live avatar:** Deprecated old `WEB_REMIX` client in favor of `ANDROID_REELS` ([#1519](https://github.com/MorpheApp/morphe-patches/issues/1519)) ([e45b769](https://github.com/MorpheApp/morphe-patches/commit/e45b7693e43f8182c84b2b5093c37c2b381cbb2d))
* **YouTube - Open channel of live avatar:** Improved check to exclude the live avatar in the channel header ([#1544](https://github.com/MorpheApp/morphe-patches/issues/1544)) ([2b4d5b7](https://github.com/MorpheApp/morphe-patches/commit/2b4d5b79416b9da4408e3d6de2eac463335b1fef))
* **YouTube - Hide player flyout menu components:** Hide the divider in flyout menu ([#1576](https://github.com/MorpheApp/morphe-patches/issues/1576)) ([d2c53d5](https://github.com/MorpheApp/morphe-patches/commit/d2c53d508198162a1c69dc011e167dea1b233406))
* **YouTube - Reload video:** Allow reloading video that has not finished starting ([8318079](https://github.com/MorpheApp/morphe-patches/commit/8318079f1b7ec256ba7a738c0cb247ea78567135))
* **YouTube - Sanitize sharing links:** Sanitize system share sheet ([#1536](https://github.com/MorpheApp/morphe-patches/issues/1536)) ([c0338d8](https://github.com/MorpheApp/morphe-patches/commit/c0338d8bb6802a4b160100abec625a03d7b49f4e))
* **YouTube - Sanitize sharing links:** Sanitize invite URLs before shortening and added playlist links exclusion ([#1550](https://github.com/MorpheApp/morphe-patches/issues/1550)) ([86e53de](https://github.com/MorpheApp/morphe-patches/commit/86e53de6178bfcb65c7f6b5794df3c68da84af86))
* **YouTube - Theme:** Apply "Material You" to notification dot ([#1489](https://github.com/MorpheApp/morphe-patches/issues/1489)) ([bfc1a02](https://github.com/MorpheApp/morphe-patches/commit/bfc1a020cd5b06ffa08d3c311b751fbd854a0be6))

### ✨ New Features

* **Custom branding:** Add icon previews to branding selectors and new play black icon ([#1522](https://github.com/MorpheApp/morphe-patches/issues/1522)) ([adc4ec2](https://github.com/MorpheApp/morphe-patches/commit/adc4ec2db07ce0db3ab449d72d979bc52de1286c))
* **Settings:** Add haptic feedback to settings toggles ([#1575](https://github.com/MorpheApp/morphe-patches/issues/1575)) ([4a7deb8](https://github.com/MorpheApp/morphe-patches/commit/4a7deb8fb90eb3736ebb45eddd42a8918b9c592a))
* **YouTube - Hide layout components:** Add "Hide Featured channels section" setting ([#1567](https://github.com/MorpheApp/morphe-patches/issues/1567)) ([47cd714](https://github.com/MorpheApp/morphe-patches/commit/47cd7148685966e602e91687ea5297603d13d431))
* **YouTube - Hide video action buttons:** Add quick actions top margin setting ([#1574](https://github.com/MorpheApp/morphe-patches/issues/1574)) ([72424b0](https://github.com/MorpheApp/morphe-patches/commit/72424b0d810097aba43e0a26960439b8f46e63f6))
* **YouTube - Loop video:** Add loop range via long-press on loop button ([#1557](https://github.com/MorpheApp/morphe-patches/issues/1557)) ([793424c](https://github.com/MorpheApp/morphe-patches/commit/793424c911157c04d67ec62a8442767d32ba95e7))
* **YouTube - Loop video:** Add a toggle to prevent preference saving ([3ebb5eb](https://github.com/MorpheApp/morphe-patches/commit/3ebb5eb87f3d6a12e73d26f9504437c89f18a85f))
* **YouTube - SponsorBlock:** Add draggable new segment panel with persistent position ([#1560](https://github.com/MorpheApp/morphe-patches/issues/1560)) ([d4715d0](https://github.com/MorpheApp/morphe-patches/commit/d4715d015c7a6b918a66d7890f023a893b8a7b91))
* **YouTube - Swipe controls:** Add configurable zones and speed gesture ([#1527](https://github.com/MorpheApp/morphe-patches/issues/1527)) ([92e6d06](https://github.com/MorpheApp/morphe-patches/commit/92e6d065e254f4c31f17baf0cce4dc45c0c43362))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.21.0` ([78d67e0](https://github.com/MorpheApp/morphe-patches/commit/78d67e0c8668b1f8f3bd7e700a9898e319e9403d))
* **Reddit:** Add support for `2026.14.0` ([2376e91](https://github.com/MorpheApp/morphe-patches/commit/2376e91793f1c3374da937f39bd71d116ff53c8a))
* **YouTube Music:** Add experimental support for `9.20.52` ([c34abe0](https://github.com/MorpheApp/morphe-patches/commit/c34abe0b20692a3ff4780fde68700625c5494d2a))
* **YouTube Music:** Add experimental support for `9.21.51` ([3972b0c](https://github.com/MorpheApp/morphe-patches/commit/3972b0c4eb2ac11ff67c28f3d7b4c854494ad2c7))
* **YouTube:** Add experimental support for `21.21.80` ([65c35f1](https://github.com/MorpheApp/morphe-patches/commit/65c35f10536e5e1d1153fd0d3e96d6776683d245))
* **YouTube:** Add support for `20.51.39` ([d8033a8](https://github.com/MorpheApp/morphe-patches/commit/d8033a817e57fce5cc19f4f46694a099dda4606d))

## [1.30.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.30.0-dev.5...v1.30.0-dev.6) (2026-05-28)

### 🐛 Bug Fixes

* **YouTube - Hide Shorts components:** Hide new type of Shorts in search results ([893f2bd](https://github.com/MorpheApp/morphe-patches/commit/893f2bd68adffb5f9e8b82b4781670c16e61ff2b))
* **YouTube - Hide player flyout menu components:** Hide the divider in flyout menu ([#1576](https://github.com/MorpheApp/morphe-patches/issues/1576)) ([d2c53d5](https://github.com/MorpheApp/morphe-patches/commit/d2c53d508198162a1c69dc011e167dea1b233406))
* **YouTube - Theme:** Apply "Material You" to notification dot ([#1489](https://github.com/MorpheApp/morphe-patches/issues/1489)) ([bfc1a02](https://github.com/MorpheApp/morphe-patches/commit/bfc1a020cd5b06ffa08d3c311b751fbd854a0be6))

### ✨ New Features

* **Settings:** Add haptic feedback to settings toggles ([#1575](https://github.com/MorpheApp/morphe-patches/issues/1575)) ([4a7deb8](https://github.com/MorpheApp/morphe-patches/commit/4a7deb8fb90eb3736ebb45eddd42a8918b9c592a))
* **YouTube - Hide video action buttons:** Add quick actions top margin setting ([#1574](https://github.com/MorpheApp/morphe-patches/issues/1574)) ([72424b0](https://github.com/MorpheApp/morphe-patches/commit/72424b0d810097aba43e0a26960439b8f46e63f6))
* **YouTube - Loop video:** Add loop range via long-press on loop button ([#1557](https://github.com/MorpheApp/morphe-patches/issues/1557)) ([793424c](https://github.com/MorpheApp/morphe-patches/commit/793424c911157c04d67ec62a8442767d32ba95e7))
* **YouTube - Loop video:** Add a toggle to prevent preference saving ([3ebb5eb](https://github.com/MorpheApp/morphe-patches/commit/3ebb5eb87f3d6a12e73d26f9504437c89f18a85f))
* **YouTube - SponsorBlock:** Add draggable new segment panel with persistent position ([#1560](https://github.com/MorpheApp/morphe-patches/issues/1560)) ([d4715d0](https://github.com/MorpheApp/morphe-patches/commit/d4715d015c7a6b918a66d7890f023a893b8a7b91))

### 🚀 Updated App Support

* **YouTube Music:** Add experimental support for `9.21.51` ([3972b0c](https://github.com/MorpheApp/morphe-patches/commit/3972b0c4eb2ac11ff67c28f3d7b4c854494ad2c7))

## [1.30.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.30.0-dev.4...v1.30.0-dev.5) (2026-05-27)

### 🐛 Bug Fixes

* **YouTube - Disable DRC audio:** Resolve patch not working for `21.19+` ([#1561](https://github.com/MorpheApp/morphe-patches/issues/1561)) ([8608270](https://github.com/MorpheApp/morphe-patches/commit/8608270c1b7a517b1f863ee5f3471153b2ad3574))

### ✨ New Features

* **YouTube - Hide layout components:** Add "Hide Featured channels section" setting ([#1567](https://github.com/MorpheApp/morphe-patches/issues/1567)) ([47cd714](https://github.com/MorpheApp/morphe-patches/commit/47cd7148685966e602e91687ea5297603d13d431))

## [1.30.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.30.0-dev.3...v1.30.0-dev.4) (2026-05-26)

### ✨ New Features

* **YouTube - Swipe controls:** Add configurable zones and speed gesture ([#1527](https://github.com/MorpheApp/morphe-patches/issues/1527)) ([92e6d06](https://github.com/MorpheApp/morphe-patches/commit/92e6d065e254f4c31f17baf0cce4dc45c0c43362))

### 🚀 Updated App Support

* **YouTube:** Add support for `20.51.39` ([d8033a8](https://github.com/MorpheApp/morphe-patches/commit/d8033a817e57fce5cc19f4f46694a099dda4606d))

## [1.30.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.30.0-dev.2...v1.30.0-dev.3) (2026-05-25)

### 🐛 Bug Fixes

* **YouTube - Open channel of live avatar:** Improved check to exclude the live avatar in the channel header ([#1544](https://github.com/MorpheApp/morphe-patches/issues/1544)) ([2b4d5b7](https://github.com/MorpheApp/morphe-patches/commit/2b4d5b79416b9da4408e3d6de2eac463335b1fef))
* **YouTube - Sanitize sharing links:** Sanitize invite URLs before shortening and added playlist links exclusion ([#1550](https://github.com/MorpheApp/morphe-patches/issues/1550)) ([86e53de](https://github.com/MorpheApp/morphe-patches/commit/86e53de6178bfcb65c7f6b5794df3c68da84af86))

## [1.30.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.30.0-dev.1...v1.30.0-dev.2) (2026-05-24)

### 🐛 Bug Fixes

* **YouTube - Reload video:** Allow reloading video that has not finished starting ([8318079](https://github.com/MorpheApp/morphe-patches/commit/8318079f1b7ec256ba7a738c0cb247ea78567135))
* **YouTube - Sanitize sharing links:** Sanitize system share sheet ([#1536](https://github.com/MorpheApp/morphe-patches/issues/1536)) ([c0338d8](https://github.com/MorpheApp/morphe-patches/commit/c0338d8bb6802a4b160100abec625a03d7b49f4e))

## [1.30.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.29.1-dev.2...v1.30.0-dev.1) (2026-05-23)

### ✨ New Features

* **Custom branding:** Add icon previews to branding selectors and new play black icon ([#1522](https://github.com/MorpheApp/morphe-patches/issues/1522)) ([adc4ec2](https://github.com/MorpheApp/morphe-patches/commit/adc4ec2db07ce0db3ab449d72d979bc52de1286c))

### 🚀 Updated App Support

* **Reddit:** Add experimental support for `2026.21.0` ([78d67e0](https://github.com/MorpheApp/morphe-patches/commit/78d67e0c8668b1f8f3bd7e700a9898e319e9403d))
* **Reddit:** Add support for `2026.14.0` ([2376e91](https://github.com/MorpheApp/morphe-patches/commit/2376e91793f1c3374da937f39bd71d116ff53c8a))
* **YouTube Music:** Add experimental support for `9.20.52` ([c34abe0](https://github.com/MorpheApp/morphe-patches/commit/c34abe0b20692a3ff4780fde68700625c5494d2a))

## [1.29.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.29.1-dev.1...v1.29.1-dev.2) (2026-05-22)

### 🐛 Bug Fixes

* **YouTube - Hide ads:** Fix player crash ([d67d1e1](https://github.com/MorpheApp/morphe-patches/commit/d67d1e1fddcada5ca5310294b6be03a32ba636d3))

## [1.29.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.29.0...v1.29.1-dev.1) (2026-05-22)

### 🐛 Bug Fixes

* **YouTube - Hide layout components:** Resolve "Hide mix playlists" leaving empty space ([#1526](https://github.com/MorpheApp/morphe-patches/issues/1526)) ([d2eabee](https://github.com/MorpheApp/morphe-patches/commit/d2eabeec1728bd0666e6ec611e5dbeb4062b8960))
* **YouTube - Open channel of live avatar:** Deprecated old `WEB_REMIX` client in favor of `ANDROID_REELS` ([#1519](https://github.com/MorpheApp/morphe-patches/issues/1519)) ([e45b769](https://github.com/MorpheApp/morphe-patches/commit/e45b7693e43f8182c84b2b5093c37c2b381cbb2d))

### 🚀 Updated App Support

* **YouTube:** Add experimental support for `21.21.80` ([65c35f1](https://github.com/MorpheApp/morphe-patches/commit/65c35f10536e5e1d1153fd0d3e96d6776683d245))

## [1.29.0](https://github.com/MorpheApp/morphe-patches/compare/v1.28.0...v1.29.0) (2026-05-21)

### 🐛 Bug Fixes

* **Reddit:** Spoof installation source ([e62b180](https://github.com/MorpheApp/morphe-patches/commit/e62b1803a34ffc3045b93ee5669e0d3441d0a8c1))
* **YouTube - Open channel of live avatar:** Deprecate match with translations ([#1511](https://github.com/MorpheApp/morphe-patches/issues/1511)) ([4264071](https://github.com/MorpheApp/morphe-patches/commit/42640710c8be1b173984a0d052891006960bcbcc))
* **YouTube - Open channel of live avatar:** Fixed null exception after tapping on some Shorts player buttons ([#1497](https://github.com/MorpheApp/morphe-patches/issues/1497)) ([6ec2fd4](https://github.com/MorpheApp/morphe-patches/commit/6ec2fd4f8d9e77b8adffb6b91da5ebb61413f58c))
* **YouTube - PlayerOverlayButton:** Improved containers handling ([1580f02](https://github.com/MorpheApp/morphe-patches/commit/1580f02a0dfe0a75f9f636229bc6d77cab4b3699))
* **YouTube - Remove background playback restrictions:** Disable client flag that interferes with player overlay buttons ([f24cae5](https://github.com/MorpheApp/morphe-patches/commit/f24cae55007dd00bc061dadcaeb2c2f74baf3816))

### ✨ New Features

* **Reddit:** Add experimental support for `2026.20.0` ([0e6b305](https://github.com/MorpheApp/morphe-patches/commit/0e6b3054b011ac89d99dc7088dc983a1f95e99a4))

## [1.29.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.29.0-dev.2...v1.29.0-dev.3) (2026-05-20)

### 🐛 Bug Fixes

* **YouTube - Open channel of live avatar:** Deprecate match with translations ([#1511](https://github.com/MorpheApp/morphe-patches/issues/1511)) ([4264071](https://github.com/MorpheApp/morphe-patches/commit/42640710c8be1b173984a0d052891006960bcbcc))

## [1.29.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.29.0-dev.1...v1.29.0-dev.2) (2026-05-19)

### 🐛 Bug Fixes

* **YouTube - PlayerOverlayButton:** Improved containers handling ([1580f02](https://github.com/MorpheApp/morphe-patches/commit/1580f02a0dfe0a75f9f636229bc6d77cab4b3699))
* **YouTube - Remove background playback restrictions:** Disable client flag that interferes with player overlay buttons ([f24cae5](https://github.com/MorpheApp/morphe-patches/commit/f24cae55007dd00bc061dadcaeb2c2f74baf3816))

## [1.29.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.28.0...v1.29.0-dev.1) (2026-05-18)

### 🐛 Bug Fixes

* **Reddit:** Spoof installation source ([e62b180](https://github.com/MorpheApp/morphe-patches/commit/e62b1803a34ffc3045b93ee5669e0d3441d0a8c1))
* **YouTube - Open channel of live avatar:** Fixed null exception after tapping on some Shorts player buttons ([#1497](https://github.com/MorpheApp/morphe-patches/issues/1497)) ([6ec2fd4](https://github.com/MorpheApp/morphe-patches/commit/6ec2fd4f8d9e77b8adffb6b91da5ebb61413f58c))

### ✨ New Features

* **Reddit:** Add experimental support for `2026.20.0` ([0e6b305](https://github.com/MorpheApp/morphe-patches/commit/0e6b3054b011ac89d99dc7088dc983a1f95e99a4))

## [1.28.0](https://github.com/MorpheApp/morphe-patches/compare/v1.27.0...v1.28.0) (2026-05-17)

### 🐛 Bug Fixes

* Update Crowdin translations ([b652e67](https://github.com/MorpheApp/morphe-patches/commit/b652e6731eebe67f1c20cb0e0d83ace9a94167cb))
* **YouTube - Hide ads:** Hide new type of product sticker ([#1484](https://github.com/MorpheApp/morphe-patches/issues/1484)) ([aa3637d](https://github.com/MorpheApp/morphe-patches/commit/aa3637ddb2470ad5147ebb1ccad10311d366571a))
* **YouTube - Hide layout components:** Hide new type of community post ([#1476](https://github.com/MorpheApp/morphe-patches/issues/1476)) ([50085da](https://github.com/MorpheApp/morphe-patches/commit/50085dab0ceed84778d40b40150f7a16c5ea18ec))
* **YouTube - Open channel of live avatar:** Add missing resources ([#1463](https://github.com/MorpheApp/morphe-patches/issues/1463)) ([0870e3c](https://github.com/MorpheApp/morphe-patches/commit/0870e3c3dc8a3ec3bd42af174bb7d63106658d29))
* **YouTube - Open channel of live avatar:** Fixed remaining translations ([#1491](https://github.com/MorpheApp/morphe-patches/issues/1491)) ([d3433ab](https://github.com/MorpheApp/morphe-patches/commit/d3433ab92a8bad7af00c795de44f943136dd2987))
* **YouTube - Open channel of live avatar:** Patch doesn't work for Arabic/Korean localizations ([ff16083](https://github.com/MorpheApp/morphe-patches/commit/ff160839fdcc03e24c9eff3eec4499b58febc26a))
* **YouTube - Open channel of live avatar:** patch doesn't work for Vietnamese localization ([#1479](https://github.com/MorpheApp/morphe-patches/issues/1479)) ([2a5979e](https://github.com/MorpheApp/morphe-patches/commit/2a5979ee3184ea4ca7a045c61363c853e6cbea15))
* **YouTube - Open channel of live avatar:** Patch doesn't work in video player ([3f03a18](https://github.com/MorpheApp/morphe-patches/commit/3f03a184d9997f844a934b76a05fd6b228d38529))
* **YouTube - Open channel of live avatar:** Patch may not work when app is using non English language ([#1459](https://github.com/MorpheApp/morphe-patches/issues/1459)) ([34d55c2](https://github.com/MorpheApp/morphe-patches/commit/34d55c27d9f915cc39bed37af5a9a4c1e28a3e0f))
* **YouTube - PlayerOverlayButton:** Reset `skipFirstCall` for next `updateRef` attempts ([9d12346](https://github.com/MorpheApp/morphe-patches/commit/9d12346c17a11fd9c4452ba14ee5e96312b760e8))
* **YouTube - PlayerOverlayButton:** Set `videoHeading` container reference only in upper bar constructor to avoid race condition ([#1478](https://github.com/MorpheApp/morphe-patches/issues/1478)) ([93c6300](https://github.com/MorpheApp/morphe-patches/commit/93c6300450a5fafbb815a79d112707880109f358))
* **YouTube - PlayerOverlayButton:** Video heading margin not applied when no bottom buttons present ([#1472](https://github.com/MorpheApp/morphe-patches/issues/1472)) ([12bee40](https://github.com/MorpheApp/morphe-patches/commit/12bee40312e040c7bccd17f9d1af2e56d3214972))

### ✨ New Features

* Add miscellaneous link handling preference to help change "Open with" links ([#1389](https://github.com/MorpheApp/morphe-patches/issues/1389)) ([c364ced](https://github.com/MorpheApp/morphe-patches/commit/c364ced39ccbe5a4183cb07c2e70d4a666b08c46))
* Simplify settings summary text ([#1366](https://github.com/MorpheApp/morphe-patches/issues/1366)) ([67576a8](https://github.com/MorpheApp/morphe-patches/commit/67576a8ffd2c81326acc178454148bbca315a855))
* **YouTube Music:** Add experimental support for `9.19.50` ([a2c0681](https://github.com/MorpheApp/morphe-patches/commit/a2c06815d0c3345bfc29aac0f7d555ac03f1f543))
* **YouTube:** Add experimental support for `21.20.400` ([a4d1412](https://github.com/MorpheApp/morphe-patches/commit/a4d1412849e578189adb04130f8559905cfe8f8f))

## [1.28.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.28.0-dev.3...v1.28.0-dev.4) (2026-05-16)

### 🐛 Bug Fixes

* **YouTube - Hide ads:** Hide new type of product sticker ([#1484](https://github.com/MorpheApp/morphe-patches/issues/1484)) ([aa3637d](https://github.com/MorpheApp/morphe-patches/commit/aa3637ddb2470ad5147ebb1ccad10311d366571a))
* **YouTube - PlayerOverlayButton:** Reset `skipFirstCall` for next `updateRef` attempts ([9d12346](https://github.com/MorpheApp/morphe-patches/commit/9d12346c17a11fd9c4452ba14ee5e96312b760e8))

# [1.28.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.28.0-dev.2...v1.28.0-dev.3) (2026-05-15)


### Bug Fixes

* **YouTube - Hide layout components:** Hide new type of community post ([#1476](https://github.com/MorpheApp/morphe-patches/issues/1476)) ([50085da](https://github.com/MorpheApp/morphe-patches/commit/50085dab0ceed84778d40b40150f7a16c5ea18ec))
* **YouTube - Open channel of live avatar:** Patch doesn't work for Arabic/Korean localizations ([ff16083](https://github.com/MorpheApp/morphe-patches/commit/ff160839fdcc03e24c9eff3eec4499b58febc26a))
* **YouTube - Open channel of live avatar:** Patch doesn't work for Vietnamese localization ([#1479](https://github.com/MorpheApp/morphe-patches/issues/1479)) ([2a5979e](https://github.com/MorpheApp/morphe-patches/commit/2a5979ee3184ea4ca7a045c61363c853e6cbea15))
* **YouTube - PlayerOverlayButton:** Set `videoHeading` container reference only in upper bar constructor to avoid race condition ([#1478](https://github.com/MorpheApp/morphe-patches/issues/1478)) ([93c6300](https://github.com/MorpheApp/morphe-patches/commit/93c6300450a5fafbb815a79d112707880109f358))
* **YouTube - PlayerOverlayButton:** Video heading margin not applied when no bottom buttons present ([#1472](https://github.com/MorpheApp/morphe-patches/issues/1472)) ([12bee40](https://github.com/MorpheApp/morphe-patches/commit/12bee40312e040c7bccd17f9d1af2e56d3214972))


### Features

* **YouTube:** Add experimental support for `21.20.400` ([a4d1412](https://github.com/MorpheApp/morphe-patches/commit/a4d1412849e578189adb04130f8559905cfe8f8f))

# [1.28.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.28.0-dev.1...v1.28.0-dev.2) (2026-05-14)


### Bug Fixes

* Update Crowdin translations ([b652e67](https://github.com/MorpheApp/morphe-patches/commit/b652e6731eebe67f1c20cb0e0d83ace9a94167cb))

# [1.28.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.27.1-dev.2...v1.28.0-dev.1) (2026-05-14)


### Features

* Add miscellaneous link handling preference to help change "Open with" links ([#1389](https://github.com/MorpheApp/morphe-patches/issues/1389)) ([c364ced](https://github.com/MorpheApp/morphe-patches/commit/c364ced39ccbe5a4183cb07c2e70d4a666b08c46))
* Simplify settings summary text ([#1366](https://github.com/MorpheApp/morphe-patches/issues/1366)) ([67576a8](https://github.com/MorpheApp/morphe-patches/commit/67576a8ffd2c81326acc178454148bbca315a855))
* **YouTube Music:** Add experimental support for `9.19.50` ([a2c0681](https://github.com/MorpheApp/morphe-patches/commit/a2c06815d0c3345bfc29aac0f7d555ac03f1f543))

## [1.27.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.27.1-dev.1...v1.27.1-dev.2) (2026-05-13)


### Bug Fixes

* **YouTube - Open channel of live avatar:** Add missing resources ([#1463](https://github.com/MorpheApp/morphe-patches/issues/1463)) ([0870e3c](https://github.com/MorpheApp/morphe-patches/commit/0870e3c3dc8a3ec3bd42af174bb7d63106658d29))

## [1.27.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.27.0...v1.27.1-dev.1) (2026-05-13)


### Bug Fixes

* **YouTube - Open channel of live avatar:** Patch may not work when app is using non English language ([#1459](https://github.com/MorpheApp/morphe-patches/issues/1459)) ([34d55c2](https://github.com/MorpheApp/morphe-patches/commit/34d55c27d9f915cc39bed37af5a9a4c1e28a3e0f))

# [1.27.0](https://github.com/MorpheApp/morphe-patches/compare/v1.26.0...v1.27.0) (2026-05-12)


### Bug Fixes

* Add patch exception message for users who ignore the Manager update prompt ([87beb32](https://github.com/MorpheApp/morphe-patches/commit/87beb32f10b05fd960e86cf393bbd14bba9460b5))
* Add Reddit patch exception message for users who ignore the Manager update prompt ([875f10a](https://github.com/MorpheApp/morphe-patches/commit/875f10ab1bc502a73e271c407138511e37b90fba))
* Add YouTube patch exception message for users who ignore the Manager update prompt ([296e137](https://github.com/MorpheApp/morphe-patches/commit/296e137e63f79493fedea773c1d415cc1da94064))
* **Spoof video streams:** Change default client to Android VR ([cafec9c](https://github.com/MorpheApp/morphe-patches/commit/cafec9c61ec65740358777121172896ed7efdf18))
* **YouTube - Save to watch later:** Support saving videos to 'brand' accounts, prevent opening live Shorts from live avatar ([#1438](https://github.com/MorpheApp/morphe-patches/issues/1438)) ([0b12c81](https://github.com/MorpheApp/morphe-patches/commit/0b12c81793aac544f83aa36ee0c19c88cf81cbda))
* **YouTube - Spoof video streams:** Resolve Shorts freezing and feed scrolling issues ([#1433](https://github.com/MorpheApp/morphe-patches/issues/1433)) ([85e0b0c](https://github.com/MorpheApp/morphe-patches/commit/85e0b0c08f465e067716d52c66af396f28b267b0))


### Features

* **Reddit:** Add experimental support for `2026.19.0` ([c4f6058](https://github.com/MorpheApp/morphe-patches/commit/c4f6058872a007624aaafc1456f46733ec6e2212))
* **YouTube - Hide layout components:** Add "Hide Auto-dubbed label", "Hide Corrections section", "Hide Hyped label",  and "Hide Video details section" settings ([#1367](https://github.com/MorpheApp/morphe-patches/issues/1367)) ([43b79c6](https://github.com/MorpheApp/morphe-patches/commit/43b79c6b4679eb7bd5d9983aa4d4e3f8d2ffbb21))
* **YouTube Music:** Add experimental support for `9.18.50` ([5a9ca78](https://github.com/MorpheApp/morphe-patches/commit/5a9ca78ed1354ec415592c63d532bf8d09fb3032))
* **YouTube:** Add `Open channel of live avatar` patch ([#1408](https://github.com/MorpheApp/morphe-patches/issues/1408)) ([b221097](https://github.com/MorpheApp/morphe-patches/commit/b221097be8f1422f35f69b10d94775a2e245b01b))
* **YouTube:** Add `Save to watch later` patch ([#1274](https://github.com/MorpheApp/morphe-patches/issues/1274)) ([45cea2c](https://github.com/MorpheApp/morphe-patches/commit/45cea2c860f7447d643c3c0af59d4517989c9306))
* **YouTube:** Add experimental support for `21.19.280` ([d1c7ac7](https://github.com/MorpheApp/morphe-patches/commit/d1c7ac7b1580ddcc5a2e47211cfb572106b5ffce))

# [1.27.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.27.0-dev.5...v1.27.0-dev.6) (2026-05-12)


### Bug Fixes

* **YouTube - Save to watch later:** Support saving videos to 'brand' accounts, prevent opening live Shorts from live avatar ([#1438](https://github.com/MorpheApp/morphe-patches/issues/1438)) ([0b12c81](https://github.com/MorpheApp/morphe-patches/commit/0b12c81793aac544f83aa36ee0c19c88cf81cbda))


### Features

* **YouTube:** Add `Open channel of live avatar` patch ([#1408](https://github.com/MorpheApp/morphe-patches/issues/1408)) ([b221097](https://github.com/MorpheApp/morphe-patches/commit/b221097be8f1422f35f69b10d94775a2e245b01b))

# [1.27.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.27.0-dev.4...v1.27.0-dev.5) (2026-05-11)


### Bug Fixes

* **Spoof video streams:** Change default client to Android VR ([cafec9c](https://github.com/MorpheApp/morphe-patches/commit/cafec9c61ec65740358777121172896ed7efdf18))

# [1.27.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.27.0-dev.3...v1.27.0-dev.4) (2026-05-11)


### Bug Fixes

* **YouTube - Spoof video streams:** Resolve Shorts freezing and feed scrolling issues ([#1433](https://github.com/MorpheApp/morphe-patches/issues/1433)) ([85e0b0c](https://github.com/MorpheApp/morphe-patches/commit/85e0b0c08f465e067716d52c66af396f28b267b0))

# [1.27.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.27.0-dev.2...v1.27.0-dev.3) (2026-05-10)


### Features

* **Reddit:** Add experimental support for `2026.19.0` ([c4f6058](https://github.com/MorpheApp/morphe-patches/commit/c4f6058872a007624aaafc1456f46733ec6e2212))
* **YouTube Music:** Add experimental support for `9.18.50` ([5a9ca78](https://github.com/MorpheApp/morphe-patches/commit/5a9ca78ed1354ec415592c63d532bf8d09fb3032))

# [1.27.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.27.0-dev.1...v1.27.0-dev.2) (2026-05-08)


### Features

* **YouTube - Hide layout components:** Add "Hide Auto-dubbed label", "Hide Corrections section", "Hide Hyped label",  and "Hide Video details section" settings ([#1367](https://github.com/MorpheApp/morphe-patches/issues/1367)) ([43b79c6](https://github.com/MorpheApp/morphe-patches/commit/43b79c6b4679eb7bd5d9983aa4d4e3f8d2ffbb21))
* **YouTube:** Add `Save to watch later` patch ([#1274](https://github.com/MorpheApp/morphe-patches/issues/1274)) ([45cea2c](https://github.com/MorpheApp/morphe-patches/commit/45cea2c860f7447d643c3c0af59d4517989c9306))

# [1.27.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.26.1-dev.3...v1.27.0-dev.1) (2026-05-07)


### Features

* **YouTube:** Add experimental support for `21.19.280` ([d1c7ac7](https://github.com/MorpheApp/morphe-patches/commit/d1c7ac7b1580ddcc5a2e47211cfb572106b5ffce))

## [1.26.1-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.26.1-dev.2...v1.26.1-dev.3) (2026-05-06)


### Bug Fixes

* Add Reddit patch exception message for users who ignore the Manager update prompt ([875f10a](https://github.com/MorpheApp/morphe-patches/commit/875f10ab1bc502a73e271c407138511e37b90fba))

## [1.26.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.26.1-dev.1...v1.26.1-dev.2) (2026-05-06)


### Bug Fixes

* Add YouTube patch exception message for users who ignore the Manager update prompt ([296e137](https://github.com/MorpheApp/morphe-patches/commit/296e137e63f79493fedea773c1d415cc1da94064))

## [1.26.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.26.0...v1.26.1-dev.1) (2026-05-06)


### Bug Fixes

* Add patch exception message for users who ignore the Manager update prompt ([87beb32](https://github.com/MorpheApp/morphe-patches/commit/87beb32f10b05fd960e86cf393bbd14bba9460b5))

# [1.26.0](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0...v1.26.0) (2026-05-05)


### Bug Fixes

* Disable 'open with' link verification that prevents opening YouTube/Music/Reddit web links on some devices ([b9dbfc1](https://github.com/MorpheApp/morphe-patches/commit/b9dbfc1e05df0f2268b11caef2f3391dc3d8797d))
* Resolve patching error with older versions of Android ([84c74ae](https://github.com/MorpheApp/morphe-patches/commit/84c74ae7a9678ab7c89a660d0dd38d78db506e8f))
* **YouTube - Hide layout components:** Hide flyout menu items for experimental app targets ([#1358](https://github.com/MorpheApp/morphe-patches/issues/1358)) ([d2c324f](https://github.com/MorpheApp/morphe-patches/commit/d2c324f2d25130ac6250539a006b9ccd10d04429))
* **YouTube - Swipe controls:** 2x speed action is triggered when the status bar is visible on Android 15+ ([#1341](https://github.com/MorpheApp/morphe-patches/issues/1341)) ([e0005d3](https://github.com/MorpheApp/morphe-patches/commit/e0005d38ac9140a0a4209a28bdf9601ac9759034))


### Features

* **YouTube - Miniplayer:** Add "Disable resuming miniplayer" setting ([#1342](https://github.com/MorpheApp/morphe-patches/issues/1342)) ([0eb81a9](https://github.com/MorpheApp/morphe-patches/commit/0eb81a91c2d70d4416a56fc91f3d238e4c6f2f29))
* **YouTube - Sanitize sharing links:** Add setting to change Share links to shortened URLs ([#1346](https://github.com/MorpheApp/morphe-patches/issues/1346)) ([f24c119](https://github.com/MorpheApp/morphe-patches/commit/f24c1190796904e77a77a79e086208e6c059b09b))
* **YouTube:** Add experimental support for `21.18.163` ([1bc264a](https://github.com/MorpheApp/morphe-patches/commit/1bc264af775087244c2fa50f66b76999cad48fa0))

# [1.26.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.26.0-dev.3...v1.26.0-dev.4) (2026-05-05)


### Bug Fixes

* Resolve patching error with older versions of Android ([84c74ae](https://github.com/MorpheApp/morphe-patches/commit/84c74ae7a9678ab7c89a660d0dd38d78db506e8f))

# [1.26.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.26.0-dev.2...v1.26.0-dev.3) (2026-05-05)


### Bug Fixes

* Disable 'open with' link verification that prevents opening YouTube/Music/Reddit web links on some devices ([b9dbfc1](https://github.com/MorpheApp/morphe-patches/commit/b9dbfc1e05df0f2268b11caef2f3391dc3d8797d))


### Features

* **YouTube - Miniplayer:** Add "Disable resuming miniplayer" setting ([#1342](https://github.com/MorpheApp/morphe-patches/issues/1342)) ([0eb81a9](https://github.com/MorpheApp/morphe-patches/commit/0eb81a91c2d70d4416a56fc91f3d238e4c6f2f29))
* **YouTube - Sanitize sharing links:** Add setting to change Share links to shortened URLs ([#1346](https://github.com/MorpheApp/morphe-patches/issues/1346)) ([f24c119](https://github.com/MorpheApp/morphe-patches/commit/f24c1190796904e77a77a79e086208e6c059b09b))

# [1.26.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.26.0-dev.1...v1.26.0-dev.2) (2026-05-03)


### Bug Fixes

* **YouTube - Hide layout components:** Hide flyout menu items for experimental app targets ([#1358](https://github.com/MorpheApp/morphe-patches/issues/1358)) ([d2c324f](https://github.com/MorpheApp/morphe-patches/commit/d2c324f2d25130ac6250539a006b9ccd10d04429))

# [1.26.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0...v1.26.0-dev.1) (2026-05-03)


### Bug Fixes

* **YouTube - Swipe controls:** 2x speed action is triggered when the status bar is visible on Android 15+ ([#1341](https://github.com/MorpheApp/morphe-patches/issues/1341)) ([e0005d3](https://github.com/MorpheApp/morphe-patches/commit/e0005d38ac9140a0a4209a28bdf9601ac9759034))


### Features

* **YouTube:** Add experimental support for `21.18.163` ([1bc264a](https://github.com/MorpheApp/morphe-patches/commit/1bc264af775087244c2fa50f66b76999cad48fa0))

# [1.25.0](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0...v1.25.0) (2026-05-02)


### Bug Fixes

* **YouTube - Hide ads:** Fullscreen ads sometimes show with older versions of YouTube ([4a58cb9](https://github.com/MorpheApp/morphe-patches/commit/4a58cb9dafa51429cc46ab2b2c8098d7eb622e50))
* **YouTube - Hide layout components:** "Hide info panel" does not work in search results ([#1229](https://github.com/MorpheApp/morphe-patches/issues/1229)) ([a846ce3](https://github.com/MorpheApp/morphe-patches/commit/a846ce3096484aed65b2a3f7bf5b1a4a67cb6ecb))
* **YouTube - Hide layout components:** Hide preview comment dots ([#1208](https://github.com/MorpheApp/morphe-patches/issues/1208)) ([8a2b63b](https://github.com/MorpheApp/morphe-patches/commit/8a2b63be46400113b4556a5ef73156b44aacde9a))
* **YouTube - Hide layout components:** Resolve "Hide How this was made" not working in Shorts description ([#1257](https://github.com/MorpheApp/morphe-patches/issues/1257)) ([1a188de](https://github.com/MorpheApp/morphe-patches/commit/1a188de4e374ca659f90c0d1b955fe1080974138))
* **YouTube - Open Shorts in regular player:** Back button does not work when viewing Shorts in the regular player ([#1269](https://github.com/MorpheApp/morphe-patches/issues/1269)) ([3976592](https://github.com/MorpheApp/morphe-patches/commit/3976592a983234e2d9470bfe82496d64aed6ddde))
* **YouTube - Remove background playback restrictions:** Add in-app setting to manually turn off the patch ([#1325](https://github.com/MorpheApp/morphe-patches/issues/1325)) ([7877952](https://github.com/MorpheApp/morphe-patches/commit/7877952d5a29cf19b931227014964ea471e5d870))
* **YouTube - Theme:** Apply light/dark theme colors to `21.17.480` ([290315f](https://github.com/MorpheApp/morphe-patches/commit/290315f9847158f947adf045234e99292d7190cd))
* **YouTube Music - Hide ads:** Home feed does not load when Hide fullscreen ads setting is enabled ([0208916](https://github.com/MorpheApp/morphe-patches/commit/0208916e2774451c6fc72258005adc4221c84953))
* **YouTube Music - Track crossfade:** Crossfade does not work when screen is locked ([#1312](https://github.com/MorpheApp/morphe-patches/issues/1312)) ([cc7b8f1](https://github.com/MorpheApp/morphe-patches/commit/cc7b8f1b7b96bcbba099bf388bb7cd73db51db13))
* **YouTube Music - Track crossfade:** Stop playback when app is closed and improvements to auto-play crossfade transitions ([#1243](https://github.com/MorpheApp/morphe-patches/issues/1243)) ([676d61b](https://github.com/MorpheApp/morphe-patches/commit/676d61b5b81f2c63fe27570f38148d7d9297177e))
* **YouTube:** prevent related video hiding with restricted video confirmation watching ([#1235](https://github.com/MorpheApp/morphe-patches/issues/1235)) ([7005f13](https://github.com/MorpheApp/morphe-patches/commit/7005f13aa385768bb9c5852ee395cb028fa21d79))


### Features

* **Reddit:** Add experimental support for `2026.17.0` ([c41d0f8](https://github.com/MorpheApp/morphe-patches/commit/c41d0f8782781aadfcea25d96fbe37295074f014))
* **Reddit:** Add experimental support for `2026.18.0` ([019c338](https://github.com/MorpheApp/morphe-patches/commit/019c338c9c4c82f0b5342c117553887a4c6defce))
* Show a list of patches in the GitHub readme ([#1304](https://github.com/MorpheApp/morphe-patches/issues/1304)) ([73beef5](https://github.com/MorpheApp/morphe-patches/commit/73beef5ddf1b535161e2a3cd7e6799d129b761af))
* **YouTube - Hide layout components:** Add "Hide live chat donators bar" setting ([#1236](https://github.com/MorpheApp/morphe-patches/issues/1236)) ([d058745](https://github.com/MorpheApp/morphe-patches/commit/d0587451c26e80cead531a19843852bdca641c53))
* **YouTube - Hide Shorts components:** Add "Disable double-tap to like" setting ([#1275](https://github.com/MorpheApp/morphe-patches/issues/1275)) ([73c2c58](https://github.com/MorpheApp/morphe-patches/commit/73c2c58f27f62e1221215ce54e39fe9859feeaf5))
* **YouTube - Hide video action buttons:** Add "Hide quick actions" settings ([#1118](https://github.com/MorpheApp/morphe-patches/issues/1118)) ([590033f](https://github.com/MorpheApp/morphe-patches/commit/590033f9db845a5807e4456b03c7c5bad655e5b8))
* **YouTube - Navigation bar:** Add "Hide Cast button" setting ([#1268](https://github.com/MorpheApp/morphe-patches/issues/1268)) ([53dd12c](https://github.com/MorpheApp/morphe-patches/commit/53dd12c0b25587f6cf5ea5c54f2a7b3924b03d75))
* **YouTube - Sponsorblock:** Add highlight segment submission ([#1301](https://github.com/MorpheApp/morphe-patches/issues/1301)) ([000846f](https://github.com/MorpheApp/morphe-patches/commit/000846feb0c8fafd3c67f9d2c8c759c941c13b4d))
* **YouTube - System media controls:** Add options to hide prev/next and seekbar in notification media controls ([#1322](https://github.com/MorpheApp/morphe-patches/issues/1322)) ([71aa218](https://github.com/MorpheApp/morphe-patches/commit/71aa218979117e5cc56d4656a578860845278b38))
* **YouTube Music:** Add experimental support for `9.16.51` ([e743ba8](https://github.com/MorpheApp/morphe-patches/commit/e743ba870674f7826f81987c0d18393c97b35d1a))
* **YouTube Music:** Add experimental support for `9.17.51` ([d4cd2a6](https://github.com/MorpheApp/morphe-patches/commit/d4cd2a628219e9dc57cb15452178514ee8aaebea))
* **YouTube:** Add experimental support for `21.17.480` ([1bb6403](https://github.com/MorpheApp/morphe-patches/commit/1bb64036c33ce225d0f172d1e865e68b3f25ae72))

# [1.25.0-dev.12](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.11...v1.25.0-dev.12) (2026-05-01)


### Features

* **Reddit:** Add experimental support for `2026.18.0` ([019c338](https://github.com/MorpheApp/morphe-patches/commit/019c338c9c4c82f0b5342c117553887a4c6defce))
* **YouTube - System media controls:** Add options to hide prev/next and seekbar in notification media controls ([#1322](https://github.com/MorpheApp/morphe-patches/issues/1322)) ([71aa218](https://github.com/MorpheApp/morphe-patches/commit/71aa218979117e5cc56d4656a578860845278b38))

# [1.25.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.10...v1.25.0-dev.11) (2026-04-30)


### Bug Fixes

* **YouTube - Hide ads:** Fullscreen ads sometimes show with older versions of YouTube ([4a58cb9](https://github.com/MorpheApp/morphe-patches/commit/4a58cb9dafa51429cc46ab2b2c8098d7eb622e50))
* **YouTube - Remove background playback restrictions:** Add in-app setting to manually turn off the patch ([#1325](https://github.com/MorpheApp/morphe-patches/issues/1325)) ([7877952](https://github.com/MorpheApp/morphe-patches/commit/7877952d5a29cf19b931227014964ea471e5d870))


### Features

* **YouTube Music:** Add experimental support for `9.17.51` ([d4cd2a6](https://github.com/MorpheApp/morphe-patches/commit/d4cd2a628219e9dc57cb15452178514ee8aaebea))

# [1.25.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.9...v1.25.0-dev.10) (2026-04-30)


### Bug Fixes

* **YouTube Music - Hide ads:** Home feed does not load when Hide fullscreen ads setting is enabled ([0208916](https://github.com/MorpheApp/morphe-patches/commit/0208916e2774451c6fc72258005adc4221c84953))

# [1.25.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.8...v1.25.0-dev.9) (2026-04-29)


### Features

* **YouTube - Sponsorblock:** Add highlight segment submission ([#1301](https://github.com/MorpheApp/morphe-patches/issues/1301)) ([000846f](https://github.com/MorpheApp/morphe-patches/commit/000846feb0c8fafd3c67f9d2c8c759c941c13b4d))

# [1.25.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.7...v1.25.0-dev.8) (2026-04-28)


### Bug Fixes

* **YouTube Music - Track crossfade:** Crossfade does not work when screen is locked ([#1312](https://github.com/MorpheApp/morphe-patches/issues/1312)) ([cc7b8f1](https://github.com/MorpheApp/morphe-patches/commit/cc7b8f1b7b96bcbba099bf388bb7cd73db51db13))

# [1.25.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.6...v1.25.0-dev.7) (2026-04-28)


### Bug Fixes

* **YouTube Music - Track crossfade:** Stop playback when app is closed and improvements to auto-play crossfade transitions ([#1243](https://github.com/MorpheApp/morphe-patches/issues/1243)) ([676d61b](https://github.com/MorpheApp/morphe-patches/commit/676d61b5b81f2c63fe27570f38148d7d9297177e))

# [1.25.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.5...v1.25.0-dev.6) (2026-04-27)


### Features

* Show a list of patches in the GitHub readme ([#1304](https://github.com/MorpheApp/morphe-patches/issues/1304)) ([73beef5](https://github.com/MorpheApp/morphe-patches/commit/73beef5ddf1b535161e2a3cd7e6799d129b761af))

# [1.25.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.4...v1.25.0-dev.5) (2026-04-27)


### Bug Fixes

* **YouTube - Open Shorts in regular player:** Back button does not work when viewing Shorts in the regular player ([#1269](https://github.com/MorpheApp/morphe-patches/issues/1269)) ([3976592](https://github.com/MorpheApp/morphe-patches/commit/3976592a983234e2d9470bfe82496d64aed6ddde))

# [1.25.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.3...v1.25.0-dev.4) (2026-04-26)


### Bug Fixes

* **YouTube - Theme:** Apply light/dark theme colors to `21.17.480` ([290315f](https://github.com/MorpheApp/morphe-patches/commit/290315f9847158f947adf045234e99292d7190cd))


### Features

* **YouTube - Hide Shorts components:** Add "Disable double-tap to like" setting ([#1275](https://github.com/MorpheApp/morphe-patches/issues/1275)) ([73c2c58](https://github.com/MorpheApp/morphe-patches/commit/73c2c58f27f62e1221215ce54e39fe9859feeaf5))

# [1.25.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.2...v1.25.0-dev.3) (2026-04-25)


### Features

* **Reddit:** Add experimental support for `2026.17.0` ([c41d0f8](https://github.com/MorpheApp/morphe-patches/commit/c41d0f8782781aadfcea25d96fbe37295074f014))
* **YouTube Music:** Add experimental support for `9.16.51` ([e743ba8](https://github.com/MorpheApp/morphe-patches/commit/e743ba870674f7826f81987c0d18393c97b35d1a))

# [1.25.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.25.0-dev.1...v1.25.0-dev.2) (2026-04-24)


### Bug Fixes

* **YouTube:** prevent related video hiding with restricted video confirmation watching ([#1235](https://github.com/MorpheApp/morphe-patches/issues/1235)) ([7005f13](https://github.com/MorpheApp/morphe-patches/commit/7005f13aa385768bb9c5852ee395cb028fa21d79))


### Features

* **YouTube - Navigation bar:** Add "Hide Cast button" setting ([#1268](https://github.com/MorpheApp/morphe-patches/issues/1268)) ([53dd12c](https://github.com/MorpheApp/morphe-patches/commit/53dd12c0b25587f6cf5ea5c54f2a7b3924b03d75))
* **YouTube:** Add experimental support for `21.17.480` ([1bb6403](https://github.com/MorpheApp/morphe-patches/commit/1bb64036c33ce225d0f172d1e865e68b3f25ae72))

# [1.25.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0...v1.25.0-dev.1) (2026-04-22)


### Bug Fixes

* **YouTube - Hide layout components:** "Hide info panel" does not work in search results ([#1229](https://github.com/MorpheApp/morphe-patches/issues/1229)) ([a846ce3](https://github.com/MorpheApp/morphe-patches/commit/a846ce3096484aed65b2a3f7bf5b1a4a67cb6ecb))
* **YouTube - Hide layout components:** Hide preview comment dots ([#1208](https://github.com/MorpheApp/morphe-patches/issues/1208)) ([8a2b63b](https://github.com/MorpheApp/morphe-patches/commit/8a2b63be46400113b4556a5ef73156b44aacde9a))
* **YouTube - Hide layout components:** Resolve "Hide How this was made" not working in Shorts description ([#1257](https://github.com/MorpheApp/morphe-patches/issues/1257)) ([1a188de](https://github.com/MorpheApp/morphe-patches/commit/1a188de4e374ca659f90c0d1b955fe1080974138))


### Features

* **YouTube - Hide layout components:** Add "Hide live chat donators bar" setting ([#1236](https://github.com/MorpheApp/morphe-patches/issues/1236)) ([d058745](https://github.com/MorpheApp/morphe-patches/commit/d0587451c26e80cead531a19843852bdca641c53))
* **YouTube - Hide video action buttons:** Add "Hide quick actions" settings ([#1118](https://github.com/MorpheApp/morphe-patches/issues/1118)) ([590033f](https://github.com/MorpheApp/morphe-patches/commit/590033f9db845a5807e4456b03c7c5bad655e5b8))

# [1.24.0](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0...v1.24.0) (2026-04-20)


### Bug Fixes

* **Reddit - Remove subreddit dialog:** Does not work with experimental app targets ([#1130](https://github.com/MorpheApp/morphe-patches/issues/1130)) ([2560192](https://github.com/MorpheApp/morphe-patches/commit/2560192985c53b63858e78311057d30d5a2887f7))
* **Settings:** Bypass click debounce for toggleable preferences ([#1175](https://github.com/MorpheApp/morphe-patches/issues/1175)) ([b7f453f](https://github.com/MorpheApp/morphe-patches/commit/b7f453fb1bdcc3c962a41d07ef840b352249d1db))
* **Spoof video streams:** Handle Number.toLocaleString() for webview versions that have issues ([#919](https://github.com/MorpheApp/morphe-patches/issues/919)) ([20b6801](https://github.com/MorpheApp/morphe-patches/commit/20b68015857502f447d85e888051dd371128676f))
* **YouTube - Hide ads:** Resolve "Hide YouTube Premium promotions" affecting YouTube Doodles ([#1135](https://github.com/MorpheApp/morphe-patches/issues/1135)) ([881f61b](https://github.com/MorpheApp/morphe-patches/commit/881f61ba200083e3ee35b7e0cf2675ee1f7678d6))
* **YouTube - Hide player flyout menu components:** Cannot hide flyout menu items with `20.21.37` ([#1158](https://github.com/MorpheApp/morphe-patches/issues/1158)) ([3374765](https://github.com/MorpheApp/morphe-patches/commit/3374765710cd3988e19a2aa15132eedc86f2b5c2))
* **YouTube - Hide player flyout menu components:** Resolve "Hide Captions footer" hiding other components ([#1160](https://github.com/MorpheApp/morphe-patches/issues/1160)) ([cf9259a](https://github.com/MorpheApp/morphe-patches/commit/cf9259ab50f5cc8aafe8c615ae7cf87215ac0160))
* **YouTube - Override YouTube Music buttons:** Support launching arbitrary third-party music clients ([#1177](https://github.com/MorpheApp/morphe-patches/issues/1177)) ([7514bf7](https://github.com/MorpheApp/morphe-patches/commit/7514bf7a7e8f0377f7804c420eeaf02106617f2d))
* **YouTube Music - Permanent repeat:** Resolve repeat state not being permanent ([#1193](https://github.com/MorpheApp/morphe-patches/issues/1193)) ([ab5a914](https://github.com/MorpheApp/morphe-patches/commit/ab5a914eb6968a0cdb3de9c2c0f45d1cd42cce4e))
* **YouTube:** Background playback PiP shows a black bar after using livestream chat ([#1173](https://github.com/MorpheApp/morphe-patches/issues/1173)) ([1a30f60](https://github.com/MorpheApp/morphe-patches/commit/1a30f6022166a11cacbce7e098ed474039458829))
* **YouTube:** Fullscreen gradient does not show with bold player layout ([d019c01](https://github.com/MorpheApp/morphe-patches/commit/d019c0172d0a3f205f457c9bde873a7fbfb54bd9))


### Features

* **Reddit:** Add `Hide Ask button` patch ([#1129](https://github.com/MorpheApp/morphe-patches/issues/1129)) ([3ff35f9](https://github.com/MorpheApp/morphe-patches/commit/3ff35f9a5288999d84b1c2323407dd533ed1db2d))
* **Reddit:** Add `Hide Reddit search` patch ([#1128](https://github.com/MorpheApp/morphe-patches/issues/1128)) ([08a1f52](https://github.com/MorpheApp/morphe-patches/commit/08a1f528199e6dede11c8f3cc94322f669a7fac1))
* **Reddit:** Add `Open links externally` patch ([#1131](https://github.com/MorpheApp/morphe-patches/issues/1131)) ([78be254](https://github.com/MorpheApp/morphe-patches/commit/78be254db96f39f93d9a2ac326c7907c2d836f6e))
* **Reddit:** Add experimental support for `2026.15.1` ([2a6442e](https://github.com/MorpheApp/morphe-patches/commit/2a6442ebbad12d858b30fb2f2594cb06c2e4a0d0))
* **Reddit:** Add experimental support for `2026.16.0` ([887c14d](https://github.com/MorpheApp/morphe-patches/commit/887c14d1d96a7d564cabcd301a03c5e7d19222cf))
* **Reddit:** Support version `2026.10.0` ([ea135fb](https://github.com/MorpheApp/morphe-patches/commit/ea135fb439b784c1b4336cc5245b64d8af5b2697))
* **YouTube - Hide layout components:** Add "Hide Settings button" setting for player ([#1187](https://github.com/MorpheApp/morphe-patches/issues/1187)) ([c665a5c](https://github.com/MorpheApp/morphe-patches/commit/c665a5ce8e39ff2e7ac10286372918ce47c35c21))
* **YouTube - Hide layout components:** Add list preference for hiding expandable cards ([#1123](https://github.com/MorpheApp/morphe-patches/issues/1123)) ([7f96bcd](https://github.com/MorpheApp/morphe-patches/commit/7f96bcd6fd4cea0c69e3aa683393e9db4ef7aab5))
* **YouTube - Hide player flyout menu components:** Add "Hide Audio track footer", "Hide Captions footer", and "Hide Captions header" settings ([#642](https://github.com/MorpheApp/morphe-patches/issues/642)) ([19c6c2d](https://github.com/MorpheApp/morphe-patches/commit/19c6c2d525c6dab715f21210d690ea68f7b22f7a))
* **YouTube Music - Hide ads:** Add "Hide fullscreen ads" setting ([#1192](https://github.com/MorpheApp/morphe-patches/issues/1192)) ([b1d704d](https://github.com/MorpheApp/morphe-patches/commit/b1d704d3be325c257abd4acce96516ec9038bd03))
* **YouTube Music:** Add `Track crossfade` patch ([#1065](https://github.com/MorpheApp/morphe-patches/issues/1065)) ([697c794](https://github.com/MorpheApp/morphe-patches/commit/697c79412dd74ffd09dfedbc8458ba7ce3e2938b))
* **YouTube Music:** Add experimental support for `9.14.51` ([e8c941f](https://github.com/MorpheApp/morphe-patches/commit/e8c941ff053e20e54e6303ed405ca5421a4e5768))
* **YouTube Music:** Add experimental support for `9.15.50` ([2399828](https://github.com/MorpheApp/morphe-patches/commit/239982892abbffd6e8e25d310692fb2e860b2c24))
* **YouTube Music:** Add support for `8.47.56` ([fc5bcdd](https://github.com/MorpheApp/morphe-patches/commit/fc5bcdd988f48e00c3ab031f41890ec2792a3a2e))
* **YouTube:** Add experimental support for `21.15.282` ([647911e](https://github.com/MorpheApp/morphe-patches/commit/647911ea660de94d771e4f9e97826ad4d28ffb89))
* **YouTube:** Add experimental support for `21.16.240` ([5a0992c](https://github.com/MorpheApp/morphe-patches/commit/5a0992c98a5e560193706a200eebf51a49f9cb06))
* **YouTube:** Add support for `20.47.62` ([b143f54](https://github.com/MorpheApp/morphe-patches/commit/b143f54c63f94651b6cd14ae1969746047f2ad60))

# [1.24.0-dev.14](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.13...v1.24.0-dev.14) (2026-04-19)


### Bug Fixes

* **YouTube:** Resolve 20.47.62 crashing ([d472802](https://github.com/MorpheApp/morphe-patches/commit/d4728022e29c8babf811bf56f3b32174e81bdef7))

# [1.24.0-dev.13](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.12...v1.24.0-dev.13) (2026-04-19)


### Bug Fixes

* **YouTube:** Resolve app crash when opening community post ([41a5e66](https://github.com/MorpheApp/morphe-patches/commit/41a5e66b04afb51ff25d0b620e053c50605f1939))

# [1.24.0-dev.12](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.11...v1.24.0-dev.12) (2026-04-19)


### Bug Fixes

* **YouTube Music - Permanent repeat:** Resolve repeat state not being permanent ([#1193](https://github.com/MorpheApp/morphe-patches/issues/1193)) ([ab5a914](https://github.com/MorpheApp/morphe-patches/commit/ab5a914eb6968a0cdb3de9c2c0f45d1cd42cce4e))


### Features

* **YouTube - Hide layout components:** Add "Hide Settings button" setting for player ([#1187](https://github.com/MorpheApp/morphe-patches/issues/1187)) ([c665a5c](https://github.com/MorpheApp/morphe-patches/commit/c665a5ce8e39ff2e7ac10286372918ce47c35c21))
* **YouTube Music - Hide ads:** Add "Hide fullscreen ads" setting ([#1192](https://github.com/MorpheApp/morphe-patches/issues/1192)) ([b1d704d](https://github.com/MorpheApp/morphe-patches/commit/b1d704d3be325c257abd4acce96516ec9038bd03))

# [1.24.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.10...v1.24.0-dev.11) (2026-04-18)


### Features

* **Reddit:** Add experimental support for `2026.16.0` ([887c14d](https://github.com/MorpheApp/morphe-patches/commit/887c14d1d96a7d564cabcd301a03c5e7d19222cf))

# [1.24.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.9...v1.24.0-dev.10) (2026-04-17)


### Bug Fixes

* **YouTube:** Fullscreen gradient does not show with bold player layout ([d019c01](https://github.com/MorpheApp/morphe-patches/commit/d019c0172d0a3f205f457c9bde873a7fbfb54bd9))


### Features

* **Reddit:** Support version `2026.10.0` ([ea135fb](https://github.com/MorpheApp/morphe-patches/commit/ea135fb439b784c1b4336cc5245b64d8af5b2697))
* Use shared morphe-patches-library for common code ([#1068](https://github.com/MorpheApp/morphe-patches/issues/1068)) ([a0c5c06](https://github.com/MorpheApp/morphe-patches/commit/a0c5c060bd34159ebe3d13a172e61aa37860a887))
* **YouTube:** Add experimental support for `21.16.240` ([5a0992c](https://github.com/MorpheApp/morphe-patches/commit/5a0992c98a5e560193706a200eebf51a49f9cb06))

# [1.24.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.8...v1.24.0-dev.9) (2026-04-16)


### Features

* **YouTube Music:** Add `Track crossfade` patch ([#1065](https://github.com/MorpheApp/morphe-patches/issues/1065)) ([697c794](https://github.com/MorpheApp/morphe-patches/commit/697c79412dd74ffd09dfedbc8458ba7ce3e2938b))

# [1.24.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.7...v1.24.0-dev.8) (2026-04-15)


### Bug Fixes

* **YouTube - Override YouTube Music buttons:** Support launching arbitrary third-party music clients ([#1177](https://github.com/MorpheApp/morphe-patches/issues/1177)) ([7514bf7](https://github.com/MorpheApp/morphe-patches/commit/7514bf7a7e8f0377f7804c420eeaf02106617f2d))


### Features

* **Reddit:** Add experimental support for `2026.15.1` ([2a6442e](https://github.com/MorpheApp/morphe-patches/commit/2a6442ebbad12d858b30fb2f2594cb06c2e4a0d0))
* **YouTube Music:** Add experimental support for `9.15.50` ([2399828](https://github.com/MorpheApp/morphe-patches/commit/239982892abbffd6e8e25d310692fb2e860b2c24))

# [1.24.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.6...v1.24.0-dev.7) (2026-04-14)


### Bug Fixes

* **Settings:** Bypass click debounce for toggleable preferences ([#1175](https://github.com/MorpheApp/morphe-patches/issues/1175)) ([b7f453f](https://github.com/MorpheApp/morphe-patches/commit/b7f453fb1bdcc3c962a41d07ef840b352249d1db))
* **YouTube - Hide player flyout menu components:** Cannot hide flyout menu items with `20.21.37` ([#1158](https://github.com/MorpheApp/morphe-patches/issues/1158)) ([3374765](https://github.com/MorpheApp/morphe-patches/commit/3374765710cd3988e19a2aa15132eedc86f2b5c2))
* **YouTube - Hide player flyout menu components:** Resolve "Hide Captions footer" hiding other components ([#1160](https://github.com/MorpheApp/morphe-patches/issues/1160)) ([cf9259a](https://github.com/MorpheApp/morphe-patches/commit/cf9259ab50f5cc8aafe8c615ae7cf87215ac0160))
* **YouTube:** Background playback PiP shows a black bar after using livestream chat ([#1173](https://github.com/MorpheApp/morphe-patches/issues/1173)) ([1a30f60](https://github.com/MorpheApp/morphe-patches/commit/1a30f6022166a11cacbce7e098ed474039458829))

# [1.24.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.5...v1.24.0-dev.6) (2026-04-12)


### Features

* **YouTube - Hide layout components:** Add list preference for hiding expandable cards ([#1123](https://github.com/MorpheApp/morphe-patches/issues/1123)) ([7f96bcd](https://github.com/MorpheApp/morphe-patches/commit/7f96bcd6fd4cea0c69e3aa683393e9db4ef7aab5))
* **YouTube Music:** Add support for `8.47.56` ([fc5bcdd](https://github.com/MorpheApp/morphe-patches/commit/fc5bcdd988f48e00c3ab031f41890ec2792a3a2e))
* **YouTube:** Add support for `20.47.62` ([b143f54](https://github.com/MorpheApp/morphe-patches/commit/b143f54c63f94651b6cd14ae1969746047f2ad60))

# [1.24.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.4...v1.24.0-dev.5) (2026-04-11)


### Bug Fixes

* **YouTube - Hide ads:** Resolve "Hide YouTube Premium promotions" affecting YouTube Doodles ([#1135](https://github.com/MorpheApp/morphe-patches/issues/1135)) ([881f61b](https://github.com/MorpheApp/morphe-patches/commit/881f61ba200083e3ee35b7e0cf2675ee1f7678d6))


### Features

* **Reddit:** Add `Hide Ask button` patch ([#1129](https://github.com/MorpheApp/morphe-patches/issues/1129)) ([3ff35f9](https://github.com/MorpheApp/morphe-patches/commit/3ff35f9a5288999d84b1c2323407dd533ed1db2d))
* **Reddit:** Add `Hide Reddit search` patch ([#1128](https://github.com/MorpheApp/morphe-patches/issues/1128)) ([08a1f52](https://github.com/MorpheApp/morphe-patches/commit/08a1f528199e6dede11c8f3cc94322f669a7fac1))
* **Reddit:** Add `Open links externally` patch ([#1131](https://github.com/MorpheApp/morphe-patches/issues/1131)) ([78be254](https://github.com/MorpheApp/morphe-patches/commit/78be254db96f39f93d9a2ac326c7907c2d836f6e))
* **Reddit:** Add experimental support for `2026.15.0` ([784c83c](https://github.com/MorpheApp/morphe-patches/commit/784c83c676c48525fb4c4575fbce00bcf0b6c024))

# [1.24.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.3...v1.24.0-dev.4) (2026-04-10)


### Bug Fixes

* **YouTube:** Use legacy layout parameters with older app targets ([329473a](https://github.com/MorpheApp/morphe-patches/commit/329473aadec23209800ad54aa4daa5df5a24e8f3))


### Features

* **YouTube Music:** Add experimental support for `9.14.51` ([e8c941f](https://github.com/MorpheApp/morphe-patches/commit/e8c941ff053e20e54e6303ed405ca5421a4e5768))

# [1.24.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.2...v1.24.0-dev.3) (2026-04-10)


### Bug Fixes

* **YouTube - SponsorBlock:** 400 error is shown when closing 21.15.282 video player ([c9a5530](https://github.com/MorpheApp/morphe-patches/commit/c9a553048c318ec79ae893c06b0fe3aaf381528d))


### Features

* **YouTube - Hide player flyout menu components:** Add "Hide Audio track footer", "Hide Captions footer", and "Hide Captions header" settings ([#642](https://github.com/MorpheApp/morphe-patches/issues/642)) ([19c6c2d](https://github.com/MorpheApp/morphe-patches/commit/19c6c2d525c6dab715f21210d690ea68f7b22f7a))

# [1.24.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.24.0-dev.1...v1.24.0-dev.2) (2026-04-10)


### Bug Fixes

* **Reddit - Remove subreddit dialog:** Does not work with experimental app targets ([#1130](https://github.com/MorpheApp/morphe-patches/issues/1130)) ([2560192](https://github.com/MorpheApp/morphe-patches/commit/2560192985c53b63858e78311057d30d5a2887f7))
* **YouTube - Playback speed:** 21.15.282 speed button text alignment is wrong ([16104ac](https://github.com/MorpheApp/morphe-patches/commit/16104ac969c89cb58ca49d47c04792a0918ba173))

# [1.24.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0...v1.24.0-dev.1) (2026-04-09)


### Features

* **YouTube:** Add experimental support for `21.15.282` ([647911e](https://github.com/MorpheApp/morphe-patches/commit/647911ea660de94d771e4f9e97826ad4d28ffb89))

# [1.23.0](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0...v1.23.0) (2026-04-09)


### Bug Fixes

* **Change package name:** DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is not updated correctly ([#1091](https://github.com/MorpheApp/morphe-patches/issues/1091)) ([39c5b47](https://github.com/MorpheApp/morphe-patches/commit/39c5b47cbd41163bac6fff1a397e186a8e9c567a))
* Notification icon entries key mismatch ([#1040](https://github.com/MorpheApp/morphe-patches/issues/1040)) ([01ef1ea](https://github.com/MorpheApp/morphe-patches/commit/01ef1ea731196325a00de060d6f34f51644a94c5))
* **Spoof video streams:** Expose patch to root (mount) install since the patch now works again with rooted devices ([0363d75](https://github.com/MorpheApp/morphe-patches/commit/0363d75d19ac6a6ec02319562f8f687db5027d6f))
* **YouTube - Change start page:** Remove explore and trending start pages that YouTube no longer supports ([f165f0c](https://github.com/MorpheApp/morphe-patches/commit/f165f0c2a44f37ab3e2f036644eb5057f0a89bd4))
* **YouTube - Disable video codecs:** Improve setting summary if the spoof video client does not support forcing AVC ([0238d14](https://github.com/MorpheApp/morphe-patches/commit/0238d14df83198f41c9cbddd4aa4933ba995581d))
* **YouTube - Hide layout components:** Hide new type of community post ([#1114](https://github.com/MorpheApp/morphe-patches/issues/1114)) ([9fc6aef](https://github.com/MorpheApp/morphe-patches/commit/9fc6aefba130629cbeefb5d4bfa1cd798e9ce995))
* **YouTube - Hide player overlay buttons:** Do not hide Morphe player buttons if hide fullscreen button is enabled ([96c5789](https://github.com/MorpheApp/morphe-patches/commit/96c5789e45ea2f5827db93319d9be7405c8cee19))
* **YouTube - Overlay buttons:** Always use thin player overlay buttons with 20.21.37 ([db96e8e](https://github.com/MorpheApp/morphe-patches/commit/db96e8e34d7e0c1393562a7f1dfa5fd0fb1f9c5d))
* **YouTube - Theme:** Carbon background color does not use custom background color with experimental app targets ([fa2439d](https://github.com/MorpheApp/morphe-patches/commit/fa2439d09286db83a0d588b407bbc5248e1589cf))
* **YouTube:** "Restore old setings menu" does not work with experimental app targets ([#1127](https://github.com/MorpheApp/morphe-patches/issues/1127)) ([74826e6](https://github.com/MorpheApp/morphe-patches/commit/74826e6aea75dec8e7290f33e4bf9eb476d2abe8))
* **YouTube:** Bold player buttons break under certain conditions ([#1126](https://github.com/MorpheApp/morphe-patches/issues/1126)) ([8d928f5](https://github.com/MorpheApp/morphe-patches/commit/8d928f596323b4d33cd2ae071a6c47101a61404b))
* **YouTube:** Swiping back on home feed does not close the app ([74a5074](https://github.com/MorpheApp/morphe-patches/commit/74a5074991ec935d0588843b1067a084944c2e42))


### Features

* Add search and file export to debug logs ([#997](https://github.com/MorpheApp/morphe-patches/issues/997)) ([a53a6c3](https://github.com/MorpheApp/morphe-patches/commit/a53a6c3b0c8f534e4bca4a015a25452d2c73ae8a))
* **Reddit:** Add experimental support for `2026.13.0` ([722f5bf](https://github.com/MorpheApp/morphe-patches/commit/722f5bf34f986c51b9631575cd09eb64dac47314))
* **Reddit:** Add experimental support for `2026.14.0` ([921fa89](https://github.com/MorpheApp/morphe-patches/commit/921fa89a37a4e318ed3ba5a0cb090b00f0a4c3af))
* **YouTube - Hide layout components:** Add "Hide search term thumbnails" setting ([#1116](https://github.com/MorpheApp/morphe-patches/issues/1116)) ([61d5fd6](https://github.com/MorpheApp/morphe-patches/commit/61d5fd62f300640240a82f477bb56c82cbedfbee))
* **YouTube - Seekbar:** Add "Enable livestream DVR" setting ([#1092](https://github.com/MorpheApp/morphe-patches/issues/1092)) ([8af85d0](https://github.com/MorpheApp/morphe-patches/commit/8af85d07b68e08f8e2731a77a4a08a1931348911))
* **YouTube - Seekbar:** Add "Expand livestream DVR duration" setting ([#1110](https://github.com/MorpheApp/morphe-patches/issues/1110)) ([1a446d0](https://github.com/MorpheApp/morphe-patches/commit/1a446d089c969993813a5017150ac8e648e26786))
* **YouTube Music:** Add experimental support for `9.13.50` ([bd462e5](https://github.com/MorpheApp/morphe-patches/commit/bd462e5ab274968878314295b7238ae9371564d0))
* **YouTube:** Add `Play all` patch ([#1012](https://github.com/MorpheApp/morphe-patches/issues/1012)) ([b3ac238](https://github.com/MorpheApp/morphe-patches/commit/b3ac238aa15342706d60532ea0bba12f45fef2ac))
* **YouTube:** Add experimental support for `21.14.482` ([bb6f79f](https://github.com/MorpheApp/morphe-patches/commit/bb6f79f5a0ad36383a047586895d1c40212dae48))
* **YouTube:** Add Morphe bold style player buttons ([#954](https://github.com/MorpheApp/morphe-patches/issues/954)) ([7f70b6f](https://github.com/MorpheApp/morphe-patches/commit/7f70b6f10ab5e600f4297d4208641a0ea2d817b1))

# [1.23.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.10...v1.23.0-dev.11) (2026-04-09)


### Bug Fixes

* **YouTube:** "Restore old setings menu" does not work with experimental app targets ([#1127](https://github.com/MorpheApp/morphe-patches/issues/1127)) ([74826e6](https://github.com/MorpheApp/morphe-patches/commit/74826e6aea75dec8e7290f33e4bf9eb476d2abe8))
* **YouTube:** Bold player buttons break under certain conditions ([#1126](https://github.com/MorpheApp/morphe-patches/issues/1126)) ([8d928f5](https://github.com/MorpheApp/morphe-patches/commit/8d928f596323b4d33cd2ae071a6c47101a61404b))

# [1.23.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.9...v1.23.0-dev.10) (2026-04-09)


### Features

* **YouTube - Seekbar:** Add "Expand livestream DVR duration" setting ([#1110](https://github.com/MorpheApp/morphe-patches/issues/1110)) ([1a446d0](https://github.com/MorpheApp/morphe-patches/commit/1a446d089c969993813a5017150ac8e648e26786))

# [1.23.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.8...v1.23.0-dev.9) (2026-04-08)


### Bug Fixes

* **YouTube - Hide layout components:** Hide new type of community post ([#1114](https://github.com/MorpheApp/morphe-patches/issues/1114)) ([9fc6aef](https://github.com/MorpheApp/morphe-patches/commit/9fc6aefba130629cbeefb5d4bfa1cd798e9ce995))


### Features

* **YouTube - Hide layout components:** Add "Hide search term thumbnails" setting ([#1116](https://github.com/MorpheApp/morphe-patches/issues/1116)) ([61d5fd6](https://github.com/MorpheApp/morphe-patches/commit/61d5fd62f300640240a82f477bb56c82cbedfbee))

# [1.23.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.7...v1.23.0-dev.8) (2026-04-06)


### Bug Fixes

* **YouTube - Overlay buttons:** Always use thin player overlay buttons with 20.21.37 ([db96e8e](https://github.com/MorpheApp/morphe-patches/commit/db96e8e34d7e0c1393562a7f1dfa5fd0fb1f9c5d))

# [1.23.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.6...v1.23.0-dev.7) (2026-04-06)


### Bug Fixes

* **YouTube - Change start page:** Remove explore and trending start pages that YouTube no longer supports ([f165f0c](https://github.com/MorpheApp/morphe-patches/commit/f165f0c2a44f37ab3e2f036644eb5057f0a89bd4))


### Features

* **YouTube:** Add `Play all` patch ([#1012](https://github.com/MorpheApp/morphe-patches/issues/1012)) ([b3ac238](https://github.com/MorpheApp/morphe-patches/commit/b3ac238aa15342706d60532ea0bba12f45fef2ac))

# [1.23.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.5...v1.23.0-dev.6) (2026-04-05)


### Bug Fixes

* **Change package name:** DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is not updated correctly ([#1091](https://github.com/MorpheApp/morphe-patches/issues/1091)) ([39c5b47](https://github.com/MorpheApp/morphe-patches/commit/39c5b47cbd41163bac6fff1a397e186a8e9c567a))
* **YouTube - Disable video codecs:** Improve setting summary if the spoof video client does not support forcing AVC ([0238d14](https://github.com/MorpheApp/morphe-patches/commit/0238d14df83198f41c9cbddd4aa4933ba995581d))


### Features

* **YouTube - Seekbar:** Add "Enable livestream DVR" setting ([#1092](https://github.com/MorpheApp/morphe-patches/issues/1092)) ([8af85d0](https://github.com/MorpheApp/morphe-patches/commit/8af85d07b68e08f8e2731a77a4a08a1931348911))

# [1.23.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.4...v1.23.0-dev.5) (2026-04-04)


### Features

* **Reddit:** Add experimental support for `2026.14.0` ([921fa89](https://github.com/MorpheApp/morphe-patches/commit/921fa89a37a4e318ed3ba5a0cb090b00f0a4c3af))

# [1.23.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.3...v1.23.0-dev.4) (2026-04-03)


### Bug Fixes

* **Spoof video streams:** Expose patch to root (mount) install since the patch now works again with rooted devices ([0363d75](https://github.com/MorpheApp/morphe-patches/commit/0363d75d19ac6a6ec02319562f8f687db5027d6f))
* **YouTube - Hide player overlay buttons:** Do not hide Morphe player buttons if hide fullscreen button is enabled ([96c5789](https://github.com/MorpheApp/morphe-patches/commit/96c5789e45ea2f5827db93319d9be7405c8cee19))


### Features

* Add search and file export to debug logs ([#997](https://github.com/MorpheApp/morphe-patches/issues/997)) ([a53a6c3](https://github.com/MorpheApp/morphe-patches/commit/a53a6c3b0c8f534e4bca4a015a25452d2c73ae8a))
* **YouTube:** Add experimental support for `21.14.482` ([bb6f79f](https://github.com/MorpheApp/morphe-patches/commit/bb6f79f5a0ad36383a047586895d1c40212dae48))

# [1.23.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.2...v1.23.0-dev.3) (2026-04-02)


### Bug Fixes

* **YouTube:** Swiping back on home feed does not close the app ([74a5074](https://github.com/MorpheApp/morphe-patches/commit/74a5074991ec935d0588843b1067a084944c2e42))


### Features

* **YouTube Music:** Add experimental support for `9.13.50` ([bd462e5](https://github.com/MorpheApp/morphe-patches/commit/bd462e5ab274968878314295b7238ae9371564d0))

# [1.23.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.23.0-dev.1...v1.23.0-dev.2) (2026-03-30)


### Bug Fixes

* Notification icon entries key mismatch ([#1040](https://github.com/MorpheApp/morphe-patches/issues/1040)) ([01ef1ea](https://github.com/MorpheApp/morphe-patches/commit/01ef1ea731196325a00de060d6f34f51644a94c5))


### Features

* **Reddit:** Add experimental support for `2026.13.0` ([722f5bf](https://github.com/MorpheApp/morphe-patches/commit/722f5bf34f986c51b9631575cd09eb64dac47314))

# [1.23.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0...v1.23.0-dev.1) (2026-03-29)


### Bug Fixes

* **YouTube - Theme:** Carbon background color does not use custom background color with experimental app targets ([fa2439d](https://github.com/MorpheApp/morphe-patches/commit/fa2439d09286db83a0d588b407bbc5248e1589cf))


### Features

* **YouTube:** Add Morphe bold style player buttons ([#954](https://github.com/MorpheApp/morphe-patches/issues/954)) ([7f70b6f](https://github.com/MorpheApp/morphe-patches/commit/7f70b6f10ab5e600f4297d4208641a0ea2d817b1))

# [1.22.0](https://github.com/MorpheApp/morphe-patches/compare/v1.21.1...v1.22.0) (2026-03-28)


### Bug Fixes

* **Reddit - Hide sidebar components:** Update patch to work with `2026.12.0` ([bdcde63](https://github.com/MorpheApp/morphe-patches/commit/bdcde634e457912e61bce21192fd6e8d8bad3ea2))
* **Reddit:** App side bar may not be shown ([7df05c2](https://github.com/MorpheApp/morphe-patches/commit/7df05c2637fbcc370d7f10950ca8f586134e09e1))
* **Settings:** Improve back gesture, prevent double-clicks, and fix theme update ([#920](https://github.com/MorpheApp/morphe-patches/issues/920)) ([a2e86d2](https://github.com/MorpheApp/morphe-patches/commit/a2e86d28171e13179d672766929f9c7e9afe3003))
* **YouTube - Hide ads:** Hide all promotion banner ads ([#987](https://github.com/MorpheApp/morphe-patches/issues/987)) ([9096bcd](https://github.com/MorpheApp/morphe-patches/commit/9096bcd4cea53c57a8cb336421d53302ea8cd444))
* **YouTube - Hide layout components:** Resolve patch failing on `20.21.37` and `20.31.42` ([#971](https://github.com/MorpheApp/morphe-patches/issues/971)) ([387df8c](https://github.com/MorpheApp/morphe-patches/commit/387df8cc609b26ff2591b3a97e3865996e425db3))


### Features

* Per-theme notification and monochrome icons, add Play icon ([#978](https://github.com/MorpheApp/morphe-patches/issues/978)) ([ddc59c9](https://github.com/MorpheApp/morphe-patches/commit/ddc59c9a96d4b77ca72642e4919e27b9872beee0))
* **Reddit:** Add experimental support for `2026.12.0` ([#999](https://github.com/MorpheApp/morphe-patches/issues/999)) ([8df2a01](https://github.com/MorpheApp/morphe-patches/commit/8df2a010ef2950c503dcb640b19207357da0d248))
* Set notification icon independently of the launcher icon ([#1006](https://github.com/MorpheApp/morphe-patches/issues/1006)) ([f21c62d](https://github.com/MorpheApp/morphe-patches/commit/f21c62de9e2897c5096d1545a40cfe21c6c25f44))
* **YouTube - Navigation bar:** Add "Disable auto-hide navigation bar" setting ([#973](https://github.com/MorpheApp/morphe-patches/issues/973)) ([f04d001](https://github.com/MorpheApp/morphe-patches/commit/f04d00108539368894aca6820e160324ab102559))
* **YouTube Music:** Add experimental support for `9.12.51` ([746bdb9](https://github.com/MorpheApp/morphe-patches/commit/746bdb9649ebbb252ac222110a3ca09eda227458))
* **YouTube:** Add experimental support for `21.13.163` ([ef345ba](https://github.com/MorpheApp/morphe-patches/commit/ef345ba5579a3631420ca5f29d2e415ae7f19948))


### Performance Improvements

* **Litho filtering:** Use Boyer-Moore-Horspool algorithm instead of KMP ([#960](https://github.com/MorpheApp/morphe-patches/issues/960)) ([5b65548](https://github.com/MorpheApp/morphe-patches/commit/5b655488bea90372ccf0e103cdb3229b51b8bfbd))

# [1.22.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0-dev.6...v1.22.0-dev.7) (2026-03-28)


### Bug Fixes

* **YouTube - Hide ads:** Hide all promotion banner ads ([#987](https://github.com/MorpheApp/morphe-patches/issues/987)) ([9096bcd](https://github.com/MorpheApp/morphe-patches/commit/9096bcd4cea53c57a8cb336421d53302ea8cd444))


### Features

* **YouTube Music:** Add experimental support for `9.12.51` ([746bdb9](https://github.com/MorpheApp/morphe-patches/commit/746bdb9649ebbb252ac222110a3ca09eda227458))
* **YouTube:** Add experimental support for `21.13.163` ([ef345ba](https://github.com/MorpheApp/morphe-patches/commit/ef345ba5579a3631420ca5f29d2e415ae7f19948))

# [1.22.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0-dev.5...v1.22.0-dev.6) (2026-03-26)


### Bug Fixes

* **Settings:** Improve back gesture, prevent double-clicks, and fix theme update ([#920](https://github.com/MorpheApp/morphe-patches/issues/920)) ([a2e86d2](https://github.com/MorpheApp/morphe-patches/commit/a2e86d28171e13179d672766929f9c7e9afe3003))


### Features

* Set notification icon independently of the launcher icon ([#1006](https://github.com/MorpheApp/morphe-patches/issues/1006)) ([f21c62d](https://github.com/MorpheApp/morphe-patches/commit/f21c62de9e2897c5096d1545a40cfe21c6c25f44))


### Performance Improvements

* **Litho filtering:** Use Boyer-Moore-Horspool algorithm instead of KMP ([#960](https://github.com/MorpheApp/morphe-patches/issues/960)) ([5b65548](https://github.com/MorpheApp/morphe-patches/commit/5b655488bea90372ccf0e103cdb3229b51b8bfbd))

# [1.22.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0-dev.4...v1.22.0-dev.5) (2026-03-24)


### Bug Fixes

* **Reddit - Hide sidebar components:** Update patch to work with `2026.12.0` ([bdcde63](https://github.com/MorpheApp/morphe-patches/commit/bdcde634e457912e61bce21192fd6e8d8bad3ea2))

# [1.22.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0-dev.3...v1.22.0-dev.4) (2026-03-24)


### Bug Fixes

* **Reddit:** App side bar may not be shown ([7df05c2](https://github.com/MorpheApp/morphe-patches/commit/7df05c2637fbcc370d7f10950ca8f586134e09e1))

# [1.22.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0-dev.2...v1.22.0-dev.3) (2026-03-24)


### Features

* Per-theme notification and monochrome icons, add Play icon ([#978](https://github.com/MorpheApp/morphe-patches/issues/978)) ([ddc59c9](https://github.com/MorpheApp/morphe-patches/commit/ddc59c9a96d4b77ca72642e4919e27b9872beee0))

# [1.22.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.22.0-dev.1...v1.22.0-dev.2) (2026-03-24)


### Features

* **Reddit:** Add experimental support for `2026.12.0` ([#999](https://github.com/MorpheApp/morphe-patches/issues/999)) ([8df2a01](https://github.com/MorpheApp/morphe-patches/commit/8df2a010ef2950c503dcb640b19207357da0d248))

# [1.22.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.21.1...v1.22.0-dev.1) (2026-03-23)


### Bug Fixes

* **YouTube - Hide layout components:** Resolve patch failing on `20.21.37` and `20.31.42` ([#971](https://github.com/MorpheApp/morphe-patches/issues/971)) ([387df8c](https://github.com/MorpheApp/morphe-patches/commit/387df8cc609b26ff2591b3a97e3865996e425db3))


### Features

* **YouTube - Navigation bar:** Add "Disable auto-hide navigation bar" setting ([#973](https://github.com/MorpheApp/morphe-patches/issues/973)) ([f04d001](https://github.com/MorpheApp/morphe-patches/commit/f04d00108539368894aca6820e160324ab102559))

## [1.21.1](https://github.com/MorpheApp/morphe-patches/compare/v1.21.0...v1.21.1) (2026-03-22)


### Bug Fixes

* Change to patcher 1.3.2 ([b8e63dc](https://github.com/MorpheApp/morphe-patches/commit/b8e63dc7f18fc496a1159472a12f2ece7f76b273))

## [1.21.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.21.0...v1.21.1-dev.1) (2026-03-22)


### Bug Fixes

* Change to patcher 1.3.2 ([b8e63dc](https://github.com/MorpheApp/morphe-patches/commit/b8e63dc7f18fc496a1159472a12f2ece7f76b273))

# [1.21.0](https://github.com/MorpheApp/morphe-patches/compare/v1.20.0...v1.21.0) (2026-03-22)


### Bug Fixes

* Change to patcher 1.3.1 ([02b09cf](https://github.com/MorpheApp/morphe-patches/commit/02b09cfa7aebe4a4cf954aa9490fcbf06884dfce))
* **YouTube - Hide player flyout menu items:** Do not hide speed flyout menu if 'Hide loop video' is enabled ([46834e5](https://github.com/MorpheApp/morphe-patches/commit/46834e5fdbba474e6aff693caad7ae14b6f0eb65))


### Features

* **YouTube - Hide layout components:** Add "Hide Info button" setting ([#944](https://github.com/MorpheApp/morphe-patches/issues/944)) ([d43fbeb](https://github.com/MorpheApp/morphe-patches/commit/d43fbeb9b790eba32f62656a95a613c06d8ef021))
* **YouTube - Navigation bar:** Add "Show Settings" in Toolbar and "Settings button action" in Navigation buttons ([#750](https://github.com/MorpheApp/morphe-patches/issues/750)) ([7bd589e](https://github.com/MorpheApp/morphe-patches/commit/7bd589eaf4abd1a69989be336b2f6e5521581bd5))

# [1.21.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.21.0-dev.2...v1.21.0-dev.3) (2026-03-22)


### Bug Fixes

* Change to patcher 1.3.1 ([02b09cf](https://github.com/MorpheApp/morphe-patches/commit/02b09cfa7aebe4a4cf954aa9490fcbf06884dfce))

# [1.21.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.21.0-dev.1...v1.21.0-dev.2) (2026-03-22)


### Bug Fixes

* **YouTube - Hide player flyout menu items:** Do not hide speed flyout menu if 'Hide loop video' is enabled ([46834e5](https://github.com/MorpheApp/morphe-patches/commit/46834e5fdbba474e6aff693caad7ae14b6f0eb65))


### Features

* **YouTube - Navigation bar:** Add "Show Settings" in Toolbar and "Settings button action" in Navigation buttons ([#750](https://github.com/MorpheApp/morphe-patches/issues/750)) ([7bd589e](https://github.com/MorpheApp/morphe-patches/commit/7bd589eaf4abd1a69989be336b2f6e5521581bd5))

# [1.21.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.20.0...v1.21.0-dev.1) (2026-03-22)


### Features

* **YouTube - Hide layout components:** Add "Hide Info button" setting ([#944](https://github.com/MorpheApp/morphe-patches/issues/944)) ([d43fbeb](https://github.com/MorpheApp/morphe-patches/commit/d43fbeb9b790eba32f62656a95a613c06d8ef021))

# [1.20.0](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0...v1.20.0) (2026-03-20)


### Bug Fixes

* **Settings:** Prevent duplicate dialogs on rapid preference clicks ([#900](https://github.com/MorpheApp/morphe-patches/issues/900)) ([478d97a](https://github.com/MorpheApp/morphe-patches/commit/478d97aa0856b6ebd7cbf41d5c10850295ba2fb8))
* **YouTube - Captions:** Captions disabled due to incorrect hooking ([#908](https://github.com/MorpheApp/morphe-patches/issues/908)) ([a03f245](https://github.com/MorpheApp/morphe-patches/commit/a03f24563a567a6ebd7bf55e1844ef6f2c06529c))
* **YouTube - Change miniplayer color:** Fix patching experimental app target changes ([1c6c61f](https://github.com/MorpheApp/morphe-patches/commit/1c6c61f82e0fbbf4e8609b84c26b005ed3c50df2))
* **YouTube - LithoFilter:** Press the back button twice instead of once to exit the app ([#907](https://github.com/MorpheApp/morphe-patches/issues/907)) ([c450c5a](https://github.com/MorpheApp/morphe-patches/commit/c450c5abecc416e05b335fa178382501b0e3d7b3))
* **YouTube - Navigation bar:** Use updated activity hook for toolbar settings menu ([fafac89](https://github.com/MorpheApp/morphe-patches/commit/fafac89df0680a9fd8fe3dc09983f99c157b8dca))
* **YouTube - Playback speed:** Playback speed menu opens from the feed flyout menu when `Restore old playback speed menu` is off ([#906](https://github.com/MorpheApp/morphe-patches/issues/906)) ([3bb93a9](https://github.com/MorpheApp/morphe-patches/commit/3bb93a9fd1b838593ed5e3f38314855cade17c3f))
* **YouTube - Swipe controls:** Swipe controls sometimes do not work ([#905](https://github.com/MorpheApp/morphe-patches/issues/905)) ([bd23813](https://github.com/MorpheApp/morphe-patches/commit/bd23813b31ade167e4b1cff3b636163c41696c49))
* **YouTube:** Advanced video quality menu does not work ([3381026](https://github.com/MorpheApp/morphe-patches/commit/3381026a63c91460243cb512554e64d0f3662a70))


### Features

* Add Import/Export settings from/to a file ([#865](https://github.com/MorpheApp/morphe-patches/issues/865)) ([098a0d8](https://github.com/MorpheApp/morphe-patches/commit/098a0d8e8993baff5e85676aae6ff5a66d615473))
* **GmsCore support:** Add a setting to not prompt to disable MicroG battery optimization ([#921](https://github.com/MorpheApp/morphe-patches/issues/921)) ([4632fb6](https://github.com/MorpheApp/morphe-patches/commit/4632fb69f9359a709e96d4699d9bfb05133c3a5c))
* **Reddit:** Add Crowdin translations ([#882](https://github.com/MorpheApp/morphe-patches/issues/882)) ([c08ccdb](https://github.com/MorpheApp/morphe-patches/commit/c08ccdb30b460ccbe7ca70b2a5c427b05ba29a3c))
* **Spoof video streams:** Default client maintenance ([#928](https://github.com/MorpheApp/morphe-patches/issues/928)) ([0884870](https://github.com/MorpheApp/morphe-patches/commit/088487090bc59b1c6358551f4e4634f2a847e781))
* **YouTube - Hide layout components:** Add "Comments carousel filter" setting ([#909](https://github.com/MorpheApp/morphe-patches/issues/909)) ([4239720](https://github.com/MorpheApp/morphe-patches/commit/42397200183b8c4b5edd6bd3d27cd8f2b6706756))
* **YouTube Music:** Add `Miniplayer previous and next buttons` patch ([#818](https://github.com/MorpheApp/morphe-patches/issues/818)) ([7e7e08d](https://github.com/MorpheApp/morphe-patches/commit/7e7e08dbfff859890b3bce26fcc882cff1074f4a))
* **YouTube Music:** Add experimental support for `9.11.54` ([73eb7f1](https://github.com/MorpheApp/morphe-patches/commit/73eb7f1475161517a965c0d9ebbb0f8e67a7af93))
* **YouTube:** Add `Reload video` patch ([#873](https://github.com/MorpheApp/morphe-patches/issues/873)) ([fad0e95](https://github.com/MorpheApp/morphe-patches/commit/fad0e95e6be8b29bd32bcec4ef518efff3bcc0eb))
* **YouTube:** Add experimental support for `21.12.521` ([#925](https://github.com/MorpheApp/morphe-patches/issues/925)) ([039e982](https://github.com/MorpheApp/morphe-patches/commit/039e9827fe22562e6b4b9c103621a04f08509fc4))
* **YouTube:** Add support for `20.45.36` ([c22a6df](https://github.com/MorpheApp/morphe-patches/commit/c22a6dfd07a9e1e980e5f5eb9ad22565b1da7f42))


### Performance Improvements

* Reduce patches bundle size ([#891](https://github.com/MorpheApp/morphe-patches/issues/891)) ([74c4611](https://github.com/MorpheApp/morphe-patches/commit/74c461185ac19a3d7258f738e92f49da74721703))

# [1.20.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.20.0-dev.4...v1.20.0-dev.5) (2026-03-20)


### Bug Fixes

* **YouTube - Change miniplayer color:** Fix patching experimental app target changes ([1c6c61f](https://github.com/MorpheApp/morphe-patches/commit/1c6c61f82e0fbbf4e8609b84c26b005ed3c50df2))
* **YouTube - Navigation bar:** Use updated activity hook for toolbar settings menu ([fafac89](https://github.com/MorpheApp/morphe-patches/commit/fafac89df0680a9fd8fe3dc09983f99c157b8dca))

# [1.20.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.20.0-dev.3...v1.20.0-dev.4) (2026-03-20)


### Bug Fixes

* **YouTube - LithoFilter:** Press the back button twice instead of once to exit the app ([#907](https://github.com/MorpheApp/morphe-patches/issues/907)) ([c450c5a](https://github.com/MorpheApp/morphe-patches/commit/c450c5abecc416e05b335fa178382501b0e3d7b3))


### Features

* **GmsCore support:** Add a setting to not prompt to disable MicroG battery optimization ([#921](https://github.com/MorpheApp/morphe-patches/issues/921)) ([4632fb6](https://github.com/MorpheApp/morphe-patches/commit/4632fb69f9359a709e96d4699d9bfb05133c3a5c))
* **Spoof video streams:** Default client maintenance ([#928](https://github.com/MorpheApp/morphe-patches/issues/928)) ([0884870](https://github.com/MorpheApp/morphe-patches/commit/088487090bc59b1c6358551f4e4634f2a847e781))
* **YouTube:** Add `Reload video` patch ([#873](https://github.com/MorpheApp/morphe-patches/issues/873)) ([fad0e95](https://github.com/MorpheApp/morphe-patches/commit/fad0e95e6be8b29bd32bcec4ef518efff3bcc0eb))
* **YouTube:** Add experimental support for `21.12.521` ([#925](https://github.com/MorpheApp/morphe-patches/issues/925)) ([039e982](https://github.com/MorpheApp/morphe-patches/commit/039e9827fe22562e6b4b9c103621a04f08509fc4))

# [1.20.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.20.0-dev.2...v1.20.0-dev.3) (2026-03-19)


### Bug Fixes

* **YouTube - Swipe controls:** Swipe controls sometimes do not work ([#905](https://github.com/MorpheApp/morphe-patches/issues/905)) ([bd23813](https://github.com/MorpheApp/morphe-patches/commit/bd23813b31ade167e4b1cff3b636163c41696c49))


### Features

* **YouTube - Hide layout components:** Add "Comments carousel filter" setting ([#909](https://github.com/MorpheApp/morphe-patches/issues/909)) ([4239720](https://github.com/MorpheApp/morphe-patches/commit/42397200183b8c4b5edd6bd3d27cd8f2b6706756))
* **YouTube Music:** Add experimental support for `9.11.54` ([73eb7f1](https://github.com/MorpheApp/morphe-patches/commit/73eb7f1475161517a965c0d9ebbb0f8e67a7af93))
* **YouTube:** Add support for `20.45.36` ([c22a6df](https://github.com/MorpheApp/morphe-patches/commit/c22a6dfd07a9e1e980e5f5eb9ad22565b1da7f42))

# [1.20.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.20.0-dev.1...v1.20.0-dev.2) (2026-03-18)


### Bug Fixes

* **Settings:** Prevent duplicate dialogs on rapid preference clicks ([#900](https://github.com/MorpheApp/morphe-patches/issues/900)) ([478d97a](https://github.com/MorpheApp/morphe-patches/commit/478d97aa0856b6ebd7cbf41d5c10850295ba2fb8))
* **YouTube - Captions:** Captions disabled due to incorrect hooking ([#908](https://github.com/MorpheApp/morphe-patches/issues/908)) ([a03f245](https://github.com/MorpheApp/morphe-patches/commit/a03f24563a567a6ebd7bf55e1844ef6f2c06529c))
* **YouTube - Playback speed:** Playback speed menu opens from the feed flyout menu when `Restore old playback speed menu` is off ([#906](https://github.com/MorpheApp/morphe-patches/issues/906)) ([3bb93a9](https://github.com/MorpheApp/morphe-patches/commit/3bb93a9fd1b838593ed5e3f38314855cade17c3f))


### Features

* **YouTube Music:** Add `Miniplayer previous and next buttons` patch ([#818](https://github.com/MorpheApp/morphe-patches/issues/818)) ([7e7e08d](https://github.com/MorpheApp/morphe-patches/commit/7e7e08dbfff859890b3bce26fcc882cff1074f4a))


### Performance Improvements

* Reduce patches bundle size ([#891](https://github.com/MorpheApp/morphe-patches/issues/891)) ([74c4611](https://github.com/MorpheApp/morphe-patches/commit/74c461185ac19a3d7258f738e92f49da74721703))

# [1.20.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0...v1.20.0-dev.1) (2026-03-17)


### Bug Fixes

* **YouTube:** Advanced video quality menu does not work ([3381026](https://github.com/MorpheApp/morphe-patches/commit/3381026a63c91460243cb512554e64d0f3662a70))


### Features

* Add Import/Export settings from/to a file ([#865](https://github.com/MorpheApp/morphe-patches/issues/865)) ([098a0d8](https://github.com/MorpheApp/morphe-patches/commit/098a0d8e8993baff5e85676aae6ff5a66d615473))
* **Reddit:** Add Crowdin translations ([#882](https://github.com/MorpheApp/morphe-patches/issues/882)) ([c08ccdb](https://github.com/MorpheApp/morphe-patches/commit/c08ccdb30b460ccbe7ca70b2a5c427b05ba29a3c))

# [1.19.0](https://github.com/MorpheApp/morphe-patches/compare/v1.18.0...v1.19.0) (2026-03-16)


### Bug Fixes

* **Sanitize sharing links:** Sanitize new `is` sharing parameter ([18b1fdb](https://github.com/MorpheApp/morphe-patches/commit/18b1fdb80c76a2fc4510fc53775437f5cbfc8bd9))
* **YouTube - Alternative thumbnails:** Use correct DeArrow parameter casing ([a35a012](https://github.com/MorpheApp/morphe-patches/commit/a35a01243b2bfa674d5711f3db0a0f675f4ebc6f))
* **YouTube - Captions:** Sometimes auto captions are not disabled in Shorts ([#872](https://github.com/MorpheApp/morphe-patches/issues/872)) ([8efba43](https://github.com/MorpheApp/morphe-patches/commit/8efba43064c21652f6221954fddca436cb89e011))
* **YouTube - Change form factor:** Explore button sometimes shows in Automotive layout ([#820](https://github.com/MorpheApp/morphe-patches/issues/820)) ([2980489](https://github.com/MorpheApp/morphe-patches/commit/2980489f3c0e6c95d9fd71c8b094ae9423da2a1b))
* **YouTube - Disable Shorts resuming on startup:** Resolve patch not working on experimental versions ([#848](https://github.com/MorpheApp/morphe-patches/issues/848)) ([0b8e77a](https://github.com/MorpheApp/morphe-patches/commit/0b8e77ac56ffc637bf2186e98189857362a14438))
* **YouTube - Hide layout components:** Resolve "Hide community posts" not working when selecting a channel from subscribed channels bar in Subscriptions tab ([#816](https://github.com/MorpheApp/morphe-patches/issues/816)) ([416d4ea](https://github.com/MorpheApp/morphe-patches/commit/416d4ea2b1c7d545d5c9c6ec1af1ce58a8ff5904))
* **YouTube - Hide Shorts components:** Hide Shorts shelf hides autoplaying videos in the feed ([#788](https://github.com/MorpheApp/morphe-patches/issues/788)) ([01328e7](https://github.com/MorpheApp/morphe-patches/commit/01328e7f048949384186f0ce6fd4230f81e32702))
* **YouTube - Hide Shorts components:** Resolve "Hide 'Use this sound' button" and "Hide 'Use this template' button" breaking Shorts player ([#830](https://github.com/MorpheApp/morphe-patches/issues/830)) ([ffff45a](https://github.com/MorpheApp/morphe-patches/commit/ffff45a529f788707464eed85b7cc6c2c7e513fb))
* **YouTube - Hide Shorts components:** Resolve "Hide 'Use this sound' button" and "Hide 'Use this template' button" not working ([#815](https://github.com/MorpheApp/morphe-patches/issues/815)) ([9f2a67b](https://github.com/MorpheApp/morphe-patches/commit/9f2a67bd38ac2b8fd038197736f8db0483960c7d))
* **YouTube - Hide Shorts components:** Shorts shelves are sometimes not hidden ([#870](https://github.com/MorpheApp/morphe-patches/issues/870)) ([301d3b0](https://github.com/MorpheApp/morphe-patches/commit/301d3b0528415b56f49979ef4cf7797d034ad308))
* **YouTube - Playback speed:** Old playback speed menu does not show with experimental app targets ([d90579f](https://github.com/MorpheApp/morphe-patches/commit/d90579fd93bea9628f787df34e2bbf3302c23e85))
* **YouTube - Shorts autoplay:** Shorts do not autoplay when using older app targets ([1c6478e](https://github.com/MorpheApp/morphe-patches/commit/1c6478e02a6e2a0c795f40608c653ed115292e5e))
* **YouTube - Video quality:** Initial video quality is not overridden ([#822](https://github.com/MorpheApp/morphe-patches/issues/822)) ([753026a](https://github.com/MorpheApp/morphe-patches/commit/753026a6a11552be34b41d55f5d6c0bc728858c7))
* **YouTube:** Do not show fullscreen black gradient with 21.03+ experimental app targets ([0202d57](https://github.com/MorpheApp/morphe-patches/commit/0202d57a2cb0af94257f06163f0c8e219346ac95))
* **YouTube:** Prompt to restart app on first launch of clean install ([#874](https://github.com/MorpheApp/morphe-patches/issues/874)) ([35634b7](https://github.com/MorpheApp/morphe-patches/commit/35634b772b8a7a437d560dcd7e6bf667148957de))


### Features

* **Reddit:** Add "Disable modern home" and "Show view count", fix "Hide navigation buttons" ([#823](https://github.com/MorpheApp/morphe-patches/issues/823)) ([d7c26f2](https://github.com/MorpheApp/morphe-patches/commit/d7c26f257470b66d319bc8e156d9651552a7aac0))
* **Spoof video streams:** Update yt-dlp-ejs ([#871](https://github.com/MorpheApp/morphe-patches/issues/871)) ([0fd599a](https://github.com/MorpheApp/morphe-patches/commit/0fd599a08145c9a78d0882342095a26f6a230102))
* **Theme:** Use dynamic system accents for Material You colors ([#693](https://github.com/MorpheApp/morphe-patches/issues/693)) ([1a2e61c](https://github.com/MorpheApp/morphe-patches/commit/1a2e61ce2e64f82adc51f215ed79b8c02cf6b869))
* **YouTube - Playback speed:** Allows disabling tap and hold speed ([#819](https://github.com/MorpheApp/morphe-patches/issues/819)) ([948a8a1](https://github.com/MorpheApp/morphe-patches/commit/948a8a1c4d29b2d8855225706cb7db9c060d7a18))
* **YouTube:** Add experimental support for `21.11.480` ([d8ced89](https://github.com/MorpheApp/morphe-patches/commit/d8ced89845969bc09275ae540af82229f67799b3))

# [1.19.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0-dev.6...v1.19.0-dev.7) (2026-03-16)


### Bug Fixes

* **YouTube:** Prompt to restart app on first launch of clean install ([#874](https://github.com/MorpheApp/morphe-patches/issues/874)) ([35634b7](https://github.com/MorpheApp/morphe-patches/commit/35634b772b8a7a437d560dcd7e6bf667148957de))

# [1.19.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0-dev.5...v1.19.0-dev.6) (2026-03-16)


### Bug Fixes

* **YouTube - Captions:** Sometimes auto captions are not disabled in Shorts ([#872](https://github.com/MorpheApp/morphe-patches/issues/872)) ([8efba43](https://github.com/MorpheApp/morphe-patches/commit/8efba43064c21652f6221954fddca436cb89e011))
* **YouTube - Hide Shorts components:** Shorts shelves are sometimes not hidden ([#870](https://github.com/MorpheApp/morphe-patches/issues/870)) ([301d3b0](https://github.com/MorpheApp/morphe-patches/commit/301d3b0528415b56f49979ef4cf7797d034ad308))
* **YouTube - Playback speed:** Old playback speed menu does not show with experimental app targets ([d90579f](https://github.com/MorpheApp/morphe-patches/commit/d90579fd93bea9628f787df34e2bbf3302c23e85))


### Features

* **Spoof video streams:** Update yt-dlp-ejs ([#871](https://github.com/MorpheApp/morphe-patches/issues/871)) ([0fd599a](https://github.com/MorpheApp/morphe-patches/commit/0fd599a08145c9a78d0882342095a26f6a230102))

# [1.19.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0-dev.4...v1.19.0-dev.5) (2026-03-15)


### Bug Fixes

* **YouTube - Disable Shorts resuming on startup:** Resolve patch not working on experimental versions ([#848](https://github.com/MorpheApp/morphe-patches/issues/848)) ([0b8e77a](https://github.com/MorpheApp/morphe-patches/commit/0b8e77ac56ffc637bf2186e98189857362a14438))

# [1.19.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0-dev.3...v1.19.0-dev.4) (2026-03-15)


### Features

* **Reddit:** Add "Disable modern home" and "Show view count", fix "Hide navigation buttons" ([#823](https://github.com/MorpheApp/morphe-patches/issues/823)) ([d7c26f2](https://github.com/MorpheApp/morphe-patches/commit/d7c26f257470b66d319bc8e156d9651552a7aac0))

# [1.19.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0-dev.2...v1.19.0-dev.3) (2026-03-14)


### Bug Fixes

* **YouTube - Hide layout components:** Resolve "Hide community posts" not working when selecting a channel from subscribed channels bar in Subscriptions tab ([#816](https://github.com/MorpheApp/morphe-patches/issues/816)) ([416d4ea](https://github.com/MorpheApp/morphe-patches/commit/416d4ea2b1c7d545d5c9c6ec1af1ce58a8ff5904))
* **YouTube - Hide Shorts components:** Resolve "Hide 'Use this sound' button" and "Hide 'Use this template' button" breaking Shorts player ([#830](https://github.com/MorpheApp/morphe-patches/issues/830)) ([ffff45a](https://github.com/MorpheApp/morphe-patches/commit/ffff45a529f788707464eed85b7cc6c2c7e513fb))
* **YouTube - Video quality:** Initial video quality is not overridden ([#822](https://github.com/MorpheApp/morphe-patches/issues/822)) ([753026a](https://github.com/MorpheApp/morphe-patches/commit/753026a6a11552be34b41d55f5d6c0bc728858c7))
* **YouTube:** Do not show fullscreen black gradient with 21.03+ experimental app targets ([0202d57](https://github.com/MorpheApp/morphe-patches/commit/0202d57a2cb0af94257f06163f0c8e219346ac95))

# [1.19.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.19.0-dev.1...v1.19.0-dev.2) (2026-03-13)


### Bug Fixes

* **Sanitize sharing links:** Sanitize new `is` sharing parameter ([18b1fdb](https://github.com/MorpheApp/morphe-patches/commit/18b1fdb80c76a2fc4510fc53775437f5cbfc8bd9))
* **YouTube - Change form factor:** Explore button sometimes shows in Automotive layout ([#820](https://github.com/MorpheApp/morphe-patches/issues/820)) ([2980489](https://github.com/MorpheApp/morphe-patches/commit/2980489f3c0e6c95d9fd71c8b094ae9423da2a1b))


### Features

* **YouTube:** Add experimental support for `21.11.480` ([d8ced89](https://github.com/MorpheApp/morphe-patches/commit/d8ced89845969bc09275ae540af82229f67799b3))

# [1.19.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.18.1-dev.2...v1.19.0-dev.1) (2026-03-12)


### Bug Fixes

* **YouTube - Hide Shorts components:** Resolve "Hide 'Use this sound' button" and "Hide 'Use this template' button" not working ([#815](https://github.com/MorpheApp/morphe-patches/issues/815)) ([9f2a67b](https://github.com/MorpheApp/morphe-patches/commit/9f2a67bd38ac2b8fd038197736f8db0483960c7d))


### Features

* **Theme:** Use dynamic system accents for Material You colors ([#693](https://github.com/MorpheApp/morphe-patches/issues/693)) ([1a2e61c](https://github.com/MorpheApp/morphe-patches/commit/1a2e61ce2e64f82adc51f215ed79b8c02cf6b869))
* **YouTube - Playback speed:** Allows disabling tap and hold speed ([#819](https://github.com/MorpheApp/morphe-patches/issues/819)) ([948a8a1](https://github.com/MorpheApp/morphe-patches/commit/948a8a1c4d29b2d8855225706cb7db9c060d7a18))

## [1.18.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.18.1-dev.1...v1.18.1-dev.2) (2026-03-11)


### Bug Fixes

* **YouTube - Shorts autoplay:** Shorts do not autoplay when using older app targets ([1c6478e](https://github.com/MorpheApp/morphe-patches/commit/1c6478e02a6e2a0c795f40608c653ed115292e5e))

## [1.18.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.18.0...v1.18.1-dev.1) (2026-03-11)


### Bug Fixes

* **YouTube - Alternative thumbnails:** Use correct DeArrow parameter casing ([a35a012](https://github.com/MorpheApp/morphe-patches/commit/a35a01243b2bfa674d5711f3db0a0f675f4ebc6f))
* **YouTube - Hide Shorts components:** Hide Shorts shelf hides autoplaying videos in the feed ([#788](https://github.com/MorpheApp/morphe-patches/issues/788)) ([01328e7](https://github.com/MorpheApp/morphe-patches/commit/01328e7f048949384186f0ce6fd4230f81e32702))

# [1.18.0](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0...v1.18.0) (2026-03-10)


### Bug Fixes

* **Custom branding:** Safely clone bitmaps to prevent native recycled crash in Glide ([#732](https://github.com/MorpheApp/morphe-patches/issues/732)) ([f5ffa47](https://github.com/MorpheApp/morphe-patches/commit/f5ffa4735097f4d9aaf33c55d469fdf20fcd3d83))
* **YouTube - Hide layout components:** Replace "Hide AI comments summary" with "Sanitize category bar" ([#731](https://github.com/MorpheApp/morphe-patches/issues/731)) ([bb0debf](https://github.com/MorpheApp/morphe-patches/commit/bb0debfc6343afd2b723411a15354209a734c691))
* **YouTube - Hide player flyout menu items:** Do not hide entire flyout menu for eperimental app targets ([7407796](https://github.com/MorpheApp/morphe-patches/commit/7407796551978351a74db742715154ca8e96493e))
* **YouTube - Hide Shorts components:** Hide Shorts in search nav button ([#749](https://github.com/MorpheApp/morphe-patches/issues/749)) ([4bb1162](https://github.com/MorpheApp/morphe-patches/commit/4bb11623be6828e4e0d53afc736678c34400266f))
* **YouTube - Hide video action buttons:** Some action buttons cannot be hidden in YouTube 20.22+ ([#736](https://github.com/MorpheApp/morphe-patches/issues/736)) ([61ebf75](https://github.com/MorpheApp/morphe-patches/commit/61ebf754b8e92a072ed9721573fb26cfe468e888))


### Features

* Add `Disable Play Store updates`, `Custom branding name for Reddit`, improve `Change package name` ([#575](https://github.com/MorpheApp/morphe-patches/issues/575)) ([60a2f9e](https://github.com/MorpheApp/morphe-patches/commit/60a2f9e5b27ca126c4cce3a7207c197675f10d80))
* Add Kurmanji Crowdin translations ([2413a08](https://github.com/MorpheApp/morphe-patches/commit/2413a083ddd6c6768e5db5bd7e814107c97cb8e1))
* **Reddit:** Add experimental support for `2026.10.0` ([#762](https://github.com/MorpheApp/morphe-patches/issues/762)) ([7117724](https://github.com/MorpheApp/morphe-patches/commit/711772404d3a4cee3ac51b6837d15de2e419de3b))
* **Spoof video streams:** Default client maintenance ([#789](https://github.com/MorpheApp/morphe-patches/issues/789)) ([142367e](https://github.com/MorpheApp/morphe-patches/commit/142367e1c0ee3f03341fa5c3c0483aed7384c208))
* **YouTube Music:** Add `Enable forced miniplayer` patch ([#720](https://github.com/MorpheApp/morphe-patches/issues/720)) ([9c6f5f5](https://github.com/MorpheApp/morphe-patches/commit/9c6f5f542d47222a8d7e1873b3742647455d6bf9))
* **YouTube:** Add experimental support for `21.10.493` ([c09049d](https://github.com/MorpheApp/morphe-patches/commit/c09049d46b94b5fa0097f3a53398469356b440f1))

# [1.18.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.18.0-dev.4...v1.18.0-dev.5) (2026-03-10)


### Features

* **Spoof video streams:** Default client maintenance ([#789](https://github.com/MorpheApp/morphe-patches/issues/789)) ([142367e](https://github.com/MorpheApp/morphe-patches/commit/142367e1c0ee3f03341fa5c3c0483aed7384c208))

# [1.18.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.18.0-dev.3...v1.18.0-dev.4) (2026-03-09)


### Bug Fixes

* **YouTube - Hide layout components:** Replace "Hide AI comments summary" with "Sanitize category bar" ([#731](https://github.com/MorpheApp/morphe-patches/issues/731)) ([bb0debf](https://github.com/MorpheApp/morphe-patches/commit/bb0debfc6343afd2b723411a15354209a734c691))

# [1.18.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.18.0-dev.2...v1.18.0-dev.3) (2026-03-08)


### Bug Fixes

* **YouTube - Hide video action buttons:** Some action buttons cannot be hidden in YouTube 20.22+ ([#736](https://github.com/MorpheApp/morphe-patches/issues/736)) ([61ebf75](https://github.com/MorpheApp/morphe-patches/commit/61ebf754b8e92a072ed9721573fb26cfe468e888))

# [1.18.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.18.0-dev.1...v1.18.0-dev.2) (2026-03-07)


### Bug Fixes

* **Custom branding:** Safely clone bitmaps to prevent native recycled crash in Glide ([#732](https://github.com/MorpheApp/morphe-patches/issues/732)) ([f5ffa47](https://github.com/MorpheApp/morphe-patches/commit/f5ffa4735097f4d9aaf33c55d469fdf20fcd3d83))
* **YouTube - Hide Shorts components:** Hide Shorts in search nav button ([#749](https://github.com/MorpheApp/morphe-patches/issues/749)) ([4bb1162](https://github.com/MorpheApp/morphe-patches/commit/4bb11623be6828e4e0d53afc736678c34400266f))


### Features

* Add `Disable Play Store updates`, `Custom branding name for Reddit`, improve `Change package name` ([#575](https://github.com/MorpheApp/morphe-patches/issues/575)) ([60a2f9e](https://github.com/MorpheApp/morphe-patches/commit/60a2f9e5b27ca126c4cce3a7207c197675f10d80))
* **Reddit:** Add experimental support for `2026.10.0` ([#762](https://github.com/MorpheApp/morphe-patches/issues/762)) ([7117724](https://github.com/MorpheApp/morphe-patches/commit/711772404d3a4cee3ac51b6837d15de2e419de3b))
* **YouTube Music:** Add `Enable forced miniplayer` patch ([#720](https://github.com/MorpheApp/morphe-patches/issues/720)) ([9c6f5f5](https://github.com/MorpheApp/morphe-patches/commit/9c6f5f542d47222a8d7e1873b3742647455d6bf9))

# [1.18.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.17.1-dev.1...v1.18.0-dev.1) (2026-03-07)


### Features

* Add Kurmanji Crowdin translations ([2413a08](https://github.com/MorpheApp/morphe-patches/commit/2413a083ddd6c6768e5db5bd7e814107c97cb8e1))
* **YouTube:** Add experimental support for `21.10.493` ([c09049d](https://github.com/MorpheApp/morphe-patches/commit/c09049d46b94b5fa0097f3a53398469356b440f1))

## [1.17.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0...v1.17.1-dev.1) (2026-03-06)


### Bug Fixes

* **YouTube - Hide player flyout menu items:** Do not hide entire flyout menu for eperimental app targets ([7407796](https://github.com/MorpheApp/morphe-patches/commit/7407796551978351a74db742715154ca8e96493e))

# [1.17.0](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0...v1.17.0) (2026-03-06)


### Bug Fixes

* **Custom branding:** Do not override base app name for root installation ([8eb6d48](https://github.com/MorpheApp/morphe-patches/commit/8eb6d483a53e99eaa5aece259f0d5a27d2ef5053))
* **Spoof video streams:** Show TV client deobfuscate toast only if debugging is enabled ([b9b685c](https://github.com/MorpheApp/morphe-patches/commit/b9b685c6aac6aac59aab04f6b5b28252bc424683))
* **YouTube - Hide layout components:** Resolve "Hide Explore the podcast" not working ([#691](https://github.com/MorpheApp/morphe-patches/issues/691)) ([fd50458](https://github.com/MorpheApp/morphe-patches/commit/fd504587cacaf665816c908b5db124f7a2e7bdd1))
* **YouTube - LithoFilterPatch:** Use an encoded native byte array for buffer searching ([#654](https://github.com/MorpheApp/morphe-patches/issues/654)) ([bb5cc01](https://github.com/MorpheApp/morphe-patches/commit/bb5cc0198ee87f292795c3b03127a6a82ef72647))


### Features

* **Spoof video streams:** Add "Force player JavaScript hash" and "Player JavaScript hash" ([#722](https://github.com/MorpheApp/morphe-patches/issues/722)) ([c184dbe](https://github.com/MorpheApp/morphe-patches/commit/c184dbe74aac4f2c2f7cb74955a05063209a9f2e))
* **YouTube - Navigation bar:** Add "Hide navigation bar", "Replace Create with Settings", and "Show Settings" settings ([#688](https://github.com/MorpheApp/morphe-patches/issues/688)) ([1b87895](https://github.com/MorpheApp/morphe-patches/commit/1b8789530e9ca50f439653cb4c55ae1a031d980b))
* **YouTube Music:** Add experimental support for `9.09.52` ([e42d9c1](https://github.com/MorpheApp/morphe-patches/commit/e42d9c1bbdd6985378be47c8f9138ccdb22ead1e))
* **YouTube Music:** Add support for `8.44.54` ([113e0b3](https://github.com/MorpheApp/morphe-patches/commit/113e0b3f67a5470e000c0e15d91d0c44ab18b66d))
* **YouTube:** Add `Hide releated videos` patch ([#685](https://github.com/MorpheApp/morphe-patches/issues/685)) ([7a960e1](https://github.com/MorpheApp/morphe-patches/commit/7a960e1a9f52ee2dcaea84298a62e9e2867100b7))
* **YouTube:** Add support for `20.44.38` ([b022292](https://github.com/MorpheApp/morphe-patches/commit/b022292192de08a913429400a44003fdc2622c3c))

# [1.17.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0-dev.7...v1.17.0-dev.8) (2026-03-06)


### Features

* **Spoof video streams:** Minor improvements reflecting code review ([#730](https://github.com/MorpheApp/morphe-patches/issues/730)) ([cbfe030](https://github.com/MorpheApp/morphe-patches/commit/cbfe03035683ee6b7c205327eabcd3e515c35ec8))

# [1.17.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0-dev.6...v1.17.0-dev.7) (2026-03-06)


### Bug Fixes

* **Spoof video streams:** Show TV client deobfuscate toast only if debugging is enabled ([b9b685c](https://github.com/MorpheApp/morphe-patches/commit/b9b685c6aac6aac59aab04f6b5b28252bc424683))

# [1.17.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0-dev.5...v1.17.0-dev.6) (2026-03-05)


### Bug Fixes

* Resolve navigation bar patch not working ([9e21cfd](https://github.com/MorpheApp/morphe-patches/commit/9e21cfd1c55b880e01f3ffd9296dcdab36e2282b))

# [1.17.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0-dev.4...v1.17.0-dev.5) (2026-03-05)


### Features

* **Spoof video streams:** Add "Force player JavaScript hash" and "Player JavaScript hash" ([#722](https://github.com/MorpheApp/morphe-patches/issues/722)) ([c184dbe](https://github.com/MorpheApp/morphe-patches/commit/c184dbe74aac4f2c2f7cb74955a05063209a9f2e))
* **YouTube Music:** Add support for `8.44.54` ([113e0b3](https://github.com/MorpheApp/morphe-patches/commit/113e0b3f67a5470e000c0e15d91d0c44ab18b66d))
* **YouTube:** Add support for `20.44.38` ([b022292](https://github.com/MorpheApp/morphe-patches/commit/b022292192de08a913429400a44003fdc2622c3c))

# [1.17.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0-dev.3...v1.17.0-dev.4) (2026-03-05)


### Features

* **YouTube - Navigation bar:** Add "Hide navigation bar", "Replace Create with Settings", and "Show Settings" settings ([#688](https://github.com/MorpheApp/morphe-patches/issues/688)) ([1b87895](https://github.com/MorpheApp/morphe-patches/commit/1b8789530e9ca50f439653cb4c55ae1a031d980b))

# [1.17.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0-dev.2...v1.17.0-dev.3) (2026-03-04)


### Features

* **YouTube Music:** Add experimental support for `9.09.52` ([e42d9c1](https://github.com/MorpheApp/morphe-patches/commit/e42d9c1bbdd6985378be47c8f9138ccdb22ead1e))

# [1.17.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.17.0-dev.1...v1.17.0-dev.2) (2026-03-04)


### Bug Fixes

* **Custom branding:** Do not override base app name for root installation ([8eb6d48](https://github.com/MorpheApp/morphe-patches/commit/8eb6d483a53e99eaa5aece259f0d5a27d2ef5053))

# [1.17.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.16.1-dev.2...v1.17.0-dev.1) (2026-03-04)


### Features

* **YouTube:** Add `Hide releated videos` patch ([#685](https://github.com/MorpheApp/morphe-patches/issues/685)) ([7a960e1](https://github.com/MorpheApp/morphe-patches/commit/7a960e1a9f52ee2dcaea84298a62e9e2867100b7))

## [1.16.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.16.1-dev.1...v1.16.1-dev.2) (2026-03-03)


### Bug Fixes

* **YouTube - Hide layout components:** Resolve "Hide Explore the podcast" not working ([#691](https://github.com/MorpheApp/morphe-patches/issues/691)) ([fd50458](https://github.com/MorpheApp/morphe-patches/commit/fd504587cacaf665816c908b5db124f7a2e7bdd1))

## [1.16.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0...v1.16.1-dev.1) (2026-03-03)


### Bug Fixes

* **YouTube - LithoFilterPatch:** Use an encoded native byte array for buffer searching ([#654](https://github.com/MorpheApp/morphe-patches/issues/654)) ([bb5cc01](https://github.com/MorpheApp/morphe-patches/commit/bb5cc0198ee87f292795c3b03127a6a82ef72647))

# [1.16.0](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0...v1.16.0) (2026-03-03)


### Bug Fixes

* **YouTube - Double tap to seek:** Playback setting menu does not open when patched with CLI pre-release ([ba1784d](https://github.com/MorpheApp/morphe-patches/commit/ba1784df70cb4cbfe786e991e75bb1d8f97a6dbf))
* **YouTube - Hide end screen suggested video:** Add user dialogs to minimize user confusion ([#637](https://github.com/MorpheApp/morphe-patches/issues/637)) ([6d4af87](https://github.com/MorpheApp/morphe-patches/commit/6d4af87dd6880d6114c24815109da4149df3c3b8))
* **YouTube - Hide Shorts components:** Resolve Shorts header not being hidden ([#649](https://github.com/MorpheApp/morphe-patches/issues/649)) ([84901c2](https://github.com/MorpheApp/morphe-patches/commit/84901c261ea0808514cca24c5bd7687389acae5c))
* **YouTube Music - Change start page:** Hide Podcast option that shows an error message for users in unsupported countries ([b0a9fb6](https://github.com/MorpheApp/morphe-patches/commit/b0a9fb68ba2909889612165f1f1df5bff8fecd21))


### Features

* **YouTube - Navigation bar:** Add `Show search button` setting ([#634](https://github.com/MorpheApp/morphe-patches/issues/634)) ([b46e492](https://github.com/MorpheApp/morphe-patches/commit/b46e4923592ce5f752d6e2db156a3838c25f4f4f))
* **YouTube Music:** Add `Change header` patch ([#650](https://github.com/MorpheApp/morphe-patches/issues/650)) ([f6f03e5](https://github.com/MorpheApp/morphe-patches/commit/f6f03e55d01863f3d436e00108cc608d933ddbd9))
* **YouTube Music:** Add `Change start page` patch ([#653](https://github.com/MorpheApp/morphe-patches/issues/653)) ([bc3c1bd](https://github.com/MorpheApp/morphe-patches/commit/bc3c1bda7afd3748f3bd7c7a9831ae4bad64b80f))
* **YouTube:** Add `Open system share sheet` patch ([#625](https://github.com/MorpheApp/morphe-patches/issues/625)) ([64ff907](https://github.com/MorpheApp/morphe-patches/commit/64ff9079777a2b935886c35eae0e454984d56b52))
* **YouTube:** Add `Override YouTube Music actions` patch ([#677](https://github.com/MorpheApp/morphe-patches/issues/677)) ([904dcda](https://github.com/MorpheApp/morphe-patches/commit/904dcda3f17faf26753529351d5c4b57cdbda162))

# [1.16.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.8...v1.16.0-dev.9) (2026-03-02)


### Features

* **YouTube:** Add `Override YouTube Music actions` patch ([#677](https://github.com/MorpheApp/morphe-patches/issues/677)) ([904dcda](https://github.com/MorpheApp/morphe-patches/commit/904dcda3f17faf26753529351d5c4b57cdbda162))

# [1.16.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.7...v1.16.0-dev.8) (2026-03-01)


### Bug Fixes

* **YouTube Music - Change start page:** Hide Podcast option that shows an error message for users in unsupported countries ([b0a9fb6](https://github.com/MorpheApp/morphe-patches/commit/b0a9fb68ba2909889612165f1f1df5bff8fecd21))

# [1.16.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.6...v1.16.0-dev.7) (2026-03-01)


### Features

* **YouTube Music:** Add `Change start page` patch ([#653](https://github.com/MorpheApp/morphe-patches/issues/653)) ([bc3c1bd](https://github.com/MorpheApp/morphe-patches/commit/bc3c1bda7afd3748f3bd7c7a9831ae4bad64b80f))

# [1.16.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.5...v1.16.0-dev.6) (2026-03-01)


### Features

* **YouTube Music:** Add `Change header` patch ([#650](https://github.com/MorpheApp/morphe-patches/issues/650)) ([f6f03e5](https://github.com/MorpheApp/morphe-patches/commit/f6f03e55d01863f3d436e00108cc608d933ddbd9))

# [1.16.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.4...v1.16.0-dev.5) (2026-03-01)


### Bug Fixes

* **YouTube - Double tap to seek:** Playback setting menu does not open when patched with CLI pre-release ([ba1784d](https://github.com/MorpheApp/morphe-patches/commit/ba1784df70cb4cbfe786e991e75bb1d8f97a6dbf))

# [1.16.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.3...v1.16.0-dev.4) (2026-03-01)


### Bug Fixes

* **YouTube - Hide Shorts components:** Resolve Shorts header not being hidden ([#649](https://github.com/MorpheApp/morphe-patches/issues/649)) ([84901c2](https://github.com/MorpheApp/morphe-patches/commit/84901c261ea0808514cca24c5bd7687389acae5c))

# [1.16.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.2...v1.16.0-dev.3) (2026-02-28)


### Bug Fixes

* **YouTube - Hide end screen suggested video:** Add user dialogs to minimize user confusion ([#637](https://github.com/MorpheApp/morphe-patches/issues/637)) ([6d4af87](https://github.com/MorpheApp/morphe-patches/commit/6d4af87dd6880d6114c24815109da4149df3c3b8))

# [1.16.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.16.0-dev.1...v1.16.0-dev.2) (2026-02-27)


### Features

* **YouTube:** Add `Open system share sheet` patch ([#625](https://github.com/MorpheApp/morphe-patches/issues/625)) ([64ff907](https://github.com/MorpheApp/morphe-patches/commit/64ff9079777a2b935886c35eae0e454984d56b52))

# [1.16.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0...v1.16.0-dev.1) (2026-02-26)


### Features

* **YouTube - Navigation bar:** Add `Show search button` setting ([#634](https://github.com/MorpheApp/morphe-patches/issues/634)) ([b46e492](https://github.com/MorpheApp/morphe-patches/commit/b46e4923592ce5f752d6e2db156a3838c25f4f4f))

# [1.15.0](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0...v1.15.0) (2026-02-25)


### Bug Fixes

* **YouTube - Captions:** Captions are not available in Shorts when `Auto captions visibility` is `Never show` ([#591](https://github.com/MorpheApp/morphe-patches/issues/591)) ([e386664](https://github.com/MorpheApp/morphe-patches/commit/e3866641aa544598174e5a65ef22654410b3e5c5))
* **YouTube - Hide ads:** Hide new type of ad ([#594](https://github.com/MorpheApp/morphe-patches/issues/594)) ([87c2d24](https://github.com/MorpheApp/morphe-patches/commit/87c2d24725e4ceccb9a000c95e1b7487ff9fc6b6))
* **YouTube - Hide layout components:** Replace "Hide search suggestions" with "Hide You may like section" ([#581](https://github.com/MorpheApp/morphe-patches/issues/581)) ([29d891c](https://github.com/MorpheApp/morphe-patches/commit/29d891c63c6b5c937420f7e9fb796b530c45847f))
* **YouTube - Hide Shorts components:** Resolve hiding Shorts not working ([#610](https://github.com/MorpheApp/morphe-patches/issues/610)) ([dbef1c4](https://github.com/MorpheApp/morphe-patches/commit/dbef1c419e6b871e5587e9c6f800ff622e962c2c))


### Features

* Add `Disable QUIC protocol` patch ([#586](https://github.com/MorpheApp/morphe-patches/issues/586)) ([41fac61](https://github.com/MorpheApp/morphe-patches/commit/41fac61cfdfeb0e469f13eb8d4e54e7cb054f34d))
* **Spoof video streams:** Add `Android Reel` client ([#580](https://github.com/MorpheApp/morphe-patches/issues/580)) ([01bbde3](https://github.com/MorpheApp/morphe-patches/commit/01bbde3dfea162eedf9af36dddffaba5d750db73))
* **YouTube - Disable haptic feedback:** Add `Disable tap and hold haptics` setting ([#592](https://github.com/MorpheApp/morphe-patches/issues/592)) ([279722a](https://github.com/MorpheApp/morphe-patches/commit/279722a153573cc4d93bbe8becef31aa930cf0aa))
* **YouTube - Hide ads:** Add `Hide player popup ads` setting ([#583](https://github.com/MorpheApp/morphe-patches/issues/583)) ([82c9f29](https://github.com/MorpheApp/morphe-patches/commit/82c9f29f0903416bade9613a6672822003968893))
* **YouTube:** Add experimental support for `21.08.261` ([#582](https://github.com/MorpheApp/morphe-patches/issues/582)) ([87d3ac9](https://github.com/MorpheApp/morphe-patches/commit/87d3ac955fc1f0a777b785e4294401d6451de330))

# [1.15.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.8...v1.15.0-dev.9) (2026-02-24)


### Bug Fixes

* **YouTube - Hide ads:** Hide new type of ad ([#594](https://github.com/MorpheApp/morphe-patches/issues/594)) ([87c2d24](https://github.com/MorpheApp/morphe-patches/commit/87c2d24725e4ceccb9a000c95e1b7487ff9fc6b6))

# [1.15.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.7...v1.15.0-dev.8) (2026-02-23)


### Features

* **YouTube - Disable haptic feedback:** Add `Disable tap and hold haptics` setting ([#592](https://github.com/MorpheApp/morphe-patches/issues/592)) ([279722a](https://github.com/MorpheApp/morphe-patches/commit/279722a153573cc4d93bbe8becef31aa930cf0aa))

# [1.15.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.6...v1.15.0-dev.7) (2026-02-22)


### Bug Fixes

* **YouTube - Hide Shorts components:** Resolve hiding Shorts not working ([#610](https://github.com/MorpheApp/morphe-patches/issues/610)) ([dbef1c4](https://github.com/MorpheApp/morphe-patches/commit/dbef1c419e6b871e5587e9c6f800ff622e962c2c))

# [1.15.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.5...v1.15.0-dev.6) (2026-02-22)


### Bug Fixes

* **YouTube - Captions:** Captions are not available in Shorts when `Auto captions visibility` is `Never show` ([#591](https://github.com/MorpheApp/morphe-patches/issues/591)) ([e386664](https://github.com/MorpheApp/morphe-patches/commit/e3866641aa544598174e5a65ef22654410b3e5c5))

# [1.15.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.4...v1.15.0-dev.5) (2026-02-21)


### Features

* **YouTube - Hide ads:** Add `Hide player popup ads` setting ([#583](https://github.com/MorpheApp/morphe-patches/issues/583)) ([82c9f29](https://github.com/MorpheApp/morphe-patches/commit/82c9f29f0903416bade9613a6672822003968893))

# [1.15.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.3...v1.15.0-dev.4) (2026-02-20)


### Features

* **Spoof video streams:** Add `Android Reel` client ([#580](https://github.com/MorpheApp/morphe-patches/issues/580)) ([01bbde3](https://github.com/MorpheApp/morphe-patches/commit/01bbde3dfea162eedf9af36dddffaba5d750db73))

# [1.15.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.2...v1.15.0-dev.3) (2026-02-20)


### Features

* Add `Disable QUIC protocol` patch ([#586](https://github.com/MorpheApp/morphe-patches/issues/586)) ([41fac61](https://github.com/MorpheApp/morphe-patches/commit/41fac61cfdfeb0e469f13eb8d4e54e7cb054f34d))

# [1.15.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.15.0-dev.1...v1.15.0-dev.2) (2026-02-19)


### Bug Fixes

* **YouTube - Hide layout components:** Replace "Hide search suggestions" with "Hide You may like section" ([#581](https://github.com/MorpheApp/morphe-patches/issues/581)) ([29d891c](https://github.com/MorpheApp/morphe-patches/commit/29d891c63c6b5c937420f7e9fb796b530c45847f))

# [1.15.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0...v1.15.0-dev.1) (2026-02-19)


### Features

* **YouTube:** Add experimental support for `21.08.261` ([#582](https://github.com/MorpheApp/morphe-patches/issues/582)) ([87d3ac9](https://github.com/MorpheApp/morphe-patches/commit/87d3ac955fc1f0a777b785e4294401d6451de330))

# [1.14.0](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0...v1.14.0) (2026-02-18)


### Bug Fixes

* **Override certificate pinning:** Handle element missing from network config ([#574](https://github.com/MorpheApp/morphe-patches/issues/574)) ([7b4d015](https://github.com/MorpheApp/morphe-patches/commit/7b4d015e4fb37163127257a23de7757c5d2a1f9d))
* **Spoof video streams:** Using the TV client can cause a V8 runtime memory leak ([#564](https://github.com/MorpheApp/morphe-patches/issues/564)) ([59a4061](https://github.com/MorpheApp/morphe-patches/commit/59a4061b28b4be38c4a836a22aa2af429edad5e2))
* **YouTube:** Add experimental support for `21.07.240` ([015d66a](https://github.com/MorpheApp/morphe-patches/commit/015d66a3d9fb352f5be47af59139d736fe43b26b))


### Features

* Add `Disable DRC audio` patch ([#461](https://github.com/MorpheApp/morphe-patches/issues/461)) ([e1bd7bd](https://github.com/MorpheApp/morphe-patches/commit/e1bd7bda5bc6916c6142a9b1102d094411a5a89f))
* **Reddit:** Support version `2026.04.0` ([#545](https://github.com/MorpheApp/morphe-patches/issues/545)) ([c59a0fc](https://github.com/MorpheApp/morphe-patches/commit/c59a0fc966cbe3f94c5bf3886fd96eaa80049b98))
* Use Morphe patcher 1.1.1 ([#347](https://github.com/MorpheApp/morphe-patches/issues/347)) ([f0178c3](https://github.com/MorpheApp/morphe-patches/commit/f0178c3048a001f7aa4ffae4cccbdb756b64553c))
* **YouTube - Hide layout components:** Add "Hide channel tab filter" setting ([#540](https://github.com/MorpheApp/morphe-patches/issues/540)) ([5cca175](https://github.com/MorpheApp/morphe-patches/commit/5cca175398ade42675b3c5e082d8fbe7b1043ab1))
* **YouTube - Hide layout components:** Add "Hide feed flyout menu filter" setting ([#535](https://github.com/MorpheApp/morphe-patches/issues/535)) ([06c8215](https://github.com/MorpheApp/morphe-patches/commit/06c8215d4320b38ad5f168eabcb5fc29a6ce4640))
* **YouTube - Navigation bar:** Add "Hide voice search button" setting ([#513](https://github.com/MorpheApp/morphe-patches/issues/513)) ([b59ec4b](https://github.com/MorpheApp/morphe-patches/commit/b59ec4b9e67c00e6fa284b50de294c805c255399))

# [1.14.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.8...v1.14.0-dev.9) (2026-02-18)


### Bug Fixes

* **Override certificate pinning:** Handle element missing from network config ([#574](https://github.com/MorpheApp/morphe-patches/issues/574)) ([7b4d015](https://github.com/MorpheApp/morphe-patches/commit/7b4d015e4fb37163127257a23de7757c5d2a1f9d))

# [1.14.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.7...v1.14.0-dev.8) (2026-02-18)


### Features

* **YouTube - Navigation bar:** Add "Hide voice search button" setting ([#513](https://github.com/MorpheApp/morphe-patches/issues/513)) ([b59ec4b](https://github.com/MorpheApp/morphe-patches/commit/b59ec4b9e67c00e6fa284b50de294c805c255399))

# [1.14.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.6...v1.14.0-dev.7) (2026-02-18)


### Bug Fixes

* **Spoof video streams:** Using the TV client can cause a V8 runtime memory leak ([#564](https://github.com/MorpheApp/morphe-patches/issues/564)) ([59a4061](https://github.com/MorpheApp/morphe-patches/commit/59a4061b28b4be38c4a836a22aa2af429edad5e2))

# [1.14.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.5...v1.14.0-dev.6) (2026-02-17)


### Features

* **Reddit:** Support version `2026.04.0` ([#545](https://github.com/MorpheApp/morphe-patches/issues/545)) ([c59a0fc](https://github.com/MorpheApp/morphe-patches/commit/c59a0fc966cbe3f94c5bf3886fd96eaa80049b98))

# [1.14.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.4...v1.14.0-dev.5) (2026-02-16)


### Features

* **YouTube - Hide layout components:** Add "Hide channel tab filter" setting ([#540](https://github.com/MorpheApp/morphe-patches/issues/540)) ([5cca175](https://github.com/MorpheApp/morphe-patches/commit/5cca175398ade42675b3c5e082d8fbe7b1043ab1))

# [1.14.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.3...v1.14.0-dev.4) (2026-02-15)


### Features

* Add `Disable DRC audio` patch ([#461](https://github.com/MorpheApp/morphe-patches/issues/461)) ([e1bd7bd](https://github.com/MorpheApp/morphe-patches/commit/e1bd7bda5bc6916c6142a9b1102d094411a5a89f))

# [1.14.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.2...v1.14.0-dev.3) (2026-02-14)


### Bug Fixes

* Use jsdelivr for patch bundle hosting ([3b8b3f0](https://github.com/MorpheApp/morphe-patches/commit/3b8b3f087859ebb17b9beffd4c2ce3fdca627a4a))

# [1.14.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.14.0-dev.1...v1.14.0-dev.2) (2026-02-14)


### Features

* **YouTube - Hide layout components:** Add "Hide feed flyout menu filter" setting ([#535](https://github.com/MorpheApp/morphe-patches/issues/535)) ([06c8215](https://github.com/MorpheApp/morphe-patches/commit/06c8215d4320b38ad5f168eabcb5fc29a6ce4640))

# [1.14.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.13.1-dev.1...v1.14.0-dev.1) (2026-02-13)


### Features

* Use Morphe patcher 1.1.1 ([#347](https://github.com/MorpheApp/morphe-patches/issues/347)) ([f0178c3](https://github.com/MorpheApp/morphe-patches/commit/f0178c3048a001f7aa4ffae4cccbdb756b64553c))

## [1.13.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0...v1.13.1-dev.1) (2026-02-13)


### Bug Fixes

* **YouTube:** Add experimental support for `21.07.240` ([015d66a](https://github.com/MorpheApp/morphe-patches/commit/015d66a3d9fb352f5be47af59139d736fe43b26b))

# [1.13.0](https://github.com/MorpheApp/morphe-patches/compare/v1.12.0...v1.13.0) (2026-02-12)


### Bug Fixes

* **Spoof video streams:** Change patch to default excluded for root ([546580d](https://github.com/MorpheApp/morphe-patches/commit/546580d53c87329c53ed69809b1e1301998e9fb9))
* **YouTube - Hide layout components:** Resolve "Hide comments section in Home feed" not hiding comments ([#473](https://github.com/MorpheApp/morphe-patches/issues/473)) ([915849b](https://github.com/MorpheApp/morphe-patches/commit/915849b888ff66007af4051cdb307573047285fa))
* **YouTube - Hide layout components:** Resolve "Hide community posts" not working in search results ([#485](https://github.com/MorpheApp/morphe-patches/issues/485)) ([9083a16](https://github.com/MorpheApp/morphe-patches/commit/9083a16bd3f0098acabebee91e660f15b676d1fb))
* **YouTube - Hide layout components:** Resolve community posts sometimes showing in the player suggestions ([0d0886a](https://github.com/MorpheApp/morphe-patches/commit/0d0886a1d45cb33bb9b43542009b14849fd47849))
* **YouTube - Hide Shorts components:** Resolve hiding Shorts also hiding channel page headers ([#518](https://github.com/MorpheApp/morphe-patches/issues/518)) ([966232b](https://github.com/MorpheApp/morphe-patches/commit/966232b6e7bd610b09b2b7ce40ade735115f7748))
* **YouTube - Navigation bar:** Resolve experimental player black bar when "Disable translucent status bar" is enabled ([#464](https://github.com/MorpheApp/morphe-patches/issues/464)) ([1a9dd11](https://github.com/MorpheApp/morphe-patches/commit/1a9dd1128b13161318157e632e8e4125140081b3))
* **YouTube - Theme:** Apply dark color to 21.06+ experimental app targets ([#495](https://github.com/MorpheApp/morphe-patches/issues/495)) ([62ca1be](https://github.com/MorpheApp/morphe-patches/commit/62ca1bed0f3cc4713420b3299872ce2fb285c03e))
* **YouTube:** Resolve experimental patching of `21.06.257` ([62f68a4](https://github.com/MorpheApp/morphe-patches/commit/62f68a47a690d027f16e0d05b963ea399f6237bb))


### Features

* **YouTube - Hide layout components:** Add "Hide collapse button" and "Hide fullscreen button" settings ([#511](https://github.com/MorpheApp/morphe-patches/issues/511)) ([91f1aad](https://github.com/MorpheApp/morphe-patches/commit/91f1aadb386ccfd58cfaf0cf44601af48225f40c))
* **YouTube - Hide layout components:** Add "Hide comment prompts" setting ([#463](https://github.com/MorpheApp/morphe-patches/issues/463)) ([3ca9551](https://github.com/MorpheApp/morphe-patches/commit/3ca95517f45acbc34a25c569e975e903e6eafa1f))
* **YouTube:** Add `Disable layout updates` patch ([#493](https://github.com/MorpheApp/morphe-patches/issues/493)) ([0399388](https://github.com/MorpheApp/morphe-patches/commit/039938837397317471e676e9608e4fcbc25f46ec))

# [1.13.0-dev.15](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.14...v1.13.0-dev.15) (2026-02-12)


### Bug Fixes

* **YouTube - Hide Shorts components:** Resolve hiding Shorts also hiding channel page headers ([#518](https://github.com/MorpheApp/morphe-patches/issues/518)) ([966232b](https://github.com/MorpheApp/morphe-patches/commit/966232b6e7bd610b09b2b7ce40ade735115f7748))

# [1.13.0-dev.14](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.13...v1.13.0-dev.14) (2026-02-12)


### Bug Fixes

* **YouTube:** Resolve experimental patching of `21.06.257` ([62f68a4](https://github.com/MorpheApp/morphe-patches/commit/62f68a47a690d027f16e0d05b963ea399f6237bb))

# [1.13.0-dev.13](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.12...v1.13.0-dev.13) (2026-02-11)


### Bug Fixes

* **Spoof video streams:** Change patch to default excluded for root ([546580d](https://github.com/MorpheApp/morphe-patches/commit/546580d53c87329c53ed69809b1e1301998e9fb9))

# [1.13.0-dev.12](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.11...v1.13.0-dev.12) (2026-02-11)


### Features

* **YouTube - Hide layout components:** Add "Hide collapse button" and "Hide fullscreen button" settings ([#511](https://github.com/MorpheApp/morphe-patches/issues/511)) ([91f1aad](https://github.com/MorpheApp/morphe-patches/commit/91f1aadb386ccfd58cfaf0cf44601af48225f40c))

# [1.13.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.10...v1.13.0-dev.11) (2026-02-11)


### Bug Fixes

* **YouTube - Hide layout components:** Resolve community posts sometimes showing in the player suggestions ([0d0886a](https://github.com/MorpheApp/morphe-patches/commit/0d0886a1d45cb33bb9b43542009b14849fd47849))

# [1.13.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.9...v1.13.0-dev.10) (2026-02-10)


### Bug Fixes

* **YouTube - Theme:** Resolve theme color incorrectly applying to player overlay ([bbf94ec](https://github.com/MorpheApp/morphe-patches/commit/bbf94ec0b24a1684d8c565a5eec4bf4aefd6bf57))

# [1.13.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.8...v1.13.0-dev.9) (2026-02-10)


### Bug Fixes

* **YouTube - Theme:** Override missing 21.06+ light theme color ([a338f0f](https://github.com/MorpheApp/morphe-patches/commit/a338f0f5e5885cd04539ffd831930d1643303c26))

# [1.13.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.7...v1.13.0-dev.8) (2026-02-10)


### Bug Fixes

* **YouTube - Theme:** Show correct icon light/dark mode if using YouTube light/dark manual override ([3f5d75a](https://github.com/MorpheApp/morphe-patches/commit/3f5d75a39a7450262207bc26e43ef2c3862c9e47))

# [1.13.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.6...v1.13.0-dev.7) (2026-02-10)


### Bug Fixes

* **YouTube - Theme:** Adjust theme color replacement ([12fc228](https://github.com/MorpheApp/morphe-patches/commit/12fc2284bd719d9992045474f2b7e0cf20b976fd))

# [1.13.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.5...v1.13.0-dev.6) (2026-02-10)


### Bug Fixes

* **YouTube - Theme:** Adjust black color overrides ([b779c12](https://github.com/MorpheApp/morphe-patches/commit/b779c1245e241b2ad2929cc01767e86ea814cfc4))

# [1.13.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.4...v1.13.0-dev.5) (2026-02-10)


### Bug Fixes

* **YouTube - Theme:** Use correct theme color for settings icon ([a6d472d](https://github.com/MorpheApp/morphe-patches/commit/a6d472d35c19ad3aa3a6aa1b2544d61d4070b78e))

# [1.13.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.3...v1.13.0-dev.4) (2026-02-10)


### Bug Fixes

* **YouTube - Theme:** Apply dark color to 21.06+ experimental app targets ([#495](https://github.com/MorpheApp/morphe-patches/issues/495)) ([62ca1be](https://github.com/MorpheApp/morphe-patches/commit/62ca1bed0f3cc4713420b3299872ce2fb285c03e))


### Features

* **YouTube:** Add `Disable layout updates` patch ([#493](https://github.com/MorpheApp/morphe-patches/issues/493)) ([0399388](https://github.com/MorpheApp/morphe-patches/commit/039938837397317471e676e9608e4fcbc25f46ec))

# [1.13.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.2...v1.13.0-dev.3) (2026-02-09)


### Bug Fixes

* **YouTube - Hide layout components:** Resolve "Hide community posts" not working in search results ([#485](https://github.com/MorpheApp/morphe-patches/issues/485)) ([9083a16](https://github.com/MorpheApp/morphe-patches/commit/9083a16bd3f0098acabebee91e660f15b676d1fb))

# [1.13.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.13.0-dev.1...v1.13.0-dev.2) (2026-02-08)


### Bug Fixes

* **YouTube - Hide layout components:** Resolve "Hide comments section in Home feed" not hiding comments ([#473](https://github.com/MorpheApp/morphe-patches/issues/473)) ([915849b](https://github.com/MorpheApp/morphe-patches/commit/915849b888ff66007af4051cdb307573047285fa))

# [1.13.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.12.0...v1.13.0-dev.1) (2026-02-08)


### Bug Fixes

* **YouTube - Navigation bar:** Resolve experimental player black bar when "Disable translucent status bar" is enabled ([#464](https://github.com/MorpheApp/morphe-patches/issues/464)) ([1a9dd11](https://github.com/MorpheApp/morphe-patches/commit/1a9dd1128b13161318157e632e8e4125140081b3))


### Features

* **YouTube - Hide layout components:** Add "Hide comment prompts" setting ([#463](https://github.com/MorpheApp/morphe-patches/issues/463)) ([3ca9551](https://github.com/MorpheApp/morphe-patches/commit/3ca95517f45acbc34a25c569e975e903e6eafa1f))

# [1.12.0](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0...v1.12.0) (2026-02-07)


### Features

* **Spoof video streams:** Default client maintenance ([#448](https://github.com/MorpheApp/morphe-patches/issues/448)) ([36817a3](https://github.com/MorpheApp/morphe-patches/commit/36817a35c0f337f7d988624d8aebac418424a5f2))
* **YouTube - Spoof app version:** Remove target version `19.35.36` ([#458](https://github.com/MorpheApp/morphe-patches/issues/458)) ([8c87a2c](https://github.com/MorpheApp/morphe-patches/commit/8c87a2ccb529fa7b69dc98eaa6fcd4741e05508b))
* **YouTube - Video quality:** Add "Prioritize video quality" setting ([#449](https://github.com/MorpheApp/morphe-patches/issues/449)) ([9819078](https://github.com/MorpheApp/morphe-patches/commit/98190781e890e56eeb0771496af43ce710e8443d))

# [1.12.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.12.0-dev.1...v1.12.0-dev.2) (2026-02-07)


### Features

* **YouTube - Spoof app version:** Remove target version `19.35.36` ([#458](https://github.com/MorpheApp/morphe-patches/issues/458)) ([8c87a2c](https://github.com/MorpheApp/morphe-patches/commit/8c87a2ccb529fa7b69dc98eaa6fcd4741e05508b))

# [1.12.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0...v1.12.0-dev.1) (2026-02-07)


### Features

* **Spoof video streams:** Default client maintenance ([#448](https://github.com/MorpheApp/morphe-patches/issues/448)) ([36817a3](https://github.com/MorpheApp/morphe-patches/commit/36817a35c0f337f7d988624d8aebac418424a5f2))
* **YouTube - Video quality:** Add "Prioritize video quality" setting ([#449](https://github.com/MorpheApp/morphe-patches/issues/449)) ([9819078](https://github.com/MorpheApp/morphe-patches/commit/98190781e890e56eeb0771496af43ce710e8443d))

# [1.11.0](https://github.com/MorpheApp/morphe-patches/compare/v1.10.0...v1.11.0) (2026-02-06)


### Bug Fixes

* **Custom branding:** Show user provided custom icon everywhere ([c0433ab](https://github.com/MorpheApp/morphe-patches/commit/c0433abbd8be4797c085d6637aa4ebe8ae20f9a6))
* **YouTube - Ambient mode:** Resolve "Bypass Ambient mode restrictions" not bypassing ([#392](https://github.com/MorpheApp/morphe-patches/issues/392)) ([f5b71b2](https://github.com/MorpheApp/morphe-patches/commit/f5b71b2582f3919ee02cc58b40ce0a1a49c50b91))
* **YouTube - Hide ads:** Empty space is left when ads are hidden on tablets ([#377](https://github.com/MorpheApp/morphe-patches/issues/377)) ([da12960](https://github.com/MorpheApp/morphe-patches/commit/da12960ceb4d93490b8d41f0ea616d686eb609eb))
* **YouTube - Hide layout components:** "Hide Featured places" also hide watch history shelf ([#420](https://github.com/MorpheApp/morphe-patches/issues/420)) ([fcb6758](https://github.com/MorpheApp/morphe-patches/commit/fcb675828b3465e5ea7b88cc659bbad284f6daa1))
* **YouTube - Hide layout components:** "Hide Show more button" leaves empty space ([#422](https://github.com/MorpheApp/morphe-patches/issues/422)) ([d44862d](https://github.com/MorpheApp/morphe-patches/commit/d44862d81dc8d4b07cfcf1af7109e3dd6feae414))
* **YouTube - Hide layout components:** "Hide subscribed channels bar" leaves empty space ([#404](https://github.com/MorpheApp/morphe-patches/issues/404)) ([6b68416](https://github.com/MorpheApp/morphe-patches/commit/6b6841626d76c9679b4b9bebfa98ccab9d617fd2))
* **YouTube - Hide layout components:** Resolve "Hide subscribed channels bar" leaves empty space in landscape mode ([#421](https://github.com/MorpheApp/morphe-patches/issues/421)) ([83230a6](https://github.com/MorpheApp/morphe-patches/commit/83230a61bc7b9909af87869456580927047a5aac))
* **YouTube - Miniplayer:** Minimal miniplayer has wrong play icon ([2eddce0](https://github.com/MorpheApp/morphe-patches/commit/2eddce052761c6e7cd519ef3f57e098ca4044c3e))
* **YouTube - Navigation bar:** Rename "Switch Create with Notifications" to "Swap Create with Notifications" ([#384](https://github.com/MorpheApp/morphe-patches/issues/384)) ([6472dee](https://github.com/MorpheApp/morphe-patches/commit/6472dee35dae7d3b0ba08dd2b4348b535ce48da7))
* **YouTube - SponsorBlock:** Resolve segments not fetching on experimental app targets ([46a5d9a](https://github.com/MorpheApp/morphe-patches/commit/46a5d9a0de18231b5dd4e664d6992b36663386ee))


### Features

* Show an experimental user on the first launch of an experimental app target ([#414](https://github.com/MorpheApp/morphe-patches/issues/414)) ([d52b9c9](https://github.com/MorpheApp/morphe-patches/commit/d52b9c97bc581a67708a0c9efe01472b24e0df5d))
* **YouTube - Hide layout components:** Add "Hide comments section in Home feed" setting ([#382](https://github.com/MorpheApp/morphe-patches/issues/382)) ([b20f629](https://github.com/MorpheApp/morphe-patches/commit/b20f6294236cad32b1be29b73e761659193e464b))
* **YouTube - Hide layout components:** Add "Hide explore this course" setting ([#410](https://github.com/MorpheApp/morphe-patches/issues/410)) ([42eaca0](https://github.com/MorpheApp/morphe-patches/commit/42eaca0d04c51ff5ae30b418ed8d2b9dd771ccc5))
* **YouTube - Hide layout components:** Add "Hide Quizzes" setting ([#425](https://github.com/MorpheApp/morphe-patches/issues/425)) ([c843c32](https://github.com/MorpheApp/morphe-patches/commit/c843c32e69c078a6b8da2d1c3045f0b31898c53e))
* **YouTube - Hide Shorts components:** Add "Hide AI button" setting ([#436](https://github.com/MorpheApp/morphe-patches/issues/436)) ([eea4657](https://github.com/MorpheApp/morphe-patches/commit/eea4657c94887d4e5f1e59a0392ee1f477bcd72b))
* **YouTube - Hide Shorts components:** Add "Hide in video description" setting ([#426](https://github.com/MorpheApp/morphe-patches/issues/426)) ([7c01227](https://github.com/MorpheApp/morphe-patches/commit/7c01227b49f3feb4382e895fe768cd1cc3e6afd4))
* **YouTube - Spoof app version:** Add target version `20.28.41` ([#375](https://github.com/MorpheApp/morphe-patches/issues/375)) ([3793689](https://github.com/MorpheApp/morphe-patches/commit/3793689be432ee1643e27670e2c845d4462a80b3))
* **YouTube - Spoof app version:** Add target version `20.39.41` ([9ef0d22](https://github.com/MorpheApp/morphe-patches/commit/9ef0d2211aab5d5cb2d274678b2707ae0296499e))
* **YouTube - Video quality:** Add `Hide Premium video quality` setting ([#423](https://github.com/MorpheApp/morphe-patches/issues/423)) ([fb3cb36](https://github.com/MorpheApp/morphe-patches/commit/fb3cb368ea0bb9a05cefacf0509a35acbded5ae5))
* **YouTube:** Add `Ambient mode` patch ([#367](https://github.com/MorpheApp/morphe-patches/issues/367)) ([91a68f5](https://github.com/MorpheApp/morphe-patches/commit/91a68f5200d1f4061821aaba9f955a0fc9ec2cf1))
* **YouTube:** Add experimental support for `21.06.251` ([3872f34](https://github.com/MorpheApp/morphe-patches/commit/3872f34adf76b7b20c554d3c9af4890ba06f38e6))

# [1.11.0-dev.16](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.15...v1.11.0-dev.16) (2026-02-06)


### Bug Fixes

* **YouTube:** Add experimental support for `21.06.251` ([3872f34](https://github.com/MorpheApp/morphe-patches/commit/3872f34adf76b7b20c554d3c9af4890ba06f38e6))

# [1.11.0-dev.15](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.14...v1.11.0-dev.15) (2026-02-05)


### Features

* **YouTube - Hide Shorts components:** Add "Hide AI button" setting ([#436](https://github.com/MorpheApp/morphe-patches/issues/436)) ([eea4657](https://github.com/MorpheApp/morphe-patches/commit/eea4657c94887d4e5f1e59a0392ee1f477bcd72b))

# [1.11.0-dev.14](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.13...v1.11.0-dev.14) (2026-02-05)


### Bug Fixes

* **YouTube - SponsorBlock:** Resolve segments not fetching on experimental app targets ([46a5d9a](https://github.com/MorpheApp/morphe-patches/commit/46a5d9a0de18231b5dd4e664d6992b36663386ee))

# [1.11.0-dev.13](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.12...v1.11.0-dev.13) (2026-02-05)


### Features

* **YouTube - Hide Shorts components:** Add "Hide in video description" setting ([#426](https://github.com/MorpheApp/morphe-patches/issues/426)) ([7c01227](https://github.com/MorpheApp/morphe-patches/commit/7c01227b49f3feb4382e895fe768cd1cc3e6afd4))

# [1.11.0-dev.12](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.11...v1.11.0-dev.12) (2026-02-05)


### Features

* **YouTube - Hide layout components:** Add "Hide Quizzes" setting ([#425](https://github.com/MorpheApp/morphe-patches/issues/425)) ([c843c32](https://github.com/MorpheApp/morphe-patches/commit/c843c32e69c078a6b8da2d1c3045f0b31898c53e))

# [1.11.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.10...v1.11.0-dev.11) (2026-02-05)


### Bug Fixes

* **YouTube - Hide layout components:** `Hide Featured places` also hide watch history shelf ([#420](https://github.com/MorpheApp/morphe-patches/issues/420)) ([fcb6758](https://github.com/MorpheApp/morphe-patches/commit/fcb675828b3465e5ea7b88cc659bbad284f6daa1))

# [1.11.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.9...v1.11.0-dev.10) (2026-02-04)


### Bug Fixes

* **YouTube - Hide layout components:** `Hide Show more button` leaves empty space ([#422](https://github.com/MorpheApp/morphe-patches/issues/422)) ([d44862d](https://github.com/MorpheApp/morphe-patches/commit/d44862d81dc8d4b07cfcf1af7109e3dd6feae414))
* **YouTube - Hide layout components:** Resolve "Hide subscribed channels bar" leaves empty space in landscape mode ([#421](https://github.com/MorpheApp/morphe-patches/issues/421)) ([83230a6](https://github.com/MorpheApp/morphe-patches/commit/83230a61bc7b9909af87869456580927047a5aac))


### Features

* **YouTube - Video quality:** Add `Hide Premium video quality` setting ([#423](https://github.com/MorpheApp/morphe-patches/issues/423)) ([fb3cb36](https://github.com/MorpheApp/morphe-patches/commit/fb3cb368ea0bb9a05cefacf0509a35acbded5ae5))

# [1.11.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.8...v1.11.0-dev.9) (2026-02-04)


### Bug Fixes

* **Custom branding:** Show user provided custom icon everywhere ([c0433ab](https://github.com/MorpheApp/morphe-patches/commit/c0433abbd8be4797c085d6637aa4ebe8ae20f9a6))

# [1.11.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.7...v1.11.0-dev.8) (2026-02-04)


### Bug Fixes

* **YouTube - Ambient mode:** Resolve "Bypass Ambient mode restrictions" not bypassing ([#392](https://github.com/MorpheApp/morphe-patches/issues/392)) ([f5b71b2](https://github.com/MorpheApp/morphe-patches/commit/f5b71b2582f3919ee02cc58b40ce0a1a49c50b91))
* **YouTube - Miniplayer:** Minimal miniplayer has wrong play icon ([2eddce0](https://github.com/MorpheApp/morphe-patches/commit/2eddce052761c6e7cd519ef3f57e098ca4044c3e))

# [1.11.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.6...v1.11.0-dev.7) (2026-02-04)


### Features

* Show an experimental user dialog message on the first launch of an experimental app target ([#414](https://github.com/MorpheApp/morphe-patches/issues/414)) ([d52b9c9](https://github.com/MorpheApp/morphe-patches/commit/d52b9c97bc581a67708a0c9efe01472b24e0df5d))

# [1.11.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.5...v1.11.0-dev.6) (2026-02-03)


### Features

* **YouTube - Hide layout components:** Add "Hide explore this course" setting ([#410](https://github.com/MorpheApp/morphe-patches/issues/410)) ([42eaca0](https://github.com/MorpheApp/morphe-patches/commit/42eaca0d04c51ff5ae30b418ed8d2b9dd771ccc5))

# [1.11.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.4...v1.11.0-dev.5) (2026-02-03)


### Features

* **YouTube - Spoof app version:** Add target version `20.39.41` ([9ef0d22](https://github.com/MorpheApp/morphe-patches/commit/9ef0d2211aab5d5cb2d274678b2707ae0296499e))

# [1.11.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.3...v1.11.0-dev.4) (2026-02-03)


### Bug Fixes

* **YouTube - Hide layout components:** `Hide subscribed channels bar` leaves empty space ([#404](https://github.com/MorpheApp/morphe-patches/issues/404)) ([6b68416](https://github.com/MorpheApp/morphe-patches/commit/6b6841626d76c9679b4b9bebfa98ccab9d617fd2))

# [1.11.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.2...v1.11.0-dev.3) (2026-02-02)


### Bug Fixes

* **YouTube - Spoof app version:** Resolve experimental patching of `21.05.264` ([3d7c0e6](https://github.com/MorpheApp/morphe-patches/commit/3d7c0e6fc953775048c58a77004a4938e4df7ba8))

# [1.11.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.11.0-dev.1...v1.11.0-dev.2) (2026-02-02)


### Bug Fixes

* **YouTube - Hide ads:** Empty space is left when ads are hidden on tablets ([#377](https://github.com/MorpheApp/morphe-patches/issues/377)) ([da12960](https://github.com/MorpheApp/morphe-patches/commit/da12960ceb4d93490b8d41f0ea616d686eb609eb))
* **YouTube - Navigation bar:** Rename "Switch Create with Notifications" to "Swap Create with Notifications" ([#384](https://github.com/MorpheApp/morphe-patches/issues/384)) ([6472dee](https://github.com/MorpheApp/morphe-patches/commit/6472dee35dae7d3b0ba08dd2b4348b535ce48da7))


### Features

* **YouTube - Spoof app version:** Add target version `20.28.41` ([#375](https://github.com/MorpheApp/morphe-patches/issues/375)) ([3793689](https://github.com/MorpheApp/morphe-patches/commit/3793689be432ee1643e27670e2c845d4462a80b3))
* **YouTube:** Add `Ambient mode` patch ([#367](https://github.com/MorpheApp/morphe-patches/issues/367)) ([91a68f5](https://github.com/MorpheApp/morphe-patches/commit/91a68f5200d1f4061821aaba9f955a0fc9ec2cf1))

# [1.11.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.10.1-dev.2...v1.11.0-dev.1) (2026-02-02)


### Features

* **YouTube - Hide layout components:** Add "Hide comments section in Home feed" setting ([#382](https://github.com/MorpheApp/morphe-patches/issues/382)) ([b20f629](https://github.com/MorpheApp/morphe-patches/commit/b20f6294236cad32b1be29b73e761659193e464b))

## [1.10.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.10.1-dev.1...v1.10.1-dev.2) (2026-02-02)


### Bug Fixes

* **YouTube:** Resolve experimental `21.05.264` video description crash ([90a7fae](https://github.com/MorpheApp/morphe-patches/commit/90a7faeb5adf0eb60cb519d7ae455102236e9e2d))

## [1.10.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.10.0...v1.10.1-dev.1) (2026-02-02)


### Bug Fixes

* **YouTube:** Resolve experimental `21.05.264` Shorts crash ([eda285a](https://github.com/MorpheApp/morphe-patches/commit/eda285a096024cc262eaf2233e6fbdc4ce77d275))

# [1.10.0](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0...v1.10.0) (2026-02-02)


### Bug Fixes

* **Spoof video streams:** Spoofing does not work if the default client is not `TV` ([#373](https://github.com/MorpheApp/morphe-patches/issues/373)) ([8a40c96](https://github.com/MorpheApp/morphe-patches/commit/8a40c96bdcb13d20f2f2c2055735f9678fc92bce))
* **YouTube - Downloads:** Change Seal to hidden preset and add Seal pre-release ([#371](https://github.com/MorpheApp/morphe-patches/issues/371)) ([0406aee](https://github.com/MorpheApp/morphe-patches/commit/0406aee91b0bf14430ce33dc39d3a06b4434ce0c))
* **YouTube - Hide ads:** Fix "Hide YouTube Premium promotions" hiding YouTube Doodles ([#370](https://github.com/MorpheApp/morphe-patches/issues/370)) ([61860b7](https://github.com/MorpheApp/morphe-patches/commit/61860b7784a2ad4946fd71fb211ae2d2c56d4d1b))


### Features

* **Spoof video streams:** Default client maintenance ([#340](https://github.com/MorpheApp/morphe-patches/issues/340)) ([d72cfbe](https://github.com/MorpheApp/morphe-patches/commit/d72cfbe5ccad91ca4527a23da9aa8a55bfb161f1))
* **YouTube - Captions:** Add "Set caption cookies" and "Fix transcript" settings ([#341](https://github.com/MorpheApp/morphe-patches/issues/341)) ([0e50fc7](https://github.com/MorpheApp/morphe-patches/commit/0e50fc7de9b667f6f9b1e61cec2cfd2f6011e126))
* **YouTube - Downloads:** Add "Seal Plus" as a hidden preset downloader ([#357](https://github.com/MorpheApp/morphe-patches/issues/357)) ([077128c](https://github.com/MorpheApp/morphe-patches/commit/077128c9115ae0f168c9710fc2d1bb8f1a5ee236))
* **YouTube - Hide layout components:** Add "Hide course progress" setting ([#351](https://github.com/MorpheApp/morphe-patches/issues/351)) ([4a83b54](https://github.com/MorpheApp/morphe-patches/commit/4a83b54c292a66f5ca2825c0f4a45a5650c528d4))
* **YouTube:** Add experimental support for `21.05.264` ([#335](https://github.com/MorpheApp/morphe-patches/issues/335)) ([a3f1a5c](https://github.com/MorpheApp/morphe-patches/commit/a3f1a5c1a6fc21d2d6b1e30a600c0d5eaf0c2bf5))

# [1.10.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.10.0-dev.5...v1.10.0-dev.6) (2026-02-02)


### Bug Fixes

* **YouTube - Downloads:** Change Seal to hidden preset and add Seal pre-release ([#371](https://github.com/MorpheApp/morphe-patches/issues/371)) ([0406aee](https://github.com/MorpheApp/morphe-patches/commit/0406aee91b0bf14430ce33dc39d3a06b4434ce0c))
* **YouTube - Hide ads:** Fix "Hide YouTube Premium promotions" hiding YouTube Doodles ([#370](https://github.com/MorpheApp/morphe-patches/issues/370)) ([61860b7](https://github.com/MorpheApp/morphe-patches/commit/61860b7784a2ad4946fd71fb211ae2d2c56d4d1b))


### Features

* **YouTube:** Add experimental support for `21.05.264` ([#335](https://github.com/MorpheApp/morphe-patches/issues/335)) ([a3f1a5c](https://github.com/MorpheApp/morphe-patches/commit/a3f1a5c1a6fc21d2d6b1e30a600c0d5eaf0c2bf5))

# [1.10.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.10.0-dev.4...v1.10.0-dev.5) (2026-02-02)


### Bug Fixes

* **Spoof video streams:** Spoofing does not work if the default client is not `TV` ([#373](https://github.com/MorpheApp/morphe-patches/issues/373)) ([8a40c96](https://github.com/MorpheApp/morphe-patches/commit/8a40c96bdcb13d20f2f2c2055735f9678fc92bce))

# [1.10.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.10.0-dev.3...v1.10.0-dev.4) (2026-02-01)


### Features

* **YouTube - Downloads:** Add "Seal Plus" as a hidden preset downloader ([#357](https://github.com/MorpheApp/morphe-patches/issues/357)) ([077128c](https://github.com/MorpheApp/morphe-patches/commit/077128c9115ae0f168c9710fc2d1bb8f1a5ee236))

# [1.10.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.10.0-dev.2...v1.10.0-dev.3) (2026-02-01)


### Features

* **YouTube - Hide layout components:** Add "Hide course progress" setting ([#351](https://github.com/MorpheApp/morphe-patches/issues/351)) ([4a83b54](https://github.com/MorpheApp/morphe-patches/commit/4a83b54c292a66f5ca2825c0f4a45a5650c528d4))

# [1.10.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.10.0-dev.1...v1.10.0-dev.2) (2026-02-01)


### Features

* **YouTube - Captions:** Add "Set caption cookies" and "Fix transcript" settings ([#341](https://github.com/MorpheApp/morphe-patches/issues/341)) ([0e50fc7](https://github.com/MorpheApp/morphe-patches/commit/0e50fc7de9b667f6f9b1e61cec2cfd2f6011e126))

# [1.10.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0...v1.10.0-dev.1) (2026-02-01)


### Features

* **Spoof video streams:** Default client maintenance ([#340](https://github.com/MorpheApp/morphe-patches/issues/340)) ([d72cfbe](https://github.com/MorpheApp/morphe-patches/commit/d72cfbe5ccad91ca4527a23da9aa8a55bfb161f1))

# [1.9.0](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0...v1.9.0) (2026-01-31)


### Bug Fixes

* **YouTube:** Show a more descriptive error message if attempting to patch a region specific APKM that does not contain languages for all regions ([0f2700d](https://github.com/MorpheApp/morphe-patches/commit/0f2700db110a2e8d041e1213e0d95ed50f6d54ac))


### Features

* **Reddit:** Add ability to hide Games button on nav bar ([#315](https://github.com/MorpheApp/morphe-patches/issues/315)) ([07b9fc2](https://github.com/MorpheApp/morphe-patches/commit/07b9fc250b24c0219e8f650883f3c22ea7dfa23f))
* **Reddit:** Add support for Reddit `2026.04.0` ([#276](https://github.com/MorpheApp/morphe-patches/issues/276)) ([0203cf6](https://github.com/MorpheApp/morphe-patches/commit/0203cf62d7214fe79b42a804fc51111e805f2afd))
* **YouTube - Hide layout components:** Add "Hide Latest videos button" setting ([#305](https://github.com/MorpheApp/morphe-patches/issues/305)) ([aed3e34](https://github.com/MorpheApp/morphe-patches/commit/aed3e343885732bb86647ddf626efdc173ca2ed6))
* **YouTube - Hide layout components:** Add "Hide live chat replay button" setting ([#330](https://github.com/MorpheApp/morphe-patches/issues/330)) ([7a6c697](https://github.com/MorpheApp/morphe-patches/commit/7a6c69791cafc9247a55ac4c6ad30dcabba96d70))
* **YouTube - Hide layout components:** Add "Hide video title" setting ([#309](https://github.com/MorpheApp/morphe-patches/issues/309)) ([2c7a973](https://github.com/MorpheApp/morphe-patches/commit/2c7a973d73644d81da1dd4a82a3b10056863ac70))
* **YouTube - Navigation bar:** Add settings to hide toolbar buttons ([#298](https://github.com/MorpheApp/morphe-patches/issues/298)) ([73fd722](https://github.com/MorpheApp/morphe-patches/commit/73fd722c0d76148323ae60ddc64ae05c8d95b5d4))
* **YouTube:** Add `Restore old search filters` patch ([#285](https://github.com/MorpheApp/morphe-patches/issues/285)) ([c088723](https://github.com/MorpheApp/morphe-patches/commit/c0887234612bb24ad8184fe211af14cf0640d8ec))

# [1.9.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.10...v1.9.0-dev.11) (2026-01-30)


### Bug Fixes

* **Reddit:** Change recommended app target to `2026.03.0` ([7a5cda5](https://github.com/MorpheApp/morphe-patches/commit/7a5cda571d4ec5e6324cc2905d0c8dec3b0c8f52))

# [1.9.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.9...v1.9.0-dev.10) (2026-01-30)


### Features

* **YouTube - Hide layout components:** Add "Hide live chat replay button" setting ([#330](https://github.com/MorpheApp/morphe-patches/issues/330)) ([7a6c697](https://github.com/MorpheApp/morphe-patches/commit/7a6c69791cafc9247a55ac4c6ad30dcabba96d70))

# [1.9.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.8...v1.9.0-dev.9) (2026-01-29)


### Bug Fixes

* **Reddit:** Handle navigation bar buttons hidden behind feature flags ([#328](https://github.com/MorpheApp/morphe-patches/issues/328)) ([2b8c337](https://github.com/MorpheApp/morphe-patches/commit/2b8c33702293acfbd859cdfbdf1342e5f9e96bc2))

# [1.9.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.7...v1.9.0-dev.8) (2026-01-29)


### Features

* **YouTube - Hide layout components:** Add "Hide Latest videos button" setting ([#305](https://github.com/MorpheApp/morphe-patches/issues/305)) ([aed3e34](https://github.com/MorpheApp/morphe-patches/commit/aed3e343885732bb86647ddf626efdc173ca2ed6))

# [1.9.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.6...v1.9.0-dev.7) (2026-01-28)


### Bug Fixes

* **Reddit:** Fix Recently Visited Shelf patch not being usable ([#316](https://github.com/MorpheApp/morphe-patches/issues/316)) ([49bab2b](https://github.com/MorpheApp/morphe-patches/commit/49bab2becde7f1adf593266b88abba0b876925ba))


### Features

* **Reddit:** Add ability to hide Games button on nav bar ([#315](https://github.com/MorpheApp/morphe-patches/issues/315)) ([07b9fc2](https://github.com/MorpheApp/morphe-patches/commit/07b9fc250b24c0219e8f650883f3c22ea7dfa23f))

# [1.9.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.5...v1.9.0-dev.6) (2026-01-27)


### Features

* **Reddit:** Add support for Reddit `2026.04.0` ([#276](https://github.com/MorpheApp/morphe-patches/issues/276)) ([0203cf6](https://github.com/MorpheApp/morphe-patches/commit/0203cf62d7214fe79b42a804fc51111e805f2afd))

# [1.9.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.4...v1.9.0-dev.5) (2026-01-27)


### Features

* **YouTube - Hide layout components:** Add "Hide video title" setting ([#309](https://github.com/MorpheApp/morphe-patches/issues/309)) ([2c7a973](https://github.com/MorpheApp/morphe-patches/commit/2c7a973d73644d81da1dd4a82a3b10056863ac70))

# [1.9.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.3...v1.9.0-dev.4) (2026-01-27)


### Bug Fixes

* **YouTube:** Consolidate toolbar "Hide cast button" into existing hide cast button setting ([c22a2a1](https://github.com/MorpheApp/morphe-patches/commit/c22a2a1c7a74b6370bad7b47ce79cab9b49846ad))

# [1.9.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.2...v1.9.0-dev.3) (2026-01-27)


### Bug Fixes

* **YouTube:** Show a more descriptive error message if attempting to patch a region specific APKM that does not contain languages for all regions ([0f2700d](https://github.com/MorpheApp/morphe-patches/commit/0f2700db110a2e8d041e1213e0d95ed50f6d54ac))

# [1.9.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.9.0-dev.1...v1.9.0-dev.2) (2026-01-27)


### Features

* **YouTube - Navigation bar:** Add settings to hide toolbar buttons ([#298](https://github.com/MorpheApp/morphe-patches/issues/298)) ([73fd722](https://github.com/MorpheApp/morphe-patches/commit/73fd722c0d76148323ae60ddc64ae05c8d95b5d4))

# [1.9.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0...v1.9.0-dev.1) (2026-01-26)


### Features

* **YouTube:** Add `Restore old search filters` patch ([#285](https://github.com/MorpheApp/morphe-patches/issues/285)) ([c088723](https://github.com/MorpheApp/morphe-patches/commit/c0887234612bb24ad8184fe211af14cf0640d8ec))

# [1.8.0](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0...v1.8.0) (2026-01-25)


### Bug Fixes

* **Custom branding:** Resolve background playback crash with custom branded root installation ([3039650](https://github.com/MorpheApp/morphe-patches/commit/3039650460fdd2731b9ac1622127aa04a2b8a322))
* **Spoof video streams:** Disable client flag that breaks playback with experimental app targets ([8cace8b](https://github.com/MorpheApp/morphe-patches/commit/8cace8b442d32c07b2fcd4da3b596497608e626c))
* **YouTube - Hide ads:** `Hide video ads` does not hide Shorts ads ([#260](https://github.com/MorpheApp/morphe-patches/issues/260)) ([9078d92](https://github.com/MorpheApp/morphe-patches/commit/9078d92c444d1d6abe095a3a694a518e7fb078e9))
* **YouTube - Hide Shorts components:** Fix "Hide sound metadata label" hiding other components ([#277](https://github.com/MorpheApp/morphe-patches/issues/277)) ([b55ebe4](https://github.com/MorpheApp/morphe-patches/commit/b55ebe4f0fac71905ce8c02185a174c661627b70))
* **YouTube:** Replace themed like animation applied from wrong version due to server-side fault ([#261](https://github.com/MorpheApp/morphe-patches/issues/261)) ([556dbb6](https://github.com/MorpheApp/morphe-patches/commit/556dbb68839d5c73dea20e23079d91b20f02d109))


### Features

* **Enable debugging:** Allow overriding String/long/double flags in debug flag manager ([11b61b5](https://github.com/MorpheApp/morphe-patches/commit/11b61b5a509a4d659e69e9cfd9c3cdcad70dd9cb))
* **YouTube Music:** Support version `8.40.54` ([639819d](https://github.com/MorpheApp/morphe-patches/commit/639819de0bec5f06fee4ff11c33f149521117d2b))
* **YouTube:** Add experimental support for `21.04.221` ([ba2e581](https://github.com/MorpheApp/morphe-patches/commit/ba2e581bfbe1ebe9fb10fa49b33df43726c966f2))
* **YouTube:** Support version `20.40.45` ([9514870](https://github.com/MorpheApp/morphe-patches/commit/9514870fba460f21627ebfd6f849510c01321d91))

# [1.8.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.8...v1.8.0-dev.9) (2026-01-24)


### Bug Fixes

* **Custom branding:** Resolve background playback crash with custom branded root installation ([3039650](https://github.com/MorpheApp/morphe-patches/commit/3039650460fdd2731b9ac1622127aa04a2b8a322))

# [1.8.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.7...v1.8.0-dev.8) (2026-01-24)


### Bug Fixes

* **YouTube - Hide Shorts components:** Fix "Hide sound metadata label" hiding other components ([#277](https://github.com/MorpheApp/morphe-patches/issues/277)) ([b55ebe4](https://github.com/MorpheApp/morphe-patches/commit/b55ebe4f0fac71905ce8c02185a174c661627b70))

# [1.8.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.6...v1.8.0-dev.7) (2026-01-24)


### Bug Fixes

* **YouTube:** Replace themed like animation applied from wrong version due to server-side fault ([#261](https://github.com/MorpheApp/morphe-patches/issues/261)) ([556dbb6](https://github.com/MorpheApp/morphe-patches/commit/556dbb68839d5c73dea20e23079d91b20f02d109))

# [1.8.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.5...v1.8.0-dev.6) (2026-01-24)


### Bug Fixes

* **YouTube - Hide ads:** `Hide video ads` does not hide Shorts ads ([#260](https://github.com/MorpheApp/morphe-patches/issues/260)) ([9078d92](https://github.com/MorpheApp/morphe-patches/commit/9078d92c444d1d6abe095a3a694a518e7fb078e9))

# [1.8.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.4...v1.8.0-dev.5) (2026-01-24)


### Features

* Skip publishing to GitHub packages ([#275](https://github.com/MorpheApp/morphe-patches/issues/275)) ([71b7db4](https://github.com/MorpheApp/morphe-patches/commit/71b7db4d38ac44ce6d0b9de111599485e1de015a))

# [1.8.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.3...v1.8.0-dev.4) (2026-01-24)


### Features

* **Enable debugging:** Allow overriding String/long/double flags in debug flag manager ([11b61b5](https://github.com/MorpheApp/morphe-patches/commit/11b61b5a509a4d659e69e9cfd9c3cdcad70dd9cb))

# [1.8.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.2...v1.8.0-dev.3) (2026-01-23)


### Bug Fixes

* **Spoof video streams:** Disable client flag that breaks playback with experimental app targets ([8cace8b](https://github.com/MorpheApp/morphe-patches/commit/8cace8b442d32c07b2fcd4da3b596497608e626c))

# [1.8.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.8.0-dev.1...v1.8.0-dev.2) (2026-01-23)


### Features

* **YouTube:** Add experimental support for `21.04.221` ([ba2e581](https://github.com/MorpheApp/morphe-patches/commit/ba2e581bfbe1ebe9fb10fa49b33df43726c966f2))

# [1.8.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0...v1.8.0-dev.1) (2026-01-23)


### Features

* **YouTube Music:** Support version `8.40.54` ([639819d](https://github.com/MorpheApp/morphe-patches/commit/639819de0bec5f06fee4ff11c33f149521117d2b))
* **YouTube:** Support version `20.40.45` ([9514870](https://github.com/MorpheApp/morphe-patches/commit/9514870fba460f21627ebfd6f849510c01321d91))

# [1.7.0](https://github.com/MorpheApp/morphe-patches/compare/v1.6.0...v1.7.0) (2026-01-22)


### Features

* **Spoof video streams:** Default client maintenance ([#249](https://github.com/MorpheApp/morphe-patches/issues/249)) ([ea01bfc](https://github.com/MorpheApp/morphe-patches/commit/ea01bfca9afdf0592f0486795047ef5b0abcb5ff))
* **YouTube - SponsorBlock:** Show skip button if player overlay controls are active ([#243](https://github.com/MorpheApp/morphe-patches/issues/243)) ([9f90d0e](https://github.com/MorpheApp/morphe-patches/commit/9f90d0e386d8518ebd027a469ad72b52808048ab))
* **YouTube - Theme:** Add "Hide splash screen" setting ([#245](https://github.com/MorpheApp/morphe-patches/issues/245)) ([d8ae327](https://github.com/MorpheApp/morphe-patches/commit/d8ae327bc74b23237ef877f352828c6c7c97ea53))
* **YouTube Music:** Add experimental support for `9.03.52` ([80c0198](https://github.com/MorpheApp/morphe-patches/commit/80c019889f51a1cf44a1e4f73da008a6b513ed90))
* **YouTube:** Add experimental support for `21.03.34` ([#233](https://github.com/MorpheApp/morphe-patches/issues/233)) ([4b9d7a6](https://github.com/MorpheApp/morphe-patches/commit/4b9d7a6c8769e87e80727aa64dbc7d761513a259))

# [1.7.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0-dev.6...v1.7.0-dev.7) (2026-01-22)


### Bug Fixes

* **YouTube - SponsorBlock:** Do not show context toast when auto skipping videos in the feed ([17d8bd0](https://github.com/MorpheApp/morphe-patches/commit/17d8bd0b90d11193c7a9f3411f119ddf1aa058bd))

# [1.7.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0-dev.5...v1.7.0-dev.6) (2026-01-22)


### Features

* **Spoof video streams:** Default client maintenance ([#249](https://github.com/MorpheApp/morphe-patches/issues/249)) ([ea01bfc](https://github.com/MorpheApp/morphe-patches/commit/ea01bfca9afdf0592f0486795047ef5b0abcb5ff))

# [1.7.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0-dev.4...v1.7.0-dev.5) (2026-01-21)


### Bug Fixes

* **YouTube - SponsorBlock:** Show correct nested skip segment in overlay controls when seeking to an earlier video time ([1e13236](https://github.com/MorpheApp/morphe-patches/commit/1e1323670c1265466532919424c52016cd661492))

# [1.7.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0-dev.3...v1.7.0-dev.4) (2026-01-21)


### Features

* **YouTube Music:** Add experimental support for `9.03.52` ([80c0198](https://github.com/MorpheApp/morphe-patches/commit/80c019889f51a1cf44a1e4f73da008a6b513ed90))

# [1.7.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0-dev.2...v1.7.0-dev.3) (2026-01-20)


### Features

* **YouTube - Theme:** Add "Hide splash screen" setting ([#245](https://github.com/MorpheApp/morphe-patches/issues/245)) ([d8ae327](https://github.com/MorpheApp/morphe-patches/commit/d8ae327bc74b23237ef877f352828c6c7c97ea53))

# [1.7.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.7.0-dev.1...v1.7.0-dev.2) (2026-01-19)


### Features

* **YouTube - SponsorBlock:** Show skip button if player overlay controls are active ([#243](https://github.com/MorpheApp/morphe-patches/issues/243)) ([9f90d0e](https://github.com/MorpheApp/morphe-patches/commit/9f90d0e386d8518ebd027a469ad72b52808048ab))

# [1.7.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.6.0...v1.7.0-dev.1) (2026-01-18)


### Features

* **YouTube:** Add experimental support for `21.03.34` ([#233](https://github.com/MorpheApp/morphe-patches/issues/233)) ([4b9d7a6](https://github.com/MorpheApp/morphe-patches/commit/4b9d7a6c8769e87e80727aa64dbc7d761513a259))

# [1.6.0](https://github.com/MorpheApp/morphe-patches/compare/v1.5.0...v1.6.0) (2026-01-17)


### Bug Fixes

* **YouTube - Hide Shorts components:** Hide new type of sound metadata label ([35931e6](https://github.com/MorpheApp/morphe-patches/commit/35931e6ddf287790c6a405b58e7b7d46c718bb0a))


### Features

* **YouTube - Hide layout components:** Add "Hide subscribed channels bar" setting ([#226](https://github.com/MorpheApp/morphe-patches/issues/226)) ([bbaad3a](https://github.com/MorpheApp/morphe-patches/commit/bbaad3af85b43a7addc0e984a5a13e12986f6263))
* **YouTube Music:** Add experimental support for `9.02.50` ([977cb8b](https://github.com/MorpheApp/morphe-patches/commit/977cb8bfcdfb193b035b62ae2660d20eccdc6702))
* **YouTube:** Add `Hide autoplay preview` patch ([#218](https://github.com/MorpheApp/morphe-patches/issues/218)) ([4d105bd](https://github.com/MorpheApp/morphe-patches/commit/4d105bd3d1a54ad0c59520156b0d88c68ea007ae))

# [1.6.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.6.0-dev.3...v1.6.0-dev.4) (2026-01-16)


### Features

* **YouTube - Hide layout components:** Add "Hide subscribed channels bar" setting ([#226](https://github.com/MorpheApp/morphe-patches/issues/226)) ([bbaad3a](https://github.com/MorpheApp/morphe-patches/commit/bbaad3af85b43a7addc0e984a5a13e12986f6263))

# [1.6.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.6.0-dev.2...v1.6.0-dev.3) (2026-01-16)


### Features

* **YouTube:** Add `Hide autoplay preview` patch ([#218](https://github.com/MorpheApp/morphe-patches/issues/218)) ([4d105bd](https://github.com/MorpheApp/morphe-patches/commit/4d105bd3d1a54ad0c59520156b0d88c68ea007ae)), closes [/github.com/inotia00/revanced-patches/blob/revanced-extended/patches/src/main/kotlin/app/revanced/patches/youtube/player/fullscreen/FullscreenComponentsPatch.kt#L155-L170](https://github.com//github.com/inotia00/revanced-patches/blob/revanced-extended/patches/src/main/kotlin/app/revanced/patches/youtube/player/fullscreen/FullscreenComponentsPatch.kt/issues/L155-L170)

# [1.6.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.6.0-dev.1...v1.6.0-dev.2) (2026-01-16)


### Bug Fixes

* **YouTube - Hide Shorts components:** Hide new type of sound metadata label ([35931e6](https://github.com/MorpheApp/morphe-patches/commit/35931e6ddf287790c6a405b58e7b7d46c718bb0a))

# [1.6.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.5.0...v1.6.0-dev.1) (2026-01-16)


### Features

* **YouTube Music:** Add experimental support for `9.02.50` ([977cb8b](https://github.com/MorpheApp/morphe-patches/commit/977cb8bfcdfb193b035b62ae2660d20eccdc6702))

# [1.5.0](https://github.com/MorpheApp/morphe-patches/compare/v1.4.0...v1.5.0) (2026-01-15)


### Features

* **YouTube - Navigation buttons:** Add setting to use narrow navigation bar buttons ([1074c6a](https://github.com/MorpheApp/morphe-patches/commit/1074c6a6f70199ef8173524246be23bddcbb46d2))
* **YouTube:** Add "Hide search box trending results" setting ([#40](https://github.com/MorpheApp/morphe-patches/issues/40)) ([dc1a898](https://github.com/MorpheApp/morphe-patches/commit/dc1a898924f1cf4eaa94a29e967d7e966ae7a243))

# [1.5.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.5.0-dev.1...v1.5.0-dev.2) (2026-01-13)


### Features

* **YouTube:** Add "Hide search box trending results" setting ([#40](https://github.com/MorpheApp/morphe-patches/issues/40)) ([dc1a898](https://github.com/MorpheApp/morphe-patches/commit/dc1a898924f1cf4eaa94a29e967d7e966ae7a243))

# [1.5.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.4.0...v1.5.0-dev.1) (2026-01-12)


### Features

* **YouTube - Navigation buttons:** Add setting to use narrow navigation bar buttons ([1074c6a](https://github.com/MorpheApp/morphe-patches/commit/1074c6a6f70199ef8173524246be23bddcbb46d2))

# [1.4.0](https://github.com/MorpheApp/morphe-patches/compare/v1.3.2...v1.4.0) (2026-01-12)


### Bug Fixes

* **YouTube - Hide ads:** Hide new type of player ad ([#187](https://github.com/MorpheApp/morphe-patches/issues/187)) ([4702043](https://github.com/MorpheApp/morphe-patches/commit/4702043ea35fb3b1439ad30406b41179d019f2a1))


### Features

* **YouTube:** Add `Double tap to seek` patch ([1a57a34](https://github.com/MorpheApp/morphe-patches/commit/1a57a34734d31b803820b0a530e0c57c50963f63))

# [1.4.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.4.0-dev.2...v1.4.0-dev.3) (2026-01-12)


### Bug Fixes

* Remove restrictions on AGP due to the use of hardcoded registers in LithoFilterPatch ([#41](https://github.com/MorpheApp/morphe-patches/issues/41)) ([223ed8f](https://github.com/MorpheApp/morphe-patches/commit/223ed8f78561d8cb046d371baed9646b4e8138f3))

# [1.4.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.4.0-dev.1...v1.4.0-dev.2) (2026-01-11)


### Bug Fixes

* **YouTube - Hide ads:** Hide new type of player ad ([#187](https://github.com/MorpheApp/morphe-patches/issues/187)) ([4702043](https://github.com/MorpheApp/morphe-patches/commit/4702043ea35fb3b1439ad30406b41179d019f2a1))

# [1.4.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.3.2...v1.4.0-dev.1) (2026-01-11)


### Features

* **YouTube:** Add `Double tap to seek` patch ([1a57a34](https://github.com/MorpheApp/morphe-patches/commit/1a57a34734d31b803820b0a530e0c57c50963f63))

## [1.3.2](https://github.com/MorpheApp/morphe-patches/compare/v1.3.1...v1.3.2) (2026-01-10)


### Bug Fixes

* **Custom branding:** Resolve startup crash if custom branding is excluded in expert mode ([3f2d733](https://github.com/MorpheApp/morphe-patches/commit/3f2d7338f587ef8a4ff8331621d6e68dd160d746))

## [1.3.2-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.3.1...v1.3.2-dev.1) (2026-01-10)


### Bug Fixes

* **Custom branding:** Resolve startup crash if custom branding is excluded in expert mode ([3f2d733](https://github.com/MorpheApp/morphe-patches/commit/3f2d7338f587ef8a4ff8331621d6e68dd160d746))

## [1.3.1](https://github.com/MorpheApp/morphe-patches/compare/v1.3.0...v1.3.1) (2026-01-10)


### Bug Fixes

* **Custom branding:** Default to user‑provided icon and name when provided ([eef8798](https://github.com/MorpheApp/morphe-patches/commit/eef8798602ebf805eb3cff08e010025a0a8e93ab))
* **YouTube - Auto captions:** Cannot disable auto captions with 20.21 app target ([#145](https://github.com/MorpheApp/morphe-patches/issues/145)) ([dc43944](https://github.com/MorpheApp/morphe-patches/commit/dc43944adbcef5aa4754ca3d1fecd07a502277f3))
* **YouTube - Hide end screen cards:** Resolve patching `20.31.42` ([3033432](https://github.com/MorpheApp/morphe-patches/commit/3033432644fc986930f4eec51d4fc233f6389c23))
* **YouTube - Hide layout components:** Fix "Hide ticket shelf" not working when "Hide horizontal shelves" is off ([#124](https://github.com/MorpheApp/morphe-patches/issues/124)) ([84eeb0d](https://github.com/MorpheApp/morphe-patches/commit/84eeb0d9f949657a7d468d9babfaca37d03b0d78))

## [1.3.1-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.3.1-dev.3...v1.3.1-dev.4) (2026-01-09)


### Bug Fixes

* **YouTube - Hide layout components:** Fix "Hide ticket shelf" not working when "Hide horizontal shelves" is off ([#124](https://github.com/MorpheApp/morphe-patches/issues/124)) ([84eeb0d](https://github.com/MorpheApp/morphe-patches/commit/84eeb0d9f949657a7d468d9babfaca37d03b0d78))

## [1.3.1-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.3.1-dev.2...v1.3.1-dev.3) (2026-01-09)


### Bug Fixes

* **YouTube - Auto captions:** Cannot disable auto captions with 20.21 app target ([#145](https://github.com/MorpheApp/morphe-patches/issues/145)) ([dc43944](https://github.com/MorpheApp/morphe-patches/commit/dc43944adbcef5aa4754ca3d1fecd07a502277f3))

## [1.3.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.3.1-dev.1...v1.3.1-dev.2) (2026-01-09)


### Bug Fixes

* **YouTube - Hide end screen cards:** Resolve patching `20.31.42` ([3033432](https://github.com/MorpheApp/morphe-patches/commit/3033432644fc986930f4eec51d4fc233f6389c23))

## [1.3.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.3.0...v1.3.1-dev.1) (2026-01-09)


### Bug Fixes

* **Custom branding:** Default to user‑provided icon and name when provided ([eef8798](https://github.com/MorpheApp/morphe-patches/commit/eef8798602ebf805eb3cff08e010025a0a8e93ab))

# [1.3.0](https://github.com/MorpheApp/morphe-patches/compare/v1.2.0...v1.3.0) (2026-01-09)


### Bug Fixes

* **Spoof video streams:** Change default client to VR 1.47.48 ([#116](https://github.com/MorpheApp/morphe-patches/issues/116)) ([2bc5775](https://github.com/MorpheApp/morphe-patches/commit/2bc577554f54fd2ecfcd682b83844511461effe4))
* **YouTube - Hide layout components:** Fix certain description components not working ([#143](https://github.com/MorpheApp/morphe-patches/issues/143)) ([4efcb4d](https://github.com/MorpheApp/morphe-patches/commit/4efcb4d2c0f2085b6dbd0d203ef2979a041d512a))
* **YouTube - Remove background playback restrictions:** Fix background playback not working with certain offline videos ([bb38f76](https://github.com/MorpheApp/morphe-patches/commit/bb38f763f271d14b80166d517a3e840578d1f5b9))
* **YouTube:** Change "Hide mix playlist" to default off ([00f1240](https://github.com/MorpheApp/morphe-patches/commit/00f124004efd758092a5a8330aaea92f08064172))


### Features

* **YouTube:** Add experimental support for `21.02.32` ([#138](https://github.com/MorpheApp/morphe-patches/issues/138)) ([660699d](https://github.com/MorpheApp/morphe-patches/commit/660699da7ee6b623d5010b42fe575e63947b450a))

# [1.3.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.3.0-dev.1...v1.3.0-dev.2) (2026-01-09)


### Bug Fixes

* **YouTube - Hide layout components:** Fix certain description components not working ([#143](https://github.com/MorpheApp/morphe-patches/issues/143)) ([4efcb4d](https://github.com/MorpheApp/morphe-patches/commit/4efcb4d2c0f2085b6dbd0d203ef2979a041d512a))

# [1.3.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.2.1-dev.2...v1.3.0-dev.1) (2026-01-09)


### Features

* **YouTube:** Add experimental support for `21.02.32` ([#138](https://github.com/MorpheApp/morphe-patches/issues/138)) ([660699d](https://github.com/MorpheApp/morphe-patches/commit/660699da7ee6b623d5010b42fe575e63947b450a))

## [1.2.1-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.2.1-dev.1...v1.2.1-dev.2) (2026-01-07)


### Bug Fixes

* **YouTube:** Change "Hide mix playlist" to default off ([00f1240](https://github.com/MorpheApp/morphe-patches/commit/00f124004efd758092a5a8330aaea92f08064172))

## [1.2.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.2.0...v1.2.1-dev.1) (2026-01-07)


### Bug Fixes

* **Spoof video streams:** Change default client to VR 1.47.48 ([#116](https://github.com/MorpheApp/morphe-patches/issues/116)) ([2bc5775](https://github.com/MorpheApp/morphe-patches/commit/2bc577554f54fd2ecfcd682b83844511461effe4))
* **YouTube - Remove background playback restrictions:** Fix background playback not working with certain offline videos ([bb38f76](https://github.com/MorpheApp/morphe-patches/commit/bb38f763f271d14b80166d517a3e840578d1f5b9))

# [1.2.0](https://github.com/MorpheApp/morphe-patches/compare/v1.1.0...v1.2.0) (2026-01-07)


### Bug Fixes

* **YouTube - Hide ads:** Hide new type of general ad, movie ad and web search result ([#92](https://github.com/MorpheApp/morphe-patches/issues/92)) ([ed5c287](https://github.com/MorpheApp/morphe-patches/commit/ed5c2875be27a178368203251e7c07caa18556cf))
* **YouTube - Hide layout components:** Do not change system nav bar transparency when disabling status bar transparency ([2b1330c](https://github.com/MorpheApp/morphe-patches/commit/2b1330c364cbed8ae243554616d88205fcf96376))


### Features

* **YouTube - Auto-captions:** Add an option to disable auto-captions when the device volume is muted ([#37](https://github.com/MorpheApp/morphe-patches/issues/37)) ([369f91e](https://github.com/MorpheApp/morphe-patches/commit/369f91e509d5b273624be5c75466757d9ed20a2a))

# [1.2.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.2.0-dev.3...v1.2.0-dev.4) (2026-01-07)


### Bug Fixes

* Changing default enum setting type setting does not always always clear preference data ([302fa77](https://github.com/MorpheApp/morphe-patches/commit/302fa773b97d032e6d0a1ff0f1de26b8ef348f13))

# [1.2.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.2.0-dev.2...v1.2.0-dev.3) (2026-01-06)


### Bug Fixes

* **YouTube - Hide ads:** Hide new type of general ad, movie ad and web search result ([#92](https://github.com/MorpheApp/morphe-patches/issues/92)) ([ed5c287](https://github.com/MorpheApp/morphe-patches/commit/ed5c2875be27a178368203251e7c07caa18556cf))

# [1.2.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.2.0-dev.1...v1.2.0-dev.2) (2026-01-06)


### Bug Fixes

* **YouTube - Hide layout components:** Do not change system nav bar transparency when disabling status bar transparency ([2b1330c](https://github.com/MorpheApp/morphe-patches/commit/2b1330c364cbed8ae243554616d88205fcf96376))

# [1.2.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.1.0...v1.2.0-dev.1) (2026-01-06)


### Features

* **YouTube - Auto-captions:** Add an option to disable auto-captions when the device volume is muted ([#37](https://github.com/MorpheApp/morphe-patches/issues/37)) ([369f91e](https://github.com/MorpheApp/morphe-patches/commit/369f91e509d5b273624be5c75466757d9ed20a2a))

# [1.1.0](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0...v1.1.0) (2026-01-05)


### Bug Fixes

* **YouTube - Settings:** Icon doesn't change immediately with the theme ([#85](https://github.com/MorpheApp/morphe-patches/issues/85)) ([88e0fb8](https://github.com/MorpheApp/morphe-patches/commit/88e0fb8247b89122c197b99c65e99a6e53f7093c))
* **YouTube - Spoof app version:** Change oldest spoof target to `19.35.36` ([#86](https://github.com/MorpheApp/morphe-patches/issues/86)) ([53ae587](https://github.com/MorpheApp/morphe-patches/commit/53ae587629e129c80be628b87779bfefadf0c2fb))
* **YouTube:** Do not show bold icons if old settings menus is enabled ([bee9bb3](https://github.com/MorpheApp/morphe-patches/commit/bee9bb37b39f39a3db7dfe9c59147457b7cdefd0))


### Features

* **YouTube - Hide layout components:** Add "Hide Featured links", "Hide Featured videos", "Hide Join button", and "Hide Subscribe button" options ([#72](https://github.com/MorpheApp/morphe-patches/issues/72)) ([727c2d9](https://github.com/MorpheApp/morphe-patches/commit/727c2d9d9d6af82d0c565472a6e120a7ec43e94a))
* **YouTube - Hide Shorts components:** Add "Hide 'Auto-dubbed' label" and "Hide live preview" options ([#70](https://github.com/MorpheApp/morphe-patches/issues/70)) ([5239e43](https://github.com/MorpheApp/morphe-patches/commit/5239e434ef44433b5efacbbaa122c8c036e1f57d))

# [1.1.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.1.0-dev.2...v1.1.0-dev.3) (2026-01-05)


### Bug Fixes

* **YouTube - Spoof app version:** Change oldest spoof target to `19.35.36` ([#86](https://github.com/MorpheApp/morphe-patches/issues/86)) ([53ae587](https://github.com/MorpheApp/morphe-patches/commit/53ae587629e129c80be628b87779bfefadf0c2fb))

# [1.1.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.1.0-dev.1...v1.1.0-dev.2) (2026-01-04)


### Bug Fixes

* **YouTube:** Do not show bold icons if old settings menus is enabled ([bee9bb3](https://github.com/MorpheApp/morphe-patches/commit/bee9bb37b39f39a3db7dfe9c59147457b7cdefd0))

# [1.1.0-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.0.1-dev.1...v1.1.0-dev.1) (2026-01-04)


### Features

* **YouTube - Hide layout components:** Add "Hide Featured links", "Hide Featured videos", "Hide Join button", and "Hide Subscribe button" options ([#72](https://github.com/MorpheApp/morphe-patches/issues/72)) ([727c2d9](https://github.com/MorpheApp/morphe-patches/commit/727c2d9d9d6af82d0c565472a6e120a7ec43e94a))
* **YouTube - Hide Shorts components:** Add "Hide 'Auto-dubbed' label" and "Hide live preview" options ([#70](https://github.com/MorpheApp/morphe-patches/issues/70)) ([5239e43](https://github.com/MorpheApp/morphe-patches/commit/5239e434ef44433b5efacbbaa122c8c036e1f57d))

## [1.0.1-dev.1](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0...v1.0.1-dev.1) (2026-01-04)


### Bug Fixes

* **YouTube - Settings:** Icon doesn't change immediately with the theme ([#85](https://github.com/MorpheApp/morphe-patches/issues/85)) ([88e0fb8](https://github.com/MorpheApp/morphe-patches/commit/88e0fb8247b89122c197b99c65e99a6e53f7093c))

# 1.0.0 (2026-01-01)


### Bug Fixes

* **AddResources:** Change resource system to per app, remove per patch resource system that is problematic with Crowdin ([#12](https://github.com/MorpheApp/morphe-patches/issues/12)) ([3d8b223](https://github.com/MorpheApp/morphe-patches/commit/3d8b223e390004ace9c02e138e708477e3d220ae))
* **GmsCore support:** Change recommended MicroG to MicroG-RE ([87fe57d](https://github.com/MorpheApp/morphe-patches/commit/87fe57dae2eac232741abf7c7530cc228cf95955))
* **Spoof video streams:** Restore missing file during commit conflict resolution ([69823a5](https://github.com/MorpheApp/morphe-patches/commit/69823a5d8788fd3391d32260341895fcc28f1051))
* **YouTube - Exit fullscreen mode:** Handle exiting fullscreen on the first video opened ([2d12182](https://github.com/MorpheApp/morphe-patches/commit/2d121828132dbbc5992084bed8527117c129973b))
* **YouTube - Hide ads:** Hide new type of ad ([066a3ff](https://github.com/MorpheApp/morphe-patches/commit/066a3ff6c53656d60ef5eb32c48042f6ed970c41))
* **YouTube - Hide ads:** Support `Hide fullscreen ads` on Android 13+ devices ([#17](https://github.com/MorpheApp/morphe-patches/issues/17)) ([e016b8b](https://github.com/MorpheApp/morphe-patches/commit/e016b8be4a631d6ccf123464f7fb5bd2062b7b99))
* **YouTube - Hide ads:** YouTube Doodles are unclickable when Hide ads is turned on ([1ba6238](https://github.com/MorpheApp/morphe-patches/commit/1ba623899ad6f89e4d4c44114dcea090118887d3))
* **YouTube - Hide layout components:** Fix side effect of Disable translucent status bar ([48bf054](https://github.com/MorpheApp/morphe-patches/commit/48bf0542c1417fc374bd88fee12b7af1b78eabe5))
* **YouTube - Hide Shorts components:** Action buttons are not hidden in YouTube 20.22+ ([#4](https://github.com/MorpheApp/morphe-patches/issues/4)) ([171351a](https://github.com/MorpheApp/morphe-patches/commit/171351a3989cbc8a17990789fd34b1d7b35ffad8))
* **YouTube - Hide video action buttons:** Update hide like and subscribe button glow for 20.22+ ([0fec09c](https://github.com/MorpheApp/morphe-patches/commit/0fec09c816cc2584e279d5290d082d391136bc0b))
* **YouTube - Loop video:** `Enable loop video` setting not working in playlist ([#14](https://github.com/MorpheApp/morphe-patches/issues/14)) ([77df0a3](https://github.com/MorpheApp/morphe-patches/commit/77df0a33f3ad29cdfeb859a4b89068efe9d6a860))
* **YouTube - Loop video:** Fix looping button state ([#22](https://github.com/MorpheApp/morphe-patches/issues/22)) ([d02c00e](https://github.com/MorpheApp/morphe-patches/commit/d02c00e325d967c48a9153269d52b9e94ae68f24))
* **YouTube - Open Shorts in regular player:** Fix back to exit with 20.51 ([6203858](https://github.com/MorpheApp/morphe-patches/commit/62038585df427364e7aeb4aa37f5c1b1e0639478))
* **YouTube - Open Shorts in regular player:** Resolve back button sometimes closing the app instead of exiting fullscreen mode ([d22f9b6](https://github.com/MorpheApp/morphe-patches/commit/d22f9b6ae16b4bfa0f9133fd3f902372533a6e9e))
* **YouTube - Remove viewer discretion dialog:** Not working on YouTube 20.14.43+ ([#19](https://github.com/MorpheApp/morphe-patches/issues/19)) ([d951f2e](https://github.com/MorpheApp/morphe-patches/commit/d951f2ef952f6e7858699c9e981e6a375a88ef55))
* **YouTube - Return YouTube Dislike:** Sometimes incorrect dislike counts shown in Shorts ([6401688](https://github.com/MorpheApp/morphe-patches/commit/640168818c9ada91ed7c429de7b75f08f0b79f9c))
* **YouTube - Return YouTube Dislike:** Sometimes incorrect dislike counts shown when the dislike button is clicked and then canceled ([b598b22](https://github.com/MorpheApp/morphe-patches/commit/b598b22b8c0ca5c5c37f18bf0f02ff7a97463a20))
* **YouTube - ReturnYouTubeDislike:** Fix dislikes not showing with 20.31+ ([f238b81](https://github.com/MorpheApp/morphe-patches/commit/f238b8112f0a46dc24118cf2e8b9138cfdb932c3))
* **YouTube - Spoof video streams:** Age-restricted videos do not play in the `Android No SDK` client ([#3](https://github.com/MorpheApp/morphe-patches/issues/3)) ([c8096b1](https://github.com/MorpheApp/morphe-patches/commit/c8096b14685c8822e36a9acbe7f7729d95f754e8))
* **YouTube Music - Hide buttons:** An exception is thrown due to an invalid fingerprint format ([81042aa](https://github.com/MorpheApp/morphe-patches/commit/81042aa5a7fb8afd1da8dc2a7e854ac0a5b0958c))
* **YouTube Music - Navigation bar:** Hide library tab with 8.24+ ([789dd2a](https://github.com/MorpheApp/morphe-patches/commit/789dd2a59fb2b3ace4a3d57b270d4f4ff13a38b2))
* **YouTube Music:** Change recommended version to `8.37.56` ([ab6033c](https://github.com/MorpheApp/morphe-patches/commit/ab6033c294215e4d9c50db7fe95546c2f9524da4))
* **YouTube:** Changes the default values for some settings ([#10](https://github.com/MorpheApp/morphe-patches/issues/10)) ([fc0f0b8](https://github.com/MorpheApp/morphe-patches/commit/fc0f0b82427acabe225567ce570aa08490b9f15a))
* **YouTube:** Move loop video setting to player menu ([cd82e1e](https://github.com/MorpheApp/morphe-patches/commit/cd82e1e807533e7f39bc40b1d9171929166ff9bb))
* **YouTube:** Remove `19.43.41` that YouTube no longer supports ([ae1a03b](https://github.com/MorpheApp/morphe-patches/commit/ae1a03b7e40cb033575e6f1d2e5c49f54e44c5f3))
* **YT Music:** Support `8.49.52` ([052629c](https://github.com/MorpheApp/morphe-patches/commit/052629ce202ae978a311c35751ab3f771a2fed9c))


### Features

* Add dark icon ([#16](https://github.com/MorpheApp/morphe-patches/issues/16)) ([980e4ac](https://github.com/MorpheApp/morphe-patches/commit/980e4ac8804948c5a577097dc33c498e0ca89de5))
* Add less restrictive license for build related code ([43cbf13](https://github.com/MorpheApp/morphe-patches/commit/43cbf133a1f3bdb550d6af1332a5122b9ccfc25e))
* Add new About dialog style ([#21](https://github.com/MorpheApp/morphe-patches/issues/21)) ([69ee718](https://github.com/MorpheApp/morphe-patches/commit/69ee718af2462f9fffd0289d38dec1b94fa9a9db))
* Add overlay buttons animation ([#20](https://github.com/MorpheApp/morphe-patches/issues/20)) ([a105d4c](https://github.com/MorpheApp/morphe-patches/commit/a105d4c35c9ca198eb8040bce548712f644a6349))
* Generate new release ([ad7d1c3](https://github.com/MorpheApp/morphe-patches/commit/ad7d1c3a1c5f8d75859e0a8f3f92b5c8d708e794))
* Perform full search of free registers ([7a04ba3](https://github.com/MorpheApp/morphe-patches/commit/7a04ba3ba7ac11dea565bdf6e1daa53a707e4ecf))
* **Spoof video streams:** Add an option to sign-in to Android VR ([#23](https://github.com/MorpheApp/morphe-patches/issues/23)) ([a780f67](https://github.com/MorpheApp/morphe-patches/commit/a780f67a5a068a3274b4a4969a46daecbbdc0a60))
* **Spoof video streams:** Default client maintenance ([#11](https://github.com/MorpheApp/morphe-patches/issues/11)) ([339f897](https://github.com/MorpheApp/morphe-patches/commit/339f897e422cfaa5d34b8ffcf0334b96ce50d5e7))
* **YouTube - LithoFilterPatch:** Add support for accessibility filtering ([#1](https://github.com/MorpheApp/morphe-patches/issues/1)) ([61fb9c2](https://github.com/MorpheApp/morphe-patches/commit/61fb9c2498ad261ae526b347fb89d4faf1723704))
* **YouTube Music:** Unofficial support of `8.50.51` ([a392f3f](https://github.com/MorpheApp/morphe-patches/commit/a392f3f69148e1d20c61f61b11e0d167fd920e61))

# [1.0.0-dev.34](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.33...v1.0.0-dev.34) (2026-01-01)


### Features

* Add less restrictive license for build related code ([43cbf13](https://github.com/MorpheApp/morphe-patches/commit/43cbf133a1f3bdb550d6af1332a5122b9ccfc25e))

# [1.0.0-dev.33](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.32...v1.0.0-dev.33) (2026-01-01)


### Bug Fixes

* Remove PAT from GitHub actions ([92b1089](https://github.com/MorpheApp/morphe-patches/commit/92b108961c947698b21b63ca5a182d28ea094edb))

# [1.0.0-dev.32](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.31...v1.0.0-dev.32) (2026-01-01)


### Bug Fixes

* **GmsCore support:** Remove vendor id patch option ([8fa44d2](https://github.com/MorpheApp/morphe-patches/commit/8fa44d21662d74c2103c29672f01e73863ec0c5f))

# [1.0.0-dev.31](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.30...v1.0.0-dev.31) (2025-12-31)


### Features

* **Spoof video streams:** Add an option to sign-in to Android VR ([#23](https://github.com/MorpheApp/morphe-patches/issues/23)) ([a780f67](https://github.com/MorpheApp/morphe-patches/commit/a780f67a5a068a3274b4a4969a46daecbbdc0a60))

# [1.0.0-dev.30](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.29...v1.0.0-dev.30) (2025-12-29)


### Bug Fixes

* **YouTube - Loop video:** Fix looping button state ([#22](https://github.com/MorpheApp/morphe-patches/issues/22)) ([d02c00e](https://github.com/MorpheApp/morphe-patches/commit/d02c00e325d967c48a9153269d52b9e94ae68f24))

# [1.0.0-dev.29](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.28...v1.0.0-dev.29) (2025-12-28)


### Bug Fixes

* Use more informative patch error if the same APK is patched twice ([9112491](https://github.com/MorpheApp/morphe-patches/commit/9112491a61f5a5a2c16ce56f5bdb838a367df7d7))

# [1.0.0-dev.28](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.27...v1.0.0-dev.28) (2025-12-26)


### Bug Fixes

* **YouTube - Hide ads:** Support `Hide fullscreen ads` on Android 13+ devices ([#17](https://github.com/MorpheApp/morphe-patches/issues/17)) ([e016b8b](https://github.com/MorpheApp/morphe-patches/commit/e016b8be4a631d6ccf123464f7fb5bd2062b7b99))
* **YouTube - Remove viewer discretion dialog:** Not working on YouTube 20.14.43+ ([#19](https://github.com/MorpheApp/morphe-patches/issues/19)) ([d951f2e](https://github.com/MorpheApp/morphe-patches/commit/d951f2ef952f6e7858699c9e981e6a375a88ef55))

# [1.0.0-dev.27](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.26...v1.0.0-dev.27) (2025-12-26)


### Bug Fixes

* **YouTube - Hide layout components:** Fix side effect of Disable translucent status bar ([48bf054](https://github.com/MorpheApp/morphe-patches/commit/48bf0542c1417fc374bd88fee12b7af1b78eabe5))


### Features

* Add new About dialog style ([#21](https://github.com/MorpheApp/morphe-patches/issues/21)) ([69ee718](https://github.com/MorpheApp/morphe-patches/commit/69ee718af2462f9fffd0289d38dec1b94fa9a9db))

# [1.0.0-dev.26](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.25...v1.0.0-dev.26) (2025-12-26)


### Bug Fixes

* **YouTube - Exit fullscreen mode:** Handle exiting fullscreen on the first video opened ([2d12182](https://github.com/MorpheApp/morphe-patches/commit/2d121828132dbbc5992084bed8527117c129973b))

# [1.0.0-dev.25](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.24...v1.0.0-dev.25) (2025-12-25)


### Bug Fixes

* **YouTube Music:** Change recommended version to `8.37.56` ([ab6033c](https://github.com/MorpheApp/morphe-patches/commit/ab6033c294215e4d9c50db7fe95546c2f9524da4))

# [1.0.0-dev.24](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.23...v1.0.0-dev.24) (2025-12-25)


### Bug Fixes

* Create pre-release build for testing ([931017d](https://github.com/MorpheApp/morphe-patches/commit/931017d3a97a9f40b2f1acf4fc0b636c85faf210))

# [1.0.0-dev.23](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.22...v1.0.0-dev.23) (2025-12-25)


### Bug Fixes

* Change recommended version to 20.37.48 ([5361d03](https://github.com/MorpheApp/morphe-patches/commit/5361d03c5aec922429b6cffc6b5b60690c9b608e))

# [1.0.0-dev.22](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.21...v1.0.0-dev.22) (2025-12-24)


### Features

* **YouTube Music:** Unofficial support of `8.50.51` ([a392f3f](https://github.com/MorpheApp/morphe-patches/commit/a392f3f69148e1d20c61f61b11e0d167fd920e61))

# [1.0.0-dev.21](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.20...v1.0.0-dev.21) (2025-12-24)


### Features

* Add overlay buttons animation ([#20](https://github.com/MorpheApp/morphe-patches/issues/20)) ([a105d4c](https://github.com/MorpheApp/morphe-patches/commit/a105d4c35c9ca198eb8040bce548712f644a6349))

# [1.0.0-dev.20](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.19...v1.0.0-dev.20) (2025-12-24)


### Bug Fixes

* **YouTube Music - Navigation bar:** Hide library tab with 8.24+ ([789dd2a](https://github.com/MorpheApp/morphe-patches/commit/789dd2a59fb2b3ace4a3d57b270d4f4ff13a38b2))

# [1.0.0-dev.19](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.18...v1.0.0-dev.19) (2025-12-24)


### Bug Fixes

* Resolve ssl connection timeout in ci (test) ([031ffba](https://github.com/MorpheApp/morphe-patches/commit/031ffba84ed17cd742356fb3321198c73c99ab29))

# [1.0.0-dev.18](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.17...v1.0.0-dev.18) (2025-12-23)


### Bug Fixes

* **GmsCore support:** Change recommended MicroG to MicroG-RE ([87fe57d](https://github.com/MorpheApp/morphe-patches/commit/87fe57dae2eac232741abf7c7530cc228cf95955))

# [1.0.0-dev.17](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.16...v1.0.0-dev.17) (2025-12-21)


### Bug Fixes

* **YouTube - Open Shorts in regular player:** Fix back to exit with 20.51 ([6203858](https://github.com/MorpheApp/morphe-patches/commit/62038585df427364e7aeb4aa37f5c1b1e0639478))

# [1.0.0-dev.16](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.15...v1.0.0-dev.16) (2025-12-20)


### Features

* Add dark icon ([#16](https://github.com/MorpheApp/morphe-patches/issues/16)) ([980e4ac](https://github.com/MorpheApp/morphe-patches/commit/980e4ac8804948c5a577097dc33c498e0ca89de5))

# [1.0.0-dev.15](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.14...v1.0.0-dev.15) (2025-12-18)


### Features

* Perform full search of free registers ([7a04ba3](https://github.com/MorpheApp/morphe-patches/commit/7a04ba3ba7ac11dea565bdf6e1daa53a707e4ecf))

# [1.0.0-dev.14](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.13...v1.0.0-dev.14) (2025-12-17)


### Bug Fixes

* **YouTube - Loop video:** `Enable loop video` setting not working in playlist ([#14](https://github.com/MorpheApp/morphe-patches/issues/14)) ([77df0a3](https://github.com/MorpheApp/morphe-patches/commit/77df0a33f3ad29cdfeb859a4b89068efe9d6a860))
* **YouTube - Loop video:** Wrong icon applied ([#13](https://github.com/MorpheApp/morphe-patches/issues/13)) ([92f1325](https://github.com/MorpheApp/morphe-patches/commit/92f13251c0005b44c7859c11f442ddb3a9f5375a))
* **YouTube - Open Shorts in regular player:** Resolve back button sometimes closing the app instead of exiting fullscreen mode ([d22f9b6](https://github.com/MorpheApp/morphe-patches/commit/d22f9b6ae16b4bfa0f9133fd3f902372533a6e9e))
* **YouTube:** Changes the default values for some settings ([#10](https://github.com/MorpheApp/morphe-patches/issues/10)) ([fc0f0b8](https://github.com/MorpheApp/morphe-patches/commit/fc0f0b82427acabe225567ce570aa08490b9f15a))
* **YouTube:** Remove `19.43.41` that YouTube no longer supports ([ae1a03b](https://github.com/MorpheApp/morphe-patches/commit/ae1a03b7e40cb033575e6f1d2e5c49f54e44c5f3))

# [1.0.0-dev.13](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.12...v1.0.0-dev.13) (2025-12-17)


### Bug Fixes

* **Spoof video streams:** Restore missing file during commit conflict resolution ([69823a5](https://github.com/MorpheApp/morphe-patches/commit/69823a5d8788fd3391d32260341895fcc28f1051))


### Features

* **Spoof video streams:** Default client maintenance ([#11](https://github.com/MorpheApp/morphe-patches/issues/11)) ([339f897](https://github.com/MorpheApp/morphe-patches/commit/339f897e422cfaa5d34b8ffcf0334b96ce50d5e7))

# [1.0.0-dev.12](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.11...v1.0.0-dev.12) (2025-12-16)


### Bug Fixes

* **AddResources:** Change resource system to per app, remove per patch resource system that is problematic with Crowdin ([#12](https://github.com/MorpheApp/morphe-patches/issues/12)) ([3d8b223](https://github.com/MorpheApp/morphe-patches/commit/3d8b223e390004ace9c02e138e708477e3d220ae))
* **YouTube - Hide player flyout menu items:** Hide additional menu items with 20.22+ ([734bfb3](https://github.com/MorpheApp/morphe-patches/commit/734bfb344e846339378a3aa5bb050b01a6b76223))
* **YouTube - Hide video action buttons:** Update hide like and subscribe button glow for 20.22+ ([0fec09c](https://github.com/MorpheApp/morphe-patches/commit/0fec09c816cc2584e279d5290d082d391136bc0b))

# [1.0.0-dev.11](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.10...v1.0.0-dev.11) (2025-12-15)


### Bug Fixes

* Use 'notification' language instead of 'toast' ([06d18b8](https://github.com/MorpheApp/morphe-patches/commit/06d18b8c900e473f2859bb5aaf3b4eb68009e311))

# [1.0.0-dev.10](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.9...v1.0.0-dev.10) (2025-12-13)


### Bug Fixes

* `backtick` is replaced with an empty string in bash ([#9](https://github.com/MorpheApp/morphe-patches/issues/9)) ([a29ac22](https://github.com/MorpheApp/morphe-patches/commit/a29ac22799417bde5c5d3f047b1dd4c09b7137c2))

# [1.0.0-dev.9](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.8...v1.0.0-dev.9) (2025-12-12)


### Bug Fixes

* `backtick` is replaced with an empty string in bash ([#8](https://github.com/MorpheApp/morphe-patches/issues/8)) ([4858494](https://github.com/MorpheApp/morphe-patches/commit/485849403488c3a44e770266d5d3af65ee8c1e63))

# [1.0.0-dev.8](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.7...v1.0.0-dev.8) (2025-12-11)


### Bug Fixes

* Rename `bundleBundles` to `generatePatchesList` ([0bcc12f](https://github.com/MorpheApp/morphe-patches/commit/0bcc12f64aab9045df6f94c2ab20a52fb52ca096))

# [1.0.0-dev.7](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.6...v1.0.0-dev.7) (2025-12-11)


### Bug Fixes

* **YT Music:** Support `8.49.52` ([052629c](https://github.com/MorpheApp/morphe-patches/commit/052629ce202ae978a311c35751ab3f771a2fed9c))

# [1.0.0-dev.6](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.5...v1.0.0-dev.6) (2025-12-09)


### Bug Fixes

* **YouTube - Hide player flyout menu items:** Remove hide submenu items for 20.22+ ([5d5d1f1](https://github.com/MorpheApp/morphe-patches/commit/5d5d1f1630a08899b4423f1482d602102f12e8ca))

# [1.0.0-dev.5](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.4...v1.0.0-dev.5) (2025-12-09)


### Features

* Generate new release ([ad7d1c3](https://github.com/MorpheApp/morphe-patches/commit/ad7d1c3a1c5f8d75859e0a8f3f92b5c8d708e794))

# [1.0.0-dev.4](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.3...v1.0.0-dev.4) (2025-12-08)


### Bug Fixes

* Revert Android Studio automatic changes ([f59279b](https://github.com/MorpheApp/morphe-patches/commit/f59279b8f9d2e6c1ab9e1d6716ded469a7db486c))

# [1.0.0-dev.3](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.2...v1.0.0-dev.3) (2025-12-08)


### Bug Fixes

* Remove DSL from fingerprints ([#6](https://github.com/MorpheApp/morphe-patches/issues/6)) ([ea41840](https://github.com/MorpheApp/morphe-patches/commit/ea41840e7ef6d678ab84bb3bfd10f8c84070f4e8))

# [1.0.0-dev.2](https://github.com/MorpheApp/morphe-patches/compare/v1.0.0-dev.1...v1.0.0-dev.2) (2025-12-07)


### Bug Fixes

* Fix publish? ([864f2ee](https://github.com/MorpheApp/morphe-patches/commit/864f2ee5cd4717930d7a74e3de597bfca6eeb2aa))

# 1.0.0-dev.1 (2025-12-07)


### Bug Fixes

* Move all classes to morphe namespace ([9948922](https://github.com/MorpheApp/morphe-patches/commit/9948922e4e2015fa15af27a7ebb5e2cbffc1c01c))
* Remove installation nag screen that prevent sharing with close friends, but also will never stop counterfeit sites ([e7f3497](https://github.com/MorpheApp/morphe-patches/commit/e7f3497d4197f10e028be0fc2623e152375b52b6))
* Restore code ([2e4979a](https://github.com/MorpheApp/morphe-patches/commit/2e4979ae17533eb80658aca7f50a9cb62cba478b))
* **YouTube - Hide ads:** Hide new type of ad ([066a3ff](https://github.com/MorpheApp/morphe-patches/commit/066a3ff6c53656d60ef5eb32c48042f6ed970c41))
* **YouTube - Hide ads:** YouTube Doodles are unclickable when Hide ads is turned on ([1ba6238](https://github.com/MorpheApp/morphe-patches/commit/1ba623899ad6f89e4d4c44114dcea090118887d3))
* **YouTube - Hide ads:** YouTube Doodles are unclickable when Hide ads is turned on ([d3a54c1](https://github.com/MorpheApp/morphe-patches/commit/d3a54c1b9c2c9f3179dacc07611792ee4852fc9e))
* **YouTube - Hide Shorts components:** Action buttons are not hidden in YouTube 20.22+ ([#4](https://github.com/MorpheApp/morphe-patches/issues/4)) ([171351a](https://github.com/MorpheApp/morphe-patches/commit/171351a3989cbc8a17990789fd34b1d7b35ffad8))
* **YouTube - Return YouTube Dislike:** Sometimes incorrect dislike counts shown in Shorts ([6401688](https://github.com/MorpheApp/morphe-patches/commit/640168818c9ada91ed7c429de7b75f08f0b79f9c))
* **YouTube - Return YouTube Dislike:** Sometimes incorrect dislike counts shown when the dislike button is clicked and then canceled ([b598b22](https://github.com/MorpheApp/morphe-patches/commit/b598b22b8c0ca5c5c37f18bf0f02ff7a97463a20))
* **YouTube - ReturnYouTubeDislike:** Fix dislikes not showing with 20.31+ ([f238b81](https://github.com/MorpheApp/morphe-patches/commit/f238b8112f0a46dc24118cf2e8b9138cfdb932c3))
* **YouTube - Spoof video streams:** Age-restricted videos do not play in the `Android No SDK` client ([#3](https://github.com/MorpheApp/morphe-patches/issues/3)) ([c8096b1](https://github.com/MorpheApp/morphe-patches/commit/c8096b14685c8822e36a9acbe7f7729d95f754e8))
* **YouTube Music - Hide buttons:** An exception is thrown due to an invalid fingerprint format ([81042aa](https://github.com/MorpheApp/morphe-patches/commit/81042aa5a7fb8afd1da8dc2a7e854ac0a5b0958c))
* **YouTube:** Move loop video setting to player menu ([cd82e1e](https://github.com/MorpheApp/morphe-patches/commit/cd82e1e807533e7f39bc40b1d9171929166ff9bb))


### Features

* **YouTube - LithoFilterPatch:** Add support for accessibility filtering ([#1](https://github.com/MorpheApp/morphe-patches/issues/1)) ([61fb9c2](https://github.com/MorpheApp/morphe-patches/commit/61fb9c2498ad261ae526b347fb89d4faf1723704))
