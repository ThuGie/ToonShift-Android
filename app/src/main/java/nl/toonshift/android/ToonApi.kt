package nl.toonshift.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

private val hex64 = Regex("[a-f0-9]{64}")
private val hex16 = Regex("[a-f0-9]{16}")
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun newSecret() = ByteArray(32).also(SecureRandom()::nextBytes).hex()

data class Pairing(val host: String, val pin: String, val secret: String, val id: String) {
    companion object {
        fun parse(text: String): Pairing {
            require(text.length <= 512)
            val input = JSONObject(text)
            require(input.getInt("v") == 1)
            val host = input.getString("host")
            require(privateAddress(host))
            val pin = input.getString("pin")
            val secret = input.getString("secret")
            val id = input.getString("id")
            require(hex64.matches(pin) && hex64.matches(secret) && hex16.matches(id))
            return Pairing(host, pin, secret, id)
        }
    }
}

internal fun privateAddress(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    val ip = parts.map { value ->
        val number = value.toIntOrNull() ?: return false
        if (number !in 0..255 || number.toString() != value) return false
        number
    }
    return ip[0] == 10 || ip[0] == 192 && ip[1] == 168 || ip[0] == 172 && ip[1] in 16..31
}

data class Toon(val id: String, val name: String, val host: String, val pin: String, val token: String, val zone: String)

class Vault(context: Context, filename: String = "paired-toons", private val alias: String = "toonshift-android-v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, filename))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    fun load(): List<Toon> {
        if (!file.baseFile.exists()) return emptyList()
        val encrypted = file.readFully()
        require(encrypted.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        val rows = JSONArray(String(cipher.doFinal(encrypted.copyOfRange(12, encrypted.size)), Charsets.UTF_8))
        return (0 until rows.length()).map { index ->
            val data = rows.getJSONObject(index)
            Toon(data.getString("id"), data.getString("name"), data.getString("host"),
                data.getString("pin"), data.getString("token"), data.getString("zone"))
        }
    }

    fun save(toons: List<Toon>) {
        val rows = JSONArray()
        toons.forEach { t ->
            rows.put(JSONObject().put("id", t.id).put("name", t.name).put("host", t.host)
                .put("pin", t.pin).put("token", t.token).put("zone", t.zone))
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(rows.toString().toByteArray())
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

class HttpFailure(val status: Int) : IOException("HTTP $status")

class ToonApi(private val host: String, fingerprint: String, private val token: String? = null) {
    init {
        require(privateAddress(host) && hex64.matches(fingerprint))
    }
    private val trust = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are unsupported")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            val certificate = chain?.singleOrNull() ?: throw CertificateException("Missing Toon certificate")
            certificate.checkValidity()
            if (!MessageDigest.isEqual(sha256(certificate.encoded).toByteArray(), fingerprint.toByteArray())) {
                throw CertificateException("Toon identity changed")
            }
        }
    }
    private val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    private val client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(16, TimeUnit.SECONDS)
        .build()

    private fun request(path: String, method: String, data: JSONObject? = null): Request =
        Request.Builder().url("https://$host:10083$path").apply {
            token?.let { header("Authorization", "Bearer $it") }
            method(method, data?.toString()?.toRequestBody("application/json".toMediaType()))
        }.build()

    suspend fun call(path: String, method: String = "GET", data: JSONObject? = null): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request(path, method, data))
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) =
                    continuation.resumeWith(Result.failure(e))

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching {
                        response.use {
                            if (!it.isSuccessful) throw HttpFailure(it.code)
                            it.body?.string().orEmpty()
                        }
                    })
                }
            })
        }

    fun states() = callbackFlow<JSONObject?> {
        val request = request("/v1/events", "GET")
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw HttpFailure(it.code)
                        val source = it.body?.source() ?: throw IOException("Missing event stream")
                        var event = ""
                        while (!source.exhausted()) {
                            val line = source.readUtf8LineStrict(16_384)
                            if (line.startsWith("event: ")) event = line.substringAfter("event: ")
                            if (line.startsWith("data: ")) {
                                if (event == "status") trySend(JSONObject(line.substringAfter("data: ")))
                                if (event == "unavailable") trySend(null)
                                event = ""
                            }
                            if (line.startsWith(":")) trySend(JSONObject())
                        }
                    }
                    close()
                } catch (error: Exception) { close(error) }
            }
        })
        awaitClose { call.cancel() }
    }
}

internal data class Slot(val start: Int, val end: Int, val preset: Int) {
    fun toJson() = JSONObject().put("start", start).put("end", end).put("preset", preset)
    companion object {
        fun parse(data: JSONObject) = Slot(data.getInt("start"), data.getInt("end"), data.getInt("preset"))
    }
}
