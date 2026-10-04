package com.shareguard.app

/** Client-side quote only; final financial amounts must also be checked by the server. */
object DepositBenefitPolicy {
    fun discountPercent(trustScore: Int): Int = when {
        trustScore >= 900 -> 10
        trustScore >= 800 -> 7
        trustScore >= 700 -> 5
        trustScore >= 600 -> 3
        else -> 0
    }

    fun adjustedDeposit(baseDeposit: Int, trustScore: Int): Int =
        (baseDeposit.toLong() * (100 - discountPercent(trustScore)) / 100).toInt()
}
