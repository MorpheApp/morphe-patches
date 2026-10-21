/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3852
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.music.interaction.jam.addReferenceGetter
import app.morphe.patches.music.interaction.jam.implementsType
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val SEARCH_PATCH_CLASS =
    "Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch;"
private const val PHONE_SEARCH_CLIENT =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch$PhoneSearchClient;"
private const val PHONE_SEARCH_RESPONSE =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch$PhoneSearchResponse;"
private const val SEARCH_REQUEST =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSearchPatch$SearchRequest;"
private const val FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val EXECUTOR = "Ljava/util/concurrent/Executor;"

// region Phone search client and responses

/** Uses YTM's phone search client for Android Auto's online searches. */
internal fun BytecodePatchContext.patchSearch() {
    val requestType = PhoneSearchRequestFingerprint.originalClassDef.type
    val phoneSearchSubmissionMethod = PhoneSearchSubmissionFingerprint.originalMethod
    val submissionCalls = phoneSearchSubmissionMethod.instructions.mapNotNull { it.getReference<MethodReference>() }.distinct()
    val createSearchRequestMethod = submissionCalls.single { it.returnType == requestType && it.parameterTypes.isEmpty() }
    val clientClass = mutableClassDefBy(createSearchRequestMethod.definingClass)

    val submitSearchMethodReference = submissionCalls.single { call ->
        call.returnType == "V" && call.parameterTypes.size == 3 &&
                implementsType(requestType, call.parameterTypes.first().toString()) &&
                clientClass.methods.any {
                    it.parameterTypes.isEmpty() && it.returnType == call.parameterTypes.last().toString()
                }
    }
    val policyType = submitSearchMethodReference.parameterTypes.last().toString()
    val requestPolicyMethod = clientClass.methods.single { it.parameterTypes.isEmpty() && it.returnType == policyType }
    val requestSenderField = clientClass.instanceFields.single {
        implementsType(it.type, submitSearchMethodReference.definingClass)
    }
    val requestSenderClass = classDefBy(submitSearchMethodReference.definingClass)
    val submitSearchMethod = requestSenderClass.methods.single {
        MethodUtil.methodSignaturesMatch(it, submitSearchMethodReference)
    }
    val responseConverterField = submitSearchMethod.instructions.mapNotNull {
        if (it.opcode == Opcode.SGET_OBJECT) it.getReference<FieldReference>() else null
    }.single()
    // Use YTM's future-returning request method so the patch can handle completion and timeouts.
    val submitSearchFutureMethod = requestSenderClass.methods.single {
        it.returnType == FUTURE && it.parameterTypes.map(CharSequence::toString) == listOf(
            submitSearchMethodReference.parameterTypes.first().toString(), responseConverterField.type, EXECUTOR, policyType,
        )
    }

    val (queryField, paramsField) = PhoneSearchRequestFingerprint.instructionMatches.mapNotNull {
        it.instruction.getReference<FieldReference>()
    }
    // The phone uses the same request builder for catalog and uploaded music searches.
    val searchType = SearchRequestTypeFingerprint.originalClassDef.type
    val requestTypeField = classDefBy(requestType).instanceFields.single { it.type == searchType }
    val (catalogSearchValueField, uploadedSearchValueField) = SearchRequestTypeFingerprint.instructionMatches.mapNotNull {
        it.instruction.getReference<FieldReference>()
    }

    clientClass.interfaces.add(PHONE_SEARCH_CLIENT)
    clientClass.addInterfaceMethod(
        findInterfaceMethod(PHONE_SEARCH_CLIENT, "patch_search"),
        10,
        """
            invoke-virtual { p0 }, $createSearchRequestMethod
            move-result-object v1
            iput-object p1, v1, $queryField
            iput-object p3, v1, $paramsField
            if-eqz p2, :catalog
            sget-object v0, $uploadedSearchValueField
            goto :search_type
            :catalog
            sget-object v0, $catalogSearchValueField
            :search_type
            iput-object v0, v1, $requestTypeField
            invoke-virtual { p0 }, $requestPolicyMethod
            move-result-object v4
            iget-object v0, p0, $requestSenderField
            sget-object v2, $responseConverterField
            move-object v3, p4
            invoke-virtual/range { v0 .. v4 }, $submitSearchFutureMethod
            move-result-object v0
            return-object v0
        """,
    )

    val decodeSearchResponseMethod = classDefBy(requestSenderField.type).methods.single { method ->
        method.parameterTypes.singleOrNull()?.toString() == "Lcom/google/protobuf/MessageLite;" &&
                method.returnType == "Ljava/lang/Object;"
    }
    val responseType = decodeSearchResponseMethod.instructions.single { it.opcode == Opcode.NEW_INSTANCE }
        .getReference<TypeReference>()!!.type
    val responseConstructor = decodeSearchResponseMethod.instructions.mapNotNull { it.getReference<MethodReference>() }
        .single { it.definingClass == responseType && it.name == "<init>" }
    val responseMessageType = responseConstructor.parameterTypes.single().toString()
    val responseClass = mutableClassDefBy(responseType)
    val responseMessageField = responseClass.instanceFields.single { it.type == responseMessageType }
    responseClass.interfaces.add(PHONE_SEARCH_RESPONSE)
    responseClass.addInterfaceMethod(
        findInterfaceMethod(PHONE_SEARCH_RESPONSE, "patch_responseBytes"),
        1,
        """
            iget-object p0, p0, $responseMessageField
            invoke-virtual { p0 }, $responseMessageType->toByteArray()[B
            move-result-object p0
            return-object p0
        """,
    )
    hookPhoneClientOnServiceCreate(
        clientClass.type, PHONE_SEARCH_CLIENT,
        "$SEARCH_PATCH_CLASS->setPhoneSearchClient($PHONE_SEARCH_CLIENT)V",
    )
    hookSearchRequests()
}

