/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3075
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.returnyoutubedislike

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

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

internal object TextComponentLookupFingerprint : Fingerprint(
    classFingerprint = TextComponentConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        string("…")
    )
)

internal object TextComponentFeatureFlagFingerprint : Fingerprint(
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
            location = MatchAfterWithin(5)
        ),
        methodCall(
            smali = "Landroid/text/SpannableString;->getSpans(IILjava/lang/Class;)[Ljava/lang/Object;",
            location = MatchAfterWithin(5)
        ),

        methodCall(
            name = "addOnLayoutChangeListener",
            parameters = listOf($$"Landroid/view/View$OnLayoutChangeListener;"),
        )
    )
)

internal object ComponentHostSetContentDescriptionFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/litho/ComponentHost;",
    name = "setContentDescription",
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;")
)

internal object YogaSetWidthFingerprint : Fingerprint(
    definingClass = "Lcom/facebook/yoga/YogaNodeJNIBase;",
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_WIDE,
            definingClass = "this",
            type = "J"
        ),
        methodCall(
            definingClass = "Lcom/facebook/yoga/YogaNative;",
            name = "jni_YGNodeStyleSetWidthJNI",
        )
    )
)
