package com.hyundo.parentsagent.android

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hyundo.parentsagent.core.LogKind
import com.hyundo.parentsagent.core.LogPresentation
import com.hyundo.parentsagent.core.LogRow
import com.hyundo.parentsagent.core.LongPressGate
import com.hyundo.parentsagent.core.ResultNote
import com.hyundo.parentsagent.core.StatusLevel

/** 큰 글씨·큰 버튼 (부모님이 보는 화면). 레이아웃 XML 없이 코드로 만든다. */
object Ui {
    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, s: String, sizeSp: Float = 20f, bold: Boolean = false) = TextView(c).apply {
        text = s
        textSize = sizeSp
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(c, 8), 0, dp(c, 4))
    }

    // 팔레트: 색 하나에 뜻 하나. 주 동작 파랑, 정상 초록·주의 노랑·위험 빨강은 상태에만, 나머지는 회색 보조.
    const val PRIMARY = "#1565C0"
    const val OK = "#2E7D32"
    const val WARN = "#F9A825"
    const val BAD = "#C62828"
    const val BORDER = "#E0E0E0"
    const val GREY_STROKE = "#9E9E9E"
    const val GREY_TEXT = "#424242"
    const val SCREEN_BG = "#F5F5F5"

    /** 주 동작(파랑)·[중단](빨강) 버튼. 관리·가족 영역 버튼은 [manageButton]. */
    fun button(c: Context, s: String, color: String = PRIMARY, onClick: () -> Unit) = Button(c).apply {
        text = s
        textSize = 22f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(color))
        minimumHeight = dp(c, 64)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(c, 8) }
        setOnClickListener { onClick() }
    }

    private fun rounded(c: Context, fill: String, radiusDp: Int = 16, strokeColor: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(fill))
        cornerRadius = dp(c, radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(c, 1), Color.parseColor(strokeColor))
    }

    private fun fullWidth(c: Context, topMarginDp: Int = 12) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(c, topMarginDp) }

    /** 흰 바탕에 얇은 테두리의 둥근 구역. 모서리 16dp, 테두리 1dp, 안쪽 여백 16dp, 카드 간격 12dp. */
    fun card(c: Context, vararg children: View) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, "#FFFFFF", strokeColor = BORDER)
        setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16))
        layoutParams = fullWidth(c)
        children.forEach { addView(it) }
    }

    /**
     * 부모님이 누르는 큰 타일 버튼 (아이콘 + 제목 + 보조 문구).
     * 높이는 최소 112dp라 시스템 글꼴이 커지면 늘어난다.
     */
    fun tile(c: Context, icon: String, title: String, sub: String?, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(c, PRIMARY, radiusDp = 20)
        minimumHeight = dp(c, 112)
        setPadding(dp(c, 20), dp(c, 16), dp(c, 20), dp(c, 16))
        layoutParams = fullWidth(c, 16)
        isClickable = true
        isFocusable = true
        contentDescription = if (sub.isNullOrEmpty()) title else "$title, $sub"
        setOnClickListener { onClick() }
        addView(TextView(c).apply {
            text = icon; textSize = 32f; setPadding(0, 0, dp(c, 16), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(TextView(c).apply { text = title; textSize = 27f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
            if (!sub.isNullOrEmpty()) addView(TextView(c).apply { text = sub; textSize = 20f; setTextColor(Color.WHITE) })
        })
    }

    /** 관리·가족 영역의 보조 버튼: 흰 바탕, 회색 글씨, 회색 1dp 테두리. */
    fun manageButton(c: Context, s: String, onClick: () -> Unit) = Button(c).apply {
        text = s
        textSize = 20f
        isAllCaps = false
        setTextColor(Color.parseColor(GREY_TEXT))
        background = rounded(c, "#FFFFFF", radiusDp = 12, strokeColor = GREY_STROKE)
        minimumHeight = dp(c, 64)
        layoutParams = fullWidth(c, 8)
        setOnClickListener { onClick() }
    }

    /** 상태 등급의 바탕색·글씨색·기호. 노랑일 때만 검은 글씨. */
    private fun levelStyle(level: StatusLevel): Triple<String, Int, String> = when (level) {
        StatusLevel.GOOD -> Triple(OK, Color.WHITE, "✓")
        StatusLevel.WARN -> Triple(WARN, Color.BLACK, "!")
        StatusLevel.BAD -> Triple(BAD, Color.WHITE, "✕")
    }

    /** 상태 기호 배지. 색만으로 뜻을 전하지 않도록 기호를 같이 쓴다. */
    fun statusBadge(c: Context, level: StatusLevel): TextView {
        val (_, fg, icon) = levelStyle(level)
        return TextView(c).apply {
            text = icon; textSize = 56f; setTextColor(fg)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, dp(c, 20), 0)
        }
    }

    /** 부모님 영역 기록 한 줄: 기호 + 시각 + 문장 (+ 반복 횟수). */
    fun logRowView(c: Context, row: LogRow, nowMillis: Long): View = TextView(c).apply {
        text = LogPresentation.parentLine(row, nowMillis)
        textSize = 20f
        setTextColor(if (row.kind == LogKind.FAILED) Color.parseColor(BAD) else Color.BLACK)
        setPadding(0, dp(c, 8), 0, dp(c, 8))
    }

    /**
     * 맨 위 상태 카드. 색만으로 뜻을 전하지 않도록 큰 기호(✓ / ! / ✕)를 같이 쓴다.
     * [onSpeak]가 있으면 카드 아래에 전체 폭 [읽어 주기] 버튼을 둔다. 버튼은 어느 색 위에서도 보이게 흰 바탕·검은 글씨·회색 테두리.
     */
    fun statusCard(c: Context, level: StatusLevel, title: String, sub: String, onSpeak: (() -> Unit)? = null): LinearLayout {
        val (bg, fg, _) = levelStyle(level)
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c, bg, radiusDp = 20)
            setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20))
            layoutParams = fullWidth(c, 0)
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(c, 80)
                addView(statusBadge(c, level))
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(c).apply { text = title; textSize = 30f; setTextColor(fg); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                    addView(TextView(c).apply { text = sub; textSize = 20f; setTextColor(fg); setPadding(0, dp(c, 4), 0, 0) })
                })
            })
            if (onSpeak != null) addView(Button(c).apply {
                text = "🔊 읽어 주기"
                textSize = 22f
                isAllCaps = false
                setTextColor(Color.BLACK)
                background = rounded(c, "#FFFFFF", radiusDp = 12, strokeColor = GREY_STROKE)
                minimumHeight = dp(c, 64)
                contentDescription = "상태를 소리로 읽어 주기"
                layoutParams = fullWidth(c, 12)
                setOnClickListener { onSpeak() }
            })
        }
    }

    /**
     * 부모님이 방금 한 동작의 결과 카드. 왼쪽 띠 색은 종류(FIXED 초록, FAILED 빨강, INFO 파랑)이고 기호를 같이 쓴다.
     * 본문은 줄임 없이 다 보인다. 카드 전체가 하나의 contentDescription이며 바뀔 때 TalkBack이 읽도록 liveRegion을 준다.
     */
    fun resultCard(c: Context, note: ResultNote, timeText: String, onDismiss: () -> Unit): LinearLayout {
        val (band, icon) = when (note.kind) {
            LogKind.FIXED -> OK to "✓"
            LogKind.FAILED -> BAD to "✕"
            else -> PRIMARY to "ℹ"
        }
        val body = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(text(c, note.text, 24f, bold = true))
            if (note.extra != null) addView(text(c, note.extra, 18f))
            addView(text(c, timeText, 18f).apply { setTextColor(Color.parseColor(GREY_TEXT)) })
        }
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(View(c).apply {
                setBackgroundColor(Color.parseColor(band))
                layoutParams = LinearLayout.LayoutParams(dp(c, 8), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = dp(c, 12) }
            })
            addView(TextView(c).apply {
                text = icon; textSize = 28f; setTextColor(Color.parseColor(band))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(c, 4), dp(c, 12), 0)
            })
            addView(body)
        }
        return card(c, row, manageButton(c, "확인했어요", onDismiss)).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isFocusable = true
            contentDescription = listOfNotNull(note.text, note.extra, timeText).joinToString(". ")
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    /** 작은 안내 띠 (데모 모드 표시 등). */
    fun strip(c: Context, s: String, fill: String = "#E0E0E0") = TextView(c).apply {
        text = s; textSize = 16f; setTextColor(Color.BLACK)
        background = rounded(c, fill, radiusDp = 8)
        setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6))
        layoutParams = fullWidth(c, 8)
    }

    /**
     * 접히는 구역. 펼침 상태는 호출한 쪽이 기억한다 (render()가 뷰를 전부 다시 만들기 때문).
     * [longPressToOpen]이면 닫힌 상태에서는 2초 길게 눌러야 열린다 (부모님이 우연히 열기 어렵게). 닫는 건 짧게 눌러도 된다.
     */
    fun collapsible(
        c: Context, closedTitle: String, openTitle: String, open: Boolean,
        longPressToOpen: Boolean, onToggle: (Boolean) -> Unit, body: View,
    ): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; layoutParams = fullWidth(c) }
        var isOpen = open
        val header = TextView(c).apply {
            textSize = 20f; setTextColor(Color.parseColor("#37474F"))
            background = rounded(c, "#ECEFF1", radiusDp = 12)
            setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16))
            minimumHeight = dp(c, 64)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = fullWidth(c, 0)
        }
        fun apply() {
            header.text = if (isOpen) openTitle else closedTitle
            body.visibility = if (isOpen) View.VISIBLE else View.GONE
        }
        fun set(v: Boolean) { isOpen = v; apply(); onToggle(v) }
        val handler = Handler(Looper.getMainLooper())
        val gate = LongPressGate()
        val openTask = Runnable { if (gate.onTimeout()) set(true) }
        if (longPressToOpen) {
            header.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> if (gate.onDown(isOpen)) handler.postDelayed(openTask, LONG_PRESS_MS)
                    MotionEvent.ACTION_UP -> {
                        handler.removeCallbacks(openTask)
                        // 길게 눌러 열린 직후의 손 떼기는 소비해 클릭(=닫기)이 발생하지 않게 한다.
                        if (gate.onUp()) return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(openTask); gate.onCancel() }
                    MotionEvent.ACTION_MOVE ->
                        if (e.x < 0 || e.y < 0 || e.x > v.width || e.y > v.height) {
                            handler.removeCallbacks(openTask); gate.onLeave()
                        }
                }
                false
            }
            header.setOnClickListener { if (isOpen) set(false) }
        } else {
            header.setOnClickListener { set(!isOpen) }
        }
        apply()
        box.addView(header)
        box.addView(body)
        return box
    }

    /** 구역을 나누는 얇은 선과 이름 ("가족·관리용" 등). */
    fun divider(c: Context, label: String) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = fullWidth(c, 24)
        addView(View(c).apply {
            setBackgroundColor(Color.parseColor("#B0BEC5"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1))
        })
        addView(text(c, label, 16f).apply { setTextColor(Color.parseColor("#546E7A")) })
    }

    const val LONG_PRESS_MS = 2_000L
}
