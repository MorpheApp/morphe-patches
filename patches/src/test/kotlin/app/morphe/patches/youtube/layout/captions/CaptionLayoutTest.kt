package app.morphe.patches.youtube.layout.captions

import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlin.test.*

class CaptionLayoutTest {
    private val parameters =
        listOf(
            "I",
            "J",
            "Ljava/lang/CharSequence;",
            "Ljava/lang/CharSequence;",
            "Lrenamed/Settings;",
        )

    private fun helperOwner() =
        MutableClass(
            ImmutableClassDef(
                "Ltest/Captions;",
                AccessFlags.PUBLIC.value,
                "Ljava/lang/Object;",
                emptyList(),
                null,
                emptyList(),
                emptyList(),
                emptyList(),
            ),
        )

    @Test
    fun `helper preserves native access flags and rejects static methods`() {
        val owner = helperOwner()
        val flags = AccessFlags.PUBLIC.value or AccessFlags.SYNCHRONIZED.value
        owner.addHelperMethod(
            "sample",
            "V",
            listOf("J"),
            accessFlags = flags,
            smali = "return-void",
        )
        assertEquals(flags, owner.methods.single().accessFlags)
        assertEquals(4, owner.methods.single().implementation!!.registerCount)
        assertFailsWith<IllegalArgumentException> {
            owner.addHelperMethod(
                "staticSample",
                "V",
                accessFlags = AccessFlags.STATIC.value,
                smali = "return-void",
            )
        }
    }

    @Test
    fun `helper allocates wide parameter pairs without widening array references`() {
        val owner = helperOwner()
        owner.addHelperMethod(
            "wide",
            "V",
            listOf("J", "D", "[J", "I"),
            freeRegisters = 2,
            smali = "return-void",
        )
        val method = owner.methods.single()
        assertEquals(9, method.implementation!!.registerCount)
        assertEquals(AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, method.accessFlags)
    }

    @Test
    fun `cue discovery depends on constructor types rather than obfuscated names`() {
        for (owner in listOf("Lfirst/Cue;", "Lother/version/Cue;")) {
            assertTrue(
                isCaptionCueConstructor(ImmutableMethodReference(owner, "<init>", parameters, "V")),
            )
        }
    }

    @Test
    fun `incompatible cue constructor signatures are rejected`() {
        for (args in
            listOf(
                parameters.drop(1),
                listOf("J", "I") + parameters.drop(2),
                parameters.dropLast(1) + "I",
            )) {
            assertFalse(
                isCaptionCueConstructor(
                    ImmutableMethodReference("Lnative/Cue;", "<init>", args, "V"),
                ),
            )
        }
        assertFalse(
            isCaptionCueConstructor(
                ImmutableMethodReference("Lnative/Cue;", "factory", parameters, "V"),
            ),
        )
        assertFalse(
            isCaptionCueConstructor(
                ImmutableMethodReference("Lnative/Cue;", "<init>", parameters, "I"),
            ),
        )
    }
}
