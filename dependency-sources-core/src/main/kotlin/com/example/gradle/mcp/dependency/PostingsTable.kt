package com.example.gradle.mcp.dependency

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

internal data class PostingMeta(
    val count: Int,
    val offset: Int,
    val len: Int,
)

/**
 * Posting storage: in-memory blobs after build, mmap-backed offset table after load.
 *
 * On-disk `postings.bin` (v3, little-endian, no magic — version gate is manifest only):
 * ```
 * u32 name_count
 * [name_count × 12 bytes]  # per name id: u32 count, u32 data_offset, u32 blob_len
 * [concatenated posting blobs]
 * ```
 * `data_offset` is relative to the start of the blob region (`data_base = 4 + name_count * 12`).
 */
internal sealed class PostingsTable {
    abstract val size: Int

    abstract fun postingAt(nameId: Int): PostingSlice?

    internal data class InMemory(
        private val entries: List<Pair<ByteArray, Int>>,
    ) : PostingsTable() {
        override val size: Int = entries.size

        override fun postingAt(nameId: Int): PostingSlice? {
            val (blob, count) = entries.getOrNull(nameId) ?: return null
            return PostingSlice(bytes = blob, count = count)
        }
    }

    internal data class Mmap(
        private val buffer: ByteBuffer,
        private val meta: List<PostingMeta>,
        private val dataBase: Int,
    ) : PostingsTable() {
        override val size: Int = meta.size

        override fun postingAt(nameId: Int): PostingSlice? {
            val entry = meta.getOrNull(nameId) ?: return null
            val start = dataBase + entry.offset
            val end = start + entry.len
            require(start >= dataBase && end <= buffer.limit()) {
                "corrupt postings: name id $nameId blob out of range"
            }
            return PostingSlice(buffer = buffer, offset = start, length = entry.len, count = entry.count)
        }
    }

    companion object {
        const val POSTING_ENTRY_SIZE: Int = 12
        private const val NAME_COUNT_SIZE = 4

        fun packInMemory(entries: List<Pair<ByteArray, Int>>): ByteArray {
            val nameCount = entries.size
            val dataBase = NAME_COUNT_SIZE + nameCount * POSTING_ENTRY_SIZE
            val blobBytes = entries.sumOf { it.first.size }
            val out = ByteArray(dataBase + blobBytes)
            val table = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

            table.putInt(nameCount)

            var dataOffset = 0
            var tablePos = NAME_COUNT_SIZE
            for ((blob, count) in entries) {
                table.position(tablePos)
                table.putInt(count)
                table.putInt(dataOffset)
                table.putInt(blob.size)
                tablePos += POSTING_ENTRY_SIZE
                blob.copyInto(out, destinationOffset = dataBase + dataOffset)
                dataOffset += blob.size
            }
            return out
        }

        fun writePacked(directory: File, entries: List<Pair<ByteArray, Int>>) {
            File(directory, NameLocateIndex.POSTINGS_NAME).writeBytes(packInMemory(entries))
        }

        fun mmap(file: File): PostingsTable {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                require(length >= NAME_COUNT_SIZE) { "truncated postings" }
                val buffer =
                    raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, length).order(ByteOrder.LITTLE_ENDIAN)
                return parseMmap(buffer)
            }
        }

        private fun parseMmap(buffer: ByteBuffer): PostingsTable {
            val duplicate = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            duplicate.position(0)
            require(duplicate.remaining() >= NAME_COUNT_SIZE) { "truncated postings" }
            val nameCount = duplicate.getInt()
            require(nameCount >= 0) { "posting entry count must be non-negative" }
            val dataBase = NAME_COUNT_SIZE + nameCount * POSTING_ENTRY_SIZE
            require(duplicate.limit() >= dataBase) { "truncated postings offset table" }

            val maxEntriesByFile =
                ((duplicate.limit() - NAME_COUNT_SIZE).toLong() / POSTING_ENTRY_SIZE).coerceAtLeast(0L)
            require(nameCount.toLong() <= maxEntriesByFile) {
                "posting entry count $nameCount exceeds file capacity (max $maxEntriesByFile)"
            }

            val meta = ArrayList<PostingMeta>(nameCount)
            duplicate.position(NAME_COUNT_SIZE)
            repeat(nameCount) { id ->
                if (duplicate.remaining() < POSTING_ENTRY_SIZE) {
                    throw IllegalArgumentException("truncated posting header")
                }
                val count = duplicate.getInt()
                val offset = duplicate.getInt()
                val len = duplicate.getInt()
                require(count >= 0) { "occurrence count must be non-negative" }
                require(len >= 0) { "posting blob size must be non-negative" }
                require(count > 0 || len == 0) {
                    "empty occurrence count cannot have a non-empty posting blob"
                }
                val start = dataBase + offset
                val end = start + len
                require(start >= dataBase && end <= duplicate.limit()) {
                    "corrupt postings: name id $id blob out of range"
                }
                meta.add(PostingMeta(count = count, offset = offset, len = len))
            }
            return Mmap(buffer = buffer, meta = meta, dataBase = dataBase)
        }
    }
}

internal data class PostingSlice(
    val bytes: ByteArray? = null,
    val buffer: ByteBuffer? = null,
    val offset: Int = 0,
    val length: Int = bytes?.size ?: 0,
    val count: Int,
) {
    fun forEachOccurrence(
        docCount: Int,
        limit: Int?,
        action: (docId: Int, line: Int, column: Int) -> Boolean,
    ) {
        when {
            bytes != null -> GapEliasDeltaCodec.forEachOccurrence(bytes, count, docCount, limit, action)
            buffer != null ->
                GapEliasDeltaCodec.forEachOccurrence(buffer, offset, length, count, docCount, limit, action)
            else -> error("posting slice has no backing bytes")
        }
    }
}
