package com.timepass.financialapp.sms

/**
 * Filters SMS sender IDs to identify messages from Indian banks and financial institutions.
 *
 * Indian transactional SMS headers come in several shapes:
 *   - `AD-HDFCBK`    legacy two-letter operator prefix + sender code
 *   - `AX-INDUSB-S`  post-DLT: same, plus a one-letter entity-type suffix
 *                    (S = service, T = transactional, P = promotional, G = government)
 *   - `INDUSB`       bare code, occasionally seen
 *
 * Both the operator prefix and the DLT suffix vary per telecom circle and are not worth
 * enumerating, so only the middle sender code is matched against [BANK_CODES].
 */
object SmsSenderFilter {

    // Known bank and financial institution sender codes.
    private val BANK_CODES = setOf(
        // Public sector
        "SBIINB", "SBICRD", "SBIUPI", "SBIPSG", "CENTBK", "PNBSMS", "BOIIND",
        "CANBNK", "IOBSMS", "UCOBNK", "BOBIPL", "MAHIBK", "INDBNK", "UNIONB",
        // Private sector
        "HDFCBK", "HDFCBN", "ICICIB", "ICICIT", "AXISBK", "KOTKBK", "KOTAKB",
        "INDUSB", "YESBNK", "IDFCBK", "IDFCFB", "FEDERL", "RBLBNK", "AUBANK",
        "DBSBNK", "BANDAN", "SCBANK", "HSBCBK", "CITIBK", "AMEXIN",
        // Payments banks / wallets / cards
        "AIRBNK", "JIOBNK", "PAYTMB", "PAYTM", "FINOBK", "CREDCL", "ONECAR",
        "SLICEI", "JUPITR"
    )

    /**
     * Captures the sender code from a header, tolerating an optional two-letter
     * operator prefix and an optional one-letter DLT entity suffix.
     *
     * `AX-INDUSB-S` -> `INDUSB`,  `AD-HDFCBK` -> `HDFCBK`,  `INDUSB` -> `INDUSB`
     */
    private val HEADER_PATTERN = Regex(
        """^(?:[A-Za-z]{2}-)?([A-Za-z0-9]{3,11})(?:-[A-Za-z])?$"""
    )

    /**
     * Determines whether the given SMS sender address belongs to a known Indian bank
     * or financial institution.
     *
     * @param sender The sender address from the SMS (e.g. "AX-INDUSB-S")
     * @return true if the sender's code matches a known bank, false otherwise
     */
    fun isBankSms(sender: String): Boolean {
        val code = HEADER_PATTERN.find(sender.trim())?.groupValues?.get(1) ?: return false
        return code.uppercase() in BANK_CODES
    }
}
