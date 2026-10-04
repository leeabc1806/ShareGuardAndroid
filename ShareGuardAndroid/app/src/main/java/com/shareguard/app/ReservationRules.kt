package com.shareguard.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Shared UI rules; the database repeats validation under a transaction lock. */
object ReservationRules {
    val occupiedStatuses = setOf("approved", "paid", "renting")
    val editableStatuses = setOf("pending", "approved")
    val finishedStatuses = setOf("cancelled", "rejected", "completed")

    fun overlaps(status: String, start: LocalDate, end: LocalDate, otherStart: LocalDate, otherEnd: LocalDate): Boolean =
        status in occupiedStatuses && !end.isBefore(otherStart) && !start.isAfter(otherEnd)

    fun rentalFee(price: Int, start: LocalDate, end: LocalDate): Int {
        require(price >= 0 && !end.isBefore(start)) { "가격과 대여 기간을 확인하세요." }
        return Math.toIntExact(Math.multiplyExact(price.toLong(), ChronoUnit.DAYS.between(start, end) + 1))
    }
}
