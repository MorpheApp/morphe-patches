/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4135
 *
 * This file programmatically renders the Morphe logo.
 *
 * "Morphe" and associated logos are trademarks and copyrighted
 * works owned by Morphe. No rights under trademark law are
 * granted by this software or license.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_39_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val GLOW_ANIMATION_FILE = "res/raw/rive_phantom.riv"
private const val GLOW_MORPHE_ANIMATION_FILE = "res/raw/morphe_startup_animation_glow.riv"

/**
 * The Morphe logo comes into focus out of a glow. Made only from the Morphe logo,
 * for 21.30 - 21.38 that has no glow animation to make the Glow Morphe animation from.
 */
private const val GLOW_MORPHE_LEGACY_ANIMATION_FILE = "morphe_startup_animation_glow_legacy.riv"

/**
 * The same animation with the Morphe play icon, for the play launcher icons.
 */
private const val GLOW_MORPHE_LEGACY_PLAY_ANIMATION_FILE = "morphe_startup_animation_glow_legacy_play.riv"

// Rive type keys.
private const val TYPE_ARTBOARD = 1
private const val TYPE_STRAIGHT_VERTEX = 5
private const val TYPE_CUBIC_DETACHED_VERTEX = 6
private const val TYPE_POINTS_PATH = 16
private const val TYPE_SOLID_COLOR = 18
private const val TYPE_GRADIENT_STOP = 19
private const val TYPE_FILL = 20
private const val TYPE_LINEAR_GRADIENT = 22
private const val TYPE_KEYED_OBJECT = 25
private const val TYPE_KEYED_PROPERTY = 26
private const val TYPE_KEY_FRAME_DOUBLE = 30
private const val TYPE_LINEAR_ANIMATION = 31
private const val TYPE_KEY_FRAME_COLOR = 37

// Rive property keys, which are unique across all types.
private const val PARENT_ID = 5
private const val VERTEX_X = 24
private const val VERTEX_Y = 25
private const val PATH_IS_CLOSED = 32
private const val GRADIENT_START_Y = 33
private const val GRADIENT_END_X = 34
private const val GRADIENT_END_Y = 35
private const val SOLID_COLOR_VALUE = 37
private const val GRADIENT_STOP_COLOR_VALUE = 38
private const val GRADIENT_STOP_POSITION = 39
private const val GRADIENT_START_X = 42
private const val GRADIENT_OPACITY = 46
private const val KEYED_OBJECT_ID = 51
private const val KEYED_PROPERTY_KEY = 53
private const val KEY_FRAME_FRAME = 67
private const val KEY_FRAME_INTERPOLATOR_ID = 69
private const val KEY_FRAME_DOUBLE_VALUE = 70
private const val VERTEX_IN_ROTATION = 84
private const val VERTEX_IN_DISTANCE = 85
private const val VERTEX_OUT_ROTATION = 86
private const val VERTEX_OUT_DISTANCE = 87
private const val KEY_FRAME_COLOR_VALUE = 88
private const val PATH_FLAGS = 128
private const val DRAWABLE_FLAGS = 129
private const val ARTBOARD_STYLE_ID = 494

private const val DRAWABLE_FLAG_HIDDEN = 1

/**
 * The dark backdrop of the glow animation, which the extension changes to the splash screen color.
 */
private const val GLOW_BACKDROP_COLOR = 0xFF09090A.toInt()

/**
 * The Morphe logo (morphe-branding assets/morphe-logo/morphe_logo.svg):
 * the left pillar, the right pillar and the wave between them.
 */
private val morpheLogoPaths = listOf(
    "M123,140 c-21,0 -39,17 -40,38 v192 c1,21 19,38 40,38 21,0 39,-17 40,-38 V178 c-1,-21 -19,-38 -40,-38 z",
    "M349,285 v85 c1,21 19,38 40,38 21,0 39,-17 40,-38 V182 c-11,-14 -74,63 -80,103 z",
    "M127,108 c-34,0 -44,25 -44,40 v54 c30,-33 75,27 80,33 28,32 44,87 93,89 48,-2 67,-56 93,-89 " +
        "0,0 45,-74 80,-80 0,-28 -11,-47 -44,-47 -34,0 -58,50 -75,72 -17,22 -25,46 -54,46 -29,0 " +
        "-38,-25 -54,-46 -17,-22 -41,-72 -75,-72 z"
)
private const val MORPHE_LOGO_LEFT = 83.0
private const val MORPHE_LOGO_TOP = 108.0
private const val MORPHE_LOGO_RIGHT = 429.0
private const val MORPHE_LOGO_BOTTOM = 408.0

