package com.timepass.financialapp.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailParserTest {

    /** Shape of an HDFC UPI alert: the transaction sentence buried in greeting and footer. */
    private val hdfcUpiHtml = """
        <html><head><style>p { color: #333; }</style></head><body>
        <table><tr><td>
        <p>Dear Customer,</p>
        <p>Rs.250.00 has been debited from account **8607 to VPA swiggy@icici SWIGGY on 05-04-26.</p>
        <p>Your UPI transaction reference number is 509512345678.</p>
        <p>If you did not authorize this transaction, please report it immediately.</p>
        <p>Warm Regards,<br>HDFC Bank</p>
        <p>Never share your OTP, PIN or password with anyone. Get flat Rs.500 cashback - apply now at https://hdfc.bank.in/offer</p>
        </td></tr></table></body></html>
    """.trimIndent()

    @Test
    fun `reads an HDFC UPI debit despite the OTP footer`() {
        val body = MailParser.htmlToText(hdfcUpiHtml)
        val result = MailParser.parse("You have done a UPI txn. Check details!", body)

        assertNotNull(result)
        assertEquals(250.0, result!!.amount, 0.001)
        assertEquals("DEBIT", result.transactionType)
        assertEquals("UPI", result.paymentMode)
        assertEquals("8607", result.accountRef)
    }

    @Test
    fun `reads an ICICI credit card purchase`() {
        val body = "Dear Customer,\n\nYour ICICI Bank Credit Card XX1234 has been used for a " +
            "transaction of INR 1,499.00 on Apr 06, 2026 at 10:15:22. Info: AMAZON PAY IN. " +
            "The Available Credit Limit on your card is INR 48,501.00 and Total Credit Limit " +
            "is INR 50,000.00.\n\nNever share your OTP with anyone."
        val result = MailParser.parse("Transaction alert for your ICICI Bank Credit Card", body)

        assertNotNull(result)
        assertEquals(1499.0, result!!.amount, 0.001)
        assertEquals("DEBIT", result.transactionType)
        assertEquals("CREDIT_CARD", result.paymentMode)
        assertEquals("1234", result.accountRef)
    }

    @Test
    fun `reads a credit to the account`() {
        val body = "Dear Customer, Rs. 12,000.00 has been credited to your A/c XX4321 on " +
            "01-05-26 by NEFT from ACME PAYROLL. Available balance: Rs 40,512.10."
        val result = MailParser.parse("Credit alert", body)

        assertNotNull(result)
        assertEquals(12000.0, result!!.amount, 0.001)
        assertEquals("CREDIT", result.transactionType)
        assertEquals("4321", result.accountRef)
    }

    @Test
    fun `ignores a statement email`() {
        val body = "Your statement for the period is ready. Total amount due Rs 5,130.00. " +
            "Minimum amount due Rs 257.00."
        assertNull(MailParser.parse("Your HDFC Bank Credit Card Statement - April 2026", body))
    }

    @Test
    fun `ignores a promotional email`() {
        val body = "Get a pre-approved loan of Rs 5,00,000 today. Apply now: https://example.com"
        assertNull(MailParser.parse("An exclusive offer for you", body))
    }

    @Test
    fun `ignores an email that only quotes a balance`() {
        val body = "Dear Customer, the available balance of your account XX8607 is Rs 10,000.00 as of today."
        assertNull(MailParser.parse("Balance update", body))
    }

    @Test
    fun `stored description round-trips through the re-parser`() {
        val body = MailParser.htmlToText(hdfcUpiHtml)
        val stored = MailParser.toDescription("You have done a UPI txn", body)

        val result = MailParser.parseStored(stored)
        assertNotNull(result)
        assertEquals(250.0, result!!.amount, 0.001)
    }

    @Test
    fun `html conversion drops styles and decodes entities`() {
        val text = MailParser.htmlToText(
            "<style>.x{}</style><table><tr><td>Amount</td><td>&#8377;&nbsp;500</td></tr></table>"
        )
        assertTrue(text, text.contains("Amount ₹ 500"))
        assertTrue(text, !text.contains(".x"))
    }

    @Test
    fun `recognises bank senders by domain`() {
        assertEquals("alerts@hdfcbank.net", MailSenderFilter.addressOf("HDFC Bank <Alerts@HDFCBank.net>"))
        assertTrue(MailSenderFilter.isBankMail("alerts@hdfcbank.net"))
        assertTrue(MailSenderFilter.isBankMail("noreply@mail.icicibank.com"))
        assertTrue(MailSenderFilter.isBankMail("alerts@axis.bank.in"))
        assertEquals("HDFC", MailSenderFilter.bankOf("alerts@hdfcbank.net"))
        assertEquals("AXIS", MailSenderFilter.bankOf("alerts@axis.bank.in"))
        assertTrue(!MailSenderFilter.isBankMail("friend@gmail.com"))
    }

    @Test
    fun `reads an IndusInd account with leading digits before the mask`() {
        val body = "Dear Customer, Thank you for banking with us. Your IndusInd Bank Account No. " +
            "15XXXXXX8607 has been Debited for INR 18,500.00 towards UPI/661110350743/DR/A P/SBIN/arumugam3@oksbi . " +
            "The balance available in your Account is INR 67,767.46."
        val result = MailParser.parse("IndusInd Bank Transaction Alert", body)

        assertNotNull(result)
        assertEquals(18500.0, result!!.amount, 0.001)
        assertEquals("DEBIT", result.transactionType)
        assertEquals("8607", result.accountRef)
    }

    @Test
    fun `ignores a promotional email quoting rupee amounts`() {
        val body = "Annual Benefits worth ₹25,000. Spend ₹200 and get cashback credited to your account."
        assertNull(MailParser.parse("Enjoy a world of exclusive banking perks with Axis Bank", body))
    }

    @Test
    fun `plain bodies lose a leftover stylesheet`() {
        val text = MailParser.plainToText("﻿IndusInd Bank\r\nbody {\r\nmargin: 0;\r\n}\r\nDear Customer,")
        assertTrue(text, !text.contains("margin"))
        assertTrue(text, text.contains("Dear Customer,"))
        assertTrue(text, !text.contains("\r") && !text.contains("﻿"))
    }
}
