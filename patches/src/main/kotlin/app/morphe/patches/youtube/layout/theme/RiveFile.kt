/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4135
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.patch.PatchException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The type a Rive property value is written with, in the order the file header numbers them.
 * A boolean is written the same as an unsigned integer of 0 or 1.
 */
internal enum class RivePropertyType {
    UINT,
    STRING,
    FLOAT,
    COLOR
}

/**
 * @param value An [Int] for [RivePropertyType.UINT] and [RivePropertyType.COLOR],
 *              a [Float] for [RivePropertyType.FLOAT], and a [ByteArray] for [RivePropertyType.STRING].
 */
internal class RiveProperty(val key: Int, val type: RivePropertyType, var value: Any)

/**
 * An object of a Rive file, and its properties that do not have the default value.
 */
internal class RiveObject(var typeKey: Int) {
    val properties = mutableListOf<RiveProperty>()

    fun property(key: Int) = properties.firstOrNull { it.key == key }

    fun uint(key: Int) = property(key)?.value as Int? ?: 0

    fun float(key: Int) = property(key)?.value as Float? ?: 0f

    /**
     * Changes the value of a property, or adds the property after the others.
     */
    fun set(key: Int, type: RivePropertyType, value: Any): RiveProperty {
        val property = property(key)
        if (property != null) {
            property.value = value
            return property
        }
        return RiveProperty(key, type, value).also { properties += it }
    }
}

/**
 * The value types of the properties of the glow animation of the app that the file header does not list.
 */
private val propertyTypes = buildMap {
    // Names.
    listOf(4, 55, 138).forEach { put(it, RivePropertyType.STRING) }
    // Sizes, positions, transforms, gradient positions, vertices, interpolators and key frame values.
    listOf(
        7, 8, 13, 14, 15, 16, 17, 20, 21, 24, 25, 33, 34, 35, 39, 42,
        63, 64, 65, 66, 70, 82, 83, 84, 85, 86, 87
    ).forEach { put(it, RivePropertyType.FLOAT) }
    // Solid colors, gradient stops and color key frames.
    listOf(37, 38, 88).forEach { put(it, RivePropertyType.COLOR) }
    // Ids, flags, enums and booleans.
    listOf(5, 32, 40, 41, 51, 53, 57, 59, 67, 68, 69, 128, 129, 149, 151).forEach {
        put(it, RivePropertyType.UINT)
    }
}

/**
 * A Rive animation file (.riv), read into objects that can be changed and written back.
 *
 * After the header the file is a list of objects. Each object is its type key followed by its
 * properties, each a property key and a value, and a 0 key ends the object. The header lists the
 * value types of properties that a runtime may not know, and the other value types come from the
 * Rive runtime.
 */
internal class RiveFile(bytes: ByteArray) {
    private val header: ByteArray

    val objects = mutableListOf<RiveObject>()

    init {
        if (bytes.size < 4 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIVE") {
            throw PatchException("Not a Rive file")
        }

        // ByteBuffer.position(Int) and clear() are not used, because calls to them compiled
        // with Java 9+ fail on older Android versions, which patch on the device.
        val buffer = ByteBuffer.wrap(bytes, 4, bytes.size - 4).order(ByteOrder.LITTLE_ENDIAN)
        // Major version, minor version and file id.
        repeat(3) { buffer.readVarUint() }

        val headerKeys = mutableListOf<Int>()
        while (true) {
            val key = buffer.readVarUint()
            if (key == 0) break
            headerKeys += key
        }

        // Each type is 2 bits, and the lowest byte of an integer holds 4 of them.
        val headerTypes = mutableMapOf<Int, RivePropertyType>()
        var types = 0
        headerKeys.forEachIndexed { index, key ->
            if (index % 4 == 0) types = buffer.int
            headerTypes[key] = RivePropertyType.entries[(types ushr (index % 4 * 2)) and 3]
        }
        header = bytes.copyOf(buffer.position())

        while (buffer.hasRemaining()) {
            val riveObject = RiveObject(buffer.readVarUint())
            while (true) {
                val key = buffer.readVarUint()
                if (key == 0) break

                val type = headerTypes[key] ?: propertyTypes[key]
                    ?: throw PatchException("Unknown Rive property: $key")
                val value: Any = when (type) {
                    RivePropertyType.UINT -> buffer.readVarUint()
                    RivePropertyType.STRING -> ByteArray(buffer.readVarUint()).also { buffer.get(it) }
                    RivePropertyType.FLOAT -> buffer.float
                    RivePropertyType.COLOR -> buffer.int
                }
                riveObject.properties += RiveProperty(key, type, value)
            }
            objects += riveObject
        }
    }

    fun toByteArray(): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(header)

        val number = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        objects.forEach { riveObject ->
            output.writeVarUint(riveObject.typeKey)
            riveObject.properties.forEach { property ->
                output.writeVarUint(property.key)
                when (property.type) {
                    RivePropertyType.UINT -> output.writeVarUint(property.value as Int)
                    RivePropertyType.STRING -> (property.value as ByteArray).let {
                        output.writeVarUint(it.size)
                        output.write(it)
                    }
                    RivePropertyType.FLOAT -> output.write(number.putFloat(0, property.value as Float).array())
                    RivePropertyType.COLOR -> output.write(number.putInt(0, property.value as Int).array())
                }
            }
            output.writeVarUint(0)
        }

        return output.toByteArray()
    }

    private fun ByteBuffer.readVarUint(): Int {
        var result = 0
        var shift = 0
        while (true) {
            val byte = get().toInt()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun ByteArrayOutputStream.writeVarUint(value: Int) {
        var remaining = value
        while (remaining ushr 7 != 0) {
            write((remaining and 0x7F) or 0x80)
            remaining = remaining ushr 7
        }
        write(remaining)
    }
}
