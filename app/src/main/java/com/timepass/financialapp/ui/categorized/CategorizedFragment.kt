package com.timepass.financialapp.ui.categorized

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
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.R
import com.timepass.financialapp.databinding.FragmentCategorizedBinding
import com.timepass.financialapp.ui.common.AddTransactionDialog
import com.timepass.financialapp.ui.common.CategoryPickerDialog
import com.timepass.financialapp.ui.common.FilterBarController
import com.timepass.financialapp.ui.common.MessageDialog
import com.timepass.financialapp.ui.common.MultiSelectDialog
import com.timepass.financialapp.ui.common.SelectionController
import com.timepass.financialapp.ui.common.TypeFilter
import com.timepass.financialapp.ui.common.TransactionStyling
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class CategorizedFragment : Fragment() {

    private var _binding: FragmentCategorizedBinding? = null
    private val binding get() = _binding!!

    private val viewModel: CategorizedViewModel by viewModels {
        val app = requireActivity().application as FinancialApp
        CategorizedViewModel.Factory(app.database.transactionDao(), app.database.categoryDao())
    }

    private lateinit var adapter: CategorizedAdapter
    private lateinit var filterBar: FilterBarController
    private lateinit var selection: SelectionController
    private var pendingBulkIds: Set<Long>? = null

    /** Latest rows on screen, so a selection can be checked against what it contains. */
    private var currentRows: List<TransactionEntity> = emptyList()

    /** The row whose picker is open, so an untag result knows what it is undoing. */
    private var pendingPick: TransactionEntity? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCategorizedBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = CategorizedAdapter(
            onItemClick = { transaction ->
                if (selection.isActive) {
                    selection.toggle(transaction.id)
                    return@CategorizedAdapter
                }
                CategoryPickerDialog.show(
                    childFragmentManager,
                    transaction.id,
                    isIncome = transaction.transactionType == "CREDIT",
                    isTagged = true,
                    existingNote = transaction.note
                )
                pendingPick = transaction
            },
            onMessageClick = { transaction -> MessageDialog.show(requireContext(), transaction) },
            onLongClick = { transaction -> selection.toggle(transaction.id) }
        )
        binding.recyclerView.adapter = adapter

        selection = SelectionController(
            binding = binding.selectionBar,
            action1Label = R.string.action_change_category,
            action2Label = R.string.action_move_pending,
            onAction1 = { ids ->
                pendingBulkIds = ids
                CategoryPickerDialog.show(childFragmentManager, -1L, isIncome = false)
            },
            onAction2 = { ids -> bulkUntag(ids) },
            action3Label = R.string.delete,
            onAction3 = { ids -> confirmDelete(ids) },
            // Delete is offered only when everything selected was typed in by hand.
            action3AppliesTo = { ids -> ids.all(::isManual) },
            onChanged = { ids ->
                adapter.setSelection(ids)
                // The selection bar occupies the bottom of the screen, which is where the
                // Add button floats; showing both puts one on top of the other.
                binding.fabAddTransaction.visibility =
                    if (ids.isEmpty()) View.VISIBLE else View.GONE
            }
        ).also { it.attach() }

        binding.fabAddTransaction.setOnClickListener {
            AddTransactionDialog.show(requireContext(), viewModel.categories.value) { entry ->
                viewModel.addManual(entry) {
                    _binding?.let {
                        Snackbar.make(it.root, R.string.add_saved, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        filterBar = FilterBarController(
            binding = binding.filterBar,
            showCategoryFilter = true,
            onMonthPicked = viewModel::setMonth,
            onPrevMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth,
            onTypeClicked = { showTypeDialog() },
            onModeClicked = { showModeDialog() },
            onSenderClicked = { showSenderDialog() },
            onAccountClicked = { showAccountDialog() },
            onCategoryClicked = { showCategoryDialog() },
            onSearchChanged = viewModel::setSearch,
            onClearFilters = viewModel::clearFilters
        ).also { it.attach() }

        // Listener must be on childFragmentManager -- that is where the picker, shown via
        // childFragmentManager, posts its result. See UncategorizedFragment for detail.
        childFragmentManager.setFragmentResultListener(
            CategoryPickerDialog.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val transactionId = bundle.getLong(CategoryPickerDialog.ARG_TRANSACTION_ID)

            if (bundle.getBoolean(CategoryPickerDialog.ARG_UNTAG)) {
                val target = pendingPick?.takeIf { it.id == transactionId } ?: return@setFragmentResultListener
                viewModel.untag(target) {
                    Snackbar.make(binding.root, R.string.moved_to_pending, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) { viewModel.undoUntag() }
                        .show()
                }
                return@setFragmentResultListener
            }

            val categoryId = bundle.getLong(CategoryPickerDialog.ARG_CATEGORY_ID)
            val note = bundle.getString(CategoryPickerDialog.ARG_NOTE)

            val bulk = pendingBulkIds
            if (bulk != null) {
                pendingBulkIds = null
                viewModel.categorizeAll(bulk, categoryId, note) {
                    selection.clear()
                    Snackbar.make(
                        binding.root,
                        getString(R.string.bulk_tagged, bulk.size),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } else {
                viewModel.recategorize(transactionId, categoryId, note)
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
                    viewModel.categories,
                    viewModel.paymentModes,
                    viewModel.senders
                ) { filter, transactions, categories, _, _ ->
                    Triple(filter, transactions, categories)
                }.collect { (filter, transactions, categories) ->
                    adapter.updateCategories(categories.associateBy { it.id })

                    val categoryLabel = filter.categoryIds.singleOrNull()?.let { id ->
                        categories.firstOrNull { it.id == id }?.name
                    }
                    filterBar.render(filter, categoryLabel)

                    currentRows = transactions
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







    private fun isManual(id: Long): Boolean =
        currentRows.firstOrNull { it.id == id }?.paymentMode == "MANUAL"

    /**
     * Confirms before removing, because this is the only action in the app that cannot be
     * undone -- every other one moves a row between lists and leaves it recoverable.
     */
    private fun confirmDelete(ids: Set<Long>) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete)
            .setMessage(resources.getQuantityString(R.plurals.delete_confirm, ids.size, ids.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteManual(ids) { removed ->
                    selection.clear()
                    _binding?.let {
                        Snackbar.make(
                            it.root,
                            resources.getQuantityString(R.plurals.delete_done, removed, removed),
                            Snackbar.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun bulkUntag(ids: Set<Long>) {
        viewModel.untagAll(ids) {
            selection.clear()
            Snackbar.make(
                binding.root,
                getString(R.string.bulk_pending, ids.size),
                Snackbar.LENGTH_LONG
            ).show()
        }
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

    private fun showCategoryDialog() {
        MultiSelectDialog.show(
            requireContext(),
            R.string.filter_category,
            viewModel.categories.value.map { it.id to it.name },
            viewModel.filter.value.categoryIds,
            viewModel::setCategoryIds
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerView.adapter = null
        _binding = null
    }
}
