package com.timepass.financialapp.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.DialogMonthYearPickerBinding
import com.timepass.financialapp.util.DateUtils
import java.time.YearMonth

/**
 * Month + year picker: a year stepper above a grid of twelve months.
 *
 * Deliberately a plain AlertDialog with a callback rather than a DialogFragment -- there
 * is no fragment-result plumbing to get wrong, and the only cost is that the dialog closes
 * on rotation.
 */
object MonthYearPickerDialog {

    private val MONTH_LABELS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /**
     * @param current the month to preselect
     * @param onPicked invoked with the chosen month; not called if the user cancels
     */
    fun show(context: Context, current: YearMonth, onPicked: (YearMonth) -> Unit) {
        val binding = DialogMonthYearPickerBinding.inflate(LayoutInflater.from(context))

        // Selectable range: from the app's first tracked month through the current one.
        // Later months can only hold transactions if the phone clock is wrong.
        val minMonth = DateUtils.FIRST_MONTH
        val maxMonth = maxOf(DateUtils.currentMonth(), current)

        var shownYear = current.year

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.select_month_year)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun render() {
            binding.tvYear.text = shownYear.toString()
            binding.btnPrevYear.isEnabled = shownYear > minMonth.year
            binding.btnNextYear.isEnabled = shownYear < maxMonth.year
            binding.btnPrevYear.alpha = if (binding.btnPrevYear.isEnabled) 1f else 0.35f
            binding.btnNextYear.alpha = if (binding.btnNextYear.isEnabled) 1f else 0.35f

            binding.gridMonths.removeAllViews()
            for (month in 1..12) {
                val ym = YearMonth.of(shownYear, month)
                val enabled = ym >= minMonth && ym <= maxMonth
                val selected = ym == current

                val cell = buildCell(context, MONTH_LABELS[month - 1], enabled, selected)
                cell.setOnClickListener {
                    onPicked(ym)
                    dialog.dismiss()
                }
                cell.isEnabled = enabled
                cell.isClickable = enabled

                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                }
                binding.gridMonths.addView(cell, params)
            }
        }

        binding.btnPrevYear.setOnClickListener { shownYear--; render() }
        binding.btnNextYear.setOnClickListener { shownYear++; render() }

        render()
        dialog.show()
    }

    private fun buildCell(
        context: Context,
        label: String,
        enabled: Boolean,
        selected: Boolean
    ): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(context, 14), 0, dp(context, 14))

        val accent = ContextCompat.getColor(context, R.color.primary)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 12).toFloat()
            when {
                selected -> setColor(accent)
                else -> {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(context, 1), ContextCompat.getColor(context, R.color.outline))
                }
            }
        }
        setTextColor(
            when {
                selected -> ContextCompat.getColor(context, R.color.on_primary)
                enabled -> ContextCompat.getColor(context, R.color.on_surface)
                else -> ContextCompat.getColor(context, R.color.on_surface_variant)
            }
        )
        alpha = if (enabled) 1f else 0.4f
        if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
