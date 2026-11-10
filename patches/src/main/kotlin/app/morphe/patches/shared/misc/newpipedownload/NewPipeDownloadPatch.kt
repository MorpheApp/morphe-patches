package app.morphe.patches.shared.misc.newpipedownload

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.listDecodableFiles
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.inputStreamFromBundledResource

internal const val NEWPIPE_DOWNLOADS_CLASS = "Lapp/morphe/extension/newpipe/NewPipeDownloads;"

internal val newPipeDownloadExtensionPatch = patch {
    extendWith("extensions/newpipe/newpipe.mpe")

    execute {
        copyResources("spoof", ResourceGroup("raw", "po_token.html"))
        // Extension archives contain DEX only; Rhino's ResourceBundle also needs its data files.
        for (name in listOf("Messages.properties", "Messages_en.properties",
                            "Messages_fr.properties", "Messages_zh_CN.properties")) {
            val path = "org/mozilla/javascript/resources/$name"
            val bytes = inputStreamFromBundledResource("newpipe", path)!!.use { it.readBytes() }
            if (listDecodableFiles(path).isNotEmpty()) {
                if (!resourceFile(path).readBytes().contentEquals(bytes)) {
                    throw PatchException("The APK already contains a different Rhino resource: $path")
                }
            } else {
                resourceFile(path, false).apply { parentFile.mkdirs(); writeBytes(bytes) }
            }
        }
        resourceXml("AndroidManifest.xml").use { manifest ->
            val names = manifest.elements().mapNotNullTo(HashSet()) { it.attribute("android:name") }
            val application = manifest.elements("application").first()
            for (permission in listOf(
                "android.permission.INTERNET",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION",
            )) {
                if (permission !in names) {
                    application.parent!!.insertChild(application.index, "uses-permission") {
                        setAttribute("android:name", permission)
                    }
                }
            }
            val serviceName = "app.morphe.extension.newpipe.NewPipeDownloadService"
            if (serviceName !in names) {
                application.appendChild("service") {
                    setAttribute("android:name", serviceName)
                    setAttribute("android:exported", "false")
                    setAttribute("android:foregroundServiceType", "dataSync")
                }
            }
        }
    }
}
