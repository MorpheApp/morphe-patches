/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patches.youtube.misc.contexthook.NextEndpointParentFingerprint
import app.morphe.patches.youtube.misc.playservice.is_21_44_or_greater
import app.morphe.patches.youtube.video.series.NativeBaseRequestDescriptionFingerprint
import app.morphe.patches.youtube.video.series.NativeRequestIdentityFingerprint
import app.morphe.patches.youtube.video.series.NativeRequestStringSetterFingerprint
import app.morphe.patches.youtube.video.series.resolveNativeAccountContract
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val PAGES = "$EXTENSION_PACKAGE/PlaylistPages;"
private const val SOURCE = $$"$$EXTENSION_PACKAGE/PlaylistPages$Source;"
private const val ACCOUNT = $$"$$EXTENSION_PACKAGE/PlaylistPage$Account;"

/** Fetch authenticated source windows without invoking the native player response handlers. */
internal fun installPlaylistPages() {
    val requestType = NextEndpointParentFingerprint.originalClassDef.type
    val serializer = NextEndpointParentFingerprint.originalMethod.name
    fun requestField(label: String) = playlistPageRequestFieldFingerprint(requestType, serializer, label)
        .instructionMatches.last().fieldReference
    val playlist = requestField("playlistId")
    val params = requestField("params")
    val index = playlistPageIndexFingerprint(requestType, serializer)
        .instructionMatches.first().fieldReference
    val paramsSetter = NativeRequestStringSetterFingerprint(requestType, params.name).originalMethod
    val tracking = NativeBaseRequestDescriptionFingerprint.instructionMatches[1]
        .fieldReference
    val factory = playlistPageFactoryFingerprint(requestType).originalMethod
    val entryId = playlistPagePreparationFingerprint(requestType, factory, paramsSetter)
        .instructionMatches[3].fieldReference
    check(entryId != playlist) { "Native playlist entry ID must be distinct from playlist ID" }

    // The same watch response model the native queue engine receives.
    val receive = NativeQueueReceiveFingerprint
    val responseType = receive.originalMethod.parameterTypes.single().toString()
    val responsePanel = receive.instructionMatches[1].fieldReference
    val blocking = playlistPageServiceFingerprint(requestType, responseType).originalMethod
    val responseConstructor = nativeQueueResponseConstructorFingerprint(responseType).originalMethod
    val rawResponse = responseConstructor.parameterTypes.single().toString()
    val navigationType = PlaylistModelDescriptionFingerprint.originalMethod
        .findFieldFromToString(", navigationSets=").type
    val responseNavigation = nativeQueueResponseNavigationFingerprint(responseType, navigationType)
        .instructionMatches.single().fieldReference
    // The wrapper, when present, invokes this selector with all four flags off.
    val defaultNavigation = playlistPageNavigationSelectorFingerprint(navigationType).originalMethod
    val selectNavigation = """
        const/4 v1, 0x0
        invoke-virtual {v0, v1, v1, v1, v1}, $defaultNavigation
    """
    val navigationSet = classDefBy(defaultNavigation.returnType)
    val navigationConstructor = navigationSet.methods.single { it.name == "<init>" }
    val navigationProto = navigationSet.fields.single { it.type == navigationConstructor.parameterTypes.first() }

    val dispatch = playlistPageDispatchFingerprint(blocking).instructionMatches.single().methodReference
    val contextGetter = playlistPageContextFingerprint(blocking).instructionMatches.single().methodReference
    val asyncDispatch = playlistPageAsyncRequestFingerprint(dispatch).originalMethod
    val executor = playlistPageExecutorFingerprint(asyncDispatch).instructionMatches.first().methodReference
    val service = mutableClassDefBy(blocking.definingClass)
    val loadFactory = if (is_21_44_or_greater) {
        val provider = playlistPageFactoryProviderFingerprint(service.type, factory).instructionMatches
        """
            iget-object v0, p0, ${provider.first().fieldReference}
            invoke-interface {v0}, ${provider[1].methodReference}
            move-result-object v0
            check-cast v0, ${factory.definingClass}
        """
    } else {
        "iget-object v0, p0, ${service.fields.single { it.type == factory.definingClass }}"
    }
    val dispatchRead = playlistPageDispatchOwnerFingerprint(blocking, dispatch).instructionMatches
    val dispatchOwner = dispatchRead.first().fieldReference
    check(dispatchRead.first().getInstruction<TwoRegisterInstruction>().registerA ==
            dispatchRead.last().getInstruction<FiveRegisterInstruction>().registerC) {
        "Native playlist dispatcher must receive the loaded owner"
    }

    // Reuse the account identity contract also used by Series tracker privacy.
    val account = resolveNativeAccountContract()
    val accountType = account.type
    val requestIdentity = NativeRequestIdentityFingerprint(accountType).originalMethod

    service.interfaces.add(SOURCE)
    service.addBridgeMethod(
        "patch_playlistAccount",
        listOf(requestType),
        ACCOUNT,
        4,
        """
            invoke-virtual {p1}, $requestIdentity
            move-result-object v0
            if-eqz v0, :unknown
            invoke-interface {v0}, $accountType->${account.id}
            move-result-object v1
            if-eqz v1, :unknown
            invoke-interface {v0}, $accountType->${account.incognito}
            move-result v2
            new-instance v3, $ACCOUNT
            invoke-direct {v3, v1, v2}, $ACCOUNT-><init>(Ljava/lang/String;Z)V
            return-object v3

            :unknown
            const/4 v0, 0x0
            return-object v0
        """
    )
    service.addBridgeMethod(
        "patch_getPlaylistAccount",
        emptyList(),
        ACCOUNT,
        2,
        """
            $loadFactory
            const/4 v1, 0x0
            invoke-virtual {v0, v1}, $factory
            move-result-object v0
            invoke-virtual {p0, v0}, ${service.type}->patch_playlistAccount($requestType)$ACCOUNT
            move-result-object v0
            return-object v0
        """
    )
    service.addBridgeMethod(
        "patch_requestPlaylistPage",
        listOf("Ljava/lang/String;", "Ljava/lang/String;", "I", "Ljava/lang/String;", "Ljava/lang/String;", "[B", ACCOUNT),
        "Ljava/util/concurrent/Future;",
        4,
        """
            $loadFactory
            invoke-virtual {v0, p2}, $factory
            move-result-object v0
            invoke-virtual {p0, v0}, ${service.type}->patch_playlistAccount($requestType)$ACCOUNT
            move-result-object v1
            if-eqz p7, :unknown
            invoke-virtual {p7, v1}, $ACCOUNT->equals(Ljava/lang/Object;)Z
            move-result v1
            if-eqz v1, :unknown
            iput-object p1, v0, $playlist
            iput p3, v0, $index
            iput-object p5, v0, $entryId
            invoke-virtual {v0, p4}, $paramsSetter
            # Native validation requires non-null tracking even for read-only requests.
            iput-object p6, v0, $tracking
            # Return the native transport Future so timeout cancellation reaches the request.
            invoke-static {}, $executor
            move-result-object v1
            invoke-virtual {p0}, $contextGetter
            move-result-object v2
            iget-object v3, p0, $dispatchOwner
            invoke-virtual {v3, v0, v1, v2}, $asyncDispatch
            move-result-object v1
            return-object v1

            :unknown
            const/4 v0, 0x0
            return-object v0
        """
    )
    for (navigation in listOf(false, true)) {
        service.addBridgeMethod(
            if (navigation) "patch_getPlaylistNavigation" else "patch_getPlaylistPage",
            listOf("Ljava/lang/Object;"),
            "Lcom/google/protobuf/MessageLite;",
            if (navigation && defaultNavigation.parameterTypes.isNotEmpty()) 2 else 1,
            """
                check-cast p1, $rawResponse
                new-instance v0, $responseType
                invoke-direct {v0, p1}, $responseConstructor
                ${if (!navigation) "iget-object v0, v0, $responsePanel" else """
                    iget-object v0, v0, $responseNavigation
                    if-eqz v0, :done
                    $selectNavigation
                    move-result-object v0
                    if-eqz v0, :done
                    iget-object v0, v0, $navigationProto
                """}
                :done
                return-object v0
            """
        )
    }
    service.methods.single { AccessFlags.CONSTRUCTOR.isSet(it.accessFlags) }.apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstructionsAtControlFlowLabel(index, "invoke-static/range {p0 .. p0}, $PAGES->attach($SOURCE)V")
        }
    }
}
