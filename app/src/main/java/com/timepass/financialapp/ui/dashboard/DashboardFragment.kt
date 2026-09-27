package com.timepass.financialapp.ui.dashboard

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import androidx.core.content.ContextCompat
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils as AndroidDateUtils
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.MainActivity
import com.timepass.financialapp.R
import com.timepass.financialapp.backup.BackupPrefs
import com.timepass.financialapp.data.prefs.AppPreferences
import com.timepass.financialapp.security.AppLock
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.databinding.FragmentDashboardBinding
import com.timepass.financialapp.databinding.ItemDashboardRecentBinding
import com.timepass.financialapp.ui.common.TransactionStyling
import com.timepass.financialapp.ui.common.MonthYearPickerDialog
import com.timepass.financialapp.util.CurrencyUtils
import com.timepass.financialapp.util.DateUtils
import kotlinx.coroutines.launch

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DashboardViewModel by viewModels {
        val app = requireActivity().application as FinancialApp
        DashboardViewModel.Factory(app.database.transactionDao(), app.database.categoryDao())
    }

    private val appPrefs by lazy { AppPreferences(requireContext()) }
    private val backupPrefs by lazy { BackupPrefs(requireContext()) }

    /**
     * Whether the figures are showing. Deliberately not persisted: the point of hiding
     * them is that a glance at the screen gives nothing away, so every visit starts covered.
     */
    private var amountsVisible = false

    /** Latest figures, kept so the eye can re-render without waiting for another emission. */
    private var latestData: DashboardViewModel.DashboardData? = null


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupPieChart()
        setupMonthNavigation()
        setupAmountsToggle()
        renderAccount()
        renderAccountChip()
        observeData()

        // The card is about the account, so it leads where the account is managed.
        binding.accountCard.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }

        binding.btnGoPending.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_pending)
        }

        binding.chipAccount.setOnClickListener { showAccountPicker() }

        binding.btnMenu.setOnClickListener {
            (requireActivity() as? MainActivity)?.openDrawer()
        }

        binding.btnSeeAll.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_pending)
        }
    }

    override fun onResume() {
        super.onResume()
        // Connecting Drive, backing up and switching account all happen on other screens.
        renderAccount()
    }

    /**
     * One quiet line at the top: who is signed in, and whether the data is safe.
     *
     * Carries no figures, so it is unaffected by whether the totals below are masked.
     */
    /**
     * Narrows the whole screen to one account or card.
     *
     * Single choice, unlike the lists: the dashboard answers "how did this card do this
     * month", and summing two arbitrary cards is not a question worth a multi-select.
     */
    private fun showAccountPicker() {
        val accounts = viewModel.availableAccounts.value
        val labels = mutableListOf(getString(R.string.dashboard_all_accounts))
        labels += accounts.map { viewModel.accountLabel(it) }

        val current = viewModel.accountRef.value
        val checked = if (current == null) 0 else accounts.indexOf(current) + 1

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_account)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                viewModel.setAccountRef(if (which == 0) null else accounts[which - 1])
                renderAccountChip()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderAccountChip() {
        val binding = _binding ?: return
        val ref = viewModel.accountRef.value
        binding.chipAccount.text =
            if (ref == null) getString(R.string.dashboard_all_accounts)
            else viewModel.accountLabel(ref)
        binding.chipAccount.isChecked = ref != null
    }

    private fun renderAccount() {
        val binding = _binding ?: return
        val connected = backupPrefs.isConnected
        val name = backupPrefs.accountName?.trim()?.takeIf { it.isNotEmpty() }
        val email = backupPrefs.accountEmail?.trim()?.takeIf { it.isNotEmpty() }

        if (!connected) {
            // Signed out, so the card says the one useful thing and nothing else: a backup
            // time and an account line would both be blanks pretending to be information.
            binding.tvUserName.setText(R.string.nav_welcome)
            binding.tvUserEmail.setText(R.string.header_connect_hint)
            binding.backupState.visibility = View.GONE
            renderAvatar(null)
            return
        }

        binding.backupState.visibility = View.VISIBLE

        // A connection made before the app asked for the email and profile scopes has
        // neither, so "connected but nameless" is a real state and needs its own wording.
        binding.tvUserName.text = name ?: email ?: getString(R.string.header_signed_in)
        binding.tvUserEmail.text = when {
            email != null && name != null -> email
            email != null -> getString(R.string.header_backed_up_hint)
            else -> getString(R.string.header_reconnect_hint)
        }

        renderAvatar(name ?: email)
        renderBackupState(true)
    }

    /** Initial on a circle in the app's accent; a person glyph when there is no name. */
    private fun renderAvatar(label: String?) {
        val binding = _binding ?: return
        val accent = MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorPrimary
        )

        binding.tvAvatar.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent)
        }

        val initial = label?.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
        binding.tvAvatar.text = initial?.toString().orEmpty()
        binding.ivAvatarFallback.visibility =
            if (initial == null) View.VISIBLE else View.GONE
    }

    private fun renderBackupState(connected: Boolean) {
        val binding = _binding ?: return
        val lastBackup = backupPrefs.lastBackupAt

        binding.ivBackupState.setImageResource(
            if (connected && lastBackup > 0L) R.drawable.ic_cloud_done else R.drawable.ic_cloud_off
        )
        binding.tvBackupState.text = when {
            !connected -> getString(R.string.header_backup_off)
            lastBackup <= 0L -> getString(R.string.header_backup_never)
            // Relative phrasing ages correctly without the screen having to refresh it.
            else -> AndroidDateUtils.getRelativeTimeSpanString(
                lastBackup,
                System.currentTimeMillis(),
                AndroidDateUtils.MINUTE_IN_MILLIS,
                AndroidDateUtils.FORMAT_ABBREV_RELATIVE
            )
        }
    }

    /**
     * The eye beside the totals.
     *
     * With the app lock on, revealing asks for it again -- otherwise anyone handed the
     * unlocked phone could read the figures, which is the case hiding them is meant to
     * cover. With the lock off there is nothing to verify against, so a tap is enough.
     */
    private fun setupAmountsToggle() {
        binding.btnToggleAmounts.setOnClickListener {
            if (amountsVisible) {
                amountsVisible = false
                renderAmounts()
                return@setOnClickListener
            }

            if (appPrefs.biometricEnabled && AppLock.isAvailable(requireContext())) {
                AppLock.prompt(
                    activity = requireActivity(),
                    titleRes = R.string.dashboard_reveal_title,
                    subtitleRes = R.string.dashboard_reveal_subtitle,
                    onSuccess = {
                        amountsVisible = true
                        // The prompt is asynchronous; the view can be gone by now.
                        if (_binding != null) renderAmounts()
                    }
                )
            } else {
                amountsVisible = true
                renderAmounts()
            }
        }
    }

    /**
     * Writes the figures, or dots in their place.
     *
     * Masking the text itself rather than covering it means the row never changes shape,
     * and -- unlike an overlay -- a screen reader cannot read what is hidden, because the
     * hidden value is genuinely not in the view.
     */
    private fun renderAmounts() {
        val binding = _binding ?: return
        val data = latestData
        val masked = getString(R.string.amount_hidden)

        binding.tvTotalIncome.text =
            if (amountsVisible && data != null) CurrencyUtils.format(data.totalIncome) else masked
        binding.tvTotalExpense.text =
            if (amountsVisible && data != null) CurrencyUtils.format(data.totalExpense) else masked
        binding.tvBalance.text =
            if (amountsVisible && data != null) {
                CurrencyUtils.format(data.totalIncome - data.totalExpense)
            } else {
                masked
            }

        binding.btnToggleAmounts.setImageResource(
            if (amountsVisible) R.drawable.ic_eye_off else R.drawable.ic_eye
        )

        renderRecent(data?.recent.orEmpty(), masked)
    }

    /**
     * Draws the month's newest transactions into the card.
     *
     * Rows are inflated straight into a LinearLayout rather than driven by a RecyclerView:
     * there are at most six of them, they sit inside a ScrollView, and a nested scrolling
     * list here would cost more than it saves.
     */
    private fun renderRecent(recent: List<TransactionEntity>, masked: String) {
        val binding = _binding ?: return
        val container = binding.recentContainer

        binding.recentCard.visibility = if (recent.isEmpty()) View.GONE else View.VISIBLE
        if (recent.isEmpty()) {
            container.removeAllViews()
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        // Reuse the rows already there; the list changes size rarely and rebuilding every
        // emission would drop the ripple mid-press.
        while (container.childCount > recent.size) {
            container.removeViewAt(container.childCount - 1)
        }
        while (container.childCount < recent.size) {
            container.addView(ItemDashboardRecentBinding.inflate(inflater, container, false).root)
        }

        recent.forEachIndexed { index, txn ->
            val row = ItemDashboardRecentBinding.bind(container.getChildAt(index))
            val credit = txn.transactionType == "CREDIT"
            val colour = TransactionStyling.directionColor(requireContext(), txn.transactionType)

            row.viewDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colour)
            }

            row.tvTitle.text = txn.merchantName?.takeIf { it.isNotBlank() }
                ?: txn.description.lineSequence().firstOrNull()?.take(60).orEmpty()

            row.tvMeta.text = getString(
                R.string.dashboard_recent_meta,
                DateUtils.formatDate(txn.transactionDate),
                TransactionStyling.modeLabel(txn.paymentMode)
            )

            row.tvAmount.setTextColor(colour)
            row.tvAmount.text = if (amountsVisible) {
                val sign = if (credit) "+ " else "\u2212 "
                sign + CurrencyUtils.format(txn.amount)
            } else {
                masked
            }
        }
    }

    private fun setupPieChart() {
        // MPAndroidChart defaults every label to black, which disappears against a dark
        // surface. Chart text follows the theme's on-surface colour instead.
        val onSurface = ContextCompat.getColor(requireContext(), R.color.on_surface)

        binding.pieChart.apply {
            setUsePercentValues(true)
            description.isEnabled = false
            isDrawHoleEnabled = true
            setHoleColor(Color.TRANSPARENT)
            holeRadius = 40f
            transparentCircleRadius = 45f
            setDrawEntryLabels(true)
            setEntryLabelColor(onSurface)
            setEntryLabelTextSize(10f)
            legend.isEnabled = true
            legend.textSize = 11f
            legend.textColor = onSurface
        }
    }

    private fun setupMonthNavigation() {
        binding.btnPrevMonth.setOnClickListener { viewModel.previousMonth() }
        binding.btnNextMonth.setOnClickListener { viewModel.nextMonth() }
        binding.btnMonthPicker.setOnClickListener {
            MonthYearPickerDialog.show(
                requireContext(),
                viewModel.selectedMonthYear.value,
                viewModel::setMonth
            )
        }
    }

    private fun observeData() {
        // The picker reads availableAccounts synchronously, and a WhileSubscribed flow with
        // no subscriber never runs -- so without this it always reported no accounts.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.availableAccounts.collect { renderAccountChip() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.selectedMonthYear.collect { monthYear ->
                        binding.tvMonthYear.text = DateUtils.formatMonth(monthYear)
                    }
                }

                launch {
                    viewModel.dashboardData.collect { data ->
                        if (data == null) return@collect
                        latestData = data
                        renderAmounts()

                        val empty = data.categorySpending.isEmpty()
                        binding.pieChart.visibility = if (empty) View.GONE else View.VISIBLE
                        binding.emptyChart.visibility = if (empty) View.VISIBLE else View.GONE
                        if (!empty) updatePieChart(data.categorySpending)

                        // An untagged month would otherwise be a tall blank card; this
                        // turns the space into the one action that fills it.
                        val pending = data.pendingCount
                        binding.btnGoPending.visibility =
                            if (empty && pending > 0) View.VISIBLE else View.GONE
                        if (empty && pending > 0) {
                            binding.btnGoPending.text = resources.getQuantityString(
                                R.plurals.dashboard_go_pending, pending, pending
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updatePieChart(categorySpending: List<DashboardViewModel.CategorySpendingDisplay>) {
        val entries = categorySpending.map { PieEntry(it.amount.toFloat(), it.categoryName) }
        val colors = categorySpending.map { Color.parseColor(it.colorHex) }

        val dataSet = PieDataSet(entries, "").apply {
            this.colors = colors
            sliceSpace = 2f
            valueTextSize = 12f
            valueTextColor = ContextCompat.getColor(requireContext(), R.color.on_surface)
            valueFormatter = PercentFormatter(binding.pieChart)
        }

        binding.pieChart.data = PieData(dataSet)
        binding.pieChart.invalidate()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
