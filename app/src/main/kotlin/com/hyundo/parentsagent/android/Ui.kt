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
import com.hyundo.parentsagent.core.LongPressGate
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

    fun button(c: Context, s: String, color: String = "#1565C0", onClick: () -> Unit) = Button(c).apply {
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

    /** 흰 바탕에 얇은 테두리의 둥근 구역. 안에 뷰를 addView 해서 쓴다. */
    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c, "#FFFFFF", strokeColor = "#CFD8DC")
        setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 16))
        layoutParams = fullWidth(c)
    }

    /** 부모님이 누르는 큰 버튼. 높이는 최소값이라 글씨가 커져도 안 잘린다. */
    fun bigButton(c: Context, title: String, sub: String, color: String = "#1565C0", onClick: () -> Unit) = Button(c).apply {
        text = "$title\n$sub"
        textSize = 24f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(c, color)
        minimumHeight = dp(c, 104)
        layoutParams = fullWidth(c)
        setOnClickListener { onClick() }
    }

    /** 맨 위 상태 카드. 색만으로 뜻을 전하지 않도록 큰 기호(✓ / ! / ✕)를 같이 쓴다. */
    fun statusCard(c: Context, level: StatusLevel, title: String, sub: String): LinearLayout {
        val (bg, fg, icon) = when (level) {
            StatusLevel.GOOD -> Triple("#2E7D32", Color.WHITE, "✓")
            StatusLevel.WARN -> Triple("#F9A825", Color.BLACK, "!")
            StatusLevel.BAD -> Triple("#C62828", Color.WHITE, "✕")
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(c, bg, radiusDp = 20)
            setPadding(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 20))
            minimumHeight = dp(c, 120)
            layoutParams = fullWidth(c, 0)
            addView(TextView(c).apply {
                text = icon; textSize = 56f; setTextColor(fg)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, dp(c, 20), 0)
            })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(c).apply { text = title; textSize = 30f; setTextColor(fg); setTypeface(typeface, android.graphics.Typeface.BOLD) })
                addView(TextView(c).apply { text = sub; textSize = 20f; setTextColor(fg); setPadding(0, dp(c, 4), 0, 0) })
            })
        }
    }

    /** 작은 안내 띠 (데모 모드 표시 등). */
    fun strip(c: Context, s: String, fill: String = "#E1BEE7") = TextView(c).apply {
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
