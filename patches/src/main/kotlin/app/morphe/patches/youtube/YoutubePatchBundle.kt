package app.morphe.patches.youtube

/**
 * Central bundle for the refactored YouTube patch architecture.
 *
 * This class is intentionally lightweight: it acts as the top-level entry point for the
 * feature-first package structure introduced during the refactor.
 */
object YoutubePatchBundle {
    const val PACKAGE_NAME = "app.morphe.patches.youtube"

    fun register() {
        // Hook registration is handled by the bootstrap layer.
    }
}
