package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val OBJECT = "Ljava/lang/Object;"
private const val NATIVE_ITEM = "Lapp/morphe/extension/reddit/patches/archive/ArchiveNativeItem;"
internal const val NATIVE_SECTION = "Lapp/morphe/extension/reddit/patches/archive/ArchiveNativeSection;"
private const val INSTANT = "Ljava/time/Instant;"

private fun Method.references() = implementation?.instructions?.mapNotNull {
    ((it as? ReferenceInstruction)?.reference as? MethodReference)
}?.distinctBy { it.toString() }?.toList().orEmpty()

context(context: BytecodePatchContext)
internal fun installProfileMerge(sectionInterface: String, sectionContent: Method, sectionKey: Method) = with(context) {
    fun mutableMethodBy(method: Method) = mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
    }
    fun model(text: String) = Fingerprint(name = "toString", returnType = "Ljava/lang/String;", filters = listOf(string(text))).method
    val host = nativeFeedState.classDef
    val hostType = host.type
    val feedString = nativeFeedModel.method
    val feedType = feedString.definingClass
    val feedFields = constructorFields(nativeFeedModel.classDef.methods.single { it.name == "<init>" })
    val sortField = feedFields[8]
    val sortType = sortField.type
    val sortConstructor = classDefBy(sortType).methods.single { it.name == "<init>" && it.parameterTypes.size == 2 }
    val sortEnum = constructorFields(sortConstructor)[0]
    val sortProviderType = host.fields.map { it.type }.single { type ->
        classDefByOrNull(type)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) && it.methods.count() == 1 &&
                it.methods.single().returnType == sortType && it.methods.single().parameterTypes.isEmpty() } == true
    }
    val sortProviderField = host.fields.single { it.type == sortProviderType }
    replaceArchiveBridge("hostSort", 1, """
        check-cast p0, $hostType
        iget-object p0, p0, $sortProviderField
        invoke-interface { p0 }, ${classDefBy(sortProviderType).methods.single()}
        move-result-object p0
        return-object p0
    """)
    val sortProviderClass = classDefBy { owner -> owner.interfaces.contains(sortProviderType) && owner.methods.any { method ->
        method.references().any { it.definingClass == "Lcom/reddit/screen/listing/usecase/a;" } &&
            method.implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.name == "SUBMITTED_POSTS"
            } == true
    } }
    val sortProviderMethod = sortProviderClass.methods.single { it.returnType == sortType && it.parameterTypes.isEmpty() }
    val sortLookup = sortProviderMethod.references().first { it.definingClass == "Lcom/reddit/screen/listing/usecase/a;" }
    val listingName = sortProviderMethod.references().single { it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
    // The synthetic provider stores the listing-name delegate before the use-case delegate.
    val providerConstructor = sortProviderClass.methods.single { it.name == "<init>" }
    val providerFields = constructorFields(providerConstructor)
    if (providerFields[0].type != OBJECT || providerFields[1].type != OBJECT || providerFields.last().type != "I")
        throw PatchException("Unexpected submitted-post sort provider")
    val verifiedListingField = providerFields[0]
    replaceArchiveBridge("newSort", 3, """
        new-instance v0, $sortType
        sget-object v1, ${sortEnum.type}->NEW:${sortEnum.type}
        const/4 v2, 0x0
        invoke-direct { v0, v1, v2 }, $sortConstructor
        return-object v0
    """)
    replaceArchiveBridge("bindSortHost", 4, """
        check-cast p0, $hostType
        iget-object v0, p0, $sortProviderField
        instance-of v1, v0, ${sortProviderClass.type}
        if-eqz v1, :done
        check-cast v0, ${sortProviderClass.type}
        iget-object v1, v0, $verifiedListingField
        check-cast v1, ${listingName.definingClass}
        invoke-interface { v1 }, $listingName
        move-result-object v1
        invoke-static { p0, v0, v1 }, $ARCHIVE_CLASS->bindSort(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V
        :done
        return-void
    """)
    val sortMutable = mutableMethodBy(sortProviderMethod)
    val sortReturns = sortMutable.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
    val oldSortCount = sortMutable.implementation!!.registerCount
    val (sortPatched, sortShift) = sortMutable.addFreeRegisters(2)
    sortPatched.addInstructions(sortShift, "move-object/16 v$oldSortCount, v${oldSortCount - 1}")
    sortReturns.reversed().forEach { (index, instruction) ->
        val register = (instruction as OneRegisterInstruction).registerA
        sortPatched.addInstructionsAtControlFlowLabel(index + sortShift + 1, """
            move-object/16 v${oldSortCount + 1}, v$register
            invoke-static/range { v$oldSortCount .. v${oldSortCount + 1} }, $ARCHIVE_CLASS->sort(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$register
            check-cast v$register, $sortType
        """)
    }
    val saveSort = classDefBy(sortLookup.definingClass).methods.single { it.parameterTypes.size == 3 &&
            it.parameterTypes[0] == "Ljava/lang/String;" && it.parameterTypes[1] == sortType }
    mutableMethodBy(saveSort).addInstructions(0, "invoke-static/range { p1 .. p2 }, $ARCHIVE_CLASS->savedSort(Ljava/lang/String;Ljava/lang/Object;)V")

    listOf("feedMore" to 2, "feedRefreshing" to 4).forEach { (name, index) ->
        val field = feedFields[index]
        replaceArchiveBridge(name, 1, "check-cast p0, $feedType\niget-boolean p0, p0, $field\nreturn p0")
    }
    replaceArchiveBridge("feedSort", 1, """
        check-cast p0, $feedType
        iget-object p0, p0, $sortField
        if-eqz p0, :none
        iget-object p0, p0, $sortEnum
        if-eqz p0, :none
        invoke-virtual { p0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
        move-result-object p0
        return-object p0
        :none
        const-string p0, ""
        return-object p0
    """)
    replaceArchiveBridge("sectionKey", 1, "check-cast p0, $sectionInterface\ninvoke-interface { p0 }, $sectionKey\nmove-result-object p0\nreturn-object p0")
    val pagingType = host.fields.map { it.type }.single { type -> classDefByOrNull(type)?.methods?.any {
        it.parameterTypes == listOf("Lcom/reddit/feeds/ui/events/FeedRefreshType;", "Z") && it.returnType == "V"
    } == true }
    val pagingField = host.fields.single { it.type == pagingType }
    val refresh = classDefBy(pagingType).methods.single { it.parameterTypes == listOf("Lcom/reddit/feeds/ui/events/FeedRefreshType;", "Z") }
    replaceArchiveBridge("refreshHost", 3, """
        check-cast p0, $hostType
        iget-object p0, p0, $pagingField
        sget-object v0, Lcom/reddit/feeds/ui/events/FeedRefreshType;->PULL_TO_REFRESH:Lcom/reddit/feeds/ui/events/FeedRefreshType;
        const/4 v1, 0x0
        invoke-interface { p0, v0, v1 }, $refresh
        return-void
    """)

    val profiles = listOf("Posts", "Comments").map { kind ->
        classDefBy("Lcom/reddit/profile/submitted${kind.lowercase()}feed/viewmodel/Submitted${kind}ViewModel;")
    }
    val eventInterface = profiles.first().fields.map { it.type }.single { type ->
        host.interfaces.contains(type) && classDefBy(type).methods.any { it.name == "onEvent" }
    }
    val profileBodies = profiles.mapIndexed { index, owner ->
        val param = owner.fields.map { it.type }.single { type -> classDefByOrNull(type)?.methods?.any { method ->
            method.name == "toString" && method.implementation?.instructions?.any { ins ->
                ((ins as? ReferenceInstruction)?.reference as? StringReference)?.string == "UserParam(username="
            } == true
        } == true }
        val paramField = owner.fields.single { it.type == param }
        val username = constructorFields(classDefBy(param).methods.single { it.name == "<init>" })[0]
        val eventField = owner.fields.single { it.type == eventInterface }
        val session = owner.fields.single { it.type == "Lcom/reddit/session/Session;" }
        val constructor = owner.methods.single { it.name == "<init>" }
        val mutable = mutableMethodBy(constructor)
        mutable.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.reversed().forEach { (at, _) ->
            mutable.addInstructions(at, "invoke-static/range { p0 .. p0 }, $ARCHIVE_CLASS->bindHost(Ljava/lang/Object;)V")
        }
        """
            instance-of v0, p0, ${owner.type}
            if-eqz v0, :profile${index + 1}
            check-cast p0, ${owner.type}
            iget-object v0, p0, $eventField
            instance-of v1, v0, $hostType
            if-eqz v1, :done
            iget-object v1, p0, $paramField
            iget-object v1, v1, $username
            const/4 v2, $index
            iget-object v3, p0, $session
            invoke-interface { v3 }, Lcom/reddit/session/Session;->getUsername()Ljava/lang/String;
            move-result-object v3
            invoke-static { v0, v1, v2, v3 }, $ARCHIVE_CLASS->bind(Ljava/lang/Object;Ljava/lang/String;ZLjava/lang/String;)V
            return-void
            :profile${index + 1}
        """
    }
    replaceArchiveBridge("bindHost", 5, profileBodies.joinToString("\n") + "\n:done\nreturn-void")

    val requestModel = model("FeedPageParams(key=")
    val refreshFlag = constructorFields(classDefBy(requestModel.definingClass).methods.single { it.name == "<init>" && it.parameterTypes.size == 8 })[4]
    val requestBodies = listOf("Posts", "Comments").mapIndexed { index, kind ->
        val continuation = classDefBy("Lcom/reddit/profile/submitted${kind.lowercase()}feed/data/Submitted${kind}FeedPagingDataSource${'$'}getPagedData${'$'}1;")
        val sourceType = continuation.methods.single { it.name == "<init>" }.parameterTypes.first().toString()
        val source = classDefBy(sourceType)
        val params = source.fields.map { it.type }.single { type -> classDefByOrNull(type)?.methods?.any { method ->
            method.name == "toString" && method.implementation?.instructions?.any { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == "UserParam(username="
            } == true
        } == true }
        val userField = source.fields.single { it.type == params }
        val username = constructorFields(classDefBy(params).methods.single { it.name == "<init>" })[0]
        val loadMethod = source.methods.single { it.parameterTypes.size == 2 && it.parameterTypes.first() == requestModel.definingClass && it.returnType == OBJECT }
        mutableMethodBy(loadMethod).addInstructions(0, "invoke-static/range { p0 .. p1 }, $ARCHIVE_CLASS->captureRequest(Ljava/lang/Object;Ljava/lang/Object;)V")
        val filter = if (index == 0) {
            // Submitted-post flair state is a dedicated holder with one mutable String.
            val holder = source.fields.single { field -> classDefByOrNull(field.type)?.let {
                it.fields.count() == 1 && it.fields.single().type == "Ljava/lang/String;" &&
                        !AccessFlags.FINAL.isSet(it.fields.single().accessFlags) && it.methods.count() <= 1
            } == true }
            "iget-object v2, p0, $holder\niget-object v2, v2, ${classDefBy(holder.type).fields.single()}"
        } else "const/4 v2, 0x0"
        """
            instance-of v0, p0, $sourceType
            if-eqz v0, :source${index + 1}
            check-cast p0, $sourceType
            iget-object v0, p0, $userField
            iget-object v0, v0, $username
            const/4 v1, $index
            $filter
            check-cast p1, ${requestModel.definingClass}
            iget-boolean v3, p1, $refreshFlag
            move-object v4, p1
            invoke-static { v0, v1, v2, v3, v4 }, $ARCHIVE_CLASS->request(Ljava/lang/String;ZLjava/lang/String;ZLjava/lang/Object;)V
            return-void
            :source${index + 1}
        """
    }
    replaceArchiveBridge("captureRequest", 7, "if-eqz p1, :done\n" + requestBodies.joinToString("\n") + "\n:done\nreturn-void")

    val converter = Fingerprint(filters = listOf(string("FeedConverter.convert("))).method
    if (converter.parameterTypes.size != 1 || converter.returnType != sectionInterface) throw PatchException("Unexpected feed converter")
    val elementType = converter.parameterTypes.single().toString()
    val idMethod = classDefBy(elementType).methods.single { it.name == "getLinkId" }
    val promoted = classDefBy(elementType).methods.single { it.returnType == "Z" && it.parameterTypes.isEmpty() }
    replaceArchiveBridge("elementId", 2, """
        instance-of v0, p0, $elementType
        if-eqz v0, :none
        check-cast p0, $elementType
        invoke-virtual { p0 }, $promoted
        move-result v0
        if-nez v0, :none
        invoke-virtual { p0 }, $idMethod
        move-result-object p0
        return-object p0
        :none
        const/4 p0, 0x0
        return-object p0
    """)
    val holder = model("CommentHolderElement(identifier=")
    val comment = model("ProfileCommentFeedElement(identifier=")
    val bodyField = constructorFields(classDefBy(holder.definingClass).methods.single { it.name == "<init>" })[1]
    val commentFields = constructorFields(classDefBy(comment.definingClass).methods.single { it.name == "<init>" })
    val commentId = commentFields[0]
    val commentTime = commentFields[1]
    val commentText = commentFields[3]
    replaceArchiveBridge("commentItem", 4, """
        instance-of v0, p0, ${holder.definingClass}
        if-eqz v0, :none
        check-cast p0, ${holder.definingClass}
        iget-object p0, p0, $bodyField
        iget-object v0, p0, $commentId
        iget-object v1, p0, $commentTime
        iget-object v2, p0, $commentText
        invoke-static { v0, v1, v2 }, $ARCHIVE_CLASS->comment(Ljava/lang/String;Ljava/time/Instant;Ljava/lang/String;)$NATIVE_ITEM
        move-result-object p0
        return-object p0
        :none
        const/4 p0, 0x0
        return-object p0
    """)
    val count = converter.implementation!!.registerCount
    val returns = converter.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
    val (converted, shifted) = converter.addFreeRegisters(3)
    converted.addInstructions(shifted, """
        move-object/16 v${count + 1}, v${count - 1}
        move-object/16 v${count + 2}, v${count - 2}
    """)
    returns.reversed().forEach { (at, instruction) ->
        val result = (instruction as OneRegisterInstruction).registerA
        converted.addInstructionsAtControlFlowLabel(at + shifted + 2, """
            move-object/16 v$count, v$result
            invoke-static/range { v$count .. v${count + 1} }, $ARCHIVE_CLASS->converted(Ljava/lang/Object;Ljava/lang/Object;)V
            invoke-static/range { v$count .. v${count + 2} }, $RECOVERY->wrap(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$count
            check-cast v$count, ${converter.returnType}
            move-object/16 v$result, v$count
        """)
    }

    val postRecord = model("PostContentFragment(__typename=")
    val recordConstructor = classDefBy(postRecord.definingClass).methods.single { it.name == "<init>" }
    val recordFields = recordConstructor.implementation!!.instructions.filter {
        it.opcode in setOf(Opcode.IPUT, Opcode.IPUT_BOOLEAN, Opcode.IPUT_OBJECT)
    }.map { (it as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference }
    if (recordFields.map { it.type } != recordConstructor.parameterTypes.map { it.toString() })
        throw PatchException("Unexpected native post constructor assignments")
    if (recordFields.size != 52 || recordFields[48].type != "Lcom/reddit/type/RemovedByCategory;")
        throw PatchException("Unexpected native post content shape")
    val body = recordFields[6]
    val markdown = constructorFields(classDefBy(body.type).methods.single { it.name == "<init>" })[0]
    replaceArchiveBridge("capturePost", 6, """
        check-cast p0, ${postRecord.definingClass}
        iget-object v0, p0, ${recordFields[1]}
        iget-object v1, p0, ${recordFields[2]}
        iget-object v2, p0, $body
        if-eqz v2, :noText
        iget-object v2, v2, $markdown
        goto :status
        :noText
        const/4 v2, 0x0
        :status
        iget-object v3, p0, ${recordFields[48]}
        iget-boolean v4, p0, ${recordFields[19]}
        invoke-static { v0, v1, v2, v3, v4 }, $ARCHIVE_CLASS->postRecord(Ljava/lang/String;Ljava/time/Instant;Ljava/lang/String;Ljava/lang/Enum;Z)V
        return-void
    """)
    val rawMutable = mutableMethodBy(recordConstructor)
    rawMutable.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.reversed().forEach { (at, _) ->
        rawMutable.addInstructions(at, "invoke-static/range { p0 .. p0 }, $ARCHIVE_CLASS->capturePost(Ljava/lang/Object;)V")
    }
    val gqlContext = model("GqlContext(linkId=")
    val gqlId = constructorFields(classDefBy(gqlContext.definingClass).methods.single { it.name == "<init>" && it.parameterTypes.last() == "Z" })[0]
    replaceArchiveBridge("captureTime", 2, """
        check-cast p0, ${gqlContext.definingClass}
        iget-object p0, p0, $gqlId
        check-cast p1, $INSTANT
        invoke-static { p0, p1 }, $ARCHIVE_CLASS->postTime(Ljava/lang/String;Ljava/time/Instant;)V
        return-void
    """)
    // Capture the unformatted Instant at the metadata mapper, before Reddit turns it into "2h".
    listOf("MetadataHeaderElement(linkId=", "ClassicPostElement(linkId=").forEachIndexed { index, marker ->
        val metadata = if (index == 0) model(marker) else model("ClassicPostElement(linkId=")
        val mapperOwner = classDefBy { owner -> owner.methods.any { it.returnType == metadata.definingClass &&
                it.parameterTypes.size == 2 && it.parameterTypes[0] == gqlContext.definingClass &&
                it.references().any { ref -> ref.definingClass == INSTANT && ref.name == "toEpochMilli" } } }
        val mapper = mapperOwner.methods.single { it.returnType == metadata.definingClass && it.parameterTypes.size == 2 }
        val timeCall = mapper.implementation!!.instructions.withIndex().single { (_, instruction) ->
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
                it.definingClass == INSTANT && it.name == "toEpochMilli"
            } == true
        }
        val timeRegister = (timeCall.value as FiveRegisterInstruction).registerC
        val old = mapper.implementation!!.registerCount
        val (patched, shift) = mutableMethodBy(mapper).addFreeRegisters(2)
        patched.addInstructions(shift, "move-object/16 v$old, v${old - 2}")
        patched.addInstructionsAtControlFlowLabel(timeCall.index + shift + 1, """
            move-object/16 v${old + 1}, v$timeRegister
            invoke-static/range { v$old .. v${old + 1} }, $ARCHIVE_CLASS->captureTime(Ljava/lang/Object;Ljava/lang/Object;)V
        """)
    }
    val metadataModels = listOf(model("MetadataHeaderElement(linkId="), model("ClassicPostElement(linkId="))
    val statusBodies = metadataModels.mapIndexed { index, metadata ->
        val id = classDefBy(metadata.definingClass).fields.single { field -> field.toString() ==
            classDefBy(elementType).methods.single { it.name == "getLinkId" }.let {
                classDefBy(metadata.definingClass).methods.single { it.name == "getLinkId" }.implementation!!.instructions
                    .filter { instruction -> instruction.opcode == Opcode.IGET_OBJECT }
                    .map { instruction -> (instruction as ReferenceInstruction).reference.toString() }.single()
            } }
        val fullConstructor = classDefBy(metadata.definingClass).methods.single { it.name == "<init>" && it.parameterTypes.last() == "Z" }
        val indicatorFields = fullConstructor.implementation!!.instructions.filter { it.opcode == Opcode.IPUT_OBJECT }
            .map { (it as ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.FieldReference }
            .filter { it.type == "Ljava/util/List;" }.distinctBy { it.toString() }
        if (indicatorFields.size != 2) throw PatchException("Unexpected metadata indicator lists")
        val indicators = indicatorFields[1]
        classDefBy(metadata.definingClass).methods.filter { it.name == "<init>" }.forEach { constructor ->
            val mutable = mutableMethodBy(constructor)
            mutable.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.reversed().forEach { (at, _) ->
                mutable.addInstructions(at, "invoke-static/range { p0 .. p0 }, $ARCHIVE_CLASS->captureStatus(Ljava/lang/Object;)V")
            }
        }
        """
            instance-of v0, p0, ${metadata.definingClass}
            if-eqz v0, :metadata${index + 1}
            check-cast p0, ${metadata.definingClass}
            iget-object v0, p0, $id
            iget-object v1, p0, $indicators
            invoke-static { v0, v1 }, $ARCHIVE_CLASS->postStatus(Ljava/lang/String;Ljava/util/List;)V
            return-void
            :metadata${index + 1}
        """
    }
    replaceArchiveBridge("captureStatus", 3, statusBodies.joinToString("\n") + "\nreturn-void")

    installNativeRecovery(sectionInterface, sectionContent, sectionKey, converter)

    mutableClassDefBy(NATIVE_SECTION).interfaces.add(sectionInterface)
    addNativeMethod(NATIVE_SECTION, sectionKey, "invoke-virtual { p0 }, $NATIVE_SECTION->key()Ljava/lang/String;\nmove-result-object v0\nreturn-object v0")
    addNativeMethod(NATIVE_SECTION, sectionContent, """
        iget-object v0, p0, $NATIVE_SECTION->original:Ljava/lang/Object;
        check-cast v0, $sectionInterface
        invoke-interface { v0, p1, p2, p3 }, $sectionContent
        return-void
    """)
    val keyFactory = classDefBy { owner -> owner.interfaces.contains("Lkotlin/jvm/functions/Function2;") &&
            owner.methods.any { method -> method.name == "invoke" && method.references().any { it.toString() == sectionKey.toString() } &&
                    method.implementation?.instructions?.any { instruction ->
                        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == "_"
                    } == true } }
    val keyMethod = keyFactory.methods.single { it.name == "invoke" }
    val discriminator = keyFactory.fields.single { it.type == "I" }
    val renderContext = keyFactory.fields.single { it.type != "I" }
    val includeIndex = model("FeedRenderingContext(animatePlacement=false, includeIndexInItemKeys=").findFieldFromToString("includeIndexInItemKeys=")
    val keyCount = keyMethod.implementation!!.registerCount
    val (keyPatched, keyShift) = mutableMethodBy(keyMethod).addFreeRegisters(2)
    keyPatched.addInstructionsWithLabels(keyShift, """
        iget v$keyCount, v${keyCount - 3}, $discriminator
        if-nez v$keyCount, :ordinary
        iget-object v$keyCount, v${keyCount - 3}, $renderContext
        iget-boolean v$keyCount, v$keyCount, $includeIndex
        invoke-static { v${keyCount - 1}, v$keyCount }, $ARCHIVE_CLASS->stableKey(Ljava/lang/Object;Z)Ljava/lang/String;
        move-result-object v$keyCount
        if-eqz v$keyCount, :ordinary
        return-object v$keyCount
        :ordinary
        nop
    """)
}
