package com.timepass.financialapp.ui.category

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.DialogAddEditCategoryBinding
import com.timepass.financialapp.databinding.FragmentCategoryManagementBinding
import com.timepass.financialapp.ui.common.TransactionStyling
import kotlinx.coroutines.launch

class CategoryManagementFragment : Fragment() {

    private var _binding: FragmentCategoryManagementBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CategoryManagementViewModel by viewModels {
        val app = requireActivity().application as FinancialApp
        CategoryManagementViewModel.Factory(app.database.categoryDao(), app.database.transactionDao())
    }

    private lateinit var adapter: CategoryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategoryManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CategoryAdapter(
            onEdit = { row -> showCategoryDialog(row) },
            onDelete = { row -> confirmDelete(row) }
        )
        binding.recyclerView.adapter = adapter

        binding.fabAdd.setOnClickListener { showCategoryDialog(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rows.collect { rows ->
                    adapter.submitList(rows)
                    binding.tvSummary.text = getString(
                        R.string.category_summary,
                        rows.size,
                        rows.count { it.txnCount > 0 }
                    )
                }
            }
        }
    }

    /** One dialog for both add and edit; [existing] null means add. */
    private fun showCategoryDialog(existing: CategoryManagementViewModel.CategoryRow?) {
        val dialogBinding = DialogAddEditCategoryBinding.inflate(layoutInflater)
        val category = existing?.category

        var selectedColor = category?.colorHex
            ?: CategoryPalette.defaultFor(System.currentTimeMillis().toString())
        var selectedEmoji: String? = category?.emoji

        fun renderPreview() {
            val name = dialogBinding.etCategoryName.text?.toString()?.trim().orEmpty()
            val shown = name.ifEmpty { getString(R.string.category_name) }
            dialogBinding.tvPreviewName.text = shown
            dialogBinding.tvPreviewAvatar.text = selectedEmoji
                ?: if (name.isEmpty()) "?" else CategoryPalette.initials(name)
            dialogBinding.tvPreviewAvatar.textSize = if (selectedEmoji != null) 18f else 14f
            dialogBinding.tvPreviewAvatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(safeColor(selectedColor))
            }
        }

        // Redrawn on each pick so the tick moves to the newly selected swatch.
        fun refreshSwatches() {
            buildSwatches(dialogBinding.gridColors, selectedColor) { picked ->
                selectedColor = picked
                refreshSwatches()
                renderPreview()
            }
        }
        refreshSwatches()

        // Tapping the selected emoji clears it, so a category can go back to initials.
        fun refreshEmoji() {
            buildEmojiGrid(dialogBinding.gridEmoji, selectedEmoji) { picked ->
                selectedEmoji = if (picked == selectedEmoji) null else picked
                refreshEmoji()
                renderPreview()
            }
        }
        refreshEmoji()

        category?.let {
            dialogBinding.etCategoryName.setText(it.name)
            dialogBinding.switchIncome.isChecked = it.isIncome
        }
        renderPreview()

        dialogBinding.etCategoryName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderPreview()
        })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (category == null) R.string.add_category else R.string.edit_category)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = dialogBinding.etCategoryName.text?.toString()?.trim().orEmpty()
                val isIncome = dialogBinding.switchIncome.isChecked

                if (name.isEmpty()) {
                    snack(getString(R.string.category_name_required))
                    return@setPositiveButton
                }
                // The name column is uniquely indexed; inserting a duplicate would throw
                // inside the DAO coroutine and surface as nothing at all to the user.
                val clash = viewModel.rows.value.any {
                    it.category.name.equals(name, ignoreCase = true) &&
                        it.category.id != category?.id
                }
                if (clash) {
                    snack(getString(R.string.category_name_duplicate))
                    return@setPositiveButton
                }

                if (category == null) {
                    viewModel.addCategory(name, isIncome, selectedColor, selectedEmoji)
                } else {
                    viewModel.updateCategory(category.id, name, isIncome, selectedColor, selectedEmoji)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Renders the palette as tappable circles, ticking the selected one. */
    private fun buildSwatches(
        grid: GridLayout,
        selected: String,
        onPicked: (String) -> Unit
    ) {
        grid.removeAllViews()
        val size = dp(40)
        val margin = dp(4)

        CategoryPalette.COLORS.forEach { hex ->
            val swatch = ImageView(requireContext()).apply {
                contentDescription = getString(R.string.select_colour)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(safeColor(hex))
                }
                if (hex.equals(selected, ignoreCase = true)) {
                    setImageResource(R.drawable.ic_check)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                setOnClickListener { onPicked(hex) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(margin, margin, margin, margin)
            }
            swatch.layoutParams = params
            grid.addView(swatch, params)
        }
    }

    /** Emoji grid; the chosen one is ringed in the accent colour. */
    private fun buildEmojiGrid(
        grid: GridLayout,
        selected: String?,
        onPicked: (String) -> Unit
    ) {
        grid.removeAllViews()
        val size = dp(40)
        val margin = dp(3)
        val accent = ContextCompat.getColor(requireContext(), R.color.on_surface_variant)

        CategoryPalette.EMOJI.forEach { emoji ->
            val cell = TextView(requireContext()).apply {
                text = emoji
                gravity = android.view.Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
                includeFontPadding = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (emoji == selected) {
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(2), accent)
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
                setOnClickListener { onPicked(emoji) }
            }
            val params = GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins(margin, margin, margin, margin)
            }
            cell.layoutParams = params
            grid.addView(cell, params)
        }
    }

    private fun confirmDelete(row: CategoryManagementViewModel.CategoryRow) {
        val name = row.category.name

        // Deleting a category in use is allowed, but the consequence is stated up front:
        // its transactions return to the Uncategorized inbox rather than disappearing.
        val message = if (row.txnCount > 0) {
            resources.getQuantityString(
                R.plurals.category_delete_in_use_plural,
                row.txnCount,
                row.txnCount,
                name
            )
        } else {
            getString(R.string.category_delete_confirm, name)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_category)
            .setMessage(message)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteCategory(row.category.id)
                snack(getString(R.string.category_deleted, name))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun safeColor(hex: String): Int = TransactionStyling.parseCategoryColor(
        hex,
        ContextCompat.getColor(requireContext(), R.color.mode_unknown)
    )

    private fun snack(text: String) =
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }
}