/**
 * The gradient of the wave goes from the blue of the left pillar to the teal of the right pillar.
 */
private const val MORPHE_LOGO_GRADIENT_Y = 216.0
private const val MORPHE_LOGO_LEFT_PILLAR_RIGHT = 163.0
private const val MORPHE_LOGO_RIGHT_PILLAR_LEFT = 349.0
private const val MORPHE_BLUE = 0xFF1E5AA8.toInt()
private const val MORPHE_TEAL = 0xFF00AFAE.toInt()

/**
 * Hues in degrees. The reds, oranges and pinks of the app become blues around the Morphe blue,
 * and its blues and purples become teals around the Morphe teal.
 */
private const val APP_RED_HUE = 348.0
private const val APP_BLUE_HUE = 235.0
private const val MORPHE_BLUE_HUE = 214.0
private const val MORPHE_TEAL_HUE = 180.0

/**
 * The Morphe logo comes into focus in this part of the time the app logo takes.
 */
private const val KEY_FRAME_TIME_SCALE = 0.7

/**
 * Adds a copy of the glow startup animation of the app with the Morphe logo in place of the app logo,
 * which the Glow Morphe splash screen style plays. The copy is made from the animation of the app.
 * 21.30 - 21.38 plays an animation made only from the Morphe logo instead.
 */
internal val glowMorpheAnimationResourcePatch = resourcePatch {
    dependsOn(versionCheckPatch)

    execute {
        // The glow animation exists in 21.39+.
        if (!is_21_39_or_greater) {
            if (is_21_30_or_greater) {
                copyResources(
                    "custom-branding",
                    ResourceGroup("raw", GLOW_MORPHE_LEGACY_ANIMATION_FILE, GLOW_MORPHE_LEGACY_PLAY_ANIMATION_FILE)
                )
            }
            return@execute
        }

        val animation = RiveFile(get(GLOW_ANIMATION_FILE).readBytes())
        replaceLogoWithMorpheLogo(animation)
        moveKeyFramesEarlier(animation)
        get(GLOW_MORPHE_ANIMATION_FILE).writeBytes(animation.toByteArray())
    }
}

private data class Point(val x: Double, val y: Double)

/**
 * A curve of a path, from the end of the previous curve.
 */
private class Segment(val control1: Point, val control2: Point, val end: Point)

/**
 * A vertex of a closed path, and the control points of the curves into it and out of it.
 */
private class Vertex(val position: Point, val inControl: Point, val outControl: Point)

/**
 * The logo of the glow animation is a rounded rectangle with a play triangle. The glow is drawn by
 * copies of the rounded rectangle with feathered fills, and the sharp logo at the end is a copy with
 * a solid fill. The copies get the shape of the Morphe logo and the triangles are removed, so the
 * Morphe logo comes into focus out of the glow, and the colors change to the Morphe colors.
 */
