package dev.pg_axis.axs.cli

import dev.pg_axis.axs.*

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Usage: axs <command> [args]")
    println("Commands: create <file>")
    return
  }

  when (args[0]) {
    "create" -> {
      if (args.size < 2) { println("Usage: axs create <file>"); return }
      AxsFile.create(args[1])
    }
    "set" -> {
      if (args.size < 4) { println("Usage: axs set <file> <key> <value> [type]"); return }
      val valueType = when (args.getOrNull(4)?.uppercase()) {
        "INT" -> ValueType.INT
        "FLOAT" -> ValueType.FLOAT
        "BOOL" -> ValueType.BOOL
        else -> ValueType.STRING
      }
      AxsFile.set(args[2], args[1], args[3], valueType)
    }
    "get" -> {
      if (args.size < 3) { println("Usage: axs get <file> <key>"); return }
      val value = AxsFile.get(args[2], args[1])
      if (value == null) println("Key not found: ${args[2]}")
      else println(valueToJson(value))
    }
    "delete" -> {
      if (args.size < 3) { println("Usage: axs delete <file> <key> [--recursive]"); return }
      val recursive = args.contains("--recursive") || args.contains("-r")
      AxsFile.delete(args[2], args[1], recursive)
    }
    "create-array" -> {
      if (args.size < 3) { println("Usage: axs create-array <file> <path>"); return }
      AxsFile.createArray(args[2], args[1])
    }
    "create-object" -> {
      if (args.size < 3) { println("Usage: axs create-object <file> <path>"); return }
      AxsFile.createObject(args[2], args[1])
    }
    "dump" -> {
      if (args.size < 3) { println("Usage: axs dump <file> <outputDir>"); return }
      AxsFile.dump(args[1], args[2])
    }
    "import" -> {
      if (args.size < 2) { println("Usage: axs import <folder> [--force]"); return }
      val force = args.contains("--force")
      AxsFile.import(args[1], force)
    }
    "test" -> {
      AxsFile.create("test_obj.axs")
      AxsFile.set("meta", "test_obj.axs", axsValueOf(mapOf(
        "author" to axsValueOf("Axis"),
        "version" to axsValueOf(1.0f),
        "year" to axsValueOf(2026),
        "tags" to axsValueOf(listOf(
          axsValueOf("kotlin"),
          axsValueOf("android")
        ))
      )))
      println(valueToJson(AxsFile.get("meta", "test_obj.axs")!!))
    }
    else -> println("Unknown command: ${args[0]}")
  }
}