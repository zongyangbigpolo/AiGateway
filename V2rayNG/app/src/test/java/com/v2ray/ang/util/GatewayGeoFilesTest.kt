package com.v2ray.ang.util

import com.v2ray.ang.AppConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class GatewayGeoFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun bundledGitHubSnapshotValidatesAndInstallsWithoutNetwork() {
        val bundled = File("../../.native-build/geodata")
        val target = temp.newFolder()
        GatewayGeoFiles.installMissing(target) { name -> File(bundled, name).inputStream() }
        GatewayGeoFiles.names.forEach { name ->
            assertEquals(File(bundled, name).length(), File(target, name).length())
            GatewayGeoFiles.validate(File(target, name), name)
        }
        GatewayGeoFiles.installMissing(target) { throw AssertionError("Existing data must not be overwritten") }
    }

    @Test
    fun userReplacementSurvivesSubsequentInitialization() {
        val target = temp.newFolder()
        val data = geosite("custom.example")
        GatewayGeoFiles.replace(target, AppConfig.GEOSITE_DAT, ByteArrayInputStream(data))
        val opened = mutableListOf<String>()
        GatewayGeoFiles.installMissing(target) { name ->
            opened.add(name)
            ByteArrayInputStream(geoip())
        }
        assertEquals(listOf(AppConfig.GEOIP_DAT, AppConfig.GEOIP_ONLY_CN_PRIVATE_DAT), opened)
        assertArrayEquals(data, File(target, AppConfig.GEOSITE_DAT).readBytes())
    }

    @Test
    fun rejectsHtmlTruncationMissingCategoriesAndWrongSchemaWithoutReplacingOldData() {
        val directory = temp.newFolder()
        val original = geosite("old.example")
        GatewayGeoFiles.replace(directory, AppConfig.GEOSITE_DAT, ByteArrayInputStream(original))
        val invalid = listOf(
            byteArrayOf(),
            "<html>GitHub error</html>".toByteArray(),
            original.copyOf(original.size - 1),
            field(1, field(1, "CN".toByteArray())),
            geoip(),
            byteArrayOf(10, 127, 10, 2, 67, 78)
        )
        invalid.forEach { content ->
            assertThrows(IOException::class.java) {
                GatewayGeoFiles.replace(directory, AppConfig.GEOSITE_DAT, ByteArrayInputStream(content))
            }
            assertArrayEquals(original, File(directory, AppConfig.GEOSITE_DAT).readBytes())
        }
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "tmp" })
    }

    @Test
    fun rejectsInvalidGeoIpPrefix() {
        val directory = temp.newFolder()
        assertThrows(IOException::class.java) {
            GatewayGeoFiles.replace(directory, AppConfig.GEOIP_DAT, ByteArrayInputStream(geoip(33)))
        }
        assertFalse(File(directory, AppConfig.GEOIP_DAT).exists())
    }

    @Test
    fun interruptedReadKeepsCurrentFile() {
        val directory = temp.newFolder()
        val current = File(directory, "custom.dat").apply { writeText("previous") }
        val failing = object : java.io.InputStream() {
            override fun read(): Int = throw IOException("Connection interrupted")
        }
        assertThrows(IOException::class.java) { GatewayGeoFiles.replace(directory, current.name, failing) }
        assertEquals("previous", current.readText())
    }

    @Test
    fun rejectsUnsafeFilenamesAndOversizedDownloads() {
        listOf("../geosite.dat", "/tmp/geoip.dat", "a\\b.dat", ".", "..", "a:b", "").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { GatewayGeoFiles.validateName(name) }
        }
        val directory = temp.newFolder()
        val candidate = File(directory, "download.tmp")
        RandomAccessFile(candidate, "rw").use { it.setLength(GatewayGeoFiles.MAX_BYTES + 1) }
        val current = File(directory, "custom.dat").apply { writeText("previous") }
        assertThrows(IOException::class.java) { GatewayGeoFiles.commitDownload(candidate, current) }
        assertEquals("previous", current.readText())
    }

    @Test
    fun commitRequiresSameDirectoryAndKeepsExistingTarget() {
        val target = File(temp.newFolder(), "custom.dat").apply { writeText("previous") }
        val candidate = temp.newFile().apply { writeText("next") }
        assertThrows(IOException::class.java) { GatewayGeoFiles.commitDownload(candidate, target) }
        assertEquals("previous", target.readText())
    }

    private fun geosite(domain: String) = listOf("CN", "GEOLOCATION-!CN").fold(byteArrayOf()) { data, code ->
        data + field(1, field(1, code.toByteArray()) + field(2, field(2, domain.toByteArray())))
    }

    private fun geoip(prefix: Int = 8) = listOf("CN", "PRIVATE").fold(byteArrayOf()) { data, code ->
        val cidr = field(1, byteArrayOf(10, 0, 0, 0)) + byteArrayOf(16, prefix.toByte())
        data + field(1, field(1, code.toByteArray()) + field(2, cidr))
    }

    private fun field(number: Int, data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(number * 8 + 2)
        var size = data.size
        while (size >= 128) {
            output.write((size and 127) or 128)
            size = size ushr 7
        }
        output.write(size)
        output.write(data)
        return output.toByteArray()
    }
}
