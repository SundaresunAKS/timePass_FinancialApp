package com.timepass.financialapp.ui.common

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.R

/**
 * Checkbox picker backing every "choose one or more" filter.
 *
 * Selecting nothing means "no restriction" rather than "match nothing", which is what lets
 * a single chip serve both the unfiltered and the narrowed case.
 */
object MultiSelectDialog {

    /**
     * @param options value/label pairs, in display order
     * @param selected values currently chosen
     * @param onApply receives the new selection; an empty set clears the filter
     */
    fun <T> show(
        context: Context,
        titleRes: Int,
        options: List<Pair<T, String>>,
        selected: Set<T>,
        onApply: (Set<T>) -> Unit
    ) {
        if (options.isEmpty()) return

        val labels = options.map { it.second }.toTypedArray()
        val checked = BooleanArray(options.size) { options[it].first in selected }

        MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.filter_apply) { _, _ ->
                onApply(
                    options.filterIndexed { index, _ -> checked[index] }
                        .map { it.first }
                        .toSet()
                )
            }
            .setNeutralButton(R.string.filter_clear) { _, _ -> onApply(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
