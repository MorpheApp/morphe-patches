package app.morphe.patches.youtube.layout.captions

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class StableCaptionWindowTest {
    private val c = "Lrenamed/Cue;"
    private val fields =
        CaptionCueFields(
            ImmutableFieldReference(c, "id", "I"),
            ImmutableFieldReference(c, "time", "J"),
            ImmutableFieldReference(c, "shown", "Ljava/lang/CharSequence;"),
            ImmutableFieldReference(c, "complete", "Ljava/lang/CharSequence;"),
            ImmutableFieldReference(c, "config", "Lrenamed/Settings;"),
        )

    private fun fixture(
        owner: String = "Lrenamed/Window;",
        duplicate: Boolean = false,
        clobber: Boolean = false,
    ): CaptionWindowLines {
        val dir = createTempDirectory("caption-window-lists-").toFile()
        try {
            val file = dir.resolve("Window.smali")
            file.writeText(
                """
    .class public $owner
    .super Landroid/view/ViewGroup;
    .method public set($c)V
     .locals 4
     iput-object p1, p0, $owner->cue:$c
     iget-object v0, p0, $owner->shownRows:Ljava/util/List;
     iget-object v1, p0, $owner->completeRows:Ljava/util/List;
     iget-object v2, p1, $c->shown:Ljava/lang/CharSequence;
     invoke-static {v0,v2}, Lrenamed/Util;->split(Ljava/util/List;Ljava/lang/CharSequence;)V
     iget-object v2, p1, $c->${if(duplicate)"shown" else "complete"}:Ljava/lang/CharSequence;
     move-object v3, v1
     ${if(clobber)"const/4 v3, 0x0" else ""}
     invoke-static {v3,v2}, Lrenamed/Util;->split(Ljava/util/List;Ljava/lang/CharSequence;)V
     return-void
    .end method
   """
                    .trimIndent(),
            )
            val dex = dir.resolve("fixture.dex")
            assertTrue(
                Smali.assemble(
                    SmaliOptions().apply {
                        outputDexFile = dex.path
                        jobs = 1
                    },
                    file.path,
                ),
            )
            return findCaptionWindowLines(
                DexFileFactory.loadDexFile(dex, Opcodes.getDefault())
                    .classes
                    .single()
                    .methods
                    .single(),
                fields,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `field names and temporary register aliases are discovered structurally`() {
        for (owner in listOf("Lrenamed/Window;", "Lother/version/Window;")) {
            val x = fixture(owner)
            assertEquals("shownRows", x.visible.name)
            assertEquals("completeRows", x.full.name)
            assertEquals(owner, x.cue.definingClass)
        }
    }

    @Test
    fun `duplicate text mappings or clobbered list registers fail closed`() {
        assertFailsWith<IllegalStateException> { fixture(duplicate = true) }
        assertFailsWith<IllegalStateException> { fixture(clobber = true) }
    }
}
