package dev.pgaxis.axs

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import kotlin.reflect.KProperty1
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

class AxsFile(private val filePath: String) {
  private val MAGIC = byteArrayOf(0x41, 0x58, 0x53, 0x1A)
  private val VERSION: Byte = 3
  private val HEADER_SIZE = 16L
  private val BLOCK_HEADER_SIZE = 10
  private val DEFAULT_CHUNK_SIZE = 4096

  private var isFileOpen: Boolean = false

  fun open() {
    if (!File(filePath).exists()) {
      create()
      isFileOpen = true
      return
    }
    
    val file = RandomAccessFile(filePath, "r")
    file.use {
      val magic = ByteArray(4)
      it.readFully(magic)
      if (!magic.contentEquals(MAGIC)) {
        throw AxsFileCorruptException(filePath, "invalid magic bytes")
      }

      val version = it.readByte()
      if (version > VERSION) {
        throw AxsFileCorruptException(filePath, "unsupported version $version")
      }
    }

    isFileOpen = true
  }

  fun close() {
    isFileOpen = false
  }

  fun isOpen(): Boolean = isFileOpen

  private fun checkOpen() {
    if (!isFileOpen) throw AxsFileNotOpenException(filePath)
  }

  // ---------- Binding ----------
  fun <T : Any> bind(instance: T): AxsBoundObject<T> {
    checkOpen()
    val className = instance::class.simpleName
      ?: throw IllegalArgumentException("Cannot bind anonymous class")

    // Create object in file if it doesn't exist
    if (get(className) == null) {
      createObject(className)
      for (prop in instance::class.memberProperties) {
        @Suppress("UNCHECKED_CAST")
        val value = (prop as KProperty1<T, *>).get(instance)
        if (value != null) set("$className.${prop.name}", value.toAxsValue())
      }
    } else {
      // Already exists - load saved values into instance
      val saved = get(className) as? AxsObject
      saved?.let {
        for (prop in instance::class.memberProperties.filterIsInstance<KMutableProperty1<T, *>>()) {
          val key = prop.name
          val axsValue = it.children[key] ?: continue
          val converted: Any? = when (prop.returnType.classifier) {
            String::class -> (axsValue as? AxsString)?.value
            Int::class -> (axsValue as? AxsInt)?.value
            Float::class -> (axsValue as? AxsFloat)?.value
            Double::class -> (axsValue as? AxsDouble)?.value
            Boolean::class -> (axsValue as? AxsBool)?.value
            Long::class -> (axsValue as? AxsLong)?.value
            Short::class -> (axsValue as? AxsShort)?.value
            Char::class -> (axsValue as? AxsChar)?.value
            Byte::class -> (axsValue as? AxsByte)?.value
            List::class -> (axsValue as? AxsArray)?.items?.map { (it as AxsString).value }
            else -> {
              val classifier = prop.returnType.classifier
              if (classifier is KClass<*>) {
                if (classifier.java.isEnum) {
                  val enumValue = (axsValue as? AxsString)?.value
                  classifier.java.enumConstants?.firstOrNull {
                    (it as Enum<*>).name == enumValue
                  }
                } else {
                  val obj = axsValue as? AxsObject ?: return@let null
                  val constructor = classifier.primaryConstructor ?: return@let null
                  val args = constructor.parameters.associateWith { param ->
                    val child = obj.children[param.name] ?: return@let null
                    when (param.type.classifier) {
                      String::class -> (child as? AxsString)?.value
                      Int::class -> (child as? AxsInt)?.value
                      Float::class -> (child as? AxsFloat)?.value
                      Double::class -> (child as? AxsDouble)?.value
                      Boolean::class -> (child as? AxsBool)?.value
                      Long::class -> (child as? AxsLong)?.value
                      Short::class -> (child as? AxsShort)?.value
                      Char::class -> (child as? AxsChar)?.value
                      Byte::class -> (child as? AxsByte)?.value
                      else -> null
                    }
                  }
                  constructor.callBy(args)
                }
              } else null
            }
          }
          if (converted != null) {
            @Suppress("UNCHECKED_CAST")
            (prop as KMutableProperty1<T, Any>).set(instance, converted)
          }
        }
      }
    }

    return AxsBoundObject(this, instance, className)
  }

