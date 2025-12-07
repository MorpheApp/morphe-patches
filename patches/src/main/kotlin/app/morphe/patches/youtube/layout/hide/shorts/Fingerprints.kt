package app.morphe.patches.youtube.layout.hide.shorts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.InstructionLocation.*
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val componentContextParserFingerprint = Fingerprint(
    returnType = "L",
    filters = listOf(
        string("Number of bits must be positive")
    )
)

internal val treeNodeResultListFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    filters = listOf(
        methodCall(name = "nCopies", opcode = Opcode.INVOKE_STATIC),
    )
)

internal val shortsBottomBarContainerFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
    filters = listOf(
        string("r_pfvc"),
        resourceLiteral(ResourceType.ID, "bottom_bar_container"),
        methodCall(name = "getHeight"),
        opcode(Opcode.MOVE_RESULT)
    )
)

internal val renderBottomNavigationBarFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT, MatchFirst()),
        opcode(Opcode.MONITOR_ENTER, MatchAfterImmediately()),
        opcode(Opcode.IGET_OBJECT, MatchAfterImmediately()),
        opcode(Opcode.IF_EQZ, MatchAfterImmediately()),
        opcode(Opcode.INVOKE_INTERFACE, MatchAfterImmediately()),

        opcode(Opcode.MONITOR_EXIT),
        opcode(Opcode.MOVE_EXCEPTION),
        opcode(Opcode.MONITOR_EXIT),
        opcode(Opcode.THROW)
    )
)

/**
 * Less than 19.41.
 */
internal val legacyRenderBottomNavigationBarLegacyParentFingerprint = Fingerprint(
    parameters = listOf(
        "I",
        "I",
        "L",
        "L",
        "J",
        "L",
    ),
    filters = listOf(
        string("aa")
    )
)

/**
 * 19.41 - 20.44
 */
internal val renderBottomNavigationBarLegacy1941ParentFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(
        "I",
        "I",
        "L", // ReelWatchEndpointOuterClass
        "L",
        "J",
        "Ljava/lang/String;",
        "L",
    ),
    filters = listOf(
        string("aa")
    )
)

/**
 * 20.45+
 */
internal val renderBottomNavigationBarParentFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "[Ljava/lang/Class;",
    parameters = listOf(
        "Ljava/lang/Class;",
        "Ljava/lang/Object;",
        "I"
    ),
    filters = listOf(
        string("RPCAC")
    )
)

internal val setPivotBarVisibilityFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CHECK_CAST,
        Opcode.IF_EQZ,
    )
)

internal val setPivotBarVisibilityParentFingerprint = Fingerprint(
    parameters = listOf("Z"),
    filters = listOf(
        string("FEnotifications_inbox")
    )
)

internal val shortsExperimentalPlayerFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45677719L)
    )
)

internal val renderNextUIFeatureFlagFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45649743L)
    )
)
