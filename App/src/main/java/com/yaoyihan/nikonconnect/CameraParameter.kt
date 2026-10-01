package com.yaoyihan.nikonconnect

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

enum class RemoteParameter(val title: String, val codes: List<Int>) {
    SHUTTER("快门", listOf(0xD100, 0x500D)),
    APERTURE("光圈", listOf(0x5007)),
    ISO("ISO", listOf(0x500F, 0xD0B4)),
    COMPENSATION("曝光补偿", listOf(0x5010)),
    WHITE_BALANCE("白平衡", listOf(0x5005)),
    PICTURE_CONTROL("机内滤镜", listOf(0xD200));

    fun display(code: Int, value: Long): String = when (this) {
        SHUTTER -> if (code == 0xD100) when (value) {
            0xffffffffL -> "Bulb"
            0xfffffffdL -> "Time"
            0xfffffffeL -> "X 1/200"
            else -> {
                val numerator = value ushr 16
                val denominator = value and 0xffff
                when {
                    denominator == 1L -> "${numerator}s"
                    numerator > 0 && denominator > 0 -> "$numerator/$denominator"
                    else -> "—"
                }
            }
        } else if (value > 0) {
            val seconds = value / 10000.0
            if (seconds < 1) "1/${(1 / seconds).roundToInt()}" else "${decimal(seconds)}s"
        } else "—"
        APERTURE -> "f/${decimal(value / 100.0)}"
        ISO -> if (value in listOf(0L, 0xffffL, 0xffffffffL)) "自动" else value.toString()
        COMPENSATION -> String.format(Locale.US, "%+.1f EV", value / 1000.0)
        PICTURE_CONTROL -> NikonPictureControls.label(value)
        WHITE_BALANCE -> when (value) {
            1L -> "手动"; 2L -> "自动"; 3L -> "单次自动"; 4L -> "日光"
            5L -> "荧光灯"; 6L -> "白炽灯"; 7L -> "闪光灯"
            0x8010L -> "阴天"; 0x8011L -> "阴影"; 0x8012L -> "色温"
            0x8013L -> "预设手动"; 0x8016L -> "自然光自动"
            else -> "0x${value.toString(16).uppercase()}"
        }
    }

    fun modeAllows(mode: Long?): Boolean = when (this) {
        SHUTTER -> mode == 1L || mode == 4L
        APERTURE -> mode == 1L || mode == 3L
        else -> true
    }

    fun wheelOptions(descriptor: CameraParameterDescriptor): List<Long> {
        val options = descriptor.options()
        if (descriptor.values.isEmpty() || this == WHITE_BALANCE || this == PICTURE_CONTROL) return options
        return options.sortedBy { value ->
            when (this) {
                SHUTTER -> if (descriptor.code == 0xD100) when (value) {
                    0xfffffffeL -> 1.0 / 200
                    0xffffffffL -> Double.MAX_VALUE / 2
                    0xfffffffdL -> Double.MAX_VALUE
                    else -> (value ushr 16).toDouble() / (value and 0xffff).coerceAtLeast(1)
                } else value / 10000.0
                ISO -> if (value in listOf(0L, 0xffffL, 0xffffffffL)) -1.0 else value.toDouble()
                else -> value.toDouble()
            }
        }
    }

    companion object {
        private fun decimal(value: Double) = String.format(Locale.US, "%.1f", value).removeSuffix(".0")
    }
}

data class CameraParameterDescriptor(
    val code: Int,
    val type: Int,
    val writable: Boolean,
    val current: Long,
    val values: List<Long> = emptyList(),
    val minimum: Long? = null,
    val maximum: Long? = null,
    val step: Long? = null,
    val reason: String? = null,
) {
    fun allows(value: Long): Boolean = if (values.isNotEmpty()) value in values else {
        val low = minimum; val high = maximum; val increment = step
        low != null && high != null && increment != null && increment > 0 &&
            value in low..high && (value - low) % increment == 0L
    }

    fun options(): List<Long> {
        if (values.isNotEmpty()) return values
        val low = minimum ?: return emptyList()
        val high = maximum ?: return emptyList()
        val increment = step ?: return emptyList()
        if (increment <= 0 || high < low) return emptyList()
        val count = (high - low) / increment + 1
        if (count !in 1..Int.MAX_VALUE.toLong()) return emptyList()
        // Lazy wheels must not allocate every value in a large camera-declared range.
        return object : AbstractList<Long>() {
            override val size = count.toInt()
            override fun get(index: Int): Long {
                require(index in 0 until size)
                return low + index.toLong() * increment
            }
            override fun indexOf(element: Long): Int =
                if (allows(element)) ((element - low) / increment).toInt() else -1
        }
    }

    fun encode(value: Long): ByteArray {
        require(writable && allows(value)) { "相机不支持此参数值" }
        val buffer = ByteBuffer.allocate(width(type)).order(ByteOrder.LITTLE_ENDIAN)
        when (buffer.capacity()) {
            1 -> buffer.put(value.toByte())
            2 -> buffer.putShort(value.toShort())
            4 -> buffer.putInt(value.toInt())
        }
        return buffer.array()
    }

    companion object {
        private fun width(type: Int): Int = when (type) {
            1, 2 -> 1; 3, 4 -> 2; 5, 6 -> 4
            else -> error("此参数数据类型暂不支持调节")
        }

        fun parse(bytes: ByteArray): CameraParameterDescriptor {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun byte() = buffer.get().toInt() and 0xff
            fun short() = buffer.short.toInt() and 0xffff
            val code = short()
            val type = short()
            width(type)
            val writable = byte() == 1
            fun number(): Long = when (type) {
                1 -> buffer.get().toLong(); 2 -> byte().toLong()
                3 -> buffer.short.toLong(); 4 -> short().toLong()
                5 -> buffer.int.toLong(); 6 -> buffer.int.toLong() and 0xffffffffL
                else -> error("此参数数据类型暂不支持调节")
            }
            number() // Factory default, followed by current value.
            val current = number()
            return when (val form = byte()) {
                0 -> CameraParameterDescriptor(code, type, false, current, reason = "相机未提供可选择的取值")
                1 -> {
                    val low = number(); val high = number(); val increment = number()
                    require(high >= low && increment > 0) { "相机返回了无效的参数范围" }
                    CameraParameterDescriptor(code, type, writable, current, minimum = low, maximum = high, step = increment)
                }
                2 -> {
                    val count = short()
                    require(count <= 2048 && count * width(type) <= buffer.remaining()) { "相机参数列表无效或不完整" }
                    CameraParameterDescriptor(code, type, writable && count > 0, current, List(count) { number() }.distinct())
                }
                else -> error("相机参数描述格式不支持：$form")
            }
        }
    }
}