  // ---------- Private helpers ----------
  private fun Any.toAxsValue(): AxsValue = when (this) {
    is String -> axsValueOf(this)
    is Int -> axsValueOf(this)
    is Float -> axsValueOf(this)
    is Double -> axsValueOf(this)
    is Boolean -> axsValueOf(this)
    is Long -> axsValueOf(this)
    is Short -> axsValueOf(this)
    is Char -> axsValueOf(this)
    is Byte -> axsValueOf(this)
    is List<*> -> AxsArray(this.map { 
      (it as? String)?.let { s -> axsValueOf(s) } ?: throw AxsTypeMismatchException("", it?.let { it::class.simpleName } ?: "null", "supported type")
    })
    is Enum<*> -> axsValueOf(this.name)
    else -> {
      // Try to serialize as object using reflection
      val props = this::class.memberProperties
      if (props.isEmpty()) throw AxsTypeMismatchException("", this::class.simpleName ?: "unknown", "supported type")
      val children = props.associate { prop ->
        @Suppress("UNCHECKED_CAST")
        val value = (prop as KProperty1<Any, *>).get(this)
        prop.name to (value?.toAxsValue() ?: throw AxsTypeMismatchException("", "null", "supported type"))
      }
      AxsObject(children)
    }
  }

  private fun shiftData(file: RandomAccessFile, fromOffset: Long, byBytes: Long, chunkSize: Int = DEFAULT_CHUNK_SIZE) {
    val fileLength = file.length()
    val bytesToMove = fileLength - fromOffset

    if (byBytes > 0) {
      var remaining = bytesToMove
      while (remaining > 0) {
        val actualChunk = minOf(chunkSize.toLong(), remaining).toInt()
        val readPos = maxOf(fromOffset, fileLength - remaining)
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
        file.seek(node.dataOffset + 4)
        val storedType = file.readByte()
        file.readByte()
        val storedCrc = file.readInt()

        val dataBytes = ByteArray(node.dataSize)
        file.readFully(dataBytes)

        val actualCrc = CRC32().apply { update(dataBytes) }.value.toInt()
        if (actualCrc != storedCrc) {
          throw AxsFileCorruptException(filePath, "CRC mismatch at path: $path")
        }

        val raw = String(dataBytes, Charsets.UTF_8)
        when (node.valueType) {
          ValueType.STRING -> AxsString(raw)
          ValueType.INT -> AxsInt(raw.toInt())
          ValueType.FLOAT -> AxsFloat(raw.toFloat())
          ValueType.BOOL -> AxsBool(raw.toBoolean())
          ValueType.DOUBLE -> AxsDouble(raw.toDouble())
          ValueType.LONG -> AxsLong(raw.toLong())
          ValueType.SHORT -> AxsShort(raw.toShort())
          ValueType.CHAR -> AxsChar(raw[0])
          ValueType.BYTE -> AxsByte(raw.toByte())
        }
      }
      NodeType.OBJECT -> {
        val children = index.childrenOf(node.id)
          .associate { child -> child.name to readNode(file, index, child, "$path.${child.name}") }
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
          File(dir, "${node.name}.$typeName.txt").writeText(String(dataBytes, Charsets.UTF_8))
        }
      }
    }
  }

  private fun createNode(path: String, nodeType: NodeType) {
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
            index.add(AxsNode(id = segmentId, parentId = currentParentId, nodeType = NodeType.OBJECT, name = segment))
        }
        currentParentId = segmentId
      }

      val nodeId = AxsIndex.hashPath(path)
      if (index.find(nodeId) != null) return

      index.add(AxsNode(id = nodeId, parentId = currentParentId, nodeType = nodeType, name = segments.last()))

      it.seek(indexOffset)
      index.writeTo(it)
      it.seek(5)
      it.writeLong(indexOffset)
    }
  }

  private fun validateDir(dir: File, isArray: Boolean): List<String> {
    val errors = mutableListOf<String>()
    for (entry in dir.listFiles() ?: return errors) {
      if (entry.name == "_array") continue
      if (entry.isDirectory) {
        errors.addAll(validateDir(entry, File(entry, "_array").exists()))
      } else if (entry.isFile) {
        val parts = entry.name.split(".")
        if (parts.size < 3) { errors.add("Malformed filename: ${entry.path}"); continue }
        val type = parts[parts.size - 2]
        if (ValueType.entries.none { it.name.lowercase() == type })
          errors.add("Unknown type '$type' in: ${entry.path}")
        if (isArray && parts[0].toIntOrNull() == null)
          errors.add("Non-numeric name in array folder: ${entry.path}")
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
        val childIsArray = File(entry, "_array").exists()
        val nodeId = AxsIndex.hashPath(entryPath)
        val parentId = if (parentPath.isEmpty()) AxsIndex.ROOT_ID else AxsIndex.hashPath(parentPath)
        if (index.find(nodeId) == null) {
          index.add(AxsNode(id = nodeId, parentId = parentId,
            nodeType = if (childIsArray) NodeType.ARRAY else NodeType.OBJECT, name = entry.name))
        }
        importDir(file, index, entry, entryPath, force)
      } else if (entry.isFile) {
        val parts = entry.name.split(".")
        if (parts.size < 3) { if (force) continue else continue }

        val name = parts.dropLast(2).joinToString(".")
        val typeName = parts[parts.size - 2]
        val valueType = ValueType.entries.find { it.name.lowercase() == typeName }
          ?: if (force) continue else continue

        if (isArray && name.toIntOrNull() == null) { if (force) continue else continue }

        val cleanPath = if (parentPath.isEmpty()) name else "$parentPath.$name"
        val rawValue = entry.readText()
        val dataBytes = rawValue.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(dataBytes) }.value.toInt()

        file.seek(5)
        val indexOffset = file.readLong()
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

        index.add(AxsNode(id = nodeId, parentId = parentId, nodeType = NodeType.VALUE,
          name = name, dataOffset = dataOffset, dataSize = dataBytes.size, valueType = valueType))

        file.seek(newIndexOffset)
        index.writeTo(file)
        file.seek(5)
        file.writeLong(newIndexOffset)
      }
    }
  }

  private fun collectValueNodes(index: AxsIndex, node: AxsNode): List<AxsNode> =
    when (node.nodeType) {
      NodeType.VALUE -> listOf(node)
      else -> index.childrenOf(node.id).flatMap { collectValueNodes(index, it) }
    }

  private fun indexSizeOf(index: AxsIndex): Long {
    var size = 4L
    for (node in index.all()) {
      val nameBytes = node.name.toByteArray(Charsets.UTF_8)
      size += 8 + 8 + 1 + 2 + nameBytes.size
      if (node.nodeType == NodeType.VALUE) size += 8 + 4 + 1
    }
    return size
  }

  private fun setAxsValue(path: String, value: AxsValue) {
    when (value) {
      is AxsString -> set(path, value.value, ValueType.STRING)
      is AxsInt -> set(path, value.value.toString(), ValueType.INT)
      is AxsFloat -> set(path, value.value.toString(), ValueType.FLOAT)
      is AxsBool -> set(path, value.value.toString(), ValueType.BOOL)
      is AxsDouble -> set(path, value.value.toString(), ValueType.DOUBLE)
      is AxsLong -> set(path, value.value.toString(), ValueType.LONG)
      is AxsShort -> set(path, value.value.toString(), ValueType.SHORT)
      is AxsChar -> set(path, value.value.toString(), ValueType.CHAR)
      is AxsByte -> set(path, value.value.toString(), ValueType.BYTE)
      is AxsObject -> {
        createObject(path)
        for ((key, child) in value.children) setAxsValue("$path.$key", child)
      }
      is AxsArray -> {
        createArray(path)
        for ((index, child) in value.items.withIndex()) setAxsValue("$path.$index", child)
      }
    }
  }

  // ---------- Public API ----------
  private fun create() {
    val file = RandomAccessFile(filePath, "rw")
    file.use {
      it.write(MAGIC)
      it.writeByte(VERSION.toInt())
      it.writeLong(HEADER_SIZE)
      it.write(byteArrayOf(0, 0, 0))
      it.writeInt(0)
    }
  }

  fun set(path: String, value: String, valueType: ValueType = ValueType.STRING) {
    checkOpen()
    val file = RandomAccessFile(filePath, "rw")
    file.use {
      val magic = ByteArray(4)
      it.readFully(magic)
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
          index.add(AxsNode(id = segmentId, parentId = currentParentId, nodeType = NodeType.OBJECT, name = segment))
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

        index.add(AxsNode(id = nodeId, parentId = currentParentId, nodeType = NodeType.VALUE,
          name = finalSegment, dataOffset = indexOffset, dataSize = dataBytes.size, valueType = valueType))

        val newIndexOffset = indexOffset + BLOCK_HEADER_SIZE + dataBytes.size
        it.seek(newIndexOffset)
        index.writeTo(it)
        it.seek(5)
        it.writeLong(newIndexOffset)

      } else if (dataBytes.size == existingNode.dataSize) {
        it.seek(existingNode.dataOffset + 6)
        it.writeInt(crc)
        it.write(dataBytes)
        it.seek(existingNode.dataOffset + 4)
        it.writeByte(valueType.value.toInt())
        existingNode.valueType = valueType

      } else {
        val oldBlockSize = BLOCK_HEADER_SIZE + existingNode.dataSize
        val newBlockSize = BLOCK_HEADER_SIZE + dataBytes.size
        val diff = (newBlockSize - oldBlockSize).toLong()

        shiftData(it, existingNode.dataOffset + oldBlockSize, diff)

        it.seek(existingNode.dataOffset)
        it.writeInt(dataBytes.size)
        it.writeByte(valueType.value.toInt())
        it.writeByte(0)
        it.writeInt(crc)
        it.write(dataBytes)

        for (node in index.all()) {
          if (node.nodeType == NodeType.VALUE && node.dataOffset > existingNode.dataOffset)
                node.dataOffset += diff
        }

        existingNode.dataSize = dataBytes.size
        existingNode.valueType = valueType

        val newIndexOffset = indexOffset + diff
        it.seek(newIndexOffset)
        index.writeTo(it)
        it.seek(5)
        it.writeLong(newIndexOffset)
      }
    }
  }

  fun set(path: String, value: AxsValue) {
    checkOpen()
    val existing = get(path)
    if (existing != null) {
      val file = RandomAccessFile(filePath, "r")
      val index = AxsIndex()
      file.use {
        it.skipBytes(4); it.readByte()
        index.readFrom(it, it.readLong())
      }
      val node = index.find(AxsIndex.hashPath(path))
      if (node != null && node.nodeType != NodeType.VALUE) delete(path, recursive = true)
      else if (node != null) delete(path)
    }
    setAxsValue(path, value)
  }

  fun set(path: String, value: String) = set(path, axsValueOf(value))
  fun set(path: String, value: Int) = set(path, axsValueOf(value))
  fun set(path: String, value: Float) = set(path, axsValueOf(value))
  fun set(path: String, value: Double) = set(path, axsValueOf(value))
  fun set(path: String, value: Boolean) = set(path, axsValueOf(value))
  fun set(path: String, value: Long) = set(path, axsValueOf(value))
  fun set(path: String, value: Short) = set(path, axsValueOf(value))
  fun set(path: String, value: Char) = set(path, axsValueOf(value))
  fun set(path: String, value: Byte) = set(path, axsValueOf(value))

  fun get(path: String): AxsValue? {
    checkOpen()
    val file = RandomAccessFile(filePath, "r")
    file.use {
      it.skipBytes(4); it.readByte()
      val indexOffset = it.readLong()
      val index = AxsIndex()
      index.readFrom(it, indexOffset)
      val node = index.find(AxsIndex.hashPath(path)) ?: return null
      return readNode(it, index, node, path)
    }
  }

  fun createArray(path: String) { checkOpen(); createNode(path, NodeType.ARRAY) }
  fun createObject(path: String) { checkOpen(); createNode(path, NodeType.OBJECT) }

  fun dump(outputDir: String) {
    checkOpen()
    val file = RandomAccessFile(filePath, "r")
    val root = File(outputDir)
    root.mkdirs()
    file.use {
      it.skipBytes(4); it.readByte()
      val indexOffset = it.readLong()
      val index = AxsIndex()
      index.readFrom(it, indexOffset)
      dumpNode(it, index, AxsIndex.ROOT_ID, root)
    }
  }

  fun import(inputDir: String, force: Boolean = false) {
    checkOpen()
    val dir = File(inputDir)
    if (!dir.exists() || !dir.isDirectory) throw IllegalArgumentException("$inputDir is not a valid directory")

    if (!force) {
      val errors = validateDir(dir, isArray = false)
      if (errors.isNotEmpty()) throw IllegalArgumentException("Import failed:\n${errors.joinToString("\n")}")
    }

    val file = RandomAccessFile(filePath, "rw")
    file.use {
      it.skipBytes(4); it.readByte()
      val indexOffset = it.readLong()
      val index = AxsIndex()
      index.readFrom(it, indexOffset)
      importDir(it, index, dir, "", force)
    }
  }

  fun delete(path: String, recursive: Boolean = false) {
    checkOpen()
    val file = RandomAccessFile(filePath, "rw")
    file.use {
      it.skipBytes(4); it.readByte()
      val indexOffset = it.readLong()
      val index = AxsIndex()
      index.readFrom(it, indexOffset)

      val nodeId = AxsIndex.hashPath(path)
      val node = index.find(nodeId) ?: throw AxsKeyNotFoundException(path)

      if (node.nodeType != NodeType.VALUE) {
        val children = index.childrenOf(node.id)
        if (children.isNotEmpty() && !recursive)
          throw IllegalStateException("$path is non-empty — use recursive = true")
      }

      val valuesToDelete = collectValueNodes(index, node).sortedByDescending { it.dataOffset }
      var totalRemoved = 0L

      for (valueNode in valuesToDelete) {
        val blockSize = (BLOCK_HEADER_SIZE + valueNode.dataSize).toLong()
        shiftData(it, valueNode.dataOffset + blockSize, -blockSize)
        for (other in index.all()) {
          if (other.nodeType == NodeType.VALUE && other.dataOffset > valueNode.dataOffset)
            other.dataOffset -= blockSize
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
  }
}