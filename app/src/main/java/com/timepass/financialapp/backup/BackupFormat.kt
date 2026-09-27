package com.timepass.financialapp.backup

import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The backup file's shape and its parser.
 *
 * Categories travel with the expenses because a restore onto a fresh install would
 * otherwise attach transactions to category ids that mean something different -- or
 * nothing at all. Each expense also carries its category *name*, so the mapping can be
 * rebuilt even if ids collide.
 */
object BackupFormat {

    const val FILE_NAME = "expenses_backup.json"
    const val MIME_TYPE = "application/json"

    /** Bumped only for changes a previous version could not read. */
    const val VERSION = 2

    /**
     * App preferences that travel with the data.
     *
     * Restoring onto a new phone should hand back the app the user had, not a default one,
     * so the appearance and the lock come along. Every field is optional: a v1 file has no
     * settings block, and a restore from one must still succeed.
     */
    data class Settings(
        val themeMode: String?,
        val accent: String?,
        val biometricEnabled: Boolean?
    )

    data class Backup(
        val version: Int,
        val exportedAt: Long,
        val appVersion: String,
        val categories: List<CategoryEntity>,
        val transactions: List<TransactionEntity>,
        val settings: Settings?
    )

    // ---------------------------------------------------------------- encode

    fun encode(
        categories: List<CategoryEntity>,
        transactions: List<TransactionEntity>,
        appVersion: String,
        settings: Settings? = null,
        exportedAt: Long = System.currentTimeMillis()
    ): String {
        val categoryNames = categories.associate { it.id to it.name }

        val root = JSONObject().apply {
            put("version", VERSION)
            put("exportedAt", exportedAt)
            put("appVersion", appVersion)

            settings?.let { s ->
                put("settings", JSONObject().apply {
                    put("themeMode", s.themeMode ?: JSONObject.NULL)
                    put("accent", s.accent ?: JSONObject.NULL)
                    put("biometricEnabled", s.biometricEnabled ?: JSONObject.NULL)
                })
            }

            put("categories", JSONArray().apply {
                categories.forEach { c ->
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("colorHex", c.colorHex)
                        put("emoji", c.emoji ?: JSONObject.NULL)
                        put("isIncome", c.isIncome)
                        put("isDefault", c.isDefault)
                        put("sortOrder", c.sortOrder)
                        put("createdAt", c.createdAt)
                    })
                }
            })

            put("expenses", JSONArray().apply {
                transactions.forEach { t ->
                    put(JSONObject().apply {
                        // The seven fields named in the spec
                        put("id", t.id)
                        put("date", t.transactionDate)
                        put("category", t.categoryId?.let { categoryNames[it] } ?: JSONObject.NULL)
                        put("amount", t.amount)
                        put("note", t.note ?: JSONObject.NULL)
                        put("createdAt", t.createdAt)
                        put("updatedAt", t.updatedAt)

                        // Everything else, so a restore is lossless
                        put("categoryId", t.categoryId ?: JSONObject.NULL)
                        put("transactionType", t.transactionType)
                        put("paymentMode", t.paymentMode)
                        put("sender", t.sender ?: JSONObject.NULL)
                        put("merchantName", t.merchantName ?: JSONObject.NULL)
                        put("description", t.description)
                        put("smsDate", t.smsDate)
                        put("isCategorized", t.isCategorized)
                        put("isIgnored", t.isIgnored)
                    })
                }
            })
        }
        return root.toString()
    }

    // ---------------------------------------------------------------- decode

    /**
     * Parses and validates a backup.
     *
     * Throws [BackupError.CorruptBackup] rather than returning partial data: the caller
     * wipes the local database before applying a restore, so a half-parsed file must never
     * reach that point.
     */
    fun decode(json: String): Backup {
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw BackupError.CorruptBackup("File is not valid JSON")
        }

        val version = root.optInt("version", -1)
        if (version <= 0) throw BackupError.CorruptBackup("Missing backup version")
        if (version > VERSION) {
            throw BackupError.CorruptBackup(
                "Backup was made by a newer version of the app (v$version)"
            )
        }

        val categoriesJson = root.optJSONArray("categories")
            ?: throw BackupError.CorruptBackup("Missing categories")
        val expensesJson = root.optJSONArray("expenses")
            ?: throw BackupError.CorruptBackup("Missing expenses")

        val categories = ArrayList<CategoryEntity>(categoriesJson.length())
        for (i in 0 until categoriesJson.length()) {
            val o = categoriesJson.optJSONObject(i)
                ?: throw BackupError.CorruptBackup("Category $i is malformed")
            val name = o.optString("name").takeIf { it.isNotBlank() }
                ?: throw BackupError.CorruptBackup("Category $i has no name")
            categories += CategoryEntity(
                id = o.optLong("id"),
                name = name,
                colorHex = o.optString("colorHex", "#607D8B"),
                emoji = o.optStringOrNull("emoji"),
                isIncome = o.optBoolean("isIncome"),
                isDefault = o.optBoolean("isDefault", true),
                sortOrder = o.optInt("sortOrder"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }

        val byName = categories.associate { it.name to it.id }

        val transactions = ArrayList<TransactionEntity>(expensesJson.length())
        for (i in 0 until expensesJson.length()) {
            val o = expensesJson.optJSONObject(i)
                ?: throw BackupError.CorruptBackup("Expense $i is malformed")

            if (!o.has("amount")) throw BackupError.CorruptBackup("Expense $i has no amount")
            if (!o.has("date")) throw BackupError.CorruptBackup("Expense $i has no date")

            // Prefer the name mapping: ids can collide across installs, names cannot
            // silently point at the wrong category.
            val categoryId = o.optStringOrNull("category")?.let { byName[it] }
                ?: o.optLongOrNull("categoryId")

            transactions += TransactionEntity(
                id = o.optLong("id"),
                amount = o.optDouble("amount"),
                transactionType = o.optString("transactionType", "DEBIT"),
                paymentMode = o.optString("paymentMode", "UNKNOWN"),
                categoryId = categoryId,
                smsRawId = null,
                sender = o.optStringOrNull("sender"),
                description = o.optString("description", ""),
                merchantName = o.optStringOrNull("merchantName"),
                note = o.optStringOrNull("note"),
                transactionDate = o.optLong("date"),
                smsDate = o.optLong("smsDate", o.optLong("date")),
                isCategorized = o.optBoolean("isCategorized", categoryId != null),
                isIgnored = o.optBoolean("isIgnored"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }

        // Absent in v1 files, and a v2 file written before the user ever opened Settings
        // may still omit a field, so each is read independently.
        val settings = root.optJSONObject("settings")?.let { o ->
            Settings(
                themeMode = o.optStringOrNull("themeMode"),
                accent = o.optStringOrNull("accent"),
                biometricEnabled = if (o.isNull("biometricEnabled")) null
                else o.optBoolean("biometricEnabled")
            )
        }

        return Backup(
            version = version,
            exportedAt = root.optLong("exportedAt"),
            appVersion = root.optString("appVersion", "?"),
            categories = categories,
            transactions = transactions,
            settings = settings
        )
    }

    /** org.json turns a JSON null into the string "null"; this returns a real null. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key).takeIf { it != 0L }
}
