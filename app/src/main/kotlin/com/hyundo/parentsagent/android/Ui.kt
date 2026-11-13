package com.hyundo.parentsagent.android

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

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
}
