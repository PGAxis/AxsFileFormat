package dev.pg_axis.axs

sealed class AxsValue

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
  is AxsString -> "\"${value.value}\""
  is AxsInt -> value.value.toString()
  is AxsFloat -> value.value.toString()
  is AxsBool -> value.value.toString()
  is AxsObject -> value.toJson(indent)
  is AxsArray -> value.toJson(indent)
}

fun axsValueOf(value: String) = AxsString(value)
fun axsValueOf(value: Int) = AxsInt(value)
fun axsValueOf(value: Float) = AxsFloat(value)
fun axsValueOf(value: Double) = AxsFloat(value.toFloat())
fun axsValueOf(value: Boolean) = AxsBool(value)
fun axsValueOf(value: List<AxsValue>) = AxsArray(value)
fun axsValueOf(value: Map<String, AxsValue>) = AxsObject(value)