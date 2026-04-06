package dev.pg_axis.axs

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32

object AxsFile {
  private val MAGIC = byteArrayOf(0x41, 0x58, 0x53, 0x1A) // "AXS→"
  private const val VERSION: Byte = 1
  private const val HEADER_SIZE = 16L
  private const val BLOCK_HEADER_SIZE = 10
  private const val DEFAULT_CHUNK_SIZE = 4096

  // ---------- Helper commands ----------
  private fun shiftData(file: RandomAccessFile, fromOffset: Long, byBytes: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
    val fileLength = file.length()
    val bytesToMove = fileLength - fromOffset

    if (byBytes > 0) {
      var remaining = bytesToMove
      var readPos = fileLength - chunkSize
      while (remaining > 0) {
        val actualChunk = minOf(chunkSize.toLong(), remaining).toInt()
        readPos = maxOf(fromOffset, fileLength - remaining)
        val buffer = ByteArray(actualChunk)
        file.seek(readPos)
        file.readFully(buffer)
        file.seek(readPos + byBytes)
        file.write(buffer)
        remaining -= actualChunk
      }
    } else {
      var remaining = bytesToMove
      var readPos = fromOffset
      while (remaining > 0) {
          val actualChunk = minOf(chunkSize.toLong(), remaining).toInt()
          val buffer = ByteArray(actualChunk)
          file.seek(readPos)
          file.readFully(buffer)
          file.seek(readPos + byBytes)
          file.write(buffer)
          readPos += actualChunk
          remaining -= actualChunk
      }
      file.setLength(fileLength + byBytes)
    }
  }

  private fun readNode(file: RandomAccessFile, index: AxsIndex, node: AxsNode, path: String): AxsValue {
    return when (node.nodeType) {
      NodeType.VALUE -> {
        file.seek(node.dataOffset + 10)
        val dataBytes = ByteArray(node.dataSize)
        file.readFully(dataBytes)
        val raw = String(dataBytes, Charsets.UTF_8)
        when (node.valueType) {
          ValueType.STRING -> AxsString(raw)
          ValueType.INT -> AxsInt(raw.toInt())
          ValueType.FLOAT -> AxsFloat(raw.toFloat())
          ValueType.BOOL -> AxsBool(raw.toBoolean())
        }
      }
      NodeType.OBJECT -> {
        val children = index.childrenOf(node.id)
          .associate { child ->
            child.name to readNode(file, index, child, "$path.${child.name}")
          }
        AxsObject(children)
      }
      NodeType.ARRAY -> {
        val items = index.childrenOf(node.id)
          .sortedBy { it.name.toIntOrNull() ?: 0 }
          .map { child -> readNode(file, index, child, "$path.${child.name}") }
        AxsArray(items)
      }
    }
  }

  private fun dumpNode(file: RandomAccessFile, index: AxsIndex, parentId: Long, dir: File) {
    for (node in index.childrenOf(parentId)) {
      when (node.nodeType) {
        NodeType.OBJECT -> {
          val subDir = File(dir, node.name)
          subDir.mkdirs()
          dumpNode(file, index, node.id, subDir)
        }
        NodeType.ARRAY -> {
          val subDir = File(dir, node.name)
          subDir.mkdirs()
          File(subDir, "_array").createNewFile()
          dumpNode(file, index, node.id, subDir)
        }
        NodeType.VALUE -> {
          file.seek(node.dataOffset + 10)
          val dataBytes = ByteArray(node.dataSize)
          file.readFully(dataBytes)
          val typeName = node.valueType.name.lowercase()
          File(dir, "${node.name}.$typeName.txt").writeText(
            String(dataBytes, Charsets.UTF_8)
          )
        }
      }
    }
  }

  private fun createNode(path: String, filePath: String, nodeType: NodeType) {
    val file = RandomAccessFile(filePath, "rw")
    file.use {
      it.skipBytes(4)
      it.readByte()
      val indexOffset = it.readLong()

      val index = AxsIndex()
      index.readFrom(it, indexOffset)

      val segments = path.split(".")
      var currentParentId = AxsIndex.ROOT_ID
      for (i in 0 until segments.size - 1) {
        val segment = segments[i]
        val segmentPath = segments.subList(0, i + 1).joinToString(".")
        val segmentId = AxsIndex.hashPath(segmentPath)
        if (index.find(segmentId) == null) {
          index.add(AxsNode(
            id = segmentId,
            parentId = currentParentId,
            nodeType = NodeType.OBJECT,
            name = segment
          ))
        }
        currentParentId = segmentId
      }

      val nodeId = AxsIndex.hashPath(path)
      if (index.find(nodeId) != null) {
        println("$path already exists")
        return
      }

      index.add(AxsNode(
        id = nodeId,
        parentId = currentParentId,
        nodeType = nodeType,
        name = segments.last()
      ))

      it.seek(indexOffset)
      index.writeTo(it)
      it.seek(5)
      it.writeLong(indexOffset)
    }
    println("Created ${nodeType.name.lowercase()} at $path")
  }

