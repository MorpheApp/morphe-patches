package app.morphe.patches.youtube.layout.captions

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class CaptionWindowMeasurePatchTest {
    private fun fixture(
        correct: Boolean = false,
        duplicate: Boolean = false,
        widthResult: Int = 6,
        heightResult: Int = 7,
        inverted: Boolean = false,
    ): Method {
        val directory = createTempDirectory("caption-measure-").toFile()
        try {
            val source = directory.resolve("Window.smali")
            source.writeText(
                """
                .class public Lcom/google/android/libraries/youtube/player/subtitles/ui/SubtitleWindowView;
                .super Landroid/view/ViewGroup;
                .method protected onMeasure(II)V
                    .registers 16
                    const/high16 v4, -0x80000000
                    invoke-static {p1}, Landroid/view/View${'$'}MeasureSpec;->getSize(I)I
                    move-result v0
                    invoke-static {p2}, Landroid/view/View${'$'}MeasureSpec;->getSize(I)I
                    move-result v1
                    const/16 v9, 92
                    invoke-static {p1}, Landroid/view/View${'$'}MeasureSpec;->getMode(I)I
                    move-result v$widthResult
                    const/high16 v2, 0x40000000
                    if-ne v$widthResult, v4, :width_exact
                    invoke-static {v0, v9}, Ljava/lang/Math;->min(II)I
                    move-result v0
                    goto :height
                :width_exact
                    if-ne v$widthResult, v2, :width_wrap
                    goto :height
                :width_wrap
                    move v0, v9
                :height
                    invoke-static {p2}, Landroid/view/View${'$'}MeasureSpec;->getMode(I)I
                    move-result v$heightResult
                    if-ne v$heightResult, v4, :height_exact
                    invoke-static {v1, v9}, Ljava/lang/Math;->min(II)I
                    move-result v1
                    goto :done
                :height_exact
                    ${if(inverted) "if-eq v${if(correct)heightResult else widthResult}, v2, :done\n goto :height_wrap" else "if-ne v${if(correct)heightResult else widthResult}, v2, :height_wrap"}
                    ${if (duplicate) "if-ne v$widthResult, v2, :height_wrap" else ""}
                    goto :done
                :height_wrap
                    move v1, v9
                :done
                    invoke-virtual {p0, v0, v1}, Landroid/view/View;->setMeasuredDimension(II)V
                    return-void
                .end method
            """
                    .trimIndent(),
            )
            val dex = directory.resolve("fixture.dex")
            assertTrue(
                Smali.assemble(
                    SmaliOptions().apply {
                        outputDexFile = dex.path
                        jobs = 1
                    },
                    source.path,
                ),
            )
            val file = DexFileFactory.loadDexFile(dex, Opcodes.getDefault())
            return file.classes.single().methods.single()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `wrong height comparison uses height mode result`() {
        val method = fixture()
        val correction = assertNotNull(findCaptionHeightModeCorrection(method))
        assertEquals(7, correction.registerA)
        assertEquals(2, correction.registerB)
        assertTrue(correction.instructionIndex > 0)
    }

    @Test
    fun `registers come from mode calls rather than fixed register numbers`() {
        val correction =
            assertNotNull(
                findCaptionHeightModeCorrection(fixture(widthResult = 8, heightResult = 10)),
            )
        assertEquals(10, correction.registerA)
    }

    @Test
    fun `already correct height comparison needs no change`() {
        assertNull(findCaptionHeightModeCorrection(fixture(correct = true)))
    }

    @Test
    fun `ambiguous height comparisons are rejected`() {
        assertFailsWith<IllegalStateException> {
            findCaptionHeightModeCorrection(fixture(duplicate = true))
        }
    }

    @Test
    fun `inverted exact branch also resolves without changing its direction`() {
        val method = fixture(inverted = true)
        val correction = assertNotNull(findCaptionHeightModeCorrection(method))
        assertEquals(7, correction.registerA)
        assertEquals(
            com.android.tools.smali.dexlib2.Opcode.IF_EQ,
            method.implementation!!.instructions.toList()[correction.instructionIndex].opcode,
        )
        assertNull(findCaptionHeightModeCorrection(fixture(correct = true, inverted = true)))
    }
}
