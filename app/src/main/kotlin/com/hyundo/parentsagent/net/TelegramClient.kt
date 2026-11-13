package com.hyundo.parentsagent.net

import org.json.JSONObject
import java.io.IOException

/** 텔레그램 봇으로 현도에게 메시지를 보낸다. 토큰은 URL에 들어가므로 로그에 URL을 남기지 않는다. */
class TelegramClient(
    private val botToken: String,
    private val chatId: String,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val timeoutMs: Int = 15_000,
) {
    val configured: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()

    @Throws(IOException::class)
    fun send(text: String) {
        if (!configured) throw NotConfiguredException("텔레그램 봇 토큰/챗 ID")
        val body = JSONObject().put("chat_id", chatId).put("text", text.take(MAX_TEXT)).toString()
        val res = transport.post(
            "https://api.telegram.org/bot$botToken/sendMessage",
            mapOf("Content-Type" to "application/json"),
            body,
            timeoutMs,
        )
        if (res.code !in 200..299) throw HttpStatusException(res.code)
    }

    companion object {
        const val MAX_TEXT = 4000
    }
}
