package dev.pgaxis.axs.tests

import dev.pgaxis.axs.*
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random
import kotlin.system.exitProcess

// ---------- tiny result tracker ----------

private data class Result(val name: String, val passed: Boolean, val detail: String)
private val results = mutableListOf<Result>()

private fun report(name: String, passed: Boolean, detail: String = "") {
    val status = if (passed) "PASS" else "FAIL"
    println("[$status] $name" + if (detail.isNotBlank()) "\n       $detail" else "")
    results.add(Result(name, passed, detail))
}

private fun freshPath(name: String): String {
    val f = File.createTempFile("axstest_$name", ".axs")
    f.delete()
    f.deleteOnExit()
    return f.absolutePath
}

// ---------- entry point ----------

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--worker") {
        runWorker(args.drop(1))
        return
    }

    val which = args.getOrNull(0) ?: "all"
    when (which) {
        "simple" -> testSimpleWriteRead()
        "long" -> testLongWrite()
        "long-kill" -> testLongWriteKill()
        "defrag" -> testDefragment()
        "defrag-kill" -> testDefragmentKill()
        "corrupt" -> testCorruptedRead()
        "batching" -> testWriteQueueBatching()
        "all" -> {
            testSimpleWriteRead()
            testLongWrite()
            testLongWriteKill()
            testDefragment()
            testDefragmentKill()
            testCorruptedRead()
            testWriteQueueBatching()
        }
        else -> {
            println("Unknown test '$which'. Options: simple, long, long-kill, defrag, defrag-kill, corrupt, batching, migration, all")
            return
        }
    }

    println()
    val passed = results.count { it.passed }
    println("=== $passed/${results.size} tests passed ===")
    if (passed != results.size) exitProcess(1)
}

private fun runWorker(args: List<String>) {
    when (args[0]) {
        "long-write" -> {
            val axs = AxsFile(args[1])
            axs.open()
            var i = 0
            while (true) {
                axs.set("worker.$i", "w-value-$i-${"payload".repeat(3)}")
                i++
            }
        }
        "defragment" -> {
            val axs = AxsFile(args[1])
            axs.open()
            axs.defragment()
        }
    }
}

private fun spawnWorker(vararg workerArgs: String): Process {
    val javaBin = System.getProperty("java.home") + "/bin/java"
    val classpath = System.getProperty("java.class.path")
    val cmd = mutableListOf(javaBin, "-cp", classpath, "dev.pgaxis.axs.tests.AxsTestHarnessKt", "--worker")
    cmd.addAll(workerArgs)
    return ProcessBuilder(cmd)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}

// ---------- 1. simple write, simple read ----------

