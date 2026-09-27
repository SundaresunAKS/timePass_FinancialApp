package com.timepass.financialapp.sms

import kotlinx.coroutines.sync.Mutex

/**
 * Serialises every import -- SMS receiver, inbox scan, reconcile and email sync.
 *
 * Cross-channel dedupe is check-then-insert. Run an SMS import and an email sync side by
 * side and each can check before the other inserts, so both copies of one payment land.
 * One lock across both channels closes that gap; imports are short and rare enough that
 * waiting on each other costs nothing noticeable.
 */
object ImportLock {
    val mutex = Mutex()
}
