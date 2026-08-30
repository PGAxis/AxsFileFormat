package dev.pgaxis.axs

import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

class WriteQueue(
    private val quietPeriodMs: Long = 100,
    private val maxBatchDelayMs: Long = 1000,
    private val maxBatchSize: Int = 500
) {
    private val pending = LinkedHashMap<String, AxsValue>()
    private val lock = Any()
    private var oldestPendingAt: Long = 0
    private var lastArrivalAt: Long = 0
    private var processorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var onFlush: ((Map<String, AxsValue>) -> Unit)? = null

    fun enqueue(path: String, value: AxsValue) {
        synchronized(lock) {
            if (pending.isEmpty()) oldestPendingAt = System.currentTimeMillis()
            pending[path] = value
            lastArrivalAt = System.currentTimeMillis()
        }
        startProcessorIfNeeded()
    }

    private fun startProcessorIfNeeded() {
        synchronized(lock) {
            if (processorJob?.isActive == true) return
            processorJob = scope.launch { processLoop() }
        }
    }

    private suspend fun processLoop() {
        while (true) {
            val wait = synchronized(lock) {
                if (pending.isEmpty()) return
                val now = System.currentTimeMillis()
                val quietRemaining = quietPeriodMs - (now - lastArrivalAt)
                val hardRemaining = maxBatchDelayMs - (now - oldestPendingAt)
                val sizeReady = pending.size >= maxBatchSize
                if (sizeReady) 0L else maxOf(0L, minOf(quietRemaining, hardRemaining))
            }

            if (wait > 0) delay(wait.milliseconds)

            val batch = synchronized(lock) {
                if (pending.isEmpty()) return
                val now = System.currentTimeMillis()
                val ready = (now - lastArrivalAt >= quietPeriodMs) ||
                    (now - oldestPendingAt >= maxBatchDelayMs) ||
                    (pending.size >= maxBatchSize)
                if (!ready) return@synchronized null

                val copy = LinkedHashMap(pending)
                pending.clear()
                copy
            }

            if (!batch.isNullOrEmpty()) onFlush?.invoke(batch)
        }
    }

    fun cancel() {
        synchronized(lock) { pending.clear() }
        runBlocking { processorJob?.cancelAndJoin() }
    }

    fun flushNow() {
        runBlocking { processorJob?.cancelAndJoin() }
        val batch = synchronized(lock) {
            val copy = LinkedHashMap(pending)
            pending.clear()
            copy
        }
        if (batch.isNotEmpty()) onFlush?.invoke(batch)
    }
}
