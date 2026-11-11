/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3033
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.lyrics

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Matched only to reach the engagement panel controller class, which owns the field
 * holding the panel currently in the container.
 */
internal object EngagementPanelControllerFingerprint : Fingerprint(
    strings = listOf(
        "EngagementPanelController: cannot show EngagementPanel before EngagementPanelController.init() has been called.",
        "[EngagementPanel] Cannot show EngagementPanel before EngagementPanelController.init() has been called."
    )
)

/**
 * Compares the identifier of the panel being shown with the one of the comments panel. The
 * static call right before the string is the helper that reads the identifier out of the
 * panel renderer, which is what tells the lyrics panel from the other panels.
 */
internal object PanelIdentifierFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "Ljava/lang/String;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("music-comment-panel", location = MatchAfterWithin(2))
    )
)

/**
 * Extension stub that the patch fills in with a call to the app's panel identifier helper.
 */
internal object GetPanelIdentifierFingerprint : Fingerprint(
    definingClass = PANEL_INSTALLER_CLASS,
    name = "getPanelIdentifier",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/Object;")
)

/**
 * Tells the listeners of a tabbed view which tab was selected. The other single int parameter
 * methods of the class do not call the listeners.
 */
internal object TabbedViewTabSelectedFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/music/ui/tabs/TabbedView;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("I", "Z"),
            returnType = "V"
        )
    )
)
