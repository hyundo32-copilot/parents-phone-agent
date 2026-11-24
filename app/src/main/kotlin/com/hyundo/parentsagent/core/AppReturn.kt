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

    /**
     * 홈 화면(런처)이 맨 위다. 우리가 뒤로 가기를 눌러 설정 백스택을 다 쓴 뒤 앱 화면까지 닫은 것이다
     * (낡은 창 목록을 보고 한 번 더 누른 경우 등). 뒤로 가기로는 못 돌아오므로 앱을 다시 연다.
     * 단, 뒤로 가기를 한 번도 누르지 않았으면 사용자가 직접 홈으로 간 것이므로 다시 열지 않는다.
     */
    REOPEN_APP,
}

/** [NO_SERVICE]: 접근성 서비스가 꺼져 있어 시도조차 못 했다. [REOPENED]: 홈 화면에서 앱을 다시 열어 돌아왔다. */
enum class ReturnResult { ARRIVED, LEFT_ALONE, GAVE_UP, REOPENED, NO_SERVICE }

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

    /** [homePackages]: 이 폰의 홈 화면(런처) 패키지. 비우면 런처도 다른 앱처럼 LEAVE_ALONE으로 본다. */
    fun decide(topPackage: String?, appPackage: String, homePackages: Set<String> = emptySet()): ReturnStep = when {
        topPackage == null -> ReturnStep.RETRY
        topPackage == appPackage -> ReturnStep.ARRIVED
        topPackage in SYSTEM_PACKAGES -> ReturnStep.PRESS_BACK
        topPackage in homePackages -> ReturnStep.REOPEN_APP
        else -> ReturnStep.LEAVE_ALONE
    }

    /** 앱을 다시 연 뒤 앱 창이 맨 위에 오는지 [POLL_MS] 간격으로 최대 [MAX_POLLS]번 본다. */
    private fun reopen(
        appPackage: String,
        backs: Int,
        lastSeen: String?,
        readWindows: () -> List<WindowSnap>,
        reopenApp: () -> Unit,
        sleep: (Long) -> Unit,
    ): ReturnReport {
        reopenApp()
        var last = lastSeen
        repeat(MAX_POLLS) {
            sleep(POLL_MS)
            val top = topPackage(readWindows())
            last = top ?: last
            if (top == appPackage) return ReturnReport(ReturnResult.REOPENED, backs, last)
        }
        return ReturnReport(ReturnResult.GAVE_UP, backs, last)
    }

    /**
     * 최대 [MAX_ATTEMPTS]번만 돈다. 매번 [readWindows]로 새로 읽어 판정하므로 이미 앱이면 뒤로 가기를 누르지 않는다.
     * 뒤로 가기를 누른 뒤에는 창 목록이 바뀔 때까지 [POLL_MS] 간격으로 최대 [MAX_POLLS]번 보고,
     * 바뀌었으면 [SETTLE_MS] 더 쉰다. 안 바뀌어도 시도 횟수만 쓰고 넘어간다.
     * 홈 화면([homePackages])이 맨 위인데 우리가 뒤로 가기를 한 번이라도 눌렀으면 [reopenApp]으로 앱을 한 번 다시 연다.
     * 뒤로 가기를 누른 적 없으면 사용자가 직접 홈으로 간 것이므로 건드리지 않는다.
     */
    fun run(
        appPackage: String,
        readWindows: () -> List<WindowSnap>,
        pressBack: () -> Unit,
        sleep: (Long) -> Unit,
    ): ReturnReport = run(appPackage, readWindows, pressBack, sleep, emptySet(), {})

    fun run(
        appPackage: String,
        readWindows: () -> List<WindowSnap>,
        pressBack: () -> Unit,
        sleep: (Long) -> Unit,
        homePackages: Set<String>,
        reopenApp: () -> Unit,
    ): ReturnReport {
        var backs = 0
        var last: String? = null
        repeat(MAX_ATTEMPTS) {
            val before = readWindows()
            last = topPackage(before) ?: last
            when (decide(topPackage(before), appPackage, homePackages)) {
                ReturnStep.ARRIVED -> return ReturnReport(ReturnResult.ARRIVED, backs, last)
                ReturnStep.LEAVE_ALONE -> return ReturnReport(ReturnResult.LEFT_ALONE, backs, last)
                ReturnStep.REOPEN_APP -> if (backs > 0) {
                    return reopen(appPackage, backs, last, readWindows, reopenApp, sleep)
                } else {
                    return ReturnReport(ReturnResult.LEFT_ALONE, backs, last)
                }
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
        return when (decide(finalTop, appPackage, homePackages)) {
            ReturnStep.ARRIVED -> ReturnReport(ReturnResult.ARRIVED, backs, last)
            ReturnStep.LEAVE_ALONE -> ReturnReport(ReturnResult.LEFT_ALONE, backs, last)
            ReturnStep.REOPEN_APP -> if (backs > 0) {
                reopen(appPackage, backs, last, readWindows, reopenApp, sleep)
            } else {
                ReturnReport(ReturnResult.LEFT_ALONE, backs, last)
            }
            else -> ReturnReport(ReturnResult.GAVE_UP, backs, last)
        }
    }
}
