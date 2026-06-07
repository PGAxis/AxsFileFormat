<div align="center">

# What
The AXS file format (or Axis Xtensible Settings) is, as the name suggests, a file format made primarily for storing settings. It allows for single setting updates instead of rewriting the entire file to actually save.
It supports nested objects, arrays, and all common primitive types, making it suitable for both simple and complex settings structures.

# Why
As mentioned before, AXS was created due to lack of well-fitting file formats to store settings. That should be taken with a grain of salt, as I did about two Google searches before coming to that conclusion. Anyway, that is why I created the AXS file format - to finally have a way of storing settings in a meaningful way, without having to compromise on disk space (as one alternative would be to just store one setting per file).

# How to use

</div>

<div align="center"><h3>Importing the library</h3></div>

Add the following to your `build.gradle.kts`:

```kotlin
implementation("dev.pgaxis:axs:1.0.0")
```

or, when using `libs.version.toml`:

```toml
[versions]
axs = "1.0.0"
[libraries]
axs = { group = "dev.pgaxis", name = "axs", version.ref = "axs" }
```

<div align="center"><h3>Example</h3>
<p>Define your settings class and bind it to an AXS file:</p></div>

```kotlin
data class AppSettings(
  var darkMode: Boolean = true,
  var fontSize: Int = 14,
  var username: String = "Axis"
)
val file = AxsFile("settings.axs")
file.open()
val settings = file.bind(AppSettings())
// Read
val isDark = settings.getValue(AppSettings::darkMode)
// Write — automatically persisted to disk
settings.setValue(AppSettings::darkMode, false)
file.close()
```
