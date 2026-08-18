pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenLocal()
        mavenCentral()
        google()

        maven("https://jitpack.io/")
        maven("https://maven.fabricmc.net")

        maven("https://maven.architectury.dev/")
        maven("https://repo.essential.gg/repository/maven-public")
        maven("https://maven.minecraftforge.net")
        maven("https://repo.plasmoverse.com/releases")
        maven("https://repo.plasmoverse.com/snapshots")
    }

    plugins {
        val egtVersion = "0.8.5-SNAPSHOT"
        id("gg.essential.defaults") version egtVersion
        id("gg.essential.multi-version.root") version egtVersion
    }
}

rootProject.name = "PlasmoVoice"

include("protocol")

include("api:common")
include("api:client")
include("api:server-proxy-common")
include("api:server")

include("common")
include("server-proxy-common")
include("server:common")

include("client")
project(":client").apply {
    projectDir = file("client/")
    buildFileName = "root.gradle.kts"
}

listOf(
    "1.19.4-fabric",
    "1.19.4-forge",
    "1.20.1-forge",
    "1.20.4-forge",
    "1.21.1-forge",
    "1.21.1-fabric"
).forEach {
    include("client:$it")
    project(":client:$it").apply {
        projectDir = file("client/$it")
        buildFileName = "../build.gradle.kts"
    }
}
