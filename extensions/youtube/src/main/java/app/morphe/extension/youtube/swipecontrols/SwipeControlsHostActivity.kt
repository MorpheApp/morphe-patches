package app.morphe.extension.youtube.swipecontrols

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import app.morphe.extension.shared.Logger.printDebug
import app.morphe.extension.shared.Logger.printException
import app.morphe.extension.youtube.patches.VersionCheckPatch
import app.morphe.extension.youtube.settings.Settings
import app.morphe.extension.youtube.shared.PlayerType
import app.morphe.extension.youtube.swipecontrols.controller.AudioVolumeController
import app.morphe.extension.youtube.swipecontrols.controller.ScreenBrightnessController
import app.morphe.extension.youtube.swipecontrols.controller.SwipeZonesController
import app.morphe.extension.youtube.swipecontrols.controller.VolumeKeysController
import app.morphe.extension.youtube.swipecontrols.controller.gesture.ClassicSwipeController
import app.morphe.extension.youtube.swipecontrols.controller.gesture.PressToSwipeController
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.GestureController
import app.morphe.extension.youtube.swipecontrols.misc.Rectangle
import app.morphe.extension.youtube.swipecontrols.views.SwipeControlsOverlayLayout
import java.lang.ref.WeakReference
import kotlin.concurrent.Volatile

/**
 * The main controller for volume and brightness swipe controls.
 * Note that the superclass is overwritten to the superclass of the MainActivity at patch time.
 */
class SwipeControlsHostActivity : Activity() {
    /**
     * Current instance of [AudioVolumeController].
     */
    var audio: AudioVolumeController? = null

    /**
     * Current instance of [ScreenBrightnessController].
     */
    var screen: ScreenBrightnessController? = null

    /**
     * Current instance of [SwipeControlsConfigurationProvider].
     */
    lateinit var config: SwipeControlsConfigurationProvider

    /**
     * Current instance of [SwipeControlsOverlayLayout].
     */
    lateinit var overlay: SwipeControlsOverlayLayout

    /**
     * Current instance of [SwipeZonesController].
     */
    lateinit var zones: SwipeZonesController

    /**
     * Main gesture controller.
     */
    private lateinit var gesture: GestureController

    /**
     * Main volume keys controller.
     */
    private lateinit var keys: VolumeKeysController

    /**
     * Current content view with id [android.R.id.content].
     */
    private val contentRoot
        get() = window.decorView.findViewById<ViewGroup>(android.R.id.content)

    /**
     * Whether the status bar is visible on Android 15+ (edge-to-edge display).
     */
    @Volatile
    var statusBarVisible: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialize()
    }

    override fun onStart() {
        super.onStart()
        reAttachOverlays()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ensureInitialized()
        return if ((ev != null) && gesture.submitTouchEvent(ev)) {
            true
        } else {
            super.dispatchTouchEvent(ev)
        }
    }

    override fun dispatchKeyEvent(ev: KeyEvent?): Boolean {
        ensureInitialized()
        return if ((ev != null) && keys.onKeyEvent(ev)) {
            true
        } else {
            super.dispatchKeyEvent(ev)
        }
    }

    /**
     * Dispatches a touch event to downstream views.
     *
     * @param event The event to dispatch.
     * @return Whether the event was consumed.
     */
    fun dispatchDownstreamTouchEvent(event: MotionEvent) =
        super.dispatchTouchEvent(event)

    /**
     * Ensures that swipe controllers are initialized and attached.
     * On some ROMs with SDK <= 23, [onCreate] and [onStart] may not be called correctly.
     * See https://github.com/revanced/revanced-patches/issues/446
     */
    private fun ensureInitialized() {
        if (!this::config.isInitialized) {
            printException {
                "swipe controls were not initialized in onCreate, initializing on-the-fly (SDK is ${Build.VERSION.SDK_INT})"
            }
            initialize()
            reAttachOverlays()
        }
    }

    /**
     * Initializes controllers, only call once.
     */
    private fun initialize() {
        // create controllers
        printDebug { "initializing swipe controls controllers" }
        config = SwipeControlsConfigurationProvider()
        keys = VolumeKeysController(this)
        audio = createAudioController()
        screen = createScreenController()

        // create overlay
        SwipeControlsOverlayLayout(this, config).let {
            overlay = it
            contentRoot.addView(it)
        }

        // create swipe zone controller
        zones = SwipeZonesController(this) {
            Rectangle(
                contentRoot.x.toInt(),
                contentRoot.y.toInt(),
                contentRoot.width,
                contentRoot.height,
            )
        }

        // create the gesture controller
        gesture = createGestureController()

        // listen for changes in the player type
        PlayerType.onChange += this::onPlayerTypeChanged

        // set current instance reference
        currentHost = WeakReference(this)

        // fix edge-to-edge display
        // see: https://github.com/MorpheApp/morphe-patches/issues/658
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val rootView = contentRoot.parent
            if (rootView is ViewGroup) {
                rootView.setOnApplyWindowInsetsListener { _, insets ->
                    statusBarVisible = insets.isVisible(WindowInsets.Type.statusBars())
                    insets
                }
            }
        }
    }

    /**
     * Re-attaches swipe overlays.
     */
    private fun reAttachOverlays() {
        printDebug { "attaching swipe controls overlay" }
        contentRoot.removeView(overlay)
        contentRoot.addView(overlay)
    }

    // Flag that indicates whether the brightness has been saved and restored default brightness
    private var isBrightnessSaved = false

    /**
     * Called when the player type changes.
     *
     * @param type The new player type.
     */
    private fun onPlayerTypeChanged(type: PlayerType) {
        when {
            // If saving and restoring brightness is enabled, and the player type is WATCH_WHILE_FULLSCREEN,
            // and brightness has already been saved, then restore the screen brightness
            config.shouldSaveAndRestoreBrightness && type == PlayerType.WATCH_WHILE_FULLSCREEN && isBrightnessSaved -> {
                screen?.restore()
                isBrightnessSaved = false
            }
            // If saving and restoring brightness is enabled, and brightness has not been saved,
            // then save the current screen state, restore default brightness, and mark brightness as saved
            config.shouldSaveAndRestoreBrightness && !isBrightnessSaved -> {
                screen?.save()
                screen?.restoreDefaultBrightness()
                isBrightnessSaved = true
            }
            // If saving and restoring brightness is disabled, simply keep the default brightness
            else -> screen?.restoreDefaultBrightness()
        }
    }

    /**
     * Creates the audio volume controller.
     */
    private fun createAudioController() =
        if (config.enableVolumeControls) {
            AudioVolumeController(this)
        } else {
            null
        }

    /**
     * Creates the screen brightness controller instance.
     */
    private fun createScreenController() =
        if (config.enableBrightnessControl) {
            ScreenBrightnessController(this)
        } else {
            null
        }

    /**
     * Creates the gesture controller based on settings.
     */
    private fun createGestureController() =
        if (config.shouldEnablePressToSwipe) {
            PressToSwipeController(this)
        } else {
            ClassicSwipeController(this)
        }

    companion object {
        /**
         * The currently active swipe controls host.
         * The reference may be null.
         */
        @JvmStatic
        var currentHost: WeakReference<SwipeControlsHostActivity> = WeakReference(null)
            private set

        /**
         * Injection point.
         */
        @Suppress("unused")
        @JvmStatic
        fun allowSwipeChangeVideo(original: Boolean): Boolean =
            // Feature can cause crashing if forced in newer targets.
            !VersionCheckPatch.IS_20_22_OR_GREATER && Settings.SWIPE_CHANGE_VIDEO.get()
    }
}
