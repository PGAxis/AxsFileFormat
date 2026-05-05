package dev.pgaxis.axs

import java.io.RandomAccessFile

enum class NodeType(val value: Byte) {
  OBJECT(0), ARRAY(1), VALUE(2);
  companion object { fun from(b: Byte) = entries.first { it.value == b } }
}

enum class ValueType(val value: Byte) {
  STRING(0), INT(1), FLOAT(2), BOOL(3), DOUBLE(4), LONG(5), SHORT(6), CHAR(7), BYTE(8);
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

class AxsIndex{
  private val nodes = mutableListOf<AxsNode>()

  companion object {
    const val ROOT_ID = 0L
    const val NO_PARENT = -1L

    fun hashPath(path: String): Long {
      var hash = -3750763034362895579L
      for (byte in path.toByteArray(Charsets.UTF_8)) {
        hash = (hash xor byte.toLong()) * 1099511628211L
      }
      return hash
    }
  }

  init {
    nodes.add(AxsNode(ROOT_ID, NO_PARENT, NodeType.OBJECT, "root"))
  }

  fun find(id: Long): AxsNode? = nodes.find { it.id == id }

  fun findByPath(path: String): AxsNode? = find(hashPath(path))

  fun childrenOf(parentId: Long): List<AxsNode> = nodes.filter { it.parentId == parentId }

  fun add(node: AxsNode) {
    nodes.add(node)
  }

  fun remove(id: Long): Boolean {
    childrenOf(id).forEach { remove(it.id) }
    return nodes.removeIf { it.id == id }
  }

  fun all(): List<AxsNode> = nodes.toList()

  fun readFrom(file: RandomAccessFile, indexOffset: Long) {
        file.seek(indexOffset)
        val count = file.readInt()
        nodes.clear()
        repeat(count) {
            val id = file.readLong()
            val parentId = file.readLong()
            val nodeType = NodeType.from(file.readByte())
            val nameLength = file.readShort().toInt()
            val nameBytes = ByteArray(nameLength)
            file.readFully(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8)

            val node = if (nodeType == NodeType.VALUE) {
                val dataOffset = file.readLong()
                val dataSize = file.readInt()
                val valueType = ValueType.from(file.readByte())
                AxsNode(id, parentId, nodeType, name, dataOffset, dataSize, valueType)
            } else {
                AxsNode(id, parentId, nodeType, name)
            }
            nodes.add(node)
        }
    }

    fun writeTo(file: RandomAccessFile) {
        file.writeInt(nodes.size)
        for (node in nodes) {
            file.writeLong(node.id)
            file.writeLong(node.parentId)
            file.writeByte(node.nodeType.value.toInt())
            val nameBytes = node.name.toByteArray(Charsets.UTF_8)
            file.writeShort(nameBytes.size)
            file.write(nameBytes)
            if (node.nodeType == NodeType.VALUE) {
                file.writeLong(node.dataOffset)
                file.writeInt(node.dataSize)
                file.writeByte(node.valueType.value.toInt())
            }
        }
    }
}