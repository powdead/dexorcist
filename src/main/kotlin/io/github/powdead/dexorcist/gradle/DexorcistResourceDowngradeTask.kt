package io.github.powdead.dexorcist.gradle

import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.variant.BuiltArtifact
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject

// Strips resources.arsc chunks incompatible with pre-Lollipop, re-signs.
@CacheableTask
abstract class DexorcistResourceDowngradeTask : DefaultTask() {

    companion object {
        const val RES_TABLE: Short = 0x0002
        const val RES_PKG: Short = 0x0200
        val STRIP = setOf<Short>(0x0203, 0x0204, 0x0205, 0x0206)
    }

    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val inputApkDir: DirectoryProperty
    @get:OutputDirectory
    abstract val outputApkDir: DirectoryProperty
    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<DexorcistResourceDowngradeTask>>
    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun downgrade() {
        transformationRequest.get().submit(this) { ba: BuiltArtifact ->
            val inf = File(ba.outputFile)
            val outf = File(outputApkDir.get().asFile, inf.name)
            processApk(inf, outf); outf
        }
    }

    private fun processApk(input: File, output: File) {
        val resData = ZipFile(input).use { z ->
            z.getEntry("resources.arsc")?.let { z.getInputStream(it).readBytes() }
                ?: return input.copyTo(output, overwrite = true).let {}
        }
        if (!hasStrippable(resData)) return input.copyTo(output, overwrite = true).let {}

        val fixed = stripTable(resData)
        val tmp = File(output.parentFile, "${output.nameWithoutExtension}-unsigned.apk")
        ZipFile(input).use { z ->
            ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                for (e in z.entries()) {
                    if (e.name.startsWith("META-INF/")) continue
                    val data = if (e.name == "resources.arsc") fixed else z.getInputStream(e).readBytes()
                    if (e.method == ZipEntry.STORED) writeStored(zos, e.name, data)
                    else { zos.putNextEntry(ZipEntry(e.name)); zos.write(data); zos.closeEntry() }
                }
            }
        }
        if (resign(tmp, output)) tmp.delete() else tmp.renameTo(output)
    }

    private fun writeStored(zos: ZipOutputStream, name: String, data: ByteArray) {
        val e = ZipEntry(name).apply {
            method = ZipEntry.STORED; size = data.size.toLong(); compressedSize = data.size.toLong()
            crc = CRC32().also { it.update(data) }.value
        }
        zos.putNextEntry(e); zos.write(data); zos.closeEntry()
    }

    private fun resign(unsigned: File, signed: File): Boolean {
        val ks = File(System.getProperty("user.home"), ".android/debug.keystore")
        if (!ks.exists()) return false
        val ext = if ("windows" in System.getProperty("os.name").lowercase()) ".exe" else ""
        val jh = File(System.getProperty("java.home"))
        val js = sequenceOf(jh.resolve("bin/jarsigner$ext"), jh.parentFile?.resolve("bin/jarsigner$ext"))
            .firstOrNull { it?.exists() == true } ?: return false
        execOps.exec { commandLine(js.absolutePath, "-keystore", ks.absolutePath,
            "-storepass", "android", "-keypass", "android", "-signedjar", signed.absolutePath,
            unsigned.absolutePath, "androiddebugkey") }
        return true
    }

    private fun hasStrippable(data: ByteArray): Boolean {
        if (data.size < 12) return false
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(0) != RES_TABLE) return false
        val hs = b.getShort(2).toInt() and 0xFFFF; val ts = b.getInt(4); var p = hs
        while (p + 8 <= ts && p + 8 <= data.size) {
            val ct = b.getShort(p); val cs = b.getInt(p + 4); if (cs < 8) break
            if (ct in STRIP) return true
            if (ct == RES_PKG && pkgHasStrippable(data, p, cs)) return true
            p = ((p + cs) + 3) and 3.inv()
        }
        return false
    }

    private fun pkgHasStrippable(data: ByteArray, off: Int, size: Int): Boolean {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val hs = b.getShort(off + 2).toInt() and 0xFFFF; var p = off + hs; val end = off + size
        while (p + 8 <= end && p + 8 <= data.size) {
            val ct = b.getShort(p); val cs = b.getInt(p + 4); if (cs < 8) break
            if (ct in STRIP) return true; p = ((p + cs) + 3) and 3.inv()
        }
        return false
    }

    internal fun stripTable(data: ByteArray): ByteArray {
        if (data.size < 12) return data
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(0) != RES_TABLE) return data
        val hs = b.getShort(2).toInt() and 0xFFFF; val ts = b.getInt(4)
        val out = ByteArrayOutputStream(data.size); out.write(data, 0, hs); var p = hs
        while (p + 8 <= ts && p + 8 <= data.size) {
            val ct = b.getShort(p); val cs = b.getInt(p + 4); if (cs < 8) break
            when {
                ct in STRIP -> {}
                ct == RES_PKG -> {
                    val pd = stripPkg(data.copyOfRange(p, p + cs))
                    out.write(pd); out.write(ByteArray((4 - pd.size % 4) % 4))
                    p = ((p + cs) + 3) and 3.inv(); continue
                }
                else -> { out.write(data, p, cs); out.write(ByteArray((4 - cs % 4) % 4)) }
            }
            p = ((p + cs) + 3) and 3.inv()
        }
        return out.toByteArray().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, it.size) }
    }

    private fun stripPkg(data: ByteArray): ByteArray {
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val hs = b.getShort(2).toInt() and 0xFFFF; val ts = b.getInt(4)
        val out = ByteArrayOutputStream(data.size); out.write(data, 0, hs); var p = hs
        while (p + 8 <= ts && p + 8 <= data.size) {
            val ct = b.getShort(p); val cs = b.getInt(p + 4); if (cs < 8) break
            if (ct !in STRIP) { out.write(data, p, cs); out.write(ByteArray((4 - cs % 4) % 4)) }
            p = ((p + cs) + 3) and 3.inv()
        }
        return out.toByteArray().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, it.size) }
    }
}
