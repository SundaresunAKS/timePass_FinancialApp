package com.timepass.financialapp.ui.common

import android.app.DatePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.R
import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.databinding.DialogAddTransactionBinding
import com.timepass.financialapp.util.DateUtils
import java.util.Calendar

/**
 * Manual entry for a transaction the app never saw as an SMS -- cash, or a bank that sends
 * no alerts.
 *
 * It writes straight into Tagged rather than Pending, so the category is asked for here:
 * a row the user typed by hand already has all the classification it is going to get, and
 * routing it through the inbox would only ask them to sort something they just described.
 */
object AddTransactionDialog {

    /** Everything needed to store the row; the caller owns the database write. */
    data class Entry(
        val amount: Double,
        val transactionType: String,
        val description: String,
        val categoryId: Long,
        val note: String?,
        val dateMillis: Long
    )

    fun show(
        context: Context,
        categories: List<CategoryEntity>,
        onSave: (Entry) -> Unit
    ) {
        if (categories.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.add_transaction)
                .setMessage(R.string.add_no_categories)
                .setPositiveButton(R.string.ok, null)
                .show()
            return
        }

        val binding = DialogAddTransactionBinding.inflate(LayoutInflater.from(context))
        val calendar = Calendar.getInstance()

        binding.toggleDirection.check(R.id.btnMoneyOut)
        binding.etDate.setText(DateUtils.formatDate(calendar.timeInMillis))
        binding.etDate.setOnClickListener {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    calendar.set(year, month, day)
                    binding.etDate.setText(DateUtils.formatDate(calendar.timeInMillis))
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).apply {
                // A transaction cannot have happened later than now.
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }

        // Typing in the field above leaves the keyboard up, and the dropdown opens
        // behind it -- so the list looked empty until the keyboard was dismissed by hand.
        binding.dropdownCategory.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
                ime.hideSoftInputFromWindow(view.windowToken, 0)
            }
        }

        // The dropdown shows names; the position maps back to the id on save.
        binding.dropdownCategory.setAdapter(
            ArrayAdapter(context, android.R.layout.simple_list_item_1, categories.map { it.name })
        )

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.add_transaction)
            .setView(binding.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.show()

        // Set after show() so a failed validation can keep the dialog open, which the
        // builder's own listener cannot do.
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val amount = binding.etAmount.text?.toString()?.trim()?.toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                binding.etAmount.error = context.getString(R.string.add_amount_required)
                return@setOnClickListener
            }

            val description = binding.etDescription.text?.toString()?.trim().orEmpty()
            if (description.isEmpty()) {
                binding.etDescription.error = context.getString(R.string.add_description_required)
                return@setOnClickListener
            }

            val categoryName = binding.dropdownCategory.text?.toString()
            val category = categories.firstOrNull { it.name == categoryName }
            if (category == null) {
                binding.dropdownCategory.error = context.getString(R.string.add_category_required)
                return@setOnClickListener
            }

            onSave(
                Entry(
                    amount = amount,
                    transactionType =
                        if (binding.toggleDirection.checkedButtonId == R.id.btnMoneyIn) "CREDIT"
                        else "DEBIT",
                    description = description,
                    categoryId = category.id,
                    note = binding.etNote.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                    dateMillis = calendar.timeInMillis
                )
            )
            dialog.dismiss()
        }
    }
}
