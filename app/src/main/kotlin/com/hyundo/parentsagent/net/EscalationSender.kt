package com.hyundo.parentsagent.net

import java.io.IOException

enum class SendOutcome { SENT, SENT_RAW, QUEUED, REJECTED }

interface Escalator {
    fun send(raw: String): SendOutcome
}

/**
 * 에스컬레이션 전송: 규칙 엔진 원문 → (가능하면) Liner 요약 → 텔레그램.
 * 요약이 안 되면 원문을 보내고, 망이 안 되면 원문을 대기열에 넣는다 (SMS 폴백은 2단계).
 * 4xx(429 제외)는 다시 보내도 소용없는 설정 오류라 대기열에 넣지 않고 [onPermanentError]로 알린다.
 * 429는 텔레그램이 알려준 대기 시간(상한 30초)만큼 기다렸다 다시 보낸다.
 */
class EscalationSender(
    private val liner: LinerClient?,
    private val telegram: TelegramClient,
    private val outbox: Outbox,
    private val sleeper: Sleeper = ThreadSleeper,
    private val onPermanentError: (String) -> Unit = {},
    private val persistOutbox: (Outbox) -> Unit = {},
) : Escalator {
    override fun send(raw: String): SendOutcome {
        if (!telegram.configured) { // 보낼 곳이 없으면 요약 호출도 아낀다
            return enqueue(raw)
        }
        val (text, summarized) = summarizeOrRaw(raw)
        return try {
            deliver(text)
            if (summarized) SendOutcome.SENT else SendOutcome.SENT_RAW
        } catch (e: HttpStatusException) {
            if (e.permanent) {
                onPermanentError(permanentMessage(e))
                SendOutcome.REJECTED
            } else {
                enqueue(raw)
            }
        } catch (e: IOException) {
            enqueue(raw)
        }
    }

    /**
     * 대기열을 앞에서부터 보낸다. 망 오류(5xx·429 지속 포함)면 거기서 멈춘다. 보낸 개수를 돌려준다.
     * 영구 오류(4xx)로 거부된 항목은 다시 보내도 소용없으니 버리고 다음 항목으로 넘어간다 (뒤 항목이 영원히 막히지 않게).
     */
    fun flush(): Int {
        if (!telegram.configured) return 0
        var sent = 0
        while (true) {
            val raw = outbox.peek() ?: break
            val (text, _) = summarizeOrRaw(raw)
            try {
                deliver(text)
            } catch (e: HttpStatusException) {
                if (!e.permanent) break
                onPermanentError(permanentMessage(e))
                outbox.removeFirst()
                persistOutbox(outbox)
                continue
            } catch (e: IOException) {
                break
            }
            outbox.removeFirst()
            persistOutbox(outbox)
            sent++
        }
        return sent
    }

    private fun enqueue(raw: String): SendOutcome {
        outbox.enqueue(raw)
        persistOutbox(outbox)
        return SendOutcome.QUEUED
    }

    private fun deliver(text: String) {
        var attempt = 0
        while (true) {
            try {
                telegram.send(text)
                return
            } catch (e: HttpStatusException) {
                if (e.code != 429 || attempt >= MAX_RETRIES) throw e
                sleeper.sleep(retryMs(e.retryAfterSec))
                attempt++
            }
        }
    }

    // 토큰·URL은 넣지 않는다
    private fun permanentMessage(e: HttpStatusException) =
        "알림 설정 오류: 텔레그램이 요청을 거부했어요 (HTTP ${e.code}). 봇 토큰·챗 ID를 확인해 주세요"

    private fun summarizeOrRaw(raw: String): Pair<String, Boolean> {
        val l = liner ?: return raw to false
        return try {
            l.summarize(raw) to true
        } catch (e: IOException) {
            raw to false
        }
    }

    companion object {
        const val MAX_RETRIES = 2
        private const val DEFAULT_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L

        fun retryMs(sec: Long?): Long = if (sec == null) DEFAULT_RETRY_MS else (sec * 1000).coerceIn(0, MAX_RETRY_MS)
    }
}
