package com.timepass.financialapp.ui.ignored

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.FragmentIgnoredBinding
import com.timepass.financialapp.ui.common.FilterBarController
import com.timepass.financialapp.ui.common.MessageDialog
import com.timepass.financialapp.ui.common.MultiSelectDialog
import com.timepass.financialapp.ui.common.SelectionController
import com.timepass.financialapp.ui.common.TransactionStyling
import com.timepass.financialapp.ui.common.TypeFilter
import com.timepass.financialapp.ui.uncategorized.UncategorizedAdapter
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Transactions set aside as not worth tracking.
 *
 * Reuses the Pending row adapter: an ignored row has no category either, so it renders
 * identically. Tapping a row offers to put it back rather than opening the category picker.
 */
class IgnoredFragment : Fragment() {

    private var _binding: FragmentIgnoredBinding? = null
    private val binding get() = _binding!!

    private val viewModel: IgnoredViewModel by viewModels {
        val app = requireActivity().application as FinancialApp
        IgnoredViewModel.Factory(app.database.transactionDao())
    }

    private lateinit var adapter: UncategorizedAdapter
    private lateinit var filterBar: FilterBarController
    private lateinit var selection: SelectionController

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentIgnoredBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = UncategorizedAdapter(
            onItemClick = { transaction ->
                if (selection.isActive) selection.toggle(transaction.id)
                else confirmRestore(transaction.id)
            },
            onMessageClick = { transaction -> MessageDialog.show(requireContext(), transaction) },
            onLongClick = { transaction -> selection.toggle(transaction.id) }
        )
        binding.recyclerView.adapter = adapter

        selection = SelectionController(
            binding = binding.selectionBar,
            action1Label = R.string.action_move_pending,
            action2Label = null,
            onAction1 = { ids -> bulkRestore(ids) },
            onAction2 = null,
            onChanged = { ids -> adapter.setSelection(ids) }
        ).also { it.attach() }

        filterBar = FilterBarController(
            binding = binding.filterBar,
            showCategoryFilter = false,
            onMonthPicked = viewModel::setMonth,
            onPrevMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onTypeClicked = { showTypeDialog() },
            onModeClicked = { showModeDialog() },
            onSenderClicked = { showSenderDialog() },
            onAccountClicked = { showAccountDialog() },
            onCategoryClicked = {},
            onSearchChanged = viewModel::setSearch,
            onClearFilters = viewModel::clearFilters
        ).also { it.attach() }

        // Collected on its own rather than inside the combine above: combine is only typed
        // up to five flows, and this one exists purely so its WhileSubscribed StateFlow has
        // a subscriber -- without one the Account dialog opens empty.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.accountRefs.collect { }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.filter,
                    viewModel.transactions,
                    viewModel.paymentModes,
                    viewModel.senders
                ) { filter, transactions, _, _ ->
                    filter to transactions
                }.collect { (filter, transactions) ->
                    filterBar.render(filter)
                    adapter.submitList(transactions)
                    selection.retainOnly(transactions.map { it.id }.toSet())

                    filterBar.setResultText(
                        resources.getQuantityString(
                            R.plurals.result_count_plural,
                            transactions.size,
                            transactions.size
                        )
                    )

                    val empty = transactions.isEmpty()
                    binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
                    binding.recyclerView.visibility = if (empty) View.GONE else View.VISIBLE
                    binding.tvEmptyHint.visibility =
                        if (empty && filter.activeCount > 0) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun bulkRestore(ids: Set<Long>) {
        viewModel.restoreAll(ids) {
            selection.clear()
            Snackbar.make(
                binding.root,
                getString(R.string.bulk_pending, ids.size),
                Snackbar.LENGTH_LONG
            ).setAction(R.string.undo) { viewModel.undoRestoreAll(ids) }.show()
        }
    }

    private fun confirmRestore(transactionId: Long) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.move_to_pending)
            .setPositiveButton(R.string.move_to_pending) { _, _ ->
                viewModel.restore(transactionId) {
                    Snackbar.make(binding.root, R.string.restored_to_pending, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) { viewModel.undoRestore(transactionId) }
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTypeDialog() {
        MultiSelectDialog.show(
            requireContext(),
            R.string.filter_direction,
            listOf(
                TypeFilter.DEBIT.dbValue to getString(R.string.filter_debit),
                TypeFilter.CREDIT.dbValue to getString(R.string.filter_credit)
            ),
            viewModel.filter.value.types,
            viewModel::setTypes
        )
    }

    private fun showModeDialog() {
        MultiSelectDialog.show(
            requireContext(),
            R.string.filter_mode,
            viewModel.paymentModes.value.map { it to TransactionStyling.modeLabel(it) },
            viewModel.filter.value.paymentModes,
            viewModel::setPaymentModes
        )
    }

    /**
     * Accounts and cards, shown by their masked tail.
     *
     * The stored value is only the last four digits, which is all the SMS discloses, so
     * there is nothing fuller to reveal here even if it were wanted.
     */
    private fun showAccountDialog() {
        val refs = viewModel.accountRefs.value
        MultiSelectDialog.show(
            requireContext(),
            R.string.filter_account,
            refs.map { it to viewModel.accountLabel(it) },
            viewModel.filter.value.accountRefs,
            viewModel::setAccountRefs
        )
    }

    private fun showSenderDialog() {
        MultiSelectDialog.show(
            requireContext(),
            R.string.filter_sender,
            viewModel.senders.value.map { it to it },
            viewModel.filter.value.senders,
            viewModel::setSenders
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }
}
