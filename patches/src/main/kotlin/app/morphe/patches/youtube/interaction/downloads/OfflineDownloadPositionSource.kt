/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.string
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val positionSourceType = EXTENSION_OFFLINE_DOWNLOAD_POSITION.removeSuffix(";") + "\$Source;"
private const val SQLITE_DATABASE = "Landroid/database/sqlite/SQLiteDatabase;"

internal fun hookOfflineDownloadPositionSource() {
    val (rowLookupMatch, _, positionSetterMatch) = offlinePositionWriterFingerprint().instructionMatches
    val rowLookup = rowLookupMatch.methodReference
    val positionSetter = positionSetterMatch.methodReference

    val (indexGetterMatch, _, indexLookupMatch) = offlineRowLookupFingerprint(rowLookup).instructionMatches
    val indexGetter = indexGetterMatch.methodReference
    val indexLookup = indexLookupMatch.methodReference
    val indexField = offlineIndexGetterFingerprint(indexGetter).instructionMatches.single().fieldReference

    // The database writer also updates the native row. The row copies that position into its
    // cached snapshot under the index lock, so use the same snapshot as YouTube.
    val rawPosition = offlinePositionSetterFingerprint(positionSetter).instructionMatches.last().fieldReference
    val snapshot = offlineRowSnapshotFingerprint(rawPosition)
    val (positionLoad, constructorCall) = snapshot.instructionMatches.takeLast(2)
    val positionRegister = positionLoad.getInstruction<TwoRegisterInstruction>().registerA
    val positionArgument = constructorCall.instruction.registersUsed.indexOf(positionRegister)
    check(positionArgument > 0) { "Could not find the Downloads position in the row snapshot constructor" }
    val snapshotPosition = offlineSnapshotPositionFingerprint(constructorCall.methodReference, positionArgument)
        .instructionMatches.single().fieldReference

    val cacheClass = mutableClassDefBy(rowLookup.definingClass)
    cacheClass.interfaces.add(positionSourceType)
    cacheClass.addBridge(
        "patch_getPlaybackPositionSeconds",
        listOf("Ljava/lang/String;"),
        "J",
        4,
        """
            iget-object v0, p0, $indexField
            invoke-virtual { v0, p1 }, $indexLookup
            move-result-object v0
            if-eqz v0, :missing
            invoke-virtual { v0 }, ${snapshot.method}
            move-result-object v0
            iget-wide v0, v0, $snapshotPosition
            return-wide v0

            :missing
            const-wide/16 v0, -0x1
            return-wide v0
        """
    )

    val initialized = offlineCacheInitializedFingerprint(cacheClass.type)
    val databaseType = initialized.instructionMatches[1].methodReference.definingClass
    val databaseField = offlineDatabaseOwnerFingerprint(cacheClass.type, databaseType)
        .instructionMatches.single().fieldReference
    val databaseNameField = offlineDatabaseNameFingerprint(databaseType)
        .instructionMatches.single().fieldReference
    val databaseNameGetter = "patch_getOfflineDatabaseName"
    mutableClassDefBy(databaseType).addBridge(
        databaseNameGetter,
        emptyList(),
        "Ljava/lang/String;",
        2,
        """
            iget-object v0, p0, $databaseNameField
            return-object v0
        """
    )

    // Publish the cache only after the native initializer opens its readiness condition.
    initialized.method.addInstructions(
        initialized.instructionMatches.last().index,
        """
            move-object/from16 v0, p0
            iget-object v1, v0, $databaseField
            invoke-virtual { v1 }, $databaseType->$databaseNameGetter()Ljava/lang/String;
            move-result-object v1
            invoke-static { v1, v0 }, $EXTENSION_OFFLINE_DOWNLOAD_POSITION->attach(Ljava/lang/String;$positionSourceType)V
        """
    )
}

private fun offlinePositionWriterFingerprint() = Fingerprint(
    returnType = "V",
    parameters = if (is_20_51_or_greater) listOf("Ljava/lang/String;", "J", "Lj$/time/Instant;")
    else listOf("Ljava/lang/String;", "J"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf("Ljava/lang/String;"), returnType = "L"),
        string("videosV2"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = listOf("J"), returnType = "V"),
        string("Update video last_playback_position_in_seconds affected ")
    )
)

private fun offlineRowLookupFingerprint(lookup: MethodReference) = Fingerprint(
    definingClass = lookup.definingClass,
    name = lookup.name,
    parameters = listOf("Ljava/lang/String;"),
    returnType = lookup.returnType,
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, definingClass = "this", parameters = emptyList(), returnType = "L"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            parameters = listOf("Ljava/lang/String;"),
            returnType = lookup.returnType,
            location = MatchAfterImmediately()
        )
    )
)

private fun offlineIndexGetterFingerprint(getter: MethodReference) = Fingerprint(
    definingClass = getter.definingClass,
    name = getter.name,
    parameters = emptyList(),
    returnType = getter.returnType,
    filters = listOf(fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = "this", type = getter.returnType))
)

private fun offlinePositionSetterFingerprint(setter: MethodReference) = Fingerprint(
    definingClass = setter.definingClass,
    name = setter.name,
    parameters = listOf("J"),
    returnType = "V",
    filters = listOf(
        opcode(Opcode.MONITOR_ENTER),
        fieldAccess(opcode = Opcode.IPUT_WIDE, definingClass = "this", type = "J", location = MatchAfterImmediately())
    )
)

private fun offlineRowSnapshotFingerprint(position: FieldReference) = Fingerprint(
    definingClass = position.definingClass,
    parameters = emptyList(),
    returnType = "L",
    filters = listOf(
        opcode(Opcode.MONITOR_ENTER),
        fieldAccess(reference = position, opcode = Opcode.IGET_WIDE),
        methodCall(opcode = Opcode.INVOKE_DIRECT_RANGE, name = "<init>", returnType = "V")
    )
)

private fun offlineSnapshotPositionFingerprint(constructor: MethodReference, positionArgument: Int) = Fingerprint(
    definingClass = constructor.definingClass,
    name = "<init>",
    parameters = constructor.parameterTypes.map { it.toString() },
    returnType = "V",
    filters = listOf(
        InstructionFilter { method, instruction ->
            instruction.opcode == Opcode.IPUT_WIDE && instruction is TwoRegisterInstruction &&
                instruction.registerA == method.p0Register + positionArgument &&
                instruction.registerB == method.p0Register
        }
    )
)

private fun offlineDatabaseOwnerFingerprint(cacheType: String, databaseType: String) = Fingerprint(
    definingClass = cacheType,
    name = "<init>",
    filters = listOf(fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = databaseType))
)

private fun offlineDatabaseNameFingerprint(databaseType: String) = Fingerprint(
    definingClass = databaseType,
    name = "<init>",
    parameters = listOf("L", "Ljava/lang/String;", "L"),
    filters = listOf(fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = "this", type = "Ljava/lang/String;"))
)

private fun offlineCacheInitializedFingerprint(cacheType: String) = Fingerprint(
    definingClass = cacheType,
    parameters = emptyList(),
    returnType = "V",
    filters = listOf(
        methodCall("Landroid/os/ConditionVariable;->close()V"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, parameters = emptyList(), returnType = SQLITE_DATABASE),
        methodCall("Landroid/os/ConditionVariable;->open()V"),
        opcode(Opcode.MONITOR_EXIT, location = MatchAfterImmediately()),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately())
    )
)
