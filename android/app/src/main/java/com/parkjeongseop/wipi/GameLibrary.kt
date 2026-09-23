// 게임 라이브러리 — 임포트한 게임을 filesDir/games/<UUID>/에 영구 저장하고 관리.
package com.parkjeongseop.wipi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream

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

            // Builds before the Inotia 2 startup fix kept the outer handset
            // distribution ZIP as the executable. WIE cannot execute that wrapper
            // ("Unknown archive format"); the actual KTF program is 010100D5.jar
            // inside it. Migrate such entries in-place and restore P/ at the same
            // time so users do not need to delete/re-import the game.
            val stored = gameFile.readBytes()
            if (packageAid(stored) == "010100D5") {
                val jar = extractEmbeddedJar(stored, "010100D5") ?: return null
                val source = File(dir, "inotia2-source.zip")
                if (!source.exists()) source.writeBytes(stored)
                val jarFile = File(dir, "010100D5.jar").apply { writeBytes(jar) }
                val migrated = GameEntry(dir.name, meta.getString("name"), null, jarFile, jarFile.name, File(dir, "data"))
                PDataImporter(context).importZip(migrated, Uri.fromFile(source)).getOrElse { return null }
                filename = jarFile.name
                gameFile = jarFile
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
            // The public Inotia 2 package is an installation wrapper, not a WIE
            // executable archive. Preserve it privately for P-data/migration, but
            // launch the embedded KTF JAR just like the original handset did.
            val jar = extractEmbeddedJar(bytes, aid) ?: run { dir.deleteRecursively(); return null }
            val source = File(dir, "inotia2-source.zip").apply { writeBytes(bytes) }
            executableFilename = "010100D5.jar"
            File(dir, executableFilename).writeBytes(jar)
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
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && (name == "__adf__" || name.endsWith("/__adf__"))) {
                    val text = zip.readBytes().toString(Charset.forName("EUC-KR"))
                    return Regex("(?im)^\\s*AID\\s*[:=]\\s*([A-Za-z0-9._-]+)\\s*$").find(text)?.groupValues?.getOrNull(1)?.trim()
                }
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

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
