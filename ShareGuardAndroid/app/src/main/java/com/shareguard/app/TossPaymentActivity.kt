package com.shareguard.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tosspayments.paymentsdk.PaymentWidget
import com.tosspayments.paymentsdk.model.AgreementStatus
import com.tosspayments.paymentsdk.model.AgreementStatusListener
import com.tosspayments.paymentsdk.model.PaymentCallback
import com.tosspayments.paymentsdk.model.PaymentWidgetStatusListener
import com.tosspayments.paymentsdk.model.TossPaymentResult
import com.tosspayments.paymentsdk.view.Agreement
import com.tosspayments.paymentsdk.view.PaymentMethod
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

class TossPaymentActivity : AppCompatActivity() {
    private val blue = Color.rgb(37, 99, 235)
    private val bg = Color.rgb(244, 247, 250)
    private val text = Color.rgb(24, 33, 47)
    private val muted = Color.rgb(100, 112, 132)
    private val line = Color.rgb(221, 228, 238)

    private lateinit var payButton: Button
    private lateinit var status: TextView
    private lateinit var paymentWidget: PaymentWidget

    private var orderId = ""
    private var orderName = ""
    private var amount = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clientKey = intent.getStringExtra(EXTRA_CLIENT_KEY).orEmpty()
        val customerKey = intent.getStringExtra(EXTRA_CUSTOMER_KEY).orEmpty()
        orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        orderName = intent.getStringExtra(EXTRA_ORDER_NAME).orEmpty()
        amount = intent.getLongExtra(EXTRA_AMOUNT, 0L)

        if (clientKey.isBlank() || customerKey.isBlank() || orderId.isBlank() || amount <= 0L) {
            finishWithFailure("INVALID_PAYMENT_REQUEST", "결제 요청 정보가 올바르지 않습니다.")
            return
        }

        val paymentMethod = PaymentMethod(this)
        val agreement = Agreement(this)

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(ScrollView(this@TossPaymentActivity).apply {
                addView(LinearLayout(this@TossPaymentActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                    addView(textView("토스페이먼츠 결제", 24, true, text))
                    addView(textView(orderName, 15, false, muted).withTop(4))
                    addView(textView(money(amount), 22, true, blue).withTop(12))
                    status = textView("결제위젯을 불러오는 중입니다.", 13, false, muted).withTop(14)
                    addView(status)
                    addView(paymentMethod.withTop(10))
                    addView(agreement.withTop(8))
                })
            }, LinearLayout.LayoutParams(match, 0, 1f))
            payButton = Button(this@TossPaymentActivity).apply {
                text = "결제하기"
                isAllCaps = false
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                isEnabled = false
                background = rounded(blue, 8)
                setOnClickListener { requestPayment() }
            }
            addView(payButton, LinearLayout.LayoutParams(match, dp(56)).apply {
                setMargins(dp(18), dp(10), dp(18), dp(18))
            })
        })

        paymentWidget = PaymentWidget(
            activity = this,
            clientKey = clientKey,
            customerKey = customerKey
        )
        paymentWidget.renderPaymentMethods(
            paymentMethod,
            PaymentMethod.Rendering.Amount(amount),
            null,
            widgetStatusListener
        )
        paymentWidget.renderAgreement(agreement, widgetStatusListener)
        paymentWidget.addAgreementStatusListener(agreementStatusListener)
    }

    private val widgetStatusListener = object : PaymentWidgetStatusListener {
        override fun onLoad() {
            runOnUiThread { status.text = "결제수단과 약관을 확인하세요." }
        }

        override fun onFail(fail: TossPaymentResult.Fail) {
            finishWithFailure(fail.errorCode, fail.errorMessage, fail.orderId)
        }
    }

    private val agreementStatusListener = object : AgreementStatusListener {
        override fun onAgreementStatusChanged(agreementStatus: AgreementStatus) {
            runOnUiThread {
                payButton.isEnabled = agreementStatus.agreedRequiredTerms
                payButton.alpha = if (agreementStatus.agreedRequiredTerms) 1f else 0.55f
            }
        }
    }

    private fun requestPayment() {
        payButton.isEnabled = false
        paymentWidget.requestPayment(
            paymentInfo = PaymentMethod.PaymentInfo(orderId = orderId, orderName = orderName),
            paymentCallback = object : PaymentCallback {
                override fun onPaymentSuccess(success: TossPaymentResult.Success) {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(RESULT_PAYMENT_KEY, success.paymentKey)
                            .putExtra(RESULT_ORDER_ID, success.orderId)
                            .putExtra(RESULT_AMOUNT, success.amount.toLong())
                    )
                    finish()
                }

                override fun onPaymentFailed(fail: TossPaymentResult.Fail) {
                    finishWithFailure(fail.errorCode, fail.errorMessage, fail.orderId)
                }
            }
        )
    }

    private fun finishWithFailure(errorCode: String, errorMessage: String, failedOrderId: String? = orderId) {
        Toast.makeText(this, errorMessage, Toast.LENGTH_SHORT).show()
        setResult(
            RESULT_CANCELED,
            Intent()
                .putExtra(RESULT_ERROR_CODE, errorCode)
                .putExtra(RESULT_ERROR_MESSAGE, errorMessage)
                .putExtra(RESULT_ORDER_ID, failedOrderId)
        )
        finish()
    }

    private fun textView(value: String, sp: Int, bold: Boolean, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = sp.toFloat()
        setTextColor(color)
        includeFontPadding = true
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun <T : android.view.View> T.withTop(dpValue: Int): T {
        val existing = layoutParams as? ViewGroup.MarginLayoutParams
        layoutParams = (existing ?: ViewGroup.MarginLayoutParams(match, wrap)).apply {
            topMargin = dp(dpValue)
        }
        return this
    }

    private fun money(value: Long): String = NumberFormat.getNumberInstance(Locale.KOREA).format(value) + "원"
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val EXTRA_CLIENT_KEY = "clientKey"
        private const val EXTRA_CUSTOMER_KEY = "customerKey"
        private const val EXTRA_ORDER_ID = "orderId"
        private const val EXTRA_ORDER_NAME = "orderName"
        private const val EXTRA_AMOUNT = "amount"

        const val RESULT_PAYMENT_KEY = "paymentKey"
        const val RESULT_ORDER_ID = "orderId"
        const val RESULT_AMOUNT = "amount"
        const val RESULT_ERROR_CODE = "errorCode"
        const val RESULT_ERROR_MESSAGE = "errorMessage"

        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(
            context: Context,
            clientKey: String,
            customerKey: String,
            orderId: String,
            orderName: String,
            amount: Long
        ): Intent = Intent(context, TossPaymentActivity::class.java)
            .putExtra(EXTRA_CLIENT_KEY, clientKey)
            .putExtra(EXTRA_CUSTOMER_KEY, customerKey)
            .putExtra(EXTRA_ORDER_ID, orderId)
            .putExtra(EXTRA_ORDER_NAME, orderName)
            .putExtra(EXTRA_AMOUNT, amount)
    }
}
