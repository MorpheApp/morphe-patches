package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Read the same composition-local palette as Reddit, including its live snapshot state. */
context(context: BytecodePatchContext)
internal fun installNativeArchiveTheme(composerInterface: String) = with(context) {
    val colors = Fingerprint(name = "toString", returnType = "Ljava/lang/String;",
        filters = listOf(string("Colors(ads="), string(", neutral="))).classDef
    val neutral = Fingerprint(name = "toString", returnType = "Ljava/lang/String;",
        filters = listOf(string("Neutral(background="), string(", borderDivider="),
            string(", contentStrong="), string(", contentWeak="))).classDef

    val host = Fingerprint(
        definingClass = "Lcom/reddit/profile/ui/screens/settings/ProfileVisibilityScreen;",
        parameters = listOf(composerInterface, "I"), returnType = "V",
        filters = listOf(
            literal(-462287637L),
            fieldAccess(opcode = Opcode.SGET_OBJECT),
            methodCall(returnType = "Ljava/lang/Object;", parameters = listOf("Landroidx/compose/runtime/i;"),
                location = MatchAfterWithin(2)),
            checkCast(colors.type, location = MatchAfterWithin(2)),
            fieldAccess(type = neutral.type, opcode = Opcode.IGET_OBJECT, location = MatchAfterWithin(2))
        )
    )
    val local = host.instructionMatches[1].getFieldAccessed()
    val consume = host.instructionMatches[2].getMethodCalled()
    installNativeProfileScope(composerInterface, consume)
    val neutralField = host.instructionMatches[4].getFieldAccessed()

    // R8 formats these values before appending the labels. Match the ordered getter
    // calls and direct state reads in the named toString instead of embedding names.
    val getters = Fingerprint(definingClass = neutral.type, name = "toString",
        filters = List(18) { methodCall(definingClass = neutral.type, parameters = emptyList(), returnType = "J") }
            + string("Neutral(background="))
        .instructionMatches.take(18).map { it.getMethodCalled() }
    val directStates = Fingerprint(definingClass = neutral.type, name = "toString",
        filters = List(7) { fieldAccess(definingClass = neutral.type, opcode = Opcode.IGET_OBJECT) }
            + string("Neutral(background="))
        .instructionMatches.take(7).map { it.getFieldAccessed() }
    val getter = getters.first()
    val reads = getter.implementation!!.instructions.filterIsInstance<ReferenceInstruction>().map { it.reference }
    val readState = reads.filterIsInstance<MethodReference>().single { it.name == "getValue" }
    val packed = reads.filterIsInstance<FieldReference>().single { it.type == "J" }
    val toArgb = Fingerprint(isStatic = true, returnType = "I", parameters = listOf("J"),
        filters = listOf(methodCall(definingClass = packed.definingClass, returnType = "J", parameters = listOf("J", "L")),
            literal(32L))).method
    if (neutral.fields.count() != 25 || getters.distinct().size != 18 || directStates.distinct().size != 7 ||
        neutralField.definingClass != colors.type ||
        !AccessFlags.PUBLIC.isSet(local.accessFlags)) {
        throw PatchException("Reddit's profile palette structure changed")
    }

    val secondaryField = colors.methods.single { it.name == "toString" }.findFieldFromToString("secondary=")
    val secondaryGetters = Fingerprint(definingClass = secondaryField.type, name = "toString",
        filters = List(6) { index -> methodCall(definingClass = secondaryField.type, parameters = emptyList(), returnType = "J",
            location = if (index == 0) app.morphe.patcher.InstructionLocation.MatchAfterAnywhere() else MatchAfterWithin(if (index == 5) 12 else 4)) }
            + string("Secondary(background="))
        .instructionMatches.take(6).map { it.getMethodCalled() }
    if (secondaryGetters.distinct().size != 6) throw PatchException("Reddit's secondary palette structure changed")
    val voteFields = listOf("upvote=", "downvote=").map { label ->
        colors.methods.single { it.name == "toString" }.findFieldFromToString(label)
    }
    val voteGetters = voteFields.map { field ->
        Fingerprint(definingClass = field.type, name = "toString", filters = listOf(
            methodCall(definingClass = field.type, parameters = emptyList(), returnType = "J"),
            string("Upvote(background="))).instructionMatches.first().getMethodCalled()
    }

    // background, contentStrong, contentWeak, backgroundWeak, borderDivider,
    // borderWeak, secondary.plain and selected vote colors (native Bordered appearance).
    val selected = listOf(0 to getters[0], 1 to getters[16], 2 to getters[17], 3 to getters[9], 5 to getters[14], 9 to getters[15])
    replaceArchiveBridge("themeColors", 8, buildString {
        appendLine("check-cast p0, ${consume.definingClass}")
        appendLine("sget-object v0, $local")
        appendLine("invoke-virtual { p0, v0 }, $consume")
        appendLine("move-result-object v0\ncheck-cast v0, ${colors.type}")
        appendLine("move-object v6, v0")
        appendLine("iget-object v0, v0, $neutralField")
        appendLine("const/16 v1, 0xa\nnew-array v1, v1, [I")
        selected.forEach { (index, method) ->
            appendLine("invoke-virtual { v0 }, $method\nmove-result-wide v2")
            appendLine("invoke-static { v2, v3 }, $toArgb\nmove-result v4")
            appendLine("const/16 v5, $index\naput v4, v1, v5")
        }
        appendLine("iget-object v2, v0, ${directStates.last()}")
        appendLine("check-cast v2, ${readState.definingClass}")
        appendLine("invoke-virtual { v2 }, $readState\nmove-result-object v2")
        appendLine("check-cast v2, ${packed.definingClass}\niget-wide v2, v2, $packed")
        appendLine("invoke-static { v2, v3 }, $toArgb\nmove-result v4")
        appendLine("const/4 v5, 0x4\naput v4, v1, v5")
        appendLine("iget-object v0, v6, $secondaryField")
        appendLine("invoke-virtual { v0 }, ${secondaryGetters[4]}\nmove-result-wide v2")
        appendLine("invoke-static { v2, v3 }, $toArgb\nmove-result v4")
        appendLine("const/4 v5, 0x6\naput v4, v1, v5")
        voteFields.forEachIndexed { index, field ->
            appendLine("iget-object v0, v6, $field")
            appendLine("invoke-virtual { v0 }, ${voteGetters[index]}\nmove-result-wide v2")
            appendLine("invoke-static { v2, v3 }, $toArgb\nmove-result v4")
            appendLine("const/16 v5, ${7 + index}\naput v4, v1, v5")
        }
        appendLine("return-object v1")
    })
}
