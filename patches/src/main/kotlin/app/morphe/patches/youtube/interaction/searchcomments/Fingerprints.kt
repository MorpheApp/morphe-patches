/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3942
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.searchcomments

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterLast
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Sets up the header of an engagement panel, such as the comments panel.
 */
internal object PanelSubheaderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "panel_header"),
        resourceLiteral(ResourceType.ID, "close_button"),
        resourceLiteral(ResourceType.ID, "panel_subheader"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "removeAllViews"
        )
    )
)

/**
 * A method of the controller of a section list, such as the list of the comments panel.
 */
internal object SectionListControllerFingerprint : Fingerprint(
    filters = listOf(
        string("Number of sectionList models must be equal to the number of section states")
    )
)

/**
 * Creates the controller of a section list. The match is the write of the list view.
 */
internal object SectionListControllerConstructorFingerprint : Fingerprint(
    classFingerprint = SectionListControllerFingerprint,
    name = "<init>",
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Landroid/view/View;"),
        opcode(Opcode.RETURN_VOID, MatchAfterLast())
    )
)

/**
 * Refreshes a section list. If the list has a reload continuation, as the comments have
 * for their sort order, the list is reloaded with it.
 * The first match reads the reload continuation type, the second checks that the list has
 * a continuation of the type, and the last reloads the list with it.
 */
internal object SectionListRefreshFingerprint : Fingerprint(
    classFingerprint = SectionListControllerFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        opcode(opcode = Opcode.SGET_OBJECT, location = MatchFirst()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("L"),
            returnType = "Z",
            location = MatchAfterImmediately()
        ),
        opcode(opcode = Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(opcode = Opcode.IF_EQZ, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)