private fun replaceLogoWithMorpheLogo(file: RiveFile) {
    val objects = file.objects
    val artboardIndex = objects.indexOfFirst { it.typeKey == TYPE_ARTBOARD }
    val animationIndex = objects.indexOfFirst { it.typeKey == TYPE_LINEAR_ANIMATION }
    if (artboardIndex !in 0..animationIndex) {
        throw PatchException("Could not find the artboard of the glow animation")
    }

    // The objects of the artboard, and the animations after them, refer to objects of the artboard
    // by their index. The references are kept as objects while objects are added and removed.
    val components = objects.subList(artboardIndex, animationIndex).toList()
    val animations = objects.subList(animationIndex, objects.size).toList()
    val references = mutableMapOf<RiveProperty, RiveObject>()
    fun keepReference(riveObject: RiveObject, key: Int) {
        riveObject.property(key)?.let { references[it] = components[it.value as Int] }
    }
    components.forEach {
        keepReference(it, PARENT_ID)
        keepReference(it, ARTBOARD_STYLE_ID)
    }
    animations.forEach {
        keepReference(it, KEYED_OBJECT_ID)
        keepReference(it, KEY_FRAME_INTERPOLATOR_ID)
    }

    // An object without a parent is in the artboard.
    val artboard = components.first()
    fun parent(riveObject: RiveObject) = riveObject.property(PARENT_ID)?.let(references::getValue) ?: artboard
    fun children(riveObject: RiveObject) = components.filter { it !== artboard && parent(it) === riveObject }

    val pathVertices = components.filter { it.typeKey == TYPE_POINTS_PATH }.associateWith(::children)
    val rectanglePaths = pathVertices.filterValues { vertices ->
        vertices.size == 16 && vertices.all { it.typeKey == TYPE_CUBIC_DETACHED_VERTEX }
    }
    val trianglePaths = pathVertices.filterValues { vertices ->
        vertices.size == 3 && vertices.all { it.typeKey == TYPE_STRAIGHT_VERTEX }
    }
    if (rectanglePaths.isEmpty() || trianglePaths.isEmpty()) {
        throw PatchException("Could not find the logo of the glow animation")
    }

    // The visible copy of the rounded rectangle whose fill is only a solid color.
    val logoColor = rectanglePaths.keys.asSequence().map(::parent)
        .filter { it.uint(DRAWABLE_FLAGS) and DRAWABLE_FLAG_HIDDEN == 0 }
        .flatMap(::children)
        .filter { it.typeKey == TYPE_FILL }
        .mapNotNull { children(it).singleOrNull() }
        .single { it.typeKey == TYPE_SOLID_COLOR }

    (components + animations).forEach { riveObject ->
        riveObject.properties.forEach { property ->
            if (property.type == RivePropertyType.COLOR && property.value != GLOW_BACKDROP_COLOR) {
                property.value = toMorpheColor(property.value as Int)
            }
        }
    }

    // The Morphe logo is placed in the middle of the rounded rectangle, at the same width.
    val rectangle = rectanglePaths.values.first().map {
        Point(it.float(VERTEX_X).toDouble(), it.float(VERTEX_Y).toDouble())
    }
    val rectangleLeft = rectangle.minOf { it.x }
    val rectangleRight = rectangle.maxOf { it.x }
    val rectangleCenterY = (rectangle.minOf { it.y } + rectangle.maxOf { it.y }) / 2
    val scale = (rectangleRight - rectangleLeft) / (MORPHE_LOGO_RIGHT - MORPHE_LOGO_LEFT)
    fun toRectangle(point: Point) = Point(
        (point.x - (MORPHE_LOGO_LEFT + MORPHE_LOGO_RIGHT) / 2) * scale + (rectangleLeft + rectangleRight) / 2,
        (point.y - (MORPHE_LOGO_TOP + MORPHE_LOGO_BOTTOM) / 2) * scale + rectangleCenterY
    )

    // The paths are filled with a fill rule that depends on their direction,
    // so the Morphe logo goes in the same direction as the rounded rectangle.
    val clockwise = signedArea(rectangle) > 0
    val contours = morpheLogoPaths.flatMap(::parseSvgPath).map { contour ->
        val vertices = contour.map {
            Vertex(toRectangle(it.position), toRectangle(it.inControl), toRectangle(it.outControl))
        }
        if (signedArea(vertices.map { it.position }) > 0 == clockwise) {
            vertices
        } else {
            vertices.reversed().map { Vertex(it.position, it.outControl, it.inControl) }
        }
    }

    val removedVertices = (rectanglePaths.values + trianglePaths.values).flatten().toSet()
    val newComponents = mutableListOf<RiveObject>()
    components.forEach { component ->
        if (component in removedVertices) return@forEach
        newComponents += component

        if (component in rectanglePaths) {
            var path = component
            contours.forEachIndexed { index, contour ->
                if (index > 0) {
                    path = RiveObject(TYPE_POINTS_PATH).apply {
                        references[set(PARENT_ID, RivePropertyType.UINT, 0)] = parent(component)
                        component.property(PATH_FLAGS)?.let { set(PATH_FLAGS, RivePropertyType.UINT, it.value) }
                        set(PATH_IS_CLOSED, RivePropertyType.UINT, 1)
                    }
                    newComponents += path
                }
                contour.forEach { vertex ->
                    newComponents += RiveObject(TYPE_CUBIC_DETACHED_VERTEX).apply {
                        references[set(PARENT_ID, RivePropertyType.UINT, 0)] = path
                        setVertex(vertex)
                    }
                }
            }
        } else if (component === logoColor) {
            // The sharp logo gets the colors of the Morphe logo.
            val start = toRectangle(Point(MORPHE_LOGO_LEFT, MORPHE_LOGO_GRADIENT_Y))
            val end = toRectangle(Point(MORPHE_LOGO_RIGHT, MORPHE_LOGO_GRADIENT_Y))
            component.apply {
                typeKey = TYPE_LINEAR_GRADIENT
                properties.removeAll { it.key == SOLID_COLOR_VALUE }
                set(GRADIENT_START_X, RivePropertyType.FLOAT, start.x.toFloat())
                set(GRADIENT_START_Y, RivePropertyType.FLOAT, start.y.toFloat())
                set(GRADIENT_END_X, RivePropertyType.FLOAT, end.x.toFloat())
                set(GRADIENT_END_Y, RivePropertyType.FLOAT, end.y.toFloat())
            }

            val width = MORPHE_LOGO_RIGHT - MORPHE_LOGO_LEFT
            listOf(
                0.0 to MORPHE_BLUE,
                (MORPHE_LOGO_LEFT_PILLAR_RIGHT - MORPHE_LOGO_LEFT) / width to MORPHE_BLUE,
                (MORPHE_LOGO_RIGHT_PILLAR_LEFT - MORPHE_LOGO_LEFT) / width to MORPHE_TEAL,
                1.0 to MORPHE_TEAL
            ).forEach { (position, color) ->
                newComponents += RiveObject(TYPE_GRADIENT_STOP).apply {
                    references[set(PARENT_ID, RivePropertyType.UINT, 0)] = component
                    set(GRADIENT_STOP_COLOR_VALUE, RivePropertyType.COLOR, color)
                    set(GRADIENT_STOP_POSITION, RivePropertyType.FLOAT, position.toFloat())
                }
            }
        }
    }

    // The sharp logo fades in with its color, and now fades in with the opacity of its gradient.
    var keyedObject: RiveObject? = null
    animations.forEach { animation ->
        when (animation.typeKey) {
            TYPE_KEYED_OBJECT -> keyedObject = animation.property(KEYED_OBJECT_ID)?.let(references::getValue) ?: artboard
            TYPE_KEYED_PROPERTY -> if (keyedObject === logoColor) {
                animation.set(KEYED_PROPERTY_KEY, RivePropertyType.UINT, GRADIENT_OPACITY)
            }
            TYPE_KEY_FRAME_COLOR -> if (keyedObject === logoColor) {
                val alpha = (animation.uint(KEY_FRAME_COLOR_VALUE) ushr 24) / 255.0
                animation.typeKey = TYPE_KEY_FRAME_DOUBLE
                animation.properties.removeAll { it.key == KEY_FRAME_COLOR_VALUE }
                animation.set(KEY_FRAME_DOUBLE_VALUE, RivePropertyType.FLOAT, alpha.toFloat())
            }
        }
    }

    val indices = newComponents.withIndex().associate { (index, component) -> component to index }
    references.forEach { (property, target) ->
        property.value = indices[target]
            ?: throw PatchException("A removed object of the glow animation is still used")
    }

    val head = objects.subList(0, artboardIndex).toList()
    objects.clear()
    objects += head + newComponents + animations
}

