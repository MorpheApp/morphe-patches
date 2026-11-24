/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.glassnav

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch;"

internal object BottomNavBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L", "F", "L", "L", "I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "Lkotlin/jvm/functions/Function1;"),
            returnType = "L"
        ),
        string("bottom_nav", location = MatchAfterWithin(25)),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf("L", "L", "F", "J", "L", "Lkotlin/jvm/functions/Function2;", "L", "I", "I"),
            returnType = "V",
            location = MatchAfterWithin(30)
        )
    )
)

internal object SideNavRailFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Landroidx/compose/runtime/internal/a;",
        "L",
        "L",
        "L",
        "Lkotlin/jvm/functions/Function2;",
        "L",
        "I"
    ),
    filters = listOf(
        literal(0x42c00000),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "F"),
            returnType = "L",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.SGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            parameters = listOf("L", "L", "Z", "L", "L", "I", "I"),
            returnType = "V",
            location = MatchAfterWithin(40)
        )
    )
)

internal object DrawRoundRectFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.SYNTHETIC),
    returnType = "V",
    parameters = listOf("L", "J", "J", "J", "J", "L", "F", "I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf(),
            returnType = "J"
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE_RANGE,
            parameters = listOf("J", "J", "J", "J", "L", "F"),
            returnType = "V"
        )
    )
)

internal object DrawContentFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string(
            "Attempting to drawContent for a `null` node",
            comparison = StringComparisonType.STARTS_WITH
        )
    )
)

internal object PaddingFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L", "F", "F", "F", "F"),
    filters = listOf(
        methodCall(
            name = "<init>",
            parameters = listOf("F", "F", "F", "F", "Lkotlin/jvm/functions/Function1;")
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("L"),
            returnType = "L",
            location = MatchAfterImmediately()
        )
    )
)

private val themeChainFilters = listOf(
    opcode(Opcode.SGET_OBJECT),
    methodCall(
        parameters = listOf("Landroidx/compose/runtime/i;"),
        returnType = "Ljava/lang/Object;",
        location = MatchAfterImmediately()
    ),
    opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(1)),
    opcode(Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
    methodCall(
        opcode = Opcode.INVOKE_VIRTUAL,
        parameters = listOf(),
        returnType = "J",
        location = MatchAfterWithin(2)
    ),
    methodCall(
        opcode = Opcode.INVOKE_STATIC,
        parameters = listOf("L", "J", "L"),
        returnType = "L",
        location = MatchAfterWithin(3)
    )
)

internal object BottomNavBackdropFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Lkotlin/jvm/functions/Function0;",
        "Z",
        "L",
        "L",
        "Landroidx/compose/runtime/internal/a;",
        "L",
        "I"
    ),
    filters = themeChainFilters + methodCall(
        definingClass = "Landroidx/compose/ui/graphics/d;",
        name = "a",
        parameters = listOf("L", "Lkotlin/jvm/functions/Function1;"),
        location = MatchAfterWithin(30)
    )
)

internal object BottomNavBackdropLegacyFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "V",
    parameters = listOf("L", "L", "I"),
    filters = themeChainFilters + methodCall(
        definingClass = "Landroidx/compose/ui/graphics/d;",
        name = "a",
        parameters = listOf("L", "Lkotlin/jvm/functions/Function1;"),
        location = MatchAfterWithin(30)
    )
)

internal object RailStripColorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "V",
    filters = listOf(
        newInstance("Lcom/reddit/launch/bottomnav/BottomNavScreen\$NavStackHostContent\$2\$1;"),
        methodCall(
            parameters = listOf("Landroidx/compose/runtime/i;"),
            returnType = "Ljava/lang/Object;"
        ),
        opcode(Opcode.CHECK_CAST, location = MatchAfterWithin(1)),
        opcode(Opcode.IGET_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf(),
            returnType = "J",
            location = MatchAfterWithin(2)
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L", "J", "L"),
            returnType = "L",
            location = MatchAfterWithin(3)
        )
    )
)

internal object NavScreenContentStateToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string(
            "NavScreenContentState(showBottomNav=",
            comparison = StringComparisonType.STARTS_WITH
        )
    )
)

internal object NavScreenContentStateConstructorFingerprint : Fingerprint(
    classFingerprint = NavScreenContentStateToStringFingerprint,
    name = "<init>",
    parameters = listOf("Z", "Z", "Z", "F", "Z")
)

internal object DrawRoundRectStubFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "drawRoundRect",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;", "J", "J", "J", "J", "Ljava/lang/Object;", "F", "I")
)

internal object DrawScopeSizeStubFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "drawScopeSize",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "J",
    parameters = listOf("Ljava/lang/Object;")
)

internal object DrawContentStubFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "drawContent",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;")
)

internal object PaddingStubFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "padding",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;", "F", "F", "F", "F")
)

internal object FeedColorStubFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "feedColor",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "J",
    parameters = listOf("Ljava/lang/Object;")
)

internal object StripColorStubFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "stripColor",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC),
    returnType = "J",
    parameters = listOf("Ljava/lang/Object;")
)
