package com.timepass.financialapp.sms

import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.data.local.entity.TransactionSource

/**
 * Decides whether a message is the other channel's copy of a payment already on file.
 *
 * Banks send an SMS *and* an email for most transactions. Importing both would count every
 * payment twice, so the first to arrive becomes the row and the second is folded into it
 * ([TransactionDao.markConfirmed]) -- that is what the list shows as "SMS + Mail".
 *
 * A match needs the same amount and direction, a compatible account, and timestamps close
 * together. Pairing is one-to-one: a row already confirmed by the other channel cannot
 * absorb a second message, so two genuine ₹20 payments minutes apart stay two rows.
 */
object CrossSourceDedupe {

    /**
     * Emails can trail the SMS by hours -- IndusInd's arrive up to four hours later -- and
     * the matching account plus one-to-one pairing make a wide window safe.
     */
    private const val WINDOW_WITH_ACCOUNT_MS = 6 * 60 * 60 * 1000L

    /** Without an account to compare on, the window is kept tighter. */
    private const val WINDOW_WITHOUT_ACCOUNT_MS = 2 * 60 * 60 * 1000L

    sealed class Outcome {
        /** Nothing on file; insert a new row. */
        object New : Outcome()

        /** Folded into row [transactionId]; do not insert. */
        data class Merged(val transactionId: Long) : Outcome()

        /** This very message was folded in earlier; do not insert. */
        object AlreadyMerged : Outcome()
    }

    /**
     * Checks the message and, when it matches an unpaired row from the other channel,
     * records the pairing on that row.
     *
     * @param source the channel of the incoming message ([TransactionSource.SMS] or MAIL)
     * @param ref identity of the incoming message, stored so a re-read is recognised
     */
    suspend fun foldIntoExisting(
        dao: TransactionDao,
        parsed: SmsParser.ParseResult,
        date: Long,
        source: String,
        ref: String
    ): Outcome {
        val candidates = dao.findCrossSourceCandidates(
            amount = parsed.amount,
            type = parsed.transactionType,
            source = source,
            fromMillis = date - WINDOW_WITH_ACCOUNT_MS,
            toMillis = date + WINDOW_WITH_ACCOUNT_MS
        ).filter { row ->
            val window = if (row.accountRef != null && parsed.accountRef != null) {
                WINDOW_WITH_ACCOUNT_MS
            } else {
                WINDOW_WITHOUT_ACCOUNT_MS
            }
            accountsCompatible(row.accountRef, parsed.accountRef) &&
                kotlin.math.abs(row.transactionDate - date) <= window
        }

        // The same SMS is read twice -- by the receiver as it lands and by the inbox scan
        // later -- and the first read may already have folded it in.
        if (candidates.any { it.confirmedBy == source && sameMessage(source, it.confirmedRef, ref) }) {
            return Outcome.AlreadyMerged
        }

        val partner = candidates
            .filter { it.confirmedBy == null }
            .minByOrNull { kotlin.math.abs(it.transactionDate - date) }
            ?: return Outcome.New

        dao.markConfirmed(partner.id, source, ref)
        return Outcome.Merged(partner.id)
    }

    /** The ref stored for an SMS: its sender and text, which together identify it. */
    fun smsRef(sender: String, body: String): String = "$sender\n$body"

    /**
     * Masked tails differ in length between channels ("8607" in the SMS, "607" in some
     * emails), so one ending with the other counts. A missing side is compatible; the
     * narrower time window then does the work.
     */
    private fun accountsCompatible(a: String?, b: String?): Boolean {
        if (a == null || b == null) return true
        return a.endsWith(b) || b.endsWith(a)
    }

    /**
     * Email ids are exact. An SMS copy from the receiver can be a truncated prefix of the
     * provider's copy of a multipart message, so SMS text is compared by prefix.
     */
    private fun sameMessage(source: String, stored: String?, incoming: String): Boolean {
        if (stored == null) return false
        if (source == TransactionSource.MAIL) return stored == incoming
        val shortest = minOf(stored.length, incoming.length)
        return shortest > 0 && stored.regionMatches(0, incoming, 0, shortest)
    }
}
