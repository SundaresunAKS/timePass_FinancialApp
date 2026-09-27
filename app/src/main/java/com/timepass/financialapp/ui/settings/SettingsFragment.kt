package com.timepass.financialapp.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.timepass.financialapp.R
import com.timepass.financialapp.backup.BackupPrefs
import com.timepass.financialapp.data.prefs.AppPreferences
import com.timepass.financialapp.databinding.FragmentSettingsBinding
import com.timepass.financialapp.databinding.ViewSettingsRowBinding
import com.timepass.financialapp.databinding.ViewSettingsSwitchRowBinding
import com.timepass.financialapp.security.AppLock
import com.timepass.financialapp.ui.theme.AccentColor
import com.timepass.financialapp.ui.theme.ThemeMode
import com.timepass.financialapp.ui.theme.ThemePickerDialog

/**
 * One drawer entry that gathers the screens you visit occasionally: categories, export,
 * backup and appearance.
 *
 * Subtitles are refreshed in [onResume] rather than bound once, because backup and theme
 * are both changed on the screens this one leads to -- coming back should show the result.
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val appPrefs by lazy { AppPreferences(requireContext()) }
    private val backupPrefs by lazy { BackupPrefs(requireContext()) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindRow(
            binding.rowCategories,
            R.drawable.ic_label,
            R.string.nav_categories,
            getString(R.string.settings_categories_summary)
        ) { findNavController().navigate(R.id.action_settings_to_categories) }

        bindRow(
            binding.rowExport,
            R.drawable.ic_download,
            R.string.nav_export,
            getString(R.string.settings_export_summary)
        ) { findNavController().navigate(R.id.action_settings_to_export) }

        bindRow(
            binding.rowBackup,
            R.drawable.ic_cloud_upload,
            R.string.nav_backup,
            backupSummary()
        ) { findNavController().navigate(R.id.action_settings_to_backup) }

        bindRow(
            binding.rowTheme,
            R.drawable.ic_palette,
            R.string.nav_theme,
            themeSummary()
        ) {
            // recreate() rebuilds the activity, which is what applies a new accent or
            // night mode; this fragment is rebuilt with it and re-reads both summaries.
            ThemePickerDialog.show(requireContext(), appPrefs) { requireActivity().recreate() }
        }

        bindRow(
            binding.rowAbout,
            R.drawable.ic_settings,
            R.string.nav_about,
            getString(R.string.settings_about_summary)
        ) { findNavController().navigate(R.id.action_settings_to_about) }

        bindBiometricRow()
    }

    /**
     * The app lock toggle.
     *
     * Turning it on asks for the fingerprint first: agreeing to a lock you cannot then
     * satisfy would leave the app unopenable, so the switch only moves after a successful
     * prompt. Turning it off is deliberately not gated -- the user is already inside.
     */
    private fun bindBiometricRow() {
        val row = binding.rowBiometric
        row.ivRowIcon.setImageResource(R.drawable.ic_fingerprint)
        row.tvRowTitle.setText(R.string.settings_biometric)

        val unavailable = AppLock.unavailableReason(requireContext())
        val enabled = appPrefs.biometricEnabled

        row.switchRow.isChecked = enabled
        row.switchRow.isEnabled = unavailable == null
        row.root.isEnabled = unavailable == null
        row.tvRowSubtitle.text = when {
            unavailable != null -> getString(unavailable)
            enabled -> getString(R.string.settings_biometric_on)
            else -> getString(R.string.settings_biometric_off)
        }

        if (unavailable != null) return

        row.root.setOnClickListener {
            if (appPrefs.biometricEnabled) {
                appPrefs.biometricEnabled = false
                bindBiometricRow()
                return@setOnClickListener
            }
            AppLock.prompt(
                activity = requireActivity(),
                titleRes = R.string.biometric_enable_title,
                subtitleRes = R.string.biometric_enable_subtitle,
                onSuccess = {
                    appPrefs.biometricEnabled = true
                    if (_binding != null) bindBiometricRow()
                    _binding?.let {
                        Snackbar.make(it.root, R.string.settings_biometric_enabled, Snackbar.LENGTH_SHORT).show()
                    }
                },
                onFailed = { message ->
                    _binding?.let {
                        // Re-read rather than trusting the switch: it was never moved.
                        bindBiometricRow()
                        if (message != null) {
                            Snackbar.make(it.root, message, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        binding.rowBackup.tvRowSubtitle.text = backupSummary()
        binding.rowTheme.tvRowSubtitle.text = themeSummary()
        // A restore can switch the lock on from another screen, and the system settings
        // can remove the enrolment that made it possible.
        bindBiometricRow()
    }

    private fun bindRow(
        row: ViewSettingsRowBinding,
        iconRes: Int,
        titleRes: Int,
        subtitle: String,
        onClick: () -> Unit
    ) {
        row.ivRowIcon.setImageResource(iconRes)
        row.tvRowTitle.setText(titleRes)
        row.tvRowSubtitle.text = subtitle
        row.root.setOnClickListener { onClick() }
    }

    private fun backupSummary(): String =
        if (backupPrefs.isConnected) {
            getString(
                R.string.settings_backup_connected,
                backupPrefs.folderName ?: getString(R.string.nav_backup)
            )
        } else {
            getString(R.string.settings_backup_disconnected)
        }

    private fun themeSummary(): String = getString(
        R.string.settings_theme_summary,
        getString(ThemeMode.fromKey(appPrefs.themeModeKey).labelRes),
        getString(AccentColor.fromKey(appPrefs.accentKey).labelRes)
    )

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
