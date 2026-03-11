package io.github.powdead.dexorcist.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

// Generates stubs for platform classes missing on old Android (e.g. AutoCloseable).
@CacheableTask
abstract class DexorcistPlatformStubsTask : DefaultTask() {

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun generate() {
        val jar = output.get().asFile
        jar.parentFile.mkdirs()
        JarOutputStream(jar.outputStream()).use { jos ->
            jos.putNextEntry(JarEntry("java/lang/AutoCloseable.class"))
            jos.write(generateAutoCloseable())
            jos.closeEntry()
        }
    }

    // AutoCloseable (API 19) — needed by j$.util.stream.BaseStream
    private fun generateAutoCloseable(): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V1_6,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE,
            "java/lang/AutoCloseable", null, "java/lang/Object", null)
        cw.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT,
            "close", "()V", null, arrayOf("java/lang/Exception"))?.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
