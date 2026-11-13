package com.hyundo.parentsagent.core

import org.json.JSONObject

/** 설정값 한 개. 값만 담고 내용(문자·통화·연락처)은 담지 않는다. */
sealed interface SettingValue {
    /** 켜짐/꺼짐. DND에서는 on = 방해금지가 켜져 있음. */
    data class Flag(val on: Boolean) : SettingValue

    /** 벨소리 모드: NORMAL / VIBRATE / SILENT. */
    data class Mode(val name: String) : SettingValue

    /** 볼륨. 기준 스냅샷에서는 [min]..[max]가 허용 범위, 현재값에서는 min = max = value. */
    data class Level(val value: Int, val min: Int, val max: Int) : SettingValue {
        companion object {
            fun current(v: Int) = Level(v, v, v)

            /** 기준 값 v 주변 ±tolerance 를 허용 범위로 삼는다. 기준이 0보다 크면 0(무음)은 범위 밖. */
            fun around(v: Int, tolerance: Int = 1): Level {
                val lo = if (v > 0) maxOf(1, v - tolerance) else 0
                return Level(v, lo, v + tolerance)
            }
        }
    }

    /** 읽을 수 없는 값. 비교 대상에서 제외한다. */
    data object Unknown : SettingValue

    fun display(): String = when (this) {
        is Flag -> if (on) "켜짐" else "꺼짐"
        is Mode -> when (name) {
            "NORMAL" -> "소리"
            "VIBRATE" -> "진동"
            "SILENT" -> "무음"
            else -> name
        }
        is Level -> if (min == max) "$value" else "$value (허용 $min~$max)"
        Unknown -> "알 수 없음"
    }

    fun matches(actual: SettingValue): Boolean = when (this) {
        is Flag -> actual is Flag && actual.on == on
        is Mode -> actual is Mode && actual.name == name
        is Level -> actual is Level && actual.value in min..max
        Unknown -> true
    }

    fun toJson(): JSONObject = when (this) {
        is Flag -> JSONObject().put("t", "flag").put("on", on)
        is Mode -> JSONObject().put("t", "mode").put("name", name)
        is Level -> JSONObject().put("t", "level").put("v", value).put("min", min).put("max", max)
        Unknown -> JSONObject().put("t", "unknown")
    }

    companion object {
        fun fromJson(o: JSONObject): SettingValue = when (o.optString("t")) {
            "flag" -> Flag(o.getBoolean("on"))
            "mode" -> Mode(o.getString("name"))
            "level" -> Level(o.getInt("v"), o.getInt("min"), o.getInt("max"))
            else -> Unknown
        }
    }
}
