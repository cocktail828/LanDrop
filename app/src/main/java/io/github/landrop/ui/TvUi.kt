package io.github.landrop.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/** Shared native TV surfaces and focus states. Keeps D-pad feedback independent of touch ripples. */
object TvUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun muted(context: Context): Int = Color.parseColor(if (ThemeColors.isDark(context)) "#AAB6C8" else "#566477")
    fun accent(context: Context): Int = Color.parseColor(if (ThemeColors.isDark(context)) "#72B6FF" else "#1769B8")
    fun surface(context: Context): Int = Color.parseColor(if (ThemeColors.isDark(context)) "#1B212C" else "#FFFFFF")
    private fun shape(context: Context, fill: Int, stroke: Int = Color.TRANSPARENT, width: Int = 1) = GradientDrawable().apply {
        cornerRadius = dp(context, 10).toFloat()
        setColor(fill)
        setStroke(dp(context, width), stroke)
    }
    fun focusBackground(context: Context, primary: Boolean = false, panel: Boolean = false) = StateListDrawable().apply {
        val fill = if (primary) ThemeColors.primaryColor(context) else if (panel) surface(context) else Color.TRANSPARENT
        val border = Color.parseColor(if (ThemeColors.isDark(context)) "#333D4C" else "#D5DCE6")
        addState(intArrayOf(android.R.attr.state_focused), shape(context, fill, accent(context), 3))
        addState(intArrayOf(android.R.attr.state_pressed), shape(context, if (primary) ThemeColors.pressedColor(context) else Color.parseColor(if (ThemeColors.isDark(context)) "#1D3046" else "#E4EFFB"), accent(context), 2))
        addState(intArrayOf(), shape(context, fill, if (panel) border else Color.TRANSPARENT))
    }
    fun apply(view: View) {
        val context = view.context
        val tag = view.tag as? String
        when (tag) {
            "tv-muted" -> (view as? TextView)?.setTextColor(muted(context))
            "tv-accent" -> (view as? TextView)?.setTextColor(accent(context))
            "tv-panel" -> view.background = focusBackground(context, panel = true)
            "tv-primary", "tv-outline", "tv-icon", "tv-row", "tv-card" -> {
                view.backgroundTintList = null
                view.background = focusBackground(context, primary = tag == "tv-primary", panel = tag == "tv-card")
                view.minimumHeight = maxOf(view.minimumHeight, dp(context, 48))
                view.isFocusable = true
                if (view is TextView) {
                    view.setTextColor(if (tag == "tv-primary") Color.WHITE else ThemeColors.primaryTextColor(context))
                    androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(if (tag == "tv-primary") Color.WHITE else accent(context)))
                }
                if (view is ImageView) view.imageTintList = ColorStateList.valueOf(muted(context))
            }
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) apply(view.getChildAt(i))
    }
    fun styleDialog(dialog: AlertDialog) {
        val context = dialog.context
        dialog.window?.apply {
            setBackgroundDrawable(shape(context, surface(context)))
            val width = minOf(dp(context, 480), context.resources.displayMetrics.widthPixels - dp(context, 64))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE).forEach { which ->
            dialog.getButton(which)?.let { button ->
                button.tag = if (which == AlertDialog.BUTTON_POSITIVE) "tv-primary" else "tv-outline"
                button.isAllCaps = false
                button.textSize = 16f
                apply(button)
            }
        }
    }
}
