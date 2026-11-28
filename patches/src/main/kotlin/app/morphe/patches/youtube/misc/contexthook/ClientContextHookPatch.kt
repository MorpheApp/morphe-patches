/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.contexthook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_20_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_21_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

private lateinit var clientFormFactorFieldRef : WeakReference<FieldReference>
private lateinit var clientInfoFieldRef : WeakReference<FieldReference>
private lateinit var clientVersionFieldRef : WeakReference<FieldReference>
private lateinit var messageLiteBuilderFieldRef : WeakReference<FieldReference>
private lateinit var messageLiteBuilderMethodRef : WeakReference<MethodReference>
private lateinit var osNameFieldRef : WeakReference<FieldReference>

enum class Endpoint(
    vararg val parentFingerprints: Fingerprint,
    var smaliInstructions: String = "",
) {
    BROWSE(BrowseEndpointParentFingerprint),
    GET_WATCH(
        GetWatchEndpointConstructorPrimaryFingerprint,
        GetWatchEndpointConstructorSecondaryFingerprint,
    ),
    GUIDE(GuideEndpointConstructorFingerprint),
    NEXT(NextEndpointParentFingerprint),
    PLAYER(PlayerEndpointParentFingerprint),
    REEL(
        // 21.21+ removed "reel/create_reel_items" and the replacement isn't clear.
        *(arrayOf(
            ReelItemWatchEndpointConstructorFingerprint,
            ReelWatchSequenceEndpointConstructorFingerprint,
        ) + if (!is_21_21_or_greater) arrayOf(ReelCreateItemsEndpointConstructorFingerprint) else emptyArray())
    ),
    SEARCH(SearchRequestBuildParametersFingerprint),
    TRANSCRIPT(TranscriptEndpointConstructorFingerprint);
}

val clientContextHookPatch = patch(
    description = "Hooks the context body of the endpoint.",
) {
    dependsOn(sharedExtensionPatch, versionCheckPatch)

    execute {
        val clientInfoField : FieldReference
        val clientVersionField : FieldReference
        val messageLiteBuilderField : FieldReference

        BuildDummyClientContextBodyFingerprint.let {
            it.method.apply {
                val clientInfoIndex = it.instructionMatches.last().index
                val clientVersionIndex = it.instructionMatches[2].index
                val messageLiteBuilderIndex = it.instructionMatches.first().index

                clientInfoField = getInstruction<ReferenceInstruction>(
                    clientInfoIndex
                ).reference as FieldReference
                clientInfoFieldRef = WeakReference(clientInfoField)

                clientVersionField = getInstruction<ReferenceInstruction>(
                    clientVersionIndex
                ).reference as FieldReference
                clientVersionFieldRef = WeakReference(clientVersionField)

                messageLiteBuilderField = getInstruction<ReferenceInstruction>(
                    messageLiteBuilderIndex
                ).reference as FieldReference
                messageLiteBuilderFieldRef = WeakReference(messageLiteBuilderField)
            }
        }

        // 20.51 has the listener in both of its request classes, which use the same builder method.
        val authenticationChangeListeners =
            if (is_21_20_or_greater) listOf(AuthenticationChangeListenerFingerprint.method)
            else AuthenticationChangeListenerLegacyFingerprint.matchAll().map { it.method }
        val messageLiteBuilderMethod = authenticationChangeListeners.map { listener ->
            listener.getInstruction<ReferenceInstruction>(
                listener.indexOfFirstInstructionOrThrow(
                    filter = methodCall(
                        opcode = Opcode.INVOKE_VIRTUAL,
                        parameters = emptyList(),
                        returnType = messageLiteBuilderField.definingClass
                    )
                )
            ).reference as MethodReference
        }.distinct().single()
        messageLiteBuilderMethodRef = WeakReference(messageLiteBuilderMethod)

        osNameFieldRef = WeakReference(
            BuildClientContextBodyFingerprint.method.getInstruction<ReferenceInstruction>(
                BuildClientContextBodyFingerprint.instructionMatches[1].index
            ).reference as FieldReference
        )

        clientFormFactorFieldRef = WeakReference(
            SetClientFormFactorFingerprint.method.getInstruction<ReferenceInstruction>(
                SetClientFormFactorFingerprint.instructionMatches.first().index
            ).reference as FieldReference
        )
    }

    finalize {
        val clientInfoField = clientInfoFieldRef.get()!!
        val messageLiteBuilderField = messageLiteBuilderFieldRef.get()
        val messageLiteBuilderMethod = messageLiteBuilderMethodRef.get()
        val helperMethodName = "patch_setClientContext"

        Endpoint.entries.filter {
            it.smaliInstructions.isNotEmpty()
        }.forEach { endpoint ->
            endpoint.parentFingerprints.forEach { parentFingerprint ->
                // Use locally declared fingerprint because internally fingerprint caches the match.
                // Could use Fingerprint.clearMatch() but creating a new instance also works.
                val endpointRequestBodyFingerprint = Fingerprint(
                    classFingerprint = parentFingerprint,
                    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
                    returnType = "V",
                    parameters = listOf(),
                )

                endpointRequestBodyFingerprint.let {
                    // 21.05+ clobbers p0 register.
                    it.method.cloneParameters().apply {
                        it.classDef.methods.add(
                            ImmutableMethod(
                                definingClass,
                                helperMethodName,
                                emptyList(),
                                "V",
                                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                                annotations,
                                null,
                                MutableMethodImplementation(5),
                            ).toMutable().apply {
                                addInstructionsWithLabels(
                                    0,
                                    """
                                        invoke-virtual { p0 }, $messageLiteBuilderMethod
                                        move-result-object v0
                                        iget-object v0, v0, $messageLiteBuilderField
                                        check-cast v0, ${clientInfoField.definingClass}
                                        iget-object v1, v0, $clientInfoField
                                        if-eqz v1, :ignore
                                        ${endpoint.smaliInstructions}
                                        :ignore
                                        return-void
                                    """
                                )
                            }
                        )

                        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                            addInstructionsAtControlFlowLabel(
                                index,
                                "invoke-direct/range { p0 .. p0 }, $definingClass->$helperMethodName()V"
                            )
                        }
                    }
                }
            }
        }
    }
}

