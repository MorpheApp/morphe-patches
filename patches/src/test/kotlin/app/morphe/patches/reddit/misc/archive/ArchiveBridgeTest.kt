package app.morphe.patches.reddit.misc.archive

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchiveBridgeTest {
    private val stub = ImmutableMethod(
        "Ltest/ArchiveBridge;", "prepare",
        List(2) { ImmutableMethodParameter("Ljava/lang/Object;", emptySet(), null) },
        "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        emptySet(), emptySet(), null
    )

    @Test
    fun `unchanged branch survives another patch inserting a flair call`() {
        val method = archiveBridge(stub, 3, """
            if-eqz p0, :unchanged
            new-instance v0, Ltest/Post;
            invoke-direct { v0 }, Ltest/Post;-><init>()V
            return-object v0
            :unchanged
            return-object p0
        """)
        // The reported crash: a second patch adds six code units before one return.
        // A detached label then lands inside the new invoke rather than on p0's return.
        method.addInstructions(3, """
            invoke-static { v0 }, Ltest/Flairs;->apply(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v0
            check-cast v0, Ltest/Post;
        """)
        assertBranchTarget(method, 0, method.implementation!!.instructions.lastIndex)
    }

    @Test
    fun `backward branch survives a prefix inserted by another patch`() {
        val method = archiveBridge(stub, 3, """
            :retry
            invoke-static {}, Ltest/Request;->pending()Z
            move-result v0
            if-nez v0, :retry
            return-object p0
        """)
        method.addInstructions(0, "const/16 v0, 0x1234")
        assertEquals(Opcode.IF_NEZ, method.implementation!!.instructions[3].opcode)
        assertBranchTarget(method, 3, 1)
    }

    private fun assertBranchTarget(method: Method, branchIndex: Int, targetIndex: Int) {
        val instructions = method.implementation!!.instructions.toList()
        val addresses = instructions.runningFold(0) { pc, instruction -> pc + instruction.codeUnits }
        val branch = instructions[branchIndex] as OffsetInstruction
        assertEquals(addresses[targetIndex], addresses[branchIndex] + branch.codeOffset)
    }
}
