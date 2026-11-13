package com.hyundo.parentsagent.net

import java.io.IOException

enum class SendOutcome { SENT, SENT_RAW, QUEUED }

/**
 * 에스컬레이션 전송: 규칙 엔진 원문 → (가능하면) Liner 요약 → 텔레그램.
 * 요약이 안 되면 원문을 보내고, 전송이 안 되면 원문을 대기열에 넣는다 (SMS 폴백은 2단계).
 */
class EscalationSender(
    private val liner: LinerClient?,
    private val telegram: TelegramClient,
    private val outbox: Outbox,
    private val persistOutbox: (Outbox) -> Unit = {},
) {
    fun send(raw: String): SendOutcome {
        if (!telegram.configured) { // 보낼 곳이 없으면 요약 호출도 아낀다
            outbox.enqueue(raw)
            persistOutbox(outbox)
            return SendOutcome.QUEUED
        }
        val (text, summarized) = summarizeOrRaw(raw)
        return try {
            telegram.send(text)
            if (summarized) SendOutcome.SENT else SendOutcome.SENT_RAW
        } catch (e: IOException) {
            outbox.enqueue(raw)
            persistOutbox(outbox)
            SendOutcome.QUEUED
        }
    }

    /** 대기열을 앞에서부터 보낸다. 하나라도 실패하면 거기서 멈춘다. 보낸 개수를 돌려준다. */
    fun flush(): Int {
        if (!telegram.configured) return 0
        var sent = 0
        while (true) {
            val raw = outbox.peek() ?: break
            val (text, _) = summarizeOrRaw(raw)
            try {
                telegram.send(text)
            } catch (e: IOException) {
                break
            }
            outbox.removeFirst()
            persistOutbox(outbox)
            sent++
        }
        return sent
    }

    private fun summarizeOrRaw(raw: String): Pair<String, Boolean> {
        val l = liner ?: return raw to false
        return try {
            l.summarize(raw) to true
        } catch (e: IOException) {
            raw to false
        }
    }
}
