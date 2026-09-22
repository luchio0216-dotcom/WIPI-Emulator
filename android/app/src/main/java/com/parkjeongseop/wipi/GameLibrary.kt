// 게임 라이브러리 — 임포트한 게임을 filesDir/games/<UUID>/에 영구 저장하고 관리.
package com.parkjeongseop.wipi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

data class GameEntry(
    val id: String,
    val name: String,
    val cover: Bitmap?,
    val gameFile: File,
    val filename: String,
    val dataDir: File,
)

class GameLibrary(private val context: Context) {
    private val root = File(context.filesDir, "games").apply { mkdirs() }
    private val contentResolver = context.contentResolver

    fun list(): List<GameEntry> =
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { load(it) }.sortedBy { it.name }

    private fun load(dir: File): GameEntry? {
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return null
        return try {
            val meta = JSONObject(metaFile.readText())
            val filename = meta.getString("filename")
            val gameFile = File(dir, filename)
            if (!gameFile.exists()) return null
            val cover = File(dir, "cover.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            GameEntry(dir.name, meta.getString("name"), cover, gameFile, filename, File(dir, "data"))
        } catch (_: Exception) { null }
    }

    /** SAF Uri에서 게임을 임포트 (복사 + 표지/이름 추출·캐시). */
    fun importGame(uri: Uri): GameEntry? {
        val filename = queryDisplayName(uri) ?: uri.lastPathSegment ?: "game.zip"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        File(dir, filename).writeBytes(bytes)
        WipiNative.nativeGameIcon(bytes)?.let { File(dir, "cover.png").writeBytes(it) }
        val name = WipiNative.nativeGameName(bytes)?.toString(Charset.forName("EUC-KR")) ?: filename.substringBeforeLast('.')
        File(dir, "meta.json").writeText(JSONObject().put("name", name).put("filename", filename).toString())

        val entry = load(dir) ?: return null

        // Inotia 2's P/ directory is not a set of classic KTF DB dumps. It is
        // ~2.10 MiB of post-download files. WIE does not expose archive P/
        // entries as persistent WIPI files by itself, so install them at import
        // time. Without this, the game sees the whole payload as missing and
        // immediately asks for about 2103 KB of storage/download space.
        if (containsAid(bytes, "010100D5")) {
            PDataImporter(context).importZip(entry, uri).getOrElse {
                dir.deleteRecursively()
                return null
            }
        }
        return entry
    }

    fun delete(entry: GameEntry) { File(root, entry.id).deleteRecursively() }

    private fun containsAid(zipBytes: ByteArray, aid: String): Boolean {
        // AID is ASCII in KTF __adf__. Scanning the small package byte array is
        // sufficient for this compatibility hook and avoids changing native metadata APIs.
        val needle = "AID:$aid".toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..zipBytes.size - needle.size) {
            for (j in needle.indices) if (zipBytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
