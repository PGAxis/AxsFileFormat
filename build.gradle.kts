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
<<<<<<< HEAD
      nexusUrl = uri("https://central.sonatype.com/api/v1/publisher/upload")
      snapshotRepositoryUrl = uri("https://central.sonatype.com/api/v1/publisher/upload")
=======
      nexusUrl = uri("https://s01.oss.sonatype.org/service/local/")
      snapshotRepositoryUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
>>>>>>> 4c40b4b101800d898bf2b3b91d1331ba0d8a34fa
      username = project.findProperty("mavenCentralUsername") as String?
      password = project.findProperty("mavenCentralPassword") as String?
    }
  }
}