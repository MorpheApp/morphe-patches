package app.morphe.patches.youtube.layout.captions

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class StableCaptionFingerprintTest {
    private fun fixture(
        type: String = "Lchanged/cue;",
        locals: Int = 0,
        alias: Boolean = false,
        duplicate: Boolean = false,
        missing: Boolean = false,
        overwrite: Boolean = false,
        branch: Boolean = false,
    ): Method {
        val dir = createTempDirectory("caption-layout-").toFile()
        try {
            val file = dir.resolve("Cue.smali")
            val high = locals >= 9
            val receiver = if (high) "v0" else "p0"
            val id = if (high) "v1" else "p1"
            val time = if (high) "v2" else "p2"
            val visible = if (branch) "v0" else if (high) "v4" else "p4"
            val full = if (branch) "v1" else if (high) "v5" else "p5"
            val settings = if (high) "v6" else "p6"
            val temporary = if (high) "v7" else "v0"
            val staging =
                if (branch)
                    "move-object v0, p4\n move-object v1, p5\n if-eqz p1, :no_swap\n move-object v0, p5\n move-object v1, p4\n :no_swap"
                else if (high)
                    "move-object/from16 v0, p0\n move/from16 v1, p1\n move-wide/from16 v2, p2\n move-object/from16 v4, p4\n move-object/from16 v5, p5\n move-object/from16 v6, p6"
                else ""
            file.writeText(
                """
    .class public final $type
    .super Ljava/lang/Object;
    .field public final identifier:I
    .field public final timestamp:J
    .field public final visible:Ljava/lang/CharSequence;
    .field public final complete:Ljava/lang/CharSequence;
    .field public final placement:Lchanged/settings;
    .field public final duplicate:Ljava/lang/CharSequence;
    .method public constructor <init>(IJLjava/lang/CharSequence;Ljava/lang/CharSequence;Lchanged/settings;)V
     .locals $locals
     invoke-direct {p0}, Ljava/lang/Object;-><init>()V
     $staging
     iput-object $settings, $receiver, $type->placement:Lchanged/settings;
     iput-wide $time, $receiver, $type->timestamp:J
     ${if(alias) "move-object/from16 $temporary, $visible\n iput-object $temporary, $receiver, $type->visible:Ljava/lang/CharSequence;" else "iput-object $visible, $receiver, $type->visible:Ljava/lang/CharSequence;"}
     iput $id, $receiver, $type->identifier:I
     ${if(overwrite) "const/4 v0, 0\n iput v0, $receiver, $type->identifier:I" else ""}
     ${if(missing) "" else "iput-object $full, $receiver, $type->complete:Ljava/lang/CharSequence;"}
     ${if(duplicate) "iput-object $visible, $receiver, $type->duplicate:Ljava/lang/CharSequence;" else ""}
     return-void
    .end method
   """
                    .trimIndent(),
            )
            val options = SmaliOptions().apply { outputDexFile = dir.resolve("classes.dex").path }
            check(Smali.assemble(options, listOf(file.path)))
            return DexFileFactory.loadDexFile(dir.resolve("classes.dex"), Opcodes.getDefault())
                .classes
                .single()
                .methods
                .single()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resolves renamed classes and reordered fields from constructor parameters`() {
        val x = findCaptionCueFields(fixture())
        assertEquals("identifier", x.id.name)
        assertEquals("timestamp", x.time.name)
        assertEquals("visible", x.visible.name)
        assertEquals("complete", x.full.name)
        assertEquals("placement", x.settings.name)
    }

    @Test
    fun `register count and temporary aliases do not change semantics`() {
        for (n in listOf(1, 3, 12)) {
            val x = findCaptionCueFields(fixture("Lunrelated/version$n;", n, true))
            assertEquals("visible", x.visible.name)
            assertEquals("complete", x.full.name)
        }
    }

    @Test
    fun `overwritten field no longer represents constructor parameter`() {
        assertFailsWith<IllegalStateException> {
            findCaptionCueFields(fixture(locals = 1, overwrite = true))
        }
    }

    @Test
    fun `conditional parameter swaps fail closed`() {
        assertFailsWith<IllegalStateException> {
            findCaptionCueFields(fixture(locals = 3, branch = true))
        }
    }

    @Test
    fun `ambiguous or missing assignments fail closed`() {
        assertFailsWith<IllegalStateException> { findCaptionCueFields(fixture(duplicate = true)) }
        assertFailsWith<IllegalStateException> { findCaptionCueFields(fixture(missing = true)) }
    }

    @Test
    fun `unavailable optional native layout does not block base caption features`() {
        assertNull(
            optionalCaptionSupport("test layout") {
                findCaptionCueFields(fixture(locals = 3, branch = true))
            },
        )
        assertNotNull(optionalCaptionSupport("test layout") { findCaptionCueFields(fixture()) })
    }

    @Test
    fun `unrelated programming errors are not hidden as compatibility failures`() {
        assertFailsWith<ArithmeticException> {
            optionalCaptionSupport("test layout") { throw ArithmeticException("test") }
        }
    }
}
