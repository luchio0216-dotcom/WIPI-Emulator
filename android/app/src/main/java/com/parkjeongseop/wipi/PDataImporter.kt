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
    val databaseFileCount: Int,
    val databaseRecordCount: Int,
    val totalBytes: Long,
)

data class FirstStageResult(
    val aid: String,
    val pid: String,
    val removedPFiles: Int,
)

/** Installer for old KTF/WIPI packages that shipped post-download data in P/. */
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
                            if (isP) removedPFiles++
                            else if (!isExeInfo && seen.add(relative)) {
                                val bytes = zip.readBytes()
                                out.putNextEntry(ZipEntry(relative)); out.write(bytes); out.closeEntry()
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
        entry.dataDir.deleteRecursively(); entry.dataDir.mkdirs(); entry.gameFile.writeBytes(stripped)
        FirstStageResult(meta.aid, meta.pid, removedPFiles)
    }

    /**
     * Install P/ payloads into WIE's persistent filesystem and, when a payload
     * is a classic KTF DB dump, also expand it into the database repository.
     *
     * Inotia 1 uses KTF DB dumps. Inotia 2 (010100D5) instead ships about
     * 2.10 MiB of ordinary post-download files; rejecting a package merely
     * because no DB dump was found leaves those files absent and makes the
     * title ask for roughly 2103 KB of download/storage space on every start.
     */
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
        var databaseFileCount = 0
        var databaseRecordCount = 0
        for ((relativePath, data) in files) {
            val fsDestination = safeChild(aidRoot, relativePath)
            fsDestination.parentFile?.let { check(it.mkdirs() || it.isDirectory) { "WIPI 하위 파일 폴더를 만들 수 없습니다." } }
            fsDestination.writeBytes(data)
            totalBytes += data.size

            val records = parseKtfDatabaseDump(data) ?: continue
            val dbDirectory = safeChild(dbRoot, relativePath)
            dbDirectory.deleteRecursively()
            check(dbDirectory.mkdirs() || dbDirectory.isDirectory) { "WIPI DB '$relativePath' 폴더를 만들 수 없습니다." }
            records.forEachIndexed { index, record -> File(dbDirectory, (index + 1).toString()).writeBytes(record) }
            databaseFileCount++
            databaseRecordCount += records.size
        }

        // Raw-only P packages are valid. Do not require a KTF DB dump here.
        PDataImportResult(meta.aid, meta.pid, files.size, databaseFileCount, databaseRecordCount, totalBytes)
    }

    private fun parseKtfDatabaseDump(data: ByteArray): List<ByteArray>? {
        if (data.size < 8) return null
        val count = u16be(data, 0)
        if (count <= 0 || count > 10000) return null
        val headerSize = 2 + count * 6
        if (headerSize > data.size) return null
        data class Descriptor(val offset: Int, val length: Int)
        val descriptors = ArrayList<Descriptor>(count)
        var cursor = 2
        repeat(count) {
            val offset = u24be(data, cursor); val length = u24be(data, cursor + 3); cursor += 6
            if (offset < headerSize || offset > data.size || length > data.size - offset) return null
            descriptors += Descriptor(offset, length)
        }
        if (descriptors.firstOrNull()?.offset != headerSize) return null
        for (i in 0 until descriptors.lastIndex) if (descriptors[i].offset + descriptors[i].length != descriptors[i + 1].offset) return null
        val payloadEnd = descriptors.last().let { it.offset + it.length }
        if (payloadEnd > data.size || data.size - payloadEnd !in 0..256) return null
        return descriptors.map { data.copyOfRange(it.offset, it.offset + it.length) }
    }

    private fun u16be(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
    private fun u24be(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xff) shl 16) or ((data[offset + 1].toInt() and 0xff) shl 8) or (data[offset + 2].toInt() and 0xff)
    private data class PackageMetadata(val aid: String, val pid: String)
    private fun readUri(uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("선택한 ZIP 파일을 읽을 수 없습니다.")

    private fun extractMetadata(zipBytes: ByteArray): PackageMetadata {
        val adf = findZipEntry(zipBytes) { it == "__adf__" || it.endsWith("/__adf__") } ?: error("__adf__를 찾을 수 없습니다.")
        val text = adf.toString(Charset.forName("EUC-KR"))
        fun field(name: String): String? = Regex("(?im)^\\s*${Regex.escape(name)}\\s*[:=]\\s*([A-Za-z0-9._-]+)\\s*$").find(text)?.groupValues?.getOrNull(1)?.trim()
        val aid = field("AID") ?: error("__adf__에서 AID를 찾을 수 없습니다.")
        val pid = field("PID") ?: error("__adf__에서 PID를 찾을 수 없습니다.")
        require(aid.matches(Regex("[A-Za-z0-9._-]+")) && pid.matches(Regex("[A-Za-z0-9._-]+"))) { "올바르지 않은 AID/PID입니다." }
        return PackageMetadata(aid, pid)
    }

    private fun findArchiveRootPrefix(zipBytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val normalized = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory && (normalized == "__adf__" || normalized.endsWith("/__adf__"))) return normalized.removeSuffix("__adf__")
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
                    if (relative != null && isSafeRelativePath(relative)) result[relative] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        return result
    }

    private fun pRelativePath(name: String): String? {
        if (name.startsWith("P/")) return name.removePrefix("P/").takeIf { it.isNotBlank() }
        if (name.startsWith("p/")) return name.removePrefix("p/").takeIf { it.isNotBlank() }
        for (marker in listOf("/P/", "/p/")) { val index = name.indexOf(marker); if (index >= 0) return name.substring(index + marker.length).takeIf { it.isNotBlank() } }
        return null
    }

    private fun safeChild(root: File, relativePath: String): File {
        require(isSafeRelativePath(relativePath)) { "위험한 파일 경로가 포함되어 있습니다." }
        val child = File(root, relativePath).canonicalFile
        require(child.path.startsWith(root.path + File.separator)) { "게임 데이터 폴더 밖의 경로는 사용할 수 없습니다." }
        return child
    }
    private fun isSafeRelativePath(path: String): Boolean = path.replace('\\', '/').split('/').let { it.isNotEmpty() && it.none { p -> p.isBlank() || p == "." || p == ".." } }
    private fun findZipEntry(zipBytes: ByteArray, predicate: (String) -> Boolean): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory) { val normalized = entry.name.replace('\\', '/').trimStart('/'); if (predicate(normalized)) return zip.readBytes() }
                zip.closeEntry()
            }
        }
    }
}
