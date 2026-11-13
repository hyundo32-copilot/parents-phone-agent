package com.hyundo.parentsagent.net

import com.hyundo.parentsagent.core.Symptom
import org.json.JSONArray
import org.json.JSONObject

/** Liner 모델 API 요청 본문 조립 (순수 함수). 보내는 데이터는 증상 발화 텍스트와 설정값·시도 내역뿐이다. */
object LinerRequests {
    const val BASE_URL = "https://platform.liner.com/api/v1"
    const val MODEL = "liner-mark-1.0"
    const val CLASSIFY_MAX_INPUT_CHARS = 300
    const val CLASSIFY_MAX_TOKENS = 16
    const val SUMMARY_MAX_TOKENS = 600
    const val SUMMARY_MAX_INPUT_CHARS = 4000

    val CLASSIFY_SYSTEM_PROMPT: String = buildString {
        append("너는 스마트폰 증상 분류기다. 사용자가 한 말을 아래 목록 중 정확히 하나로 분류하고, ")
        append("라벨 문자열만 출력하라. 설명, 따옴표, 줄바꿈을 붙이지 마라. 목록에 맞지 않으면 OTHER.\n")
        Symptom.entries.forEach { append("- ${it.name}: ${it.label}\n") }
    }

    const val SUMMARY_SYSTEM_PROMPT: String =
        "아래는 규칙 엔진이 만든 스마트폰 설정 점검 결과다. 가족 중 이 결과를 받는 사람이 한눈에 읽도록 " +
            "한국어로 5줄 이내로 요약하라. 항목 이름과 숫자, 켜짐/꺼짐 값은 바꾸지 마라. " +
            "없는 내용을 추가하거나 원인을 추측하지 마라. 사람을 이름으로 부르지 마라."

    fun classifyBody(utterance: String): String =
        chatBody(CLASSIFY_SYSTEM_PROMPT, utterance.trim().take(CLASSIFY_MAX_INPUT_CHARS), CLASSIFY_MAX_TOKENS)

    fun summarizeBody(rawMessage: String): String =
        chatBody(SUMMARY_SYSTEM_PROMPT, rawMessage.take(SUMMARY_MAX_INPUT_CHARS), SUMMARY_MAX_TOKENS)

    private fun chatBody(system: String, user: String, maxTokens: Int): String =
        JSONObject()
            .put("model", MODEL)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("max_completion_tokens", maxTokens)
            .put("reasoning_effort", "none") // temperature는 서버가 무시하므로 보내지 않는다
            .toString()

    /** chat completions 응답에서 첫 번째 메시지 본문. 형식이 다르면 null. */
    fun extractContent(responseBody: String): String? = try {
        JSONObject(responseBody).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content").trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}
