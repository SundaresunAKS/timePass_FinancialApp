package com.timepass.financialapp.ui.common

import android.content.Context
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.DialogTextInputBinding

/**
 * Single-field prompt used wherever a dialog needs a name typed into it.
 *
 * Exists because passing a bare EditText to `setView()` leaves it flush against the dialog
 * edges and outside the app's input styling; every caller would otherwise repeat the same
 * padding fix, in raw pixels, and get it slightly different each time.
 */
object TextInputDialog {

    /**
     * @param hintRes label shown above the field, and the dialog title
     * @param initial pre-filled text, selected so typing replaces it
     * @param onConfirm receives the trimmed text; never called with a blank value
     */
    fun show(
        context: Context,
        hintRes: Int,
        initial: String,
        positiveRes: Int,
        onConfirm: (String) -> Unit
    ) {
        val binding = DialogTextInputBinding.inflate(LayoutInflater.from(context))
        binding.tilInput.hint = context.getString(hintRes)
        binding.etInput.setText(initial)
        binding.etInput.setSelection(binding.etInput.text?.length ?: 0)

        MaterialAlertDialogBuilder(context)
            .setTitle(hintRes)
            .setView(binding.root)
            .setPositiveButton(positiveRes) { _, _ ->
                val value = binding.etInput.text?.toString()?.trim().orEmpty()
                onConfirm(value.ifEmpty { initial })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
