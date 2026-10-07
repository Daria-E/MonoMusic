package com.monoapps.monomusic.tidal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TidalJsonTest {

    @Test
    fun parsesTrack() {
        val json = JSONObject(
            """
            {
              "id": 77646170,
              "title": "Alright",
              "duration": 219,
              "trackNumber": 7,
              "volumeNumber": 1,
              "explicit": true,
              "audioQuality": "LOSSLESS",
              "artist": {"id": 3566984, "name": "Kendrick Lamar"},
              "album": {"id": 77646164, "title": "To Pimp A Butterfly"}
            }
            """
        )
        val track = TidalJson.parseTrack(json)!!
        assertEquals(77646170L, track.id)
        assertEquals("Alright", track.title)
        assertEquals("Kendrick Lamar", track.artistName)
        assertEquals(3566984L, track.artistId)
        assertEquals("To Pimp A Butterfly", track.albumTitle)
        assertEquals(77646164L, track.albumId)
        assertEquals(219, track.durationSec)
        assertEquals(7, track.trackNumber)
        assertTrue(track.explicit)
        assertEquals("LOSSLESS", track.audioQuality)
    }

    @Test
    fun rejectsTrackWithoutId() {
        assertNull(TidalJson.parseTrack(JSONObject("""{"title": "No id"}""")))
    }

    @Test
    fun parsesAlbumWithReleaseYear() {
        val json = JSONObject(
            """
            {
              "id": 77646164,
              "title": "To Pimp A Butterfly",
              "numberOfTracks": 16,
              "releaseDate": "2015-03-16",
              "artist": {"id": 3566984, "name": "Kendrick Lamar"}
            }
            """
        )
        val album = TidalJson.parseAlbum(json)!!
        assertEquals("2015", album.releaseYear)
        assertEquals(16, album.numberOfTracks)
        assertEquals("Kendrick Lamar", album.artistName)
    }

    @Test
    fun editorialPlaylistIsCreditedToTidal() {
        val json = JSONObject(
            """
            {
              "uuid": "7ce9dcf8-1a0d-4e0d-a9a8-3e4bd5b7a0f1",
              "title": "RAP: The Takeover",
              "numberOfTracks": 100,
              "description": "",
              "type": "EDITORIAL"
            }
            """
        )
        val playlist = TidalJson.parsePlaylist(json)!!
        assertEquals("TIDAL", playlist.creator)
        assertNull(playlist.description)
    }

    @Test
    fun userPlaylistUsesCreatorName() {
        val json = JSONObject(
            """
            {
              "uuid": "aaaa-bbbb",
              "title": "Mine",
              "numberOfTracks": 3,
              "type": "USER",
              "creator": {"name": "beri"}
            }
            """
        )
        assertEquals("beri", TidalJson.parsePlaylist(json)!!.creator)
    }

    @Test
    fun explicitJsonNullsParseAsAbsent() {
        // Android's org.json turns JSONObject.NULL into the string "null"
        // via optString; parsing must treat explicit nulls as absent.
        val album = TidalJson.parseAlbum(
            JSONObject(
                """
                {
                  "id": 1,
                  "title": "X",
                  "releaseDate": null,
                  "audioQuality": null,
                  "artist": {"id": 2, "name": "Y"}
                }
                """
            )
        )!!
        assertNull(album.releaseYear)
        assertNull(album.audioQuality)

        val playlist = TidalJson.parsePlaylist(
            JSONObject("""{"uuid": "u", "title": "T", "description": null, "type": "USER"}""")
        )!!
        assertNull(playlist.description)
        assertNull(playlist.creator)
    }

    @Test
    fun parsesBtsManifest() {
        val info = TidalJson.parseBtsManifest(
            """
            {
              "mimeType": "audio/flac",
              "codecs": "flac",
              "encryptionType": "NONE",
              "urls": ["https://sp-pr-cf.audio.tidal.com/mediatracks/abc/0.flac"]
            }
            """,
            deliveredQuality = "LOSSLESS",
        )
        assertEquals("https://sp-pr-cf.audio.tidal.com/mediatracks/abc/0.flac", info.url)
        assertEquals("flac", info.codec)
        assertEquals("LOSSLESS", info.audioQuality)
    }

    @Test(expected = TidalApiException::class)
    fun rejectsEncryptedManifest() {
        TidalJson.parseBtsManifest(
            """{"encryptionType": "OLD_AES", "keyId": "x", "urls": ["https://x"]}""",
            deliveredQuality = "HIGH",
        )
    }

    @Test(expected = TidalApiException::class)
    fun rejectsManifestWithoutUrls() {
        TidalJson.parseBtsManifest(
            """{"encryptionType": "NONE", "urls": []}""",
            deliveredQuality = "HIGH",
        )
    }
}
