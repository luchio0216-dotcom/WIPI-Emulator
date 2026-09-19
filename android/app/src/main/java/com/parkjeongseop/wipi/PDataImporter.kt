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

/**
 * Installer for old KTF/WIPI packages that shipped post-download data in P/.
 *
 * Important: the *.dat files used by titles such as Inotia 1 are not single
 * RMS records. They are KTF database dump files:
 *
 *   u16be recordCount
 *   repeated recordCount times: u24be offset + u24be length
 *   record payloads
 *   small handset-specific footer
 *
 * WIE's persistent DB backend stores one file per MIDP record (1, 2, ...),
 * while WIPI record ids are zero based. Therefore Stage 2 expands each KTF
 * dump into individual WIE records instead of writing the entire .dat blob as
 * record 1. Raw P files are also kept in the persistent filesystem for titles
 * that access them through the WIPI file API.
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

        // Reproduce the original clean-install step.
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
        var databaseFileCount = 0
        var databaseRecordCount = 0

        for ((relativePath, data) in files) {
            // Keep the original P file visible through WIE's persistent filesystem.
            val fsDestination = safeChild(aidRoot, relativePath)
            fsDestination.parentFile?.let { parent ->
                check(parent.mkdirs() || parent.isDirectory) { "WIPI 하위 파일 폴더를 만들 수 없습니다." }
            }
            fsDestination.writeBytes(data)
            totalBytes += data.size

            // KTF .dat database dumps contain many records. Expand the dump into
            // the layout used by FsDatabaseRepository: db/<PID>/<name>/<recordId>.
            val records = parseKtfDatabaseDump(data) ?: continue
            val dbDirectory = safeChild(dbRoot, relativePath)
            dbDirectory.deleteRecursively()
            check(dbDirectory.mkdirs() || dbDirectory.isDirectory) { "WIPI DB '$relativePath' 폴더를 만들 수 없습니다." }

            records.forEachIndexed { index, record ->
                // WIPI id 0 maps to MIDP/WIE record file 1.
                File(dbDirectory, (index + 1).toString()).writeBytes(record)
            }
            databaseFileCount++
            databaseRecordCount += records.size
        }

        require(databaseFileCount > 0 && databaseRecordCount > 0) {
            "P 폴더에서 KTF 데이터베이스 형식을 찾지 못했습니다."
        }

        PDataImportResult(
            aid = meta.aid,
            pid = meta.pid,
            fileCount = files.size,
            databaseFileCount = databaseFileCount,
            databaseRecordCount = databaseRecordCount,
            totalBytes = totalBytes,
        )
    }

    /**
     * Decode the legacy KTF DB dump format.
     *
     * In the Inotia 1 package this yields:
     * char.dat=6, map.dat=255, mon.dat=50, pattern.dat=129, tile.dat=703.
     * The five files contain 1,143 records in total. The common 44-byte tail is
     * handset DB metadata and is intentionally not exposed as a record.
     */
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
            val offset = u24be(data, cursor)
            val length = u24be(data, cursor + 3)
            cursor += 6
            if (offset < headerSize || length < 0 || offset > data.size || length > data.size - offset) return null
            descriptors += Descriptor(offset, length)
        }

        if (descriptors.firstOrNull()?.offset != headerSize) return null
        for (i in 0 until descriptors.lastIndex) {
            val current = descriptors[i]
            val next = descriptors[i + 1]
            if (current.offset + current.length != next.offset) return null
        }

        val payloadEnd = descriptors.last().let { it.offset + it.length }
        if (payloadEnd > data.size) return null

        // Real KTF DB dumps observed here carry a small footer after payloads.
        // Reject wildly different shapes so arbitrary P files (e.g. prefs) are
        // never mistaken for a database.
        val trailingBytes = data.size - payloadEnd
        if (trailingBytes !in 0..256) return null

        return descriptors.map { descriptor ->
            data.copyOfRange(descriptor.offset, descriptor.offset + descriptor.length)
        }
    }

    private fun u16be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun u24be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 16) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            (data[offset + 2].toInt() and 0xff)

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
