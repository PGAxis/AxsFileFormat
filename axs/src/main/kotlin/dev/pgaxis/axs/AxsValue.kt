package dev.pgaxis.axs

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties

sealed class AxsValue

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class AxsKey(val name: String)

object AxsNull : AxsValue() {
    override fun toString() = "null"
}

data class AxsString(val value: String): AxsValue() {
    override fun toString() = value
}

data class AxsInt(val value: Int): AxsValue() {
    override fun toString() = value.toString()
}

data class AxsFloat(val value: Float): AxsValue() {
    override fun toString() = value.toString()
}

data class AxsBool(val value: Boolean): AxsValue() {
  override fun toString() = value.toString()
}

data class AxsDouble(val value: Double) : AxsValue() {
    override fun toString() = value.toString()
}
data class AxsLong(val value: Long) : AxsValue() {
    override fun toString() = value.toString()
}
data class AxsShort(val value: Short) : AxsValue() {
    override fun toString() = value.toString()
}
data class AxsChar(val value: Char) : AxsValue() {
    override fun toString() = value.toString()
}
data class AxsByte(val value: Byte) : AxsValue() {
    override fun toString() = value.toString()
}

data class AxsObject(val children: Map<String, AxsValue>): AxsValue() {
    override fun toString() = toJson(0)
    fun toJson(indent: Int): String {
        val pad = "  ".repeat(indent)
        val inner = "  ".repeat(indent + 1)
        return children.entries.joinToString(",\n", "{\n", "\n$pad}") { (k, v) ->
            "$inner\"$k\":${valueToJson(v, indent + 1)}"
        }
    }
}

inline fun <reified T : Any> AxsObject.toDataClass(default: T): T {
    val props = T::class.memberProperties.filterIsInstance<KMutableProperty1<T, *>>()

    for (prop in props) {
        val key = prop.findAnnotation<AxsKey>()?.name ?: prop.name
        val axsValue = children[key] ?: continue // use default if missing

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
            else -> null
        }

        if (converted != null) {
            @Suppress("UNCHECKED_CAST")
            (prop as KMutableProperty1<T, Any>).set(default, converted)
        }
    }

    return default
}

data class AxsArray(val items: List<AxsValue>): AxsValue() {
    override fun toString() = toJson(0)
    fun toJson(indent: Int): String {
        val pad = "  ".repeat(indent)
        val inner = "  ".repeat(indent + 1)
        return items.joinToString(",\n", "[\n", "\n$pad]") { v ->
            "$inner${valueToJson(v, indent + 1)}"
        }
    }
}

fun valueToJson(value: AxsValue, indent: Int = 0): String = when (value) {
    is AxsNull -> "null"
    is AxsString -> "\"${value.value}\""
    is AxsInt -> value.value.toString()
    is AxsFloat -> value.value.toString()
    is AxsBool -> value.value.toString()
    is AxsDouble -> value.value.toString()
    is AxsLong -> value.value.toString()
    is AxsShort -> value.value.toString()
    is AxsChar -> "\"${value.value}\""
    is AxsByte -> value.value.toString()
    is AxsObject -> value.toJson(indent)
    is AxsArray -> value.toJson(indent)
}

fun axsValueOf(value: Nothing?) = AxsNull
fun axsValueOf(value: String) = AxsString(value)
fun axsValueOf(value: Int) = AxsInt(value)
fun axsValueOf(value: Float) = AxsFloat(value)
fun axsValueOf(value: Boolean) = AxsBool(value)
fun axsValueOf(value: Double) = AxsDouble(value)
fun axsValueOf(value: Long) = AxsLong(value)
fun axsValueOf(value: Short) = AxsShort(value)
fun axsValueOf(value: Char) = AxsChar(value)
fun axsValueOf(value: Byte) = AxsByte(value)
fun axsValueOf(value: List<AxsValue>) = AxsArray(value)
fun axsValueOf(value: Map<String, AxsValue>) = AxsObject(value)