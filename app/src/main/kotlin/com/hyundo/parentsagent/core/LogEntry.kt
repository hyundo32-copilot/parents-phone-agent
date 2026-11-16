package com.hyundo.parentsagent.core

import org.json.JSONObject

/**
 * 기록 한 줄. text는 가족용 원문, parentText는 부모님용 문장(시각 없음).
 * JSON 키: t 시각, x 원문, k 종류 코드, p 부모님 문장 (p는 있을 때만 쓴다).
 */
data class LogEntry(
    val timeMillis: Long,
    val text: String,
    val kind: LogKind = LogKind.DEV,
    val parentText: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("t", timeMillis).put("x", text).put("k", kind.code).apply {
        if (parentText != null) put("p", parentText)
    }

    companion object {
        /** 모르는 키는 무시한다. k가 없거나 모르면 DEV, p가 없거나 빈 문자열이면 null. */
        fun fromJson(o: JSONObject): LogEntry = LogEntry(
            o.getLong("t"), o.getString("x"),
            LogKind.fromCode(if (o.has("k")) o.optString("k") else null),
            if (o.has("p")) o.optString("p").ifEmpty { null } else null,
        )
    }
}
