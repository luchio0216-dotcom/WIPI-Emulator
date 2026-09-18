package com.parkjeongseop.wipi

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class PDataImportResult(
    val aid: String,
    val pid: String,
    val fileCount: Int,
    val databaseCount: Int,
    val totalBytes: Long,
)

data class FirstStageResult(
    val aid: String,
    val pid: String,
    val removedPFiles: Int,
)

/**
 * Two-stage installer for old KTF/WIPI packages that shipped post-download
 * databases in a P/ directory (for example Inotia 1 KTF).
 *
 * Stage 1 rewrites the installed archive without P/ and clears game data so
 * the title can perform its real first-run initialization.
 * Stage 2 preserves that first-run state, then installs every P/<name> both
 * as a persistent WIPI filesystem file and as DB record 1 under the package
 * PID. The latter mirrors wie's packaged-P behaviour, which seeds P/<name>
 * into database record 1 when the game opens that database.
 */
class PDataImporter(private val context: Context) {

    fun prepareFirstStage(entry: GameEntry, uri: Uri): Result<FirstStageResult> = runCatching {
        val packageBytes = readUri(uri)
        val meta = extractMetadata(packageBytes)
        val rootPrefix = findArchiveRootPrefix(packageBytes)

        var removedPFiles = 0
        val seen = mutableSetOf<String>()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { out ->
            ZipInputStream(ByteArrayInputStream(packageBytes)).use { zip ->
                while (true) {
                    val source = zip.nextEntry ?: break
                    if (!source.isDirectory) {
                        val normalized = source.name.replace('\\', '/').trimStart('/')
                        val relative = when {
                            rootPrefix.isEmpty() -> normalized
                            normalized.startsWith(rootPrefix) -> normalized.removePrefix(rootPrefix)
                            else -> ""
                        }

                        if (relative.isNotBlank() && isSafeRelativePath(relative)) {
                            val isP = relative.startsWith("P/") || relative.startsWith("p/")
                            val isExeInfo = relative.equals("exe_info", ignoreCase = true)
                            if (isP) {
                                removedPFiles++
                            } else if (!isExeInfo && seen.add(relative)) {
                                val bytes = zip.readBytes()
                                out.putNextEntry(ZipEntry(relative))
                                out.write(bytes)
                                out.closeEntry()
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        }

        val stripped = output.toByteArray()
        require(stripped.isNotEmpty()) { "1단계용 ZIP을 만들지 못했습니다." }
        require(findZipEntry(stripped) { it == "__adf__" } != null) { "1단계 ZIP에 __adf__가 없습니다." }
        require(removedPFiles > 0) { "선택한 ZIP에서 P 폴더 파일을 찾지 못했습니다." }

        // Old installation instructions explicitly start from a clean state.
        // Keep the library entry itself, but reset all per-game persistent state.
        entry.dataDir.deleteRecursively()
        entry.dataDir.mkdirs()
        entry.gameFile.writeBytes(stripped)

        FirstStageResult(meta.aid, meta.pid, removedPFiles)
    }

    fun importZip(entry: GameEntry, uri: Uri): Result<PDataImportResult> = runCatching {
        val packageBytes = readUri(uri)
        val meta = extractMetadata(packageBytes)
        val files = extractPFiles(packageBytes)
        require(files.isNotEmpty()) { "ZIP 안에서 P 폴더의 추가 데이터를 찾지 못했습니다." }

        val aidRoot = File(entry.dataDir, "fs/${meta.aid}").canonicalFile
        check(aidRoot.mkdirs() || aidRoot.isDirectory) { "WIPI 파일 저장 폴더를 만들 수 없습니다." }

        val dbRoot = File(entry.dataDir, "db/${meta.pid}").canonicalFile
        check(dbRoot.mkdirs() || dbRoot.isDirectory) { "WIPI DB 저장 폴더를 만들 수 없습니다." }

        var totalBytes = 0L
        var databaseCount = 0
        for ((relativePath, data) in files) {
            // 1) Persistent filesystem copy. FilesystemOverlay checks this layer
            // before archive-backed virtual files.
            val fsDestination = safeChild(aidRoot, relativePath)
            fsDestination.parentFile?.let { parent ->
                check(parent.mkdirs() || parent.isDirectory) { "WIPI 하위 파일 폴더를 만들 수 없습니다." }
            }
            fsDestination.writeBytes(data)

            // 2) Persistent DB record 1. KTF's database API uses PID as app_id,
            // and packaged P/<name> is represented as record 1 by the core.
            val dbDirectory = safeChild(dbRoot, relativePath)
            check(dbDirectory.mkdirs() || dbDirectory.isDirectory) { "WIPI DB '$relativePath' 폴더를 만들 수 없습니다." }
            File(dbDirectory, "1").writeBytes(data)
            databaseCount++

            totalBytes += data.size
        }

        PDataImportResult(
            aid = meta.aid,
            pid = meta.pid,
            fileCount = files.size,
            databaseCount = databaseCount,
            totalBytes = totalBytes,
        )
    }

    private data class PackageMetadata(val aid: String, val pid: String)

    private fun readUri(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("선택한 ZIP 파일을 읽을 수 없습니다.")

    private fun extractMetadata(zipBytes: ByteArray): PackageMetadata {
        val adf = findZipEntry(zipBytes) { name -> name == "__adf__" || name.endsWith("/__adf__") }
            ?: error("__adf__를 찾을 수 없습니다.")
        val text = adf.toString(Charset.forName("EUC-KR"))
        fun field(name: String): String? = Regex("(?im)^\\s*${Regex.escape(name)}\\s*[:=]\\s*([A-Za-z0-9._-]+)\\s*$")
            .find(text)?.groupValues?.getOrNull(1)?.trim()

        val aid = field("AID") ?: error("__adf__에서 AID를 찾을 수 없습니다.")
        val pid = field("PID") ?: error("__adf__에서 PID를 찾을 수 없습니다.")
        require(aid.matches(Regex("[A-Za-z0-9._-]+"))) { "올바르지 않은 AID입니다: $aid" }
        require(pid.matches(Regex("[A-Za-z0-9._-]+"))) { "올바르지 않은 PID입니다: $pid" }
        return PackageMetadata(aid, pid)
    }

    private fun findArchiveRootPrefix(zipBytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && (normalized == "__adf__" || normalized.endsWith("/__adf__"))) {
                    return normalized.removeSuffix("__adf__")
                }
                zip.closeEntry()
            }
        }
        error("__adf__를 찾을 수 없습니다.")
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
        if (name.startsWith("p/")) return name.removePrefix("p/").takeIf { it.isNotBlank() }
        for (marker in listOf("/P/", "/p/")) {
            val index = name.indexOf(marker)
            if (index >= 0) return name.substring(index + marker.length).takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun safeChild(root: File, relativePath: String): File {
        require(isSafeRelativePath(relativePath)) { "위험한 파일 경로가 포함되어 있습니다." }
        val child = File(root, relativePath).canonicalFile
        val prefix = root.path + File.separator
        require(child.path.startsWith(prefix)) { "게임 데이터 폴더 밖의 경로는 사용할 수 없습니다." }
        return child
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
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