  private fun validateDir(dir: File, isArray: Boolean): List<String> {
    val errors = mutableListOf<String>()
    for (entry in dir.listFiles() ?: return errors) {
      if (entry.name == "_array") continue
      if (entry.isDirectory) {
        val childIsArray = File(entry, "_array").exists()
        errors.addAll(validateDir(entry, childIsArray))
      } else if (entry.isFile) {
        val parts = entry.name.split(".")
        if (parts.size < 3) {
          errors.add("Malformed filename: ${entry.path} (expected name.type.txt)")
          continue
        }
        val type = parts[parts.size - 2]
        if (ValueType.entries.none { it.name.lowercase() == type }) {
          errors.add("Unknown type '$type' in: ${entry.path}")
        }
        if (isArray && parts[0].toIntOrNull() == null) {
          errors.add("Non-numeric name in array folder: ${entry.path}")
        }
      }
    }
    return errors
  }

  private fun importDir(file: RandomAccessFile, index: AxsIndex, dir: File, parentPath: String, force: Boolean) {
    val isArray = File(dir, "_array").exists()

    for (entry in (dir.listFiles() ?: return).sortedBy { it.name }) {
      if (entry.name == "_array") continue

      val entryPath = if (parentPath.isEmpty()) entry.name else "$parentPath.${entry.name}"

      if (entry.isDirectory) {
        val childIsArray = java.io.File(entry, "_array").exists()
        val nodeId = AxsIndex.hashPath(entryPath)
        val parentId = if (parentPath.isEmpty()) AxsIndex.ROOT_ID else AxsIndex.hashPath(parentPath)

        if (index.find(nodeId) == null) {
          index.add(AxsNode(
            id = nodeId,
            parentId = parentId,
            nodeType = if (childIsArray) NodeType.ARRAY else NodeType.OBJECT,
            name = entry.name
          ))
        }
        importDir(file, index, entry, entryPath, force)
      } else if (entry.isFile) {
        val parts = entry.name.split(".")
        if (parts.size < 3) {
          if (force) { println("Skipping malformed: ${entry.path}"); continue }
          else continue
        }

        val name = parts.dropLast(2).joinToString(".")
        val typeName = parts[parts.size - 2]
        val valueType = ValueType.entries.find { it.name.lowercase() == typeName }

        if (valueType == null) {
          if (force) { println("Skipping unknown type: ${entry.path}"); continue }
          else continue
        }

        if (isArray && name.toIntOrNull() == null) {
          if (force) { println("Skipping non-numeric array entry: ${entry.path}"); continue }
          else continue
        }

        val cleanPath = if (parentPath.isEmpty()) name else "$parentPath.$name"
        val rawValue = entry.readText()
        val dataBytes = rawValue.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(dataBytes) }.value.toInt()

        val indexOffset = run {
          file.seek(5)
          file.readLong()
        }

        val dataOffset = indexOffset
        file.seek(indexOffset)
        file.writeInt(dataBytes.size)
        file.writeByte(valueType.value.toInt())
        file.writeByte(0)
        file.writeInt(crc)
        file.write(dataBytes)

        val newIndexOffset = indexOffset + BLOCK_HEADER_SIZE + dataBytes.size

        val parentId = if (parentPath.isEmpty()) AxsIndex.ROOT_ID else AxsIndex.hashPath(parentPath)
        val nodeId = AxsIndex.hashPath(cleanPath)

        index.add(AxsNode(
          id = nodeId,
          parentId = parentId,
          nodeType = NodeType.VALUE,
          name = name,
          dataOffset = dataOffset,
          dataSize = dataBytes.size,
          valueType = valueType
        ))

        file.seek(newIndexOffset)
        index.writeTo(file)
        file.seek(5)
        file.writeLong(newIndexOffset)
      }
    }
  }

  private fun collectValueNodes(index: AxsIndex, node: AxsNode): List<AxsNode> {
    return when (node.nodeType) {
      NodeType.VALUE -> listOf(node)
      else -> index.childrenOf(node.id).flatMap { collectValueNodes(index, it) }
    }
  }

  private fun indexSizeOf(index: AxsIndex): Long {
    var size = 4L // entry count
    for (node in index.all()) {
      val nameBytes = node.name.toByteArray(Charsets.UTF_8)
      size += 8 + 8 + 1 + 2 + nameBytes.size // id + parentId + type + nameLen + name
      if (node.nodeType == NodeType.VALUE) {
        size += 8 + 4 + 1 // dataOffset + dataSize + valueType
      }
    }
    return size
  }

  private fun setAxsValue(path: String, filePath: String, value: AxsValue) {
    when (value) {
      is AxsString -> set(path, filePath, value.value, ValueType.STRING)
      is AxsInt -> set(path, filePath, value.value.toString(), ValueType.INT)
      is AxsFloat -> set(path, filePath, value.value.toString(), ValueType.FLOAT)
      is AxsBool -> set(path, filePath, value.value.toString(), ValueType.BOOL)
      is AxsObject -> {
        createObject(path, filePath)
        for ((key, child) in value.children) {
          setAxsValue("$path.$key", filePath, child)
        }
      }
      is AxsArray -> {
        createArray(path, filePath)
        for ((index, child) in value.items.withIndex()) {
          setAxsValue("$path.$index", filePath, child)
        }
      }
    }
  }

  // ---------- Actual commands ----------
  fun create(path: String) {
    val file = RandomAccessFile(path, "rw")
    file.use {
      it.write(MAGIC)
      it.writeByte(VERSION.toInt())
      it.writeLong(HEADER_SIZE)
      it.write(byteArrayOf(0, 0, 0))
      it.writeInt(0)
    }
    println("Created $path (${File(path).length()} bytes)")
  }

  fun set(path: String, filePath: String, value: String, valueType: ValueType = ValueType.STRING) {
    val file = RandomAccessFile(filePath, "rw")
    file.use {
      val magic = ByteArray(4)
      it.readFully(magic)
      it.readByte() // version
      val indexOffset = it.readLong()

      val index = AxsIndex()
      index.readFrom(it, indexOffset)

      val segments = path.split(".")
      var currentParentId = AxsIndex.ROOT_ID
      for (i in 0 until segments.size - 1) {
        val segment = segments[i]
        val segmentPath = segments.subList(0, i + 1).joinToString(".")
        val segmentId = AxsIndex.hashPath(segmentPath)
        if (index.find(segmentId) == null) {
          index.add(AxsNode(
            id = segmentId,
            parentId = currentParentId,
            nodeType = NodeType.OBJECT,
            name = segment
          ))
        }
        currentParentId = segmentId
      }

      val dataBytes = value.toByteArray(Charsets.UTF_8)
      val crc = CRC32().apply { update(dataBytes) }.value.toInt()
      val finalSegment = segments.last()
      val nodeId = AxsIndex.hashPath(path)
      val existingNode = index.find(nodeId)

      if (existingNode == null) {
        it.seek(indexOffset)
        it.writeInt(dataBytes.size)
        it.writeByte(valueType.value.toInt())
        it.writeByte(0)
        it.writeInt(crc)
        it.write(dataBytes)

        val dataOffset = indexOffset
        val newIndexOffset = indexOffset + BLOCK_HEADER_SIZE + dataBytes.size

        index.add(AxsNode(
          id = nodeId,
          parentId = currentParentId,
          nodeType = NodeType.VALUE,
          name = finalSegment,
          dataOffset = dataOffset,
          dataSize = dataBytes.size,
          valueType = valueType
        ))

        it.seek(newIndexOffset)
        index.writeTo(it)
        it.seek(5)
        it.writeLong(newIndexOffset)
      } else if (dataBytes.size == existingNode.dataSize) {
        it.seek(existingNode.dataOffset + 6) // skip size(4) + type(1) + flags(1)
        it.writeInt(crc)
        it.write(dataBytes)
        it.seek(existingNode.dataOffset + 4)
        it.writeByte(valueType.value.toInt())
        existingNode.valueType = valueType
      } else {
        val oldBlockSize = BLOCK_HEADER_SIZE + existingNode.dataSize
        val newBlockSize = BLOCK_HEADER_SIZE + dataBytes.size
        val diff = (newBlockSize - oldBlockSize).toLong()

        val shiftFrom = existingNode.dataOffset + oldBlockSize
        shiftData(it, shiftFrom, diff)

        it.seek(existingNode.dataOffset)
        it.writeInt(dataBytes.size)
        it.writeByte(valueType.value.toInt())
        it.writeByte(0)
        it.writeInt(crc)
        it.write(dataBytes)

        val allNodes = index.all()
        for (node in allNodes) {
          if (node.nodeType == NodeType.VALUE && node.dataOffset > existingNode.dataOffset) {
            node.dataOffset += diff
          }
        }

        existingNode.dataSize = dataBytes.size
        existingNode.dataOffset = existingNode.dataOffset
        existingNode.valueType = valueType

        val newIndexOffset = indexOffset + diff
        it.seek(newIndexOffset)
        index.writeTo(it)
        it.seek(5)
        it.writeLong(newIndexOffset)
      }
    }
  }

  fun set(path: String, filePath: String, value: AxsValue) {
    val existing = get(path, filePath)
    if (existing != null) {
      val nodeId = AxsIndex.hashPath(path)
      val file = RandomAccessFile(filePath, "r")
      val index = AxsIndex()
      file.use {
        it.skipBytes(4)
        it.readByte()
        val indexOffset = it.readLong()
        index.readFrom(it, indexOffset)
      }
      val node = index.find(nodeId)
      if (node != null && node.nodeType != NodeType.VALUE) {
        delete(path, filePath, recursive = true)
      } else if (node != null) {
        delete(path, filePath)
      }
    }

    setAxsValue(path, filePath, value)
  }

  fun get(path: String, filePath: String): AxsValue? {
    val file = RandomAccessFile(filePath, "r")
    file.use {
      it.skipBytes(4) // magic
      it.readByte() // version
      val indexOffset = it.readLong()

      val index = AxsIndex()
      index.readFrom(it, indexOffset)

      val nodeId = AxsIndex.hashPath(path)
      val node = index.find(nodeId) ?: return null

      return readNode(it, index, node, path)
    }
  }

  fun createArray(path: String, filePath: String) = createNode(path, filePath, NodeType.ARRAY)

  fun createObject(path: String, filePath: String) = createNode(path, filePath, NodeType.OBJECT)

  fun dump(filePath: String, outputDir: String) {
    val file = RandomAccessFile(filePath, "r")
    val root = File(outputDir)
    root.mkdirs()
    file.use {
      it.skipBytes(4)
      it.readByte()
      val indexOffset = it.readLong()

      val index = AxsIndex()
      index.readFrom(it, indexOffset)

      dumpNode(it, index, AxsIndex.ROOT_ID, root)
    }
    println("Dumped to $outputDir")
  }

  fun import(inputDir: String, force: Boolean = false) {
    val dir = File(inputDir)
    if (!dir.exists() || !dir.isDirectory) {
      println("Error: $inputDir is not a valid directory")
      return
    }

    val outputPath = "${inputDir.trimEnd('/')}.axs"
    val outputFile = File(outputPath)
    if (outputFile.exists()) outputFile.delete()

    if (!force) {
      val errors = validateDir(dir, isArray = false)
      if (errors.isNotEmpty()) {
        println("Import failed with ${errors.size} error(s):")
        errors.forEach { println("  - $it") }
        println("Use --force to skip malformed entries")
        return
      }
    }

    create(outputPath)

    val file = RandomAccessFile(outputPath, "rw")
    file.use {
      it.skipBytes(4)
      it.readByte()
      val indexOffset = it.readLong()
      val index = AxsIndex()
      index.readFrom(it, indexOffset)

      importDir(it, index, dir, "", force)

      it.seek(it.length() - 0)
    }
    println("Imported $inputDir -> $outputPath")
  }

  fun delete(path: String, filePath: String, recursive: Boolean = false) {
    val file = RandomAccessFile(filePath, "rw")
    file.use {
      it.skipBytes(4)
      it.readByte()
      val indexOffset = it.readLong()

      val index = AxsIndex()
      index.readFrom(it, indexOffset)

      val nodeId = AxsIndex.hashPath(path)
      val node = index.find(nodeId)

      if (node == null) {
        println("Key not found: $path")
        return
      }

      if (node.nodeType != NodeType.VALUE) {
        val children = index.childrenOf(node.id)
        if (children.isNotEmpty() && !recursive) {
          println("$path is a non-empty ${node.nodeType.name.lowercase()} — use --recursive to delete")
          return
        }
      }

      val valuesToDelete = collectValueNodes(index, node)
        .sortedByDescending { it.dataOffset }

      var totalRemoved = 0L
      for (valueNode in valuesToDelete) {
        val blockSize = (BLOCK_HEADER_SIZE + valueNode.dataSize).toLong()

        shiftData(it, valueNode.dataOffset + blockSize, -blockSize)

        for (other in index.all()) {
          if (other.nodeType == NodeType.VALUE && other.dataOffset > valueNode.dataOffset) {
            other.dataOffset -= blockSize
          }
        }

        totalRemoved += blockSize
      }

      index.remove(node.id)

      val newIndexOffset = indexOffset - totalRemoved
      it.seek(newIndexOffset)
      index.writeTo(it)
      it.seek(5)
      it.writeLong(newIndexOffset)
      it.setLength(newIndexOffset + indexSizeOf(index))
    }
    println("Deleted $path")
  }
}