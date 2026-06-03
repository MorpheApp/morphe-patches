package app.morphe.patches.reddit.layout.ask

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string

internal object AskButtonComposableFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("trailing_ask_button"),
        string("search_ask_icon"),
        string("search_ask_label")
    )
)