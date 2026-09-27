package com.timepass.financialapp.ui.category

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.ItemCategoryBinding
import com.timepass.financialapp.ui.common.TransactionStyling
import com.timepass.financialapp.util.CurrencyUtils

class CategoryAdapter(
    private val onEdit: (CategoryManagementViewModel.CategoryRow) -> Unit,
    private val onDelete: (CategoryManagementViewModel.CategoryRow) -> Unit
) : ListAdapter<CategoryManagementViewModel.CategoryRow, CategoryAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: CategoryManagementViewModel.CategoryRow) {
            val context = binding.root.context
            val category = row.category
            val fallback = ContextCompat.getColor(context, R.color.mode_unknown)
            val color = TransactionStyling.parseCategoryColor(category.colorHex, fallback)

            binding.tvCategoryName.text = category.name

            binding.tvAvatar.text = category.emoji ?: CategoryPalette.initials(category.name)
            binding.tvAvatar.textSize = if (category.emoji != null) 18f else 14f
            binding.tvAvatar.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }

            // An unused category reads differently from one with history -- it is the
            // difference between "safe to delete" and "deleting this moves transactions".
            binding.tvUsage.text = if (row.txnCount == 0) {
                context.getString(R.string.category_unused)
            } else {
                context.resources.getQuantityString(
                    R.plurals.category_usage_count,
                    row.txnCount,
                    row.txnCount,
                    CurrencyUtils.format(row.total)
                )
            }

            if (category.isIncome) {
                binding.chipIncome.visibility = View.VISIBLE
                TransactionStyling.applyTag(
                    binding.chipIncome,
                    context.getString(R.string.income_badge),
                    ContextCompat.getColor(context, R.color.credit_green)
                )
            } else {
                binding.chipIncome.visibility = View.GONE
            }

            binding.btnEdit.setOnClickListener { onEdit(row) }
            binding.btnDelete.setOnClickListener { onDelete(row) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CategoryManagementViewModel.CategoryRow>() {
        override fun areItemsTheSame(
            oldItem: CategoryManagementViewModel.CategoryRow,
            newItem: CategoryManagementViewModel.CategoryRow
        ) = oldItem.category.id == newItem.category.id

        override fun areContentsTheSame(
            oldItem: CategoryManagementViewModel.CategoryRow,
            newItem: CategoryManagementViewModel.CategoryRow
        ) = oldItem == newItem
    }
}