// endregion

// region Android Auto search requests

/** Replaces Android Auto's online search while preserving YTM's caller checks and local Library search. */
private fun BytecodePatchContext.hookSearchRequests() {
    val deliverSearchResultsMethod = SearchResultDeliveryFingerprint.method
    val searchRequestClass = mutableClassDefBy(deliverSearchResultsMethod.definingClass)
    val queryField = searchRequestClass.instanceFields.single { it.type == "Ljava/lang/String;" }
    searchRequestClass.interfaces.add(SEARCH_REQUEST)
    searchRequestClass.addReferenceGetter("patch_query", queryField)
    searchRequestClass.addInterfaceMethod(findInterfaceMethod(SEARCH_REQUEST, "patch_deliverSearchResults"), 2, """
        invoke-virtual { p0, p1 }, $deliverSearchResultsMethod
        return-void
    """)
    val contentSupplierClass = mutableClassDefBy(ContentSupplierFingerprint.originalMethod.definingClass)
    val handleSearchRequestMethod = contentSupplierClass.methods.single {
        it.returnType == "V" && it.parameterTypes == listOf(searchRequestClass.type)
    }
    val instructions = handleSearchRequestMethod.instructions.toList()
    val callerContextReadIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>()?.let {
                    it.definingClass == searchRequestClass.type && it.type != "Ljava/lang/String;"
                } == true
    }
    // Insert after YTM's caller check and before it reads the next field to build the network request.
    val networkSearchStartIndex = instructions.withIndex().first { (index, instruction) ->
        index > callerContextReadIndex && instruction.opcode == Opcode.IGET_OBJECT &&
                instruction.getReference<FieldReference>()?.definingClass == searchRequestClass.type
    }.index
    // Existing jumps to this instruction must also run the hook before YTM sends the request.
    val hookResultRegister = handleSearchRequestMethod.findFreeRegister(networkSearchStartIndex)
    handleSearchRequestMethod.addInstructionsAtControlFlowLabel(networkSearchStartIndex, """
        invoke-static { p1 }, $SEARCH_PATCH_CLASS->handleSearch($SEARCH_REQUEST)Z
        move-result v$hookResultRegister
        if-eqz v$hookResultRegister, :native_search
        return-void
        :native_search
        nop
    """)
    // Offline Library searches can bypass the online hook entirely.
    // Start the deadline before either path and ignore callbacks arriving after completion.
    handleSearchRequestMethod.addInstructions(0, """
        invoke-static { p1 }, $SEARCH_PATCH_CLASS->onSearchRequest($SEARCH_REQUEST)V
    """)
    val completionRegister = deliverSearchResultsMethod.findFreeRegister(0)
    deliverSearchResultsMethod.addInstructions(0, """
        invoke-static { p0 }, $SEARCH_PATCH_CLASS->onSearchResult($SEARCH_REQUEST)Z
        move-result v$completionRegister
        if-nez v$completionRegister, :deliver_results
        return-void
        :deliver_results
        nop
    """)
}

// endregion
