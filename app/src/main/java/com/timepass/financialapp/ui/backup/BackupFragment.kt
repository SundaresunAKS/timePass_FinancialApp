package com.timepass.financialapp.ui.backup

import android.app.Activity
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.timepass.financialapp.R
import com.timepass.financialapp.backup.BackupError
import com.timepass.financialapp.backup.BackupFrequency
import com.timepass.financialapp.backup.BackupRepository
import com.timepass.financialapp.backup.DriveAuth
import com.timepass.financialapp.databinding.FragmentBackupBinding
import com.timepass.financialapp.mail.MailAuth
import com.timepass.financialapp.ui.common.TextInputDialog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupFragment : Fragment() {

    private var _binding: FragmentBackupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BackupViewModel by viewModels {
        BackupViewModel.Factory(requireActivity().application)
    }

    private val auth by lazy { DriveAuth(requireContext()) }
    private val mailAuth by lazy { MailAuth(requireContext()) }

    /** Receives the result of the Gmail consent screen. */
    private val mailConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val state = if (result.resultCode == Activity.RESULT_OK) {
            runCatching { mailAuth.resultFromIntent(result.data) }.getOrNull()
        } else {
            null
        }
        if (state is MailAuth.AuthState.Authorized) {
            viewModel.onMailAuthorized(state.accessToken, state.email)
        } else {
            snack(getString(R.string.mail_err_permission))
        }
    }

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    /** Receives the result of the Google consent screen. */
    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            snack(getString(R.string.backup_err_permission))
            return@registerForActivityResult
        }
        runCatching { auth.resultFromIntent(result.data) }
            .onSuccess { state ->
                if (state is DriveAuth.AuthState.Authorized) {
                    viewModel.onAuthorized(state.email)
                    requestMailAccess()
                } else {
                    snack(getString(R.string.backup_err_permission))
                }
            }
            .onFailure { snack(getString(R.string.backup_err_permission)) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBackupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnConnect.setOnClickListener { connect() }
        binding.btnDisconnect.setOnClickListener { confirmDisconnect() }
        binding.btnBackupNow.setOnClickListener { viewModel.backupNow() }
        binding.btnRestore.setOnClickListener { viewModel.checkForRestore() }
        binding.btnFrequency.setOnClickListener { pickFrequency() }
        binding.switchWifi.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) viewModel.setWifiOnly(checked)
        }
        binding.switchCharging.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) viewModel.setChargingOnly(checked)
        }
        binding.switchMail.setOnCheckedChangeListener { button, checked ->
            if (!button.isPressed) return@setOnCheckedChangeListener
            if (checked) {
                // The switch only stays on once access is actually granted; render()
                // puts it back until then.
                button.isChecked = false
                requestMailAccess()
            } else {
                viewModel.disableMail()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { render(it) } }
                launch { viewModel.events.collect { handle(it) } }
            }
        }
    }

    private fun render(state: BackupViewModel.UiState) {
        // Name and address together when both are known: the address alone is what
        // identifies the account, but the name is what the user recognises.
        binding.tvAccount.text = when {
            !state.accountName.isNullOrBlank() && !state.accountEmail.isNullOrBlank() ->
                getString(R.string.backup_account_line, state.accountName, state.accountEmail)
            !state.accountEmail.isNullOrBlank() -> state.accountEmail
            else -> getString(
                if (state.connected) R.string.backup_connected else R.string.backup_not_connected
            )
        }
        binding.btnDisconnect.visibility = if (state.connected) View.VISIBLE else View.GONE
        // Once connected, the same button re-runs authorization, which is how the user
        // switches to a different Google account.
        binding.btnConnect.setText(
            if (state.connected) R.string.backup_switch_account else R.string.backup_connect
        )

        binding.tvFolder.text = state.folderName
            ?.let { getString(R.string.backup_folder, it) }
            ?: getString(R.string.backup_folder_none)

        binding.tvLastBackup.text = if (state.lastBackupAt > 0) {
            getString(
                R.string.backup_last,
                dateFormat.format(Date(state.lastBackupAt)),
                Formatter.formatShortFileSize(requireContext(), state.lastBackupSize)
            )
        } else {
            getString(R.string.backup_never)
        }

        binding.btnFrequency.text =
            getString(R.string.backup_frequency, getString(labelFor(state.frequency)))
        binding.switchWifi.isChecked = state.wifiOnly
        binding.switchCharging.isChecked = state.chargingOnly

        binding.progress.visibility = if (state.busy) View.VISIBLE else View.GONE
        renderMail(state)

        binding.btnBackupNow.isEnabled = state.connected && !state.busy
        binding.btnRestore.isEnabled = state.connected && !state.busy
    }

    private fun renderMail(state: BackupViewModel.UiState) {
        val visibility = if (state.googleLinked) View.VISIBLE else View.GONE
        binding.switchMail.visibility = visibility
        binding.tvMailStatus.visibility = visibility
        binding.switchMail.isChecked = state.mailEnabled
        binding.switchMail.isEnabled = !state.mailSyncing
        binding.tvMailStatus.text = when {
            state.mailSyncing -> getString(R.string.backup_mail_syncing)
            !state.mailEnabled -> getString(R.string.backup_mail_off)
            state.mailLastSyncAt > 0 ->
                getString(R.string.backup_mail_on, dateFormat.format(Date(state.mailLastSyncAt)))
            else -> getString(R.string.backup_mail_on_never)
        }
    }

    /**
     * Asks for read-only Gmail access, straight after the Drive connection. Asked for
     * separately: reading mail is a bigger ask than writing one backup file, and saying
     * no here leaves backups working and the app on SMS only.
     */
    private fun requestMailAccess() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (val state = mailAuth.authorize()) {
                    is MailAuth.AuthState.Authorized ->
                        viewModel.onMailAuthorized(state.accessToken, state.email)
                    is MailAuth.AuthState.NeedsConsent -> mailConsentLauncher.launch(
                        IntentSenderRequest.Builder(state.pendingIntent).build()
                    )
                }
            } catch (e: Exception) {
                snack(getString(R.string.mail_err_permission))
            }
        }
    }

    private fun handle(event: BackupViewModel.Event) = when (event) {
        is BackupViewModel.Event.Error -> showError(event.error)
        is BackupViewModel.Event.Message -> snack(getString(event.res))
        is BackupViewModel.Event.NeedsFolder -> promptForFolder()
        is BackupViewModel.Event.RestoreAvailable -> confirmRestore(event)
        // The restored theme lives in the activity's theme, applied at onCreate, so
        // it only appears once the activity is rebuilt.
        BackupViewModel.Event.SettingsRestored -> requireActivity().recreate()
        is BackupViewModel.Event.MailImported ->
            snack(resources.getQuantityString(R.plurals.mail_sync_done, event.count, event.count))
        BackupViewModel.Event.MailFailed -> snack(getString(R.string.mail_err_sync))
    }

    /** Errors carry their own message; the detail is appended only when it adds something. */
    private fun showError(error: BackupError) {
        val base = getString(error.messageRes)
        val text = error.detail?.takeIf { it.isNotBlank() }?.let { "$base ($it)" } ?: base
        snack(text)
    }

    private fun connect() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (val state = auth.authorize()) {
                    is DriveAuth.AuthState.Authorized -> {
                        viewModel.onAuthorized(state.email)
                        requestMailAccess()
                    }
                    is DriveAuth.AuthState.NeedsConsent ->
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(state.pendingIntent).build()
                        )
                }
            } catch (e: BackupError) {
                showError(e)
            } catch (e: Exception) {
                showError(BackupError.Unknown(e.message))
            }
        }
    }

    private fun promptForFolder() {
        TextInputDialog.show(
            context = requireContext(),
            hintRes = R.string.backup_folder_prompt,
            initial = BackupRepository.DEFAULT_FOLDER,
            positiveRes = R.string.save,
            onConfirm = viewModel::createFolder
        )
    }

    private fun confirmRestore(event: BackupViewModel.Event.RestoreAvailable) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_confirm_title)
            .setMessage(
                getString(
                    R.string.restore_confirm_body,
                    dateFormat.format(Date(event.exportedAt)),
                    event.count,
                    Formatter.formatShortFileSize(requireContext(), event.sizeBytes)
                )
            )
            .setPositiveButton(R.string.restore_action) { _, _ -> viewModel.restore(event.fileId) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickFrequency() {
        val options = BackupFrequency.entries
        val labels = options.map { getString(labelFor(it)) }.toTypedArray()
        val current = options.indexOf(viewModel.state.value.frequency)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_auto)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.setFrequency(options[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_disconnect)
            .setPositiveButton(R.string.backup_disconnect) { _, _ -> viewModel.disconnect() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun labelFor(frequency: BackupFrequency) = when (frequency) {
        BackupFrequency.OFF -> R.string.backup_freq_off
        BackupFrequency.DAILY -> R.string.backup_freq_daily
        BackupFrequency.WEEKLY -> R.string.backup_freq_weekly
        BackupFrequency.MONTHLY -> R.string.backup_freq_monthly
    }

    private fun snack(text: String) =
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