private fun testSimpleWriteRead() {
    val path = freshPath("simple")
    try {
        val a = AxsFile(path)
        a.open()
        a.set("greeting", "hello world")
        a.set("count", 42)
        a.close()

        val b = AxsFile(path)
        b.open()
        val greeting = (b.get("greeting") as? AxsString)?.value
        val count = (b.get("count") as? AxsInt)?.value
        b.close()

        val ok = greeting == "hello world" && count == 42
        report("simple write/read", ok, "greeting='$greeting' count=$count")
    } catch (e: Exception) {
        report("simple write/read", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}

// ---------- 2. long write ----------

private fun testLongWrite() {
    val path = freshPath("long")
    val n = 3000
    try {
        val a = AxsFile(path)
        a.open()
        for (i in 0 until n) a.set("item.$i", "value-$i")
        val big = "X".repeat(200_000)
        a.set("big", big)
        a.close()

        val b = AxsFile(path)
        b.open()
        var mismatches = 0
        for (i in 0 until n) {
            if ((b.get("item.$i") as? AxsString)?.value != "value-$i") mismatches++
        }
        val bigOk = (b.get("big") as? AxsString)?.value == big
        b.close()

        report(
            "long write ($n keys + 200KB value)",
            mismatches == 0 && bigOk,
            "mismatches=$mismatches bigOk=$bigOk fileSize=${File(path).length()}B"
        )
    } catch (e: Exception) {
        report("long write", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}

// ---------- 3. long write, kill mid-write ----------

private fun testLongWriteKill() {
    val trials = 15
    var kept = 0
    val failures = mutableListOf<String>()

    for (t in 0 until trials) {
        val path = freshPath("longkill_$t")
        try {
            val baseline = AxsFile(path)
            baseline.open()
            for (i in 0 until 50) baseline.set("baseline.$i", "base-$i")
            baseline.close()

            val proc = spawnWorker("long-write", path)
            Thread.sleep(Random.nextLong(5, 60))
            proc.destroyForcibly()
            proc.waitFor()

            val check = AxsFile(path)
            check.open()
            var baselineOk = true
            for (i in 0 until 50) {
                if ((check.get("baseline.$i") as? AxsString)?.value != "base-$i") baselineOk = false
            }
            check.debugDumpIndex()
            check.close()

            if (baselineOk) kept++ else failures.add("trial $t: baseline damaged after kill")
        } catch (e: Exception) {
            failures.add("trial $t threw: $e")
        } finally {
            File(path).delete()
            File("$path.tmp").delete()
        }
    }

    report(
        "long write + kill mid-write ($trials trials)",
        kept == trials,
        "$kept/$trials trials kept the pre-existing committed data intact" +
            if (failures.isNotEmpty()) "; " + failures.joinToString("; ") else ""
    )
}

// ---------- 4. defragmentation ----------

private fun testDefragment() {
    val path = freshPath("defrag")
    try {
        val a = AxsFile(path)
        a.open()
        val seed = (0 until 2000).associate { "item.$it" to axsValueOf("value-$it-".repeat(5)) }
        a.setAll(seed)
        for (i in 0 until 2000 step 2) a.delete("item.$i")
        val sizeBefore = File(path).length()

        a.defragment()
        val sizeAfter = File(path).length()

        var dataOk = true
        for (i in 1 until 2000 step 2) {
            if ((a.get("item.$i") as? AxsString)?.value != "value-$i-".repeat(5)) dataOk = false
        }
        for (i in 0 until 2000 step 2) {
            if (a.get("item.$i") != null) dataOk = false
        }
        val dump = a.debugDumpIndex()
        val freeListEmpty = dump.none { it.contains("Free list") }
        a.close()

        report(
            "defragment",
            dataOk && sizeAfter < sizeBefore && freeListEmpty,
            "sizeBefore=${sizeBefore}B sizeAfter=${sizeAfter}B dataOk=$dataOk freeListEmpty=$freeListEmpty"
        )
    } catch (e: Exception) {
        report("defragment", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}

// ---------- 5. defragmentation, kill mid-process ----------

private fun testDefragmentKill() {
    val trials = 8
    var kept = 0
    var confirmedMidFlight = 0
    val failures = mutableListOf<String>()

    for (t in 0 until trials) {
        val path = freshPath("defragkill_$t")
        try {
            val a = AxsFile(path)
            a.open()
            val seed = (0 until 100_000).associate { "item.$it" to axsValueOf("payload-$it-".repeat(10)) }
            a.setAll(seed)
            a.close()

            val sample = (0 until 100_000 step 2500).associateWith { i ->
                val r = AxsFile(path); r.open()
                val v = (r.get("item.$i") as? AxsString)?.value ?: ""
                r.close(); v
            }

            val proc = spawnWorker("defragment", path)
            Thread.sleep(Random.nextLong(20, 400))
            val stillRunning = proc.isAlive
            proc.destroyForcibly()
            proc.waitFor()
            if (stillRunning) confirmedMidFlight++

            File("$path.tmp").delete()

            val check = AxsFile(path)
            check.open()
            var dataOk = true
            for ((i, expected) in sample) {
                if ((check.get("item.$i") as? AxsString)?.value != expected) dataOk = false
            }
            check.close()

            if (dataOk) kept++ else failures.add("trial $t: original data damaged by a killed defragment")
        } catch (e: Exception) {
            failures.add("trial $t threw: $e")
        } finally {
            File(path).delete()
            File("$path.tmp").delete()
        }
    }

    report(
        "defragment + kill mid-process ($trials trials)",
        kept == trials,
        "$kept/$trials preserved the original file untouched " +
            "($confirmedMidFlight/$trials confirmed still mid-flight at kill time)" +
            if (failures.isNotEmpty()) "; " + failures.joinToString("; ") else ""
    )
}

// ---------- 6. corrupted file, read, per-key report ----------

private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
    outer@ for (i in 0..haystack.size - needle.size) {
        for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

// ---------- 7. WriteQueue batching (bind() property writes coalesce into one commit) ----------

data class Prefs(
    var volume: Int = 0,
    var muted: Boolean = false,
    var lastTrack: String = "",
    var brightness: Int = 0,
    var name: String = ""
)

private fun generationOf(path: String): Int {
    val a = AxsFile(path)
    a.open()
    val line = a.debugDumpIndex().first { it.contains("generation") }
    a.close()
    return Regex("""generation (\d+)""").find(line)!!.groupValues[1].toInt()
}

private fun testWriteQueueBatching() {
    val path = freshPath("batching")
    try {
        val a = AxsFile(path)
        a.open()
        a.bind(Prefs())
        a.close()

        val genBefore = generationOf(path)

        val b = AxsFile(path)
        b.open()
        val bound = b.bind(Prefs())
        bound.setValue(Prefs::volume, 80)
        bound.setValue(Prefs::muted, true)
        bound.setValue(Prefs::lastTrack, "song.mp3")
        bound.setValue(Prefs::brightness, 50)
        bound.setValue(Prefs::name, "MyDevice")
        Thread.sleep(400)
        b.close()

        val genAfter = generationOf(path)
        val commits = genAfter - genBefore

        val c = AxsFile(path)
        c.open()
        val restored = Prefs()
        c.bind(restored)
        c.close()

        val dataOk = restored == Prefs(80, true, "song.mp3", 50, "MyDevice")

        report(
            "WriteQueue batches concurrent property writes into one commit",
            commits == 1 && dataOk,
            "5 property writes -> $commits commit(s) (generation $genBefore -> $genAfter), restored=$restored"
        )
    } catch (e: Exception) {
        report("WriteQueue batching", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}

private fun testCorruptedRead() {
    val path = freshPath("corrupt")
    val marker = "CORRUPT_ME_TARGET_VALUE_MARKER_1234567890"
    try {
        val a = AxsFile(path)
        a.open()
        a.set("name", "Axis")
        a.set("age", 30)
        a.set("victim", marker)
        a.set("tags", axsValueOf(listOf(axsValueOf("kotlin"), axsValueOf("android"), axsValueOf("music"))))
        a.close()

        val markerBytes = marker.toByteArray(Charsets.UTF_8)
        val fileBytes = File(path).readBytes()
        val idx = indexOfBytes(fileBytes, markerBytes)
        if (idx < 0) {
            report("corrupted-entry read", false, "couldn't locate marker bytes in file to corrupt")
            return
        }
        RandomAccessFile(path, "rw").use { raf ->
            raf.seek((idx + 5).toLong())
            val original = raf.readByte()
            raf.seek((idx + 5).toLong())
            raf.writeByte((original.toInt() xor 0xFF) and 0xFF)
        }

        val output = StringBuilder()
        var threw = false
        var name: String? = null
        var age: Int? = null
        var victimWasFlaggedCorrupt = false
        var tagsOk = false

        try {
            val b = AxsFile(path)
            b.open()
            name = (b.get("name") as? AxsString)?.value
            age = (b.get("age") as? AxsInt)?.value
            val victim = b.get("victim")
            victimWasFlaggedCorrupt = victim is AxsNull
            val tags = (b.get("tags") as? AxsArray)?.items?.mapNotNull { (it as? AxsString)?.value }
            tagsOk = tags == listOf("kotlin", "android", "music")
            b.close()

            output.appendLine("name, $name")
            output.appendLine("age, $age")
            output.appendLine("victim, " + if (victimWasFlaggedCorrupt) "[forcefully corrupted]" else "$victim")
            output.appendLine("tags, $tags")
        } catch (e: Exception) {
            threw = true
            output.appendLine("didn't pass - threw while reading: $e")
        }

        println(output.toString().trimEnd())

        val ok = !threw && name == "Axis" && age == 30 && victimWasFlaggedCorrupt && tagsOk
        report(
            "corrupted-entry read",
            ok,
            if (threw) "reading the file threw instead of degrading the one bad entry"
            else "name/age/tags survived untouched; victim correctly flagged as corrupted"
        )
    } catch (e: Exception) {
        report("corrupted-entry read", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}
