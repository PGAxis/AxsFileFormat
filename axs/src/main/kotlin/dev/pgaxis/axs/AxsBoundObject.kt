package dev.pgaxis.axs

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties

class AxsBoundObject<T : Any>(
  private val file: AxsFile,
  private var instance: T,
  private val className: String,
  private val writeQueue: WriteQueue
) {
    fun <V> getValue(prop: KProperty1<T, V>): V {
        return prop.get(instance)
    }

    fun <V : Any> setValue(prop: KMutableProperty1<T, V>, value: V) {
        if (!file.isOpen()) throw AxsFileNotOpenException(className)
        prop.set(instance, value)

        writeQueue.enqueue(prop.name) {
            file.set("$className.${prop.name}", value.toAxsCompatibleValue())
        }
    }

    fun flush() {
        writeQueue.flushNow()
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
        is List<*> -> AxsArray(this.map { it?.toAxsCompatibleValue() ?: AxsNull })
        is Enum<*> -> axsValueOf(this.name)
        else -> {
            val props = this::class.memberProperties
            if (props.isEmpty()) throw AxsTypeMismatchException(className, this::class.simpleName ?: "unknown", "supported type")
            val children = props.associate { prop ->
                @Suppress("UNCHECKED_CAST")
                val value = (prop as KProperty1<Any, *>).get(this)
                prop.name to (value?.toAxsCompatibleValue() ?: AxsNull)
            }
            AxsObject(children)
        }
    }
}