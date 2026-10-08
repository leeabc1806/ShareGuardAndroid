package com.shareguard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

data class ContractSigner(val nickname: String, val verified: Boolean, val trustScore: Int, val signedAt: String?, val signature: Bitmap?)

/** Snapshot values, rather than the mutable item or profile, are the signed source of truth. */
data class ContractDocument(
    val contractId: String,
    val snapshot: JSONObject,
    val owner: ContractSigner,
    val borrower: ContractSigner,
    val specialTerms: String?,
    val currentBeforePhotoCount: Int = 0,
    val paymentOrderId: String? = null,
    val paidAt: String? = null,
    val generatedAt: Instant = Instant.now()
)

object ContractPdfExporter {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val MARGIN = 42f
    private const val BODY_BOTTOM = 759f

    fun create(context: Context, contract: ContractDocument): File {
        val pdf = PdfDocument()
        val writer = PageWriter(pdf, contract.contractId)
        try {
            writer.openPage()
            writer.heading("ShareGuard 전자 대여 계약서", 23f, Color.rgb(37, 99, 235))
            writer.paragraph("조항 버전 ${ContractTerms.TERMS_VERSION} · 시행일 ${ContractTerms.EFFECTIVE_DATE}", 10f)
            val s = contract.snapshot
            writer.heading("계약 정보")
            writer.paragraph("대여자: ${contract.owner.nickname} · 본인인증 ${badge(contract.owner.verified)} · 계약 시 신뢰점수 ${contract.owner.trustScore}")
            writer.paragraph("임차인: ${contract.borrower.nickname} · 본인인증 ${badge(contract.borrower.verified)} · 계약 시 신뢰점수 ${contract.borrower.trustScore}")
            writer.paragraph("물품: ${s.display("item_title")} / ${s.display("item_category")}")
            writer.paragraph("구성품: ${s.display("components")} · 계약 가액: ${s.money("item_value")}")
            writer.paragraph("기존 하자: ${s.display("known_defects")} · 대여 전 상태 사진: ${contract.currentBeforePhotoCount}장 (현재 기록, 해시 대상 아님)")
            writer.paragraph("대여 기간: ${s.display("start_date")} ~ ${s.display("end_date")} (${s.optInt("rental_days", 0)}일)")
            writer.paragraph("인도·반납 장소: ${s.display("location")}")
            writer.paragraph("하루 대여료 ${s.money("daily_price")} × ${s.optInt("rental_days", 0)}일 = ${s.money("rental_fee")}")
            writer.paragraph("원 보증금 ${s.money("original_deposit")} → 신뢰도 감면 ${s.optInt("discount_percent", 0)}% → 최종 보증금 ${s.money("deposit")}")
            writer.paragraph("결제 주문번호: ${contract.paymentOrderId ?: "미기록"} · 결제 일시: ${contract.paidAt?.let(ContractTerms::formatKst) ?: "미기록"}")
            ContractTerms.clauses.forEach { (title, body) -> writer.clause(title, body) }
            writer.heading("특약사항")
            writer.paragraph(contract.specialTerms?.takeIf(String::isNotBlank) ?: "없음")
            writer.signatures(contract.owner, contract.borrower)
            writer.heading("계약 무결성")
            writer.paragraph("계약 snapshot SHA-256: ${s.optString("hash").ifBlank { "서명 대기" }}", 10f)
            writer.paragraph("조항 버전: ${s.optString("terms_version", ContractTerms.TERMS_VERSION)} · PDF 생성: ${ContractTerms.formatKst(contract.generatedAt.toString())}", 10f)
            writer.closePage()
            val directory = File(context.filesDir, "contracts").apply { mkdirs() }
            val file = File(directory, "shareguard-contract-${contract.contractId}.pdf")
            FileOutputStream(file).use(pdf::writeTo)
            return file
        } finally { pdf.close() }
    }

