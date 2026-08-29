package eu.kanade.tachiyomi.extension.ar.dilar

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class EncryptedEnvelope(
    val v: Int = 4,
    val e: Long = 0L,
    val epk: String = "",
    val iv: String = "",
    val ct: String = "",
    val tag: String = "",
)

class DilarCrypto(private val json: Json) {

    private val ecSpec: ECParameterSpec by lazy {
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        params.getParameterSpec(ECParameterSpec::class.java)
    }

    private val keyFactory: KeyFactory by lazy {
        KeyFactory.getInstance("EC")
    }

    private var currentKeyPair: KeyPair = generateKeyPair()
    var clientPublicB64: String = encodePublicKey(currentKeyPair.public as ECPublicKey)
        private set

    fun regenerateKey() {
        currentKeyPair = generateKeyPair()
        clientPublicB64 = encodePublicKey(currentKeyPair.public as ECPublicKey)
    }

    fun isEncrypted(body: String): Boolean = body.contains("\"epk\"") && body.contains("\"ct\"") && body.contains("\"tag\"")

    fun decryptResponse(body: String): String = try {
        val envelope = json.decodeFromString<EncryptedEnvelope>(body)
        decryptEnvelope(envelope)
    } catch (e: Exception) {
        body
    }

    fun decryptEnvelope(envelope: EncryptedEnvelope): String {
        val epkRaw = base64UrlDecode(envelope.epk)
        val ivRaw = base64UrlDecode(envelope.iv)
        val ctRaw = base64UrlDecode(envelope.ct)
        val tagRaw = base64UrlDecode(envelope.tag)

        val serverPub = importPublicKey(epkRaw)
        val sharedSecret = computeSharedSecret(currentKeyPair.private, serverPub)

        val clientPubRaw = exportRawPublicKey(currentKeyPair.public as ECPublicKey)

        val salt: ByteArray
        val info: ByteArray

        when (envelope.v) {
            4 -> {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(clientPubRaw)
                md.update(epkRaw)
                md.update(ivRaw)
                salt = md.digest()
                val ivB64 = base64UrlEncode(ivRaw)
                val infoStr = "dilar.response.ecies.v4|${envelope.e}|$ivB64"
                info = infoStr.toByteArray(StandardCharsets.UTF_8)
            }
            3 -> {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(epkRaw)
                md.update(clientPubRaw)
                salt = md.digest()
                info = "dilar.response.ecies.v3|${envelope.e}".toByteArray(StandardCharsets.UTF_8)
            }
            2 -> {
                salt = epkRaw + clientPubRaw
                info = "dilar.response.ecies.v2|${envelope.e}".toByteArray(StandardCharsets.UTF_8)
            }
            else -> {
                salt = clientPubRaw + epkRaw
                info = "dilar.response.ecies.v1|${envelope.e}".toByteArray(StandardCharsets.UTF_8)
            }
        }

        val aesKey = hkdf(sharedSecret, salt, info, 32)

        val cipherWithTag = ByteArray(ctRaw.size + tagRaw.size)
        System.arraycopy(ctRaw, 0, cipherWithTag, 0, ctRaw.size)
        System.arraycopy(tagRaw, 0, cipherWithTag, ctRaw.size, tagRaw.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivRaw)
        val keySpec = SecretKeySpec(aesKey, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        val plaintext = cipher.doFinal(cipherWithTag)
        return String(plaintext, StandardCharsets.UTF_8)
    }

    private fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    private fun exportRawPublicKey(pub: ECPublicKey): ByteArray {
        val x = toFixedLength(pub.w.affineX.toByteArray(), 32)
        val y = toFixedLength(pub.w.affineY.toByteArray(), 32)
        val raw = ByteArray(65)
        raw[0] = 0x04
        System.arraycopy(x, 0, raw, 1, 32)
        System.arraycopy(y, 0, raw, 33, 32)
        return raw
    }

    private fun encodePublicKey(pub: ECPublicKey): String {
        val raw = exportRawPublicKey(pub)
        return base64UrlEncode(raw)
    }

    private fun importPublicKey(raw: ByteArray): PublicKey {
        if (raw.size != 65 || raw[0] != 0x04.toByte()) {
            throw IllegalArgumentException("Invalid uncompressed EC public key")
        }
        val xBytes = Arrays.copyOfRange(raw, 1, 33)
        val yBytes = Arrays.copyOfRange(raw, 33, 65)
        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val point = ECPoint(x, y)
        val pubSpec = ECPublicKeySpec(point, ecSpec)
        return keyFactory.generatePublic(pubSpec)
    }

    private fun computeSharedSecret(privateKey: PrivateKey, serverPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(serverPublicKey, true)
        return ka.generateSecret()
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltSpec = SecretKeySpec(salt, "HmacSHA256")
        mac.init(saltSpec)
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        val t = mac.doFinal()
        return Arrays.copyOf(t, length)
    }

    private fun toFixedLength(src: ByteArray, length: Int): ByteArray {
        if (src.size == length) return src
        val out = ByteArray(length)
        if (src.size > length) {
            System.arraycopy(src, src.size - length, out, 0, length)
        } else {
            System.arraycopy(src, 0, out, length - src.size, src.size)
        }
        return out
    }

    private fun base64UrlEncode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun base64UrlDecode(str: String): ByteArray = Base64.decode(str, Base64.URL_SAFE)
}
