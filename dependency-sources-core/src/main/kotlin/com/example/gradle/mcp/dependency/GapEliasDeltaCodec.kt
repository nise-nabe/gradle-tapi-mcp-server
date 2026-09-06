package com.example.gradle.mcp.dependency

import java.nio.ByteBuffer

/** One hit inside a name's posting list (sorted by docId, line, column). */
internal data class OccPos(
    val docId: Int,
    val line: Int,
    val column: Int,
)

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
        val reader = BitReader.fromBytes(bytes)
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

    /**
     * Encode occurrence payloads for one posting list.
     * Layout per hit: 1-bit same-doc flag; on doc change, Elias-δ docId gap
     * (first gap is docId+1); then Elias-δ (line+1) and (column+1).
     */
    internal fun encodeOccurrences(occs: List<OccPos>): ByteArray {
        if (occs.isEmpty()) return ByteArray(0)
        val writer = BitWriter()
        var prevDoc = 0
        var prevLine = -1
        var prevColumn = -1
        for ((index, occ) in occs.withIndex()) {
            require(occ.docId >= 0 && occ.line >= 0 && occ.column >= 0) {
                "occurrence fields must be non-negative"
            }
            if (index == 0 || occ.docId != prevDoc) {
                require(index == 0 || occ.docId > prevDoc) {
                    "occurrences must be sorted by ascending docId"
                }
                writer.writeBit(false) // doc change
                val gap =
                    if (index == 0) {
                        occ.docId.toLong() + 1L
                    } else {
                        (occ.docId - prevDoc).toLong()
                    }
                writer.writeDelta(gap)
                prevDoc = occ.docId
                prevLine = -1
                prevColumn = -1
            } else {
                writer.writeBit(true) // same doc
                require(
                    occ.line > prevLine || (occ.line == prevLine && occ.column >= prevColumn),
                ) {
                    "occurrences within a document must be sorted by line, then column"
                }
            }
            writer.writeDelta(occ.line.toLong() + 1L)
            writer.writeDelta(occ.column.toLong() + 1L)
            prevLine = occ.line
            prevColumn = occ.column
        }
        return writer.finish()
    }

    internal fun decodeOccurrences(bytes: ByteArray, count: Int, limit: Int? = null): List<OccPos> {
        require(count >= 0) { "occurrence count must be non-negative" }
        if (count == 0) return emptyList()
        val out = ArrayList<OccPos>()
        forEachOccurrence(bytes, count, docCount = Int.MAX_VALUE, limit = limit) { docId, line, column ->
            out.add(OccPos(docId = docId, line = line, column = column))
            true
        }
        return out
    }

    /**
     * Walk a posting payload without allocating [OccPos] instances.
     * Used at index load to validate docId ranges with lower GC pressure.
     */
    internal fun validateOccurrences(bytes: ByteArray, count: Int, docCount: Int) {
        require(docCount >= 0) { "docCount must be non-negative" }
        forEachOccurrence(bytes, count, docCount, limit = null) { docId, _, _ ->
            require(docId in 0 until docCount) {
                "occurrence docId $docId out of range for $docCount documents"
            }
            true
        }
    }

    internal fun forEachOccurrence(
        bytes: ByteArray,
        count: Int,
        docCount: Int,
        limit: Int?,
        action: (docId: Int, line: Int, column: Int) -> Boolean,
    ) {
        forEachOccurrence(BitReader.fromBytes(bytes), count, docCount, limit, action)
    }

    internal fun forEachOccurrence(
        buffer: ByteBuffer,
        offset: Int,
        length: Int,
        count: Int,
        docCount: Int,
        limit: Int?,
        action: (docId: Int, line: Int, column: Int) -> Boolean,
    ) {
        val slice = buffer.duplicate().order(buffer.order())
        slice.position(offset)
        slice.limit(offset + length)
        forEachOccurrence(BitReader.fromBuffer(slice), count, docCount, limit, action)
    }

    /**
     * Stream occurrences. [limit] null = unlimited; 0 = validate first entry only.
     * [action] should return false to stop early.
     */
    private fun forEachOccurrence(
        reader: BitReader,
        count: Int,
        docCount: Int,
        limit: Int?,
        action: (docId: Int, line: Int, column: Int) -> Boolean,
    ) {
        require(count >= 0) { "occurrence count must be non-negative" }
        if (count == 0) return
        val maxBits = reader.remainingBits()
        require(count.toLong() <= maxBits) {
            "occurrence count $count exceeds bitstream capacity ($maxBits bits)"
        }
        if (limit == 0) {
            decodeOccurrence(reader, index = 0, docIdHolder = IntArray(1))
            return
        }
        val decodeCount =
            when (limit) {
                null -> count
                else -> minOf(limit, count)
            }
        val docIdHolder = IntArray(1)
        for (i in 0 until decodeCount) {
            val (docId, line, column) = decodeOccurrence(reader, i, docIdHolder)
            if (docCount != Int.MAX_VALUE) {
                require(docId in 0 until docCount) {
                    "occurrence docId $docId out of range for $docCount documents"
                }
            }
            if (!action(docId, line, column)) return
        }
    }

    private fun decodeOccurrence(reader: BitReader, index: Int, docIdHolder: IntArray): OccPos {
        val sameDoc = reader.readBit()
            ?: throw IllegalArgumentException("truncated occurrence posting at index $index")
        if (index == 0 && sameDoc) {
            throw IllegalArgumentException("invalid occurrence posting: first entry cannot set same-doc")
        }
        if (!sameDoc) {
            val gap = reader.readDelta()
                ?: throw IllegalArgumentException("truncated docId in occurrence posting")
            docIdHolder[0] =
                if (index == 0) {
                    require(gap >= 1L && gap - 1L <= Int.MAX_VALUE.toLong()) {
                        "first docId gap does not fit in Int"
                    }
                    (gap - 1L).toInt()
                } else {
                    require(gap <= Int.MAX_VALUE.toLong()) { "docId gap does not fit in Int" }
                    val next = docIdHolder[0].toLong() + gap
                    require(next <= Int.MAX_VALUE.toLong()) { "docId overflow in occurrence posting" }
                    next.toInt()
                }
        }
        val lineRaw = reader.readDelta()
            ?: throw IllegalArgumentException("truncated line in occurrence posting")
        require(lineRaw >= 1L && lineRaw - 1L <= Int.MAX_VALUE.toLong()) {
            "invalid line value in occurrence posting"
        }
        val line = (lineRaw - 1L).toInt()
        val colRaw = reader.readDelta()
            ?: throw IllegalArgumentException("truncated column in occurrence posting")
        require(colRaw >= 1L && colRaw - 1L <= Int.MAX_VALUE.toLong()) {
            "invalid column value in occurrence posting"
        }
        val column = (colRaw - 1L).toInt()
        return OccPos(docId = docIdHolder[0], line = line, column = column)
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

private class BitReader private constructor(
    private val bytes: ByteArray?,
    private val buffer: ByteBuffer?,
    private val bufferBase: Int,
    private val bitLimit: Int,
) {
    private var pos = 0

    companion object {
        fun fromBytes(bytes: ByteArray): BitReader =
            BitReader(bytes = bytes, buffer = null, bufferBase = 0, bitLimit = bytes.size * 8)

        fun fromBuffer(buffer: ByteBuffer): BitReader =
            BitReader(
                bytes = null,
                buffer = buffer,
                bufferBase = buffer.position(),
                bitLimit = buffer.remaining() * 8,
            )
    }

    fun position(): Int = pos

    fun remainingBits(): Long = (bitLimit - pos).toLong().coerceAtLeast(0L)

    fun readBit(): Boolean? {
        if (pos >= bitLimit) return null
        val byteIndex = pos / 8
        val bitIndex = pos % 8
        val byteValue =
            if (bytes != null) {
                bytes[byteIndex].toInt() and 0xFF
            } else {
                val buf = buffer!!
                buf.get(bufferBase + byteIndex).toInt() and 0xFF
            }
        val bit = ((byteValue ushr (7 - bitIndex)) and 1) == 1
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
