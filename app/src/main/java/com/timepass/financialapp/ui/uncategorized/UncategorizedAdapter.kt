package com.timepass.financialapp.ui.uncategorized

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.timepass.financialapp.R
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.databinding.ItemTransactionBinding
import com.timepass.financialapp.ui.common.TransactionStyling
import com.timepass.financialapp.util.CurrencyUtils
import com.timepass.financialapp.util.DateUtils

class UncategorizedAdapter(
    private val onItemClick: (TransactionEntity) -> Unit,
    private val onMessageClick: (TransactionEntity) -> Unit,
    private val onLongClick: ((TransactionEntity) -> Unit)? = null
) : ListAdapter<TransactionEntity, UncategorizedAdapter.ViewHolder>(DiffCallback()) {

    private var selectedIds: Set<Long> = emptySet()

    /**
     * Selection is held here rather than in each ViewHolder so that entering and leaving
     * the mode repaints every visible row, including ones scrolled off and back.
     */
    fun setSelection(ids: Set<Long>) {
        if (ids == selectedIds) return
        selectedIds = ids
        notifyItemRangeChanged(0, itemCount)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTransactionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(transaction: TransactionEntity) {
            val context = binding.root.context
            val isDebit = transaction.transactionType == "DEBIT"
            val directionColor = TransactionStyling.directionColor(context, transaction.transactionType)

            binding.tvAmount.text =
                (if (isDebit) "- " else "+ ") + CurrencyUtils.format(transaction.amount)
            binding.tvAmount.setTextColor(directionColor)

            // Nothing is categorized on this screen, so the stripe shows direction instead.
            binding.viewAccent.backgroundTintList = ColorStateList.valueOf(directionColor)

            binding.tvDate.text = DateUtils.formatDate(transaction.transactionDate)

            TransactionStyling.applyTag(
                binding.chipPaymentMode,
                TransactionStyling.modeLabel(transaction.paymentMode),
                TransactionStyling.modeColor(context, transaction.paymentMode)
            )
            binding.chipCategory.visibility = View.GONE

            binding.tvSender.text = transaction.sender.orEmpty()
            binding.tvSender.visibility =
                if (transaction.sender.isNullOrBlank()) View.GONE else View.VISIBLE

            val note = transaction.note
            if (note.isNullOrBlank()) {
                binding.tvNote.visibility = View.GONE
            } else {
                binding.tvNote.visibility = View.VISIBLE
                binding.tvNote.text = context.getString(R.string.note_prefix, note)
                binding.tvNote.setTextColor(
                    ContextCompat.getColor(context, R.color.on_surface_variant)
                )
            }

            val selected = transaction.id in selectedIds
            (binding.root as com.google.android.material.card.MaterialCardView).apply {
                isChecked = selected
                strokeWidth = if (selected) dpToPx(context, 2) else dpToPx(context, 1)
                setStrokeColor(
                    if (selected) TransactionStyling.directionColor(context, transaction.transactionType)
                    else ContextCompat.getColor(context, R.color.outline_variant)
                )
            }

            binding.tvSmsBody.text = transaction.description
            // The preview is clipped to two lines; tapping it opens the whole message.
            binding.tvSmsBody.setOnClickListener { onMessageClick(transaction) }
            binding.root.setOnClickListener { onItemClick(transaction) }
            binding.root.setOnLongClickListener {
                onLongClick?.invoke(transaction)
                onLongClick != null
            }
        }
    }

    private fun dpToPx(context: android.content.Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()

    class DiffCallback : DiffUtil.ItemCallback<TransactionEntity>() {
        override fun areItemsTheSame(oldItem: TransactionEntity, newItem: TransactionEntity) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: TransactionEntity, newItem: TransactionEntity) =
            oldItem == newItem
    }
}
