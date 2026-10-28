package app.morphe.patches.youtube.layout.captions

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
