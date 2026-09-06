package com.shareguard.backend.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class AdminDtos {
    private AdminDtos() {}

    public record ResolveReportRequest(
        @NotBlank @Pattern(regexp = "dismissed|partial|uphold") String decision,
        @Min(0) int deductionAmount,
        @NotBlank @Pattern(regexp = "none|warning|hide_item|suspend_user") String sanction,
        @NotBlank @Size(min = 5, max = 2000) String note
    ) {}

    public record ResolveReportResponse(
        UUID reportId,
        UUID reservationId,
        String status,
        int refundableDeposit,
        boolean refundRequired
    ) {}
}
