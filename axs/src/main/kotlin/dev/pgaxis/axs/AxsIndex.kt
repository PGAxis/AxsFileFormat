package dev.pgaxis.axs

import java.io.DataInput
import java.io.DataOutput
import java.util.TreeMap

enum class NodeType(val value: Byte) {
    OBJECT(0), ARRAY(1), VALUE(2), FREE(3);
    companion object { fun from(b: Byte) = entries.first { it.value == b } }
}

enum class ValueType(val value: Byte) {
    STRING(0), INT(1), FLOAT(2), BOOL(3), DOUBLE(4), LONG(5), SHORT(6), CHAR(7), BYTE(8), NULL(9);
    companion object { fun from(b: Byte) = entries.first { it.value == b } }
}

data class AxsNode(
    val id: Long,
    val parentId: Long,
    val nodeType: NodeType,
    val name: String,

    var dataOffset: Long = -1,
    var dataSize: Int = -1,
    var valueType: ValueType = ValueType.STRING
)

class AxsIndex {
    private val nodesById = LinkedHashMap<Long, AxsNode>()
    private val childrenByParent = HashMap<Long, MutableList<Long>>()
    private val freeIdsBySize = TreeMap<Int, MutableList<Long>>()

    companion object {
        const val ROOT_ID = 0L
        const val NO_PARENT = -1L
        const val FREE_LIST_ID = -2L

        fun hashPath(path: String): Long {
            var hash = -3750763034362895579L
            for (byte in path.toByteArray(Charsets.UTF_8)) {
                hash = (hash xor byte.toLong()) * 1099511628211L
            }
            return hash
        }

        fun freeId(offset: Long): Long = hashPath("__free__$offset")
    }

    init {
        addInternal(AxsNode(ROOT_ID, NO_PARENT, NodeType.OBJECT, "root"))
    }

    fun find(id: Long): AxsNode? = nodesById[id]

    fun findByPath(path: String): AxsNode? = find(hashPath(path))

    fun childrenOf(parentId: Long): List<AxsNode> =
        childrenByParent[parentId]?.mapNotNull { nodesById[it] } ?: emptyList()

    fun findBestFitFreeBlock(minSize: Int, eligibleIds: Set<Long>): AxsNode? {
        for ((_, ids) in freeIdsBySize.tailMap(minSize)) {
            val id = ids.firstOrNull { it in eligibleIds } ?: continue
            return nodesById[id]
        }
        return null
    }

    fun freeBlocks(): List<AxsNode> = freeIdsBySize.values.flatten().mapNotNull { nodesById[it] }

    private fun addInternal(node: AxsNode) {
        nodesById[node.id] = node
        childrenByParent.getOrPut(node.parentId) { mutableListOf() }.add(node.id)
        if (node.nodeType == NodeType.FREE) {
            freeIdsBySize.getOrPut(node.dataSize) { mutableListOf() }.add(node.id)
        }
    }

    fun add(node: AxsNode) = addInternal(node)

    fun remove(id: Long): Boolean {
        val node = nodesById[id] ?: return false

        childrenByParent[id]?.toList()?.forEach { remove(it) }
        childrenByParent.remove(id)

        childrenByParent[node.parentId]?.remove(id)
        if (node.nodeType == NodeType.FREE) {
            val bucket = freeIdsBySize[node.dataSize]
            bucket?.remove(id)
            if (bucket != null && bucket.isEmpty()) freeIdsBySize.remove(node.dataSize)
        }
        return nodesById.remove(id) != null
    }

    fun all(): List<AxsNode> = nodesById.values.toList()

    fun readFromBytes(din: DataInput) {
        val count = din.readInt()
        nodesById.clear()
        childrenByParent.clear()
        freeIdsBySize.clear()
        repeat(count) {
            val id = din.readLong()
            val parentId = din.readLong()
            val nodeType = NodeType.from(din.readByte())
            val nameLength = din.readUnsignedShort()
            val nameBytes = ByteArray(nameLength)
            din.readFully(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)

            val node = if (nodeType == NodeType.VALUE || nodeType == NodeType.FREE) {
                val dataOffset = din.readLong()
                val dataSize = din.readInt()
                val valueType = ValueType.from(din.readByte())
                AxsNode(id, parentId, nodeType, name, dataOffset, dataSize, valueType)
            } else {
                AxsNode(id, parentId, nodeType, name)
            }
            addInternal(node)
        }
    }

    fun writeTo(out: DataOutput) {
        out.writeInt(nodesById.size)
        for (node in nodesById.values) {
            out.writeLong(node.id)
            out.writeLong(node.parentId)
            out.writeByte(node.nodeType.value.toInt())
            val nameBytes = node.name.toByteArray(Charsets.UTF_8)
            out.writeShort(nameBytes.size)
            out.write(nameBytes)
            if (node.nodeType == NodeType.VALUE || node.nodeType == NodeType.FREE) {
                out.writeLong(node.dataOffset)
                out.writeInt(node.dataSize)
                out.writeByte(node.valueType.value.toInt())
            }
        }
    }
}