package app.morphe.patches.youtube.video.series

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string

internal object MediaSessionFingerprint :
    Fingerprint(
        filters =
            listOf(
                methodCall(
                    definingClass = "Landroid/media/session/MediaSession;",
                    name = "setMetadata",
                    parameters = listOf("Landroid/media/MediaMetadata;"),
                )
            )
    )

internal object BrowseFragmentFingerprint :
    Fingerprint(
        parameters =
            listOf(
                "Landroid/view/LayoutInflater;",
                "Landroid/view/ViewGroup;",
                "Landroid/os/Bundle;",
            ),
        returnType = "Landroid/view/View;",
        filters =
            listOf(string("Browse Fragment was given a navigation endpoint without browse data.")),
    )

internal object AccountIdentityFingerprint :
    Fingerprint(
        name = "toString",
        strings = listOf("AccountIdentity{getId=", ", isIncognito="),
    )

internal object SignedOutIdentityFingerprint : Fingerprint(strings = listOf("PseudonymousIdentity"))

internal object CurrentAccountProviderFingerprint :
    Fingerprint(strings = listOf("NEXT_INCOGNITO_SESSION_INDEX"))
