// 게임 라이브러리 — 임포트한 게임을 filesDir/games/<UUID>/에 영구 저장하고 관리.
package com.parkjeongseop.wipi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class GameEntry(val id: String, val name: String, val cover: Bitmap?, val gameFile: File, val filename: String, val dataDir: File)

class GameLibrary(private val context: Context) {
    private val root = File(context.filesDir, "games").apply { mkdirs() }
    private val contentResolver = context.contentResolver

    fun list(): List<GameEntry> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(it) }.sortedBy { it.name }

    private fun load(dir: File): GameEntry? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            var filename = meta.getString("filename")
            var gameFile = File(dir, filename)
            if (!gameFile.exists()) return null

            // Older builds kept the public handset distribution ZIP as the executable.
            // Migrate it to a clean KTF runtime archive. The embedded JAR is still the
            // actual program, while __adf__ supplies PID/AID/MClass required by WIE.
            val stored = gameFile.readBytes()
            if (filename != "010100D5-runtime.zip" && packageAid(stored) == "010100D5") {
                val runtime = buildInotia2RuntimeArchive(stored) ?: return null
                val source = File(dir, "inotia2-source.zip")
                if (!source.exists()) source.writeBytes(stored)
                val runtimeFile = File(dir, "010100D5-runtime.zip").apply { writeBytes(runtime) }
                val migrated = GameEntry(dir.name, meta.getString("name"), null, runtimeFile, runtimeFile.name, File(dir, "data"))
                PDataImporter(context).importZip(migrated, Uri.fromFile(source)).getOrElse { return null }
                filename = runtimeFile.name
                gameFile = runtimeFile
                meta.put("filename", filename).put("sourcePackage", source.name)
                metaFile.writeText(meta.toString())
            }

            val cover = File(dir, "cover.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            GameEntry(dir.name, meta.getString("name"), cover, gameFile, filename, File(dir, "data"))
        } catch (_: Exception) { null }
    }

    fun importGame(uri: Uri): GameEntry? {
        val originalFilename = queryDisplayName(uri) ?: uri.lastPathSegment ?: "game.zip"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val aid = packageAid(bytes)
        val id = UUID.randomUUID().toString(); val dir = File(root, id).apply { mkdirs() }

        WipiNative.nativeGameIcon(bytes)?.let { File(dir, "cover.png").writeBytes(it) }
        val name = WipiNative.nativeGameName(bytes)?.toString(Charset.forName("EUC-KR")) ?: originalFilename.substringBeforeLast('.')

        val executableFilename: String
        if (aid == "010100D5") {
            // KTF execution needs both the embedded 010100D5.jar and the outer __adf__.
            // Running only the JAR loses MClass/PID and WIE terminates with
            // "Main class not found". Build a minimal private runtime archive from the
            // exact original descriptor + JAR; P/ remains installed in guest storage.
            val runtime = buildInotia2RuntimeArchive(bytes) ?: run { dir.deleteRecursively(); return null }
            val source = File(dir, "inotia2-source.zip").apply { writeBytes(bytes) }
            executableFilename = "010100D5-runtime.zip"
            File(dir, executableFilename).writeBytes(runtime)
            File(dir, "meta.json").writeText(JSONObject().put("name", name).put("filename", executableFilename).put("sourcePackage", source.name).toString())
            val entry = loadWithoutMigration(dir) ?: run { dir.deleteRecursively(); return null }
            PDataImporter(context).importZip(entry, Uri.fromFile(source)).getOrElse { dir.deleteRecursively(); return null }
            return entry
        }

        executableFilename = originalFilename
        File(dir, executableFilename).writeBytes(bytes)
        File(dir, "meta.json").writeText(JSONObject().put("name", name).put("filename", executableFilename).toString())
        return loadWithoutMigration(dir)
    }

    private fun loadWithoutMigration(dir: File): GameEntry? = try {
        val meta = JSONObject(File(dir, "meta.json").readText())
        val filename = meta.getString("filename")
        val gameFile = File(dir, filename)
        if (!gameFile.exists()) null else GameEntry(dir.name, meta.getString("name"), File(dir, "cover.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }, gameFile, filename, File(dir, "data"))
    } catch (_: Exception) { null }

    fun delete(entry: GameEntry) { File(root, entry.id).deleteRecursively() }

    private fun packageAid(zipBytes: ByteArray): String? {
        val adf = extractEntry(zipBytes, "__adf__") ?: return null
        val text = adf.toString(Charset.forName("EUC-KR"))
        return Regex("(?im)^\\s*AID\\s*[:=]\\s*([A-Za-z0-9._-]+)\\s*$").find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun extractEntry(zipBytes: ByteArray, wanted: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && (name.equals(wanted, true) || name.endsWith("/$wanted", true))) return zip.readBytes()
                zip.closeEntry()
            }
        }
    }

    private fun extractEmbeddedJar(zipBytes: ByteArray, aid: String): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            var fallback: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: return fallback
                if (!entry.isDirectory) {
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    if (name.endsWith("/$aid.jar", true) || name.equals("$aid.jar", true)) return zip.readBytes()
                    if (fallback == null && name.endsWith(".jar", true)) fallback = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
    }

    private fun buildInotia2RuntimeArchive(source: ByteArray): ByteArray? {
        val jar = extractEmbeddedJar(source, "010100D5") ?: return null
        val adf = extractEntry(source, "__adf__") ?: return null
        return ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("__adf__")); zip.write(adf); zip.closeEntry()
                zip.putNextEntry(ZipEntry("010100D5.jar")); zip.write(jar); zip.closeEntry()
            }
            out.toByteArray()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
