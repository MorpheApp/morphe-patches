/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.litho.rendernext

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val RenderNextFlagLiteral = 45661418L

/**
 * Getter of the RenderNext flag.
 * Until 21.29 it's also used to provide ElementsServices to the present context of the section list,
 * 21.30+ it's used only to convert Litho components to RenderNext.
 */
internal object RenderNextFeatureFlagFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        literal(RenderNextFlagLiteral)
    )
)

/**
 * Provides ElementsServices to the section list, if the RenderNext flag is enabled.
 * 21.30+ the flag is inlined here instead of using [RenderNextFeatureFlagFingerprint].
 */
internal object RenderNextPresentContextFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(RenderNextFlagLiteral),
        checkCast("Lcom/google/android/libraries/multiplatform/elements/ElementsServices;")
    )
)

/**
 * Presents an element with RenderNext if it's a RenderNext element, otherwise with Litho.
 */
internal object HybridElementPresenterFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        // RenderNext element check.
        opcode(Opcode.INSTANCE_OF),
        string("HybridElementPresenter: ElementsServices not provided through builder or PresentContext.")
    )
)

/**
 * Reads the enable_rendernext field of an Element proto, set by the server.
 * If true, the element is presented with an ElementsView instead of Litho.
 */
internal object RenderNextEnablementCheckFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(
        string("Failed to read Element proto passed in to RenderNextEnablementCheck: ")
    )
)

/**
 * Callers of the enablement check, that decide if an element is RenderNext.
 * The first boolean they read is the RenderNext field of the element config, set by the server.
 */
internal fun getRenderNextConfigFieldFingerprint(enablementCheck: MethodReference) = object : Fingerprint(
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            type = "Z"
        ),
        methodCall(reference = enablementCheck)
    )
) {}

/**
 * Reads the RenderNext field of the element config.
 */
internal fun getRenderNextConfigFieldReadFingerprint(configField: FieldReference) = object : Fingerprint(
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            reference = configField
        )
    )
) {}

/**
 * Checks if a component identifier is in the comma separated list of the flag 45661551
 * ("template" or "parentTemplate:template"). If true, the Litho component is converted to RenderNext.
 */
internal object RenderNextTemplateCheckFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC),
    returnType = "Z",
    parameters = listOf("L", "Ljava/lang/String;", "Ljava/lang/String;"),
    filters = listOf(
        literal(':'.code.toLong()),
        methodCall(smali = "Ljava/lang/String;->indexOf(I)I"),
        methodCall(smali = "Ljava/lang/String;->substring(II)Ljava/lang/String;"),
    )
)
