package app.morphe.patches.youtube.platform.hooks

/**
 * Shared registry for YouTube hook installation.
 */
object HookRegistry {
    private val hooks = mutableListOf<String>()

    fun register(hook: String) {
        hooks += hook
    }

    fun hooks(): List<String> = hooks.toList()
}
