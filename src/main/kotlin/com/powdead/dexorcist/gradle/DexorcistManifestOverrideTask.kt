package com.powdead.dexorcist.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

// Scans deps, generates manifest with overrideLibrary + removal of incompatible components.
@CacheableTask
abstract class DexorcistManifestOverrideTask : DefaultTask() {

    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val dependencies: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputManifest: RegularFileProperty

    @TaskAction
    fun generate() {
        val pkgs = mutableSetOf<String>()
        dependencies.files.forEach { extractPkg(it)?.let { p -> pkgs.add(p) } }
        val sorted = pkgs.sorted()
        val hasStartup = "androidx.startup" in sorted
        val hasProfileInstaller = sorted.any { it.startsWith("androidx.profileinstaller") }

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("""<manifest xmlns:android="http://schemas.android.com/apk/res/android"""")
            appendLine("""    xmlns:tools="http://schemas.android.com/tools">""")
            if (sorted.isNotEmpty())
                appendLine("""    <uses-sdk tools:overrideLibrary="${sorted.joinToString(",")}" />""")
            appendLine("""    <application android:name="androidx.multidex.MultiDexApplication">""")
            if (hasStartup) {
                appendLine("""        <provider android:name="androidx.startup.InitializationProvider"""")
                appendLine("""            android:authorities="${"$"}{applicationId}.androidx-startup" tools:node="remove" />""")
            }
            if (hasProfileInstaller) {
                appendLine("""        <receiver android:name="androidx.profileinstaller.ProfileInstallReceiver"""")
                appendLine("""            android:exported="true" tools:node="remove" />""")
            }
            appendLine("""    </application>""")
            appendLine("""</manifest>""")
        }
        outputManifest.get().asFile.also { it.parentFile.mkdirs(); it.writeText(xml) }
    }

    private fun extractPkg(file: File): String? {
        if (!file.exists()) return null
        return when {
            file.name.endsWith(".aar") -> fromAar(file)
            file.isDirectory && File(file, "AndroidManifest.xml").exists() ->
                fromXml(File(file, "AndroidManifest.xml"))
            file.name == "AndroidManifest.xml" -> fromXml(file)
            else -> null
        }
    }

    private fun fromAar(f: File): String? = try {
        ZipFile(f).use { z ->
            z.getEntry("AndroidManifest.xml")?.let { e ->
                z.getInputStream(e).use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
                    .documentElement.getAttribute("package").takeIf { it.isNotEmpty() } }
            }
        }
    } catch (_: Exception) { null }

    private fun fromXml(f: File): String? = try {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            .documentElement.getAttribute("package").takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
}
