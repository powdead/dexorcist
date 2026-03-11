plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

group = "io.github.powdead.dexorcist"
version = "0.2.1"

repositories {
    google()
    mavenCentral()
}

dependencies {
    compileOnly("com.android.tools.build:gradle:8.9.0")
    compileOnly("com.android.tools.build:gradle-api:8.9.0")
    implementation(gradleApi())
}

gradlePlugin {
    website.set("https://github.com/powdead/dexorcist")
    vcsUrl.set("https://github.com/powdead/dexorcist")
    plugins {
        create("dexorcist") {
            id = "io.github.powdead.dexorcist"
            implementationClass = "io.github.powdead.dexorcist.gradle.DexorcistPlugin"
            displayName = "Dexorcist"
            description = "Gradle plugin that lets modern Android apps compile and run on Android 2.3 (API 9). Handles multidex, core library desugaring, API shimming, resource downgrading, and platform stub injection."
            tags.set(listOf("android", "compatibility", "desugaring", "legacy", "gingerbread"))
        }
    }
}
