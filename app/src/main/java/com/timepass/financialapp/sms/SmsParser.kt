package com.timepass.financialapp.sms

/**
 * Parses Indian bank SMS messages to extract transaction details.
 *
 * Handles the wide variety of SMS formats used by Indian banks for transaction alerts,
 * including UPI, credit card, debit card, NEFT, RTGS, and IMPS notifications.
 */
object SmsParser {

    /**
     * Result of parsing a transaction SMS.
     *
     * @param amount The transaction amount in INR
     * @param transactionType Either "DEBIT" or "CREDIT"
     * @param paymentMode One of: UPI, CREDIT_CARD, DEBIT_CARD, NEFT, RTGS, IMPS, UNKNOWN
     * @param merchantName The merchant or recipient name, if extractable
     */
    data class ParseResult(
        val amount: Double,
        val transactionType: String,
        val paymentMode: String,
        val merchantName: String?,
        /** Last four digits of the account or card, e.g. "8607"; null when absent. */
        val accountRef: String? = null,
        /** "ACCOUNT" or "CARD", describing what [accountRef] identifies. */
        val accountKind: String? = null
    )

    // --- Amount extraction patterns ---
    // Handles formats like: Rs.1,234.56, Rs 1234.56, INR 1,234.56, INR1234, ₹1,234.56
    //
    // The digit group must accept BOTH grouped ("5,432.10") and plain ("2500") numbers.
    // Anchoring the leading run at {1,3} and requiring a comma silently truncates plain
    // amounts -- "Rs 2500" would parse as 250 -- so the leading run is unbounded here.
    private val NUMBER = """[0-9]+(?:,[0-9]+)*(?:\.[0-9]{1,2})?"""

