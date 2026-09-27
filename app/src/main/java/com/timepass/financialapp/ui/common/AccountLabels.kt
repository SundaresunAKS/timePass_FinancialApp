package com.timepass.financialapp.ui.common

import com.timepass.financialapp.data.local.dao.AccountLabelSource

/**
 * Turns a stored account reference into something a person recognises.
 *
 * The database keeps only the masked tail ("8607"), which is all the SMS discloses and is
 * not enough to tell one card from another in a list. The bank comes from the DLT sender
 * code on the messages themselves -- `AX-INDUSB-S` is IndusInd -- so no extra column and
 * nothing more sensitive is stored to get "IndusInd A/c 8607".
 *
 * The tail stays on the label because banks routinely issue more than one: two Axis cards
 * and two SBI cards in the same inbox would otherwise be indistinguishable.
 */
object AccountLabels {

    /** DLT sender codes to the name the bank is actually known by. */
    private val BANK_NAMES = mapOf(
        "SBIINB" to "SBI", "SBICRD" to "SBI", "SBIUPI" to "SBI", "SBIPSG" to "SBI",
        "CENTBK" to "Central Bank", "PNBSMS" to "PNB", "BOIIND" to "BOI",
        "CANBNK" to "Canara", "IOBSMS" to "IOB", "UCOBNK" to "UCO",
        "BOBIPL" to "Bank of Baroda", "MAHIBK" to "Bank of Maharashtra",
        "INDBNK" to "Indian Bank", "UNIONB" to "Union Bank",
        "HDFCBK" to "HDFC", "HDFCBN" to "HDFC", "ICICIB" to "ICICI", "ICICIT" to "ICICI",
        "AXISBK" to "Axis", "KOTKBK" to "Kotak", "KOTAKB" to "Kotak",
        "INDUSB" to "IndusInd", "YESBNK" to "YES Bank",
        "IDFCBK" to "IDFC FIRST", "IDFCFB" to "IDFC FIRST",
        "FEDERL" to "Federal", "RBLBNK" to "RBL", "AUBANK" to "AU",
        "DBSBNK" to "DBS", "BANDAN" to "Bandhan", "SCBANK" to "Standard Chartered",
        "HSBCBK" to "HSBC", "CITIBK" to "Citi", "AMEXIN" to "Amex",
        "AIRBNK" to "Airtel", "JIOBNK" to "Jio", "PAYTMB" to "Paytm", "PAYTM" to "Paytm",
        "FINOBK" to "Fino", "CREDCL" to "CRED", "ONECAR" to "OneCard",
        "SLICEI" to "Slice", "JUPITR" to "Jupiter"
    )

    private val HEADER = Regex("""^(?:[A-Za-z]{2}-)?([A-Za-z0-9]{3,11})(?:-[A-Za-z])?$""")

    /**
     * Bank name for a DLT header or a bank email address, or null when it is not one we
     * name. Email senders are recognised by domain; see MailSenderFilter.
     */
    fun bankOf(sender: String?): String? {
        if (sender != null && '@' in sender) {
            return com.timepass.financialapp.mail.MailSenderFilter.bankOf(sender)
        }
        val code = HEADER.find(sender?.trim().orEmpty())?.groupValues?.get(1) ?: return null
        return BANK_NAMES[code.uppercase()]
    }

    /**
     * Builds reference to label, e.g. `"8607"` to `"IndusInd A/c 8607"`.
     *
     * Where a reference has messages from several senders the most frequent one wins, so
     * a single stray alert from another bank cannot rename the account.
     */
    fun build(sources: List<AccountLabelSource>): Map<String, String> =
        sources
            .groupBy { it.accountRef }
            .mapValues { (ref, rows) ->
                val bank = rows
                    .sortedByDescending { it.count }
                    .firstNotNullOfOrNull { bankOf(it.sender) }
                val kind = rows.maxByOrNull { it.count }?.accountKind
                label(bank, kind, ref)
            }

    /** Falls back gracefully: an unknown bank still yields a usable "Card 1234". */
    fun label(bank: String?, kind: String?, ref: String): String {
        val noun = if (kind == "CARD") "Card" else "A/c"
        return if (bank.isNullOrBlank()) "$noun $ref" else "$bank $noun $ref"
    }
}
