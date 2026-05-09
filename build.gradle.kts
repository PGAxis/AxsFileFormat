plugins {
  id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

val localProperties = java.util.Properties()
val localPropertiesFile = file("local.properties")
if (localPropertiesFile.exists()) {
  localProperties.load(localPropertiesFile.inputStream())
  localProperties.forEach { key, value ->
    ext.set(key.toString(), value.toString())
  }
}

nexusPublishing {
  repositories {
    sonatype {
      nexusUrl = uri("https://central.sonatype.com/api/v1/publisher/upload")
      snapshotRepositoryUrl = uri("https://central.sonatype.com/api/v1/publisher/upload")
      username = project.findProperty("mavenCentralUsername") as String?
      password = project.findProperty("mavenCentralPassword") as String?
    }
  }
}