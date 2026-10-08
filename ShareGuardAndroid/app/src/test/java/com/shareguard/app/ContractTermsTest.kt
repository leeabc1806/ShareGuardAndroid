package com.shareguard.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ContractTermsTest {
    @Test fun lateFeeStartsAfterGraceAndNeverExceedsDeposit() {
        val end = Instant.parse("2026-10-07T00:00:00Z")
        assertEquals(0, ContractTerms.lateFee(10_000, 25_000, end, end.plusSeconds(3600)))
        assertEquals(10_000, ContractTerms.lateFee(10_000, 25_000, end, end.plusSeconds(3601)))
        assertEquals(25_000, ContractTerms.lateFee(10_000, 25_000, end, end.plusSeconds(3600 + 86_400L * 4)))
    }

    @Test fun cancellationBoundaries() {
        val start = LocalDate.of(2026, 10, 7)
        assertEquals(0, ContractTerms.cancellationDeduction(100_000, false, start, start))
        assertEquals(0, ContractTerms.cancellationDeduction(100_000, true, start, start.minusDays(2)))
        assertEquals(30_000, ContractTerms.cancellationDeduction(100_000, true, start, start.minusDays(1)))
        assertEquals(30_000, ContractTerms.cancellationDeduction(100_000, true, start, start))
        assertEquals(0, ContractTerms.cancellationDeduction(100_000, true, start, start, ownerFault = true))
    }

    @Test fun serverTimeFormatsInKst() {
        assertEquals("2026-10-07 09:00:00 (KST)", ContractTerms.formatKst("2026-10-07T00:00:00Z"))
        assertEquals("2026-10-07 09:00:00 (KST)", ContractTerms.formatKst("2026-10-07T00:00:00+00:00"))
        assertEquals("서명 대기", ContractTerms.formatKst(null))
    }

    @Test fun snapshotHashIgnoresObjectKeyOrderAndHashField() {
        val first = JSONObject("""{"item":{"name":"카메라","value":200000},"days":[1,2],"hash":"old"}""")
        val second = JSONObject("""{"days":[1,2],"item":{"value":200000,"name":"카메라"}}""")
        assertEquals(ContractTerms.snapshotSha256(first), ContractTerms.snapshotSha256(second))
        assertEquals(64, ContractTerms.snapshotSha256(first).length)
    }

    @Test fun allArticlesHaveVersionAndEffectiveDate() {
        assertEquals("1.0", ContractTerms.TERMS_VERSION)
        assertEquals("2026-10-07", ContractTerms.EFFECTIVE_DATE)
        assertEquals(15, ContractTerms.clauses.size)
    }
}
