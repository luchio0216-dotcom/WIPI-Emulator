package com.parkjeongseop.wipi

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

data class PDataImportResult(
    val aid: String,
    val fileCount: Int,
    val totalBytes: Long,
)

/**
 * Imports legacy KTF/WIPI post-download data stored in a package's P/ folder.
 *
 * The mobile core persists WIPI filesystem files at:
 *   <game dataDir>/fs/<AID>/<virtual path>
 *
 * This importer accepts either a normal package where P/ is at the ZIP root or
 * an archive wrapped in one extra directory (common in old backup collections).
 */
class PDataImporter(private val context: Context) {

    fun importZip(entry: GameEntry, uri: Uri): Result<PDataImportResult> = runCatching {
        val packageBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("선택한 ZIP 파일을 읽을 수 없습니다.")

        // Prefer the selected data package's AID, then fall back to the installed game package.
        val aid = extractAid(packageBytes)
            ?: extractAid(entry.gameFile.readBytes())
            ?: error("__adf__에서 AID를 찾을 수 없습니다.")

        require(aid.matches(Regex("[A-Za-z0-9._-]+"))) { "올바르지 않은 AID입니다: $aid" }

        val files = extractPFiles(packageBytes)
        require(files.isNotEmpty()) { "ZIP 안에서 P 폴더의 추가 데이터를 찾지 못했습니다." }

        val aidRoot = File(entry.dataDir, "fs/$aid").canonicalFile
        check(aidRoot.mkdirs() || aidRoot.isDirectory) { "게임 데이터 폴더를 만들 수 없습니다." }

        var totalBytes = 0L
        for ((relativePath, data) in files) {
            val destination = File(aidRoot, relativePath).canonicalFile
            val rootPrefix = aidRoot.path + File.separator
            require(destination.path.startsWith(rootPrefix)) { "위험한 파일 경로가 포함되어 있습니다." }
            destination.parentFile?.let { parent ->
                check(parent.mkdirs() || parent.isDirectory) { "하위 데이터 폴더를 만들 수 없습니다." }
            }
            destination.writeBytes(data)
            totalBytes += data.size
        }

        PDataImportResult(aid = aid, fileCount = files.size, totalBytes = totalBytes)
    }

    private fun extractAid(zipBytes: ByteArray): String? {
        val adf = findZipEntry(zipBytes) { name ->
            name == "__adf__" || name.endsWith("/__adf__")
        } ?: return null

        val text = adf.toString(Charset.forName("EUC-KR"))
        return Regex("(?im)^\\s*AID\\s*[:=]\\s*([A-Za-z0-9._-]+)\\s*$")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun extractPFiles(zipBytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/').trimStart('/')
                    val relative = pRelativePath(normalized)
                    if (relative != null && isSafeRelativePath(relative)) {
                        result[relative] = zip.readBytes()
                    }
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun pRelativePath(name: String): String? {
        if (name.startsWith("P/")) return name.removePrefix("P/").takeIf { it.isNotBlank() }
        val marker = "/P/"
        val index = name.indexOf(marker)
        if (index >= 0) return name.substring(index + marker.length).takeIf { it.isNotBlank() }
        return null
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val parts = path.split('/')
        return parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." }
    }

    private fun findZipEntry(zipBytes: ByteArray, predicate: (String) -> Boolean): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/').trimStart('/')
                    if (predicate(normalized)) return zip.readBytes()
                }
                zip.closeEntry()
            }
        }
    }
}
