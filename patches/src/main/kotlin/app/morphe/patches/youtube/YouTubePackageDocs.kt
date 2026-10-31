package app.morphe.patches.youtube

/**
 * Documentation for the refactored YouTube package layout.
 */
object YouTubePackageDocs {
    const val TARGET_LAYOUT = """
        patches/src/main/kotlin/app/morphe/patches/youtube/
        ├─ core/
        │  ├─ compatibility/
        │  ├─ bootstrap/
        │  └─ config/
        ├─ platform/
        │  ├─ litho/
        │  ├─ protobuf/
        │  ├─ hooks/
        │  └─ resources/
        ├─ shared/
        │  ├─ utils/
        │  ├─ settings/
        │  ├─ models/
        │  └─ analytics/
        ├─ features/
        │  ├─ ads/
        │  ├─ captions/
        │  ├─ comments/
        │  ├─ feed/
        │  ├─ player/
        │  ├─ search/
        │  ├─ subscriptions/
        │  ├─ interaction/
        │  └─ layout/
        └─ YoutubePatchBundle.kt
    """.trimIndent()
}
