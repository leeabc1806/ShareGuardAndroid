package com.shareguard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DepositBenefitPolicyTest {
    @Test fun discountIsAutomaticallyCappedAtTenPercent() {
        assertEquals(0, DepositBenefitPolicy.discountPercent(599))
        assertEquals(3, DepositBenefitPolicy.discountPercent(600))
        assertEquals(5, DepositBenefitPolicy.discountPercent(700))
        assertEquals(7, DepositBenefitPolicy.discountPercent(800))
        assertEquals(10, DepositBenefitPolicy.discountPercent(900))
        assertEquals(10, DepositBenefitPolicy.discountPercent(1000))
    }

    @Test fun adjustedDepositUsesTheSameRateShownInTheApp() {
        assertEquals(90_000, DepositBenefitPolicy.adjustedDeposit(100_000, 950))
        assertEquals(100_000, DepositBenefitPolicy.adjustedDeposit(100_000, 100))
    }
}
