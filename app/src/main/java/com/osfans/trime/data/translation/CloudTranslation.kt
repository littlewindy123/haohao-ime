/*
 * SPDX-FileCopyrightText: 2026 HaoHao IME contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.translation

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import com.osfans.trime.BuildConfig
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.util.appContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

internal const val TRANSLATION_SENTENCE_MAX_CODE_POINTS = 200
internal const val TRANSLATION_CANDIDATE_MAX_CODE_POINTS = 32
internal const val TRANSLATION_REQUEST_TIMEOUT_MS = 6_000
internal const val TRANSLATION_CONNECT_TIMEOUT_MS = 2_500
internal const val TRANSLATION_READ_TIMEOUT_MS = 5_000

enum class CloudTranslationProviderType {
    HAOHAO,
    ALIYUN,
    CUSTOM,
}

internal enum class TranslationPurpose {
    SENTENCE,
    CANDIDATE,
}

internal data class CloudTranslationRequest(
    val texts: List<String>,
    val purpose: TranslationPurpose,
    val sourceLanguage: String = "zh",
    val targetLanguage: String = "en",
)

internal sealed interface CloudTranslationResult {
    data class Success(val translations: List<String>) : CloudTranslationResult

    data class Failure(
        val kind: Kind,
        val message: String? = null,
    ) : CloudTranslationResult {
        enum class Kind {
            NOT_CONFIGURED,
            CONSENT_REQUIRED,
            UNSUPPORTED_DEVICE,
            INVALID_REQUEST,
            NETWORK,
            AUTHENTICATION,
            RATE_LIMITED,
            QUOTA_EXCEEDED,
            CONFIGURATION_EXPIRED,
            UPSTREAM,
            INVALID_RESPONSE,
        }
    }
}

internal fun interface CloudTranslationProvider {
    suspend fun translate(request: CloudTranslationRequest): CloudTranslationResult
}

internal data class TranslationHttpRequest(
    val url: String,
    val body: ByteArray,
    val contentType: String,
    val headers: Map<String, String> = emptyMap(),
)

internal data class TranslationHttpResponse(
    val statusCode: Int,
    val body: String,
)

internal fun interface TranslationHttpTransport {
    suspend fun post(request: TranslationHttpRequest): TranslationHttpResponse
}

internal object UrlConnectionTranslationTransport : TranslationHttpTransport {
    override suspend fun post(request: TranslationHttpRequest): TranslationHttpResponse = suspendCancellableCoroutine { continuation ->
        val connection = URL(request.url).openConnection() as HttpURLConnection
        // A blocking read must not delay cancellation of the editor's coroutine. Disconnect
        // on IO as some Android implementations can also block while closing a socket.
        continuation.invokeOnCancellation {
            Dispatchers.IO.dispatch(continuation.context, Runnable { connection.disconnect() })
        }
        Dispatchers.IO.dispatch(
            continuation.context,
            Runnable {
                try {
                    continuation.context.ensureActive()
                    connection.requestMethod = "POST"
                    connection.connectTimeout = TRANSLATION_CONNECT_TIMEOUT_MS
                    connection.readTimeout = TRANSLATION_READ_TIMEOUT_MS
                    connection.doOutput = true
                    connection.useCaches = false
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("Content-Type", request.contentType)
                    for ((name, value) in request.headers) {
                        connection.setRequestProperty(name, value)
                    }
                    connection.outputStream.use { it.write(request.body) }
                    continuation.context.ensureActive()
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { reader ->
                        val buffer = CharArray(2048)
                        buildString {
                            while (true) {
                                continuation.context.ensureActive()
                                val count = reader.read(buffer)
                                if (count < 0) break
                                require(length + count <= 65_536) { "Translation response exceeds size limit" }
                                append(buffer, 0, count)
                            }
                        }
                    }.orEmpty()
                    continuation.resumeWith(Result.success(TranslationHttpResponse(status, body)))
                } catch (failure: Exception) {
                    continuation.resumeWith(Result.failure(failure))
                } finally {
                    connection.disconnect()
                }
            },
        )
    }
}

@Serializable
private data class GatewayRequest(
    val texts: List<String>,
    @SerialName("source_lang") val sourceLanguage: String,
    @SerialName("target_lang") val targetLanguage: String,
    val purpose: String,
    @SerialName("request_id") val requestId: String,
)

@Serializable
private data class GatewayResponse(
    val translations: List<String> = emptyList(),
)

internal class HaoHaoTranslationProvider(
    private val baseUrl: String,
    private val installId: String,
    private val transport: TranslationHttpTransport = UrlConnectionTranslationTransport,
    private val allowLoopbackHttp: Boolean = BuildConfig.DEBUG,
) : CloudTranslationProvider {
    override suspend fun translate(request: CloudTranslationRequest): CloudTranslationResult {
        if (!isAllowedTranslationEndpoint(baseUrl, allowLoopbackHttp)) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
        }
        val normalized = normalizeTranslationRequest(request) ?: return CloudTranslationResult.Failure(
            CloudTranslationResult.Failure.Kind.INVALID_REQUEST,
        )
        val payload = GatewayRequest(
            texts = normalized.texts,
            sourceLanguage = normalized.sourceLanguage,
            targetLanguage = normalized.targetLanguage,
            purpose = normalized.purpose.name.lowercase(),
            requestId = UUID.randomUUID().toString(),
        )
        return try {
            transport.post(
                TranslationHttpRequest(
                    url = baseUrl.trimEnd('/') + "/v1/translate",
                    body = JSON.encodeToString(GatewayRequest.serializer(), payload).toByteArray(StandardCharsets.UTF_8),
                    contentType = "application/json; charset=utf-8",
                    headers = mapOf("X-HaoHao-Install" to installId),
                ),
            ).let { response ->
                when (response.statusCode) {
                    in 200..299 -> parseGatewayResponse(response.body, normalized.texts.size)
                    401, 403 -> CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.AUTHENTICATION)
                    429 -> CloudTranslationResult.Failure(
                        if (response.body.contains("MONTHLY_QUOTA", ignoreCase = true)) {
                            CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED
                        } else {
                            CloudTranslationResult.Failure.Kind.RATE_LIMITED
                        },
                    )
                    in 400..499 -> CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
                    else -> CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.UPSTREAM)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK)
        }
    }
}

internal class CustomTranslationProvider(
    private val endpoint: String,
    private val bearerToken: String?,
    private val transport: TranslationHttpTransport = UrlConnectionTranslationTransport,
    private val allowLoopbackHttp: Boolean = BuildConfig.DEBUG,
) : CloudTranslationProvider {
    override suspend fun translate(request: CloudTranslationRequest): CloudTranslationResult {
        if (!isAllowedTranslationEndpoint(endpoint, allowLoopbackHttp)) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
        }
        val normalized = normalizeTranslationRequest(request) ?: return CloudTranslationResult.Failure(
            CloudTranslationResult.Failure.Kind.INVALID_REQUEST,
        )
        val translations = mutableListOf<String>()
        for (text in normalized.texts) {
            coroutineContext.ensureActive()
            val body = JSON.encodeToString(
                JsonObject.serializer(),
                JsonObject(
                    mapOf(
                        "text" to kotlinx.serialization.json.JsonPrimitive(text),
                        "source_lang" to kotlinx.serialization.json.JsonPrimitive(normalized.sourceLanguage.uppercase()),
                        "target_lang" to kotlinx.serialization.json.JsonPrimitive(normalized.targetLanguage.uppercase()),
                    ),
                ),
            ).toByteArray(StandardCharsets.UTF_8)
            val response = try {
                transport.post(
                    TranslationHttpRequest(
                        url = endpoint,
                        body = body,
                        contentType = "application/json; charset=utf-8",
                        headers = bearerToken?.takeIf(String::isNotBlank)?.let {
                            mapOf("Authorization" to "Bearer $it")
                        }.orEmpty(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK)
            }
            if (response.statusCode == 401 || response.statusCode == 403) {
                return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.AUTHENTICATION)
            }
            if (response.statusCode !in 200..299) {
                return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.UPSTREAM)
            }
            val translated = parseCustomTranslation(response.body)
                ?: return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_RESPONSE)
            translations += translated
        }
        return CloudTranslationResult.Success(translations)
    }
}

internal class AliyunTranslationProvider(
    private val accessKeyId: String,
    private val accessKeySecret: String,
    private val transport: TranslationHttpTransport = UrlConnectionTranslationTransport,
    private val timestamp: () -> String = ::aliyunTimestamp,
    private val nonce: () -> String = { UUID.randomUUID().toString() },
) : CloudTranslationProvider {
    override suspend fun translate(request: CloudTranslationRequest): CloudTranslationResult {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
        }
        val normalized = normalizeTranslationRequest(request) ?: return CloudTranslationResult.Failure(
            CloudTranslationResult.Failure.Kind.INVALID_REQUEST,
        )
        return translateTextsConcurrently(normalized.texts) { text ->
            translateOne(normalized, text)
        }
    }

    private suspend fun translateOne(
        request: CloudTranslationRequest,
        text: String,
    ): CloudTranslationResult {
        coroutineContext.ensureActive()
        val parameters = linkedMapOf(
            "AccessKeyId" to accessKeyId,
            "Action" to "TranslateGeneral",
            "Format" to "JSON",
            "FormatType" to "text",
            "Scene" to "general",
            "SignatureMethod" to "HMAC-SHA1",
            "SignatureNonce" to nonce(),
            "SignatureVersion" to "1.0",
            "SourceLanguage" to request.sourceLanguage,
            "SourceText" to text,
            "TargetLanguage" to request.targetLanguage,
            "Timestamp" to timestamp(),
            "Version" to "2018-10-12",
        )
        val signed = AliyunRpcSigner.sign(parameters, accessKeySecret)
        val response = try {
            transport.post(
                TranslationHttpRequest(
                    url = ALIYUN_ENDPOINT,
                    body = AliyunRpcSigner.formEncode(signed).toByteArray(StandardCharsets.UTF_8),
                    contentType = "application/x-www-form-urlencoded; charset=utf-8",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK)
        }
        when (response.statusCode) {
            401, 403 -> return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.AUTHENTICATION)
            429 -> return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.RATE_LIMITED)
            in 400..499 -> return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_REQUEST)
            in 500..599 -> return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.UPSTREAM)
        }
        val translated = parseAliyunTranslation(response.body)
            ?: return CloudTranslationResult.Failure(aliyunFailureKind(response.body))
        return CloudTranslationResult.Success(listOf(translated))
    }

    private companion object {
        const val ALIYUN_ENDPOINT = "https://mt.cn-hangzhou.aliyuncs.com/"
    }
}

internal sealed interface BaiduAccessTokenResult {
    data class Success(
        val value: String,
        val expiresInSeconds: Long,
    ) : BaiduAccessTokenResult

    data class Failure(
        val failure: CloudTranslationResult.Failure,
    ) : BaiduAccessTokenResult
}

internal class BaiduAccessTokenCache(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: CachedToken? = null

    suspend fun get(loader: suspend () -> BaiduAccessTokenResult): BaiduAccessTokenResult = mutex.withLock {
        cached?.takeIf { nowMillis() < it.refreshAtMillis }?.let {
            return@withLock BaiduAccessTokenResult.Success(it.value, 0L)
        }
        when (val loaded = loader()) {
            is BaiduAccessTokenResult.Success -> {
                val cacheMillis = (loaded.expiresInSeconds * 1_000L - TOKEN_REFRESH_SKEW_MS).coerceAtLeast(0L)
                cached = CachedToken(loaded.value, nowMillis() + cacheMillis)
                loaded
            }
            is BaiduAccessTokenResult.Failure -> loaded
        }
    }

    suspend fun invalidate(value: String) = mutex.withLock {
        if (cached?.value == value) cached = null
    }

    private data class CachedToken(
        val value: String,
        val refreshAtMillis: Long,
    )

    private companion object {
        const val TOKEN_REFRESH_SKEW_MS = 60_000L
    }
}

internal class BaiduTranslationProvider(
    private val apiKey: String,
    private val secretKey: String,
    private val transport: TranslationHttpTransport = UrlConnectionTranslationTransport,
    private val tokenCache: BaiduAccessTokenCache = BaiduAccessTokenCache(),
) : CloudTranslationProvider {
    override suspend fun translate(request: CloudTranslationRequest): CloudTranslationResult {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
        }
        val normalized = normalizeTranslationRequest(request) ?: return CloudTranslationResult.Failure(
            CloudTranslationResult.Failure.Kind.INVALID_REQUEST,
        )
        return translateTextsConcurrently(normalized.texts) { text ->
            translateOne(normalized, text, mayRefreshToken = true)
        }
    }

    private suspend fun translateOne(
        request: CloudTranslationRequest,
        text: String,
        mayRefreshToken: Boolean,
    ): CloudTranslationResult {
        val token = when (val result = tokenCache.get(::fetchAccessToken)) {
            is BaiduAccessTokenResult.Success -> result.value
            is BaiduAccessTokenResult.Failure -> return result.failure
        }
        val payload = JsonObject(
            mapOf(
                "q" to kotlinx.serialization.json.JsonPrimitive(text),
                "from" to kotlinx.serialization.json.JsonPrimitive(request.sourceLanguage),
                "to" to kotlinx.serialization.json.JsonPrimitive(request.targetLanguage),
            ),
        )
        val response = try {
            transport.post(
                TranslationHttpRequest(
                    url = "$BAIDU_TRANSLATE_ENDPOINT?access_token=${AliyunRpcSigner.percentEncode(token)}",
                    body = JSON.encodeToString(JsonObject.serializer(), payload).toByteArray(StandardCharsets.UTF_8),
                    contentType = "application/json; charset=utf-8",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK)
        }
        val failureKind = baiduFailureKind(response.statusCode, response.body)
        if (failureKind == CloudTranslationResult.Failure.Kind.AUTHENTICATION && mayRefreshToken) {
            tokenCache.invalidate(token)
            return translateOne(request, text, mayRefreshToken = false)
        }
        if (failureKind != null) return CloudTranslationResult.Failure(failureKind)
        val translated = parseBaiduTranslation(response.body)
            ?: return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_RESPONSE)
        return CloudTranslationResult.Success(listOf(translated))
    }

    private suspend fun fetchAccessToken(): BaiduAccessTokenResult {
        val response = try {
            transport.post(
                TranslationHttpRequest(
                    url = "$BAIDU_TOKEN_ENDPOINT?grant_type=client_credentials" +
                        "&client_id=${AliyunRpcSigner.percentEncode(apiKey)}" +
                        "&client_secret=${AliyunRpcSigner.percentEncode(secretKey)}",
                    body = ByteArray(0),
                    contentType = "application/x-www-form-urlencoded; charset=utf-8",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return BaiduAccessTokenResult.Failure(
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK),
            )
        }
        if (response.statusCode == 429) {
            return BaiduAccessTokenResult.Failure(
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.RATE_LIMITED),
            )
        }
        if (response.statusCode !in 200..299) {
            return BaiduAccessTokenResult.Failure(
                CloudTranslationResult.Failure(
                    if (response.statusCode in 400..499) {
                        CloudTranslationResult.Failure.Kind.AUTHENTICATION
                    } else {
                        CloudTranslationResult.Failure.Kind.UPSTREAM
                    },
                ),
            )
        }
        return parseBaiduAccessToken(response.body) ?: BaiduAccessTokenResult.Failure(
            CloudTranslationResult.Failure(
                CloudTranslationResult.Failure.Kind.AUTHENTICATION,
            ),
        )
    }

    private companion object {
        const val BAIDU_TOKEN_ENDPOINT = "https://aip.baidubce.com/oauth/2.0/token"
        const val BAIDU_TRANSLATE_ENDPOINT = "https://aip.baidubce.com/rpc/2.0/mt/texttrans/v1"
    }
}

internal class DirectDualCloudTranslationProvider(
    private val primary: CloudTranslationProvider,
    private val fallback: CloudTranslationProvider,
) : CloudTranslationProvider {
    override suspend fun translate(request: CloudTranslationRequest): CloudTranslationResult {
        val primaryResult = primary.translate(request)
        return if (
            primaryResult is CloudTranslationResult.Failure &&
            primaryResult.kind in FALLBACK_FAILURES
        ) {
            fallback.translate(request)
        } else {
            primaryResult
        }
    }

    private companion object {
        val FALLBACK_FAILURES = setOf(
            CloudTranslationResult.Failure.Kind.NETWORK,
            CloudTranslationResult.Failure.Kind.AUTHENTICATION,
            CloudTranslationResult.Failure.Kind.RATE_LIMITED,
            CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED,
            CloudTranslationResult.Failure.Kind.UPSTREAM,
            CloudTranslationResult.Failure.Kind.INVALID_RESPONSE,
        )
    }
}

internal fun isInternalCloudConfigured(nowMillis: Long = System.currentTimeMillis()): Boolean = isInternalCloudConfigurationValid(
    enabled = BuildConfig.INTERNAL_CLOUD_ENABLED,
    aliyunAccessKeyId = BuildConfig.INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_ID,
    aliyunAccessKeySecret = BuildConfig.INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_SECRET,
    baiduApiKey = BuildConfig.INTERNAL_CLOUD_BAIDU_API_KEY,
    baiduSecretKey = BuildConfig.INTERNAL_CLOUD_BAIDU_SECRET_KEY,
    expiresAt = BuildConfig.INTERNAL_CLOUD_EXPIRES_AT,
    nowMillis = nowMillis,
)

internal fun isInternalCloudConfigurationValid(
    enabled: Boolean,
    aliyunAccessKeyId: String,
    aliyunAccessKeySecret: String,
    baiduApiKey: String,
    baiduSecretKey: String,
    expiresAt: String,
    nowMillis: Long,
): Boolean {
    if (!enabled || listOf(aliyunAccessKeyId, aliyunAccessKeySecret, baiduApiKey, baiduSecretKey).any(String::isBlank)) {
        return false
    }
    if (!INTERNAL_CLOUD_EXPIRY_PATTERN.matches(expiresAt)) return false
    val expiresAtEnd = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply {
        isLenient = false
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(expiresAt)?.time?.plus(MILLIS_PER_DAY) ?: return false
    return nowMillis < expiresAtEnd
}

internal fun internalCloudProvider(
    transport: TranslationHttpTransport = UrlConnectionTranslationTransport,
    nowMillis: Long = System.currentTimeMillis(),
): CloudTranslationProvider? {
    if (!isInternalCloudConfigured(nowMillis)) return null
    return DirectDualCloudTranslationProvider(
        primary = AliyunTranslationProvider(
            BuildConfig.INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_ID,
            BuildConfig.INTERNAL_CLOUD_ALIYUN_ACCESS_KEY_SECRET,
            transport,
        ),
        fallback = BaiduTranslationProvider(
            BuildConfig.INTERNAL_CLOUD_BAIDU_API_KEY,
            BuildConfig.INTERNAL_CLOUD_BAIDU_SECRET_KEY,
            transport,
        ),
    )
}

internal object AliyunRpcSigner {
    fun sign(
        parameters: Map<String, String>,
        accessKeySecret: String,
        httpMethod: String = "POST",
    ): Map<String, String> {
        val canonical = formEncode(parameters)
        val stringToSign = "${httpMethod.uppercase(Locale.ROOT)}&%2F&${percentEncode(canonical)}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec((accessKeySecret + "&").toByteArray(StandardCharsets.UTF_8), "HmacSHA1"))
        val signature = encodeBase64(mac.doFinal(stringToSign.toByteArray(StandardCharsets.UTF_8)))
        return parameters + ("Signature" to signature)
    }

    fun formEncode(parameters: Map<String, String>): String = parameters.entries
        .sortedBy { it.key }
        .joinToString("&") { "${percentEncode(it.key)}=${percentEncode(it.value)}" }

    internal fun percentEncode(value: String): String = buildString {
        value.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val valueInt = byte.toInt() and 0xff
            if (
                valueInt in 'A'.code..'Z'.code ||
                valueInt in 'a'.code..'z'.code ||
                valueInt in '0'.code..'9'.code ||
                valueInt == '-'.code || valueInt == '_'.code || valueInt == '.'.code || valueInt == '~'.code
            ) {
                append(valueInt.toChar())
            } else {
                append('%')
                append(HEX[valueInt ushr 4])
                append(HEX[valueInt and 0x0f])
            }
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

internal object AesGcmSecretCodec {
    fun encrypt(
        value: String,
        key: SecretKey,
        random: SecureRandom = SecureRandom(),
    ): String {
        if (value.isEmpty()) return ""
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return "${encodeBase64(iv)}:${encodeBase64(cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))}"
    }

    fun decrypt(
        envelope: String,
        key: SecretKey,
    ): String? {
        if (envelope.isEmpty()) return ""
        return runCatching {
            val parts = envelope.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decodeBase64(parts[0])))
            String(cipher.doFinal(decodeBase64(parts[1])), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    const val TRANSFORMATION = "AES/GCM/NoPadding"
}

internal class TranslationSecretStore(
    private val sharedPreferences: android.content.SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw UnsupportedOperationException("Encrypted cloud credentials require Android 6.0 or newer")
        }
        return encryptApi23(value)
    }

    fun decrypt(value: String): String? {
        if (value.isEmpty()) return ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return AesGcmSecretCodec.decrypt(value, key())
    }

    fun put(
        key: String,
        value: String,
    ) {
        sharedPreferences.edit().putString(key, encrypt(value)).apply()
    }

    fun get(key: String): String? = decrypt(sharedPreferences.getString(key, "").orEmpty())

    fun contains(key: String): Boolean = !sharedPreferences.getString(key, null).isNullOrBlank()

    fun fingerprintMaterial(key: String): String = sharedPreferences.getString(key, "").orEmpty()

    fun remove(vararg keys: String) {
        sharedPreferences.edit().apply {
            keys.forEach(::remove)
        }.apply()
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun encryptApi23(value: String): String = AesGcmSecretCodec.encrypt(value, key())

    @RequiresApi(Build.VERSION_CODES.M)
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "haohao_cloud_translation_secrets"
        const val KEY_ALIAS = "haohao_cloud_translation_v1"
    }
}

internal class TranslationInstallIdStore(
    private val sharedPreferences: android.content.SharedPreferences =
        appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    fun getOrCreate(): String {
        sharedPreferences.getString(INSTALL_ID, null)?.takeIf(String::isNotBlank)?.let { return it }
        return UUID.randomUUID().toString().also {
            sharedPreferences.edit().putString(INSTALL_ID, it).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "haohao_cloud_translation_instance"
        const val INSTALL_ID = "install_id"
    }
}

internal data class AliyunTranslationCredentials(val accessKeyId: String, val accessKeySecret: String)
internal data class CustomTranslationCredentials(val endpoint: String, val bearerToken: String?)

internal class CloudTranslationConfigStore(
    private val prefs: AppPrefs.CloudTranslation = AppPrefs.defaultInstance().cloudTranslation,
    private val secrets: TranslationSecretStore = TranslationSecretStore(),
    private val installIds: TranslationInstallIdStore = TranslationInstallIdStore(),
) {
    fun activeProvider(): CloudTranslationProviderType = prefs.provider.getValue()

    fun saveAliyun(credentials: AliyunTranslationCredentials) {
        secrets.put(SECRET_ALIYUN_ACCESS_KEY_ID, credentials.accessKeyId.trim())
        secrets.put(SECRET_ALIYUN_ACCESS_KEY_SECRET, credentials.accessKeySecret.trim())
        prefs.provider.setValue(CloudTranslationProviderType.ALIYUN)
    }

    fun aliyun(): AliyunTranslationCredentials? {
        val id = secrets.get(SECRET_ALIYUN_ACCESS_KEY_ID) ?: return null
        val secret = secrets.get(SECRET_ALIYUN_ACCESS_KEY_SECRET) ?: return null
        return AliyunTranslationCredentials(id, secret).takeIf { id.isNotBlank() && secret.isNotBlank() }
    }

    fun hasAliyunCredentials(): Boolean = secrets.contains(SECRET_ALIYUN_ACCESS_KEY_ID) && secrets.contains(SECRET_ALIYUN_ACCESS_KEY_SECRET)

    fun saveCustom(credentials: CustomTranslationCredentials) {
        prefs.customEndpoint.setValue(credentials.endpoint.trim())
        secrets.put(SECRET_CUSTOM_BEARER_TOKEN, credentials.bearerToken.orEmpty().trim())
        prefs.provider.setValue(CloudTranslationProviderType.CUSTOM)
    }

    fun custom(): CustomTranslationCredentials? {
        val endpoint = prefs.customEndpoint.getValue().trim()
        val token = secrets.get(SECRET_CUSTOM_BEARER_TOKEN) ?: return null
        return CustomTranslationCredentials(endpoint, token.takeIf(String::isNotBlank)).takeIf {
            isAllowedTranslationEndpoint(it.endpoint, BuildConfig.DEBUG)
        }
    }

    fun hasCustomConfiguration(): Boolean = isAllowedTranslationEndpoint(prefs.customEndpoint.getValue().trim(), BuildConfig.DEBUG)

    fun selectPublic() {
        prefs.provider.setValue(CloudTranslationProviderType.HAOHAO)
    }

    fun installId(): String = installIds.getOrCreate()

    fun providerFingerprint(): String {
        val raw = if (BuildConfig.INTERNAL_CLOUD_ENABLED) {
            "internal-dual:${BuildConfig.INTERNAL_CLOUD_EXPIRES_AT}"
        } else {
            when (activeProvider()) {
                CloudTranslationProviderType.HAOHAO -> "haohao:${BuildConfig.HAOHAO_TRANSLATION_BASE_URL}"
                CloudTranslationProviderType.ALIYUN ->
                    "aliyun:${secrets.fingerprintMaterial(SECRET_ALIYUN_ACCESS_KEY_ID)}:" +
                        secrets.fingerprintMaterial(SECRET_ALIYUN_ACCESS_KEY_SECRET)
                CloudTranslationProviderType.CUSTOM ->
                    "custom:${prefs.customEndpoint.getValue().trim()}\u0000" +
                        secrets.fingerprintMaterial(SECRET_CUSTOM_BEARER_TOKEN)
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private companion object {
        const val SECRET_ALIYUN_ACCESS_KEY_ID = "aliyun_access_key_id"
        const val SECRET_ALIYUN_ACCESS_KEY_SECRET = "aliyun_access_key_secret"
        const val SECRET_CUSTOM_BEARER_TOKEN = "custom_bearer_token"
    }
}

internal class CloudTranslationManager(
    private val config: CloudTranslationConfigStore = CloudTranslationConfigStore(),
    private val transport: TranslationHttpTransport = UrlConnectionTranslationTransport,
) {
    private val directProvider: CloudTranslationProvider? by lazy {
        internalCloudProvider(transport)
    }

    fun status(): CloudTranslationResult.Failure? {
        if (!AppPrefs.defaultInstance().cloudTranslation.consentGranted.getValue()) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.CONSENT_REQUIRED)
        }
        return configurationStatus()
    }

    fun configurationStatus(): CloudTranslationResult.Failure? {
        if (BuildConfig.INTERNAL_CLOUD_ENABLED) {
            return if (isInternalCloudConfigured()) {
                null
            } else {
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.CONFIGURATION_EXPIRED)
            }
        }
        if (config.activeProvider() != CloudTranslationProviderType.HAOHAO && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.UNSUPPORTED_DEVICE)
        }
        return when (config.activeProvider()) {
            CloudTranslationProviderType.HAOHAO -> if (
                !isAllowedTranslationEndpoint(BuildConfig.HAOHAO_TRANSLATION_BASE_URL, BuildConfig.DEBUG)
            ) {
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
            } else {
                null
            }
            CloudTranslationProviderType.ALIYUN -> if (config.hasAliyunCredentials()) {
                null
            } else {
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
            }
            CloudTranslationProviderType.CUSTOM -> if (config.hasCustomConfiguration()) {
                null
            } else {
                CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
            }
        }
    }

    suspend fun translate(request: CloudTranslationRequest): CloudTranslationResult {
        status()?.let { return it }
        val provider = directProvider ?: when (config.activeProvider()) {
            CloudTranslationProviderType.HAOHAO ->
                HaoHaoTranslationProvider(BuildConfig.HAOHAO_TRANSLATION_BASE_URL, config.installId(), transport)
            CloudTranslationProviderType.ALIYUN ->
                config.aliyun()?.let { AliyunTranslationProvider(it.accessKeyId, it.accessKeySecret, transport) }
                    ?: return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
            CloudTranslationProviderType.CUSTOM ->
                config.custom()?.let { CustomTranslationProvider(it.endpoint, it.bearerToken, transport) }
                    ?: return CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NOT_CONFIGURED)
        }
        return try {
            withTimeout(TRANSLATION_REQUEST_TIMEOUT_MS.toLong()) {
                provider.translate(request)
            }
        } catch (_: TimeoutCancellationException) {
            CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.NETWORK)
        }
    }
}

internal fun isAllowedTranslationEndpoint(endpoint: String, allowLoopbackHttp: Boolean): Boolean = runCatching {
    val uri = URI(endpoint)
    when (uri.scheme?.lowercase()) {
        "https" -> !uri.host.isNullOrBlank()
        "http" -> allowLoopbackHttp && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        else -> false
    }
}.getOrDefault(false)

private fun normalizeTranslationRequest(request: CloudTranslationRequest): CloudTranslationRequest? {
    if (request.sourceLanguage != "zh" || request.targetLanguage != "en") return null
    if (request.texts.isEmpty() || request.texts.size > 5) return null
    val maxLength = if (request.purpose == TranslationPurpose.SENTENCE) {
        TRANSLATION_SENTENCE_MAX_CODE_POINTS
    } else {
        TRANSLATION_CANDIDATE_MAX_CODE_POINTS
    }
    val texts = request.texts.map(String::trim)
    if (texts.any { it.isEmpty() || it.codePointCount(0, it.length) > maxLength }) return null
    return request.copy(texts = texts)
}

private suspend fun translateTextsConcurrently(
    texts: List<String>,
    translateOne: suspend (String) -> CloudTranslationResult,
): CloudTranslationResult = coroutineScope {
    val results = texts.map { text -> async { translateOne(text) } }.awaitAll()
    val failures = results.filterIsInstance<CloudTranslationResult.Failure>()
    val failure = failures.firstOrNull {
        it.kind == CloudTranslationResult.Failure.Kind.INVALID_REQUEST
    } ?: failures.firstOrNull()
    failure?.let {
        return@coroutineScope it
    }
    val translations = results.mapNotNull { result ->
        (result as? CloudTranslationResult.Success)?.translations?.singleOrNull()
    }
    if (translations.size == texts.size) {
        CloudTranslationResult.Success(translations)
    } else {
        CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_RESPONSE)
    }
}

private fun parseGatewayResponse(body: String, expectedSize: Int): CloudTranslationResult {
    val values = runCatching { JSON.decodeFromString(GatewayResponse.serializer(), body).translations }.getOrNull()
    return if (values != null && values.size == expectedSize && values.all(String::isNotBlank)) {
        CloudTranslationResult.Success(values)
    } else {
        CloudTranslationResult.Failure(CloudTranslationResult.Failure.Kind.INVALID_RESPONSE)
    }
}

private fun parseCustomTranslation(body: String): String? = runCatching {
    val root = JSON.parseToJsonElement(body).jsonObject
    listOf("translation", "data", "result").firstNotNullOfOrNull { key ->
        root[key]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
    }
}.getOrNull()

private fun parseAliyunTranslation(body: String): String? = runCatching {
    val root = JSON.parseToJsonElement(body).jsonObject
    val code = root["Code"]?.jsonPrimitive?.content
    if (code != "200") return@runCatching null
    root["Data"]?.jsonObject?.get("Translated")?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
}.getOrNull()

private fun aliyunFailureKind(body: String): CloudTranslationResult.Failure.Kind {
    val code = runCatching {
        JSON.parseToJsonElement(body).jsonObject["Code"]?.jsonPrimitive?.content
    }.getOrNull()
    return when (code) {
        "10003", "10004", "10005", "10008" -> CloudTranslationResult.Failure.Kind.INVALID_REQUEST
        "10009", "10010" -> CloudTranslationResult.Failure.Kind.AUTHENTICATION
        "10013" -> CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED
        else -> CloudTranslationResult.Failure.Kind.UPSTREAM
    }
}

private fun parseBaiduAccessToken(body: String): BaiduAccessTokenResult.Success? = runCatching {
    val root = JSON.parseToJsonElement(body).jsonObject
    val token = root["access_token"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return@runCatching null
    val expiresIn = root["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0L }
        ?: return@runCatching null
    BaiduAccessTokenResult.Success(token, expiresIn)
}.getOrNull()

private fun parseBaiduTranslation(body: String): String? = runCatching {
    JSON.parseToJsonElement(body)
        .jsonObject["result"]
        ?.jsonObject
        ?.get("trans_result")
        ?.jsonArray
        ?.mapNotNull { item ->
            item.jsonObject["dst"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
        }
        ?.takeIf(List<String>::isNotEmpty)
        ?.joinToString("\n")
}.getOrNull()

private fun baiduFailureKind(
    statusCode: Int,
    body: String,
): CloudTranslationResult.Failure.Kind? {
    when (statusCode) {
        401, 403 -> return CloudTranslationResult.Failure.Kind.AUTHENTICATION
        429 -> return CloudTranslationResult.Failure.Kind.RATE_LIMITED
        in 500..599 -> return CloudTranslationResult.Failure.Kind.UPSTREAM
        in 400..499 -> return CloudTranslationResult.Failure.Kind.INVALID_REQUEST
    }
    val errorCode = runCatching {
        JSON.parseToJsonElement(body).jsonObject["error_code"]?.jsonPrimitive?.content?.toIntOrNull()
    }.getOrNull() ?: return null
    return when (errorCode) {
        6, 110, 111 -> CloudTranslationResult.Failure.Kind.AUTHENTICATION
        4, 18, 31104 -> CloudTranslationResult.Failure.Kind.RATE_LIMITED
        19, 31005 -> CloudTranslationResult.Failure.Kind.QUOTA_EXCEEDED
        100, 20003, 31103, 31105, 31106, 31201, 31202, 31203, 282003, 282004 ->
            CloudTranslationResult.Failure.Kind.INVALID_REQUEST
        else -> CloudTranslationResult.Failure.Kind.UPSTREAM
    }
}

private fun aliyunTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).run {
    timeZone = TimeZone.getTimeZone("UTC")
    format(Date())
}

private fun encodeBase64(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    return buildString((bytes.size + 2) / 3 * 4) {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val hasSecond = index < bytes.size
            val second = if (hasSecond) bytes[index++].toInt() and 0xff else 0
            val hasThird = index < bytes.size
            val third = if (hasThird) bytes[index++].toInt() and 0xff else 0
            append(alphabet[first ushr 2])
            append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            append(if (hasSecond) alphabet[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            append(if (hasThird) alphabet[third and 0x3f] else '=')
        }
    }
}

private fun decodeBase64(value: String): ByteArray {
    if (value.isEmpty()) return byteArrayOf()
    require(value.length % 4 == 0)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val output = ArrayList<Byte>(value.length / 4 * 3)
    value.chunked(4).forEach { block ->
        val first = alphabet.indexOf(block[0]).also { require(it >= 0) }
        val second = alphabet.indexOf(block[1]).also { require(it >= 0) }
        val third = if (block[2] == '=') 0 else alphabet.indexOf(block[2]).also { require(it >= 0) }
        val fourth = if (block[3] == '=') 0 else alphabet.indexOf(block[3]).also { require(it >= 0) }
        output += ((first shl 2) or (second ushr 4)).toByte()
        if (block[2] != '=') output += (((second and 0x0f) shl 4) or (third ushr 2)).toByte()
        if (block[3] != '=') output += (((third and 0x03) shl 6) or fourth).toByte()
    }
    return output.toByteArray()
}

private val JSON = Json { ignoreUnknownKeys = true }

private const val MILLIS_PER_DAY = 86_400_000L
private val INTERNAL_CLOUD_EXPIRY_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
