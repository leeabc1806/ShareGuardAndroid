package com.shareguard.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Versioned contract wording. Financial rules here describe the agreement; server billing is separate. */
object ContractTerms {
    const val TERMS_VERSION = "1.0"
    const val EFFECTIVE_DATE = "2026-10-07"
    const val RETURN_GRACE_HOURS = 1L
    const val UNRETURNED_DAYS = 3L
    const val FULL_REFUND_DAYS_BEFORE = 2L
    const val BORROWER_CANCELLATION_DEDUCTION_PERCENT = 30
    const val OWNER_OBJECTION_HOURS = 24L
    const val DAMAGE_CLAIM_MONTHS = 6L

    val clauses: List<Pair<String, String>> = listOf(
        "제1조 목적 및 계약의 성질" to "이 계약은 대여자가 임차인에게 물품을 사용·수익하게 하고 임차인이 대여료를 지급하는 민법 제618조의 임대차입니다. ShareGuard는 통신판매중개자로서 거래 당사자가 아니며 결제 보관·기록 보존·분쟁 조정 역할을 합니다(전자상거래법 제20조).",
        "제2조 당사자" to "대여자와 임차인의 닉네임, 본인인증 여부, 계약 시점 신뢰 점수를 아래 계약 정보에 표시합니다. 실명과 연락처는 계약서에 표시하지 않으며, 분쟁 시 관련 법령에 따라 제공될 수 있습니다.",
        "제3조 대여 물품" to "물품명, 카테고리, 구성품, 계약 시 물품 가액, 대여 전 기존 하자와 대여 전 상태 사진 장수를 아래 계약 정보에 기록합니다. 양측은 인도 전 실제 상태를 확인합니다.",
        "제4조 기간·인도·반납" to "약정한 시작·종료 일시와 장소를 따릅니다. 앱의 QR 인증 시각을 물품 인도·반납 시점으로 봅니다. QR 인증에 실패하면 양측의 수동 검수 기록으로 확인합니다.",
        "제5조 대여료·보증금·결제" to "대여료는 하루 대여료 × 대여 일수입니다. 원 보증금에서 신뢰도 감면율을 적용한 최종 보증금과 결제 주문번호·일시(있는 경우)를 계약 정보에 표시합니다. 보증금은 결제 승인 후 정상 반납이 확인되면 결제 취소 방식으로 환급합니다. 별도 에스크로 서비스가 아닙니다.",
        "제6조 임차인 의무" to "임차인은 선량한 관리자의 주의로 물품을 보관하고(민법 제374조), 약정 용도를 준수하며 재대여·양도·개조를 하지 않습니다(민법 제610조·제629조). 고장이나 분실을 안 때에는 24시간 안에 앱으로 통지합니다.",
        "제7조 대여자 의무" to "대여자는 정상 상태와 약정 구성품을 갖춘 물품을 인도하고 알고 있는 하자를 미리 고지합니다(민법 제623조). 고의로 숨긴 하자로 인한 손해는 대여자가 부담합니다.",
        "제8조 반납 지연" to "종료 시각 후 ${RETURN_GRACE_HOURS}시간은 유예합니다. 이후 지연 1일당 하루 대여료를 연체료로 하되 총액은 최종 보증금을 넘지 않습니다. ${UNRETURNED_DAYS}일간 연락 두절 및 미반납이면 신고하고 보증금 환급을 보류할 수 있습니다.",
        "제9조 파손·분실 배상" to "통상 마모는 제외합니다. 수리 가능하면 수리비 실비, 수리 불가·분실이면 시가 또는 계약 가액에서 감가상각한 금액, 구성품 누락이면 해당 구성품 가격을 기준으로 합니다. 상태 사진·채팅·QR 기록을 판단 근거로 하고 보증금에서 먼저 공제하며 부족분은 별도 청구할 수 있습니다. 사용·수익 위반으로 인한 손해는 반납일부터 ${DAMAGE_CLAIM_MONTHS}개월 안에 청구합니다(민법 제654조·제617조).",
        "제10조 취소·환불" to "결제 전 취소는 자유롭습니다. 결제 후 시작 ${FULL_REFUND_DAYS_BEFORE}일 전까지는 전액 환불합니다. 시작 1일 전부터 당일까지 임차인 사정으로 취소하면 대여료의 ${BORROWER_CANCELLATION_DEDUCTION_PERCENT}%를 공제하며, 대여자 사정이면 전액 환불합니다.",
        "제11조 보증금 반환" to "반납 QR 인증 후 대여자가 ${OWNER_OBJECTION_HOURS}시간 안에 이의를 제기하지 않으면 정상 반납으로 보고 환급 대상이 됩니다. 분쟁이 접수되면 환급을 보류하고 관리자 결정을 따릅니다.",
        "제12조 분쟁 해결" to "당사자가 먼저 협의하고, 해결되지 않으면 ShareGuard의 조정을 거칩니다. 이후 한국소비자원(1372) 상담·조정 또는 관할 법원을 이용할 수 있습니다.",
        "제13조 불가항력·면책" to "천재지변 등 통제할 수 없는 사유는 사정을 확인하여 당사자가 협의합니다. ShareGuard의 고의 또는 중대한 과실로 인한 법률상 책임은 면책하지 않습니다(약관규제법 제7조).",
        "제14조 개인정보·기록 보존" to "당사자는 분쟁 처리를 위해 필요한 범위에서 관련 정보가 제공될 수 있음에 동의합니다. 계약·결제 기록은 5년, 불만·분쟁 기록은 3년 보관합니다. 별도의 개인정보 처리·보존 절차는 관련 법령과 개인정보 처리방침을 따릅니다.",
        "제15조 전자계약의 효력" to "전자문서는 전자적 형태만으로 효력이 부인되지 않고(전자문서법 제4조), 당사자가 선택한 전자서명 방식은 전자서명법 제3조에 따릅니다. 본인인증 계정의 앱 서명 패드로 서명하고 계약 snapshot의 SHA-256 해시를 표시합니다."
    )

