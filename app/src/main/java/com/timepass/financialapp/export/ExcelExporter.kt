package com.timepass.financialapp.export

import android.content.Context
import android.os.Environment
import com.timepass.financialapp.data.local.entity.TransactionEntity
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports categorized transactions to an Excel (.xlsx) file using Apache POI.
 *
 * Produces a formatted spreadsheet with bold headers, auto-width columns,
 * and currency-formatted amount cells.
 */
class ExcelExporter(private val context: Context) {

    companion object {
        private val HEADERS = arrayOf("Date", "Amount", "Category", "Mode", "Note", "Description")

        /** Width of each column in characters, matched to [HEADERS] by position. */
        private val COLUMN_WIDTHS = intArrayOf(18, 14, 20, 14, 30, 80)

        private const val SHEET_NAME = "Transactions"
    }

    /**
     * Exports the given transactions to an .xlsx file.
     *
     * @param transactions The list of transactions to export
     * @param categoryMap A mapping from category ID to category name
     * @return The absolute path of the created file
     */
    fun export(
        transactions: List<TransactionEntity>,
        categoryMap: Map<Long, String>
    ): String {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet(SHEET_NAME)

        // --- Header style: bold white text on dark-blue background ---
        val headerFont = workbook.createFont().apply {
            bold = true
            fontHeightInPoints = 12
            color = IndexedColors.WHITE.index
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFont(headerFont)
            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        // --- Amount style: currency format with 2 decimal places ---
        val amountFormat = workbook.createDataFormat().getFormat("#,##0.00")
        val amountStyle = workbook.createCellStyle().apply {
            dataFormat = amountFormat
            alignment = HorizontalAlignment.RIGHT
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        // --- Default cell style with borders ---
        val defaultStyle = workbook.createCellStyle().apply {
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        // --- Create header row ---
        val headerRow = sheet.createRow(0)
        HEADERS.forEachIndexed { index, title ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(title)
            cell.cellStyle = headerStyle
        }

        // --- Populate data rows ---
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        transactions.forEachIndexed { index, transaction ->
            val row = sheet.createRow(index + 1)

            // Date
            row.createCell(0).apply {
                setCellValue(dateFormatter.format(Date(transaction.transactionDate)))
                cellStyle = defaultStyle
            }

            // Amount (with sign indicator for debit/credit)
            row.createCell(1).apply {
                val signedAmount = if (transaction.transactionType == "DEBIT") {
                    -transaction.amount
                } else {
                    transaction.amount
                }
                setCellValue(signedAmount)
                cellStyle = amountStyle
            }

            // Category
            row.createCell(2).apply {
                val categoryName = transaction.categoryId?.let { categoryMap[it] } ?: "Uncategorized"
                setCellValue(categoryName)
                cellStyle = defaultStyle
            }

            // Payment Mode
            row.createCell(3).apply {
                setCellValue(transaction.paymentMode)
                cellStyle = defaultStyle
            }

            // User note
            row.createCell(4).apply {
                setCellValue(transaction.note.orEmpty())
                cellStyle = defaultStyle
            }

            // Description (full SMS)
            row.createCell(5).apply {
                setCellValue(transaction.description)
                cellStyle = defaultStyle
            }
        }

        // --- Column widths ---
        // sheet.autoSizeColumn() cannot be used on Android: it measures glyphs via
        // java.awt.font.FontRenderContext, which does not exist in the Android runtime,
        // and throws NoClassDefFoundError. Widths are therefore fixed, in units of
        // 1/256th of a character.
        COLUMN_WIDTHS.forEachIndexed { index, chars ->
            sheet.setColumnWidth(index, chars * 256)
        }

        // --- Write to file ---
        val file = createOutputFile()
        FileOutputStream(file).use { outputStream ->
            workbook.write(outputStream)
        }
        workbook.close()

        return file.absolutePath
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
