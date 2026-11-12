/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.devicefilessearch

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.io.path.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceFilesSearchPatchTest {
    @Test
    fun `device search replaces failed GMS index lookup but retains native consumers`() {
        verifyDeviceSearch(validate = true)
    }

    @Test
    fun `device search resolves in normal Manager matching mode`() {
        verifyDeviceSearch(validate = false)
    }

    private fun verifyDeviceSearch(validate: Boolean) {
        val apk = System.getProperty("jamApk")
        assumeTrue(!apk.isNullOrBlank(), "Pass -PjamApk with a stock Music APK")
        val fixture = patch {
            execute {
                val search = DeviceFilesSearchFingerprint.method
                val owner = classDefBy(search.definingClass)
                val unchanged = owner.methods.filter { it.name != search.name }.map { it.implementation }
                installDeviceFilesSearch()
                val calls = search.instructions.mapNotNull { it.getReference<MethodReference>() }.toList()
                assertTrue(calls.any {
                    it.definingClass == "Lapp/morphe/extension/music/patches/DeviceFilesSearchPatch;" &&
                        it.name == "createSearch"
                }, "Device Files must query MediaStore rather than the unavailable GMS search index")
                assertTrue(calls.any { it == SubmitSearchFingerprint.originalMethod })
                assertEquals(unchanged, owner.methods.filter { it.name != search.name }.map { it.implementation })
                assertEquals(2, calls.size, "Only build the query and submit it to the native executor")
            }
        }
        val workspace = createTempDirectory("device-files-search")
        Patcher(PatcherConfig(Path(apk).toFile(), workspace.toFile(), validate = validate)).use { patcher ->
            patcher += setOf(fixture)
            runBlocking {
                patcher().collect { result ->
                    assertEquals(null, result.exception, result.exception?.stackTraceToString().orEmpty())
                }
            }
        }
    }
}
