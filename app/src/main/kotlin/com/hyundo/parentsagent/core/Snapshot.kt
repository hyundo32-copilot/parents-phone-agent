package com.hyundo.parentsagent.core

import org.json.JSONObject

/** 기준 스냅샷 (설계 §3). 설정값과 야간 시간대만 저장한다. 로컬에만 둔다. */
data class Snapshot(
    val takenAtMillis: Long,
    val values: Map<SettingKey, SettingValue>,
    /** 부모님 취침 시간대. 분 단위(0~1439). 시작 > 끝이면 자정을 넘긴다. */
    val nightStartMin: Int = DEFAULT_NIGHT_START,
    val nightEndMin: Int = DEFAULT_NIGHT_END,
) {
    fun toJson(): String {
        val vs = JSONObject()
        values.forEach { (k, v) -> vs.put(k.name, v.toJson()) }
        return JSONObject()
            .put("takenAt", takenAtMillis)
            .put("nightStart", nightStartMin)
            .put("nightEnd", nightEndMin)
            .put("values", vs)
            .toString()
    }

    companion object {
        const val DEFAULT_NIGHT_START = 22 * 60
        const val DEFAULT_NIGHT_END = 7 * 60

        fun fromJson(s: String): Snapshot {
            val o = JSONObject(s)
            val vs = o.getJSONObject("values")
            val map = LinkedHashMap<SettingKey, SettingValue>()
            for (name in vs.keys()) {
                val key = SettingKey.entries.firstOrNull { it.name == name } ?: continue
                map[key] = SettingValue.fromJson(vs.getJSONObject(name))
            }
            return Snapshot(
                o.getLong("takenAt"),
                map,
                o.optInt("nightStart", DEFAULT_NIGHT_START),
                o.optInt("nightEnd", DEFAULT_NIGHT_END),
            )
        }

        /** 읽은 현재 설정으로 기준 스냅샷을 만든다. 볼륨은 ±1 허용 범위로 바꾼다. */
        fun capture(
            current: Map<SettingKey, SettingValue>,
            nowMillis: Long,
            nightStartMin: Int = DEFAULT_NIGHT_START,
            nightEndMin: Int = DEFAULT_NIGHT_END,
        ): Snapshot {
            val values = current.mapValues { (_, v) ->
                if (v is SettingValue.Level) SettingValue.Level.around(v.value) else v
            }
            return Snapshot(nowMillis, values, nightStartMin, nightEndMin)
        }
    }
}

/** 기준에서 벗어난 항목 한 개. */
data class Deviation(val key: SettingKey, val expected: SettingValue, val actual: SettingValue)

data class CompareResult(
    val deviations: List<Deviation>,
    /** 현재 값을 읽지 못해 비교하지 못한 항목. */
    val unreadable: List<SettingKey>,
)

object SnapshotDiff {
    /** 기준과 현재를 비교해 값이 다른 키 목록을 돌려준다. 기준이나 현재가 Unknown이면 판단하지 않는다. */
    fun compare(baseline: Snapshot, current: Map<SettingKey, SettingValue>): CompareResult {
        val deviations = mutableListOf<Deviation>()
        val unreadable = mutableListOf<SettingKey>()
        for (key in SettingKey.entries) {
            val expected = baseline.values[key] ?: continue
            if (expected is SettingValue.Unknown) continue
            val actual = current[key] ?: SettingValue.Unknown
            if (actual is SettingValue.Unknown) {
                unreadable += key
                continue
            }
            if (!expected.matches(actual)) deviations += Deviation(key, expected, actual)
        }
        return CompareResult(deviations, unreadable)
    }
}
