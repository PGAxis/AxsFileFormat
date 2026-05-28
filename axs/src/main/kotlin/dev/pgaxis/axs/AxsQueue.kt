package dev.pgaxis.axs

import kotlinx.coroutines.*
import java.util.LinkedList

data class QueueEntry(
  val key: String,
  val write: suspend () -> Unit,
  val timestamp: Long = System.currentTimeMillis()
)

class WriteQueue {
  private val queue = LinkedList<QueueEntry>()
  private val lock = Any()
  private var processorJob: Job? = null
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  fun enqueue(key: String, write: suspend () -> Unit) {
    synchronized(lock) {
      queue.removeIf { it.key == key }
      queue.add(QueueEntry(key, write))
    }
    startProcessorIfNeeded()
  }

  private fun startProcessorIfNeeded() {
    synchronized(lock) {
      if (processorJob?.isActive == true) return
      processorJob = scope.launch { processQueue() }
    }
  }

  private suspend fun processQueue() {
    while (true) {
      val entry = synchronized(lock) { queue.peek() } ?: return

      val age = System.currentTimeMillis() - entry.timestamp
      val remaining = 300L - age

      if (remaining > 0) {
        delay(remaining)
      }

      val current = synchronized(lock) { queue.peek() }
      if (current === entry) {
        synchronized(lock) { queue.poll() }
        entry.write()
      }
    }
  }

  fun cancel() {
    synchronized(lock) { queue.clear() }
    runBlocking { processorJob?.cancelAndJoin() }
  }

  fun flushNow() {
    runBlocking {
      processorJob?.cancelAndJoin()
      val remaining = synchronized(lock) {
        val copy = queue.toList()
        queue.clear()
        copy
      }
      for (entry in remaining) {
        entry.write()
      }
    }
  }
}