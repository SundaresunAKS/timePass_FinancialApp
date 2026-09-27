package com.timepass.financialapp.export

import android.content.Context
import android.os.Environment
import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.ui.common.AccountLabels
import com.timepass.financialapp.ui.common.TransactionStyling
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.WorkbookUtil
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports categorized transactions to a single Excel (.xlsx) file using Apache POI.
 *
 * One workbook, several sheets:
 *  - **All Transactions** -- every tagged transaction;
 *  - **Summary** -- debit, credit and net per account and per category;
 *  - one **Acct -** sheet per bank account or card;
 *  - one **Cat -** sheet per category.
 *
 * Every transaction sheet has the same columns and ends in a Net row, so a sheet can be
 * read on its own.
 */
class ExcelExporter(private val context: Context) {

    companion object {
        private val HEADERS = arrayOf(
            "Date", "Amount", "Category", "Mode", "Account", "Source", "Note", "Description"
        )

        /** Width of each column in characters, matched to [HEADERS] by position. */
        private val COLUMN_WIDTHS = intArrayOf(18, 14, 20, 14, 24, 12, 30, 80)

        private val SUMMARY_HEADERS = arrayOf("Name", "Transactions", "Debit", "Credit", "Net")
        private val SUMMARY_WIDTHS = intArrayOf(30, 14, 16, 16, 16)

        private const val ALL_SHEET = "All Transactions"
        private const val SUMMARY_SHEET = "Summary"
        private const val ACCOUNT_PREFIX = "Acct - "
        private const val CATEGORY_PREFIX = "Cat - "
        private const val NO_ACCOUNT = "No account"
        private const val UNCATEGORIZED = "Uncategorized"
    }

    /** Cell styles are per workbook and capped in number, so they are made once. */
    private class Styles(workbook: XSSFWorkbook) {
        val header: XSSFCellStyle
        val amount: XSSFCellStyle
        val text: XSSFCellStyle
        val totalLabel: XSSFCellStyle
        val totalAmount: XSSFCellStyle

        init {
            val headerFont = workbook.createFont().apply {
                bold = true
                fontHeightInPoints = 12
                color = IndexedColors.WHITE.index
            }
            val boldFont = workbook.createFont().apply { bold = true }
            val amountFormat = workbook.createDataFormat().getFormat("#,##0.00")

            header = workbook.createCellStyle().apply {
                setFont(headerFont)
                fillForegroundColor = IndexedColors.DARK_BLUE.index
                fillPattern = FillPatternType.SOLID_FOREGROUND
                alignment = HorizontalAlignment.CENTER
                bordered()
            }
            amount = workbook.createCellStyle().apply {
                dataFormat = amountFormat
                alignment = HorizontalAlignment.RIGHT
                bordered()
            }
            text = workbook.createCellStyle().apply { bordered() }
            totalLabel = workbook.createCellStyle().apply {
                setFont(boldFont)
                bordered()
            }
            totalAmount = workbook.createCellStyle().apply {
                setFont(boldFont)
                dataFormat = amountFormat
                alignment = HorizontalAlignment.RIGHT
                bordered()
            }
        }

