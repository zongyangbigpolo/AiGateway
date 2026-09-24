package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

object GatewayGeoFiles {
    const val MAX_BYTES = 64L * 1024 * 1024
    val names = listOf(AppConfig.GEOSITE_DAT, AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT)

    fun validateName(name: String) {
        require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid asset filename." }
    }

    fun installMissing(directory: File, open: (String) -> InputStream) = locked(directory) {
        names.forEach { name ->
            val target = File(directory, name)
            if (!target.exists()) {
                open(name).use { replaceUnlocked(directory, name, it) }
            } else if (!target.isFile || target.length() == 0L) {
                throw IOException("Unusable rule file: $name. Import a valid replacement.")
            }
        }
    }

    fun replace(directory: File, name: String, input: InputStream) = locked(directory) {
        validateName(name)
        replaceUnlocked(directory, name, input)
    }

    fun commitDownload(candidate: File, target: File) = locked(target.parentFile ?: throw IOException("Missing asset directory")) {
        validateName(target.name)
        commitUnlocked(candidate, target)
    }

    private fun replaceUnlocked(directory: File, name: String, input: InputStream) {
        val candidate = File.createTempFile("gateway-", ".tmp", directory)
        try {
            candidate.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw IOException("Rule file exceeds 64 MiB.")
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            commitUnlocked(candidate, File(directory, name))
        } finally {
            candidate.delete()
        }
    }

    private fun commitUnlocked(candidate: File, target: File) {
        if (candidate.canonicalFile.parentFile != target.canonicalFile.parentFile) {
            throw IOException("Rule replacement must be staged in the same directory.")
        }
        validate(candidate, target.name)
        if (!candidate.renameTo(target)) throw IOException("Cannot replace ${target.name}. Previous file was kept.")
    }

    fun validate(file: File, name: String) {
        validateName(name)
        if (!file.isFile || file.length() !in 1..MAX_BYTES) throw IOException("Empty or oversized rule file: $name")
        if (name !in names) return
        val isIp = name != AppConfig.GEOSITE_DAT
        val required = if (isIp) setOf("cn", "private") else setOf("cn", "geolocation-!cn")
        val populated = mutableSetOf<String>()
        BufferedInputStream(file.inputStream()).use { input ->
            val reader = ProtoReader(input)
            val end = file.length()
            while (reader.position < end) {
                if (reader.varint(end) != 10L) throw IOException("Invalid Geo data container: $name")
                val entryEnd = reader.endOfField(end)
                var code: String? = null
                var hasRules = false
                while (reader.position < entryEnd) {
                    val tag = reader.varint(entryEnd)
                    when (tag) {
                        10L -> code = reader.text(entryEnd).lowercase()
                        18L -> {
                            validateRecord(reader, reader.endOfField(entryEnd), isIp)
                            hasRules = true
                        }
                        else -> reader.skipField(tag, entryEnd)
                    }
                }
                if (code.isNullOrBlank()) throw IOException("Geo entry has no category: $name")
                if (hasRules) populated.add(code)
            }
        }
        if (!populated.containsAll(required)) {
            throw IOException("$name is missing required categories: ${(required - populated).joinToString()}")
        }
    }

    private fun validateRecord(reader: ProtoReader, end: Long, isIp: Boolean) {
        var ipBytes = 0L
        var prefix = 0L
        var domain = false
        while (reader.position < end) {
            val tag = reader.varint(end)
            when {
                isIp && tag == 10L -> {
                    val fieldEnd = reader.endOfField(end)
                    ipBytes = fieldEnd - reader.position
                    if (ipBytes != 4L && ipBytes != 16L) throw IOException("Invalid GeoIP address")
                    reader.skip(ipBytes, end)
                }
                isIp && tag == 16L -> prefix = reader.varint(end)
                !isIp && tag == 8L -> {
                    if (reader.varint(end) !in 0..3) throw IOException("Invalid GeoSite domain type")
                }
                !isIp && tag == 18L -> {
                    val fieldEnd = reader.endOfField(end)
                    if (fieldEnd == reader.position) throw IOException("Empty GeoSite domain")
                    reader.skip(fieldEnd - reader.position, end)
                    domain = true
                }
                else -> reader.skipField(tag, end)
            }
        }
        if (isIp && (ipBytes == 0L || prefix !in 0..ipBytes * 8)) throw IOException("Invalid GeoIP prefix")
        if (!isIp && !domain) throw IOException("Invalid GeoSite record")
    }

    private fun <T> locked(directory: File, block: () -> T): T = synchronized(this) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create rule directory.")
        // The UI and VPN service may initialize files in different Android processes.
        RandomAccessFile(File(directory, ".gateway-geodata.lock"), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    private class ProtoReader(private val input: InputStream) {
        var position = 0L
            private set

        fun varint(limit: Long): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (position >= limit) throw EOFException("Truncated Geo data.")
                val byte = input.read()
                if (byte < 0) throw EOFException("Truncated Geo data.")
                position++
                if (shift == 63 && byte > 0) throw IOException("Oversized Geo data integer.")
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            throw IOException("Invalid Geo data integer.")
        }

        fun endOfField(limit: Long): Long {
            val size = varint(limit)
            if (size > limit - position) throw EOFException("Truncated Geo data field.")
            return position + size
        }

        fun text(limit: Long): String {
            val end = endOfField(limit)
            val size = end - position
            if (size !in 1..128) throw IOException("Invalid Geo category.")
            val result = StringBuilder()
            repeat(size.toInt()) {
                val byte = input.read()
                if (byte !in 33..126) throw IOException("Invalid Geo category.")
                position++
                result.append(byte.toChar())
            }
            return result.toString()
        }

        fun skip(count: Long, limit: Long) {
            if (count > limit - position) throw EOFException("Truncated Geo data field.")
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    position += skipped
                } else {
                    if (input.read() < 0) throw EOFException("Truncated Geo data field.")
                    remaining--
                    position++
                }
            }
        }

        fun skipField(tag: Long, limit: Long) {
            if (tag shr 3 == 0L) throw IOException("Invalid Geo data field.")
            when (tag and 7) {
                0L -> varint(limit)
                1L -> skip(8, limit)
                2L -> skip(endOfField(limit) - position, limit)
                5L -> skip(4, limit)
                else -> throw IOException("Invalid Geo data wire type.")
            }
        }
    }
}
