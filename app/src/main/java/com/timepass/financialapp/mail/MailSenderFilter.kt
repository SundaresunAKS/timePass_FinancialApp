package com.timepass.financialapp.mail

/**
 * Identifies bank emails by the sender's domain -- the email counterpart of SmsSenderFilter.
 *
 * Matched on the domain suffix, so `alerts@hdfcbank.net` and `noreply@mail.hdfcbank.net`
 * are both HDFC. Indian banks are moving to the RBI-mandated `.bank.in` domains, so any
 * address under `bank.in` counts as a bank as well.
 */
object MailSenderFilter {

    /** Domain suffix to the name the bank is known by, as AccountLabels shows it. */
    private val BANK_DOMAINS = linkedMapOf(
        // Public sector
        "sbi.co.in" to "SBI", "sbicard.com" to "SBI", "centralbank.co.in" to "Central Bank",
        "pnb.co.in" to "PNB", "bankofindia.co.in" to "BOI", "canarabank.com" to "Canara",
        "iob.in" to "IOB", "ucobank.co.in" to "UCO", "bankofbaroda.com" to "Bank of Baroda",
        "bankofbaroda.co.in" to "Bank of Baroda", "bankofmaharashtra.in" to "Bank of Maharashtra",
        "indianbank.in" to "Indian Bank", "unionbankofindia.co.in" to "Union Bank",
        // Private sector
        "hdfcbank.net" to "HDFC", "hdfcbank.com" to "HDFC", "icicibank.com" to "ICICI",
        "axisbank.com" to "Axis", "kotak.com" to "Kotak", "indusind.com" to "IndusInd",
        "yesbank.in" to "YES Bank", "idfcfirstbank.com" to "IDFC FIRST",
        "federalbank.co.in" to "Federal", "rblbank.com" to "RBL", "aubank.in" to "AU",
        "dbs.com" to "DBS", "bandhanbank.com" to "Bandhan", "sc.com" to "Standard Chartered",
        "hsbc.co.in" to "HSBC", "citibank.co.in" to "Citi", "americanexpress.com" to "Amex",
        "aexp.com" to "Amex",
        // Payments banks / cards
        "airtelbank.com" to "Airtel", "paytmbank.com" to "Paytm", "finobank.com" to "Fino",
        "getonecard.app" to "OneCard", "sliceit.com" to "Slice", "jupiter.money" to "Jupiter"
    )

    /** The new RBI domain; the label before it names the bank, e.g. `hdfc.bank.in`. */
    private const val BANK_IN = "bank.in"

    /** Domains to ask Gmail for; every other email is never downloaded. */
    val searchDomains: List<String> get() = BANK_DOMAINS.keys.toList() + BANK_IN

    /** `"HDFC Bank <alerts@hdfcbank.net>"` -> `"alerts@hdfcbank.net"`. */
    fun addressOf(fromHeader: String): String {
        val angled = Regex("""<([^>]+)>""").find(fromHeader)?.groupValues?.get(1)
        return (angled ?: fromHeader).trim().lowercase()
    }

    fun isBankMail(address: String): Boolean {
        val domain = domainOf(address) ?: return false
        return domain == BANK_IN || domain.endsWith(".$BANK_IN") || matchDomain(domain) != null
    }

    /** Bank name for a sender address, or null when the domain is not one we name. */
    fun bankOf(address: String?): String? {
        val domain = domainOf(address ?: return null) ?: return null
        matchDomain(domain)?.let { return BANK_DOMAINS[it] }
        if (domain.endsWith(".$BANK_IN")) {
            return domain.removeSuffix(".$BANK_IN").substringAfterLast('.').uppercase()
        }
        return null
    }

    private fun domainOf(address: String): String? =
        address.substringAfterLast('@', "").lowercase().takeIf { it.isNotEmpty() }

    private fun matchDomain(domain: String): String? =
        BANK_DOMAINS.keys.firstOrNull { domain == it || domain.endsWith(".$it") }
}
