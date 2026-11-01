package eu.kanade.tachiyomi.alldebrid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest

class TorrentMetadataReaderTest {
    private val info = "d6:lengthi12e4:name8:test.mkv12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaae"

    @Test
    fun readsSingleFileAndHashesExactOriginalInfoBytes() {
        val result = TorrentMetadataReader.read("d8:announce20:https://tracker.test4:info${info}e".toByteArray())
        assertEquals("test.mkv", result.name)
        assertEquals(listOf(TorrentMetadataFile("test.mkv", 1, 12)), result.files)
        assertEquals(MessageDigest.getInstance("SHA-1").digest(info.toByteArray()).joinToString("") { "%02x".format(it) }, result.hash)
    }

    @Test
    fun preservesAllFileIndicesIncludingTextFilesAndNestedPaths() {
        val multi = "d5:filesld6:lengthi2e4:pathl10:readme.txteed6:lengthi30e4:pathl6:Season7:one.mkveee4:name4:Show12:piece lengthi16384e6:pieces20:aaaaaaaaaaaaaaaaaaaae"
        val result = TorrentMetadataReader.read("d4:info${multi}e".toByteArray())
        assertEquals(listOf(TorrentMetadataFile("Show/readme.txt", 1, 2), TorrentMetadataFile("Show/Season/one.mkv", 2, 30)), result.files)
    }

    @Test
    fun rejectsTruncatedOversizedDuplicateAndUnsupportedMetadata() {
        listOf(
            "", "d4:info${info}", "d4:info${info}4:info${info}e", "d4:info${info}ee",
            "d4:infod6:lengthi-1e4:name8:test.mkv6:pieces20:aaaaaaaaaaaaaaaaaaaaee",
            "d4:infod6:lengthi1e4:name8:test.mkvee", // v2-only / missing v1 pieces
            "d4:infod4:name9999999999999999999999999999:xee",
            "d4:info" + "l".repeat(70) + "e".repeat(71),
        ).forEach { input ->
            assertEquals(AllDebridFailure.FILE, assertThrows(AllDebridException::class.java) { TorrentMetadataReader.read(input.toByteArray()) }.failure)
        }
        assertThrows(AllDebridException::class.java) { TorrentMetadataReader.read(ByteArray(AllDebridClient.MAX_TORRENT_BYTES + 1)) }
    }
}
