package dev.pgaxis.axs

class AxsFileNotOpenException(filePath: String) :
    Exception("AXS file is not open: $filePath")

class AxsFileCorruptException(filePath: String, reason: String) :
    Exception("AXS file is corrupt at $filePath: $reason")

class AxsKeyNotFoundException(path: String) :
    Exception("Key not found in AXS file: $path")

class AxsTypeMismatchException(path: String, expected: String, actual: String) :
    Exception("Type mismatch at $path: expected $expected, got $actual")