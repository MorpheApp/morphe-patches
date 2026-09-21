package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.*

private const val TRANSPORT = "${OUR_PREFIX}NativeHistoryTransport;"
private const val HISTORY_TICKET = "${OUR_PREFIX}NativeHistoryTransport\$Ticket;"
private const val HISTORY_SOURCE = "${OUR_PREFIX}NativeHistoryTransport\$Source;"

/** Keep host changes limited to typed bridges; resolve every host member before mutation. */
internal fun BytecodePatchContext.wireNativeHistory(account: NativeAccountContract) {
    // Several accessors can read the same browse response payload.
    val responseAccessors = NativeBrowseResponseFingerprint.matchAll().map { it.originalMethod }
    val contract =
        resolveNativeHistory(
            NativeBrowseServiceFingerprint.matchAll(1..1).single().originalClassDef,
            responseAccessors,
            NativeWatchEndpointFingerprint.matchAll(1..1).single().originalMethod,
            account.type,
            ::classDefBy,
        )
    val service = contract.service
    val request = contract.request
    val mutable = mutableClassDefBy(service.type)
    mutable.interfaces.add(HISTORY_SOURCE)
    fun bridge(name: String, args: List<String>, result: String, registers: Int, body: String) {
        if (mutable.methods.any { it.name == name && it.parameterTypes == args }) {
            throw PatchException("Series Tracker: native History bridge collision: $name")
        }
        val method =
            ImmutableMethod(
                    service.type,
                    name,
                    args.map { ImmutableMethodParameter(it, emptySet(), null) },
                    result,
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(registers, emptyList(), emptyList(), emptyList()),
                )
                .toMutable()
        method.addInstructions(0, body.trimIndent())
        mutable.methods.add(method)
    }
    bridge(
        "seriesTrackerHistory",
        listOf("Ljava/lang/String;", "Ljava/util/concurrent/Executor;"),
        "Ljava/util/concurrent/Future;",
        5,
        """
        ${if (contract.factory.parameterTypes.isEmpty())
            "invoke-virtual {p0}, ${contract.factory}"
        else
            "const/4 v0, 0x0\ninvoke-virtual {p0, v0}, ${contract.factory}"}
        move-result-object v0
        invoke-virtual {p1}, Ljava/lang/String;->isEmpty()Z
        move-result v1
        if-eqz v1, :continuation
        const-string v1, "FEhistory"
        invoke-virtual {v0, v1}, ${contract.routeSetter}
        goto :params
        :continuation
        invoke-virtual {v0, p1}, ${contract.continuationSetter}
        :params
        const/4 v1, 0x0
        new-array v1, v1, [B
        iput-object v1, v0, ${contract.clickTracking}
        invoke-static {p0, v0}, $TRANSPORT->before(${HISTORY_SOURCE}Ljava/lang/Object;)$HISTORY_TICKET
        move-result-object v1
        if-nez v1, :authorized
        const/4 v0, 0x0
        return-object v0
        :authorized
        invoke-virtual {p0, v0, p2}, ${contract.dispatch}
        move-result-object v0
        return-object v0
    """,
    )
    bridge(
        "seriesTrackerHistoryBytes",
        listOf("Ljava/lang/Object;"),
        "[B",
        5,
        """
        check-cast p1, ${contract.payload.definingClass}
        iget-object v0, p1, ${contract.payload}
        invoke-interface {v0}, Lcom/google/protobuf/MessageLite;->getSerializedSize()I
        move-result v1
        const v2, 0x400000
        if-gt v1, v2, :too_large
        invoke-interface {v0}, Lcom/google/protobuf/MessageLite;->toByteArray()[B
        move-result-object v0
        return-object v0
        :too_large
        const/4 v0, 0x0
        return-object v0
    """,
    )
    mutable.methods
        .single { it.signature() == contract.capture.signature() }
        .addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $TRANSPORT->attach($HISTORY_SOURCE)V",
        )
    val requestInterface = "${OUR_PREFIX}NativeHistoryTransport\$Request;"
    val identity = "${OUR_PREFIX}RecordingPrivacy\$Identity;"
    val requestClass = mutableClassDefBy(request.type)
    requestClass.interfaces.add(requestInterface)
    fun requestBridge(name: String, result: String, registers: Int, body: String) {
        if (requestClass.methods.any { it.name == name }) {
            throw PatchException("Series Tracker: native History request bridge collision: $name")
        }
        val method =
            ImmutableMethod(
                    request.type,
                    name,
                    emptyList(),
                    result,
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(registers, emptyList(), emptyList(), emptyList()),
                )
                .toMutable()
        method.addInstructions(0, body.trimIndent())
        requestClass.methods.add(method)
    }
    requestBridge(
        "seriesTrackerRoute",
        "Ljava/lang/String;",
        2,
        """
        iget-object v0, p0, ${contract.route}
        return-object v0
    """,
    )
    requestBridge(
        "seriesTrackerContinuation",
        "Ljava/lang/String;",
        2,
        """
        iget-object v0, p0, ${contract.continuation}
        return-object v0
    """,
    )
    requestBridge(
        "seriesTrackerRequestIdentity",
        identity,
        5,
        """
        invoke-virtual {p0}, ${contract.identity}
        move-result-object v0
        if-eqz v0, :unknown
        invoke-interface {v0}, ${account.type}->${account.id}
        move-result-object v1
        invoke-interface {v0}, ${account.type}->${account.incognito}
        move-result v2
        new-instance v3, $identity
        invoke-direct {v3, v1, v2}, $identity-><init>(Ljava/lang/String;Z)V
        return-object v3
        :unknown
        const/4 v0, 0x0
        return-object v0
    """,
    )
    // Save the request before the native implementation reuses its parameter registers.
    // Retain the exact future: the extension never cancels a host-owned request.
    val ticket = "${OUR_PREFIX}NativeHistoryTransport\$Ticket;"
    for (target in listOf(contract.dispatch, contract.genericDispatch)) {
        val signature = target.signature()
        val original = mutable.methods.single { it.signature() == signature }
        val name = original.name
        val alias = "seriesTrackerNative" + name
        if (mutable.methods.any { it.name == alias })
            throw PatchException("Series Tracker: native History alias collision")
        val args = original.parameterTypes.map { it.toString() }
        val result = original.returnType
        mutable.methods.remove(original)
        original.setName(alias)
        mutable.methods.add(original)
        bridge(
            name,
            args,
            result,
            args.size + 3,
            """
            invoke-static {p0, p1}, $TRANSPORT->before(${HISTORY_SOURCE}Ljava/lang/Object;)$ticket
            move-result-object v0
            invoke-virtual/range {p0 .. p${args.size}}, ${service.type}->$alias(${args.joinToString("")})$result
            move-result-object v1
            invoke-static {v0, v1}, $TRANSPORT->after(${ticket}Ljava/util/concurrent/Future;)V
            return-object v1
        """,
        )
    }
}
