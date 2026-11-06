package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.literal
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFieldFromToString
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable

internal const val ARCHIVE_CLASS = "Lapp/morphe/extension/reddit/patches/archive/ProfileArchivePatch;"
internal const val FEED_TYPE = "Lcom/reddit/profile/model/ProfileFeedType;"
private const val FUNCTION = "Lkotlin/jvm/functions/Function1;"

internal val profileModel = Fingerprint(
    name = "toString", returnType = "Ljava/lang/String;", parameters = emptyList(),
    filters = listOf(string("ProfileEmptyFeedPresentationModel(targetFeed="))
)

private val archiveReaderResources = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { doc ->
            val activity = doc.createElement("activity")
            activity.setAttribute("android:name", "app.morphe.extension.reddit.patches.archive.ArchiveReaderActivity")
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:theme", "@android:style/Theme.Material.Light.NoActionBar")
            doc.getElementsByTagName("application").item(0).appendChild(activity)
        }
    }
}

// These are Compose AndroidView's actual factory/update parameters, discovered in the APK.
// No obfuscated class or method names are embedded in the runtime extension.
internal val androidView = Fingerprint(
    definingClass = "Landroidx/compose/ui/viewinterop/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V", parameters = listOf(FUNCTION, "L", FUNCTION, "L", "I", "I"),
    filters = listOf(literal(-1783766393L))
)

internal fun profileRenderer(modelType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(modelType, "Lkotlin/jvm/functions/Function0;", "L", "L", "I"),
    filters = listOf(
        literal(literal = { resourceId(ResourceType.STRING, "profile_empty_feed_visitor_comment_filtered_description") }),
        literal(literal = { resourceId(ResourceType.STRING, "profile_empty_feed_visitor_post_filtered_description") })
    )
)

context(context: BytecodePatchContext)
fun replaceArchiveBridge(name: String, registers: Int, code: String, type: String = ARCHIVE_CLASS) {
    val owner = context.mutableClassDefBy(type)
    val old = owner.methods.single { it.name == name }
    val replacement = ImmutableMethod(old.definingClass, old.name, old.parameters, old.returnType,
        old.accessFlags, old.annotations, old.hiddenApiRestrictions,
        MutableMethodImplementation(registers)).toMutable()
    replacement.addInstructions(0, code)
    owner.methods.remove(old)
    owner.methods.add(replacement)
}

@Suppress("unused")
val profileArchivePatch = bytecodePatch(
    name = "Archived public profile history",
    description = "Merges archived public posts and comments into profiles using Arctic Shift, including hidden history and recoverable deleted content. Sends the viewed username to that service. Coverage may be incomplete or outdated.",
    default = false
) {
    compatibleWith(Compatibility(
        packageName = "com.reddit.frontpage", name = "Reddit",
        signatures = COMPATIBILITY_REDDIT.signatures,
        apkFileType = COMPATIBILITY_REDDIT.apkFileType,
        targets = COMPATIBILITY_REDDIT.targets.filter { it.version == "2026.40.0" }
    ))
    dependsOn(settingsPatch, archiveReaderResources)

    execute {
        // The runtime resolves these host resources by name. Fail while patching if the
        // supported APK cannot supply them, rather than crash when opening a profile.
        mapOf(
            ResourceType.FONT to listOf("reddit_sans_regular", "reddit_sans_semi_bold", "reddit_sans_bold"),
            ResourceType.COLOR to listOf("neutral_background", "rdt_content_strong_neutral",
                "rdt_content_weak_neutral", "rdt_background_weak_neutral"),
            ResourceType.DRAWABLE to listOf("icon_info", "icon_community_fill", "icon_overflow_horizontal",
                "icon_upvote", "icon_downvote", "icon_comment", "icon_share", "icon_play")
        ).forEach { (type, names) -> names.forEach { resourceId(type, it) } }
        val model = profileModel.method
        val modelType = model.definingClass
        val renderer = profileRenderer(modelType).method
        val bridge = androidView.method
        val unit = classDefBy("Lkotlin/Unit;").fields.single {
            it.type == "Lkotlin/Unit;" && AccessFlags.STATIC.isSet(it.accessFlags)
        }
        replaceArchiveBridge("unit", 1, """
            sget-object v0, $unit
            return-object v0
        """)
        if (renderer.parameterTypes[3] != bridge.parameterTypes[3]) {
            throw PatchException("Profile and AndroidView use different Compose runtimes")
        }

        val constructor = profileModel.classDef.methods.single {
            it.name == "<init>" && it.parameterTypes == listOf(FEED_TYPE, "Ljava/lang/String;", "Z", "Z")
        }
        val parameterStart = constructor.implementation!!.registerCount - 5
        fun flag(parameter: Int): FieldReference = constructor.implementation!!.instructions
            .filter { it.opcode == Opcode.IPUT_BOOLEAN }
            .single { (it as TwoRegisterInstruction).registerA == parameterStart + parameter }
            .let { (it as ReferenceInstruction).reference as FieldReference }

        val fields = mapOf(
            "profileName" to model.findFieldFromToString("profileName="),
            "feedType" to model.findFieldFromToString("targetFeed="),
            "isFiltered" to flag(3),
            "isFlairFiltered" to flag(4)
        )
        fields.forEach { (getter, field) ->
            val boolean = field.type == "Z"
            replaceArchiveBridge(getter, 2, """
                check-cast p0, $modelType
                ${if (boolean) "iget-boolean" else "iget-object"} v0, p0, $field
                ${if (boolean) "return" else "return-object"} v0
            """)
        }

        replaceArchiveBridge("androidView", 9, """
                move-object v0, p0
                const/4 v1, 0x0
                move-object v2, p1
                move-object v3, p2
                check-cast v3, ${bridge.parameterTypes[3]}
                const/4 v4, 0x0
                const/4 v5, 0x2
                invoke-static/range { v0 .. v5 }, $bridge
                return-void
        """)

        installNativeArchiveFeed(modelType, renderer, bridge.parameterTypes[3].toString())
        setExtensionIsPatchIncluded(ARCHIVE_CLASS)
    }
}
