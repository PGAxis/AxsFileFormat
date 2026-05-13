package dev.pgaxis.axs

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AxsBoundObject<T : Any>(
  private val file: AxsFile,
  private var instance: T,
  private val className: String
) {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val mutex = Mutex()
  private val writeJobs = mutableMapOf<String, Job>()

  fun <V> getValue(prop: KProperty1<T, V>): V {
    return prop.get(instance)
  }

  fun <V : Any> setValue(prop: KMutableProperty1<T, V>, value: V) {
    if (!file.isOpen()) throw AxsFileNotOpenException(className)
    prop.set(instance, value)

    val key = prop.name
    writeJobs[key]?.cancel()
    writeJobs[key] = scope.launch {
      delay(300)
      mutex.withLock {
        file.set("$className.$key", value.toAxsCompatibleValue())
      }
    }
  }

  fun flush() {
    runBlocking {
      writeJobs.values.forEach { it.cancel() }
      writeJobs.clear()

      val values = buildMap {
        for (prop in instance::class.memberProperties
            .filterIsInstance<KMutableProperty1<T, Any>>()) {
          val value = prop.get(instance) ?: continue
          put("$className.${prop.name}", value.toAxsCompatibleValue())
        }
      }

      mutex.withLock {
        file.setAll(values)
      }
    }
  }

  fun get(): T = instance

  private fun Any.toAxsCompatibleValue(): AxsValue = when (this) {
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
      it?.toAxsCompatibleValue() ?: throw AxsTypeMismatchException(className, "null", "supported type")
    })
    is Enum<*> -> axsValueOf(this.name)
    else -> {
      val props = this::class.memberProperties
      if (props.isEmpty()) throw AxsTypeMismatchException(className, this::class.simpleName ?: "unknown", "supported type")
      val children = props.associate { prop ->
        @Suppress("UNCHECKED_CAST")
        val value = (prop as KProperty1<Any, *>).get(this)
        prop.name to (value?.toAxsCompatibleValue()
          ?: throw AxsTypeMismatchException(className, "null", "supported type"))
      }
      AxsObject(children)
    }
  }
}