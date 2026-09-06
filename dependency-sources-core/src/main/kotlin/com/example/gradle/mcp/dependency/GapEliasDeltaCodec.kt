package com.example.gradle.mcp.dependency

object GapEliasDeltaCodec {
    fun encode(positions: IntArray): ByteArray {
        require(isStrictlyIncreasing(positions)) { "positions must be strictly increasing" }
        val writer = BitWriter()
        var prev = -1
        for (position in positions) {
            val gap = (position - prev).toLong()
            writer.writeDelta(gap)
            prev = position
        }
        return writer.finish()
    }

    fun decode(bytes: ByteArray, count: Int): IntArray {
        if (count == 0) return IntArray(0)
        val reader = BitReader(bytes)
        val out = IntArray(count)
        var prev = -1
        for (i in 0 until count) {
            val gap = reader.readDelta()
                ?: throw IllegalArgumentException("truncated Elias-δ stream at index $i")
            val position = prev + gap.toInt()
            out[i] = position
            prev = position
        }
        return out
    }

    private fun isStrictlyIncreasing(positions: IntArray): Boolean {
        if (positions.isEmpty()) return true
        var prev = positions[0]
        for (i in 1 until positions.size) {
            if (positions[i] <= prev) return false
            prev = positions[i]
        }
        return true
    }
}

private class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var bit = 0

    fun writeBit(value: Boolean) {
        if (bit == 0) bytes.add(0)
        if (value) {
            val last = bytes.size - 1
            bytes[last] = ((bytes[last].toInt() and 0xFF) or (1 shl (7 - bit))).toByte()
        }
        bit += 1
        if (bit == 8) bit = 0
    }

    fun writeBits(value: Long, nbits: Int) {
        for (i in nbits - 1 downTo 0) {
            writeBit(((value ushr i) and 1L) == 1L)
        }
    }

    fun writeDelta(n: Long) {
        require(n >= 1)
        val l = floorLog2(n)
        val len = l + 1
        val lenL = floorLog2(len.toLong())
        repeat(lenL) { writeBit(false) }
        writeBits(len.toLong(), lenL + 1)
        if (l > 0) {
            writeBits(n and ((1L shl l) - 1), l)
        }
    }

    fun finish(): ByteArray = bytes.toByteArray()
}

private class BitReader(private val bytes: ByteArray) {
    private var pos = 0

    fun readBit(): Boolean? {
        val byteIndex = pos / 8
        if (byteIndex >= bytes.size) return null
        val bitIndex = pos % 8
        val bit = ((bytes[byteIndex].toInt() ushr (7 - bitIndex)) and 1) == 1
        pos += 1
        return bit
    }

    fun readBits(nbits: Int): Long? {
        var value = 0L
        repeat(nbits) {
            val bit = readBit() ?: return null
            value = (value shl 1) or if (bit) 1L else 0L
        }
        return value
    }

    fun readDelta(): Long? {
        var lenL = 0
        while (true) {
            val bit = readBit() ?: return null
            if (bit) break
            lenL += 1
            if (lenL >= 64) return null
        }
        val rest = if (lenL == 0) 0L else (readBits(lenL) ?: return null)
        val len = (1L shl lenL) or rest
        if (len == 0L || len > 64L) return null
        val l = (len - 1).toInt()
        val low = if (l == 0) 0L else (readBits(l) ?: return null)
        return (1L shl l) or low
    }
}

private fun floorLog2(n: Long): Int {
    require(n >= 1)
    return 63 - n.countLeadingZeroBits()
}