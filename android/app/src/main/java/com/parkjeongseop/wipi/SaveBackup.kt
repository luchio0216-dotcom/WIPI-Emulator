package com.parkjeongseop.wipi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipInputStream

/** W-Feature-compatible .wfs save backup reader/writer (SavePack v1). */
object SaveBackup {
    private const val MAGIC = "WFSAVEBK"
    private const val VERSION = 1
    private const val HEADER_SIZE = 50
    private const val MAX_PAYLOAD = 64 * 1024 * 1024

    data class Entry(val key: String, val data: ByteArray)
    data class ImportResult(val entryCount: Int, val totalBytes: Int)

    class FormatException(message: String) : Exception(message)
    class WrongGameException : Exception("이 세이브 파일은 선택한 게임용이 아닙니다.")
    class UnsupportedSaveException(message: String) : Exception(message)

    fun exportKtf(game: GameEntry): ByteArray {
        val archive = game.gameFile.readBytes()
        val identity = archiveIdentity(archive)
        val meta = readKtfArchiveMeta(archive)
        val entries = linkedMapOf<String, ByteArray>()

        val removed = mutableListOf<String>()
        val dbRoot = File(game.dataDir, "db/${meta.pid}")
        if (dbRoot.exists()) {
            dbRoot.walkTopDown()
                .filter { it.isDirectory && it != dbRoot }
                .forEach { dir ->
                    val children = dir.listFiles()?.toList().orEmpty()
                    val subdirs = children.filter { it.isDirectory }
                    if (subdirs.isNotEmpty()) return@forEach
                    val rel = dir.relativeTo(dbRoot).invariantSeparatorsPath
                    validateRelativeName(rel)
                    val files = children.filter { it.isFile }
                    if (files.isEmpty()) {
                        // Empty leaf directories are used as tombstones so packaged data does not re-seed.
                        removed += rel
                        return@forEach
                    }
                    if (files.any { it.name != "1" } || files.size != 1) {
                        throw UnsupportedSaveException("$rel 데이터베이스는 W-Feature 스트림 세이브로 안전하게 변환할 수 없습니다.")
                    }
                    val data = files.single().readBytes()
                    // Packaged P/ data is part of the game archive, not player progress. Only export an override.
                    if (!meta.packaged[rel].contentEqualsNullable(data)) {
                        entries["db/$rel"] = data
                    }
                }
        }
        entries["db/.removed"] = removed.distinct().sorted().joinToString("\n").toByteArray(Charsets.UTF_8)

        val fsRoot = File(game.dataDir, "fs/${meta.aid}")
        if (fsRoot.exists()) {
            fsRoot.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(fsRoot).invariantSeparatorsPath
                validateRelativeName(rel)
                entries["fs/$rel"] = file.readBytes()
            }
        }