        private fun XSSFCellStyle.bordered() {
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    /**
     * Exports the given transactions to an .xlsx file.
     *
     * @param transactions the transactions to export, newest first
     * @param categories every category, in the user's order; drives the Cat sheets
     * @param accountLabels account reference to its display name, from [AccountLabels.build]
     * @return the absolute path of the created file
     */
    fun export(
        transactions: List<TransactionEntity>,
        categories: List<CategoryEntity>,
        accountLabels: Map<String, String>
    ): String {
        val workbook = XSSFWorkbook()
        val styles = Styles(workbook)
        val categoryNames = categories.associate { it.id to it.name }
        val usedNames = mutableSetOf<String>()

        fun categoryOf(t: TransactionEntity) = t.categoryId?.let { categoryNames[it] } ?: UNCATEGORIZED
        fun accountOf(t: TransactionEntity) = t.accountRef?.let { ref ->
            accountLabels[ref] ?: AccountLabels.label(null, t.accountKind, ref)
        } ?: NO_ACCOUNT

        writeTransactions(
            workbook.createSheet(uniqueName(ALL_SHEET, usedNames)),
            transactions, styles, ::categoryOf, ::accountOf
        )

        // Accounts in label order; "No account" (manual entries, alerts naming none) last.
        val byAccount = transactions.groupBy(::accountOf)
            .toSortedMap(compareBy<String> { it == NO_ACCOUNT }.thenBy { it })
        // Categories in the user's own order, so the sheets line up with the app's list.
        val categoryOrder = categories.sortedBy { it.sortOrder }.map { it.name } + UNCATEGORIZED
        val byCategory = transactions.groupBy(::categoryOf)
            .toSortedMap(
                compareBy<String> { categoryOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                    .thenBy { it }
            )

        writeSummary(
            workbook.createSheet(uniqueName(SUMMARY_SHEET, usedNames)),
            byAccount, byCategory, styles
        )

        byAccount.forEach { (account, rows) ->
            val name = uniqueName(ACCOUNT_PREFIX + account, usedNames)
            writeTransactions(workbook.createSheet(name), rows, styles, ::categoryOf, ::accountOf)
        }
        byCategory.forEach { (category, rows) ->
            val name = uniqueName(CATEGORY_PREFIX + category, usedNames)
            writeTransactions(workbook.createSheet(name), rows, styles, ::categoryOf, ::accountOf)
        }

        // POI writes formulas without cached results; this makes Excel work them out on open.
        workbook.setForceFormulaRecalculation(true)

        val file = createOutputFile()
        FileOutputStream(file).use { workbook.write(it) }
        workbook.close()
        return file.absolutePath
    }

    private fun writeTransactions(
        sheet: XSSFSheet,
        transactions: List<TransactionEntity>,
        styles: Styles,
        categoryOf: (TransactionEntity) -> String,
        accountOf: (TransactionEntity) -> String
    ) {
        writeHeader(sheet, HEADERS, styles)
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        transactions.forEachIndexed { index, t ->
            val row = sheet.createRow(index + 1)
            // Signed, so a column total is the net movement.
            val signed = if (t.transactionType == "DEBIT") -t.amount else t.amount
            val values = listOf(
                dateFormatter.format(Date(t.transactionDate)),
                null,
                categoryOf(t),
                TransactionStyling.modeLabel(t.paymentMode),
                accountOf(t),
                TransactionStyling.sourceLabel(context, t.source, t.confirmedBy),
                t.note.orEmpty(),
                t.description
            )
            values.forEachIndexed { col, value ->
                row.createCell(col).apply {
                    if (col == 1) {
                        setCellValue(signed)
                        cellStyle = styles.amount
                    } else {
                        setCellValue(value.orEmpty())
                        cellStyle = styles.text
                    }
                }
            }
        }

        if (transactions.isNotEmpty()) {
            val totalRow = sheet.createRow(transactions.size + 1)
            totalRow.createCell(0).apply {
                setCellValue("Net")
                cellStyle = styles.totalLabel
            }
            totalRow.createCell(1).apply {
                cellFormula = "SUM(B2:B${transactions.size + 1})"
                cellStyle = styles.totalAmount
            }
        }

        finishSheet(sheet, COLUMN_WIDTHS)
    }

    /** Two tables, accounts then categories, separated by a blank row. */
    private fun writeSummary(
        sheet: XSSFSheet,
        byAccount: Map<String, List<TransactionEntity>>,
        byCategory: Map<String, List<TransactionEntity>>,
        styles: Styles
    ) {
        writeHeader(sheet, arrayOf("Account") + SUMMARY_HEADERS.drop(1), styles)
        var rowIndex = 1
        byAccount.forEach { (name, rows) -> writeSummaryRow(sheet, rowIndex++, name, rows, styles) }

        rowIndex++
        val header = sheet.createRow(rowIndex++)
        (arrayOf("Category") + SUMMARY_HEADERS.drop(1)).forEachIndexed { i, title ->
            header.createCell(i).apply {
                setCellValue(title)
                cellStyle = styles.header
            }
        }
        byCategory.forEach { (name, rows) -> writeSummaryRow(sheet, rowIndex++, name, rows, styles) }

        finishSheet(sheet, SUMMARY_WIDTHS)
    }

    private fun writeSummaryRow(
        sheet: XSSFSheet,
        index: Int,
        name: String,
        rows: List<TransactionEntity>,
        styles: Styles
    ) {
        val debit = rows.filter { it.transactionType == "DEBIT" }.sumOf { it.amount }
        val credit = rows.filter { it.transactionType != "DEBIT" }.sumOf { it.amount }
        val row = sheet.createRow(index)
        row.createCell(0).apply { setCellValue(name); cellStyle = styles.text }
        row.createCell(1).apply { setCellValue(rows.size.toDouble()); cellStyle = styles.text }
        row.createCell(2).apply { setCellValue(debit); cellStyle = styles.amount }
        row.createCell(3).apply { setCellValue(credit); cellStyle = styles.amount }
        row.createCell(4).apply { setCellValue(credit - debit); cellStyle = styles.totalAmount }
    }

    private fun writeHeader(sheet: XSSFSheet, headers: Array<String>, styles: Styles) {
        val row = sheet.createRow(0)
        headers.forEachIndexed { index, title ->
            row.createCell(index).apply {
                setCellValue(title)
                cellStyle = styles.header
            }
        }
    }

    private fun finishSheet(sheet: XSSFSheet, widths: IntArray) {
        // sheet.autoSizeColumn() cannot be used on Android: it measures glyphs via
        // java.awt.font.FontRenderContext, which does not exist in the Android runtime,
        // and throws NoClassDefFoundError. Widths are therefore fixed, in units of
        // 1/256th of a character.
        widths.forEachIndexed { index, chars -> sheet.setColumnWidth(index, chars * 256) }
        sheet.createFreezePane(0, 1)
    }

    /**
     * Excel sheet names are at most 31 characters, cannot contain `/ \ ? * [ ] :`, and
     * must be unique ignoring case. "A/c" is common in account labels, so it is spelt
     * "A-c" rather than losing the slash to a space.
     */
    private fun uniqueName(wanted: String, used: MutableSet<String>): String {
        val base = WorkbookUtil.createSafeSheetName(wanted.replace("/", "-")).take(31)
        var name = base
        var n = 2
        while (name.lowercase() in used) {
            val suffix = " ($n)"
            name = base.take(31 - suffix.length) + suffix
            n++
        }
        used += name.lowercase()
        return name
    }

    /**
     * Creates the output file in the app's external Documents directory.
     */
    private fun createOutputFile(): File {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: throw IllegalStateException("External documents directory is not available")

        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }

        // Seconds are included so two exports on the same day are distinct files rather
        // than one silently overwriting the other.
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
        val fileName = "ExpenseTracker_$stamp.xlsx"

        return File(documentsDir, fileName)
    }
}
