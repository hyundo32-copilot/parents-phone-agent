package com.hyundo.parentsagent.core

/** 점검 뒤 앱 화면으로 돌아오기: 최상위 창을 보고 다음에 뭘 할지. */
enum class ReturnStep {
    /** 우리가 연 시스템 화면이다. 뒤로 가기를 한 번 누른다. */
    PRESS_BACK,

    /** 이미 앱 화면이다. */
    ARRIVED,

    /** 사용자가 다른 앱으로 갔다. 건드리지 않는다. */
    LEAVE_ALONE,

    /** 최상위 창을 못 읽었다. 잠깐 기다렸다가 다시 본다. */
    RETRY,
}

enum class ReturnResult { ARRIVED, LEFT_ALONE, GAVE_UP }

/**
 * 화면에 떠 있는 창 하나. [pkg]는 못 읽으면 null, [heightPercent]는 화면 높이 대비 창 높이(0~100),
 * [ignorable]은 키보드·접근성 오버레이처럼 "화면 맨 위"를 판단할 때 빼야 하는 창.
 */
data class WindowSnap(val pkg: String?, val heightPercent: Int = 100, val ignorable: Boolean = false)

/** 돌아오기 시도 결과. [backs]는 누른 뒤로 가기 횟수, [lastPackage]는 마지막에 본 최상위 패키지. */
data class ReturnReport(val result: ReturnResult, val backs: Int, val lastPackage: String?) {
    fun logLine(): String = "앱 복귀: ${result.name}, 뒤로 가기 ${backs}번, 마지막 화면 ${lastPackage ?: "모름"}"
}

/** 판정과 반복 규칙 (순수 함수). 뒤로 가기·대기·창 읽기는 호출하는 쪽이 넘긴다. */
object AppReturn {
    const val MAX_ATTEMPTS = 5

    /** 뒤로 가기 뒤 창이 바뀌었는지 보는 간격·횟수. 합쳐서 약 1.5초. */
    const val POLL_MS = 250L
    const val MAX_POLLS = 6

    /** 창이 바뀐 걸 본 뒤 화면 트리가 가라앉도록 한 번 더 쉰다. */
    const val SETTLE_MS = 250L

    /** 상태 표시줄·내비게이션 바도 systemui 창이다. 화면의 이 비율 미만이면 패널이 아니라 막대로 본다. */
    const val PANEL_MIN_HEIGHT_PERCENT = 20

    /** 접근성 복원이 여는 시스템 화면: 빠른 설정, 설정, 삼성 설정. */
    val SYSTEM_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.samsung.android.settings",
    )

    /** 앱 위에 항상 떠 있는 시스템 오버레이. 삼성 엣지 제스처(옆에서 밀어 뒤로 가기) 터치 영역 등은 "맨 위 화면"으로 치지 않는다. */
    val OVERLAY_PACKAGES: Set<String> = setOf(
        "com.samsung.android.sidegesturepad",
    )

    /** 창 목록(맨 위 창이 먼저)에서 실제로 화면 맨 위에 있는 창의 패키지. 활성 창이 아니라 쌓인 순서를 본다. */
    fun topPackage(windowsTopFirst: List<WindowSnap>): String? =
        windowsTopFirst.firstOrNull { w ->
            w.pkg != null && !w.ignorable && w.pkg !in OVERLAY_PACKAGES &&
                !(w.pkg == "com.android.systemui" && w.heightPercent < PANEL_MIN_HEIGHT_PERCENT)
        }?.pkg

    fun decide(topPackage: String?, appPackage: String): ReturnStep = when {
        topPackage == null -> ReturnStep.RETRY
        topPackage == appPackage -> ReturnStep.ARRIVED
        topPackage in SYSTEM_PACKAGES -> ReturnStep.PRESS_BACK
        else -> ReturnStep.LEAVE_ALONE
    }

    /**
     * 최대 [MAX_ATTEMPTS]번만 돈다. 매번 [readWindows]로 새로 읽어 판정하므로 이미 앱이면 뒤로 가기를 누르지 않는다.
     * 뒤로 가기를 누른 뒤에는 창 목록이 바뀔 때까지 [POLL_MS] 간격으로 최대 [MAX_POLLS]번 보고,
     * 바뀌었으면 [SETTLE_MS] 더 쉰다. 안 바뀌어도 시도 횟수만 쓰고 넘어간다.
     */
    fun run(
        appPackage: String,
        readWindows: () -> List<WindowSnap>,
        pressBack: () -> Unit,
        sleep: (Long) -> Unit,
    ): ReturnReport {
        var backs = 0
        var last: String? = null
        repeat(MAX_ATTEMPTS) {
            val before = readWindows()
            last = topPackage(before) ?: last
            when (decide(topPackage(before), appPackage)) {
                ReturnStep.ARRIVED -> return ReturnReport(ReturnResult.ARRIVED, backs, last)
                ReturnStep.LEAVE_ALONE -> return ReturnReport(ReturnResult.LEFT_ALONE, backs, last)
                ReturnStep.PRESS_BACK -> {
                    pressBack(); backs++
                    var changed = false
                    repeat(MAX_POLLS) {
                        if (!changed) {
                            sleep(POLL_MS)
                            if (readWindows() != before) changed = true
                        }
                    }
                    if (changed) sleep(SETTLE_MS)
                }
                ReturnStep.RETRY -> sleep(POLL_MS)
            }
        }
        // 마지막으로 한 번 더 보고 결과를 정한다 (마지막 뒤로 가기가 먹었을 수 있다)
        val finalTop = topPackage(readWindows())
        last = finalTop ?: last
        return when (decide(finalTop, appPackage)) {
            ReturnStep.ARRIVED -> ReturnReport(ReturnResult.ARRIVED, backs, last)
            ReturnStep.LEAVE_ALONE -> ReturnReport(ReturnResult.LEFT_ALONE, backs, last)
            else -> ReturnReport(ReturnResult.GAVE_UP, backs, last)
        }
    }
}
