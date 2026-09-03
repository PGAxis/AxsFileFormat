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
    f.delete() // we want the path, not an empty file - AxsFile.open() creates it
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
        "splitting" -> testFreeSpaceSplitting()
        "index-reuse" -> testIndexSpaceReuseBoundedGrowth()
        "reuse-hazard" -> testSameTransactionReuseHazard()
        "prune" -> testStaleChildPruning()
        "all" -> {
            testSimpleWriteRead()
            testLongWrite()
            testLongWriteKill()
            testDefragment()
            testDefragmentKill()
            testCorruptedRead()
            testWriteQueueBatching()
            testFreeSpaceSplitting()
            testIndexSpaceReuseBoundedGrowth()
            testSameTransactionReuseHazard()
            testStaleChildPruning()
        }
        else -> {
            println("Unknown test '$which'. Options: simple, long, long-kill, defrag, defrag-kill, corrupt, batching, migration, splitting, index-reuse, reuse-hazard, prune, all")
            return
        }
    }

    println()
    val passed = results.count { it.passed }
    println("=== $passed/${results.size} tests passed ===")
    if (passed != results.size) exitProcess(1)
}

// ---------- worker subprocess (used by the two kill tests) ----------
// Runs in a completely separate JVM process so destroyForcibly() is a real
// SIGKILL, not just an interrupted thread - the same failure mode as Android
// force-stopping the app mid-write.

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
            // Known-good, fully committed baseline before the kill happens.
            val baseline = AxsFile(path)
            baseline.open()
            for (i in 0 until 50) baseline.set("baseline.$i", "base-$i")
            baseline.close()

            val proc = spawnWorker("long-write", path)
            Thread.sleep(Random.nextLong(5, 60)) // vary where in the write loop the kill lands
            proc.destroyForcibly()
            proc.waitFor()

            val check = AxsFile(path)
            check.open() // must not throw - this alone is most of the claim
            var baselineOk = true
            for (i in 0 until 50) {
                if ((check.get("baseline.$i") as? AxsString)?.value != "base-$i") baselineOk = false
            }
            check.debugDumpIndex() // exercises reading whatever partial worker state exists too
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
        // Populate in one commit (setAll) rather than 2000 individual set() calls -
        // see the "long write" test's file-size finding for why that distinction
        // matters. What's under test here is defragment(), not commit overhead.
        val seed = (0 until 2000).associate { "item.$it" to axsValueOf("value-$it-".repeat(5)) }
        a.setAll(seed)
        for (i in 0 until 2000 step 2) a.delete("item.$i") // free every other one
        val sizeBefore = File(path).length()

        a.defragment()
        val sizeAfter = File(path).length()

        var dataOk = true
        for (i in 1 until 2000 step 2) {
            if ((a.get("item.$i") as? AxsString)?.value != "value-$i-".repeat(5)) dataOk = false
        }
        for (i in 0 until 2000 step 2) {
            if (a.get("item.$i") != null) dataOk = false // deleted ones must stay gone
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
            // Populate in one commit (setAll), not 20,000 individual set() calls -
            // individual top-level set()s each pay for a full index rewrite (see the
            // "long write" test), so 20,000 of them would make *setup* the slow part
            // instead of defragment() itself. One commit gets us a large enough live
            // dataset that defragment's copy loop has a real multi-hundred-ms window,
            // without that unrelated cost.
            val seed = (0 until 100_000).associate { "item.$it" to axsValueOf("payload-$it-".repeat(10)) }
            a.setAll(seed)
            a.close()

            val sample = (0 until 100_000 step 2500).associateWith { i ->
                val r = AxsFile(path); r.open()
                val v = (r.get("item.$i") as? AxsString)?.value ?: ""
                r.close(); v
            }

            val proc = spawnWorker("defragment", path)
            Thread.sleep(Random.nextLong(20, 400)) // defragment(100k) takes ~700ms locally
            val stillRunning = proc.isAlive
            proc.destroyForcibly()
            proc.waitFor()
            if (stillRunning) confirmedMidFlight++

            // Leftover .tmp from an interrupted defragment is harmless debris -
            // nothing reads it - but clean it up for hygiene before reopening.
            File("$path.tmp").delete()

            val check = AxsFile(path)
            check.open() // the ORIGINAL file - must not throw regardless of kill timing
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
        a.bind(Prefs()) // creates the class's default object - its own commit(s)
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
        Thread.sleep(400) // past the quiet period, batch should have flushed once
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

// ---------- 8. best-fit free-block reuse with splitting ----------

private fun testFreeSpaceSplitting() {
    val path = freshPath("splitting")
    try {
        val a = AxsFile(path)
        a.open()

        // One big value, then delete it - leaves one large free block (plus,
        // now that old index generations get freed too, some small unrelated
        // free entries from prior commits' index blobs - identify the one that
        // actually corresponds to "big" by its expected total span rather than
        // assuming it's first in the list).
        a.set("big", "X".repeat(1000))
        a.delete("big")
        val sizeAfterFree = File(path).length()
        val expectedBigBlockSpan = AXS_BLOCK_HEADER_SIZE + 1000
        val freeSizes = a.debugDumpIndex()
            .filter { it.trim().startsWith("offset=") }
            .map { Regex("""size=(\d+)""").find(it)!!.groupValues[1].toInt() }
        val bigBlockFound = expectedBigBlockSpan in freeSizes

        // Batched into ONE commit deliberately: three separate top-level set()
        // calls would each pay for their own index rewrite (a different, already-
        // known cost - see the "long write" test), which would swamp the much
        // smaller effect we're actually isolating here: whether the VALUE bytes
        // themselves get reused out of the freed hole instead of appended fresh.
        a.setBatch(
            mapOf(
                "small1" to axsValueOf("aaaa"),      // 4 bytes
                "small2" to axsValueOf("bbbbbbbb"),  // 8 bytes
                "small3" to axsValueOf("cc")         // 2 bytes
            )
        )
        val sizeAfterSmallWrites = File(path).length()

        val dataOk = (a.get("small1") as? AxsString)?.value == "aaaa" &&
                (a.get("small2") as? AxsString)?.value == "bbbbbbbb" &&
                (a.get("small3") as? AxsString)?.value == "cc"

        val bigSpanStillFree = a.debugDumpIndex()
            .filter { it.trim().startsWith("offset=") }
            .map { Regex("""size=(\d+)""").find(it)!!.groupValues[1].toInt() }
            .any { it == expectedBigBlockSpan } // should be GONE/shrunk now, not still 1010

        a.close()

        // 3 payloads (4+8+2=14) plus 3 block headers (3*10=30) = 44 bytes should
        // have come out of the freed 1010-byte hole (split down to a smaller
        // remainder), not appended fresh at EOF (which would cost the full 14
        // bytes of new *file* growth on top of everything already there, same
        // as before this feature existed).
        val growth = sizeAfterSmallWrites - sizeAfterFree

        report(
            "free space reused via best-fit + splitting",
            dataOk && bigBlockFound && !bigSpanStillFree && growth < 500,
            "big's freed block (span=$expectedBigBlockSpan) found pre-write=$bigBlockFound, " +
                    "consumed/split by small writes=${!bigSpanStillFree}, fileGrowth=${growth}B dataOk=$dataOk"
        )
    } catch (e: Exception) {
        report("free space reused via best-fit + splitting", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}

// ---------- 9. index space reuse under realistic churn (bounded growth) ----------

private fun testIndexSpaceReuseBoundedGrowth() {
    val path = freshPath("index-reuse")
    try {
        val a = AxsFile(path)
        a.open()

        // Seed a modest, STABLE set of keys - this is the realistic pattern
        // (bound settings, a fixed-ish set of fields updated over time), not
        // the "always brand new keys" pattern the "long write" test uses (which
        // this feature can't help - see its own results/notes).
        a.setBatch((0 until 20).associate { "field$it" to axsValueOf("AAAA") })

        // A few warm-up rounds so the free list settles into its steady state
        // (the first couple of resizes necessarily still need to allocate the
        // "other" size class for the first time).
        repeat(3) { round ->
            for (i in 0 until 20) a.set("field$i", if (round % 2 == 0) "BBBBBBBB" else "AAAA")
        }
        val sizeAfterWarmup = File(path).length()

        // Alternate every field between exactly two FIXED-length values, many
        // times over - deliberately avoids any size class ever being new by
        // this point, isolating "does the index blob itself get reused across
        // commits" from "does the value's byte length happen to keep changing
        // to a class never seen before" (which the free list can't help with
        // no matter what - see the "long write" test).
        repeat(200) { round ->
            for (i in 0 until 20) {
                a.set("field$i", if (round % 2 == 0) "AAAA" else "BBBBBBBB")
            }
        }
        val sizeAfterChurn = File(path).length()

        val dataOk = (0 until 20).all { i -> (a.get("field$i") as? AxsString)?.value == "BBBBBBBB" }
        a.close()

        // Once warmed up, every subsequent round should be cycling through the
        // SAME two free-list entries per field (the previous round's freed
        // block, immediately reused for this round's opposite-sized value) -
        // growth from here should be at most the fixed per-commit index-write
        // cost, not scaling with the 4000 individual set() calls involved.
        val growth = sizeAfterChurn - sizeAfterWarmup

        report(
            "index space reused under realistic (stable key set) churn",
            dataOk && growth < 20_000,
            "sizeAfterWarmup=${sizeAfterWarmup}B sizeAfterChurn=${sizeAfterChurn}B growth=${growth}B " +
                    "over 4000 individual commits alternating 2 fixed value sizes " +
                    "(unbounded reuse-free growth would be several MB) dataOk=$dataOk"
        )
    } catch (e: Exception) {
        report("index space reused under realistic churn", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}

// ---------- 10. same-transaction free-list reuse hazard (regression test) ----------

private fun testSameTransactionReuseHazard() {
    val path = freshPath("reuse-hazard")
    try {
        val a = AxsFile(path)
        a.open()
        a.setBatch(mapOf("victim" to axsValueOf("V".repeat(200))))

        // Read victim's original 200 bytes directly off disk before the resize.
        val beforeBytes = File(path).readBytes().copyOf()

        // A resize that, if the just-freed block were eligible for reuse WITHIN
        // this same commit, would physically overwrite victim's own pre-existing
        // bytes before this generation is safely superseded - see the design
        // notes on why that's unsafe (a real crash between the write and the
        // commit's flip could leave the pre-commit generation unable to read a
        // value it never asked to have touched).
        a.setBatch(mapOf("victim" to axsValueOf("v")))
        val afterBytes = File(path).readBytes()

        val marker = "V".repeat(200).toByteArray()
        var origOffset = -1
        outer@ for (i in 0..beforeBytes.size - marker.size) {
            for (j in marker.indices) if (beforeBytes[i + j] != marker[j]) continue@outer
            origOffset = i; break
        }

        val stillIntact = origOffset >= 0 && (0 until marker.size).all { k ->
            origOffset + k < afterBytes.size && afterBytes[origOffset + k] == marker[k]
        }
        a.close()

        report(
            "same-transaction free-list entries are never reused within that transaction",
            origOffset >= 0 && stillIntact,
            if (origOffset < 0) "couldn't locate victim's original bytes to check"
            else "victim's pre-resize bytes at offset $origOffset " +
                    (if (stillIntact) "were left untouched (safe)" else "were overwritten during the same commit that freed them (unsafe)")
        )
    } catch (e: Exception) {
        report("same-transaction free-list reuse hazard", false, "threw: $e")
    } finally {
        File(path).delete()
    }
}

// ---------- 11. stale children pruned when an object/array shrinks ----------

private fun testStaleChildPruning() {
    val path = freshPath("prune")
    try {
        val a = AxsFile(path)
        a.open()

        a.set("list", axsValueOf(listOf(axsValueOf("A"), axsValueOf("B"), axsValueOf("C"))))
        a.set("list", axsValueOf(listOf(axsValueOf("A"), axsValueOf("C")))) // drop the middle item
        val listAfter = (a.get("list") as? AxsArray)?.items?.mapNotNull { (it as? AxsString)?.value }

        a.set("obj", axsValueOf(mapOf("a" to axsValueOf("1"), "b" to axsValueOf("2"), "c" to axsValueOf("3"))))
        a.set("obj", axsValueOf(mapOf("a" to axsValueOf("1"), "c" to axsValueOf("3")))) // drop key "b"
        val objAfter = (a.get("obj") as? AxsObject)?.children?.mapValues { (it.value as? AxsString)?.value }

        a.close()

        val listOk = listAfter == listOf("A", "C")
        val objOk = objAfter == mapOf("a" to "1", "c" to "3")

        report(
            "shrinking an object/array prunes stale children instead of leaking them",
            listOk && objOk,
            "list after removing middle item: $listAfter (want [A, C]); " +
                    "object after removing key 'b': $objAfter (want {a=1, c=3})"
        )
    } catch (e: Exception) {
        report("stale child pruning", false, "threw: $e")
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

        // Forcefully corrupt the "victim" value's bytes on disk, in place -
        // same length, so this is a pure bit-flip, not a structural change.
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