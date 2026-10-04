package com.shareguard.app

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReservationRulesTest {
    private val start = LocalDate.of(2026, 10, 10)
    @Test fun `pending or finished reservations do not block another request`() {
        listOf("pending", "cancelled", "rejected", "completed").forEach {
            assertFalse(ReservationRules.overlaps(it, start, start.plusDays(2), start, start.plusDays(1)))
        }
    }
    @Test fun `confirmed reservations only block intersecting dates including endpoints`() {
        ReservationRules.occupiedStatuses.forEach {
            assertTrue(ReservationRules.overlaps(it, start, start.plusDays(2), start.plusDays(2), start.plusDays(3)))
            assertFalse(ReservationRules.overlaps(it, start, start.plusDays(2), start.plusDays(3), start.plusDays(4)))
        }
    }
    @Test fun `rental fee includes start and end date`() {
        assertEquals(5000, ReservationRules.rentalFee(5000, start, start))
        assertEquals(15000, ReservationRules.rentalFee(5000, start, start.plusDays(2)))
    }
    @Test(expected = IllegalArgumentException::class) fun `reversed dates are rejected`() {
        ReservationRules.rentalFee(5000, start, start.minusDays(1))
    }
    @Test(expected = ArithmeticException::class) fun `large amounts cannot wrap into negative fees`() {
        ReservationRules.rentalFee(Int.MAX_VALUE, start, start.plusDays(1))
    }
}
