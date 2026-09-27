package com.timepass.financialapp.mail

import android.content.Context
import android.util.Log
import com.timepass.financialapp.backup.BackupError
import com.timepass.financialapp.backup.BackupPrefs
import com.timepass.financialapp.data.local.AppDatabase
import com.timepass.financialapp.data.local.entity.MailRawEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.data.local.entity.TransactionSource
import com.timepass.financialapp.sms.CrossSourceDedupe
import com.timepass.financialapp.sms.ImportLock
import com.timepass.financialapp.sms.SmsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Imports bank alert emails from Gmail, through the same steps as SMS.
 *
 *  1. Only mail from known bank domains, received on or after [MAIL_CUTOFF_MILLIS].
 *  2. Each email is recorded in mail_raw so it is never fetched twice.
 *  3. The alert is parsed; a transaction lands in Pending, uncategorized.
 *  4. When the SMS for the same payment is already on file, the email is folded into that
 *     row instead ([CrossSourceDedupe]), so one payment is one row.
 */
class MailReader(
    private val context: Context,
    private val database: AppDatabase,
    private val prefs: MailPrefs = MailPrefs(context),
    private val auth: MailAuth = MailAuth(context),
    private val gmail: GmailClient = GmailClient()
) {

    companion object {
        private const val TAG = "MailReader"

        /**
         * September 1, 2026 00:00 IST. Email starts later than SMS (April 1): the user
         * chose to import only mail from this date on.
         */
        val MAIL_CUTOFF_MILLIS: Long = java.time.LocalDate.of(2026, 9, 1)
            .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata"))
            .toInstant()
            .toEpochMilli()

        /**
         * How far before the last sync the next one looks again. Gmail's `after:` is
         * coarse and mail can be delivered late; already-seen ids are skipped cheaply.
         */
        private const val OVERLAP_MS = 2 * 24 * 60 * 60 * 1000L

        /**
         * Bumped when email parsing or pairing changes enough that emails already imported
         * should be read again. v2: account masks with leading digits, wider pairing
         * windows, promotional subjects, stylesheet text in plain bodies. v3: repeated
         * copies of one alert email.
         */
        private const val IMPORT_VERSION = 3

        /** How far apart a bank's repeated copy of one alert email can arrive. */
        private const val REPEAT_WINDOW_MS = 3 * 60 * 60 * 1000L

        /** A first sync reaches back to the cutoff; this bounds it. */
        private const val MAX_MESSAGES_PER_SYNC = 2_000

        /** ~600 fetches a minute, 3,000 quota units: a fifth of the per-user limit. */
        private const val FETCH_SPACING_MS = 100L

        private const val RATE_LIMIT_RETRIES = 3
        private const val RATE_LIMIT_WAIT_MS = 30_000L
    }

    /**
     * Whether email is read at all: only when a Google account is connected on the Backup
     * screen *and* Gmail access was granted there. Otherwise the app stays SMS-only.
     */
    fun isActive(): Boolean = BackupPrefs(context).authorized && prefs.enabled

    /**
     * Syncs when email reading is active ([isActive]); never shows UI.
     *
     * @return transactions added, or 0 when email reading is off
     * @throws BackupError.NeedsReauth when Gmail access has lapsed and must be granted again
     */
    suspend fun syncIfEnabled(): Int {
        if (!isActive()) return 0
        val token = auth.silentToken() ?: throw BackupError.NeedsReauth
        return sync(token)
    }

    /** @return how many new transactions were added */
    suspend fun sync(token: String): Int {
        val smsOrphaned = ImportLock.mutex.withLock {
            withContext(Dispatchers.IO) {
                val reimport = prefs.importVersion < IMPORT_VERSION
                val orphaned = if (reimport) dropForReimport() else dropBeforeCutoff()
                prefs.importVersion = IMPORT_VERSION
                orphaned
            }
        }
        // Outside the lock: reconcile takes it itself. Brings back as SMS rows any SMS
        // that had been folded into an email row that was just removed.
        if (smsOrphaned) runCatching { SmsReader(context, database).reconcile() }

        return ImportLock.mutex.withLock {
            withContext(Dispatchers.IO) { syncLocked(token) }
        }
    }

    /**
     * Forgets every imported email so the next sync reads them all again with the current
     * parser. As with the cutoff, only rows the user has not tagged or ignored are removed.
     *
     * @return true when removed rows had absorbed an SMS that must be re-imported
     */
    private suspend fun dropForReimport(): Boolean {
        val everything = Long.MAX_VALUE
        val dao = database.transactionDao()
        val orphaned = dao.countMailAbsorbingSmsBefore(everything) > 0
        val removed = dao.deleteUntouchedMailBefore(everything)
        val unlinked = dao.clearMailConfirmationsBefore(everything)
        database.mailRawDao().deleteAll()
        prefs.lastSyncAt = 0L
        Log.i(TAG, "Re-import: removed $removed email rows, unlinked $unlinked")
        return orphaned
    }

    /**
     * Removes email data older than [MAIL_CUTOFF_MILLIS], which exists when the cutoff
     * was moved later after emails had already been imported. Rows the user tagged or
     * ignored are kept. Idempotent and cheap, so it runs before every sync.
     *
     * @return true when removed rows had absorbed an SMS that must be re-imported
     */
    private suspend fun dropBeforeCutoff(): Boolean {
        val dao = database.transactionDao()
        val orphaned = dao.countMailAbsorbingSmsBefore(MAIL_CUTOFF_MILLIS) > 0
        val removed = dao.deleteUntouchedMailBefore(MAIL_CUTOFF_MILLIS)
        val unlinked = dao.clearMailConfirmationsBefore(MAIL_CUTOFF_MILLIS)
        database.mailRawDao().deleteBefore(MAIL_CUTOFF_MILLIS)
        if (removed > 0 || unlinked > 0) {
            Log.i(TAG, "Before email cutoff: removed $removed email rows, unlinked $unlinked")
        }
        return orphaned
    }

    private suspend fun syncLocked(token: String): Int {
        val mailRawDao = database.mailRawDao()
        val transactionDao = database.transactionDao()
        val startedAt = System.currentTimeMillis()

        val since = maxOf(MAIL_CUTOFF_MILLIS, prefs.lastSyncAt - OVERLAP_MS)
        val ids = gmail.search(token, searchQuery(since), MAX_MESSAGES_PER_SYNC)
        val seen = mailRawDao.getAllMessageIds().toHashSet()
        Log.i(TAG, "Mail sync: ${ids.size} matched, ${ids.count { it !in seen }} not yet read")

        var added = 0
        // Oldest first, so pairing with SMS follows the order the payments happened in.
        for (id in ids.asReversed()) {
            if (id in seen) continue

            // After a restore mail_raw is empty but the restored rows still carry their
            // message ids; those emails are recorded as seen, not imported again.
            if (transactionDao.countByMessageRef(id) > 0) {
                mailRawDao.insert(MailRawEntity(messageId = id, sender = "", subject = "", receivedAt = 0L, isProcessed = true))
                continue
            }

            // A network or sign-in failure propagates and aborts the sync without moving
            // lastSyncAt, so the next run retries from the same point. Anything else -- a
            // message deleted since the search, a malformed one -- is skipped, or it would
            // stop every future sync at the same email.
            val message = try {
                fetchPaced(token, id)
            } catch (e: BackupError) {
                if (e.retryable || e is BackupError.NeedsReauth) throw e
                Log.w(TAG, "Skipping email $id: ${e::class.java.simpleName}")
                continue
            } catch (e: org.json.JSONException) {
                Log.w(TAG, "Skipping malformed email $id", e)
                continue
            }
            try {
                if (importOne(message)) added++
            } catch (e: Exception) {
                Log.e(TAG, "Error importing email $id", e)
            }
        }

        prefs.lastSyncAt = startedAt
        Log.i(TAG, "Mail sync: ${ids.size} matched, $added transactions added")
        return added
    }

    /**
     * Fetches one email, staying under Gmail's per-user rate limit.
     *
     * Each fetch costs 5 of the 15,000 quota units a user gets per minute, and a first
     * sync reaching back to the cutoff can mean hundreds of them -- fetched back to back
     * that trips the limit within seconds. So fetches are spaced out, and when Gmail still
     * says "slow down" the sync waits for the minute to roll over and tries again rather
     * than giving up.
     */
    private suspend fun fetchPaced(token: String, id: String): GmailClient.Message {
        var attempt = 0
        while (true) {
            delay(FETCH_SPACING_MS)
            try {
                return gmail.fetch(token, id)
            } catch (e: BackupError.RateLimited) {
                if (++attempt > RATE_LIMIT_RETRIES) throw e
                Log.w(TAG, "Gmail rate limit hit; waiting before retry $attempt")
                delay(RATE_LIMIT_WAIT_MS * attempt)
            }
        }
    }

    /** @return true when a new transaction row was created */
    private suspend fun importOne(message: GmailClient.Message): Boolean {
        val sender = MailSenderFilter.addressOf(message.from)
        val raw = MailRawEntity(
            messageId = message.id,
            sender = sender,
            subject = message.subject,
            receivedAt = message.receivedAt,
            isProcessed = true
        )

        val relevant = message.receivedAt >= MAIL_CUTOFF_MILLIS && MailSenderFilter.isBankMail(sender)
        val parsed = if (relevant) MailParser.parse(message.subject, message.body) else null
        if (parsed == null) {
            database.mailRawDao().insert(raw)
            return false
        }

        val transactionDao = database.transactionDao()
        val outcome = CrossSourceDedupe.foldIntoExisting(
            transactionDao, parsed, message.receivedAt, TransactionSource.MAIL, message.id
        )
        val isNew = outcome == CrossSourceDedupe.Outcome.New &&
            !isRepeatOfEarlierEmail(parsed, sender, message)
        if (isNew) {
            transactionDao.insert(
                TransactionEntity(
                    amount = parsed.amount,
                    transactionType = parsed.transactionType,
                    paymentMode = parsed.paymentMode,
                    categoryId = null,
                    smsRawId = null,
                    sender = sender,
                    description = MailParser.toDescription(message.subject, message.body),
                    merchantName = parsed.merchantName,
                    accountRef = parsed.accountRef,
                    accountKind = parsed.accountKind,
                    transactionDate = message.receivedAt,
                    smsDate = message.receivedAt,
                    isCategorized = false,
                    source = TransactionSource.MAIL,
                    sourceRef = message.id
                )
            )
        }
        // Recorded after the row, so a crash in between re-imports rather than loses it.
        database.mailRawDao().insert(raw)
        return isNew
    }

    /**
     * Banks sometimes send the same alert twice -- IndusInd two hours apart, SBI Card a
     * minute apart -- as separate messages with separate ids. The first is on file; this
     * decides whether [message] is the second copy.
     *
     * Checked only after pairing with an SMS has failed, so a genuine second payment that
     * has its own SMS always pairs with it instead. What remains is:
     *  - an earlier email from the same sender already paired with an SMS: that SMS is the
     *    one payment, so this is a repeat;
     *  - an earlier email row from the same sender whose alert sentence is word for word
     *    the same. Two real payments differ in their reference number or time.
     */
    private suspend fun isRepeatOfEarlierEmail(
        parsed: com.timepass.financialapp.sms.SmsParser.ParseResult,
        sender: String,
        message: GmailClient.Message
    ): Boolean {
        val dao = database.transactionDao()
        val from = message.receivedAt - REPEAT_WINDOW_MS
        val to = message.receivedAt + REPEAT_WINDOW_MS
        fun sameAccount(ref: String?) = ref == null || parsed.accountRef == null ||
            ref.endsWith(parsed.accountRef) || parsed.accountRef.endsWith(ref)

        // SMS rows (candidates from the MAIL side's point of view) already confirmed by an
        // email from this sender.
        val pairedSms = dao.findCrossSourceCandidates(
            parsed.amount, parsed.transactionType, TransactionSource.MAIL, from, to
        ).filter { it.confirmedBy == TransactionSource.MAIL && sameAccount(it.accountRef) }
        for (row in pairedSms) {
            val ref = row.confirmedRef ?: continue
            if (database.mailRawDao().senderOf(ref) == sender) return true
        }

        // Email rows from this sender with the same alert text.
        val snippet = MailParser.transactionSnippet(message.body) ?: return false
        return dao.findCrossSourceCandidates(
            parsed.amount, parsed.transactionType, TransactionSource.SMS, from, to
        ).any { row ->
            row.source == TransactionSource.MAIL && row.sender == sender &&
                sameAccount(row.accountRef) &&
                MailParser.transactionSnippet(row.description.substringAfter('\n', "")) == snippet
        }
    }

    /**
     * `after:` takes epoch seconds. The sender list is an OR group, so Gmail returns only
     * bank mail and nothing else in the inbox is ever downloaded.
     */
    private fun searchQuery(sinceMillis: Long): String {
        val senders = MailSenderFilter.searchDomains.joinToString(" ") { "from:$it" }
        return "after:${sinceMillis / 1000} {$senders}"
    }
}
