package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.*
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val ACTIONS = "Lapp/morphe/extension/reddit/patches/archive/NativeArchiveActions;"
private const val CONT = "Lapp/morphe/extension/reddit/patches/archive/ArchiveActionContinuation;"
private const val STATE = "Lapp/morphe/extension/reddit/patches/archive/ArchiveVoteState;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val VOTE = "Lcom/reddit/domain/model/vote/VoteDirection;"

/** Reddit 2026.40.0 only. All names stay in the patcher, with checked signatures and DI provenance. */
context(context: BytecodePatchContext)
internal fun installNativeArchiveActions() = with(context) {
    fun method(owner: String, name: String, parameters: List<String>, result: String) =
        classDefBy(owner).methods.singleOrNull {
            it.name == name && it.parameterTypes == parameters && it.returnType == result && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: throw PatchException("Missing Reddit action method: $owner->$name$parameters$result")
    fun field(owner: String, name: String, type: String) = classDefBy(owner).fields.singleOrNull {
        it.name == name && it.type == type && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } ?: throw PatchException("Missing Reddit action field: $owner->$name:$type")
    fun bridge(name: String, registers: Int, code: String) = replaceArchiveBridge(name, registers, code, ACTIONS)
    val scopeType = "Luq5;"
    val postType = "Lcom/reddit/link/impl/data/repository/d;"
    val commentType = "Lcom/reddit/comment/data/datasource/b;"
    val postField = field(scopeType, "Pa", "Lpff;")
    val commentField = field(scopeType, "b9", "Lgp5;")
    val postProvider = method("Lpff;", "invoke", emptyList(), OBJECT)
    val commentProvider = method("Lgp5;", "invoke", emptyList(), OBJECT)
    // Verify the provider's switch arm actually constructs the expected service.
    fun providerBranch(name: String, key: Int, expected: String) {
        val provider = method("Lgp5;", name, emptyList(), OBJECT)
        val instructions = provider.implementation!!.instructions.toList()
        val addresses = instructions.runningFold(0) { address, ins -> address + ins.codeUnits }
        val index = instructions.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH }
        if (index < 0) throw PatchException("Missing Reddit service provider switch")
        val switch = instructions[index] as OffsetInstruction
        val payload = instructions[addresses.indexOf(addresses[index] + switch.codeOffset)] as SwitchPayload
        val arm = payload.switchElements.single { it.key == key }
        val start = addresses.indexOf(addresses[index] + arm.offset)
        val body = instructions.drop(start).takeWhile { it.opcode != Opcode.RETURN_OBJECT }
        if (body.none { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == expected })
            throw PatchException("Reddit action provider $key no longer creates $expected")
    }
    providerBranch("X", 1390, postType)
    providerBranch("b0", 1479, commentType)
    // Check field -> factory binding, including the deferred singleton link repository.
    val scopeMethods = classDefBy(scopeType).methods
    val postWiring = scopeMethods.single { it.name == "<init>" }.implementation!!.instructions.toList()
    val postPosition = postWiring.indexOfLast { it.opcode == Opcode.IGET_OBJECT && (it as ReferenceInstruction).reference == postField }
    if (postPosition < 0 || (postWiring[postPosition + 1] as? WideLiteralInstruction)?.wideLiteral != 1390L ||
        (postWiring[postPosition + 2] as? ReferenceInstruction)?.reference.toString() != "Lr23;->C(Luq5;ILpff;)V")
        throw PatchException("Reddit link repository provider field changed")
    val commentWiring = scopeMethods.single { it.name == "oh" }.implementation!!.instructions.toList()
    val commentPosition = commentWiring.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference == commentField }
    if (commentPosition < 0 || commentWiring.subList(maxOf(0, commentPosition - 5), commentPosition).none {
            (it as? WideLiteralInstruction)?.wideLiteral == 1479L
        }) throw PatchException("Reddit comment service provider field changed")

    val scope = method("Lcom/reddit/frontpage/di/b;", "c", emptyList(), "Lnjm0;")
    val sessionContainer = method(scopeType, "ve", emptyList(), "Lkre0;")
    val session = method("Lfqe0;", "b", emptyList(), "Lcom/reddit/session/Session;")
    val loggedIn = method("Lcom/reddit/session/Session;", "isLoggedIn", emptyList(), "Z")
    bridge("scope", 1, "invoke-static {}, $scope\nmove-result-object v0\nreturn-object v0")
    bridge("signedIn", 2, """
        check-cast p0, $scopeType
        invoke-virtual {p0}, $sessionContainer
        move-result-object v0
        invoke-interface {v0}, $session
        move-result-object v0
        invoke-interface {v0}, $loggedIn
        move-result v0
        return v0
    """)
    bridge("service", 3, """
        check-cast p0, $scopeType
        if-nez p1, :comment
        iget-object v0, p0, $postField
        invoke-virtual {v0}, $postProvider
        move-result-object v0
        return-object v0
        :comment
        iget-object v0, p0, $commentField
        invoke-virtual {v0}, $commentProvider
        move-result-object v0
        return-object v0
    """)
    val suspendType = "Lkotlin/coroutines/jvm/internal/SuspendLambda;"
    val continuationType = "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
    val continuation = classDefBy(suspendType).methods.single { it.name == "<init>" && it.parameterTypes.size == 2 }.parameterTypes[1].toString()
    val suspendCtor = method(suspendType, "<init>", listOf("I", continuation), "V")
    val callback = mutableClassDefBy(CONT)
    callback.setSuperClass(suspendType)
    val callbackCtor = callback.methods.single { it.name == "<init>" }
    val ctor = ImmutableMethod(CONT, "<init>", emptyList(), "V", callbackCtor.accessFlags,
        callbackCtor.annotations, callbackCtor.hiddenApiRestrictions, MutableMethodImplementation(3)).toMutable()
    ctor.addInstructions(0, """
        const/4 v0, 0x1
        const/4 v1, 0x0
        invoke-direct {p0, v0, v1}, $suspendCtor
        new-instance v1, Ljava/util/concurrent/CountDownLatch;
        invoke-direct {v1, v0}, Ljava/util/concurrent/CountDownLatch;-><init>(I)V
        iput-object v1, p0, $CONT->done:Ljava/util/concurrent/CountDownLatch;
        return-void
    """)
    callback.methods.remove(callbackCtor); callback.methods.add(ctor)
    val emptyContext = field("Lkotlin/coroutines/EmptyCoroutineContext;", "INSTANCE", "Lkotlin/coroutines/EmptyCoroutineContext;")
    val contextMethod = ImmutableMethod(CONT, "getContext", emptyList(), "Lkotlin/coroutines/CoroutineContext;",
        AccessFlags.PUBLIC.value, emptySet(), emptySet(), MutableMethodImplementation(2)).toMutable()
    contextMethod.addInstructions(0, "sget-object v0, $emptyContext\nreturn-object v0")
    callback.methods.add(contextMethod)
    val suspended = field("Lkotlin/coroutines/intrinsics/CoroutineSingletons;", "COROUTINE_SUSPENDED", "Lkotlin/coroutines/intrinsics/CoroutineSingletons;")
    bridge("suspended", 1, "sget-object v0, $suspended\nreturn-object v0")
    val postLoad = method(postType, "w", listOf(STRING, "Lles;", continuation), OBJECT)
    val options = method("Lles;", "<init>", listOf("I", "Z", "Z"), "V")
    val commentLoad = method(commentType, "j", listOf(STRING, continuationType), OBJECT)
    bridge("load", 8, """
        if-nez p2, :comment
        check-cast p0, $postType
        new-instance v0, Lles;
        const/16 v1, 0x7e
        const/4 v2, 0x0
        invoke-direct {v0, v1, v2, v2}, $options
        invoke-virtual {p0, p1, v0, p3}, $postLoad
        move-result-object v0
        return-object v0
        :comment
        check-cast p0, $commentType
        invoke-virtual {p0, p1, p3}, $commentLoad
        move-result-object v0
        return-object v0
    """)
    val postVote = method(postType, "l0", listOf(STRING, VOTE, suspendType), OBJECT)
    val commentVote = method(commentType, "D", listOf(STRING, VOTE, continuation), OBJECT)
    val up = field(VOTE, "UP", VOTE); val down = field(VOTE, "DOWN", VOTE); val none = field(VOTE, "NONE", VOTE)
    bridge("vote", 7, """
        sget-object v0, $none
        if-eqz p3, :ready
        if-ltz p3, :down
        sget-object v0, $up
        goto :ready
        :down
        sget-object v0, $down
        :ready
        if-nez p2, :comment
        check-cast p0, $postType
        invoke-virtual {p0, p1, v0, p4}, $postVote
        move-result-object v0
        return-object v0
        :comment
        check-cast p0, $commentType
        invoke-virtual {p0, p1, v0, p4}, $commentVote
        move-result-object v0
        return-object v0
    """)
    val throwFailure = method("Lkotlin/b;", "a", listOf(OBJECT), "V")
    val successValue = field("Lyyh0;", "a", OBJECT)
    bridge("unwrap", 3, """
        invoke-static {p0}, $throwFailure
        instance-of v0, p0, Lyyh0;
        if-eqz v0, :failed
        check-cast p0, Lyyh0;
        iget-object v0, p0, $successValue
        return-object v0
        :failed
        new-instance v0, Ljava/lang/IllegalStateException;
        const-string v1, "Reddit could not load this item"
        invoke-direct {v0, v1}, Ljava/lang/IllegalStateException;-><init>(Ljava/lang/String;)V
        throw v0
    """)
    val updateSuccess = method("Lcom/reddit/domain/model/UpdateResponse;", "getSuccess", emptyList(), "Z")
    bridge("success", 3, """
        invoke-static {p0}, $throwFailure
        if-nez p1, :comment
        instance-of v0, p0, Lcom/reddit/domain/model/UpdateResponse;
        if-eqz v0, :no
        check-cast p0, Lcom/reddit/domain/model/UpdateResponse;
        invoke-virtual {p0}, $updateSuccess
        move-result v0
        return v0
        :comment
        instance-of v0, p0, Lyyh0;
        return v0
        :no
        const/4 v0, 0x0
        return v0
    """)
    val post = "Lcom/reddit/domain/model/Link;"; val comment = "Lcom/reddit/domain/model/Comment;"
    val scorePost = method(post, "getScore", emptyList(), "I")
    val scoreComment = method(comment, "getScore", emptyList(), "I")
    val archivedPost = method(post, "getArchived", emptyList(), "Z")
    val archivedComment = method(comment, "getArchived", emptyList(), "Z")
    val hiddenPost = method(post, "getHideScore", emptyList(), "Z")
    val hiddenComment = method(comment, "getScoreHidden", emptyList(), "Z")
    val directionPost = method(post, "getVoteDirection", emptyList(), VOTE)
    val directionComment = method(comment, "getVoteState", emptyList(), "Ljava/lang/Boolean;")
    val directionValue = method(VOTE, "getValue", emptyList(), "I")
    bridge("state", 7, """
        if-nez p1, :comment
        check-cast p0, $post
        invoke-virtual {p0}, $scorePost
        move-result v0
        invoke-virtual {p0}, $directionPost
        move-result-object v1
        invoke-virtual {v1}, $directionValue
        move-result v1
        invoke-virtual {p0}, $archivedPost
        move-result v2
        invoke-virtual {p0}, $hiddenPost
        move-result v3
        goto :state
        :comment
        check-cast p0, $comment
        invoke-virtual {p0}, $scoreComment
        move-result v0
        invoke-virtual {p0}, $directionComment
        move-result-object v4
        const/4 v1, 0x0
        if-eqz v4, :commentFlags
        invoke-virtual {v4}, Ljava/lang/Boolean;->booleanValue()Z
        move-result v1
        if-nez v1, :commentFlags
        const/4 v1, -0x1
        :commentFlags
        invoke-virtual {p0}, $archivedComment
        move-result v2
        invoke-virtual {p0}, $hiddenComment
        move-result v3
        :state
        invoke-static {v0, v1, v2, v3}, $STATE->live(IIZZ)$STATE
        move-result-object v0
        return-object v0
    """)
    val votes = "Lcom/reddit/vote/domain/a;"
    val normalize = method(votes, "c", listOf(STRING), STRING)
    val cache = field(votes, "b", "Lybt;")
    val readCache = method("Lybt;", "c", listOf(OBJECT), OBJECT)
    val voteInstance = field(votes, "a", votes)
    val publish = method(votes, "a", listOf("I", STRING), "V")
    bridge("cachedDirection", 2, """
        invoke-static {p0}, $normalize
        move-result-object p0
        sget-object v0, $cache
        invoke-virtual {v0, p0}, $readCache
        move-result-object v0
        check-cast v0, Ljava/lang/Integer;
        return-object v0
    """)
    bridge("publish", 3, """
        invoke-static {p0}, $normalize
        move-result-object p0
        sget-object v0, $voteInstance
        invoke-virtual {v0, p1, p0}, $publish
        return-void
    """)
    val subreddit = "Lcom/reddit/domain/model/SubredditDetail;"
    val subredditId = method(post, "getSubredditId", emptyList(), STRING)
    val subredditDetail = method(post, "getSubredditDetail", emptyList(), subreddit)
    val communityIcon = method(subreddit, "getCommunityIconUrl", emptyList(), STRING)
    val iconImage = method(subreddit, "getIconImage", emptyList(), STRING)
    val joined = method(subreddit, "getUserIsSubscriber", emptyList(), "Ljava/lang/Boolean;")
    val crosspostable = method(post, "shouldAllowCrossposts", emptyList(), "Z")
    bridge("postInfo", 6, """
        check-cast p0, $post
        const/4 v0, 0x4
        new-array v0, v0, [Ljava/lang/String;
        invoke-virtual { p0 }, $subredditId
        move-result-object v1
        const/4 v2, 0x0
        aput-object v1, v0, v2
        invoke-virtual { p0 }, $crosspostable
        move-result v1
        invoke-static { v1 }, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;
        move-result-object v1
        const/4 v2, 0x3
        aput-object v1, v0, v2
        invoke-virtual { p0 }, $subredditDetail
        move-result-object p0
        if-eqz p0, :done
        invoke-virtual { p0 }, $communityIcon
        move-result-object v1
        if-eqz v1, :fallback
        invoke-virtual { v1 }, Ljava/lang/String;->isEmpty()Z
        move-result v2
        if-eqz v2, :icon
        :fallback
        invoke-virtual { p0 }, $iconImage
        move-result-object v1
        :icon
        const/4 v2, 0x1
        aput-object v1, v0, v2
        invoke-virtual { p0 }, $joined
        move-result-object v1
        invoke-static { v1 }, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;
        move-result-object v1
        const/4 v2, 0x2
        aput-object v1, v0, v2
        :done
        return-object v0
    """)
    val joinType = "Lcom/reddit/feeds/ui/events/JoinedSubredditEvent;"
    val joinState = "Lcom/reddit/feeds/ui/events/JoinedSubredditEvent${'$'}State;"
    val joinConstructor = classDefBy(joinType).methods.single { it.name == "<init>" }
    if (joinConstructor.parameterTypes.take(5) != listOf(STRING, STRING, STRING, joinState, "Ljava/lang/Boolean;") ||
        joinConstructor.parameterTypes.size != 7 || joinConstructor.parameterTypes.last() != "I")
        throw PatchException("Reddit's community action structure changed")
    val subscribe = field(joinState, "Subscribe", joinState)
    bridge("joinEvent", 11, """
        new-instance v0, $joinType
        move-object v1, p0
        move-object v2, p1
        move-object v3, p2
        sget-object v4, $subscribe
        const/4 v5, 0x0
        const/4 v6, 0x0
        const/16 v7, 0x30
        invoke-direct/range { v0 .. v7 }, $joinConstructor
        return-object v0
    """)
    val repostType = "Lcom/reddit/feeds/impl/ui/events/OnClickRepost;"
    val repostConstructor = method(repostType, "<init>", listOf(STRING, STRING, "Z", STRING), "V")
    bridge("repostEvent", 7, """
        new-instance v0, $repostType
        move-object v1, p0
        move-object v2, p0
        const/4 v3, 0x0
        move-object v4, p1
        invoke-direct/range { v0 .. v4 }, $repostConstructor
        return-object v0
    """)
    listOf("Success" to "joinSucceeded", "Failure" to "joinFailed").forEach { (result, name) ->
        val callbackType = "Lcom/reddit/feeds/impl/domain/paging/JoinedSubredditHandler${'$'}on$result${'$'}2;"
        val callback = Fingerprint(definingClass = callbackType, name = "invokeSuspend", parameters = listOf(OBJECT),
            returnType = OBJECT, filters = listOf(fieldAccess(type = joinType, opcode = Opcode.IGET_OBJECT)))
        val event = callback.instructionMatches.single()
        bridge(name, 2, """
            const/4 v0, ${if (result == "Success") "0x1" else "0x0"}
            invoke-static { p0, v0 }, $ACTIONS->joinResult(Ljava/lang/Object;Z)V
            return-void
        """)
        val register = (event.instruction as OneRegisterInstruction).registerA
        callback.method.addInstructions(event.index + 1, "invoke-static { v$register }, $ACTIONS->$name(Ljava/lang/Object;)V")
    }
    // This is the host's standard auth activity; its onCreate supports an empty intent.
    classDefBy("Lcom/reddit/auth/login/screen/AuthActivityKt;")
    println("Installed archive actions: account-scoped native post/comment lookup, voting, vote cache and coroutine bridge")
}