/**
 * Plays the animation faster. Only the key frames move, and the timeline keeps its length,
 * so the logo then stays in focus until the timeline loops as the original animation does.
 * The extension finds the timeline by its length and loop.
 */
private fun moveKeyFramesEarlier(file: RiveFile) {
    file.objects.forEach { riveObject ->
        riveObject.property(KEY_FRAME_FRAME)?.let {
            it.value = ((it.value as Int) * KEY_FRAME_TIME_SCALE).roundToInt()
        }
    }
}

private fun RiveObject.setVertex(vertex: Vertex) {
    val (x, y) = vertex.position
    set(VERTEX_X, RivePropertyType.FLOAT, x.toFloat())
    set(VERTEX_Y, RivePropertyType.FLOAT, y.toFloat())
    set(VERTEX_IN_ROTATION, RivePropertyType.FLOAT, atan2(vertex.inControl.y - y, vertex.inControl.x - x).toFloat())
    set(VERTEX_IN_DISTANCE, RivePropertyType.FLOAT, hypot(vertex.inControl.x - x, vertex.inControl.y - y).toFloat())
    set(VERTEX_OUT_ROTATION, RivePropertyType.FLOAT, atan2(vertex.outControl.y - y, vertex.outControl.x - x).toFloat())
    set(VERTEX_OUT_DISTANCE, RivePropertyType.FLOAT, hypot(vertex.outControl.x - x, vertex.outControl.y - y).toFloat())
}

