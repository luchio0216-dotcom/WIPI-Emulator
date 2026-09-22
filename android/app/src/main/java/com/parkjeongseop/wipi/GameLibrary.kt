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
            val meta = JSONObject(metaFile.readText()); val filename = meta.getString("filename"); val gameFile = File(dir, filename)
            if (!gameFile.exists()) return null
            val cover = File(dir, "cover.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            GameEntry(dir.name, meta.getString("name"), cover, gameFile, filename, File(dir, "data"))
        } catch (_: Exception) { null }
    }

    fun importGame(uri: Uri): GameEntry? {
        val filename = queryDisplayName(uri) ?: uri.lastPathSegment ?: "game.zip"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val id = UUID.randomUUID().toString(); val dir = File(root, id).apply { mkdirs() }
        File(dir, filename).writeBytes(bytes)
        WipiNative.nativeGameIcon(bytes)?.let { File(dir, "cover.png").writeBytes(it) }
        val name = WipiNative.nativeGameName(bytes)?.toString(Charset.forName("EUC-KR")) ?: filename.substringBeforeLast('.')
        File(dir, "meta.json").writeText(JSONObject().put("name", name).put("filename", filename).toString())
        val entry = load(dir) ?: return null

        // 010100D5 ships ~2.10 MiB of ordinary post-download P/ files. Install
        // them immediately; otherwise the title treats the complete payload as
        // missing and shows the handset's ~2103 KB download/storage gate.
        if (packageAid(bytes) == "010100D5") {
            PDataImporter(context).importZip(entry, uri).getOrElse { dir.deleteRecursively(); return null }
        }
        return entry
    }

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

    private fun queryDisplayName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}