    private fun badge(verified: Boolean) = if (verified) "완료" else "미완료"
    private fun JSONObject.display(key: String): String = if (isNull(key)) "미기재" else optString(key).ifBlank { "미기재" }
    private fun JSONObject.money(key: String): String = if (isNull(key)) "미기재" else "${"%,d".format(optLong(key))}원"

    private class PageWriter(private val document: PdfDocument, private val contractId: String) {
        private var page: PdfDocument.Page? = null
        private var number = 0
        private var y = 96f
        private val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 33, 47); textSize = 14f; isFakeBoldText = true }
        private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 55, 72); textSize = 11f }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 112, 132); textSize = 9f }
        private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 227, 238); style = Paint.Style.STROKE; strokeWidth = 1f }

        fun openPage() {
            closePage()
            number++
            page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, HEIGHT, number).create())
            val canvas = requireNotNull(page).canvas
            canvas.drawColor(Color.WHITE)
            canvas.drawText("ShareGuard · 계약번호 $contractId", MARGIN, 51f, small)
            canvas.drawLine(MARGIN, 62f, WIDTH - MARGIN, 62f, rule)
            canvas.drawLine(MARGIN, 778f, WIDTH - MARGIN, 778f, rule)
            canvas.drawText("ShareGuard는 거래 당사자가 아닙니다", MARGIN, 798f, small)
            canvas.drawText("${number}쪽", WIDTH - MARGIN - 22f, 798f, small)
            y = 94f
        }

        fun closePage() { page?.let(document::finishPage); page = null }

        private fun layout(value: String, paint: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(value, 0, value.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(2f, 1f).build()

        private fun block(value: String, paint: TextPaint, topGap: Float, bottomGap: Float) {
            val rendered = layout(value, paint, (WIDTH - MARGIN * 2).toInt())
            if (y + topGap + rendered.height + bottomGap > BODY_BOTTOM) openPage()
            y += topGap
            val canvas = requireNotNull(page).canvas
            canvas.save(); canvas.translate(MARGIN, y); rendered.draw(canvas); canvas.restore()
            y += rendered.height + bottomGap
        }

        fun heading(value: String, size: Float = 14f, color: Int = Color.rgb(24, 33, 47)) {
            heading.textSize = size; heading.color = color; block(value, heading, 11f, 4f)
        }
        fun paragraph(value: String, size: Float = 11f) { body.textSize = size; block(value, body, 0f, 5f) }

        fun clause(title: String, value: String) {
            heading.textSize = 14f
            body.textSize = 11f
            val width = (WIDTH - MARGIN * 2).toInt()
            val required = 11f + layout(title, heading, width).height + 4f +
                layout(value, body, width).height + 5f
            if (y + required > BODY_BOTTOM) openPage()
            heading(title)
            paragraph(value)
        }

        fun signatures(owner: ContractSigner, borrower: ContractSigner) {
            val height = 145f
            if (y + height + 25f > BODY_BOTTOM) openPage()
            heading("전자 서명")
            val canvas = requireNotNull(page).canvas
            val gap = 12f
            val boxWidth = (WIDTH - MARGIN * 2 - gap) / 2
            listOf(owner, borrower).forEachIndexed { index, signer ->
                val x = MARGIN + index * (boxWidth + gap)
                canvas.drawRoundRect(RectF(x, y, x + boxWidth, y + height), 7f, 7f, rule)
                val label = if (index == 0) "대여자" else "임차인"
                canvas.drawText("$label  ${signer.nickname.take(17)}", x + 9f, y + 17f, body)
                canvas.drawText("본인인증 ${badge(signer.verified)}", x + 9f, y + 34f, small)
                signer.signature?.let { bitmap -> canvas.drawBitmap(bitmap, null, RectF(x + 10f, y + 40f, x + boxWidth - 10f, y + 110f), null) }
                canvas.drawText(ContractTerms.formatKst(signer.signedAt), x + 9f, y + 131f, small)
            }
            y += height + 8f
        }
    }
}
