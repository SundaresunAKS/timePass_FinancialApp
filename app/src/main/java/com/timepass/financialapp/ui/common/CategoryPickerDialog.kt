package com.timepass.financialapp.ui.common

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.DialogCategoryPickerBinding
import com.timepass.financialapp.databinding.ItemCategoryPickerBinding
import com.timepass.financialapp.ui.category.CategoryPalette
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class CategoryPickerDialog : BottomSheetDialogFragment() {

    private var _binding: DialogCategoryPickerBinding? = null
    private val binding get() = _binding!!

    private val transactionId: Long by lazy {
        requireArguments().getLong(ARG_TRANSACTION_ID)
    }

    /** Direction of the transaction being categorised; decides which categories lead. */
    private val isIncome: Boolean by lazy {
        requireArguments().getBoolean(ARG_IS_INCOME)
    }

    /** Set once the user asks to see categories outside the matching direction. */
    private var showingAll = false

    /** True when the transaction already has a category, i.e. opened from Tagged. */
    private val isTagged: Boolean by lazy {
        requireArguments().getBoolean(ARG_IS_TAGGED)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogCategoryPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = CategoryPickerAdapter { category ->
            setFragmentResult(
                RESULT_KEY,
                bundleOf(
                    ARG_TRANSACTION_ID to transactionId,
                    ARG_CATEGORY_ID to category.id,
                    ARG_NOTE to binding.etNote.text?.toString()
                )
            )
            dismiss()
        }

        binding.recyclerCategories.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerCategories.adapter = adapter

        val app = requireActivity().application as FinancialApp
        val categoryDao = app.database.categoryDao()

        binding.tvTitle.setText(
            if (isIncome) R.string.pick_category_for_money_in
            else R.string.pick_category_for_money_out
        )

        // Scoped to the view: getAllCategories() is a Flow that never completes, so an
        // unscoped CoroutineScope would outlive the dialog and push lists into a dead
        // adapter forever.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                categoryDao.getAllCategories().collect { all ->
                    render(adapter, all)
                }
            }
        }

        binding.etNote.setText(requireArguments().getString(ARG_EXISTING_NOTE).orEmpty())

        // The note sits at the top of the sheet, so scrolling all the way up is what puts
        // it above the keyboard. Scrolling to tilNote.bottom instead -- which is what this
        // did while the note was lower down -- now drives the field off the top edge.
        binding.etNote.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) return@setOnFocusChangeListener
            binding.scrollRoot.postDelayed({
                _binding?.scrollRoot?.smoothScrollTo(0, 0)
            }, KEYBOARD_SETTLE_MS)
        }

        binding.btnUntag.visibility = if (isTagged) View.VISIBLE else View.GONE
        binding.btnUntag.setOnClickListener {
            setFragmentResult(
                RESULT_KEY,
                bundleOf(
                    ARG_TRANSACTION_ID to transactionId,
                    ARG_UNTAG to true
                )
            )
            dismiss()
        }

        binding.btnShowAll.setOnClickListener {
            showingAll = true
            binding.btnShowAll.visibility = View.GONE
            lastCategories?.let { render(adapter, it) }
        }
    }

    private var lastCategories: List<CategoryEntity>? = null

    /**
     * Shows only the categories matching the transaction's direction -- an income
     * transaction should not offer "Fuel". The full list stays one tap away, because the
     * isIncome flag is the user's own and they may have set it differently than expected.
     */
    private fun render(adapter: CategoryPickerAdapter, all: List<CategoryEntity>) {
        lastCategories = all
        val matching = all.filter { it.isIncome == isIncome }
        val shown = if (showingAll || matching.isEmpty()) all else matching
        adapter.submitList(shown)

        val hidden = all.size - shown.size
        binding.btnShowAll.visibility = if (hidden > 0) View.VISIBLE else View.GONE
    }

    override fun onStart() {
        super.onStart()

        // ADJUST_RESIZE so the sheet shrinks rather than letting the IME cover the note;
        // STATE_HIDDEN so it opens showing the grid, since the note is optional and most
        // picks never touch it. The keyboard appears when the note is actually tapped.
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )

        // A half-expanded sheet has even less room once the keyboard appears, so go
        // straight to fully expanded and stay there.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Gives the IME time to finish animating before measuring where to scroll. */
        private const val KEYBOARD_SETTLE_MS = 250L

        const val RESULT_KEY = "category_selected"
        const val ARG_TRANSACTION_ID = "transaction_id"
        const val ARG_CATEGORY_ID = "category_id"
        const val ARG_IS_INCOME = "is_income"
        const val ARG_NOTE = "note"
        const val ARG_IS_TAGGED = "is_tagged"
        const val ARG_EXISTING_NOTE = "existing_note"
        const val ARG_UNTAG = "untag"

        fun show(
            fragmentManager: FragmentManager,
            transactionId: Long,
            isIncome: Boolean,
            isTagged: Boolean = false,
            existingNote: String? = null
        ) {
            val dialog = CategoryPickerDialog().apply {
                arguments = bundleOf(
                    ARG_TRANSACTION_ID to transactionId,
                    ARG_IS_INCOME to isIncome,
                    ARG_IS_TAGGED to isTagged,
                    ARG_EXISTING_NOTE to existingNote
                )
            }
            dialog.show(fragmentManager, "CategoryPickerDialog")
        }
    }

    class CategoryPickerAdapter(
        private val onCategorySelected: (CategoryEntity) -> Unit
    ) : ListAdapter<CategoryEntity, CategoryPickerAdapter.ViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemCategoryPickerBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(
            private val binding: ItemCategoryPickerBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(category: CategoryEntity) {
                binding.tvName.text = category.name

                // Emoji reads faster than a bare colour; initials are the fallback for a
                // category that has not been given one.
                binding.viewColor.text =
                    category.emoji ?: CategoryPalette.initials(category.name)
                binding.viewColor.textSize = if (category.emoji != null) 18f else 13f

                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(TransactionStyling.parseCategoryColor(category.colorHex, Color.GRAY))
                }
                binding.viewColor.background = drawable

                binding.root.setOnClickListener {
                    onCategorySelected(category)
                }
            }
        }

        class DiffCallback : DiffUtil.ItemCallback<CategoryEntity>() {
            override fun areItemsTheSame(oldItem: CategoryEntity, newItem: CategoryEntity) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: CategoryEntity, newItem: CategoryEntity) =
                oldItem == newItem
        }
    }
}
