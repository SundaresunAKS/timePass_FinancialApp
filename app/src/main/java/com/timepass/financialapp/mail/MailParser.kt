package com.timepass.financialapp.mail

import com.timepass.financialapp.sms.SmsParser

/**
 * Reads a transaction out of a bank alert email.
 *
 * An alert email says the same thing as the SMS -- usually in the very same sentence --
 * wrapped in a greeting, a table layout and a page of footer. The footer is the problem:
 * it routinely says "never share your OTP", quotes offers with rupee amounts and links,
 * all of which [SmsParser] rightly treats as "not a transaction". So the parsing is done
 * on the one or two sentences that report the movement, and the SMS rules apply to those
 * unchanged.
 *
 * Pure Kotlin with no Android dependencies, so it can be unit tested on the JVM.
 */
object MailParser {

    /** Keeps a stored email a sensible size; the alert itself is always near the top. */
    private const val MAX_STORED_CHARS = 6_000

    /** How much text around the alert sentence is handed to the SMS parser. */
    private const val MAX_SNIPPET_CHARS = 500

    private val AMOUNT = Regex("""(?:Rs\.?|INR|₹)\s*[0-9]""", RegexOption.IGNORE_CASE)

    private val MOVEMENT = Regex(
        """\b(?:debited|credited|spent|withdrawn|received|paid|sent|transferred|refunded|reversed|deducted|deposited|done|used)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Boilerplate that must not reach the SMS parser, which would reject the whole mail. */
    private val BOILERPLATE = Regex(
        """\b(?:OTP|one.?time.?password|never\s+share|do\s+not\s+share|phishing|unsubscribe|disclaimer|call\s+us|customer\s+care|helpline)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Emails that are never a single transaction, decided on the subject alone. */
    private val NON_TRANSACTION_SUBJECT = Regex(
        """\b(?:statement|OTP|one.?time.?password|newsletter|offer|offers|webinar|password|login|KYC|reward\s+points|pre-?approved|apply\s+now|update\s+your|exclusive|perks|benefits|enjoy|congratulations|upgrade|invite|invitation|introducing|unlock)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Neighbouring sentences the SMS rules would read as "not a transaction" -- a balance
     * statement or a scheduled debit -- when they only accompany the real alert.
     */
    private val MISLEADING_NEIGHBOUR = Regex(
        """(?:credit|available|avl)\s+balance\s+of|will\s+be\s+(?:debited|deducted|auto.?debited)""",
        RegexOption.IGNORE_CASE
    )

    private val ACCOUNT_WORDS = Regex("""\b(?:a/?c|account|card)\b""", RegexOption.IGNORE_CASE)

    /**
     * Sentences end at a full stop followed by a capital -- which leaves "Rs. 500" and
     * "A/c no. XX1234 is" intact -- or at a line break.
     */
    private val SENTENCE_BREAK = Regex("""\n+|(?<=[.!?])\s+(?=[A-Z])""")

    /**
     * Parses a freshly fetched email.
     *
     * @return the transaction, or null when the email reports no money moving
     */
    fun parse(subject: String, body: String): SmsParser.ParseResult? {
        if (NON_TRANSACTION_SUBJECT.containsMatchIn(subject)) return null

        val snippet = transactionSnippet(body) ?: return null
        val parsed = SmsParser.parse(snippet) ?: return null

        // The subject often names the rail or the card when the sentence does not:
        // "UPI txn alert", "Transaction alert for your ICICI Bank Credit Card".
        val context = "$subject. $snippet"
        val mode = parsed.paymentMode.takeUnless { it == "UNKNOWN" }
            ?: SmsParser.detectPaymentMode(context)
        val (kind, ref) = if (parsed.accountRef != null) {
            parsed.accountKind to parsed.accountRef
        } else {
            SmsParser.extractAccount(context)
        }
        return parsed.copy(paymentMode = mode, accountRef = ref, accountKind = kind)
    }

    /** Whether [text] quotes a rupee amount at all. */
    fun mentionsAmount(text: String): Boolean = AMOUNT.containsMatchIn(text)

    /** Re-parses a row written by [toDescription], for the stored-transaction repair. */
    fun parseStored(description: String): SmsParser.ParseResult? {
        val subject = description.substringBefore('\n')
        val body = description.substringAfter('\n', "")
        return parse(subject, body)
    }

    /**
     * The text kept on the transaction: subject on the first line, then the body.
     *
     * Subject first so the two-line preview in the lists reads as a headline, and so
     * [parseStored] can split it off again.
     */
    fun toDescription(subject: String, body: String): String =
        "${subject.trim()}\n${normalize(body)}".take(MAX_STORED_CHARS)

    /**
     * Cleans a text/plain body. Some banks generate theirs by stripping tags from the
     * HTML, which leaves the stylesheet behind as text ("body { margin: 0; }").
     */
    fun plainToText(plain: String): String =
        normalize(plain.replace(Regex("""[^{}\n]*\{[^{}]*\}"""), " "))

    /**
     * The sentence reporting the movement, with its neighbours for context.
     *
     * The sentence before is kept when it names the account ("Your A/c XX1234 --"); the
     * one after usually carries the balance or limit, which is how card alerts are told
     * from account debits. Boilerplate sentences are dropped wherever they fall.
     */
    internal fun transactionSnippet(body: String): String? {
        val sentences = normalize(body)
            .split(SENTENCE_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !BOILERPLATE.containsMatchIn(it) }

        val hit = sentences.indexOfFirst { AMOUNT.containsMatchIn(it) && MOVEMENT.containsMatchIn(it) }
        if (hit < 0) return null

        val parts = mutableListOf<String>()
        sentences.getOrNull(hit - 1)
            ?.takeIf { ACCOUNT_WORDS.containsMatchIn(it) && !AMOUNT.containsMatchIn(it) }
            ?.let { parts += it }
        parts += sentences[hit]
        sentences.getOrNull(hit + 1)
            ?.takeUnless { MISLEADING_NEIGHBOUR.containsMatchIn(it) }
            ?.let { parts += it }

        return parts.joinToString(" ").take(MAX_SNIPPET_CHARS)
    }

    /**
     * Plain text from an HTML email body.
     *
     * Hand-rolled rather than android.text.Html so the parser stays testable off-device.
     * Block ends become line breaks and table cells spaces, which keeps a table layout's
     * "Amount | Rs 500" readable as one sentence.
     */
    fun htmlToText(html: String): String {
        var text = html
            .replace(Regex("""(?is)<(head|style|script)\b.*?</\1>"""), " ")
            .replace(Regex("""(?is)<!--.*?-->"""), " ")
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|tr|li|h[1-6]|table)>"""), "\n")
            .replace(Regex("""(?i)</t[dh]>"""), " ")
            .replace(Regex("""<[^>]+>"""), " ")

        text = text
            .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: " "
            }
            .replace(Regex("""&#([0-9]+);""")) { m ->
                m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: " "
            }
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&rupee;", "₹")

        return normalize(text)
    }

    /** One space between words, at most one blank line between paragraphs. */
    private fun normalize(text: String): String =
        text.replace(' ', ' ')
            .replace("﻿", "")
            .replace("​", "")
            .replace("\r", "")
            .replace(Regex("""[ \t\x0B\f]+"""), " ")
            .replace(Regex(""" *\n *"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
}
