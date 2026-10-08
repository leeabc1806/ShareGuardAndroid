package com.shareguard.app

/** UI navigation only. The server remains authoritative for QR and payment transitions. */
enum class RentalStep {
    HANDOVER_PHOTOS, HANDOVER_QR, STARTED, RENTING, RETURN_INSPECT, RETURN_QR, RETURNED
}

object RentalFlowRules {
    fun stepFor(status: String, beforePhotos: Int): RentalStep? = when (status) {
        "SIGNED", "READY" -> if (beforePhotos > 0) RentalStep.HANDOVER_QR else RentalStep.HANDOVER_PHOTOS
        "RENTING" -> RentalStep.RENTING
        "COMPLETED" -> RentalStep.RETURNED
        else -> null
    }

    fun canEnter(
        step: RentalStep, status: String, beforePhotos: Int, afterPhotos: Int,
        owner: Boolean = false, inspectionComplete: Boolean = false
    ): Boolean = when (step) {
        RentalStep.HANDOVER_PHOTOS -> status == "SIGNED" || status == "READY"
        RentalStep.HANDOVER_QR -> status in setOf("SIGNED", "READY") && beforePhotos > 0
        RentalStep.STARTED, RentalStep.RENTING, RentalStep.RETURN_INSPECT -> status == "RENTING"
        RentalStep.RETURN_QR -> status == "RENTING" && afterPhotos > 0 && (!owner || inspectionComplete)
        RentalStep.RETURNED -> status == "COMPLETED"
    }

    fun backFrom(step: RentalStep): RentalStep? = when (step) {
        RentalStep.HANDOVER_QR -> RentalStep.HANDOVER_PHOTOS
        RentalStep.RETURN_INSPECT -> RentalStep.RENTING
        RentalStep.RETURN_QR -> RentalStep.RETURN_INSPECT
        RentalStep.STARTED -> RentalStep.RENTING
        RentalStep.HANDOVER_PHOTOS, RentalStep.RENTING, RentalStep.RETURNED -> null
    }

    fun progressIndex(step: RentalStep): Int = when (step) {
        RentalStep.HANDOVER_PHOTOS, RentalStep.HANDOVER_QR -> 0
        RentalStep.STARTED, RentalStep.RENTING -> 1
        RentalStep.RETURN_INSPECT, RentalStep.RETURN_QR -> 2
        RentalStep.RETURNED -> 3
    }
}
