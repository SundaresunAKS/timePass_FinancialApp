package com.timepass.financialapp.ui.export

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.FragmentExportBinding
import com.timepass.financialapp.ui.common.TextInputDialog
import com.timepass.financialapp.util.DateUtils
import kotlinx.coroutines.launch
import java.io.File

class ExportFragment : Fragment() {

    private companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    private var hasExport = false
    private var driveConnected = false

    private var _binding: FragmentExportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ExportViewModel by viewModels {
        val app = requireActivity().application as FinancialApp
        ExportViewModel.Factory(
            app.database.transactionDao(),
            app.database.categoryDao(),
            requireActivity().application
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnExportExcel.setOnClickListener { viewModel.exportToExcel() }
        binding.btnShareExport.setOnClickListener {
            viewModel.lastExport.value?.let { shareFile(it.filePath) }
        }
        binding.btnExportToDrive.setOnClickListener { promptForDriveFolder() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { observeExportState() }
                launch { observeLastExport() }
                launch {
                    viewModel.driveConnected.collect { connected ->
                        driveConnected = connected
                        binding.tvDriveHint.visibility =
                            if (connected) View.GONE else View.VISIBLE
                        updateActionButtons()
                    }
                }
            }
        }
    }

    private suspend fun observeExportState() {
        viewModel.exportState.collect { state ->
            val running = state is ExportViewModel.ExportState.Exporting
            binding.progressBar.visibility = if (running) View.VISIBLE else View.GONE
            binding.btnExportExcel.isEnabled = !running

            when (state) {
                is ExportViewModel.ExportState.Success -> {
                    // Offer sharing straight from the confirmation, so the common path is
                    // two taps rather than hunting for the button below.
                    Snackbar.make(binding.root, R.string.export_saved, Snackbar.LENGTH_LONG)
                        .setAction(R.string.share_export) { shareFile(state.filePath) }
                        .show()
                    viewModel.consumeState()
                }

                is ExportViewModel.ExportState.UploadedToDrive -> {
                    binding.tvExportStatus.visibility = View.GONE
                    Snackbar.make(
                        binding.root,
                        getString(R.string.export_drive_done, state.folderName),
                        Snackbar.LENGTH_LONG
                    ).show()
                    viewModel.consumeState()
                }

                is ExportViewModel.ExportState.Error -> {
                    binding.tvExportStatus.visibility = View.VISIBLE
                    binding.tvExportStatus.text = getString(R.string.export_error, state.message)
                    viewModel.consumeState()
                }

                is ExportViewModel.ExportState.Exporting -> {
                    binding.tvExportStatus.visibility = View.GONE
                }

                is ExportViewModel.ExportState.Idle -> Unit
            }
        }
    }

    private suspend fun observeLastExport() {
        viewModel.lastExport.collect { last ->
            hasExport = last != null
            if (last == null) {
                binding.tvExportFileName.setText(R.string.no_export_yet)
                binding.tvExportMeta.visibility = View.GONE
            } else {
                binding.tvExportFileName.text = last.fileName
                binding.tvExportMeta.visibility = View.VISIBLE
                binding.tvExportMeta.text = getString(
                    R.string.export_meta,
                    Formatter.formatShortFileSize(requireContext(), last.sizeBytes),
                    DateUtils.formatDate(last.savedAt)
                )
            }
            updateActionButtons()
        }
    }

    /** Both actions need a file; only the Drive one also needs a Drive connection. */
    private fun updateActionButtons() {
        binding.btnShareExport.isEnabled = hasExport
        binding.btnExportToDrive.isEnabled = hasExport && driveConnected
    }

    private fun promptForDriveFolder() {
        TextInputDialog.show(
            context = requireContext(),
            hintRes = R.string.export_drive_folder_prompt,
            initial = viewModel.defaultExportFolder,
            positiveRes = R.string.export_to_drive,
            onConfirm = viewModel::exportToDrive
        )
    }

    /**
     * Hands the spreadsheet to another app as a content:// URI. A raw file:// path in an
     * Intent throws FileUriExposedException on API 24+, so the file goes out through the
     * FileProvider declared in the manifest, with read access granted only for this share.
     */
    private fun shareFile(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Snackbar.make(binding.root, R.string.no_export_yet, Snackbar.LENGTH_LONG).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = XLSX_MIME
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)))
        } catch (e: ActivityNotFoundException) {
            showShareError(e)
        } catch (e: IllegalArgumentException) {
            // FileProvider throws this when the path falls outside file_paths.xml.
            showShareError(e)
        }
    }

    private fun showShareError(e: Throwable) {
        Snackbar.make(
            binding.root,
            getString(R.string.share_failed, e.message ?: e::class.java.simpleName),
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun onResume() {
        super.onResume()
        // The connection may have been made on the Backup screen since this was last shown.
        viewModel.refreshDriveState()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
