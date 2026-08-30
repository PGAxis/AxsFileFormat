package dev.pgaxis.axs

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

// ---------- Layout constants ----------

const val AXS_SLOT_SIZE = 40L
const val AXS_SLOT_A_OFFSET = 0L
const val AXS_SLOT_B_OFFSET = AXS_SLOT_SIZE
const val AXS_DATA_START = AXS_SLOT_SIZE * 2

const val AXS_BLOCK_HEADER_SIZE = 10

const val AXS_FLAG_CLEAN: Byte = 0
const val AXS_FLAG_WRITING: Byte = 1

// ---------- Superblock ----------

data class AxsSuperblock(
    val generation: Long,
    val indexOffset: Long,
    val indexLength: Int,
    val indexCrc: Int
) {
    fun serialize(magic: ByteArray, version: Byte): ByteArray {
        val body = ByteArrayOutputStream(AXS_SLOT_SIZE.toInt())
        val dos = DataOutputStream(body)
        dos.write(magic)
        dos.writeByte(version.toInt())
        dos.write(byteArrayOf(0, 0, 0))
        dos.writeLong(generation)
        dos.writeLong(indexOffset)
        dos.writeInt(indexLength)
        dos.writeInt(indexCrc)
        val bodyBytes = body.toByteArray()
        val headerCrc = CRC32().apply { update(bodyBytes) }.value.toInt()

        val out = ByteArrayOutputStream(AXS_SLOT_SIZE.toInt())
        out.write(bodyBytes)
        DataOutputStream(out).writeInt(headerCrc)
        return out.toByteArray().copyOf(AXS_SLOT_SIZE.toInt())
    }

    companion object {
        private const val BODY_LENGTH = 4 + 1 + 3 + 8 + 8 + 4 + 4

        fun readSlot(raf: RandomAccessFile, slotOffset: Long, expectedMagic: ByteArray): AxsSuperblock? {
            return try {
                raf.seek(slotOffset)
                val bytes = ByteArray(AXS_SLOT_SIZE.toInt())
                raf.readFully(bytes)

                if (!bytes.copyOfRange(0, 4).contentEquals(expectedMagic)) return null

                val din = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
                din.skipBytes(4) // magic
                din.readByte()   // version
                din.skipBytes(3) // reserved
                val generation = din.readLong()
                val indexOffset = din.readLong()
                val indexLength = din.readInt()
                val indexCrc = din.readInt()
                val storedHeaderCrc = din.readInt()

                val actualHeaderCrc = CRC32().apply { update(bytes, 0, BODY_LENGTH) }.value.toInt()
                if (actualHeaderCrc != storedHeaderCrc) return null

                AxsSuperblock(generation, indexOffset, indexLength, indexCrc)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Reads both slots, returns (slotIndex, superblock) for the valid one with
         * the higher generation. Null only if BOTH slots are torn - equivalent to
         * today's "file is garbage", but now needs two independent torn writes
         * instead of one, since a commit only ever touches one slot at a time.
         */
        fun pickActive(raf: RandomAccessFile, expectedMagic: ByteArray): Pair<Int, AxsSuperblock>? {
            val a = readSlot(raf, AXS_SLOT_A_OFFSET, expectedMagic)
            val b = readSlot(raf, AXS_SLOT_B_OFFSET, expectedMagic)
            return when {
                a != null && b != null -> if (a.generation >= b.generation) 0 to a else 1 to b
                a != null -> 0 to a
                b != null -> 1 to b
                else -> null
            }
        }
    }
}

/**
 * Writes a fresh superblock to whichever slot is NOT currentSlotIndex, fsyncing
 * both before (so the data it points at is durable first) and after (so the
 * pointer itself is durable). Returns the slot index that is now active.
 */
fun commitSuperblock(
    raf: RandomAccessFile,
    magic: ByteArray,
    version: Byte,
    currentSlotIndex: Int?,
    currentGeneration: Long,
    indexOffset: Long,
    indexBytes: ByteArray
): Int {
    val indexCrc = CRC32().apply { update(indexBytes) }.value.toInt()
    val sb = AxsSuperblock(
        generation = currentGeneration + 1,
        indexOffset = indexOffset,
        indexLength = indexBytes.size,
        indexCrc = indexCrc
    )

    val targetSlot = if (currentSlotIndex == 0) 1 else 0
    val targetOffset = if (targetSlot == 0) AXS_SLOT_A_OFFSET else AXS_SLOT_B_OFFSET

    raf.fd.sync()

    raf.seek(targetOffset)
    raf.write(sb.serialize(magic, version))
    raf.fd.sync()

    return targetSlot
}

// ---------- Copy-on-write value blocks ----------

/**
 * Writes one value block at `offset` (a free-listed slot, or end-of-file for a
 * brand-new block). Never overwrites bytes outside (offset, offset+blockSize) -
 * safe to interrupt at any point, since nothing reachable from the currently
 * active superblock points here yet.
 */
fun appendValueBlock(
    raf: RandomAccessFile,
    offset: Long,
    dataBytes: ByteArray,
    valueType: ValueType
) {
    val crc = CRC32().apply { update(dataBytes) }.value.toInt()
    raf.seek(offset)
    raf.writeInt(dataBytes.size)
    raf.writeByte(valueType.value.toInt())
    raf.writeByte(AXS_FLAG_CLEAN.toInt())
    raf.writeInt(crc)
    raf.write(dataBytes)
}

/** Serializes an AxsIndex to bytes so its length/CRC can be computed before committing. */
fun serializeIndex(index: AxsIndex): ByteArray {
    val out = ByteArrayOutputStream()
    val dos = DataOutputStream(out)
    index.writeTo(dos)
    dos.flush()
    return out.toByteArray()
}

/**
 * Appends the given index and flips the superblock to point at it - the only
 * in-place write in a structural commit. Caller has already appended/reused
 * whatever value blocks the new `index` references; unrelated nodes keep their
 * existing offsets untouched.
 */
fun commitStructuralChange(
    raf: RandomAccessFile,
    index: AxsIndex,
    magic: ByteArray,
    version: Byte,
    currentSlotIndex: Int?,
    currentGeneration: Long
): Int {
    val indexBytes = serializeIndex(index)
    val indexOffset = raf.length()
    raf.seek(indexOffset)
    raf.write(indexBytes)

    return commitSuperblock(
        raf, magic, version, currentSlotIndex, currentGeneration, indexOffset, indexBytes
    )
}

// ---------- In-place fast path: same-size overwrite ----------

/**
 * Overwrites an existing value block IN PLACE. Only valid when the new payload
 * is exactly the same size as what's already there.
 */
fun overwriteValueBlockInPlace(
    raf: RandomAccessFile,
    dataOffset: Long,
    dataBytes: ByteArray,
    valueType: ValueType
) {
    raf.seek(dataOffset + 4) // past len (unchanged)
    raf.writeByte(valueType.value.toInt())
    raf.writeByte(AXS_FLAG_WRITING.toInt())

    raf.seek(dataOffset + 6)
    val crc = CRC32().apply { update(dataBytes) }.value.toInt()
    raf.writeInt(crc)
    raf.write(dataBytes)
    raf.fd.sync()

    raf.seek(dataOffset + 5)
    raf.writeByte(AXS_FLAG_CLEAN.toInt())
    raf.fd.sync()
}

/**
 * Reads a value block, returning null (recoverable-missing -> caller should use
 * the default) instead of throwing if it's mid-write or its CRC fails.
 */
fun readValueBlockOrNull(raf: RandomAccessFile, dataOffset: Long, dataSize: Int): ByteArray? {
    return try {
        raf.seek(dataOffset + 4)
        raf.readByte() // type - caller already knows it from the index
        val flags = raf.readByte()
        val storedCrc = raf.readInt()
        if (flags == AXS_FLAG_WRITING) return null

        val dataBytes = ByteArray(dataSize)
        raf.readFully(dataBytes)
        val actualCrc = CRC32().apply { update(dataBytes) }.value.toInt()
        if (actualCrc != storedCrc) return null

        dataBytes
    } catch (_: Exception) {
        null
    }
}
