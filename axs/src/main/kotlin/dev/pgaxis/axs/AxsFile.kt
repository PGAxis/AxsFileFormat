package dev.pgaxis.axs

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KProperty1
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KType

class AxsFile(private val filePath: String) {
    private val MAGIC = byteArrayOf(0x41, 0x58, 0x53, 0x1A)
    private val VERSION: Byte = 5 // format v5: dual superblock + COW
    private val fileMutex = Mutex()
    private var LOGGING = false

    companion object {
        private val queues = ConcurrentHashMap<String, WriteQueue>()

        private fun queueFor(filePath: String): WriteQueue {
            val canonical = File(filePath).canonicalPath
            return queues.getOrPut(canonical) { WriteQueue() }
        }
    }

    private val writeQueue = queueFor(filePath)

    init {
        writeQueue.onFlush = { batch -> setBatch(batch) }
    }

    private var isFileOpen: Boolean = false

    fun open() {
        if (!File(filePath).exists()) {
            create()
            isFileOpen = true
            return
        }

        val looksLikeV5 = RandomAccessFile(filePath, "r").use { raf ->
            AxsSuperblock.pickActive(raf, MAGIC)
        }

        if (looksLikeV5 == null) {
            migrateFromLegacyV4()
        }

        RandomAccessFile(filePath, "r").use { raf ->
            val active = AxsSuperblock.pickActive(raf, MAGIC)
                ?: throw AxsFileCorruptException(filePath, "both superblock slots are invalid")
            loadIndex(raf, active.second)
        }

        isFileOpen = true
    }

    private fun migrateFromLegacyV4() {
        val legacyIndex = AxsIndex()
        val looksLikeLegacy = RandomAccessFile(filePath, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (!magic.contentEquals(MAGIC)) return@use false
            val version = raf.readByte()
            if (version.toInt() !in 1..4) return@use false
            val legacyIndexOffset = raf.readLong()
            raf.seek(legacyIndexOffset)
            legacyIndex.readFromBytes(raf)
            true
        }

        if (!looksLikeLegacy) {
            throw AxsFileCorruptException(
                filePath,
                "both superblock slots are invalid, and the file doesn't look like a recognized v4 file either"
            )
        }

        val migratedPath = "$filePath.migrating"
        File(migratedPath).delete()
        try {
            RandomAccessFile(filePath, "r").use { oldRaf ->
                RandomAccessFile(migratedPath, "rw").use { newRaf ->
                    val newIndex = AxsIndex()
                    var cursor = AXS_DATA_START
                    for (node in legacyIndex.all()) {
                        if (node.id == AxsIndex.ROOT_ID) continue
                        if (node.nodeType != NodeType.VALUE) {
                            newIndex.add(node.copy())
                            continue
                        }
                        val bytes = readValueBlockOrNull(oldRaf, node.dataOffset, node.dataSize) ?: continue
                        appendValueBlock(newRaf, cursor, bytes, node.valueType)
                        newIndex.add(node.copy(dataOffset = cursor, dataSize = bytes.size))
                        cursor += AXS_BLOCK_HEADER_SIZE + bytes.size
                    }

                    val indexBytes = serializeIndex(newIndex)
                    newRaf.seek(cursor)
                    newRaf.write(indexBytes)
                    commitSuperblock(
                        newRaf, MAGIC, VERSION, currentSlotIndex = null, currentGeneration = 0L,
                        indexOffset = cursor, indexBytes = indexBytes
                    )
                }
            }

            if (!File(migratedPath).renameTo(File(filePath))) {
                throw AxsFileCorruptException(filePath, "legacy migration finished but couldn't replace the original file")
            }
        } finally {
            File(migratedPath).delete()
        }
    }

    fun close() {
        isFileOpen = false
    }

    fun isOpen(): Boolean = isFileOpen

    fun setLogging(on: Boolean) {
        LOGGING = on
    }

    private fun checkOpen() {
        if (!isFileOpen) throw AxsFileNotOpenException(filePath)
    }

    // ---------- Storage-format helpers ----------

    private fun loadActive(raf: RandomAccessFile): Pair<Int, AxsSuperblock> =
        AxsSuperblock.pickActive(raf, MAGIC)
            ?: throw AxsFileCorruptException(filePath, "both superblock slots are invalid")

