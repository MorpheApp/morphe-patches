package app.morphe.patches.youtube.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE = "Lcom/google/android/apps/youtube/app/watchwhile/MainActivity;"

internal object ConversionContextFingerprintToString : Fingerprint(
    name = "toString",
    parameters = listOf(),
    strings = listOf(
        "ConversionContext{", // Partial string match.
        ", widthConstraint=",
        ", heightConstraint=",
        ", templateLoggerFactory=",
        ", rootDisposableContainer=",
        ", identifierProperty="
    )
)

internal object BackgroundPlaybackManagerShortsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L"),
    filters = listOf(
        literal(151635310)
    )
)

internal object LayoutConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        literal(159962),
        resourceLiteral(ResourceType.ID, "player_control_previous_button_touch_area"),
        resourceLiteral(ResourceType.ID, "player_control_next_button_touch_area"),
        methodCall(parameters = listOf("Landroid/view/View;", "I"))
    )
)

internal object MainActivityConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf(),
    custom = { _, classDef ->
        classDef.type == YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
    }
)

internal object MainActivityOnBackPressedFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onBackPressed",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
)

internal object YouTubeActivityOnCreateFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)

internal object RollingNumberTextViewAnimationUpdateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/graphics/Bitmap;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.NEW_INSTANCE, // bitmap ImageSpan
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CONST_4,
        Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.CONST_16,
        Opcode.INVOKE_VIRTUAL,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.INT_TO_FLOAT,
        Opcode.INVOKE_VIRTUAL, // set textview padding using bitmap width
    ),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/support/v7/widget/AppCompatTextView;" ||
            classDef.superclass ==
            "Lcom/google/android/libraries/youtube/rendering/ui/spec/typography/YouTubeAppCompatTextView;"
    }
)

internal object SearchRequestBuildParametersFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("searchFormData"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "toByteArray",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
    )
)

internal object SeekbarFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("timed_markers_width"),
    )
)

internal object SeekbarOnDrawFingerprint : Fingerprint(
    name = "onDraw",
    filters = listOf(
        methodCall(smali = "Ljava/lang/Math;->round(F)I"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

internal object SubtitleButtonControllerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "accessibility_captions_unavailable"),
        resourceLiteral(ResourceType.STRING, "accessibility_captions_button_name"),
    )
)

internal object VideoQualityChangedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET, type = "I", location = MatchFirst()),
        literal(2, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NE, location = MatchAfterImmediately()),
        opcode(Opcode.NEW_INSTANCE, location = MatchAfterImmediately()), // Obfuscated VideoQuality

        opcode(Opcode.IGET_OBJECT, location = MatchAfterWithin(6)),
        opcode(Opcode.CHECK_CAST),
        fieldAccess(type = "I", opcode = Opcode.IGET, location = MatchAfterImmediately()), // Video resolution (human readable).
    )
)

internal object ToolBarButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "menu_item_view"),
        methodCall(returnType = "I", opcode = Opcode.INVOKE_INTERFACE),
        opcode(Opcode.MOVE_RESULT, MatchAfterImmediately()),
        fieldAccess(type = "Landroid/widget/ImageView;", opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(6)),
        methodCall(definingClass = "Landroid/content/res/Resources;", name = "getDrawable", location = MatchAfterWithin(8)),
        methodCall(definingClass = "Landroid/widget/ImageView;", name = "setImageDrawable", location = MatchAfterWithin(4))
    ),
    custom = { method, _ ->
        // 20.37+ has second parameter of "Landroid/content/Context;"
        val parameterCount = method.parameterTypes.count()
        (parameterCount == 1 || parameterCount == 2)
                && method.parameterTypes.firstOrNull() == "Landroid/view/MenuItem;"
    }
)

