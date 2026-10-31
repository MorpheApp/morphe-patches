package app.morphe.patches.youtube.core.bootstrap

/**
 * Registry for the YouTube patch bundle.
 */
object YoutubePatchRegistry {
    private val entries = mutableListOf<String>()

    fun register(entry: String) {
        entries += entry
    }

    fun entries(): List<String> = entries.toList()
}
