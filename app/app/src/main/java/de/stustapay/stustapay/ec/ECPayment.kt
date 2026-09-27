package de.stustapay.stustapay.ec

import de.stustapay.libssp.model.NfcTag
import com.ionspin.kotlin.bignum.integer.BigInteger
import java.math.BigDecimal

data class ECPayment(
    /** transaction identifier */
    val id: String,

    /** what tag this payment is for */
    val tag: NfcTag,

    /** value without tip */
    val amount: BigDecimal,

    /** additional tip */
    val tip: BigDecimal = BigDecimal(0),

    /**
     * customer account the payment is booked on, if known.
     * Sent to SumUp as additional info so a card payment can be traced back
     * to the band/account in the admin UI — never the band PIN.
     */
    val customerAccountId: BigInteger? = null,
)