private fun signedArea(points: List<Point>) = points.indices.sumOf { i ->
    val next = points[(i + 1) % points.size]
    points[i].x * next.y - next.x * points[i].y
}

/**
 * The closed paths of SVG path data. Only has the commands the Morphe logo uses.
 */
private fun parseSvgPath(data: String): List<List<Vertex>> {
    val tokens = Regex("""[A-Za-z]|-?\d*\.?\d+""").findAll(data).map { it.value }.toList()
    val contours = mutableListOf<List<Vertex>>()
    var segments = mutableListOf<Segment>()
    var start = Point(0.0, 0.0)
    var current = start
    var command = 'M'
    var index = 0
    fun number() = tokens[index++].toDouble()

    while (index < tokens.size) {
        if (tokens[index][0].isLetter()) command = tokens[index++][0]
        val relative = command.isLowerCase()
        fun point(x: Double, y: Double) = if (relative) Point(current.x + x, current.y + y) else Point(x, y)

        when (command.uppercaseChar()) {
            'M' -> {
                start = point(number(), number())
                current = start
            }
            'C' -> {
                val segment = Segment(point(number(), number()), point(number(), number()), point(number(), number()))
                segments += segment
                current = segment.end
            }
            'V' -> {
                val y = number()
                val end = Point(current.x, if (relative) current.y + y else y)
                segments += Segment(current, end, end)
                current = end
            }
            'Z' -> {
                // A path that does not end at its start is closed with a line.
                if (segments.last().end != start) segments += Segment(segments.last().end, start, start)
                contours += segments.indices.map { i ->
                    val previous = segments[(i + segments.size - 1) % segments.size]
                    Vertex(previous.end, previous.control2, segments[i].control1)
                }
                segments = mutableListOf()
                current = start
            }
            else -> throw PatchException("Unsupported SVG path command: $command")
        }
    }

    return contours
}

/**
 * The same color in the Morphe colors, with the same saturation, brightness and alpha.
 * Gray colors stay the same.
 */
private fun toMorpheColor(color: Int): Int {
    val red = (color ushr 16 and 0xFF) / 255.0
    val green = (color ushr 8 and 0xFF) / 255.0
    val blue = (color and 0xFF) / 255.0
    val max = maxOf(red, green, blue)
    val range = max - minOf(red, green, blue)
    val saturation = if (max == 0.0) 0.0 else range / max
    if (saturation < 0.1) return color

    val hueSixths = when (max) {
        red -> (max - blue) / range - (max - green) / range
        green -> 2 + (max - red) / range - (max - blue) / range
        else -> 4 + (max - green) / range - (max - red) / range
    }
    val hue = (hueSixths / 6).mod(1.0) * 360
    val newHue = if (hue !in 70.0..<290.0) {
        MORPHE_BLUE_HUE - ((hue - APP_RED_HUE + 180).mod(360.0) - 180) * 0.7
    } else {
        MORPHE_TEAL_HUE + (hue - APP_BLUE_HUE) * 0.5
    }

    val sector = newHue.mod(360.0) / 360 * 6
    val sectorIndex = floor(sector).toInt()
    val fraction = sector - sectorIndex
    val low = max * (1 - saturation)
    val falling = max * (1 - saturation * fraction)
    val rising = max * (1 - saturation * (1 - fraction))
    val (newRed, newGreen, newBlue) = when (sectorIndex % 6) {
        0 -> Triple(max, rising, low)
        1 -> Triple(falling, max, low)
        2 -> Triple(low, max, rising)
        3 -> Triple(low, falling, max)
        4 -> Triple(rising, low, max)
        else -> Triple(max, low, falling)
    }

    fun channel(value: Double) = (value * 255).roundToInt()
    return (color and 0xFF000000.toInt()) or
        (channel(newRed) shl 16) or (channel(newGreen) shl 8) or channel(newBlue)
}
