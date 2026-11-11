package io.github.kkwans.fileway.tv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class TvUpdateProtocolTest {
    @Test fun signedChannelsKeepTheSameApkVersionName() {
        val result = directory(
            envelope("a".repeat(32), manifest("stable", 8)),
            envelope("b".repeat(32), manifest("preview", 9)),
        )
        assertEquals(listOf(TvUpdateChannel.STABLE, TvUpdateChannel.PREVIEW), result.offers.map { it.manifest.channel })
        assertEquals(listOf("0.5.0", "0.5.0"), result.offers.map { it.manifest.versionName })
        assertFalse(result.rejected)
    }

    @Test fun changingSignedBytesOrUsingADifferentSigningKeyRejectsTheOffer() {
        val raw = manifest("stable", 8)
        val tampered = envelope("a".repeat(32), raw).put("manifestBase64", encode(raw + byteArrayOf(32)))
        val differentSigner = envelope("b".repeat(32), raw, keyPair())
        val result = directory(tampered, differentSigner)
        assertTrue(result.offers.isEmpty())
        assertTrue(result.rejected)
    }

    @Test fun incompleteDirectoryKeepsVerifiedOffersAndDoesNotClaimLatest() {
        val response = JSONObject().put("offers", JSONArray(listOf(envelope("a".repeat(32), manifest("stable", 8)))))
            .put("incomplete", true)
        val device = TvUpdateDevice(7, 28, setOf("x86"))
        val result = tvUpdateOffers(response, device, signer.public, TV_UPDATE_SIGNER)
        assertEquals(1, result.offers.size)
        assertTrue(result.rejected)
        val empty = tvUpdateOffers(JSONObject().put("offers", JSONArray()).put("incomplete", true), device, signer.public, TV_UPDATE_SIGNER)
        assertTrue(empty.offers.isEmpty())
        assertTrue(empty.rejected)
        assertThrows(IllegalArgumentException::class.java) {
            tvUpdateOffers(response.put("incomplete", "true"), device, signer.public, TV_UPDATE_SIGNER)
        }
    }

    @Test fun rejectsIncompatibleApiAbiAndVersions() {
        val needsNewApi = envelope("a".repeat(32), manifest("stable", 8) { it.put("minSdk", 29) })
        val needsArm = envelope("b".repeat(32), manifest("preview", 9) { it.put("abis", JSONArray(listOf("arm64-v8a"))) })
        assertTrue(directory(needsNewApi, needsArm).offers.isEmpty())
        val sameVersion = envelope("a".repeat(32), manifest("stable", 7))
        val olderVersion = envelope("b".repeat(32), manifest("preview", 6))
        assertTrue(directory(sameVersion, olderVersion).offers.isEmpty())
    }

    @Test fun rejectsDuplicateChannelAndHandle() {
        val stable = envelope("a".repeat(32), manifest("stable", 8))
        assertTrue(directory(stable, envelope("b".repeat(32), manifest("stable", 9))).offers.isEmpty())
        assertTrue(directory(stable, envelope("a".repeat(32), manifest("preview", 9))).offers.isEmpty())
    }

    @Test fun rejectsUnboundedUnknownAndMismatchedFields() {
        val badFields = listOf<(JSONObject) -> Unit>(
            { it.put("applicationId", "io.github.kkwans.nasfilebrowser") },
            { it.put("signerSha256", "0".repeat(64)) },
            { it.put("size", TV_UPDATE_MAX_BYTES + 1) },
            { it.put("versionCode", "8") },
            { it.put("apkName", "../Fileway-TV-0.5.0.apk") },
            { it.put("versionName", "0.5.1") },
            { it.put("extra", true) },
        )
        badFields.forEach { edit ->
            val result = directory(envelope("a".repeat(32), manifest("stable", 8, edit)))
            assertTrue(result.rejected)
            assertTrue(result.offers.isEmpty())
        }
        val duplicate = manifest("stable", 8).toString(Charsets.UTF_8).dropLast(1) + ",\"notes\":\"repeat\"}"
        assertTrue(directory(envelope("a".repeat(32), duplicate.toByteArray(Charsets.UTF_8))).rejected)
    }

    @Test fun validatesActualServerProgressAndContentIdentity() {
        val update = directory(envelope("a".repeat(32), manifest("stable", 8))).offers.single().manifest
        val id = "b".repeat(32)
        val status = JSONObject().put("downloadId", id).put("state", "preparing")
            .put("receivedBytes", 10).put("totalBytes", 100)
        assertEquals(TvUpdateRemoteStatus(id, false, 10, 100), tvUpdateRemoteStatus(status, update))
        assertThrows(IllegalArgumentException::class.java) { tvUpdateRemoteStatus(status, update, "a".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { tvUpdateRemoteStatus(status.put("state", "ready"), update) }
        assertTrue(tvUpdateRemoteStatus(status.put("receivedBytes", 100), update).ready)
        assertThrows(IllegalArgumentException::class.java) { tvUpdateRemoteStatus(status.put("totalBytes", 90), update) }
        status.put("state", "failed").put("code", "UPDATE_EXPIRED")
        assertTrue(assertThrows(TvUpdateError::class.java) { tvUpdateRemoteStatus(status, update) }.recheck)
    }

    @Test fun onlyAnExactPartialResponseCanAppendBytes() {
        val update = directory(envelope("a".repeat(32), manifest("stable", 8))).offers.single().manifest
        assertEquals(32L, tvUpdateWriteOffset(206, update.etag, 68, "bytes 32-99/100", 32, update))
        assertEquals(0L, tvUpdateWriteOffset(200, update.etag, 100, null, 32, update))
        listOf("bytes 31-99/100", "bytes 32-99/101", "bytes 32-98/100", null).forEach { range ->
            assertThrows(IllegalArgumentException::class.java) { tvUpdateWriteOffset(206, update.etag, 68, range, 32, update) }
        }
        assertThrows(IllegalArgumentException::class.java) { tvUpdateWriteOffset(206, "W/${update.etag}", 68, "bytes 32-99/100", 32, update) }
        assertThrows(IllegalArgumentException::class.java) { tvUpdateWriteOffset(206, update.etag, 100, "bytes 32-99/100", 32, update) }
    }

    private fun directory(vararg offers: JSONObject) = tvUpdateOffers(
        JSONObject().put("offers", JSONArray(offers.toList())), TvUpdateDevice(7, 28, setOf("x86")),
        signer.public, TV_UPDATE_SIGNER,
    )

    private fun manifest(channel: String, code: Int, edit: (JSONObject) -> Unit = {}): ByteArray =
        JSONObject().put("schema", 1).put("product", "android-tv").put("applicationId", TV_UPDATE_PACKAGE)
            .put("tag", "android-tv-0.5.0").put("channel", channel).put("versionName", "0.5.0")
            .put("versionCode", code).put("minSdk", 28).put("abis", JSONArray(listOf("x86")))
            .put("apkName", "Fileway-TV-0.5.0.apk").put("size", 100).put("sha256", "b".repeat(64))
            .put("signerSha256", TV_UPDATE_SIGNER).put("sourceRevision", "a".repeat(40))
            .put("notes", "修复播放问题\n新增在线更新").also(edit).toString().toByteArray(Charsets.UTF_8)

    private fun envelope(id: String, raw: ByteArray, key: java.security.KeyPair = signer): JSONObject {
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key.private)
            update(TV_UPDATE_DOMAIN.toByteArray(Charsets.US_ASCII))
            update(raw)
            sign()
        }
        return JSONObject().put("offerId", id).put("manifestBase64", encode(raw)).put("signatureBase64", encode(signature))
    }

    private fun encode(value: ByteArray) = Base64.getEncoder().encodeToString(value)

    private companion object {
        fun keyPair(): java.security.KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val signer = keyPair()
    }
}