    private fun loadIndex(raf: RandomAccessFile, sb: AxsSuperblock): AxsIndex {
        val index = AxsIndex()
        if (sb.indexLength == 0) return index
        raf.seek(sb.indexOffset)
        val bytes = ByteArray(sb.indexLength)
        raf.readFully(bytes)
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        if (crc != sb.indexCrc) {
            throw AxsFileCorruptException(filePath, "index CRC mismatch at generation ${sb.generation}")
        }
        index.readFromBytes(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)))
        return index
    }

    private fun commitIndex(
        raf: RandomAccessFile,
        currentSlot: Int,
        sb: AxsSuperblock,
        index: AxsIndex
    ): Int {
        return commitStructuralChange(
            raf, index, MAGIC, VERSION, currentSlot, sb.generation,
            reuseHintOffset = sb.previousIndexOffset, reuseHintLength = sb.previousIndexLength,
            supersededIndexOffset = sb.indexOffset, supersededIndexLength = sb.indexLength
        )
    }

    private fun eligibleFreeIdsOf(index: AxsIndex): Set<Long> =
        index.freeBlocks().map { it.id }.toHashSet()

    private fun parentIdOf(path: String): Long {
        val segments = path.split(".")
        return if (segments.size > 1) AxsIndex.hashPath(segments.dropLast(1).joinToString(".")) else AxsIndex.ROOT_ID
    }

    private fun writeValueEntry(
        raf: RandomAccessFile,
        index: AxsIndex,
        eligibleFreeIds: Set<Long>,
        path: String,
        dataBytes: ByteArray,
        valueType: ValueType
    ): Boolean {
        ensureParentNodes(index, path)
        val nodeId = AxsIndex.hashPath(path)
        val existingNode = index.find(nodeId)

        if (existingNode != null && existingNode.nodeType == NodeType.VALUE && existingNode.dataSize == dataBytes.size) {
            overwriteValueBlockInPlace(raf, existingNode.dataOffset, dataBytes, valueType)
            existingNode.valueType = valueType
            return false
        }

        if (existingNode != null && existingNode.nodeType == NodeType.VALUE) {
            index.add(
                AxsNode(
                    id = AxsIndex.freeId(existingNode.dataOffset), parentId = AxsIndex.FREE_LIST_ID,
                    nodeType = NodeType.FREE, name = "",
                    dataOffset = existingNode.dataOffset, dataSize = AXS_BLOCK_HEADER_SIZE + existingNode.dataSize
                )
            )
        }

        val required = AXS_BLOCK_HEADER_SIZE + dataBytes.size
        val reusable = index.findBestFitFreeBlock(required, eligibleFreeIds)
        val offset: Long
        if (reusable != null) {
            offset = reusable.dataOffset
            index.remove(reusable.id)

            val leftoverRaw = reusable.dataSize - required
            if (leftoverRaw >= AXS_BLOCK_HEADER_SIZE) {
                val leftoverOffset = offset + required
                index.add(
                    AxsNode(
                        id = AxsIndex.freeId(leftoverOffset), parentId = AxsIndex.FREE_LIST_ID,
                        nodeType = NodeType.FREE, name = "",
                        dataOffset = leftoverOffset, dataSize = leftoverRaw
                    )
                )
            }
        } else {
            offset = raf.length()
        }

        appendValueBlock(raf, offset, dataBytes, valueType)

        val parentId = parentIdOf(path)
        val name = path.split(".").last()
        index.remove(nodeId)
        index.add(
            AxsNode(
                id = nodeId, parentId = parentId, nodeType = NodeType.VALUE, name = name,
                dataOffset = offset, dataSize = dataBytes.size, valueType = valueType
            )
        )
        return true
    }

    private fun writeEntry(raf: RandomAccessFile, index: AxsIndex, eligibleFreeIds: Set<Long>, path: String, value: AxsValue): Boolean {
        ensureParentNodes(index, path)
        val nodeId = AxsIndex.hashPath(path)
        val parentId = parentIdOf(path)
        val name = path.split(".").last()

        return when (value) {
            is AxsObject -> {
                if (index.find(nodeId) == null) {
                    index.add(AxsNode(id = nodeId, parentId = parentId, nodeType = NodeType.OBJECT, name = name))
                    true
                } else false
            }
            is AxsArray -> {
                if (index.find(nodeId) == null) {
                    index.add(AxsNode(id = nodeId, parentId = parentId, nodeType = NodeType.ARRAY, name = name))
                    true
                } else false
            }
            is AxsNull -> writeValueEntry(raf, index, eligibleFreeIds, path, ByteArray(0), ValueType.NULL)
            else -> {
                val (raw, valueType) = primitiveToRaw(value)
                writeValueEntry(raf, index, eligibleFreeIds, path, raw.toByteArray(Charsets.UTF_8), valueType)
            }
        }
    }

    // ---------- Binding ----------
    fun <T : Any> bind(instance: T): AxsBoundObject<T> {
        checkOpen()
        val className = instance::class.simpleName
            ?: throw IllegalArgumentException("Cannot bind anonymous class")

        if (LOGGING) println("[AxsBind] Binding $className")
        val existing = get(className)
        if (LOGGING) println("[AxsBind] get($className) returned: $existing")

        if (existing == null) {
            if (LOGGING) println("[AxsBind] No existing data, writing defaults")
            createObject(className)
            for (prop in instance::class.memberProperties) {
                @Suppress("UNCHECKED_CAST")
                val value = (prop as KProperty1<T, *>).get(instance)
                if (LOGGING) println("[AxsBind] Writing default ${prop.name} = $value")
                set("$className.${prop.name}", value?.toAxsValue() ?: AxsNull)
            }
        } else {
            if (LOGGING) println("[AxsBind] Found existing data, restoring properties")
            val saved = existing as? AxsObject
            saved?.let {
                for (prop in instance::class.memberProperties.filterIsInstance<KMutableProperty1<T, *>>()) {
                    if (LOGGING) println("[AxsBind] Restoring ${prop.name} (type: ${prop.returnType})")
                    val key = prop.name
                    val axsValue = it.children[key] ?: continue

                    if (axsValue is AxsNull) {
                        if (prop.returnType.isMarkedNullable) {
                            @Suppress("UNCHECKED_CAST")
                            (prop as KMutableProperty1<T, Any?>).set(instance, null)
                        }
                        continue
                    }

                    if (LOGGING) println("[AxsBind] raw value: $axsValue")
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
                        List::class -> {
                            val itemType = prop.returnType.arguments.firstOrNull()?.type ?: return@let
                            (axsValue as? AxsArray)?.items?.mapNotNull { item ->
                                try { reconstructValue(item, itemType) }
                                catch (_: Exception) { null }
                            }
                        }
                        else -> {
                            try { reconstructValue(axsValue, prop.returnType) }
                            catch (_: Exception) { continue }
                        }
                    }
                    if (converted != null) {
                        @Suppress("UNCHECKED_CAST")
                        (prop as KMutableProperty1<T, Any>).set(instance, converted)
                    }
                }
            }
        }

        return AxsBoundObject(this, instance, className, writeQueue)
    }

    // ---------- Private helpers (format-independent - unchanged from before) ----------
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
            it?.toAxsValue() ?: throw AxsTypeMismatchException("", "null", "supported type")
        })
        is Enum<*> -> axsValueOf(this.name)
        else -> {
            val props = this::class.memberProperties
            if (props.isEmpty()) throw AxsTypeMismatchException("", this::class.simpleName ?: "unknown", "supported type")
            val children = props.associate { prop ->
                @Suppress("UNCHECKED_CAST")
                val value = (prop as KProperty1<Any, *>).get(this)
                prop.name to (value?.toAxsValue() ?: AxsNull)
            }
            AxsObject(children)
        }
    }

    private fun reconstructValue(child: AxsValue, type: KType): Any? {
        if (child is AxsNull) {
            return if (type.isMarkedNullable) null else throw IllegalArgumentException("Non-nullable type got null")
        }
        return when (type.classifier) {
            String::class -> (child as? AxsString)?.value
            Int::class -> (child as? AxsInt)?.value
            Float::class -> (child as? AxsFloat)?.value
            Double::class -> (child as? AxsDouble)?.value
            Boolean::class -> (child as? AxsBool)?.value
            Long::class -> (child as? AxsLong)?.value
            Short::class -> (child as? AxsShort)?.value
            Char::class -> (child as? AxsChar)?.value
            Byte::class -> (child as? AxsByte)?.value
            List::class -> {
                val itemType = type.arguments.firstOrNull()?.type ?: return null
                (child as? AxsArray)?.items?.mapNotNull { item ->
                    try { reconstructValue(item, itemType) }
                    catch (_: Exception) { null }
                }
            }
            else -> {
                val classifier = type.classifier
                if (classifier is KClass<*>) {
                    if (classifier.java.isEnum) {
                        val strValue = (child as? AxsString)?.value
                        classifier.java.enumConstants?.firstOrNull { (it as Enum<*>).name == strValue }
                    } else {
                        val obj = child as? AxsObject ?: return null
                        val constructor = classifier.primaryConstructor ?: return null
                        val args = constructor.parameters.associateWith { param ->
                            val paramChild = obj.children[param.name]
                            if (paramChild == null || paramChild is AxsNull) {
                                if (param.type.isMarkedNullable) return@associateWith null
                                else return@associateWith null // will use default if available
                            }
                            try { reconstructValue(paramChild, param.type) }
                            catch (_: Exception) { null }
                        }
                        constructor.callBy(args)
                    }
                } else null
            }
        }
    }

    private fun ensureParentNodes(index: AxsIndex, path: String) {
        val segments = path.split(".")
        var currentParentId = AxsIndex.ROOT_ID
        for (i in 0 until segments.size - 1) {
            val segment = segments[i]
            val segPath = segments.subList(0, i + 1).joinToString(".")
            val segId = AxsIndex.hashPath(segPath)
            if (index.find(segId) == null) {
                index.add(
                    AxsNode(
                        id = segId,
                        parentId = currentParentId,
                        nodeType = NodeType.OBJECT,
                        name = segment
                    )
                )
            }
            currentParentId = segId
        }
    }

    private fun primitiveToRaw(value: AxsValue): Pair<String, ValueType> = when (value) {
        is AxsString -> value.value to ValueType.STRING
        is AxsInt -> value.value.toString() to ValueType.INT
        is AxsFloat -> value.value.toString() to ValueType.FLOAT
        is AxsDouble -> value.value.toString() to ValueType.DOUBLE
        is AxsBool -> value.value.toString() to ValueType.BOOL
        is AxsLong -> value.value.toString() to ValueType.LONG
        is AxsShort -> value.value.toString() to ValueType.SHORT
        is AxsChar -> value.value.toString() to ValueType.CHAR
        is AxsByte -> value.value.toString() to ValueType.BYTE
        else -> throw AxsTypeMismatchException(
            "", value::class.simpleName ?: "unknown", "primitive type"
        )
    }

    private fun readNode(
        raf: RandomAccessFile,
        index: AxsIndex,
        node: AxsNode,
        path: String
    ): AxsValue {
        return when (node.nodeType) {
            NodeType.VALUE -> {
                if (node.valueType == ValueType.NULL) return AxsNull
                val dataBytes = readValueBlockOrNull(raf, node.dataOffset, node.dataSize) ?: return AxsNull
                val raw = String(dataBytes, Charsets.UTF_8)
                try {
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
                        ValueType.NULL -> AxsNull
                    }
                } catch (_: Exception) {
                    AxsNull
                }
            }

            NodeType.OBJECT -> {
                val children = index.childrenOf(node.id)
                    .associate { child -> child.name to readNode(raf, index, child, "$path.${child.name}") }
                AxsObject(children)
            }

            NodeType.ARRAY -> {
                val items = index.childrenOf(node.id)
                    .sortedBy { it.name.toIntOrNull() ?: 0 }
                    .map { child -> readNode(raf, index, child, "$path.${child.name}") }
                AxsArray(items)
            }

            NodeType.FREE -> AxsNull
        }
    }

    private fun dumpNode(raf: RandomAccessFile, index: AxsIndex, parentId: Long, dir: File) {
        for (node in index.childrenOf(parentId)) {
            when (node.nodeType) {
                NodeType.OBJECT -> {
                    val subDir = File(dir, node.name)
                    subDir.mkdirs()
                    dumpNode(raf, index, node.id, subDir)
                }

                NodeType.ARRAY -> {
                    val subDir = File(dir, node.name)
                    subDir.mkdirs()
                    File(subDir, "_array").createNewFile()
                    dumpNode(raf, index, node.id, subDir)
                }

                NodeType.VALUE -> {
                    val dataBytes = readValueBlockOrNull(raf, node.dataOffset, node.dataSize)
                    val typeName = node.valueType.name.lowercase()
                    File(dir, "${node.name}.$typeName.txt").writeText(
                        dataBytes?.let { String(it, Charsets.UTF_8) } ?: ""
                    )
                }

                NodeType.FREE -> {}
            }
        }
    }

    private fun createNode(path: String, nodeType: NodeType) {
        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)

                    ensureParentNodes(index, path)
                    val nodeId = AxsIndex.hashPath(path)
                    if (index.find(nodeId) != null) return@withLock

                    index.add(
                        AxsNode(
                            id = nodeId,
                            parentId = parentIdOf(path),
                            nodeType = nodeType,
                            name = path.split(".").last()
                        )
                    )

                    commitIndex(raf, slotIdx, sb, index)
                }
            }
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
                if (parts.size < 3) {
                    errors.add("Malformed filename: ${entry.path}"); continue
                }
                val type = parts[parts.size - 2]
                if (ValueType.entries.none { it.name.lowercase() == type })
                    errors.add("Unknown type '$type' in: ${entry.path}")
                if (isArray && parts[0].toIntOrNull() == null)
                    errors.add("Non-numeric name in array folder: ${entry.path}")
            }
        }
        return errors
    }

    private fun importDir(raf: RandomAccessFile, index: AxsIndex, eligibleFreeIds: Set<Long>, dir: File, parentPath: String, force: Boolean) {
        val isArray = File(dir, "_array").exists()
        for (entry in (dir.listFiles() ?: return).sortedBy { it.name }) {
            if (entry.name == "_array") continue
            val entryPath = if (parentPath.isEmpty()) entry.name else "$parentPath.${entry.name}"

            if (entry.isDirectory) {
                val childIsArray = File(entry, "_array").exists()
                val nodeId = AxsIndex.hashPath(entryPath)
                val parentId = if (parentPath.isEmpty()) AxsIndex.ROOT_ID else AxsIndex.hashPath(parentPath)
                if (index.find(nodeId) == null) {
                    index.add(
                        AxsNode(
                            id = nodeId, parentId = parentId,
                            nodeType = if (childIsArray) NodeType.ARRAY else NodeType.OBJECT,
                            name = entry.name
                        )
                    )
                }
                importDir(raf, index, eligibleFreeIds, entry, entryPath, force)
            } else if (entry.isFile) {
                val parts = entry.name.split(".")
                if (parts.size < 3) continue

                val name = parts.dropLast(2).joinToString(".")
                val typeName = parts[parts.size - 2]
                val valueType = ValueType.entries.find { it.name.lowercase() == typeName } ?: continue

                if (isArray && name.toIntOrNull() == null) continue

                val cleanPath = if (parentPath.isEmpty()) name else "$parentPath.$name"
                val dataBytes = entry.readText().toByteArray(Charsets.UTF_8)
                writeValueEntry(raf, index, eligibleFreeIds, cleanPath, dataBytes, valueType)
            }
        }
    }

    private fun collectValueNodes(index: AxsIndex, node: AxsNode): List<AxsNode> =
        when (node.nodeType) {
            NodeType.VALUE -> listOf(node)
            NodeType.FREE -> emptyList()
            else -> index.childrenOf(node.id).flatMap { collectValueNodes(index, it) }
        }

    // ---------- Public API ----------

    private fun create() {
        RandomAccessFile(filePath, "rw").use { raf ->
            val emptyIndex = AxsIndex()
            val indexBytes = serializeIndex(emptyIndex)
            raf.seek(AXS_DATA_START)
            raf.write(indexBytes)
            commitSuperblock(raf, MAGIC, VERSION, currentSlotIndex = null, currentGeneration = 0L,
                indexOffset = AXS_DATA_START, indexBytes = indexBytes)
        }
    }

    fun debugDumpIndex(): List<String> {
        return runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "r").use { raf ->
                    val (_, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)

                    val result = mutableListOf("=== generation ${sb.generation} ===")
                    val printed = mutableSetOf<Long>()

                    fun readValue(node: AxsNode): String {
                        if (node.dataOffset < 0) return ""
                        val bytes = readValueBlockOrNull(raf, node.dataOffset, node.dataSize)
                            ?: return "<corrupted>"
                        return String(bytes, Charsets.UTF_8)
                    }

                    fun printTree(nodeId: Long, depth: Int) {
                        for (node in index.childrenOf(nodeId)) {
                            if (node.id in printed) continue
                            printed.add(node.id)
                            val indent = "  ".repeat(depth)
                            val value = if (node.nodeType == NodeType.VALUE) " value='${readValue(node)}'" else ""
                            result.add("${indent}id=${node.id} parentId=${node.parentId} type=${node.nodeType} name='${node.name}'$value")
                            printTree(node.id, depth + 1)
                        }
                    }

                    result.add("=== Tree ===")
                    printTree(AxsIndex.ROOT_ID, 0)

                    val orphans = index.all().filter { it.id !in printed && it.id != AxsIndex.ROOT_ID && it.nodeType != NodeType.FREE }
                    if (orphans.isNotEmpty()) {
                        result.add("=== Orphans ===")
                        for (node in orphans) {
                            val value = if (node.nodeType == NodeType.VALUE) " value='${readValue(node)}'" else ""
                            result.add("id=${node.id} parentId=${node.parentId} type=${node.nodeType} name='${node.name}'$value")
                            printTree(node.id, 1)
                        }
                    }

                    val free = index.freeBlocks()
                    if (free.isNotEmpty()) {
                        result.add("=== Free list (${free.size} blocks) ===")
                        for (node in free) result.add("offset=${node.dataOffset} size=${node.dataSize}")
                    }

                    result
                }
            }
        }
    }

    fun set(path: String, value: String, valueType: ValueType = ValueType.STRING) {
        checkOpen()
        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)
                    val eligibleFreeIds = eligibleFreeIdsOf(index)
                    val changed = writeValueEntry(raf, index, eligibleFreeIds, path, value.toByteArray(Charsets.UTF_8), valueType)
                    if (changed) commitIndex(raf, slotIdx, sb, index)
                }
            }
        }
    }

    fun set(path: String, value: AxsValue) {
        checkOpen()
        when (value) {
            is AxsArray, is AxsObject -> setBulk(path, value)
            is AxsNull -> setNull(path)
            else -> {
                val (raw, valueType) = primitiveToRaw(value)
                set(path, raw, valueType)
            }
        }
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

    private fun setNull(path: String) {
        checkOpen()
        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)
                    val eligibleFreeIds = eligibleFreeIdsOf(index)
                    val changed = writeValueEntry(raf, index, eligibleFreeIds, path, ByteArray(0), ValueType.NULL)
                    if (changed) commitIndex(raf, slotIdx, sb, index)
                }
            }
        }
    }

    private fun setBulk(path: String, root: AxsValue) {
        val entries = mutableListOf<Pair<String, AxsValue>>()
        collectEntries(path, root, entries)

        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)
                    val eligibleFreeIds = eligibleFreeIdsOf(index)

                    var changed = false
                    for ((entryPath, value) in entries) {
                        if (writeEntry(raf, index, eligibleFreeIds, entryPath, value)) changed = true
                    }

                    if (changed) commitIndex(raf, slotIdx, sb, index)
                }
            }
        }
    }

    private fun collectEntries(
        path: String,
        value: AxsValue,
        out: MutableList<Pair<String, AxsValue>>
    ) {
        when (value) {
            is AxsObject -> {
                out.add(path to value)
                for ((key, child) in value.children)
                    collectEntries("$path.$key", child, out)
            }

            is AxsArray -> {
                out.add(path to value)
                for ((i, child) in value.items.withIndex())
                    collectEntries("$path.$i", child, out)
            }

            else -> out.add(path to value)
        }
    }

    fun setBatch(values: Map<String, AxsValue>) {
        if (values.isEmpty()) return
        checkOpen()

        val entries = mutableListOf<Pair<String, AxsValue>>()
        for ((path, value) in values) collectEntries(path, value, entries)

        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)
                    val eligibleFreeIds = eligibleFreeIdsOf(index)

                    var changed = false
                    for ((path, value) in entries) {
                        if (writeEntry(raf, index, eligibleFreeIds, path, value)) changed = true
                    }

                    if (changed) commitIndex(raf, slotIdx, sb, index)
                }
            }
        }
    }

    fun setAll(values: Map<String, AxsValue>) {
        checkOpen()
        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val oldIndex = loadIndex(raf, sb)
                    val eligibleFreeIds = eligibleFreeIdsOf(oldIndex)

                    val newIndex = AxsIndex()
                    for (old in oldIndex.freeBlocks()) {
                        newIndex.add(old.copy())
                    }
                    for (old in oldIndex.all()) {
                        if (old.nodeType == NodeType.VALUE) {
                            newIndex.add(
                                AxsNode(
                                    id = AxsIndex.freeId(old.dataOffset), parentId = AxsIndex.FREE_LIST_ID,
                                    nodeType = NodeType.FREE, name = "",
                                    dataOffset = old.dataOffset, dataSize = AXS_BLOCK_HEADER_SIZE + old.dataSize
                                )
                            )
                        }
                    }

                    for ((path, value) in values) writeEntry(raf, newIndex, eligibleFreeIds, path, value)

                    commitIndex(raf, slotIdx, sb, newIndex)
                }
            }
        }
    }

    fun get(path: String): AxsValue? {
        checkOpen()
        return runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "r").use { raf ->
                    val (_, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)
                    val node = index.find(AxsIndex.hashPath(path)) ?: return@withLock null
                    readNode(raf, index, node, path)
                }
            }
        }
    }

    fun createArray(path: String) {
        checkOpen(); createNode(path, NodeType.ARRAY)
    }

    fun createObject(path: String) {
        checkOpen(); createNode(path, NodeType.OBJECT)
    }

    fun dump(outputDir: String) {
        checkOpen()
        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "r").use { raf ->
                    val (_, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)
                    val root = File(outputDir)
                    root.mkdirs()
                    dumpNode(raf, index, AxsIndex.ROOT_ID, root)
                }
            }
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

        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)
                    val eligibleFreeIds = eligibleFreeIdsOf(index)
                    importDir(raf, index, eligibleFreeIds, dir, "", force)
                    commitIndex(raf, slotIdx, sb, index)
                }
            }
        }
    }

    fun delete(path: String, recursive: Boolean = false) {
        checkOpen()
        runBlocking {
            fileMutex.withLock {
                RandomAccessFile(filePath, "rw").use { raf ->
                    val (slotIdx, sb) = loadActive(raf)
                    val index = loadIndex(raf, sb)

                    val nodeId = AxsIndex.hashPath(path)
                    val node = index.find(nodeId) ?: throw AxsKeyNotFoundException(path)

                    if (node.nodeType != NodeType.VALUE) {
                        val children = index.childrenOf(node.id)
                        if (children.isNotEmpty() && !recursive)
                            throw IllegalStateException("$path is non-empty — use recursive = true")
                    }

                    for (valueNode in collectValueNodes(index, node)) {
                        index.add(
                            AxsNode(
                                id = AxsIndex.freeId(valueNode.dataOffset), parentId = AxsIndex.FREE_LIST_ID,
                                nodeType = NodeType.FREE, name = "",
                                dataOffset = valueNode.dataOffset, dataSize = AXS_BLOCK_HEADER_SIZE + valueNode.dataSize
                            )
                        )
                    }
                    index.remove(node.id)

                    commitIndex(raf, slotIdx, sb, index)
                }
            }
        }
    }

    fun defragment() {
        checkOpen()
        runBlocking {
            fileMutex.withLock {
                val tmpPath = "$filePath.tmp"
                try {
                    RandomAccessFile(filePath, "r").use { src ->
                        val (_, sb) = loadActive(src)
                        val liveIndex = loadIndex(src, sb)

                        RandomAccessFile(tmpPath, "rw").use { dst ->
                            val newIndex = AxsIndex()
                            var cursor = AXS_DATA_START

                            for (node in liveIndex.all()) {
                                if (node.id == AxsIndex.ROOT_ID) { newIndex.add(node.copy()); continue }
                                when (node.nodeType) {
                                    NodeType.FREE -> {}
                                    NodeType.VALUE -> {
                                        val bytes = readValueBlockOrNull(src, node.dataOffset, node.dataSize)
                                        if (bytes == null) continue // already-corrupted - drop, don't propagate
                                        appendValueBlock(dst, cursor, bytes, node.valueType)
                                        newIndex.add(node.copy(dataOffset = cursor, dataSize = bytes.size))
                                        cursor += AXS_BLOCK_HEADER_SIZE + bytes.size
                                    }
                                    else -> newIndex.add(node.copy())
                                }
                            }

                            val indexBytes = serializeIndex(newIndex)
                            dst.seek(cursor)
                            dst.write(indexBytes)
                            commitSuperblock(dst, MAGIC, VERSION, currentSlotIndex = null, currentGeneration = 0L,
                                indexOffset = cursor, indexBytes = indexBytes)
                        }
                    }

                    if (!File(tmpPath).renameTo(File(filePath))) {
                        File(tmpPath).delete()
                        throw java.io.IOException("defragment: atomic replace failed for $filePath")
                    }
                } catch (e: Exception) {
                    File(tmpPath).delete()
                    throw e
                }
            }
        }
    }
}