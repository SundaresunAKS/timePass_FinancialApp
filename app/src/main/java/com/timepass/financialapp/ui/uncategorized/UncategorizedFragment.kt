package com.timepass.financialapp.ui.uncategorized

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuHost
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.FragmentUncategorizedBinding
import com.timepass.financialapp.ui.common.CategoryPickerDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.ui.common.FilterBarController
import com.google.android.material.snackbar.Snackbar
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.ui.common.MessageDialog
import com.timepass.financialapp.ui.common.MultiSelectDialog
import com.timepass.financialapp.ui.common.SelectionController
import com.timepass.financialapp.ui.common.TypeFilter
import com.timepass.financialapp.ui.common.TransactionStyling
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class UncategorizedFragment : Fragment() {

    private var _binding: FragmentUncategorizedBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UncategorizedViewModel by viewModels {
        val app = requireActivity().application as FinancialApp
        UncategorizedViewModel.Factory(app.database.transactionDao(), app.database.categoryDao())
    }

    private lateinit var adapter: UncategorizedAdapter
    private lateinit var filterBar: FilterBarController
    private lateinit var selection: SelectionController

    /** Set while a bulk tag is in flight, so the picker result knows to apply to many. */
    private var pendingBulkIds: Set<Long>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUncategorizedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        addRefreshAction()

        adapter = UncategorizedAdapter(
            onItemClick = { transaction ->
                if (selection.isActive) {
                    selection.toggle(transaction.id)
                } else {
                    CategoryPickerDialog.show(
                        childFragmentManager,
                        transaction.id,
                        isIncome = transaction.transactionType == "CREDIT"
                    )
                }
            },
            onMessageClick = { transaction -> MessageDialog.show(requireContext(), transaction) },
            onLongClick = { transaction -> selection.toggle(transaction.id) }
        )
        binding.recyclerView.adapter = adapter

        selection = SelectionController(
            binding = binding.selectionBar,
            action1Label = R.string.action_tag,
            action2Label = R.string.action_ignore,
            onAction1 = { ids ->
                pendingBulkIds = ids
                CategoryPickerDialog.show(childFragmentManager, -1L, isIncome = false)
            },
            onAction2 = { ids -> bulkIgnore(ids) },
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

        // The picker is shown on childFragmentManager, so it posts its result there.
        // Fragment.setFragmentResultListener() would listen on parentFragmentManager
        // instead, and the selection would be silently dropped.
        childFragmentManager.setFragmentResultListener(
            CategoryPickerDialog.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val transactionId = bundle.getLong(CategoryPickerDialog.ARG_TRANSACTION_ID)
            val categoryId = bundle.getLong(CategoryPickerDialog.ARG_CATEGORY_ID)
            val note = bundle.getString(CategoryPickerDialog.ARG_NOTE)

            val bulk = pendingBulkIds
            if (bulk != null) {
                pendingBulkIds = null
                viewModel.categorizeAll(bulk, categoryId, note) {
                    selection.clear()
                    snack(getString(R.string.bulk_tagged, bulk.size))
                }
            } else {
                viewModel.categorize(transactionId, categoryId, note)
            }
        }

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
                // paymentModes is included so its WhileSubscribed StateFlow has a
                // collector; without one it never starts and the Mode dialog is empty.
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






    private fun bulkIgnore(ids: Set<Long>) {
        viewModel.ignoreAll(ids) {
            selection.clear()
            Snackbar.make(binding.root, getString(R.string.bulk_ignored, ids.size), Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { viewModel.undoIgnoreAll(ids) }
                .show()
        }
    }

    private fun snack(text: String) =
        Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG).show()

    /** Long-press menu. Ignore is destructive-ish, so it explains itself and offers undo. */
    private fun showRowActions(transaction: TransactionEntity) {
        val actions = arrayOf(
            getString(R.string.ignore_transaction),
            getString(R.string.action_view_message)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.row_actions)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> viewModel.ignore(transaction.id) {
                        Snackbar.make(binding.root, R.string.ignored_done, Snackbar.LENGTH_LONG)
                            .setAction(R.string.undo) { viewModel.undoIgnore(transaction.id) }
                            .show()
                    }
                    1 -> MessageDialog.show(requireContext(), transaction)
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

    /**
     * Adds Refresh to the toolbar for this screen only.
     *
     * A MenuProvider scoped to the view lifecycle rather than a permanent item on the
     * activity menu: refreshing means reading the SMS inbox, which only makes sense where
     * the imported messages are shown.
     */
    private fun addRefreshAction() {
        (requireActivity() as MenuHost).addMenuProvider(
            object : MenuProvider {
                override fun onCreateMenu(menu: android.view.Menu, inflater: android.view.MenuInflater) {
                    inflater.inflate(R.menu.pending_menu, menu)
                }

                override fun onMenuItemSelected(item: android.view.MenuItem): Boolean {
                    if (item.itemId != R.id.action_refresh) return false
                    runRefresh()
                    return true
                }
            },
            viewLifecycleOwner,
            Lifecycle.State.RESUMED
        )
    }

    private fun runRefresh() {
        val binding = _binding ?: return
        Snackbar.make(binding.root, R.string.pending_refreshing, Snackbar.LENGTH_SHORT).show()

        val app = requireActivity().application as FinancialApp
        val reader = com.timepass.financialapp.sms.SmsReader(requireContext(), app.database)
        val mailReader = com.timepass.financialapp.mail.MailReader(requireContext(), app.database)

        viewModel.refresh(reader, mailReader) { result ->
            val current = _binding ?: return@refresh
            val message = when (result) {
                is RefreshResult.Added ->
                    resources.getQuantityString(
                        R.plurals.pending_refresh_found, result.count, result.count
                    )
                RefreshResult.NothingNew -> getString(R.string.pending_refresh_none)
                is RefreshResult.Failed ->
                    getString(R.string.pending_refresh_failed, result.message.orEmpty())
                is RefreshResult.MailFailed ->
                    getString(
                        if (result.needsReconnect) R.string.mail_err_reconnect else R.string.mail_err_sync
                    )
            }
            Snackbar.make(current.root, message, Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }
}
