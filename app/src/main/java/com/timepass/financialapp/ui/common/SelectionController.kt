package com.timepass.financialapp.ui.common

import android.view.View
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.ViewSelectionBarBinding

/**
 * Multi-select state for a transaction list, plus the contextual bar that acts on it.
 *
 * Selection lives here rather than in the ViewModel because it is view state: it should not
 * survive the screen going away, and it must clear when the underlying list changes out
 * from under it (after a bulk action, the selected rows have usually left the list).
 */
class SelectionController(
    private val binding: ViewSelectionBarBinding,
    private val action1Label: Int,
    private val action2Label: Int?,
    private val onAction1: (Set<Long>) -> Unit,
    private val onAction2: ((Set<Long>) -> Unit)?,
    private val action3Label: Int? = null,
    private val onAction3: ((Set<Long>) -> Unit)? = null,
    /**
     * Whether the third action applies to a given selection.
     *
     * A predicate rather than a setter the screen calls: attach() renders once during
     * construction, so anything that called back into this object at that moment would
     * touch a field the screen has not finished assigning. That crashed the Tagged screen
     * on every open.
     */
    private val action3AppliesTo: ((Set<Long>) -> Boolean)? = null,
    private val onChanged: (Set<Long>) -> Unit
) {

    private val selected = linkedSetOf<Long>()

    val isActive: Boolean get() = selected.isNotEmpty()

    fun attach() {
        binding.btnSelectionAction1.setText(action1Label)
        binding.btnSelectionAction1.setOnClickListener {
            if (selected.isNotEmpty()) onAction1(selected.toSet())
        }

        if (action2Label != null && onAction2 != null) {
            binding.btnSelectionAction2.visibility = View.VISIBLE
            binding.btnSelectionAction2.setText(action2Label)
            binding.btnSelectionAction2.setOnClickListener {
                if (selected.isNotEmpty()) onAction2.invoke(selected.toSet())
            }
        } else {
            binding.btnSelectionAction2.visibility = View.GONE
        }

        if (action3Label != null && onAction3 != null) {
            binding.btnSelectionAction3.setText(action3Label)
            binding.btnSelectionAction3.setOnClickListener {
                if (selected.isNotEmpty()) onAction3.invoke(selected.toSet())
            }
        }

        binding.btnCloseSelection.setOnClickListener { clear() }
        render()
    }

    /** Starts selection mode, or toggles a row once it has started. */
    fun toggle(id: Long) {
        if (!selected.add(id)) selected.remove(id)
        render()
    }

    fun clear() {
        if (selected.isEmpty()) return
        selected.clear()
        render()
    }

    /**
     * Drops ids that are no longer in the list, which is what happens to every selected
     * row after a bulk action moves it to another screen.
     */
    fun retainOnly(visibleIds: Set<Long>) {
        if (selected.retainAll(visibleIds)) render()
    }

    private fun render() {
        binding.root.visibility = if (selected.isEmpty()) View.GONE else View.VISIBLE

        val showAction3 = action3Label != null &&
            selected.isNotEmpty() &&
            action3AppliesTo?.invoke(selected.toSet()) == true
        binding.btnSelectionAction3.visibility = if (showAction3) View.VISIBLE else View.GONE
        binding.tvSelectionCount.text =
            binding.root.context.getString(R.string.selection_count, selected.size)
        onChanged(selected.toSet())
    }
}
