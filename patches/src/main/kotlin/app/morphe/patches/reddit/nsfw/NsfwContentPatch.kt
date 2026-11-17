package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION = "Lapp/morphe/extension/reddit/patches/NsfwNavigationPatch;"

@Suppress("unused")
val nsfwContentPatch = patch(
    name = "Fix NSFW content flashing",
    description = "Prevents flagged posts and profiles from appearing before the warning when Reddit NSFW viewing is off.",
    default = false
) {
    compatibleWith(nsfwCompatibility)
    dependsOn(nsfwNavigationPatch)
    execute {
        val viewing = NativeNsfwViewingFingerprint.method
        val preferenceType = Fingerprint(
            definingClass = "Lcom/reddit/postdetail/refactor/events/handlers/PostLoadedNsfwIncognitoVerifyEventHandler;",
            name = "<init>"
        ).originalClassDef.fields.single { it.name == "preferenceRepository" }.type
        val replaceScreen = NsfwReplaceScreenFingerprint.method
        // Gate the native post view before its first composition, rather than waiting
        // for the asynchronous PostLoadedNsfwIncognitoVerifyEvent handler.
        val postState = Fingerprint(filters = listOf(string("PostDetailPostState(link=")))
        val hiddenField = Fingerprint(classFingerprint = postState, filters = listOf(
            string(", joinButton=null, isContentHidden="),
            fieldAccess(type = "Z", opcode = Opcode.IGET_BOOLEAN, location = MatchAfterWithin(5))
        )).instructionMatches.last().getFieldAccessed()
        val blockedField = Fingerprint(classFingerprint = postState, filters = listOf(
            string(", isContentBlocked="),
            fieldAccess(type = "Z", opcode = Opcode.IGET_BOOLEAN, location = MatchAfterWithin(6))
        )).instructionMatches.last().getFieldAccessed()
        val linkField = postState.originalClassDef.fields.single { it.type == "Lcom/reddit/domain/model/Link;" }
        val producer = Fingerprint(filters = listOf(
            string("PostDetailStateProducer::updatePostViewState"),
            methodCall(returnType = "Lcom/reddit/presentation/listing/model/LinkPresentationModel;")
        ))
        val mapper = producer.instructionMatches.last().getMethodCalled().definingClass
        val mapperField = producer.originalClassDef.fields.single { it.type == mapper }
        val mapperPreferences = Fingerprint(definingClass = mapper, name = "<init>")
            .originalClassDef.fields.single { it.type == preferenceType }
        val postViewModel = "Lcom/reddit/postdetail/refactor/RedditPostDetailViewModel;"
        val postClass = Fingerprint(definingClass = postViewModel, name = "<init>")
        val producerField = postClass.originalClassDef.fields.single { it.type == producer.originalClassDef.type }
        val shouldHidePost = ImmutableMethod(
            postViewModel, "patch_shouldHideNsfw", listOf(ImmutableMethodParameter(postState.originalClassDef.type, emptySet(), null)),
            "Z", AccessFlags.PRIVATE.value, emptySet(), null, MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                iget-object v0, p1, $linkField
                if-eqz v0, :visible
                invoke-virtual { v0 }, Lcom/reddit/domain/model/Link;->getOver18()Z
                move-result v0
                if-eqz v0, :visible
                iget-object v0, p0, $producerField
                iget-object v0, v0, $mapperField
                iget-object v0, v0, $mapperPreferences
                check-cast v0, ${viewing.definingClass}
                invoke-virtual { v0 }, $viewing
                move-result v0
                xor-int/lit8 v0, v0, 0x1
                return v0
                :visible
                const/4 v0, 0x0
                return v0
            """)
        }
        postClass.classDef.methods.add(shouldHidePost)
        Fingerprint(definingClass = postViewModel, filters = listOf(
            methodCall(definingClass = "Lcom/reddit/postdetail/refactor/RedditPostDetailViewModel\$viewState\$postUnitViewState\$1\$1;", name = "<init>"),
            fieldAccess(smali = hiddenField.toString()),
            fieldAccess(smali = blockedField.toString(), location = MatchAfterImmediately())
        )).apply {
            method.apply {
                val owner = getInstruction<FiveRegisterInstruction>(instructionMatches[0].index).registerD
                val hidden = getInstruction<TwoRegisterInstruction>(instructionMatches[1].index).registerA
                val blocked = getInstruction<TwoRegisterInstruction>(instructionMatches[2].index)
                val index = instructionMatches.last().index + 1
                val register = findFreeRegister(index)
                addInstructionsWithLabels(index, """
                    invoke-direct { v$owner, v${blocked.registerB} }, $shouldHidePost
                    move-result v$register
                    if-eqz v$register, :nativePostVisibility
                    const/4 v$hidden, 0x1
                    const/4 v${blocked.registerA}, 0x1
                    :nativePostVisibility
                    nop
                """)
            }
        }

        // Stop flagged profiles before publishing their model to the header/feed.
        // The later native warning is too late when content is already cached.
        val profileViewModel = "Lcom/reddit/profile/viewmodel/ProfileDetailsViewModel;"
        val profileNsfw = Fingerprint(filters = listOf(
            string(", isProfileNsfw="),
            fieldAccess(type = "Z", opcode = Opcode.IGET_BOOLEAN, location = MatchAfterWithin(6))
        )).instructionMatches.last().getFieldAccessed()
        val contextCallback = NsfwHomeNavigationFingerprint.instructionMatches.first().getFieldAccessed()
        val profileContext = Fingerprint(definingClass = profileViewModel, name = "<init>")
            .originalClassDef.fields.single { it.type == contextCallback.definingClass }
        val profilePreferences = Fingerprint(definingClass = profileViewModel, name = "<init>")
            .originalClassDef.fields.single { it.type == preferenceType }
        Fingerprint(filters = listOf(
            newInstance("Lcom/reddit/profile/viewmodel/ProfileDetailsViewModel\$loadProfileDetails\$1\$1\$emit\$1;"),
            fieldAccess(definingClass = profileViewModel, opcode = Opcode.IGET_OBJECT),
            methodCall(parameters = listOf("Ljava/lang/Object;"), returnType = "V", location = MatchAfterImmediately()),
            fieldAccess(definingClass = "Lkotlin/Unit;", opcode = Opcode.SGET_OBJECT)
        )).apply {
            method.apply {
                val index = instructionMatches[2].index
                val profile = getInstruction<FiveRegisterInstruction>(index).registerD
                val owner = getInstruction<TwoRegisterInstruction>(instructionMatches[1].index).registerB
                val unit = instructionMatches.last().getFieldAccessed()
                val register = findFreeRegister(index)
                addInstructionsWithLabels(index, """
                    iget-boolean v$register, v$profile, $profileNsfw
                    if-eqz v$register, :publishProfile
                    iget-object v$register, v$owner, $profilePreferences
                    check-cast v$register, ${viewing.definingClass}
                    invoke-virtual { v$register }, $viewing
                    move-result v$register
                    if-nez v$register, :publishProfile
                    iget-object v$register, v$owner, $profileContext
                    iget-object v$register, v$register, $contextCallback
                    invoke-interface { v$register }, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
                    move-result-object v$register
                    check-cast v$register, Landroid/app/Activity;
                    invoke-static { v$register }, $EXTENSION->showProfileWarning(Landroid/app/Activity;)V
                    sget-object v$register, $unit
                    return-object v$register
                    :publishProfile
                    nop
                """)
            }
        }
        val openWarning = Fingerprint(definingClass = EXTENSION, name = "openProfileWarning")
        val originalWarning = openWarning.method
        val nativeWarning = ImmutableMethod(
            EXTENSION, originalWarning.name, originalWarning.parameters, originalWarning.returnType,
            originalWarning.accessFlags, originalWarning.annotations, originalWarning.hiddenApiRestrictions,
            MutableMethodImplementation(5)
        ).toMutable().apply {
            addInstructions(0, """
                new-instance v0, Landroid/os/Bundle;
                invoke-direct { v0 }, Landroid/os/Bundle;-><init>()V
                const-string v1, "tag_age_target_screen_page_type"
                const-string v2, "profile"
                invoke-virtual { v0, v1, v2 }, Landroid/os/BaseBundle;->putString(Ljava/lang/String;Ljava/lang/String;)V
                new-instance v1, Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet;
                invoke-direct { v1, v0 }, Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet;-><init>(Landroid/os/Bundle;)V
                invoke-static { p0, v1 }, $replaceScreen
                return-void
            """)
        }
        openWarning.classDef.methods.remove(originalWarning)
        openWarning.classDef.methods.add(nativeWarning)
    }
}
