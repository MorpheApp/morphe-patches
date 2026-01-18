package app.morphe.patches.youtube.layout.returnyoutubedislike

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object DislikeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("like/dislike")
    )
)

internal object LikeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("like/like")
    )
)

internal object RemoveLikeFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("like/removelike")
    )
)

internal object RollingNumberMeasureAnimatedTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Lj\$/util/Optional;",
    parameters = listOf("L", "Ljava/lang/String;", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET, // First instruction of method
        Opcode.IGET_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.CONST_HIGH16,
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT,
        Opcode.CONST_4,
        Opcode.AGET,
        Opcode.CONST_4,
        Opcode.CONST_4, // Measured text width
    )
)

/**
 * Matches to class found in [rollingNumberMeasureStaticLabelParentFingerprint].
 */
internal object RollingNumberMeasureStaticLabelFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "F",
    parameters = listOf("Ljava/lang/String;"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.RETURN,
    )
)

internal object RollingNumberMeasureStaticLabelParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("RollingNumberFontProperties{paint=")
    )
)

internal object RollingNumberSetterFingerprint : Fingerprint(
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_DIRECT,
        Opcode.IGET_OBJECT,
    ),
    // Partial string match.
    strings = listOf("RollingNumberType required properties missing! Need")
)

internal object RollingNumberTextViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "F", "F"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IPUT,
        null, // invoke-direct or invoke-virtual
        Opcode.IPUT_OBJECT,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.RETURN_VOID,
    ),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/support/v7/widget/AppCompatTextView;" || classDef.superclass ==
                "Lcom/google/android/libraries/youtube/rendering/ui/spec/typography/YouTubeAppCompatTextView;"
    }
)

internal object TextComponentConstructorFingerprint : Fingerprint(
    filters = listOf(
        string("TextComponent")
    ),
    custom = { method, _ ->
        // 20.23+ is public.
        // 20.22 and lower is private.
        AccessFlags.CONSTRUCTOR.isSet(method.accessFlags)
    }
)

internal object TextComponentDataFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("L", "L"),
    filters = listOf(
        string("text")
    ),
    custom = { _, classDef ->
        classDef.fields.find { it.type == "Ljava/util/BitSet;" } != null
    }
)

/**
 * Matches against the same class found in [textComponentConstructorFingerprint].
 */
internal object TextComponentLookupFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        string("…")
    )
)

internal object TextComponentFeatureFlagFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(45675738L)
    )
)

internal object LithoSpannableStringCreationFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;", "L"),
    filters = listOf(
        newInstance(type = "Landroid/text/SpannableString;"),
        methodCall(
            smali = "Landroid/text/SpannableString;-><init>(Ljava/lang/CharSequence;)V",
            location = InstructionLocation.MatchAfterWithin(5)
        ),
        methodCall(
            smali = "Landroid/text/SpannableString;->getSpans(IILjava/lang/Class;)[Ljava/lang/Object;",
            location = InstructionLocation.MatchAfterWithin(5)
        ),

        methodCall(
            name = "addOnLayoutChangeListener",
            parameters = listOf("Landroid/view/View\$OnLayoutChangeListener;"),
        )
    )
)