fun addClientFormFactorHook(endPoint: Endpoint, descriptor: String) {
    val clientFormFactorField = clientFormFactorFieldRef.get()!!
    val smaliInstructions = """
        iget v2, v1, $clientFormFactorField
        invoke-static { v2 }, $descriptor
        move-result v2
        iput v2, v1, $clientFormFactorField
        """

    endPoint.smaliInstructions += smaliInstructions
}

/**
 * Same as [addClientFormFactorHook] for the browse endpoint, but also passes the browse ID
 * so the form factor can be chosen per page.
 *
 * @param descriptor Method that takes the form factor and the browse ID, and returns the form factor.
 */
fun addBrowseClientFormFactorHook(descriptor: String) {
    val clientFormFactorField = clientFormFactorFieldRef.get()!!
    val browseIdField = if (is_20_51_or_greater) {
        BrowseEndpointBrowseIdFingerprint.instructionMatches.last()
    } else {
        BrowseEndpointBrowseIdLegacyFingerprint.instructionMatches.first()
    }.fieldReference
    val smaliInstructions = """
        iget v2, v1, $clientFormFactorField
        iget-object v3, p0, $browseIdField
        invoke-static { v2, v3 }, $descriptor
        move-result v2
        iput v2, v1, $clientFormFactorField
        """

    Endpoint.BROWSE.smaliInstructions += smaliInstructions
}

fun addClientVersionHook(endPoint: Endpoint, descriptor: String) {
    val clientVersionField = clientVersionFieldRef.get()!!
    val smaliInstructions = """
        iget-object v2, v1, $clientVersionField
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $clientVersionField
        """

    endPoint.smaliInstructions += smaliInstructions
}

fun addOSNameHook(endPoint: Endpoint, descriptor: String) {
    val osNameField = osNameFieldRef.get()!!
    val smaliInstructions = """
        iget-object v2, v1, $osNameField
        invoke-static { v2 }, $descriptor
        move-result-object v2
        iput-object v2, v1, $osNameField
        """

    endPoint.smaliInstructions += smaliInstructions
}