    private val AMOUNT_PATTERNS = listOf(
        Regex("""(?:Rs\.?|INR|₹)\s*($NUMBER)""", RegexOption.IGNORE_CASE),
        Regex("""($NUMBER)\s*(?:is\s+|has\s+been\s+)?(?:debited|credited|spent|withdrawn)""", RegexOption.IGNORE_CASE),
        Regex("""(?:amount|amt)\s*(?:of)?\s*(?:Rs\.?|INR|₹)?\s*($NUMBER)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Direction decided by structure rather than by counting words.
     *
     * A bank SMS is generated from a template, so the reliable signal is which verb is
     * attached to *your* account or card -- not how many direction words the message
     * happens to contain. Counting them gets 5% of real messages wrong, and always in the
     * same place: a transfer or a card bill names both directions in one sentence
     * ("...8607 debited with Rs 2000 and account ...7228 will be credited"), and the
     * tie-break then picks one at random as far as the user is concerned.
     *
     * Ordered most specific first; the first match wins.
     */
    private val DIRECTION_TEMPLATES: List<Pair<Regex, String>> = listOf(
        // Paying a credit card bill. The issuer says "payment received", which reads as
        // income but is the other half of a debit from the bank account. Filed as money
        // out so the two never disagree, and so a bill payment cannot inflate income.
        Regex("""payment\s+of\s+(?:Rs\.?|INR)[\s\S]{0,40}?\btowards\s+your\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""payment[\s\S]{0,40}?received[\s\S]{0,40}?towards\s+your\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""received\s+payment[\s\S]{0,80}?credited\s+to\s+your\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""thank\s+you\s+for\s+(?:your\s+)?payment""", RegexOption.IGNORE_CASE) to "DEBIT",
        // Topping up your own tag or wallet: the tag is credited, but the money is yours
        // going out. Placed above the generic credit rules so it is not read as income.
        Regex("""\b(?:FASTag|wallet)\b[\s\S]{0,60}?\bcredited\b""", RegexOption.IGNORE_CASE) to "DEBIT",

        // Money out of an account or card.
        Regex("""\bdebited\s+(?:by|with|for|from)\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""\bDebited;\s*INR""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""\bhas\s+been\s+debited\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""\bspent\s+on\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""^\s*Spent\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""\bwithdrawn\b""", RegexOption.IGNORE_CASE) to "DEBIT",
        Regex("""\bloaded\s+on\b""", RegexOption.IGNORE_CASE) to "DEBIT",

        // Money in.
        Regex("""\bis\s+credited\s+(?:for|with|by)\b""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""\bhas\s+been\s+credited\b""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""\bcredited\s+to\s+(?:your\s+)?a/?c\b""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""\bCredited;\s*INR""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""-\s*credited\s+by\b""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""\bcredited\s+(?:by|with)\s+(?:Rs|INR)""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""amount\s+of\s+(?:Rs\.?|INR)[0-9,. ]+\s+received\b""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""\brefund(?:ed)?\s+of\b""", RegexOption.IGNORE_CASE) to "CREDIT",
        Regex("""\bdeposited\s+(?:to|in|into)\b""", RegexOption.IGNORE_CASE) to "CREDIT"
    )

    /**
     * Advertising. It quotes amounts and direction words but moves no money, so a "flat
     * Rs.500 cashback" offer was becoming a 500 rupee credit.
     */
    private val PROMO_PATTERNS = listOf(
        Regex("""https?://""", RegexOption.IGNORE_CASE),
        Regex("""\bupgrade\s+now\b""", RegexOption.IGNORE_CASE),
        Regex("""\bapply\s+now\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfee\s+waived\b""", RegexOption.IGNORE_CASE),
        Regex("""\bpre-?approved\b""", RegexOption.IGNORE_CASE),
        Regex("""\bT&C\b""", RegexOption.IGNORE_CASE)
    )

    /**
     * The account or card the message is about, reduced to its last four digits.
     *
     * Banks only ever print a masked tail, so this stores no more than the SMS already
     * shows. Ordered so an explicit "card" reading wins over the generic account one.
     */
    private val ACCOUNT_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""card\s*(?:no\.?|number)?\s*(?:ending(?:\s+in)?)?\s*[:#]?\s*[*xX]{0,12}(\d{4})\b""", RegexOption.IGNORE_CASE) to "CARD",
        Regex("""(?:a/?c|account)\s*(?:no\.?|number)?\s*[:#]?\s*[*xX]{1,12}(\d{3,4})\b""", RegexOption.IGNORE_CASE) to "ACCOUNT",
        Regex("""\bA/?C\s*\*+(\d{3,4})\b""", RegexOption.IGNORE_CASE) to "ACCOUNT",
        Regex("""\*{1,2}(\d{4})\b""") to "ACCOUNT"
    )

    // --- Transaction type keyword scoring ---
    //
    // The bare stems "debit"/"credit" are deliberately absent: "credit" occurs inside
    // "Credit Card", which would score every card purchase as income. Matching is done
    // on word boundaries for the same reason.
    private val DEBIT_KEYWORDS = listOf(
        "debited", "withdrawn", "withdrawal", "paid", "sent",
        "purchase", "spent", "transferred", "deducted", "payment"
    )
    private val CREDIT_KEYWORDS = listOf(
        "credited", "received", "deposited", "deposit",
        "refund", "reversed", "cashback", "added"
    )

    private val DEBIT_REGEXES = DEBIT_KEYWORDS.map { Regex("""\b${Regex.escape(it)}\b""") }
    private val CREDIT_REGEXES = CREDIT_KEYWORDS.map { Regex("""\b${Regex.escape(it)}\b""") }

    // --- Payment mode keywords ---
    private val UPI_KEYWORDS = listOf("upi", "google pay", "gpay", "phonepe", "paytm", "bhim", "upi ref")
    private val CREDIT_CARD_KEYWORDS = listOf("credit card", "cc ", "creditcard", "cc ending", "credit card ending")

    /**
     * A UPI virtual payment address, e.g. "Q091999206@ybl" or "name.123@okicici".
     *
     * The handle must NOT be followed by a dot, which is what separates a VPA from an
     * email address: "sunderesun.aks@gmail.com" appears in statement alerts and would
     * otherwise be read as a UPI payment.
     */
    private val VPA = Regex("""[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?@[A-Za-z]{2,}(?!\.[A-Za-z]{2,})""")

    /** RRN is the retrieval reference number quoted on UPI and IMPS transfers. */
    private val RRN = Regex("""\bRRN[:\s]""", RegexOption.IGNORE_CASE)

    /**
     * Card alerts quote the remaining limit ("Avl Lmt") rather than a balance ("Avl Bal"),
     * which is what separates a credit card purchase from an account debit. Many issuers
     * never write the words "credit card" in the purchase alert itself.
     */
    private val CREDIT_CARD_HINTS = listOf(
        Regex("""av(?:l|ailable)\.?\s*(?:lmt|limit)""", RegexOption.IGNORE_CASE),
        Regex("""spent\s+on\s+.{0,30}?\bcard\b""", RegexOption.IGNORE_CASE)
    )
    /**
     * Cash and card-present withdrawals.
     *
     * Checked before the UPI rail, because an ATM or POS alert also carries an RRN and
     * often a VPA ("hdfcatm@hdfcbank") and would otherwise be filed as a UPI payment.
     *
     * Word boundaries matter here: plain substring matching made every message containing
     * "deposited" a debit-card transaction, since "deposit" contains "pos".
     */
    private val DEBIT_CARD_HINTS = listOf(
        Regex("""\bdebit\s*card\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdebitcard\b""", RegexOption.IGNORE_CASE),
        Regex("""\bdc\s+ending\b""", RegexOption.IGNORE_CASE),
        Regex("""\batm\b""", RegexOption.IGNORE_CASE),
        Regex("""atm@""", RegexOption.IGNORE_CASE),
        Regex("""\bcash\s*wdl\b""", RegexOption.IGNORE_CASE),
        Regex("""(?:\bpos\b|pos\.)""", RegexOption.IGNORE_CASE)
    )
    private val NEFT_KEYWORDS = listOf("neft")
    private val RTGS_KEYWORDS = listOf("rtgs")
    private val IMPS_KEYWORDS = listOf("imps")

    // --- Merchant extraction patterns ---
    // Matches patterns like "to MERCHANT", "at MERCHANT", "to VPA merchant@upi"
    private val MERCHANT_PATTERNS = listOf(
        // "towards kpnfarmfresh@ybl." -- the payee half of a VPA is the merchant name.
        Regex("""(?:towards|to|VPA)\s+([A-Za-z0-9][A-Za-z0-9._-]*)@[A-Za-z]{2,}(?!\.[A-Za-z]{2,})""", RegexOption.IGNORE_CASE),
        // "spent on <bank> Card XX0908 on <date> at SWIGGY FOOD." / "@AMAZON PAY INDIA"
        Regex("""\bat\s+([A-Za-z0-9][A-Za-z0-9 .&'*_-]{2,40}?)\.\s""", RegexOption.IGNORE_CASE),
        Regex("""(?:to|at|towards|for)\s+([A-Za-z0-9][A-Za-z0-9 .&'_-]{1,40}?)(?:\s+(?:on|via|ref|thru|through|using|UPI|w\.e\.f|vpa|a/c|ac|account|Ref|ref\.?\s*no|$))""", RegexOption.IGNORE_CASE),
        Regex("""(?:to|at)\s+([A-Za-z][A-Za-z0-9 .&'_-]{1,40}?)(?:\.\s|[.]\s*$)""", RegexOption.IGNORE_CASE),
        Regex("""VPA\s+([a-zA-Z0-9._-]+@[a-zA-Z]+)""", RegexOption.IGNORE_CASE),
        Regex("""(?:to|at)\s+([A-Za-z][A-Za-z0-9 .&'_-]{2,30})$""", RegexOption.IGNORE_CASE)
    )

    /**
     * Messages that quote a rupee amount but describe no money actually moving.
     *
     * Without these, a statement notice, a bill reminder or a collect request is read as
     * spending and inflates every total -- a "Total due INR 513.00" statement alert became
     * a 513 rupee expense.
     */
    private val NON_TRANSACTION_PATTERNS = listOf(
        Regex("""(?:OTP|one.?time.?password|verification.?code)""", RegexOption.IGNORE_CASE),
        Regex("""(?:Dear Customer,?\s*Your\s+\w+\s+application)""", RegexOption.IGNORE_CASE),
        // A collect request is an invitation to pay, not a payment.
        Regex("""has\s+requested\s+money""", RegexOption.IGNORE_CASE),
        Regex("""(?:on\s+approving|approve)\s+the\s+request""", RegexOption.IGNORE_CASE),
        // Describes a position, not a movement.
        Regex("""(?:credit|available|avl)\s+balance\s+of""", RegexOption.IGNORE_CASE),
        // Scheduled, not yet taken.
        Regex("""will\s+be\s+(?:debited|deducted|auto.?debited)""", RegexOption.IGNORE_CASE)
    )

    /**
     * Statement and bill-reminder wording, which quotes amounts that never moved.
     *
     * Unlike [NON_TRANSACTION_PATTERNS] these only reject a message that reports no actual
     * event, because banks append a due summary to real alerts: a refund reading "An amount
     * of INR 342.02 received on your Card ... Current Total Amount Due is INR 3025.48" is a
     * genuine credit that a blanket "amount due" rule would throw away.
     */
    private val NOTICE_PATTERNS = listOf(
        Regex("""min(?:imum)?\.?\s*(?:amt\.?|amount)?\s*due""", RegexOption.IGNORE_CASE),
        Regex("""total\s+(?:amount\s+)?due""", RegexOption.IGNORE_CASE),
        Regex("""\bis\s+(?:due|overdue)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bEMI\s*due\b""", RegexOption.IGNORE_CASE),
        Regex("""e[-\s]?statement""", RegexOption.IGNORE_CASE),
        Regex("""statement\s+(?:sent|is\s+here|generated|for\s+your)""", RegexOption.IGNORE_CASE)
    )

    /** Wording that reports money having actually moved, which overrides [NOTICE_PATTERNS]. */
    private val COMPLETED_MOVEMENT = listOf(
        Regex("""\b(?:debited|credited|withdrawn)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bspent\s+on\b""", RegexOption.IGNORE_CASE),
        Regex("""amount\s+of\s+(?:Rs\.?|INR|₹)?\s*[0-9][0-9,.]*\s+received""", RegexOption.IGNORE_CASE)
    )

    /**
     * Parses an SMS body to extract transaction details.
     *
     * @param smsBody The full text of the SMS message
     * @return A [ParseResult] if the SMS is a valid transaction message, null otherwise
     */
    fun parse(smsBody: String): ParseResult? {
        // Skip non-transaction messages (OTP, promotional, etc.)
        if (NON_TRANSACTION_PATTERNS.any { it.containsMatchIn(smsBody) }) {
            return null
        }

        // Statement and reminder wording only disqualifies a message that reports no event.
        if (NOTICE_PATTERNS.any { it.containsMatchIn(smsBody) } &&
            COMPLETED_MOVEMENT.none { it.containsMatchIn(smsBody) }
        ) {
            return null
        }

        // Marketing that happens to quote a rupee figure. Only rejected when nothing in
        // the message reports an actual movement, since real alerts sometimes carry a link.
        if (PROMO_PATTERNS.any { it.containsMatchIn(smsBody) } &&
            COMPLETED_MOVEMENT.none { it.containsMatchIn(smsBody) }
        ) {
            return null
        }

        val amount = extractAmount(smsBody) ?: return null
        val transactionType = detectTransactionType(smsBody) ?: return null
        val paymentMode = detectPaymentMode(smsBody)
        val merchantName = extractMerchant(smsBody)
        val (kind, ref) = extractAccount(smsBody)

        return ParseResult(
            amount = amount,
            transactionType = transactionType,
            paymentMode = paymentMode,
            merchantName = merchantName?.trim(),
            accountRef = ref,
            accountKind = kind
        )
    }

    /**
     * Extracts the monetary amount from the SMS body.
     * Tries multiple regex patterns and returns the first successful match.
     */
    private fun extractAmount(body: String): Double? {
        for (pattern in AMOUNT_PATTERNS) {
            val match = pattern.find(body)
            if (match != null) {
                val rawAmount = match.groupValues[1].replace(",", "")
                val amount = rawAmount.toDoubleOrNull()
                if (amount != null && amount > 0) {
                    return amount
                }
            }
        }
        return null
    }

    /**
     * Determines transaction type using keyword scoring.
     * Each keyword found contributes +1 to its category score.
     * The category with the higher score wins.
     */
    private fun detectTransactionType(body: String): String? {
        // 1. Structure, where the message follows a recognisable bank template.
        DIRECTION_TEMPLATES.firstOrNull { (regex, _) -> regex.containsMatchIn(body) }
            ?.let { return it.second }

        // 2. Otherwise the direction word nearest the amount, which beats counting them:
        //    the verb next to the figure is the one acting on it.
        nearestVerbDirection(body)?.let { return it }

        // 3. Last resort, the old whole-message tally.
        val lowerBody = body.lowercase()
        val debitScore = DEBIT_REGEXES.count { it.containsMatchIn(lowerBody) }
        val creditScore = CREDIT_REGEXES.count { it.containsMatchIn(lowerBody) }

        return when {
            debitScore > creditScore -> "DEBIT"
            creditScore > debitScore -> "CREDIT"
            debitScore > 0 -> "DEBIT" // tie-break: prefer DEBIT (more common in alerts)
            else -> null // no transaction keywords found
        }
    }

    /** The direction word closest to the first amount in the message. */
    private fun nearestVerbDirection(body: String): String? {
        val amountAt = AMOUNT_PATTERNS.firstNotNullOfOrNull { it.find(body) }?.range?.first
            ?: return null
        val lower = body.lowercase()

        var best: Pair<Int, String>? = null
        for (regex in DEBIT_REGEXES) {
            regex.find(lower)?.let { m ->
                val distance = kotlin.math.abs(m.range.first - amountAt)
                if (best == null || distance < best!!.first) best = distance to "DEBIT"
            }
        }
        for (regex in CREDIT_REGEXES) {
            regex.find(lower)?.let { m ->
                val distance = kotlin.math.abs(m.range.first - amountAt)
                if (best == null || distance < best!!.first) best = distance to "CREDIT"
            }
        }
        return best?.second
    }

    /** @return kind ("ACCOUNT"/"CARD") to last-four digits, or nulls when none is named. */
    private fun extractAccount(body: String): Pair<String?, String?> {
        for ((regex, kind) in ACCOUNT_PATTERNS) {
            val digits = regex.find(body)?.groupValues?.getOrNull(1)
            if (!digits.isNullOrBlank()) return kind to digits
        }
        return null to null
    }

    /**
     * Identifies the payment mode based on keywords in the SMS body.
     */
    private fun detectPaymentMode(body: String): String {
        val lowerBody = body.lowercase()

        // Order is deliberate, strongest signal first. Cash and card-present alerts come
        // before the UPI rail because they carry the same RRN and VPA markers; the bare
        // RRN check is last because NEFT, IMPS and ATM alerts quote one too.
        return when {
            DEBIT_CARD_HINTS.any { it.containsMatchIn(body) } -> "DEBIT_CARD"
            CREDIT_CARD_HINTS.any { it.containsMatchIn(body) } -> "CREDIT_CARD"
            CREDIT_CARD_KEYWORDS.any { lowerBody.contains(it) } -> "CREDIT_CARD"
            UPI_KEYWORDS.any { lowerBody.contains(it) } -> "UPI"
            VPA.containsMatchIn(body) -> "UPI"
            NEFT_KEYWORDS.any { lowerBody.contains(it) } -> "NEFT"
            RTGS_KEYWORDS.any { lowerBody.contains(it) } -> "RTGS"
            IMPS_KEYWORDS.any { lowerBody.contains(it) } -> "IMPS"
            RRN.containsMatchIn(body) -> "UPI"
            else -> "UNKNOWN"
        }
    }

    /**
     * Attempts to extract the merchant or recipient name from the SMS body.
     * Tries multiple patterns and returns the first clean match.
     */
    private fun extractMerchant(body: String): String? {
        for (pattern in MERCHANT_PATTERNS) {
            val match = pattern.find(body)
            if (match != null) {
                val merchant = match.groupValues[1].trim()
                // Filter out common false positives
                val falsePositives = setOf(
                    "a/c", "ac", "account", "bank", "your", "the", "rs", "inr", "card"
                )
                if (merchant.length >= 2 && merchant.lowercase() !in falsePositives) {
                    return merchant
                }
            }
        }
        return null
    }
}
