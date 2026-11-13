package com.hyundo.parentsagent.net

import com.hyundo.parentsagent.core.Symptom
import java.io.IOException

class NotConfiguredException(what: String) : IOException("$what 설정이 비어 있습니다 (local.properties)")
class RateLimitedException : IOException("Liner 속도 제한(429)이 계속됩니다")
class HttpStatusException(val code: Int) : IOException("HTTP $code")

/** Liner 모델 API. 증상 분류와 에스컬레이션 요약만 한다. 조작 결정에는 쓰지 않는다. */
class LinerClient(
    private val apiKey: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val sleeper: Sleeper = ThreadSleeper,
    private val baseUrl: String = LinerRequests.BASE_URL,
    private val timeoutMs: Int = 20_000,
) {
    val configured: Boolean get() = apiKey.isNotBlank()

    /** 분류 실패(망·키 없음)는 예외로 알린다. 호출자는 OTHER 흐름으로 넘어간다. */
    @Throws(IOException::class)
    fun classify(utterance: String): Symptom =
        Symptom.parse(LinerRequests.extractContent(call(LinerRequests.classifyBody(utterance))))

    /** 요약 실패는 예외. 호출자는 규칙 엔진 원문을 쓴다. */
    @Throws(IOException::class)
    fun summarize(rawMessage: String): String =
        LinerRequests.extractContent(call(LinerRequests.summarizeBody(rawMessage)))
            ?: throw IOException("요약 응답 형식 오류")

    private fun call(body: String): String {
        if (!configured) throw NotConfiguredException("Liner API 키")
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json",
        )
        var attempt = 0
        while (true) {
            val res = transport.post("$baseUrl/chat/completions", headers, body, timeoutMs)
            when {
                res.code in 200..299 -> return res.body
                res.code == 429 && attempt < MAX_RETRIES -> {
                    sleeper.sleep(retryAfterMs(res.header("Retry-After")))
                    attempt++
                }
                res.code == 429 -> throw RateLimitedException()
                else -> throw HttpStatusException(res.code)
            }
        }
    }

    companion object {
        const val MAX_RETRIES = 2
        private const val DEFAULT_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L

        fun retryAfterMs(header: String?): Long {
            val sec = header?.trim()?.toLongOrNull() ?: return DEFAULT_RETRY_MS
            return (sec * 1000).coerceIn(0, MAX_RETRY_MS)
        }
    }
}