        return encode(identity, entries.map { Entry(it.key, it.value) })
    }

    fun importKtf(game: GameEntry, wfsBytes: ByteArray): ImportResult {
        val archive = game.gameFile.readBytes()
        val meta = readKtfArchiveMeta(archive)
        val pack = decode(wfsBytes, archiveIdentity(archive))

        val dbEntries = linkedMapOf<String, ByteArray>()
        val fsEntries = linkedMapOf<String, ByteArray>()
        var removedBytes = ByteArray(0)
        for (entry in pack) {
            when {
                entry.key == "db/.removed" -> removedBytes = entry.data
                entry.key.startsWith("db/") -> {
                    val name = entry.key.removePrefix("db/")
                    validateRelativeName(name)
                    dbEntries[name] = entry.data
                }
                entry.key.startsWith("fs/") -> {
                    val name = entry.key.removePrefix("fs/")
                    validateRelativeName(name)
                    fsEntries[name] = entry.data
                }
                else -> throw UnsupportedSaveException("아직 지원하지 않는 WFS 저장 영역입니다: ${entry.key.substringBefore('/')}")
            }
        }

        val removed = decodeRemovalList(removedBytes)
        if (removed.any { it in dbEntries }) {
            throw FormatException("삭제 목록과 저장 데이터에 같은 이름이 중복되어 있습니다.")
        }

        // Restore is destructive. Keep an on-device rollback copy until every write succeeds.
        val dataDir = game.dataDir
        val parent = dataDir.parentFile ?: throw FormatException("게임 저장 경로가 올바르지 않습니다.")
        val backup = File(parent, ".${dataDir.name}.wfs-backup-${UUID.randomUUID()}")
        if (backup.exists()) backup.deleteRecursively()
        if (dataDir.exists() && !dataDir.copyRecursively(backup, overwrite = true)) {
            throw FormatException("기존 세이브의 안전 백업을 만들지 못했습니다.")
        }

        try {
            // Replace the W-Feature save tree. Packaged P/ files are re-seeded from the game archive on demand.
            File(dataDir, "db/${meta.pid}").deleteRecursively()
            File(dataDir, "fs/${meta.aid}").deleteRecursively()

            val dbRoot = File(dataDir, "db/${meta.pid}").apply { mkdirs() }
            for ((name, data) in dbEntries) {
                val dir = safeChild(dbRoot, name).apply { mkdirs() }
                File(dir, "1").writeBytes(data)
            }
            // W-Feature's db/.removed is a newline-separated name ledger. An empty WIE DB directory
            // acts as a tombstone because the repository exists and packaged record 1 is not seeded again.
            for (name in removed) {
                safeChild(dbRoot, name).mkdirs()
            }

            val fsRoot = File(dataDir, "fs/${meta.aid}")
            for ((name, data) in fsEntries) {
                val file = safeChild(fsRoot, name)
                file.parentFile?.mkdirs()
                file.writeBytes(data)
            }

            backup.deleteRecursively()
        } catch (t: Throwable) {
            dataDir.deleteRecursively()
            if (backup.exists()) backup.copyRecursively(dataDir, overwrite = true)
            backup.deleteRecursively()
            throw t
        }

        return ImportResult(pack.size, pack.sumOf { it.data.size })
    }

    fun decodeForTest(wfsBytes: ByteArray, archiveBytes: ByteArray): List<Entry> =
        decode(wfsBytes, archiveIdentity(archiveBytes))

    private data class KtfArchiveMeta(
        val pid: String,
        val aid: String,
        val packaged: Map<String, ByteArray>,
    )

    private fun readKtfArchiveMeta(archive: ByteArray): KtfArchiveMeta {
        var adf: ByteArray? = null
        val packaged = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/').trimStart('/')
                if (!entry.isDirectory) {
                    val data = zip.readBytes()
                    if (name == "__adf__") adf = data
                    if (name.startsWith("P/") && name.length > 2) {
                        val dbName = name.removePrefix("P/")
                        validateRelativeName(dbName)
                        packaged[dbName] = data
                    }
                }
                zip.closeEntry()
            }
        }
        val adfText = adf?.toString(Charsets.ISO_8859_1)
            ?: throw UnsupportedSaveException("KTF __adf__ 정보를 찾을 수 없습니다.")
        val pid = Regex("(?m)^PID:([^\\r\\n]+)").find(adfText)?.groupValues?.get(1)?.trim()
            ?: throw UnsupportedSaveException("KTF PID를 찾을 수 없습니다.")
        val aid = Regex("(?m)^AID:([^\\r\\n]+)").find(adfText)?.groupValues?.get(1)?.trim()
            ?: throw UnsupportedSaveException("KTF AID를 찾을 수 없습니다.")
        return KtfArchiveMeta(pid, aid, packaged)
    }

    private fun encode(identity: ByteArray, sourceEntries: List<Entry>): ByteArray {
        require(identity.size == 32)
        val entries = sourceEntries.sortedWith { a, b -> compareUtf8(a.key, b.key) }
        val payload = ByteArrayOutputStream()
        var previous: String? = null
        for (entry in entries) {
            validateKey(entry.key)
            if (entry.key == previous) throw FormatException("세이브 항목 이름이 중복되었습니다: ${entry.key}")
            previous = entry.key
            val key = entry.key.toByteArray(Charsets.UTF_8)
            if (key.size > 0xffff) throw FormatException("세이브 항목 이름이 너무 깁니다.")
            writeU16LE(payload, key.size)
            payload.write(key)
            writeU32LE(payload, entry.data.size.toLong())
            payload.write(entry.data)
        }
        val payloadBytes = payload.toByteArray()
        if (payloadBytes.size > MAX_PAYLOAD) throw FormatException("세이브 백업이 너무 큽니다.")
        val crc = CRC32().apply { update(payloadBytes) }.value
        return ByteArrayOutputStream(HEADER_SIZE + payloadBytes.size).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(VERSION)
            write(0)
            write(identity)
            writeU32LE(this, payloadBytes.size.toLong())
            writeU32LE(this, crc)
            write(payloadBytes)
        }.toByteArray()
    }

    private fun decode(container: ByteArray, expectedIdentity: ByteArray): List<Entry> {
        if (container.size < HEADER_SIZE || !container.copyOfRange(0, 8).contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
            throw FormatException("W-Feature .wfs 세이브 파일이 아닙니다.")
        }
        if (container[8].toInt() and 0xff != VERSION || container[9].toInt() != 0) {
            throw FormatException("이 버전에서 읽을 수 없는 .wfs 형식입니다.")
        }
        val identity = container.copyOfRange(10, 42)
        if (!identity.contentEquals(expectedIdentity)) throw WrongGameException()
        val length = readU32LE(container, 42)
        val checksum = readU32LE(container, 46)
        if (length > MAX_PAYLOAD.toLong() || length != (container.size - HEADER_SIZE).toLong()) {
            throw FormatException("세이브 백업 길이가 올바르지 않습니다.")
        }
        val payload = container.copyOfRange(HEADER_SIZE, container.size)
        val actualCrc = CRC32().apply { update(payload) }.value
        if (actualCrc != checksum) throw FormatException("세이브 백업이 손상되었습니다(CRC 불일치).")

        val entries = mutableListOf<Entry>()
        var offset = 0
        var previous: String? = null
        while (offset < payload.size) {
            if (offset + 2 > payload.size) throw FormatException("세이브 항목 헤더가 잘렸습니다.")
            val keyLen = readU16LE(payload, offset); offset += 2
            if (offset + keyLen + 4 > payload.size) throw FormatException("세이브 항목 이름이 잘렸습니다.")
            val keyBytes = payload.copyOfRange(offset, offset + keyLen); offset += keyLen
            val key = keyBytes.toString(Charsets.UTF_8)
            if (!key.toByteArray(Charsets.UTF_8).contentEquals(keyBytes)) throw FormatException("세이브 항목 이름 인코딩이 올바르지 않습니다.")
            validateKey(key)
            val dataLen = readU32LE(payload, offset); offset += 4
            if (dataLen > Int.MAX_VALUE.toLong() || offset + dataLen.toInt() > payload.size) throw FormatException("세이브 항목 데이터가 잘렸습니다.")
            val data = payload.copyOfRange(offset, offset + dataLen.toInt()); offset += dataLen.toInt()
            previous?.let {
                if (compareUtf8(it, key) >= 0) throw FormatException("세이브 항목 순서 또는 중복이 올바르지 않습니다.")
            }
            previous = key
            entries += Entry(key, data)
        }
        return entries
    }

    private fun decodeRemovalList(data: ByteArray): Set<String> {
        if (data.isEmpty()) return emptySet()
        val text = data.toString(Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(data)) throw FormatException("삭제 목록 인코딩이 올바르지 않습니다.")
        return text.split('\n').filter { it.isNotEmpty() }.onEach(::validateRelativeName).toSet()
    }

    private fun validateKey(key: String) {
        if (key.isEmpty() || key.toByteArray(Charsets.UTF_8).size > 512) throw FormatException("세이브 항목 이름이 올바르지 않습니다.")
        if (key.startsWith('/') || key.endsWith('/') || '\\' in key || '\u0000' in key) throw FormatException("안전하지 않은 세이브 경로입니다: $key")
        key.split('/').forEach { if (it.isEmpty() || it == "." || it == "..") throw FormatException("안전하지 않은 세이브 경로입니다: $key") }
    }

    private fun validateRelativeName(name: String) = validateKey(name)

    private fun safeChild(root: File, relative: String): File {
        validateRelativeName(relative)
        val child = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        if (!childPath.startsWith(rootPath)) throw FormatException("안전하지 않은 세이브 경로입니다: $relative")
        return child
    }

    private fun archiveIdentity(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray?.contentEqualsNullable(other: ByteArray): Boolean = this != null && this.contentEquals(other)

    private fun compareUtf8(left: String, right: String): Int {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a[i].toInt() and 0xff
            val bi = b[i].toInt() and 0xff
            if (ai != bi) return ai - bi
        }
        return a.size - b.size
    }

    private fun writeU16LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff); out.write((value ushr 8) and 0xff)
    }
    private fun writeU32LE(out: ByteArrayOutputStream, value: Long) {
        repeat(4) { shift -> out.write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }
    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    private fun readU32LE(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 0xff) shl (i * 8)) }
}
