package com.shareguard.app

import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Manual layout fixture: am instrument -w com.shareguard.app.test/com.shareguard.app.ContractPdfSampleInstrumentation */
class ContractPdfSampleInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val result = Bundle()
        try {
            val snapshot = JSONObject()
                .put("terms_version", ContractTerms.TERMS_VERSION)
                .put("item_title", "[예시] 캠핑용 텐트 6인용")
                .put("item_category", "캠핑")
                .put("components", "방수 플라이, 그라운드시트, 폴대, 팩 12개")
                .put("known_defects", "수납 가방 손잡이의 경미한 마모")
                .put("item_value", 320000)
                .put("daily_price", 25000)
                .put("location", "서울시 송파구 잠실동 (예시)")
                .put("before_photo_count", 5)
                .put("start_date", "2026-10-10").put("end_date", "2026-10-12")
                .put("rental_days", 3).put("rental_fee", 75000)
                .put("original_deposit", 100000).put("discount_percent", 7)
                .put("deposit", 93000)
            snapshot.put("hash", ContractTerms.snapshotSha256(snapshot))
            val exampleSignature = Bitmap.createBitmap(320, 100, Bitmap.Config.ARGB_8888)
            Canvas(exampleSignature).drawText("SAMPLE", 25f, 65f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 45f })
            val sample = ContractDocument(
                "SAMPLE-20261007", snapshot,
                ContractSigner("대여자(예시)", true, 850, "2026-10-07T03:00:00Z", exampleSignature),
                ContractSigner("임차인(예시)", true, 800, "2026-10-07T03:10:00Z", exampleSignature),
                "반납 장소는 최초 인도 장소와 동일합니다. (예시)",
                currentBeforePhotoCount = 5,
                paymentOrderId = "sample-order-001", paidAt = "2026-10-07T02:55:00Z"
            )
            val pdf = ContractPdfExporter.create(targetContext, sample)
            val destination = checkNotNull(targetContext.getExternalFilesDir(null)).toPath().resolve("shareguard-contract-sample.pdf")
            Files.copy(pdf.toPath(), destination, StandardCopyOption.REPLACE_EXISTING)
            result.putString("pdf", destination.toString())
            finish(0, result)
        } catch (error: Exception) {
            result.putString("error", error.stackTraceToString())
            finish(-1, result)
        }
    }
}
