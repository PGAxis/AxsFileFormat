package dev.pgaxis.axs.cli

import dev.pgaxis.axs.*

fun main(args: Array<String>) {
  if (args.isEmpty()) {
    println("Usage: axs <command> [args]")
    println("Commands: create <file> | set <file> <key> <value> [type] | get <file> <key> | delete <file> <key> [--recursive/-r] | create-array <file> <path> | create-object <file> <path> | dump <file> <outputDir> | import <folder> [--force]")
    return
  }

  when (args[0]) {
    "create" -> {
      if (args.size < 2) { println("Usage: axs create <file>"); return }
      val file = AxsFile(args[1])
      file.open()
      file.close()
      println("Created ${args[1]}")
    }
    "set" -> {
      if (args.size < 4) { println("Usage: axs set <file> <key> <value> [type]"); return }
      val valueType = when (args.getOrNull(4)?.uppercase()) {
        "INT" -> ValueType.INT
        "FLOAT" -> ValueType.FLOAT
        "BOOL" -> ValueType.BOOL
        "DOUBLE" -> ValueType.DOUBLE
        "LONG" -> ValueType.LONG
        "SHORT" -> ValueType.SHORT
        "CHAR" -> ValueType.CHAR
        "BYTE" -> ValueType.BYTE
        else -> ValueType.STRING
      }
      val file = AxsFile(args[1])
      file.open()
      file.set(args[2], args[3], valueType)
      file.close()
    }
    "get" -> {
      if (args.size < 3) { println("Usage: axs get <file> <key>"); return }
      val file = AxsFile(args[1])
      file.open()
      val value = file.get(args[2])
      file.close()
      if (value == null) println("Key not found: ${args[2]}")
      else println(valueToJson(value))
    }
    "delete" -> {
      if (args.size < 3) { println("Usage: axs delete <file> <key> [--recursive/-r]"); return }
      val recursive = args.contains("--recursive") || args.contains("-r")
      val file = AxsFile(args[1])
      file.open()
      file.delete(args[2], recursive)
      file.close()
    }
    "create-array" -> {
      if (args.size < 3) { println("Usage: axs create-array <file> <path>"); return }
      val file = AxsFile(args[1])
      file.open()
      file.createArray(args[2])
      file.close()
    }
    "create-object" -> {
      if (args.size < 3) { println("Usage: axs create-object <file> <path>"); return }
      val file = AxsFile(args[1])
      file.open()
      file.createObject(args[2])
      file.close()
    }
    "dump" -> {
      if (args.size < 3) { println("Usage: axs dump <file> <outputDir>"); return }
      val file = AxsFile(args[1])
      file.open()
      file.dump(args[2])
      file.close()
      println("Dumped to ${args[2]}")
    }
    "import" -> {
      if (args.size < 2) { println("Usage: axs import <folder> [--force]"); return }
      val force = args.contains("--force")
      val outputPath = "${args[1].trimEnd('/')}.axs"
      val file = AxsFile(outputPath)
      file.open()
      file.import(args[1], force)
      file.close()
      println("Imported ${args[1]} -> $outputPath")
    }
    "test" -> {
      val file = AxsFile("test_obj.axs")
      file.open()
      file.set("meta", axsValueOf(mapOf(
        "author" to axsValueOf("Axis"),
        "version" to axsValueOf(1.0f),
        "year" to axsValueOf(2026),
        "tags" to axsValueOf(listOf(
          axsValueOf("kotlin"),
          axsValueOf("android")
        ))
      )))
      println(valueToJson(file.get("meta")!!))
      file.close()
    }
    "bind-test" -> {
      data class AppSettings(
        var darkMode: Boolean = true,
        var fontSize: Int = 14,
        var username: String = "Axis",
        var volume: Float = 0.8f
      )

      val file = AxsFile("bind_test.axs")
      file.open()
      val settings = file.bind(AppSettings())

      println("Before:")
      println(valueToJson(file.get("AppSettings")!!))

      settings.setValue(AppSettings::darkMode, false)
      settings.setValue(AppSettings::fontSize, 16)

      println("After:")
      println(valueToJson(file.get("AppSettings")!!))

      file.close()
    }
    else -> println("Unknown command: ${args[0]}")
  }
}