    fun lateFee(dailyFee: Int, deposit: Int, scheduledEnd: Instant, returnedAt: Instant): Int {
        require(dailyFee >= 0 && deposit >= 0)
        val overdue = Duration.between(scheduledEnd.plusSeconds(RETURN_GRACE_HOURS * 3600), returnedAt)
        if (overdue.isNegative || overdue.isZero) return 0
        val days = (overdue.toMillis() + 86_399_999L) / 86_400_000L
        return (days * dailyFee).coerceAtMost(deposit.toLong()).toInt()
    }

    /** Contractual deduction only; this does not initiate any refund or charge. */
    fun cancellationDeduction(rentalFee: Int, paid: Boolean, startDate: LocalDate, cancelledOn: LocalDate, ownerFault: Boolean = false): Int {
        require(rentalFee >= 0)
        if (!paid || ownerFault || !cancelledOn.isAfter(startDate.minusDays(FULL_REFUND_DAYS_BEFORE))) return 0
        return (rentalFee.toLong() * BORROWER_CANCELLATION_DEDUCTION_PERCENT / 100).toInt()
    }

    private val kstFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss '(KST)'")
    fun formatKst(timestamp: String?): String = timestamp?.takeIf(String::isNotBlank)?.let {
        runCatching { Instant.parse(it).atZone(ZoneId.of("Asia/Seoul")).format(kstFormatter) }.getOrNull()
    } ?: "서명 대기"

    /** Excludes the hash field itself. Object keys are recursively ordered; arrays retain order. */
    fun snapshotSha256(snapshot: JSONObject): String {
        val copy = JSONObject(snapshot.toString()).apply { remove("hash") }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonicalJson(copy).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
