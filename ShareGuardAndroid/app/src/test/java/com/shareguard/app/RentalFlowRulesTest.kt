package com.shareguard.app

import org.junit.Assert.*
import org.junit.Test

class RentalFlowRulesTest {
    @Test fun `entry step follows authoritative contract status`() {
        assertEquals(RentalStep.HANDOVER_PHOTOS, RentalFlowRules.stepFor("SIGNED", 0))
        assertEquals(RentalStep.HANDOVER_QR, RentalFlowRules.stepFor("SIGNED", 1))
        assertEquals(RentalStep.HANDOVER_PHOTOS, RentalFlowRules.stepFor("READY", 0))
        assertEquals(RentalStep.HANDOVER_QR, RentalFlowRules.stepFor("READY", 2))
        assertEquals(RentalStep.RENTING, RentalFlowRules.stepFor("RENTING", 0))
        assertEquals(RentalStep.RETURNED, RentalFlowRules.stepFor("COMPLETED", 0))
        assertNull(RentalFlowRules.stepFor("DISPUTED", 2))
        assertNull(RentalFlowRules.stepFor("PAID", 2))
    }

    @Test fun `cannot skip photos or server status`() {
        assertFalse(RentalFlowRules.canEnter(RentalStep.HANDOVER_QR, "SIGNED", 0, 0))
        assertTrue(RentalFlowRules.canEnter(RentalStep.HANDOVER_QR, "SIGNED", 1, 0))
        assertFalse(RentalFlowRules.canEnter(RentalStep.RETURN_INSPECT, "READY", 1, 0))
        assertFalse(RentalFlowRules.canEnter(RentalStep.RETURN_QR, "RENTING", 1, 0))
        assertFalse(RentalFlowRules.canEnter(RentalStep.RETURN_QR, "RENTING", 1, 1, owner = true))
        assertTrue(RentalFlowRules.canEnter(RentalStep.RETURN_QR, "RENTING", 1, 1, owner = true, inspectionComplete = true))
        assertTrue(RentalFlowRules.canEnter(RentalStep.RETURN_QR, "RENTING", 1, 1, owner = false))
        assertFalse(RentalFlowRules.canEnter(RentalStep.RETURNED, "RENTING", 1, 1))
    }

    @Test fun `back navigation never returns from a result to QR`() {
        assertEquals(RentalStep.HANDOVER_PHOTOS, RentalFlowRules.backFrom(RentalStep.HANDOVER_QR))
        assertEquals(RentalStep.RENTING, RentalFlowRules.backFrom(RentalStep.RETURN_INSPECT))
        assertEquals(RentalStep.RETURN_INSPECT, RentalFlowRules.backFrom(RentalStep.RETURN_QR))
        assertEquals(RentalStep.RENTING, RentalFlowRules.backFrom(RentalStep.STARTED))
        assertNull(RentalFlowRules.backFrom(RentalStep.HANDOVER_PHOTOS))
        assertNull(RentalFlowRules.backFrom(RentalStep.RENTING))
        assertNull(RentalFlowRules.backFrom(RentalStep.RETURNED))
    }
}
