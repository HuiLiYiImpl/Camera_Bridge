package com.yaoyihan.nikonconnect.lut

import com.yaoyihan.nikonconnect.CubeLut
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

object LutBinaryCodec {
    private val Magic = byteArrayOf('N'.code.toByte(), 'L'.code.toByte(), 'U'.code.toByte(), 'T'.code.toByte())
    private const val Version = 1

    fun write(lut: CubeLut, output: OutputStream) {
        DataOutputStream(output).use { out ->
            out.write(Magic); out.writeInt(Version); out.writeInt(lut.size)
            lut.domainMin.forEach(out::writeFloat); lut.domainMax.forEach(out::writeFloat)
            out.writeInt(lut.values.size)
            lut.values.forEach(out::writeFloat)
        }
    }

    fun read(name: String, input: InputStream): CubeLut = DataInputStream(input).use { source ->
        require(source.readNBytes(4).contentEquals(Magic)) { "LUT 内部文件头错误" }
        require(source.readInt() == Version) { "不支持的 LUT 内部版本" }
        val size = source.readInt()
        require(size in 2..65) { "不支持的 LUT 尺寸" }
        val min = FloatArray(3) { source.readFloat() }
        val max = FloatArray(3) { source.readFloat() }
        val count = source.readInt()
        require(count == size * size * size * 3) { "LUT 内部数据长度错误" }
        require(count <= 65 * 65 * 65 * 3) { "LUT 数据过大" }
        val values = FloatArray(count) { source.readFloat().also { require(it.isFinite()) { "LUT 含有非法数值" } } }
        CubeLut(name, size, values, min, max)
    }
}
