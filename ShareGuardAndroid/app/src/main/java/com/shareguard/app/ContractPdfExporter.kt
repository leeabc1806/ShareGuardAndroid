package com.shareguard.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

object ContractPdfExporter {
    fun create(
        context: Context,
        contractId: String,
        itemTitle: String,
        period: String,
        location: String,
        rentalFee: String,
        deposit: String,
        signer: String,
        signature: Bitmap
    ): File {
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 99, 235); textSize = 25f; isFakeBoldText = true }
        val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 33, 47); textSize = 16f; isFakeBoldText = true }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(45, 55, 72); textSize = 13f }
        canvas.drawColor(Color.WHITE)
        canvas.drawText("ShareGuard 전자 대여 계약서", 42f, 60f, title)
        canvas.drawText("계약번호  $contractId", 42f, 95f, body)
        canvas.drawText("대여 물품", 42f, 145f, heading)
        canvas.drawText(itemTitle, 42f, 172f, body)
        canvas.drawText("대여 기간", 42f, 215f, heading)
        canvas.drawText(period, 42f, 242f, body)
        canvas.drawText("거래 장소", 42f, 285f, heading)
        canvas.drawText(location, 42f, 312f, body)
        canvas.drawText("대여료  $rentalFee", 42f, 360f, body)
        canvas.drawText("보증금  $deposit", 42f, 388f, body)
        canvas.drawText("약정 사항", 42f, 445f, heading)
        canvas.drawText("1. 양 당사자는 대여 전후 상태 사진을 사실대로 기록합니다.", 42f, 475f, body)
        canvas.drawText("2. 파손·분실 및 환급은 계약 기록과 분쟁 처리 결과를 따릅니다.", 42f, 502f, body)
        canvas.drawText("3. QR 인증 완료 시 물품 인도 또는 반납에 동의한 것으로 봅니다.", 42f, 529f, body)
        canvas.drawText("서명자  $signer", 42f, 600f, heading)
        val scaled = Bitmap.createScaledBitmap(signature, 260, 120, true)
        canvas.drawBitmap(scaled, 42f, 620f, null)
        canvas.drawText("서명 시각  ${Instant.now()}", 42f, 770f, body)
        document.finishPage(page)
        val directory = File(context.filesDir, "contracts").apply { mkdirs() }
        val file = File(directory, "shareguard-contract-$contractId.pdf")
        FileOutputStream(file).use(document::writeTo)
        document.close()
        scaled.recycle()
        return file
    }
}
