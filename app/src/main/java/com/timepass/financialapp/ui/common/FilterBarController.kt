package com.timepass.financialapp.ui.common

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.google.android.material.chip.Chip
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.ViewFilterBarBinding
import com.timepass.financialapp.util.DateUtils
import java.time.YearMonth

/**
 * Wires the shared filter bar to a screen's callbacks and renders filter state back into
 * it. Used by both list screens; [showCategoryFilter] is the only difference between them.
 *
 * Every chip except Clear opens a multi-select dialog. These are Material filter chips,
 * which toggle themselves when tapped, so each handler immediately restores the checked
 * state from the filter -- the dialog result decides it, not the tap.
 */
class FilterBarController(
    private val binding: ViewFilterBarBinding,
    private val showCategoryFilter: Boolean,
    private val onMonthPicked: (YearMonth) -> Unit,
    private val onPrevMonth: () -> Unit,
    private val onNextMonth: () -> Unit,
    private val onTypeClicked: () -> Unit,
    private val onModeClicked: () -> Unit,
    private val onSenderClicked: () -> Unit,
    private val onAccountClicked: () -> Unit,
    private val onCategoryClicked: () -> Unit,
    private val onSearchChanged: (String?) -> Unit,
    private val onClearFilters: () -> Unit
) {

    /** Guards programmatic writes in [render] from re-entering the listeners. */
    private var updating = false

    /** Last rendered state, so the dialog chips can restore their own checked look. */
    private var lastFilter: TransactionFilter? = null

    private var currentMonth: YearMonth = DateUtils.currentMonth()

    fun attach() {
        binding.btnPrevMonth.setOnClickListener { onPrevMonth() }
        binding.btnNextMonth.setOnClickListener { onNextMonth() }
        binding.btnMonthPicker.setOnClickListener {
            MonthYearPickerDialog.show(binding.root.context, currentMonth, onMonthPicked)
        }

        bindDialogChip(binding.chipTypeFilter, { it.types.isNotEmpty() }, onTypeClicked)
        bindDialogChip(binding.chipModeFilter, { it.paymentModes.isNotEmpty() }, onModeClicked)
        bindDialogChip(binding.chipSenderFilter, { it.senders.isNotEmpty() }, onSenderClicked)
        bindDialogChip(binding.chipAccountFilter, { it.accountRefs.isNotEmpty() }, onAccountClicked)

        binding.chipCategoryFilter.visibility = if (showCategoryFilter) View.VISIBLE else View.GONE
        bindDialogChip(binding.chipCategoryFilter, { it.categoryIds.isNotEmpty() }, onCategoryClicked)

        binding.chipClear.setOnClickListener { onClearFilters() }

        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.setText("")
            onSearchChanged(null)
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val text = s?.toString()
                binding.btnClearSearch.visibility =
                    if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
                onSearchChanged(text)
            }
        })
    }

    private fun bindDialogChip(
        chip: Chip,
        isActive: (TransactionFilter) -> Boolean,
        onClick: () -> Unit
    ) {
        chip.setOnClickListener {
            chip.isChecked = lastFilter?.let(isActive) ?: false
            onClick()
        }
    }

    /** Pushes filter state into the bar. Safe to call on every emission. */
    fun render(filter: TransactionFilter, categoryLabel: String? = null) {
        updating = true
        lastFilter = filter
        currentMonth = filter.month

        val context = binding.root.context
        binding.tvMonthYear.text = DateUtils.formatMonth(filter.month)

        applyChip(
            binding.chipTypeFilter,
            filter.types.size,
            context.getString(R.string.filter_direction),
            filter.types.singleOrNull()?.let {
                context.getString(
                    if (it == TypeFilter.CREDIT.dbValue) R.string.filter_credit
                    else R.string.filter_debit
                )
            }
        )
        applyChip(
            binding.chipModeFilter,
            filter.paymentModes.size,
            context.getString(R.string.filter_mode),
            filter.paymentModes.singleOrNull()?.let(TransactionStyling::modeLabel)
        )
        applyChip(
            binding.chipSenderFilter,
            filter.senders.size,
            context.getString(R.string.filter_sender),
            filter.senders.singleOrNull()
        )
        applyChip(
            binding.chipAccountFilter,
            filter.accountRefs.size,
            context.getString(R.string.filter_account),
            filter.accountRefs.singleOrNull()
        )
        if (showCategoryFilter) {
            applyChip(
                binding.chipCategoryFilter,
                filter.categoryIds.size,
                context.getString(R.string.filter_category),
                categoryLabel
            )
        }

        val search = filter.search.orEmpty()
        if (binding.etSearch.text.toString() != search) {
            binding.etSearch.setText(search)
            binding.etSearch.setSelection(search.length)
        }
        binding.btnClearSearch.visibility = if (search.isEmpty()) View.GONE else View.VISIBLE

        binding.chipClear.visibility = if (filter.activeCount > 0) View.VISIBLE else View.GONE

        updating = false
    }

    /**
     * One selection reads better as its own name ("UPI"); several read better as a count,
     * since the chip cannot fit them all.
     */
    private fun applyChip(chip: Chip, count: Int, defaultLabel: String, singleLabel: String?) {
        chip.isChecked = count > 0
        chip.text = when {
            count == 0 -> defaultLabel
            count == 1 && singleLabel != null -> singleLabel
            else -> chip.context.getString(R.string.filter_n_selected, count)
        }
    }

    fun setResultText(text: String) {
        binding.tvResultCount.text = text
    }
}
