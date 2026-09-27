package com.timepass.financialapp.ui.theme

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.R
import com.timepass.financialapp.data.prefs.AppPreferences
import com.timepass.financialapp.databinding.DialogAccentPickerBinding
import com.timepass.financialapp.databinding.ItemAccentSwatchBinding

/**
 * Appearance picker: light/dark mode, then accent colour.
 *
 * Mode stays a plain single-choice list -- three named options need nothing more. The
 * accent is a grid of swatches instead, because a list of a dozen colour names says
 * nothing about the colours it is naming.
 */
object ThemePickerDialog {

    /** @param onChanged invoked when either setting changed and the UI must be rebuilt. */
    fun show(context: Context, prefs: AppPreferences, onChanged: () -> Unit) {
        val modes = ThemeMode.entries
        val currentMode = ThemeMode.fromKey(prefs.themeModeKey)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.theme_mode)
            .setSingleChoiceItems(
                modes.map { context.getString(it.labelRes) }.toTypedArray(),
                modes.indexOf(currentMode)
            ) { dialog, which ->
                dialog.dismiss()
                val picked = modes[which]
                if (picked != currentMode) {
                    prefs.themeModeKey = picked.key
                }
                showAccentPicker(context, prefs, changedAlready = picked != currentMode, onChanged)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAccentPicker(
        context: Context,
        prefs: AppPreferences,
        changedAlready: Boolean,
        onChanged: () -> Unit
    ) {
        val binding = DialogAccentPickerBinding.inflate(LayoutInflater.from(context))
        val currentAccent = AccentColor.fromKey(prefs.accentKey)

        // Held so the dialog can be dismissed from inside the adapter's click handler.
        var dialog: androidx.appcompat.app.AlertDialog? = null

        binding.recyclerAccents.adapter = AccentAdapter(currentAccent) { picked ->
            dialog?.dismiss()
            if (picked != currentAccent) {
                prefs.accentKey = picked.key
                onChanged()
            } else if (changedAlready) {
                onChanged()
            }
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.theme_accent)
            .setView(binding.root)
            // Backing out of the accent step still has to apply a mode change made in the
            // step before it, which is what changedAlready carries.
            .setNegativeButton(R.string.cancel) { _, _ -> if (changedAlready) onChanged() }
            .setOnCancelListener { if (changedAlready) onChanged() }
            .show()
    }

    private class AccentAdapter(
        private val selected: AccentColor,
        private val onPicked: (AccentColor) -> Unit
    ) : RecyclerView.Adapter<AccentAdapter.ViewHolder>() {

        private val accents = AccentColor.entries

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            ItemAccentSwatchBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = accents.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(accents[position])
        }

        inner class ViewHolder(
            private val binding: ItemAccentSwatchBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(accent: AccentColor) {
                val context = binding.root.context
                val colour = ContextCompat.getColor(context, accent.swatchRes)

                binding.viewSwatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colour)
                    // The ring marks the current choice even where the tick is hard to see
                    // against a pale swatch.
                    if (accent == selected) {
                        setStroke(3.dp(context), Color.WHITE)
                    }
                }

                binding.tvSwatchName.setText(accent.labelRes)
                binding.ivSelected.visibility =
                    if (accent == selected) View.VISIBLE else View.GONE

                binding.root.setOnClickListener { onPicked(accent) }
            }

            private fun Int.dp(context: Context): Int =
                (this * context.resources.displayMetrics.density).toInt()
        }
    }
}
