package com.shareguard.app

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import com.shareguard.app.data.AuthResult
import com.shareguard.app.data.NewRemoteItem
import com.shareguard.app.data.RemoteContract
import com.shareguard.app.data.RemoteItem
import com.shareguard.app.data.RemoteReport
import com.shareguard.app.data.RemoteReservation
import com.shareguard.app.data.SupabaseRestClient
import com.shareguard.app.data.TossConfirmClient
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val SESSION_PREFS = "shareguard_session"
        private const val INACTIVITY_DAYS = 14L
        private const val INACTIVITY_TIMEOUT_MS = INACTIVITY_DAYS * 24L * 60L * 60L * 1000L
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_LAST_ACTIVE_AT = "last_active_at"
        private const val KEY_APP_VERSION = "app_version_code"
        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val TOSS_PAYMENT_REQUEST = 9001
        private const val TOSS_DOCS_CLIENT_KEY = "test_gck_docs_Ovk5rk1EwkEbP0W43n07xlzm"
    }
    private enum class AuthPanel {
        CLOSED,
        LOGIN,
        SIGN_UP
    }

    private enum class VerificationStep(val number: Int) {
        NONE(0),
        CONSENT(1),
        PHONE(2),
        IDENTITY(3)
    }

    private enum class Screen(val title: String, val subtitle: String) {
        HOME("위치 기반 물품 탐색", "주변 물품을 거리, 가격, 신뢰도 기준으로 확인합니다."),
        FILTER("검색 필터", "카테고리, 거리, 가격, 정렬 기준을 선택합니다."),
        MAP("지도 검색", "주변 대여 물품을 지도에서 확인합니다."),
        DETAIL("물품 상세", "가격, 보증금, 대여자 신뢰 정보를 확인합니다."),
        REGISTER("물품 등록", "사진, 가격, 보증금, 거래 위치를 입력해 물품을 등록합니다."),
        RESERVATIONS("예약 및 거래 관리", "예약 승인부터 결제, 계약, QR 인증, 환급까지 진행합니다."),
        TRANSACTION("안전 결제 및 계약", "대여료와 보증금을 확인하고 전자 계약에 서명합니다."),
        CHAT("실시간 채팅", "대여 조건, 장소, 시간을 거래 당사자끼리 조율합니다."),
        QR("QR 거래 인증", "상태 사진 기록 후 QR로 대여 시작과 반납 완료를 인증합니다."),
        FEEDBACK("후기 및 문제 신고", "거래 후기를 남기거나 문제가 있는 거래를 신고합니다."),
        NOTIFICATIONS("알림", "예약, 결제, 채팅, 반납 상태를 한곳에서 확인합니다."),
        PROFILE("프로필 및 신뢰 지수", "거래 이력, 평점, 본인 인증, 무사고 기록을 확인합니다."),
        ADMIN("관리자 분쟁 처리", "신고와 분쟁을 검토하고 보증금 처리를 결정합니다.")
    }

    private enum class ContractStatus(val label: String) {
        APPROVED("예약 승인"),
        PAID("결제 완료"),
        SIGNED("계약 완료"),
        READY("대여 준비"),
        RENTING("대여중"),
        COMPLETED("반납 완료"),
        DISPUTED("분쟁중")
    }

    private data class User(
        val id: Int,
        val name: String,
        val role: String,
        var verified: Boolean,
        var trust: Int,
        var rating: Double,
        var deals: Int,
        var noAccidentDays: Int
    )

    private data class Item(
        val id: Int,
        var title: String,
        var category: String,
        var description: String,
        var price: Int,
        var deposit: Int,
        val distance: Int,
        var location: String,
        val ownerId: Int,
        val tone: Int,
        val remoteId: String? = null,
        val remoteOwnerId: String? = null
    )

    private data class Reservation(
        val id: Int,
        val itemId: Int,
        val borrowerId: Int,
        val startDate: String,
        val endDate: String,
        val totalAmount: Int,
        var approved: Boolean = false,
        var status: String = "pending",
        val remoteId: String? = null,
        val remoteBorrowerId: String? = null
    )

    private data class Contract(
        val id: Int,
        val reservationId: Int,
        var status: ContractStatus,
        var beforePhotos: Int = 0,
        var afterPhotos: Int = 0,
        var refunded: Boolean = false,
        var refundHeld: Boolean = false,
        val remoteId: String? = null,
        var ownerSigned: Boolean = false,
        var borrowerSigned: Boolean = false
    )

    private data class ChatMessage(val mine: Boolean, val text: String)
    private data class Report(
        val id: Int,
        val contractId: Int,
        val type: String,
        val reason: String,
        var open: Boolean = true,
        var status: String = "접수",
        val remoteId: String? = null
    )
    private data class AppNotification(
        val id: Int,
        val icon: String,
        val title: String,
        val body: String,
        val time: String,
        val target: Screen,
        var unread: Boolean = true
    )

    private val blue = Color.rgb(37, 99, 235)
    private val teal = Color.rgb(15, 118, 110)
    private val bg = Color.rgb(244, 247, 250)
    private val surface = Color.WHITE
    private val text = Color.rgb(24, 33, 47)
    private val muted = Color.rgb(100, 112, 132)
    private val line = Color.rgb(221, 228, 238)
    private val danger = Color.rgb(220, 38, 38)
    private val warn = Color.rgb(217, 119, 6)
    private val good = Color.rgb(22, 163, 74)

    private var screen = Screen.HOME
    private var selectedItemId = 501
    private var nextItemId = 505
    private var nextReservationId = 1
    private var nextContractId = 1
    private var nextReportId = 1

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var supabase: SupabaseRestClient
    private var sessionToken: String? = null
    private var pendingPaymentContractId: Int? = null
    private var sessionUserId: String? = null
    private var activeChatRoomId: String? = null
    private var pendingPhotoContractId: String? = null
    private var pendingPhotoPhase: String? = null
    private var authPanel = AuthPanel.CLOSED
    private var verificationStep = VerificationStep.NONE
    private var verificationCodeSent = false
    private var searchQuery = ""
    private var selectedCategory = "전체"
    private var sortMode = "거리순"
    private var maxDistanceKm = 10
    private var registerTab = "새 물품"
    private var registerCategory = "카메라"
    private var editingItemId: Int? = null
    private var registrationPhotos = 0
    private var chatDetailOpen = false
    private var reservationTab = "빌린 물품"
    private var activeContractId: Int? = null
    private var qrPhase = "대여 시작"
    private var notificationFilter = "전체"
    private var adminTab = "신고 관리"
    private var selectedAdminReportId: Int? = null
    private var nextNotificationId = 10
    private var sessionNotice: String? = null

    private val beforePhotoRequest = 7101
    private val afterPhotoRequest = 7102

    private val users = mutableListOf(
        User(1, "김민수", "대여자", true, 850, 4.9, 28, 120),
        User(2, "이지수", "차용자", false, 780, 4.8, 12, 64)
    )
    private val items = mutableListOf(
        Item(501, "소니 A7III 풀프레임 미러리스", "카메라", "렌즈, 배터리 2개, 충전기 포함. 대여 전후 사진 기록 후 안전하게 거래합니다.", 50000, 200000, 520, "강남구 역삼동", 1, Color.rgb(219, 234, 254)),
        Item(502, "PS5 디지털 에디션", "게임기", "듀얼센스 2개와 인기 타이틀 3종을 함께 제공합니다.", 30000, 150000, 1180, "서초구 서초동", 1, Color.rgb(237, 233, 254)),
        Item(503, "캠핑용 텐트 6인용", "캠핑", "방수 플라이와 그라운드시트 포함. 구성품 체크리스트를 제공합니다.", 25000, 100000, 2100, "송파구 잠실동", 1, Color.rgb(220, 252, 231)),
        Item(504, "캠핑용 빔프로젝터", "IT", "HDMI 케이블, 삼각대, 전용 파우치를 포함합니다.", 15000, 80000, 850, "강남구 논현동", 1, Color.rgb(254, 243, 199))
    )
    private val reservations = mutableListOf<Reservation>()
    private val contracts = mutableListOf<Contract>()
    private val reports = mutableListOf<Report>()
    private val demoAdminReport = Report(
        -1,
        0,
        "파손 의심",
        "반납 후 사진에서 렌즈 가장자리 스크래치가 확인되어 보증금 처리를 요청합니다.",
        true,
        "검토중"
    )
    private val notifications = mutableListOf(
        AppNotification(1, "✓", "예약이 승인되었어요", "소니 A7III 예약이 승인되었습니다. 안전 결제를 진행해주세요.", "방금", Screen.RESERVATIONS),
        AppNotification(2, "💬", "새 메시지가 도착했어요", "김민수님: 네, 내일 오전 10시 가능합니다!", "2분 전", Screen.CHAT),
        AppNotification(3, "🔒", "보증금 환급 완료", "정상 반납이 확인되어 보증금 200,000원이 환급되었습니다.", "어제", Screen.RESERVATIONS, false),
        AppNotification(4, "📷", "대여 전 사진을 등록해주세요", "거래 시작 전 물품 상태 사진이 필요합니다.", "2일 전", Screen.QR, false)
    )
    private val chatMessages = mutableListOf(
        ChatMessage(false, "가능합니다. 5월 1일부터 3일까지 비워둘게요."),
        ChatMessage(true, "거래 장소는 역삼역 근처가 괜찮습니다.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supabase = SupabaseRestClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val storedToken = preferences.getString(KEY_ACCESS_TOKEN, null)
        val storedUserId = preferences.getString(KEY_USER_ID, null)
        val lastActiveAt = preferences.getLong(KEY_LAST_ACTIVE_AT, 0L)
        val storedVersion = preferences.getInt(KEY_APP_VERSION, -1)
        val hasStoredSession = !storedToken.isNullOrBlank() && !storedUserId.isNullOrBlank()
        when {
            hasStoredSession && storedVersion != BuildConfig.VERSION_CODE -> {
                clearStoredSession(preferences)
                sessionNotice = "앱 업데이트로 안전하게 로그아웃되었습니다. 다시 로그인해주세요."
            }
            hasStoredSession && lastActiveAt > 0L && now - lastActiveAt >= INACTIVITY_TIMEOUT_MS -> {
                clearStoredSession(preferences)
                sessionNotice = "${INACTIVITY_DAYS}일 동안 사용하지 않아 자동 로그아웃되었습니다."
            }
            else -> {
                sessionToken = storedToken
                sessionUserId = storedUserId
            }
        }
        preferences.edit()
            .putInt(KEY_APP_VERSION, BuildConfig.VERSION_CODE)
            .putLong(KEY_LAST_ACTIVE_AT, now)
            .apply()
        if (supabase.isConfigured) {
            currentUser().verified = sessionUserId?.let { userId ->
                preferences
                    .getBoolean(identityVerificationKey(userId), false)
            } ?: false
            if (!sessionToken.isNullOrBlank() && !currentUser().verified) {
                verificationStep = VerificationStep.CONSENT
            }
        }
        buildShell()
        render()
        sessionNotice?.let(::toast)
        if (!sessionToken.isNullOrBlank() && !preferences.getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()) {
            refreshPersistedSession()
        } else {
            loadRemoteItems()
        }
    }

    override fun onStop() {
        super.onStop()
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ACTIVE_AT, System.currentTimeMillis())
            .putInt(KEY_APP_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == TOSS_PAYMENT_REQUEST) {
            handleTossPaymentResult(resultCode, data)
            return
        }
        if (resultCode != RESULT_OK || requestCode !in listOf(beforePhotoRequest, afterPhotoRequest)) return
        val uri = data?.data ?: return
        val token = sessionToken ?: return
        val userId = sessionUserId ?: return
        val contractId = pendingPhotoContractId ?: return
        val phase = pendingPhotoPhase ?: return
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null) {
            toast("사진을 읽을 수 없습니다.")
            return
        }
        toast("상태 사진을 업로드 중입니다.")
        Thread {
            val result = runCatching {
                supabase.uploadConditionPhoto(contractId, phase, userId, bytes, mimeType, token)
            }
            runOnUiThread {
                result.onSuccess {
                    loadRemoteReservations()
                    toast("상태 사진이 서버에 저장되었습니다.")
                }.onFailure { error -> toast("사진 업로드 실패: ${error.message ?: "파일과 권한을 확인하세요."}") }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (verificationStep != VerificationStep.NONE) {
            verificationStep = when (verificationStep) {
                VerificationStep.IDENTITY -> VerificationStep.PHONE
                VerificationStep.PHONE -> VerificationStep.CONSENT
                else -> VerificationStep.NONE
            }
            render()
            return
        }
        if (authPanel != AuthPanel.CLOSED) {
            authPanel = AuthPanel.CLOSED
            render()
            return
        }
        if (screen == Screen.CHAT && chatDetailOpen) {
            chatDetailOpen = false
            render()
            return
        }
        if (screen in setOf(Screen.FILTER, Screen.DETAIL)) {
            screen = Screen.HOME
            render()
            return
        }
        if (screen in setOf(Screen.TRANSACTION, Screen.QR, Screen.FEEDBACK, Screen.NOTIFICATIONS, Screen.ADMIN)) {
            screen = when (screen) {
                Screen.NOTIFICATIONS -> Screen.HOME
                Screen.ADMIN -> Screen.PROFILE
                else -> Screen.RESERVATIONS
            }
            render()
            return
        }
        super.onBackPressed()
    }

    private fun handleTossPaymentResult(resultCode: Int, data: Intent?) {
        val contractId = pendingPaymentContractId
        pendingPaymentContractId = null
        val contract = contracts.find { it.id == contractId }
        if (contract == null) {
            toast("결제 대상 거래를 찾을 수 없습니다.")
            return
        }

        if (resultCode != RESULT_OK || data == null) {
            val message = data?.getStringExtra(TossPaymentActivity.RESULT_ERROR_MESSAGE)
                ?: "결제가 취소되었습니다."
            toast(message)
            return
        }

        val paymentKey = data.getStringExtra(TossPaymentActivity.RESULT_PAYMENT_KEY).orEmpty()
        val orderId = data.getStringExtra(TossPaymentActivity.RESULT_ORDER_ID).orEmpty()
        val amount = data.getLongExtra(TossPaymentActivity.RESULT_AMOUNT, 0L)
        confirmPayment(contract, paymentKey, orderId, amount)
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(match, match)
        }

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            addView(content)
        }
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.WHITE, 0)
        }
        root.addView(scroll)
        root.addView(bottomNav, LinearLayout.LayoutParams(match, dp(66)))
        setContentView(root)
    }

    private fun render() {
        content.removeAllViews()
        bottomNav.removeAllViews()
        val showInitialAuth = supabase.isConfigured && sessionToken.isNullOrBlank()
        val showVerification = supabase.isConfigured &&
            !sessionToken.isNullOrBlank() &&
            verificationStep != VerificationStep.NONE
        bottomNav.visibility = if (showInitialAuth || showVerification) View.GONE else View.VISIBLE
        if (showInitialAuth) {
            if (authPanel == AuthPanel.CLOSED) renderAuthLanding() else renderAuthPage()
            return
        }
        if (showVerification) {
            renderVerificationPage()
            return
        }
        val customScreen = screen in setOf(Screen.HOME, Screen.FILTER, Screen.MAP, Screen.DETAIL)
        bottomNav.visibility = if (screen in setOf(Screen.FILTER, Screen.DETAIL, Screen.NOTIFICATIONS, Screen.ADMIN)) View.GONE else View.VISIBLE
        if (!customScreen) {
            renderHeader()
        }
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.FILTER -> renderFilter()
            Screen.MAP -> renderMap()
            Screen.DETAIL -> renderItemDetail()
            Screen.REGISTER -> renderRegister()
            Screen.RESERVATIONS -> renderReservations()
            Screen.TRANSACTION -> renderTransaction()
            Screen.CHAT -> renderChat()
            Screen.QR -> renderQr()
            Screen.FEEDBACK -> renderFeedback()
            Screen.NOTIFICATIONS -> renderNotifications()
            Screen.PROFILE -> renderProfile()
            Screen.ADMIN -> renderAdmin()
        }
        renderBottomNav()
    }

    private fun renderHeader() {
        content.addView(row {
            if (screen in setOf(Screen.TRANSACTION, Screen.QR, Screen.FEEDBACK, Screen.NOTIFICATIONS, Screen.ADMIN)) {
                addView(coloredButton("‹", Color.rgb(245, 247, 251), text) {
                    screen = if (screen == Screen.NOTIFICATIONS) Screen.HOME else if (screen == Screen.ADMIN) Screen.PROFILE else Screen.RESERVATIONS
                    render()
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(space(10))
            }
            addView(column {
                addView(textView(screen.title, 23, true, text))
                addView(textView(screen.subtitle, 12, false, muted).withTop(2))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            if (screen == Screen.PROFILE) addView(coloredButton("⚙", Color.rgb(245, 247, 251), text) {
                toast("설정은 준비 중입니다.")
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
        })
        if (screen in setOf(Screen.REGISTER, Screen.RESERVATIONS, Screen.CHAT)) {
            content.addView(textView("안전한 이웃 간 대여 · ShareGuard", 12, true, blue).withTop(8))
        }
    }

    private fun openScreen(target: Screen) {
        screen = target
        if (target == Screen.CHAT) chatDetailOpen = false
        render()
    }

    private fun activeContract(): Contract? = activeContractId?.let { id -> contracts.find { it.id == id } }
        ?: contracts.lastOrNull()

    private fun activeReservation(): Reservation? = activeContract()?.let { contract ->
        reservations.find { it.id == contract.reservationId }
    }

    private fun transactionProgress(current: Int): LinearLayout = row {
        listOf("결제", "계약", "기록", "QR", "반납").forEachIndexed { index, label ->
            val active = index <= current
            addView(textView(if (active) "●\n$label" else "○\n$label", 11, true, if (active) blue else muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, wrap, 1f))
        }
    }

    private fun renderHomeAuth() {
        val user = currentUser()
        if (supabase.isConfigured) {
            val signedIn = !sessionToken.isNullOrBlank()
            content.addView(row {
                addView(textView("ShareGuard", 20, true, blue), LinearLayout.LayoutParams(0, wrap, 1f))
                if (signedIn) {
                    addView(statusChip(if (currentUser().verified) "인증 완료" else "인증 필요"))
                    addView(space(8))
                    addView(button("로그아웃", secondary = true) { signOut() }, LinearLayout.LayoutParams(dp(88), dp(40)))
                } else {
                    addView(button("로그인", secondary = authPanel != AuthPanel.LOGIN) {
                        authPanel = AuthPanel.LOGIN
                        render()
                    }, LinearLayout.LayoutParams(dp(82), dp(40)))
                    addView(space(8))
                    addView(button("회원가입", secondary = authPanel != AuthPanel.SIGN_UP) {
                        authPanel = AuthPanel.SIGN_UP
                        render()
                    }, LinearLayout.LayoutParams(dp(96), dp(40)))
                }
            })

            return
        }

        val box = card(if (user.verified) Color.rgb(234, 248, 239) else Color.rgb(255, 245, 221))
        box.addView(textView(if (user.verified) "본인 인증 완료" else "본인 인증 필요", 14, true, if (user.verified) good else warn))
        box.addView(textView(if (user.verified) "데모 모드입니다. Supabase 설정을 추가하면 실제 DB에 저장됩니다." else "AUTH-03 기준으로 미인증 사용자는 등록과 거래가 차단됩니다.", 13, false, muted).withTop(2))
        box.addView(button(if (user.verified) "인증 해제" else "본인 인증 완료", secondary = user.verified) {
            user.verified = !user.verified
            render()
        }.withTop(10))
        content.addView(box.withTop(14))
    }

    private fun renderAuthLanding() {
        content.addView(textView("🛡", 34, true, Color.WHITE).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(99, 91, 255), 14)
        }.also {
            it.layoutParams = ViewGroup.MarginLayoutParams(dp(72), dp(72)).apply {
                topMargin = dp(40)
            }
        })
        content.addView(textView("ShareGuard", 32, true, text).withTop(22))
        content.addView(textView("안전한 이웃 간 대여, 쉐어가드", 14, false, muted).withTop(5))

        content.addView(card().apply {
            background = gradientRounded(
                intArrayOf(Color.rgb(232, 229, 255), Color.rgb(232, 248, 242)),
                18
            )
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(28), dp(18), dp(28))
            addView(textView("🔐", 38, true, text).apply { gravity = Gravity.CENTER })
            addView(textView("TRUST · ESCROW · VERIFY", 14, true, Color.rgb(99, 91, 255)).apply {
                gravity = Gravity.CENTER
            }.withTop(10))
        }.withTop(32))

        content.addView(coloredButton("카카오로 시작하기", Color.rgb(254, 229, 0), Color.rgb(25, 25, 25)) {
            toast("카카오 로그인은 OAuth 설정 후 연결됩니다.")
        }.withTop(24))
        content.addView(coloredButton("네이버로 시작하기", Color.rgb(3, 199, 90), Color.WHITE) {
            toast("네이버 로그인은 OAuth 설정 후 연결됩니다.")
        }.withTop(10))
        content.addView(coloredButton("Apple로 계속하기", Color.BLACK, Color.WHITE) {
            toast("Apple 로그인은 OAuth 설정 후 연결됩니다.")
        }.withTop(10))
        content.addView(button("이메일로 회원가입", secondary = true) {
            authPanel = AuthPanel.SIGN_UP
            render()
        }.withTop(18))
        content.addView(button("이미 계정이 있나요? 로그인", secondary = true) {
            authPanel = AuthPanel.LOGIN
            render()
        }.withTop(10))
    }

    private fun renderAuthPage() {
        val createAccount = authPanel == AuthPanel.SIGN_UP
        val email = edit("").apply {
            hint = "example@email.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = edit("").apply {
            hint = "8자 이상 비밀번호"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        content.addView(row {
            addView(button("← 홈으로", secondary = true) {
                authPanel = AuthPanel.CLOSED
                render()
            }, LinearLayout.LayoutParams(dp(96), dp(40)))
            addView(textView("ShareGuard", 20, true, blue).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        })
        content.addView(textView(if (createAccount) "회원가입" else "로그인", 28, true, text).withTop(32))
        content.addView(textView(
            if (createAccount) "새 계정을 만들고 안전한 공유 거래를 시작하세요."
            else "계정에 로그인하고 물품 등록과 거래를 이어가세요.",
            14,
            false,
            muted
        ).withTop(5))
        content.addView(row {
            addView(button("로그인", secondary = createAccount) {
                authPanel = AuthPanel.LOGIN
                render()
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(space(8))
            addView(button("회원가입", secondary = !createAccount) {
                authPanel = AuthPanel.SIGN_UP
                render()
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }.withTop(22))
        content.addView(card().apply {
            addView(field("이메일", email))
            addView(field("비밀번호", password))
            addView(button(if (createAccount) "회원가입" else "로그인") {
                authenticate(email.text.toString(), password.text.toString(), createAccount)
            }.withTop(16))
        }.withTop(12))
    }

    private fun renderVerificationPage() {
        val step = verificationStep.number
        content.addView(row {
            addView(button("‹", secondary = true) {
                verificationStep = when (verificationStep) {
                    VerificationStep.IDENTITY -> VerificationStep.PHONE
                    VerificationStep.PHONE -> VerificationStep.CONSENT
                    else -> VerificationStep.NONE
                }
                render()
            }, LinearLayout.LayoutParams(dp(48), dp(42)))
            addView(textView("본인 인증", 22, true, text).apply {
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(0, dp(42), 1f).withLeftMargin(10))
            addView(textView("$step/3", 17, true, Color.rgb(99, 91, 255)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }, LinearLayout.LayoutParams(dp(52), dp(42)))
        })
        content.addView(verificationProgress(step).withTop(18))

        when (verificationStep) {
            VerificationStep.CONSENT -> renderVerificationConsent()
            VerificationStep.PHONE -> renderVerificationPhone()
            VerificationStep.IDENTITY -> renderVerificationIdentity()
            VerificationStep.NONE -> Unit
        }
    }

    private fun renderVerificationConsent() {
        content.addView(textView("안전한 거래 인증을 시작해요", 26, true, text).withTop(34))
        content.addView(textView("거래 당사자 확인과 보증금 보호를 위해 필요한 정보만 확인합니다.", 14, false, muted).withTop(6))
        content.addView(card(Color.rgb(247, 246, 255)).apply {
            addView(textView("1  휴대폰 소유 확인", 16, true, text))
            addView(textView("인증번호로 사용 중인 휴대폰을 확인합니다.", 13, false, muted).withTop(4))
        }.withTop(24))
        content.addView(card(Color.rgb(247, 246, 255)).apply {
            addView(textView("2  기본 신원 정보 확인", 16, true, text))
            addView(textView("이름과 생년월일을 입력해 거래 당사자를 확인합니다.", 13, false, muted).withTop(4))
        }.withTop(10))
        content.addView(card(Color.rgb(247, 246, 255)).apply {
            addView(textView("3  인증 배지 발급", 16, true, text))
            addView(textView("완료 후 등록, 예약, 결제 기능이 활성화됩니다.", 13, false, muted).withTop(4))
        }.withTop(10))
        content.addView(button("동의하고 계속") {
            verificationStep = VerificationStep.PHONE
            render()
        }.withTop(28))
        content.addView(button("나중에 하기", secondary = true) {
            verificationStep = VerificationStep.NONE
            render()
        }.withTop(10))
    }

    private fun renderVerificationPhone() {
        content.addView(textView("안전 거래 인증을 진행해요", 26, true, text).withTop(34))
        content.addView(textView("보증금 결제와 물품 등록은 인증 완료 후 가능합니다.", 14, false, muted).withTop(6))

        val phone = edit("").apply {
            hint = "010-1234-5678"
            inputType = InputType.TYPE_CLASS_PHONE
        }
        val code = edit("").apply {
            hint = "6자리 입력"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(column {
            addView(textView("휴대폰 번호", 14, true, text))
            addView(row {
                addView(edit("+82").apply { isEnabled = false }, LinearLayout.LayoutParams(dp(92), dp(52)))
                addView(space(10))
                addView(phone, LinearLayout.LayoutParams(0, dp(52), 1f))
            }.withTop(8))
        }.withTop(28))
        content.addView(column {
            addView(textView("인증번호", 14, true, text))
            addView(row {
                addView(code, LinearLayout.LayoutParams(0, dp(52), 1f))
                addView(space(10))
                addView(button("전송") {
                    if (phone.text.toString().filter(Char::isDigit).length < 10) {
                        toast("휴대폰 번호를 확인하세요.")
                    } else {
                        verificationCodeSent = true
                        toast("프로토타입 인증번호는 123456입니다.")
                    }
                }, LinearLayout.LayoutParams(dp(82), dp(52)))
            }.withTop(8))
        }.withTop(20))
        content.addView(card(Color.rgb(247, 246, 255)).apply {
            addView(textView("다음 단계", 15, true, text))
            addView(textView("이름과 생년월일로 거래 당사자 여부를 확인합니다.", 13, false, muted).withTop(4))
        }.withTop(18))
        content.addView(button("인증 계속하기") {
            if (!verificationCodeSent || code.text.toString() != "123456") {
                toast("전송 후 인증번호 123456을 입력하세요.")
                return@button
            }
            verificationStep = VerificationStep.IDENTITY
            render()
        }.withTop(28))
    }

    private fun renderVerificationIdentity() {
        content.addView(textView("마지막 신원 정보를 확인해요", 26, true, text).withTop(34))
        content.addView(textView("입력한 정보는 인증 상태 확인에만 사용됩니다.", 14, false, muted).withTop(6))
        val name = edit("").apply { hint = "실명 입력" }
        val birthDate = edit("").apply {
            hint = "생년월일 6자리"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        content.addView(card().apply {
            addView(field("이름", name))
            addView(field("생년월일", birthDate))
        }.withTop(24))
        content.addView(card(Color.rgb(234, 248, 239)).apply {
            addView(textView("인증 완료 후 제공되는 기능", 15, true, good))
            addView(textView("물품 등록 · 예약 · 보증금 결제 · 인증 배지", 13, false, muted).withTop(5))
        }.withTop(12))
        content.addView(button("본인 인증 완료") {
            if (name.text.toString().trim().length < 2 || birthDate.text.toString().length != 6) {
                toast("이름과 생년월일 6자리를 확인하세요.")
                return@button
            }
            completeIdentityVerification()
        }.withTop(28))
    }

    private fun renderHome() {
        content.addView(row {
            addView(textView("📍  강남구 역삼동", 19, true, text), LinearLayout.LayoutParams(0, dp(44), 1f))
            val unread = notifications.count { it.unread }
            addView(coloredButton(if (unread > 0) "🔔 $unread" else "🔔", Color.rgb(245, 247, 251), text) {
                screen = Screen.NOTIFICATIONS
                render()
            }, LinearLayout.LayoutParams(dp(if (unread > 0) 72 else 48), dp(44)))
        })

        val search = edit(searchQuery).apply {
            hint = "🔎 어떤 물품을 찾으시나요?"
            setSingleLine(true)
        }
        content.addView(row {
            addView(search, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(space(8))
            addView(button("검색") {
                searchQuery = search.text.toString().trim()
                render()
            }, LinearLayout.LayoutParams(dp(76), dp(52)))
        }.withTop(12))

        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row {
                listOf("전체", "카메라", "게임기", "캠핑", "IT기기").forEachIndexed { index, category ->
                    addView(categoryChip(category, selectedCategory == category) {
                        selectedCategory = category
                        render()
                    })
                    if (index != 4) addView(space(8))
                }
            })
        }.withTop(14))

        content.addView(row {
            addView(categoryChip("거리순", sortMode == "거리순") {
                sortMode = "거리순"
                render()
            })
            addView(space(8))
            addView(categoryChip("가격순", sortMode == "가격순") {
                sortMode = "가격순"
                render()
            })
            addView(space(8))
            addView(button("필터", secondary = true) {
                screen = Screen.FILTER
                render()
            }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(space(8))
            addView(button("＋") {
                screen = Screen.REGISTER
                render()
            }, LinearLayout.LayoutParams(dp(52), dp(42)))
        }.withTop(16))

        val results = filteredItems()
        content.addView(textView("주변 ${results.size}개의 대여 가능 물품", 14, true, muted).withTop(18))
        if (results.isEmpty()) {
            content.addView(empty("조건에 맞는 물품이 없습니다. 필터를 조정해 보세요.").withTop(10))
        } else {
            results.forEach { item -> content.addView(homeItemRow(item).withTop(10)) }
        }
    }

    private fun homeItemRow(item: Item): LinearLayout {
        val owner = ownerOf(item)
        return card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedItemId = item.id
                screen = Screen.DETAIL
                render()
            }
            addView(row {
                addView(textView(itemEmoji(item), 32, false, text).apply {
                    gravity = Gravity.CENTER
                    background = rounded(item.tone, 14)
                }, LinearLayout.LayoutParams(dp(94), dp(94)))
                addView(column {
                    addView(textView(item.title, 17, true, text))
                    addView(textView("${item.location} · ${distanceLabel(item.distance)} · ⭐ ${owner.rating}", 12, false, muted).withTop(4))
                    addView(textView("${money(item.price)} /일", 17, true, Color.rgb(99, 91, 255)).withTop(8))
                    addView(textView("보증금 ${money(item.deposit)}", 12, false, muted).withTop(2))
                }, LinearLayout.LayoutParams(0, wrap, 1f).withLeftMargin(12))
            })
        }
    }

    private fun renderFilter() {
        content.addView(row {
            addView(button("×", secondary = true) {
                screen = Screen.HOME
                render()
            }, LinearLayout.LayoutParams(dp(48), dp(42)))
            addView(textView("검색 필터", 22, true, text).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(42), 1f).withLeftMargin(10))
            addView(button("초기화", secondary = true) {
                selectedCategory = "전체"
                sortMode = "거리순"
                maxDistanceKm = 10
                searchQuery = ""
                render()
            }, LinearLayout.LayoutParams(dp(88), dp(42)))
        })
        content.addView(textView("카테고리", 18, true, text).withTop(30))
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row {
                listOf("전체", "카메라", "게임기", "캠핑", "IT기기").forEachIndexed { index, category ->
                    addView(categoryChip(category, selectedCategory == category) {
                        selectedCategory = category
                        render()
                    })
                    if (index != 4) addView(space(8))
                }
            })
        }.withTop(12))
        content.addView(textView("거리", 18, true, text).withTop(30))
        content.addView(row {
            listOf(1, 3, 5, 10).forEachIndexed { index, distance ->
                addView(categoryChip("${distance}km", maxDistanceKm == distance) {
                    maxDistanceKm = distance
                    render()
                }, LinearLayout.LayoutParams(0, dp(44), 1f))
                if (index != 3) addView(space(8))
            }
        }.withTop(12))
        content.addView(textView("정렬", 18, true, text).withTop(30))
        content.addView(row {
            addView(categoryChip("가까운 순", sortMode == "거리순") {
                sortMode = "거리순"
                render()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(space(10))
            addView(categoryChip("낮은 가격순", sortMode == "가격순") {
                sortMode = "가격순"
                render()
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
        }.withTop(12))
        content.addView(button("결과 ${filteredItems().size}개 보기") {
            screen = Screen.HOME
            render()
        }.withTop(38))
    }

    private fun renderMap() {
        content.addView(row {
            addView(textView("🗺  주변 물품 지도", 21, true, text), LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button("필터", secondary = true) {
                screen = Screen.FILTER
                render()
            }, LinearLayout.LayoutParams(dp(80), dp(44)))
        })
        content.addView(FrameLayout(this).apply {
            background = rounded(Color.rgb(214, 227, 241), 16)
            addView(textView("╱  ╲  ╱  ╲  ╱\n  ╲  ╱  ╲  ╱\n╱  ╲  ╱  ╲  ╱\n  ╲  ╱  ╲  ╱", 34, false, Color.WHITE).apply {
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(match, match))
            listOf(
                Triple("5만원", 44, 80),
                Triple("3만원", 230, 180),
                Triple("2.5만원", 90, 300)
            ).forEach { (label, left, top) ->
                addView(statusChip(label), FrameLayout.LayoutParams(wrap, dp(38)).apply {
                    leftMargin = dp(left)
                    topMargin = dp(top)
                })
            }
            addView(textView("내 위치", 13, true, Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(59, 130, 246), 18)
            }, FrameLayout.LayoutParams(dp(82), dp(42)).apply {
                gravity = Gravity.CENTER
            })
        }.withTop(12).also {
            it.layoutParams = ViewGroup.MarginLayoutParams(match, dp(430)).apply { topMargin = dp(12) }
        })
        val nearby = filteredItems().take(3)
        content.addView(textView("주변 ${nearby.size}개의 대여 가능 물품", 16, true, text).withTop(20))
        nearby.forEach { item -> content.addView(homeItemRow(item).withTop(10)) }
    }

    private fun renderItemDetail() {
        val item = selectedItem()
        val owner = ownerOf(item)
        content.addView(row {
            addView(button("‹", secondary = true) {
                screen = Screen.HOME
                render()
            }, LinearLayout.LayoutParams(dp(48), dp(42)))
            addView(textView("물품 상세", 20, true, text).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(button("♡", secondary = true) { toast("관심 물품에 저장했습니다.") }, LinearLayout.LayoutParams(dp(52), dp(42)))
        })
        content.addView(FrameLayout(this).apply {
            background = rounded(item.tone, 16)
            addView(textView(itemEmoji(item), 62, false, text).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(match, match))
        }.withTop(12).also {
            it.layoutParams = ViewGroup.MarginLayoutParams(match, dp(270)).apply { topMargin = dp(12) }
        })
        content.addView(statusChip("${item.category}/대여").withTop(24))
        content.addView(textView(item.title, 25, true, text).withTop(12))
        content.addView(textView("${item.location} · ${distanceLabel(item.distance)}", 14, false, muted).withTop(5))
        content.addView(row {
            addView(textView(owner.name.take(1), 20, true, Color.WHITE).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.rgb(99, 91, 255), 24)
            }, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(column {
                addView(textView("${owner.name}  ⭐${owner.rating}", 16, true, text))
                addView(textView("대여 ${owner.deals}회 · 신뢰지수 ${owner.trust}", 13, false, muted).withTop(3))
            }, LinearLayout.LayoutParams(0, wrap, 1f).withLeftMargin(12))
            addView(button("프로필", secondary = true) {
                screen = Screen.PROFILE
                render()
            }, LinearLayout.LayoutParams(dp(86), dp(42)))
        }.withTop(20))
        content.addView(textView(item.description, 15, false, text).withTop(22))
        content.addView(card(Color.rgb(247, 246, 255)).apply {
            addView(metric("하루 대여료", money(item.price)))
            addView(metric("보증금", money(item.deposit)))
        }.withTop(22))
        content.addView(button("대여 신청하기") { createReservation(item) }.withTop(18))
    }

    private fun filteredItems(): List<Item> {
        val categoryFiltered = items.filter { item ->
            val categoryMatches = selectedCategory == "전체" ||
                item.category == selectedCategory ||
                selectedCategory == "IT기기" && item.category == "IT"
            val queryMatches = searchQuery.isBlank() || listOf(item.title, item.category, item.description, item.location)
                .any { it.contains(searchQuery, ignoreCase = true) }
            categoryMatches && queryMatches && item.distance <= maxDistanceKm * 1000
        }
        return when (sortMode) {
            "가격순" -> categoryFiltered.sortedBy(Item::price)
            else -> categoryFiltered.sortedBy(Item::distance)
        }
    }

    private fun itemEmoji(item: Item): String = when (item.category) {
        "카메라" -> "📷"
        "게임기" -> "🎮"
        "캠핑" -> "⛺"
        else -> "📽"
    }

    private fun distanceLabel(distanceMeters: Int): String = if (distanceMeters < 1000) {
        "${distanceMeters}m"
    } else {
        String.format(Locale.KOREA, "%.1fkm", distanceMeters / 1000f)
    }

    private fun renderRegister() {
        content.addView(segmentedTabs(listOf("새 물품", "내 물품"), registerTab) { tab ->
            registerTab = tab
            editingItemId = null
            render()
        }.withTop(14))
        if (registerTab == "내 물품") {
            renderMyItems()
            return
        }

        val editing = editingItemId?.let { id -> items.find { it.id == id } }
        val titleInput = edit(editing?.title ?: "물품명을 입력하세요")
        val priceInput = edit(editing?.price?.toString() ?: "50000")
        val depositInput = edit(editing?.deposit?.toString() ?: "200000")
        val locationInput = edit(editing?.location ?: "강남구 역삼동")
        val descriptionInput = edit(editing?.description ?: "구성품과 사용 상태를 자세히 적어주세요.")
        content.addView(textView(if (editing == null) "사진과 정보를 등록해요" else "물품 정보를 수정해요", 21, true, text).withTop(18))
        content.addView(textView("정확한 정보와 사진은 더 빠르고 안전한 거래로 이어집니다.", 13, false, muted).withTop(3))
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(textView("사진 등록  $registrationPhotos/5", 15, true, text))
            addView(photoSlots(registrationPhotos).withTop(10))
            addView(button(if (registrationPhotos == 0) "+ 사진 추가" else "+ 사진 더 추가", secondary = true) {
                if (registrationPhotos < 5) {
                    registrationPhotos += 1
                    render()
                } else toast("사진은 최대 5장까지 등록할 수 있습니다.")
            }.withTop(10))
        }.withTop(12))
        content.addView(textView("카테고리", 14, true, text).withTop(18))
        content.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row {
                listOf("카메라", "IT기기", "게임기", "캠핑", "생활").forEach { category ->
                    addView(categoryChip(category, registerCategory == category) {
                        registerCategory = category
                        render()
                    })
                    addView(space(8))
                }
            })
        }.withTop(8))
        content.addView(card().apply {
            addView(field("제목", titleInput))
            addView(field("설명", descriptionInput))
            addView(field("하루 대여료", priceInput))
            addView(field("보증금", depositInput))
            addView(field("거래 위치", locationInput))
            addView(button(if (editing == null) "물품 등록하기" else "수정 내용 저장") {
                if (!currentUser().verified) {
                    requestIdentityVerification("본인 인증 후 물품을 등록할 수 있습니다.")
                    return@button
                }
                val item = editing ?: Item(
                    nextItemId++, "", registerCategory, "", 0, 0, 960, "",
                    currentUser().id, Color.rgb(219, 234, 254)
                )
                item.title = titleInput.text.toString().trim()
                item.category = registerCategory
                item.description = descriptionInput.text.toString().trim()
                item.price = priceInput.text.toString().toIntOrNull() ?: 0
                item.deposit = depositInput.text.toString().toIntOrNull() ?: 0
                item.location = locationInput.text.toString().trim()
                if (editing == null) saveItem(item) else updateItem(item)
            }.withTop(12))
        }.withTop(12))
    }

    private fun renderMyItems() {
        val mine = items.filter { item ->
            item.ownerId == currentUser().id || item.remoteOwnerId != null && item.remoteOwnerId == sessionUserId
        }
        content.addView(row {
            addView(textView("내 물품 ${mine.size}", 21, true, text), LinearLayout.LayoutParams(0, wrap, 1f))
            addView(statusChip("대여 가능 ${mine.size}"))
        }.withTop(18))
        if (mine.isEmpty()) {
            content.addView(card(Color.rgb(248, 248, 255)).apply {
                addView(textView("📦", 34, true, blue).apply { gravity = Gravity.CENTER })
                addView(textView("아직 등록한 물품이 없어요", 16, true, text).apply { gravity = Gravity.CENTER }.withTop(8))
                addView(textView("사용하지 않는 물품을 안전하게 빌려주세요.", 13, false, muted).apply { gravity = Gravity.CENTER }.withTop(3))
                addView(button("첫 물품 등록하기") {
                    registerTab = "새 물품"
                    render()
                }.withTop(12))
            }.withTop(12))
            return
        }
        mine.forEach { item ->
            content.addView(card().apply {
                addView(row {
                    addView(colorBlock(itemEmoji(item), item.tone), LinearLayout.LayoutParams(dp(78), dp(78)))
                    addView(column {
                        addView(textView(item.title, 16, true, text))
                        addView(textView("${money(item.price)} / 일 · 보증금 ${money(item.deposit)}", 12, false, muted).withTop(4))
                        addView(statusChip("대여 가능").withTop(7))
                    }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
                })
                addView(row {
                    addView(button("수정", secondary = true) {
                        editingItemId = item.id
                        registerCategory = item.category
                        registerTab = "새 물품"
                        render()
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                    addView(space(8))
                    addView(button("삭제", dangerStyle = true) { deleteItem(item) }, LinearLayout.LayoutParams(0, dp(42), 1f))
                }.withTop(12))
            }.withTop(10))
        }
    }

    private fun renderReservations() {
        content.addView(segmentedTabs(listOf("빌린 물품", "빌려준 물품"), reservationTab) { tab ->
            reservationTab = tab
            render()
        }.withTop(14))
        content.addView(row {
            addView(textView("진행 중인 거래", 21, true, text), LinearLayout.LayoutParams(0, wrap, 1f))
            addView(textView("전체 ${reservations.size}", 13, true, blue))
        }.withTop(18))
        val visible = reservations.filter { reservation ->
            val item = itemById(reservation.itemId)
            val mineAsOwner = item.ownerId == currentUser().id || item.remoteOwnerId == sessionUserId
            if (reservationTab == "빌려준 물품") mineAsOwner else !mineAsOwner
        }
        if (visible.isEmpty()) {
            content.addView(card(Color.rgb(248, 248, 255)).apply {
                addView(textView("📅", 34, true, blue).apply { gravity = Gravity.CENTER })
                addView(textView(if (reservationTab == "빌린 물품") "아직 빌린 물품이 없어요" else "아직 빌려준 물품이 없어요", 16, true, text).apply { gravity = Gravity.CENTER }.withTop(8))
                addView(button(if (reservationTab == "빌린 물품") "물품 둘러보기" else "내 물품 관리") {
                    if (reservationTab == "빌린 물품") screen = Screen.HOME else {
                        screen = Screen.REGISTER
                        registerTab = "내 물품"
                    }
                    render()
                }.withTop(12))
            }.withTop(12))
        } else visible.forEach { reservation ->
            content.addView(reservationCard(reservation).withTop(10))
        }
        content.addView(card(Color.rgb(246, 245, 255)).apply {
            addView(textView("안전 거래 순서", 16, true, text))
            addView(transactionProgress(0).withTop(12))
            addView(textView("각 단계가 완료되어야 다음 단계로 이동할 수 있어요.", 12, false, muted).withTop(10))
        }.withTop(14))
    }

    private fun reservationCard(reservation: Reservation): LinearLayout {
        val item = itemById(reservation.itemId)
        val contract = contracts.find { it.reservationId == reservation.id }
        return card().apply {
            addView(row {
                addView(colorBlock(itemEmoji(item), item.tone), LinearLayout.LayoutParams(dp(68), dp(68)))
                addView(column {
                    addView(row {
                        addView(statusChip(contract?.status?.label ?: if (reservation.approved) "예약 승인" else "승인 대기"))
                        addView(textView("D-${reservation.id}", 11, true, muted), LinearLayout.LayoutParams(0, wrap, 1f).withLeftMargin(8))
                    })
                    addView(textView(item.title, 16, true, text).withTop(6))
                    addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 12, false, muted).withTop(2))
                }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
            })
            addView(metric("결제 예정 금액", money(reservation.totalAmount)).withTop(12))
            addView(row {
                when {
                    reservation.status == "rejected" -> addView(statusChip("예약 거절"), LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract == null && item.remoteOwnerId == sessionUserId -> {
                        addView(button("예약 승인") { approveReservation(reservation) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                        addView(space(8))
                        addView(button("거절", dangerStyle = true) { rejectReservation(reservation) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    }
                    contract == null && reservation.remoteId == null -> addView(button("예약 승인 시연") { approveReservation(reservation) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract == null -> addView(statusChip("대여자 승인 대기"), LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.APPROVED || contract.status == ContractStatus.PAID -> addView(button(if (contract.status == ContractStatus.APPROVED) "결제 진행" else "계약서 확인") {
                        activeContractId = contract.id
                        screen = Screen.TRANSACTION
                        render()
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.SIGNED || contract.status == ContractStatus.READY || contract.status == ContractStatus.RENTING -> addView(button("QR 인증으로 이동") {
                        activeContractId = contract.id
                        screen = Screen.QR
                        render()
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.COMPLETED -> addView(button("후기 작성") {
                        activeContractId = contract.id
                        screen = Screen.FEEDBACK
                        render()
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.DISPUTED -> addView(button("관리자 검토", secondary = true) {
                        screen = Screen.ADMIN
                        render()
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                }
                if (contract != null && contract.status == ContractStatus.RENTING) {
                    addView(space(8))
                    addView(button("분쟁 신고", dangerStyle = true) {
                        activeContractId = contract.id
                        screen = Screen.FEEDBACK
                        render()
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                }
            }.withTop(10))
            addView(button("채팅으로 일정 조율", secondary = true) {
                selectedItemId = item.id
                chatDetailOpen = true
                screen = Screen.CHAT
                render()
            }.withTop(8))
        }
    }

    private fun renderTransaction() {
        val contract = activeContract()
        val reservation = activeReservation()
        if (contract == null || reservation == null) {
            content.addView(empty("결제를 진행할 승인된 예약이 없습니다.").withTop(16))
            return
        }
        val item = itemById(reservation.itemId)
        val rentalFee = (reservation.totalAmount - item.deposit).coerceAtLeast(0)
        val paid = contract.status.ordinal >= ContractStatus.PAID.ordinal
        val signed = contract.status.ordinal >= ContractStatus.SIGNED.ordinal
        content.addView(transactionProgress(if (signed) 2 else if (paid) 1 else 0).withTop(18))
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(row {
                addView(colorBlock(itemEmoji(item), item.tone), LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(column {
                    addView(statusChip(contract.status.label))
                    addView(textView(item.title, 16, true, text).withTop(5))
                    addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 12, false, muted).withTop(2))
                }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
            })
        }.withTop(14))
        content.addView(textView("결제 금액", 19, true, text).withTop(18))
        content.addView(card().apply {
            addView(metric("대여료", money(rentalFee)))
            addView(metric("보증금", money(item.deposit)))
            addView(View(this@MainActivity).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(match, dp(1)).withBottomMargin(8))
            addView(metric("총 결제 금액", money(reservation.totalAmount), blue))
            addView(textView("🔒 보증금은 ShareGuard가 안전하게 보관하며 정상 반납 확인 후 자동 환급됩니다.", 12, false, muted).withTop(10))
            if (!paid) addView(button("${money(reservation.totalAmount)} 안전 결제") { pay(contract) }.withTop(14))
            else addView(statusChip("✓ 에스크로 결제 완료").withTop(12))
        }.withTop(10))
        if (paid) {
            content.addView(textView("전자 대여 계약", 19, true, text).withTop(18))
            content.addView(card().apply {
                addView(textView("계약 주요 내용", 15, true, text))
                addView(metric("대여 물품", item.title).withTop(8))
                addView(metric("거래 장소", item.location))
                addView(metric("파손·분실", "사진 기록 및 약관 기준으로 보증금 처리"))
                addView(textView("☑ 대여 조건과 물품 상태 기록 의무를 확인했습니다.", 12, false, text).withTop(12))
                if (!signed) addView(button("전자 서명하고 다음 단계") { sign(contract) }.withTop(12))
                else addView(button("상태 사진 기록으로 이동") {
                    activeContractId = contract.id
                    screen = Screen.QR
                    render()
                }.withTop(12))
            }.withTop(10))
        }
    }

    private fun renderChat() {
        if (!chatDetailOpen) {
            val search = edit("대화 검색")
            content.addView(search.withTop(14))
            val chatItems = listOf(items[0], items[1], items[2])
            chatItems.forEachIndexed { index, item ->
                content.addView(card().apply {
                    setOnClickListener {
                        selectedItemId = item.id
                        chatDetailOpen = true
                        render()
                    }
                    addView(row {
                        addView(colorBlock(if (index == 0) "김" else if (index == 1) "박" else "이", Color.rgb(232, 228, 255)), LinearLayout.LayoutParams(dp(54), dp(54)))
                        addView(column {
                            addView(row {
                                addView(textView(if (index == 0) "김민수" else if (index == 1) "박지영" else "이준호", 15, true, text), LinearLayout.LayoutParams(0, wrap, 1f))
                                addView(textView(if (index == 0) "2분 전" else if (index == 1) "15분 전" else "1시간 전", 11, false, muted))
                            })
                            addView(textView(item.title, 11, true, blue).withTop(2))
                            addView(textView(if (index == 0) "네, 내일 오전 10시 가능합니다!" else if (index == 1) "보증금은 언제 결제하나요?" else "대여해주셔서 감사합니다.", 12, false, muted).withTop(2))
                        }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
                        if (index == 0) addView(statusChip("2"))
                    })
                }.withTop(8))
            }
            return
        }

        content.addView(row {
            addView(coloredButton("‹", Color.rgb(245, 247, 251), text) {
                chatDetailOpen = false
                render()
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(column {
                addView(textView(ownerOf(selectedItem()).name, 17, true, text))
                addView(textView("본인 인증 완료 · 응답 빠름", 11, false, good).withTop(1))
            }.apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(statusChip("⋮"))
        }.withTop(10))
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(row {
                addView(colorBlock(itemEmoji(selectedItem()), selectedItem().tone), LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(column {
                    addView(textView(selectedItem().title, 14, true, text))
                    addView(textView("${money(selectedItem().price)} / 일 · 보증금 ${money(selectedItem().deposit)}", 11, false, muted).withTop(3))
                }.apply { setPadding(dp(10), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
                addView(button("예약하기") { createReservation(selectedItem()) }, LinearLayout.LayoutParams(dp(92), dp(42)))
            })
        }.withTop(10))
        content.addView(textView("오늘", 11, true, muted).apply { gravity = Gravity.CENTER }.withTop(14))
        chatMessages.forEach { msg -> content.addView(chatBubble(msg).withTop(8)) }
        listOf("내일 오전 10시", "역삼역 2번 출구", "3일 대여").forEach { suggestion ->
            content.addView(textView("  $suggestion  ", 11, true, blue).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = rounded(Color.rgb(239, 237, 255), 12)
            }.withTop(5))
        }
        val input = edit("메시지를 입력하세요")
        content.addView(row {
            addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(space(8))
            addView(coloredButton("➤", blue, Color.WHITE) {
                val body = input.text.toString().trim()
                if (body.isBlank()) return@coloredButton
                val token = sessionToken
                val roomId = activeChatRoomId
                if (supabase.isConfigured && token != null && roomId != null) {
                    Thread {
                        val result = runCatching { supabase.sendChatMessage(roomId, body, token) }
                        runOnUiThread {
                            result.onSuccess { loadRemoteChat(); toast("메시지가 전송되었습니다.") }
                                .onFailure { error -> toast("전송 실패: ${error.message ?: "채팅방 권한을 확인하세요."}") }
                        }
                    }.start()
                } else {
                    chatMessages.add(ChatMessage(true, body))
                    render()
                }
            }, LinearLayout.LayoutParams(dp(50), dp(48)))
        }.withTop(14))
    }

    private fun renderQr() {
        val contract = activeContract()
        if (contract == null) {
            content.addView(empty("진행 중인 계약이 없습니다. 예약 승인 후 QR 인증을 사용할 수 있습니다."))
            return
        }
        if (contract.status == ContractStatus.RENTING) qrPhase = "반납 완료"
        val isReturn = qrPhase == "반납 완료"
        content.addView(transactionProgress(if (contract.status == ContractStatus.COMPLETED) 4 else if (contract.status == ContractStatus.RENTING) 3 else 2).withTop(16))
        content.addView(segmentedTabs(listOf("대여 시작", "반납 완료"), qrPhase) { phase ->
            qrPhase = phase
            render()
        }.withTop(14))
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(textView(if (isReturn) "2. 반납 후 상태를 기록해요" else "1. 대여 전 상태를 기록해요", 19, true, text))
            addView(textView(if (isReturn) "반납 직후 같은 각도로 촬영하면 분쟁을 예방할 수 있어요." else "물품 전체와 흠집, 구성품을 최대 5장으로 남겨주세요.", 12, false, muted).withTop(4))
            val count = if (isReturn) contract.afterPhotos else contract.beforePhotos
            addView(photoSlots(count).withTop(12))
            addView(button(if (count == 0) "+ 상태 사진 촬영/선택" else "사진 추가  $count/5", secondary = true) {
                val phase = if (isReturn) "after" else "before"
                if (contract.remoteId != null && sessionToken != null) {
                    openPhotoPicker(contract, phase)
                } else if (!isReturn && contract.status == ContractStatus.SIGNED) {
                    contract.beforePhotos = 5
                    contract.status = ContractStatus.READY
                    render()
                } else if (isReturn && contract.status == ContractStatus.RENTING) {
                    contract.afterPhotos = 5
                    render()
                } else toast(if (isReturn) "대여 시작 인증 후 반납 사진을 등록할 수 있습니다." else "전자 계약 서명 후 등록할 수 있습니다.")
            }.withTop(10))
        }.withTop(12))
        content.addView(card().apply {
            addView(textView(if (isReturn) "2. QR로 반납을 확인해요" else "2. QR로 물품 수령을 확인해요", 19, true, text))
            addView(textView("상대방 화면의 QR을 스캔하거나 내 QR을 보여주세요.", 12, false, muted).withTop(4))
            addView(qrBox().withTop(12))
            addView(textView("SG-${contract.id}-${if (isReturn) "RETURN" else "START"}", 12, true, muted).apply { gravity = Gravity.CENTER }.withTop(8))
            addView(button(if (isReturn) "반납 QR 인증 완료" else "대여 시작 QR 스캔", goodStyle = isReturn) {
                if (contract.remoteId != null && sessionToken != null) {
                    verifyRemoteRental(contract, if (isReturn) "end" else "start")
                    return@button
                }
                if (!isReturn && contract.status == ContractStatus.READY && contract.beforePhotos > 0) {
                    contract.status = ContractStatus.RENTING
                    qrPhase = "반납 완료"
                    toast("대여가 시작되었습니다.")
                    render()
                } else if (isReturn && contract.status == ContractStatus.RENTING && contract.afterPhotos > 0) {
                    contract.status = ContractStatus.COMPLETED
                    contract.refunded = true
                    currentUser().deals += 1
                    currentUser().trust += 12
                    addNotification("🔒", "반납 및 환급 완료", "정상 반납이 확인되어 보증금이 환급되었습니다.", Screen.RESERVATIONS)
                    screen = Screen.FEEDBACK
                    toast("반납 완료 · 보증금 환급이 처리되었습니다.")
                    render()
                } else toast(if (isReturn) "반납 후 사진을 먼저 등록하세요." else "대여 전 사진을 먼저 등록하세요.")
            }.withTop(12))
        }.withTop(12))
        if (contract.refunded) content.addView(card(Color.rgb(236, 253, 245)).apply {
            addView(textView("✓ 보증금 환급 완료", 17, true, good))
            addView(textView("정상 반납이 확인되어 등록된 결제 수단으로 환급되었습니다.", 12, false, muted).withTop(3))
        }.withTop(12))
    }

    private fun renderFeedback() {
        val contract = activeContract()
        val reservation = activeReservation()
        if (contract == null || reservation == null) {
            content.addView(empty("후기 또는 신고를 남길 거래가 없습니다.").withTop(16))
            return
        }
        val item = itemById(reservation.itemId)
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(row {
                addView(colorBlock(itemEmoji(item), item.tone), LinearLayout.LayoutParams(dp(60), dp(60)))
                addView(column {
                    addView(textView(item.title, 16, true, text))
                    addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 12, false, muted).withTop(3))
                    addView(statusChip(contract.status.label).withTop(5))
                }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
            })
        }.withTop(14))
        content.addView(textView("거래는 어떠셨나요?", 21, true, text).withTop(18))
        content.addView(textView("후기는 상대방의 신뢰 지수에 반영됩니다.", 12, false, muted).withTop(3))
        val reviewInput = edit("물품 상태가 좋고 약속 시간이 정확했어요.")
        content.addView(card().apply {
            addView(textView("★ ★ ★ ★ ★", 30, true, warn).apply { gravity = Gravity.CENTER })
            addView(textView("5.0 · 매우 만족", 13, true, text).apply { gravity = Gravity.CENTER }.withTop(4))
            addView(field("상세 후기", reviewInput).withTop(12))
            addView(button("후기 등록하기") {
                if (contract.status != ContractStatus.COMPLETED) toast("반납 완료 후 후기를 작성할 수 있습니다.")
                else createReview(reservation, reviewInput.text.toString().trim())
            }.withTop(10))
        }.withTop(10))
        content.addView(textView("거래에 문제가 있나요?", 19, true, text).withTop(20))
        content.addView(card(Color.rgb(255, 247, 247)).apply {
            addView(textView("신고 유형", 14, true, text))
            addView(row {
                listOf("파손", "미반납", "약속 불이행").forEach { label ->
                    addView(statusChip(label), LinearLayout.LayoutParams(0, dp(34), 1f))
                    if (label != "약속 불이행") addView(space(6))
                }
            }.withTop(8))
            val reportInput = edit("문제 상황과 확인 가능한 내용을 자세히 적어주세요.")
            addView(field("신고 내용", reportInput).withTop(10))
            addView(textView("상태 사진과 채팅 기록이 분쟁 검토 자료로 함께 보존됩니다.", 11, false, danger).withTop(7))
            addView(button("분쟁 신고 접수", dangerStyle = true) {
                createReport(contract, reportInput.text.toString().trim())
            }.withTop(10))
        }.withTop(10))
    }

    private fun renderNotifications() {
        content.addView(row {
            addView(textView("새 알림 ${notifications.count { it.unread }}개", 15, true, blue), LinearLayout.LayoutParams(0, wrap, 1f))
            addView(button("모두 읽음", secondary = true) {
                notifications.forEach { it.unread = false }
                render()
            }, LinearLayout.LayoutParams(dp(112), dp(40)))
        }.withTop(14))
        content.addView(segmentedTabs(listOf("전체", "거래", "채팅"), notificationFilter) { filter ->
            notificationFilter = filter
            render()
        }.withTop(12))
        val visible = notifications.filter { notification ->
            when (notificationFilter) {
                "거래" -> notification.target in setOf(Screen.RESERVATIONS, Screen.TRANSACTION, Screen.QR, Screen.FEEDBACK)
                "채팅" -> notification.target == Screen.CHAT
                else -> true
            }
        }
        if (visible.isEmpty()) {
            content.addView(card(Color.rgb(248, 248, 255)).apply {
                addView(textView("🔕", 34, true, muted).apply { gravity = Gravity.CENTER })
                addView(textView("새 알림이 없습니다", 16, true, text).apply { gravity = Gravity.CENTER }.withTop(8))
            }.withTop(14))
        } else visible.forEach { notification ->
            content.addView(card(if (notification.unread) Color.rgb(239, 246, 255) else Color.WHITE).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    notification.unread = false
                    screen = notification.target
                    if (screen == Screen.CHAT) chatDetailOpen = false
                    render()
                }
                addView(row {
                    addView(textView(notification.icon, 23, true, blue).apply {
                        gravity = Gravity.CENTER
                        background = rounded(if (notification.unread) Color.WHITE else Color.rgb(245, 247, 251), 22)
                    }, LinearLayout.LayoutParams(dp(50), dp(50)))
                    addView(column {
                        addView(row {
                            addView(textView(notification.title, 15, true, text), LinearLayout.LayoutParams(0, wrap, 1f))
                            if (notification.unread) addView(textView("●", 10, true, blue))
                        })
                        addView(textView(notification.body, 12, false, muted).withTop(4))
                        addView(textView(notification.time, 11, false, muted).withTop(5))
                    }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
                })
            }.withTop(9))
        }
        content.addView(card().apply {
            addView(textView("알림 설정", 16, true, text))
            addView(metric("거래 상태 알림", "켜짐").withTop(8))
            addView(metric("채팅 알림", "켜짐"))
            addView(metric("혜택 및 마케팅", "꺼짐"))
        }.withTop(16))
    }

    private fun renderProfile() {
        val user = currentUser()
        content.addView(card().apply {
            addView(textView(user.name, 24, true, text))
            addView(textView("${user.role} · ${if (user.verified) "본인 인증 완료" else "본인 인증 필요"}", 14, false, muted).withTop(2))
            addView(textView("${user.trust} / 1000", 28, true, blue).withTop(14))
            addView(metric("거래 이력", "${user.deals}건"))
            addView(metric("평균 평점", user.rating.toString()))
            addView(metric("무사고 기록", "${user.noAccidentDays}일"))
            addView(metric("보증금 혜택", if (user.trust >= 850) "보증금 10% 감면 가능" else "신뢰 지수 850부터 적용"))
            if (!user.verified) {
                addView(button("본인 인증 시작") {
                    verificationStep = VerificationStep.CONSENT
                    render()
                }.withTop(14))
            }
            if (supabase.isConfigured && !sessionToken.isNullOrBlank()) {
                addView(button("로그아웃", secondary = true) { signOut() }.withTop(10))
            }
        })
        if (supabase.isConfigured && !sessionToken.isNullOrBlank()) {
            content.addView(card(Color.rgb(236, 253, 245)).apply {
                addView(textView("✓ 로그인 유지 중", 16, true, good))
                addView(textView("앱을 종료해도 자동 로그인됩니다. ${INACTIVITY_DAYS}일 미사용 또는 앱 업데이트 시에만 안전하게 로그아웃됩니다.", 12, false, muted).withTop(4))
                addView(metric("본인 인증", if (user.verified) "이 계정에 저장됨" else "인증 필요").withTop(8))
                addView(metric("토큰 관리", "자동 갱신"))
            }.withTop(12))
        }
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(textView("운영자 도구", 16, true, text))
            addView(textView("신고 접수 현황과 보증금 분쟁 처리 화면을 확인합니다.", 12, false, muted).withTop(3))
            addView(button("관리자 화면 열기", secondary = true) {
                screen = Screen.ADMIN
                selectedAdminReportId = null
                loadAdminReports()
                render()
            }.withTop(10))
        }.withTop(12))
        content.addView(sectionTitle("최근 후기", "FR-13 후기가 신뢰 지수에 반영됩니다.").withTop(16))
        content.addView(card().apply {
            addView(textView("★★★★★", 20, true, warn))
            addView(textView("카메라 상태가 좋고 약속 시간이 정확했어요.", 14, false, text).withTop(4))
            addView(textView("지수 · 2일 전", 12, false, muted).withTop(2))
        }.withTop(10))
    }

    private fun renderAdmin() {
        val allReports = if (reports.isEmpty()) listOf(demoAdminReport) else reports.toList()
        val selectedReport = selectedAdminReportId?.let { id -> allReports.find { it.id == id } }
        if (selectedReport != null) {
            renderAdminReportDetail(selectedReport)
            return
        }
        content.addView(row {
            addView(adminMetricCard("접수 대기", allReports.count { it.open }.toString(), warn), LinearLayout.LayoutParams(0, wrap, 1f))
            addView(space(8))
            addView(adminMetricCard("검토중", allReports.count { it.status == "검토중" }.toString(), blue), LinearLayout.LayoutParams(0, wrap, 1f))
            addView(space(8))
            addView(adminMetricCard("오늘 거래", "24", good), LinearLayout.LayoutParams(0, wrap, 1f))
        }.withTop(14))
        content.addView(segmentedTabs(listOf("신고 관리", "거래 현황", "사용자"), adminTab) { tab ->
            adminTab = tab
            render()
        }.withTop(14))
        when (adminTab) {
            "거래 현황" -> renderAdminTransactions()
            "사용자" -> renderAdminUsers()
            else -> {
                content.addView(row {
                    addView(textView("신고 및 분쟁", 20, true, text), LinearLayout.LayoutParams(0, wrap, 1f))
                    addView(statusChip("최신순"))
                }.withTop(18))
                allReports.forEach { report ->
                    content.addView(card(if (report.open) Color.WHITE else Color.rgb(247, 249, 251)).apply {
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            selectedAdminReportId = report.id
                            render()
                        }
                        addView(row {
                            addView(statusChip(if (report.id < 0) "샘플" else report.status))
                            addView(textView("DIS-${if (report.id < 0) "2026-014" else report.id}", 12, true, muted), LinearLayout.LayoutParams(0, wrap, 1f).withLeftMargin(8))
                            addView(textView("방금", 11, false, muted))
                        })
                        addView(textView(report.type, 17, true, text).withTop(9))
                        addView(textView(report.reason, 12, false, muted).withTop(4))
                        addView(row {
                            addView(textView("신고자 이지수", 11, false, muted), LinearLayout.LayoutParams(0, wrap, 1f))
                            addView(textView("증거 사진 5장  ›", 11, true, blue))
                        }.withTop(10))
                    }.withTop(9))
                }
            }
        }
    }

    private fun renderAdminReportDetail(report: Report) {
        content.addView(row {
            addView(button("‹ 목록", secondary = true) {
                selectedAdminReportId = null
                render()
            }, LinearLayout.LayoutParams(dp(104), dp(42)))
            addView(textView("DIS-${if (report.id < 0) "2026-014" else report.id}", 14, true, muted).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(42), 1f).withLeftMargin(10))
            addView(statusChip(if (report.open) report.status else "처리 완료"))
        }.withTop(14))
        content.addView(card(Color.rgb(255, 249, 235)).apply {
            addView(textView(report.type, 21, true, text))
            addView(textView(report.reason, 13, false, muted).withTop(6))
            addView(metric("신고자", "이지수 · 신뢰 780").withTop(12))
            addView(metric("피신고자", "김민수 · 신뢰 850"))
            addView(metric("접수 일시", "2026.08.23 13:42"))
        }.withTop(12))
        content.addView(textView("거래 및 증거 자료", 19, true, text).withTop(18))
        content.addView(card().apply {
            addView(metric("물품", "소니 A7III 풀프레임 미러리스"))
            addView(metric("에스크로 보증금", "200,000원"))
            addView(metric("상태 사진", "대여 전 5장 · 반납 후 5장"))
            addView(metric("채팅 기록", "18개 메시지 보존"))
            addView(row {
                addView(colorBlock("전", Color.rgb(219, 234, 254)), LinearLayout.LayoutParams(0, dp(92), 1f))
                addView(space(8))
                addView(colorBlock("후", Color.rgb(254, 226, 226)), LinearLayout.LayoutParams(0, dp(92), 1f))
            }.withTop(12))
        }.withTop(10))
        content.addView(textView("처리 결정", 19, true, text).withTop(18))
        content.addView(card().apply {
            addView(button("전액 환급 · 신고 기각", goodStyle = true) {
                resolveAdminReport(report, "dismissed", "물품 파손 근거가 부족하여 보증금을 전액 환급합니다.")
            })
            addView(button("일부 차감 후 종결", dangerStyle = true) {
                resolveAdminReport(report, "resolved", "수리비 50,000원을 차감하고 잔여 보증금을 환급합니다.")
            }.withTop(8))
            addView(button("추가 자료 요청", secondary = true) {
                report.status = "자료 요청"
                addNotification("📎", "관리자가 추가 자료를 요청했어요", "분쟁 검토를 위해 물품 상태 사진을 추가해주세요.", Screen.FEEDBACK)
                toast("양측 사용자에게 자료 요청 알림을 보냈습니다.")
                selectedAdminReportId = null
                render()
            }.withTop(8))
        }.withTop(10))
    }

    private fun renderAdminTransactions() {
        content.addView(textView("실시간 거래 현황", 20, true, text).withTop(18))
        listOf(
            Triple("소니 A7III", "대여중 · QR 시작 완료", good),
            Triple("PS5 디지털 에디션", "전자 계약 대기", blue),
            Triple("캠핑용 텐트 6인용", "환급 보류 · 분쟁", danger)
        ).forEach { (title, state, stateColor) ->
            content.addView(card().apply {
                addView(textView(title, 16, true, text))
                addView(textView(state, 12, true, stateColor).withTop(5))
                addView(textView("거래 당사자 및 결제 기록 확인  ›", 11, false, muted).withTop(8))
            }.withTop(9))
        }
    }

    private fun renderAdminUsers() {
        content.addView(textView("사용자 관리", 20, true, text).withTop(18))
        content.addView(edit("이름 또는 이메일 검색").withTop(10))
        content.addView(card().apply {
            addView(row {
                addView(textView("김", 20, true, Color.WHITE).apply {
                    gravity = Gravity.CENTER
                    background = rounded(Color.rgb(99, 91, 255), 26)
                }, LinearLayout.LayoutParams(dp(54), dp(54)))
                addView(column {
                    addView(textView("김민수", 16, true, text))
                    addView(textView("본인 인증 완료 · 신뢰 850 · 신고 1건", 12, false, muted).withTop(3))
                }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
            })
            addView(row {
                addView(button("거래 내역", secondary = true) { adminTab = "거래 현황"; render() }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(space(8))
                addView(button("계정 검토", dangerStyle = true) { toast("계정 검토 대상으로 표시했습니다.") }, LinearLayout.LayoutParams(0, dp(40), 1f))
            }.withTop(12))
        }.withTop(10))
    }

    private fun adminMetricCard(label: String, value: String, valueColor: Int): LinearLayout = card().apply {
        setPadding(dp(12), dp(12), dp(12), dp(12))
        addView(textView(value, 23, true, valueColor).apply { gravity = Gravity.CENTER })
        addView(textView(label, 11, false, muted).apply { gravity = Gravity.CENTER }.withTop(2))
    }

    private fun contractSummary(): LinearLayout {
        val contract = contracts.lastOrNull()
        val box = card()
        box.addView(textView("전자 계약서", 18, true, text))
        if (contract == null) {
            box.addView(textView("예약 승인 후 생성됩니다.", 13, false, muted).withTop(4))
        } else {
            val reservation = reservations.find { it.id == contract.reservationId }!!
            val item = itemById(reservation.itemId)
            box.addView(statusChip(contract.status.label).withTop(8))
            box.addView(metric("물품", item.title).withTop(8))
            box.addView(metric("기간", "${reservation.startDate} ~ ${reservation.endDate}"))
            box.addView(metric("결제", money(reservation.totalAmount)))
            if (contract.remoteId != null) {
                box.addView(metric("전자 서명", "대여자 ${if (contract.ownerSigned) "완료" else "대기"} · 차용자 ${if (contract.borrowerSigned) "완료" else "대기"}"))
            }
            box.addView(metric("상태 사진", "전 ${contract.beforePhotos}/5 · 후 ${contract.afterPhotos}/5"))
            box.addView(metric("보증금", if (contract.refunded) "환급 완료" else if (contract.refundHeld) "환급 보류" else "에스크로 보관"))
        }
        return box
    }

    private fun flowSummary(): LinearLayout {
        val contract = contracts.lastOrNull()
        val active = contract?.status
        val steps = listOf(
            ContractStatus.APPROVED to "예약 승인",
            ContractStatus.PAID to "에스크로 결제",
            ContractStatus.SIGNED to "전자 계약",
            ContractStatus.READY to "상태 사진",
            ContractStatus.RENTING to "QR 대여 인증",
            ContractStatus.COMPLETED to "반납 및 환급"
        )
        return card().apply {
            addView(textView("거래 상태 흐름", 18, true, text))
            steps.forEach { (status, label) ->
                val color = if (active == status) blue else if (active != null && status.ordinal < active.ordinal) good else muted
                addView(textView("● $label", 14, true, color).withTop(8))
            }
        }
    }

    private fun persistAuthenticatedSession(auth: AuthResult): Boolean {
        val token = auth.accessToken ?: return false
        val userId = auth.userId ?: sessionUserId ?: return false
        sessionToken = token
        sessionUserId = userId
        val editor = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putLong(KEY_EXPIRES_AT, auth.expiresAt)
            .putLong(KEY_LAST_ACTIVE_AT, System.currentTimeMillis())
            .putInt(KEY_APP_VERSION, BuildConfig.VERSION_CODE)
        auth.refreshToken?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
        editor.apply()
        return true
    }

    private fun refreshPersistedSession() {
        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        val refreshToken = preferences.getString(KEY_REFRESH_TOKEN, null) ?: run {
            loadRemoteItems()
            return
        }
        Thread {
            val result = runCatching { supabase.refreshSession(refreshToken) }
            runOnUiThread {
                result.onSuccess { auth ->
                    if (auth.isSignedIn && persistAuthenticatedSession(auth)) {
                        val userId = sessionUserId
                        currentUser().verified = userId?.let { preferences.getBoolean(identityVerificationKey(it), false) } ?: false
                        loadRemoteItems()
                    } else {
                        clearStoredSession(preferences)
                        sessionToken = null
                        sessionUserId = null
                        currentUser().verified = false
                        verificationStep = VerificationStep.NONE
                        toast("보안 세션이 만료되어 다시 로그인이 필요합니다.")
                        render()
                    }
                }.onFailure {
                    // A temporary network failure must not sign the user out.
                    loadRemoteItems()
                }
            }
        }.start()
    }

    private fun clearStoredSession(preferences: SharedPreferences) {
        preferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_LAST_ACTIVE_AT)
            .apply()
    }

    private fun authenticate(email: String, password: String, createAccount: Boolean) {
        if (!email.contains('@') || password.length < 8) {
            toast("이메일과 8자 이상 비밀번호를 입력하세요.")
            return
        }
        toast(if (createAccount) "회원가입 중입니다." else "로그인 중입니다.")
        Thread {
            val result = runCatching {
                if (createAccount) {
                    supabase.signUp(email, password, currentUser().name)
                } else {
                    supabase.signIn(email, password)
                }
            }
            runOnUiThread {
                result.onSuccess { auth ->
                    if (auth.isSignedIn) {
                        if (!persistAuthenticatedSession(auth)) return@onSuccess
                        val authenticatedUserId = sessionUserId ?: return@onSuccess
                        authPanel = AuthPanel.CLOSED
                        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
                        currentUser().verified = preferences.getBoolean(identityVerificationKey(authenticatedUserId), false)
                        verificationStep = if (currentUser().verified) VerificationStep.NONE else VerificationStep.CONSENT
                        loadRemoteItems()
                    }
                    toast(auth.message)
                    render()
                }.onFailure { error ->
                    toast(error.message ?: "Supabase 인증에 실패했습니다.")
                }
            }
        }.start()
    }

    private fun signOut() {
        val token = sessionToken
        sessionToken = null
        sessionUserId = null
        authPanel = AuthPanel.CLOSED
        verificationStep = VerificationStep.NONE
        currentUser().verified = false
        clearStoredSession(getSharedPreferences(SESSION_PREFS, MODE_PRIVATE))
        render()
        if (token != null) {
            Thread { runCatching { supabase.signOut(token) } }.start()
        }
    }

    private fun requestIdentityVerification(message: String) {
        toast(message)
        verificationStep = VerificationStep.CONSENT
        render()
    }

    private fun completeIdentityVerification() {
        val userId = sessionUserId ?: return
        currentUser().verified = true
        verificationStep = VerificationStep.NONE
        verificationCodeSent = false
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(identityVerificationKey(userId), true)
            .apply()
        screen = Screen.HOME
        toast("본인 인증이 완료되었습니다. 안전 거래 기능이 활성화됐습니다.")
        render()
    }

    private fun identityVerificationKey(userId: String): String = "identity_verified_$userId"

    private fun loadRemoteItems() {
        if (!supabase.isConfigured) return
        Thread {
            val result = runCatching { supabase.fetchItems(sessionToken) }
            runOnUiThread {
                result.onSuccess { remoteItems ->
                    val knownIds = items.mapNotNull { it.remoteId }.toSet()
                    remoteItems
                        .filterNot { it.id in knownIds }
                        .map(::remoteToItem)
                        .reversed()
                        .forEach { items.add(0, it) }
                    if (!sessionToken.isNullOrBlank()) loadRemoteReservations()
                    if (remoteItems.isNotEmpty() && screen == Screen.HOME) render()
                }.onFailure { error ->
                    toast("DB 조회 실패: ${error.message ?: "연결을 확인하세요."}")
                }
            }
        }.start()
    }

    private fun openPhotoPicker(contract: Contract, phase: String) {
        if (phase == "before" && contract.status !in listOf(ContractStatus.SIGNED, ContractStatus.READY)) {
            toast("양측 전자 서명 후 대여 전 사진을 등록할 수 있습니다.")
            return
        }
        if (phase == "after" && contract.status != ContractStatus.RENTING) {
            toast("대여중 상태에서 반납 후 사진을 등록할 수 있습니다.")
            return
        }
        pendingPhotoContractId = contract.remoteId
        pendingPhotoPhase = phase
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, if (phase == "before") beforePhotoRequest else afterPhotoRequest)
    }

    private fun verifyRemoteRental(contract: Contract, action: String) {
        val token = sessionToken ?: return
        val contractId = contract.remoteId ?: return
        toast(if (action == "start") "대여 시작을 인증 중입니다." else "반납 완료를 인증 중입니다.")
        Thread {
            val result = runCatching { supabase.verifyRental(contractId, action, token) }
            runOnUiThread {
                result.onSuccess {
                    addNotification(
                        if (action == "end") "🔒" else "📦",
                        if (action == "end") "반납 및 환급 완료" else "대여가 시작되었어요",
                        if (action == "end") "정상 반납이 확인되어 보증금 환급이 처리되었습니다." else "QR 인증이 완료되어 거래 상태가 대여중으로 변경되었습니다.",
                        Screen.RESERVATIONS
                    )
                    screen = if (action == "end") Screen.RESERVATIONS else Screen.QR
                    loadRemoteReservations()
                    toast(if (action == "end") "반납 완료와 테스트 보증금 환급이 처리되었습니다." else "대여중 상태로 변경되었습니다.")
                    render()
                }.onFailure { error -> toast("QR 인증 실패: ${error.message ?: "서명과 사진 상태를 확인하세요."}") }
            }
        }.start()
    }

    private fun loadRemoteReservations() {
        val token = sessionToken ?: return
        Thread {
            val result = runCatching {
                supabase.fetchReservations(token) to supabase.fetchContracts(token)
            }
            runOnUiThread {
                result.onSuccess { (remoteReservations, remoteContracts) ->
                    reservations.removeAll { it.remoteId != null }
                    contracts.removeAll { it.remoteId != null }
                    remoteReservations.mapNotNull(::remoteToReservation).forEach(reservations::add)
                    remoteContracts.mapNotNull(::remoteToContract).forEach(contracts::add)
                    loadRemoteChat()
                    if (screen == Screen.RESERVATIONS) render()
                }.onFailure { error ->
                    toast("예약 조회 실패: ${error.message ?: "연결을 확인하세요."}")
                }
            }
        }.start()
    }

    private fun loadRemoteChat() {
        val token = sessionToken ?: return
        val reservationId = reservations.firstOrNull { it.remoteId != null }?.remoteId ?: return
        Thread {
            val result = runCatching {
                val roomId = supabase.fetchChatRoom(reservationId, token)
                roomId to if (roomId == null) emptyList() else supabase.fetchChatMessages(roomId, token)
            }
            runOnUiThread {
                result.onSuccess { (roomId, messages) ->
                    activeChatRoomId = roomId
                    chatMessages.clear()
                    messages.forEach { message ->
                        chatMessages.add(ChatMessage(message.senderId == sessionUserId, message.body))
                    }
                    if (screen == Screen.CHAT) render()
                }
            }
        }.start()
    }

    private fun remoteToReservation(remote: RemoteReservation): Reservation? {
        val item = items.find { it.remoteId == remote.itemId } ?: return null
        return Reservation(
            id = remote.id.hashCode().ushr(1),
            itemId = item.id,
            borrowerId = currentUser().id,
            startDate = remote.startDate,
            endDate = remote.endDate,
            totalAmount = remote.totalAmount,
            approved = remote.status != "pending",
            status = remote.status,
            remoteId = remote.id,
            remoteBorrowerId = remote.borrowerId
        )
    }

    private fun remoteToContract(remote: RemoteContract): Contract? {
        val reservation = reservations.find { it.remoteId == remote.reservationId } ?: return null
        val status = when {
            remote.status == "draft" && reservation.status == "paid" -> ContractStatus.PAID
            remote.status == "draft" -> ContractStatus.APPROVED
            remote.status == "signed" && remote.beforePhotos > 0 -> ContractStatus.READY
            remote.status == "signed" -> ContractStatus.SIGNED
            remote.status == "active" -> ContractStatus.RENTING
            remote.status == "completed" -> ContractStatus.COMPLETED
            remote.status == "disputed" -> ContractStatus.DISPUTED
            else -> ContractStatus.APPROVED
        }
        return Contract(
            id = remote.id.hashCode().ushr(1),
            reservationId = reservation.id,
            status = status,
            remoteId = remote.id,
            beforePhotos = remote.beforePhotos,
            afterPhotos = remote.afterPhotos,
            refunded = remote.status == "completed",
            refundHeld = remote.status == "disputed",
            ownerSigned = remote.ownerSigned,
            borrowerSigned = remote.borrowerSigned
        )
    }

    private fun saveItem(item: Item) {
        if (!supabase.isConfigured) {
            items.add(0, item)
            registerTab = "내 물품"
            registrationPhotos = 0
            toast("데모 데이터에 물품이 등록되었습니다.")
            render()
            return
        }
        val token = sessionToken
        if (token == null) {
            toast("Supabase 로그인 후 등록할 수 있습니다.")
            return
        }
        if (item.title.length < 2 || item.location.length < 2 || item.price < 0 || item.deposit < 0) {
            toast("물품명, 가격, 보증금, 위치를 확인하세요.")
            return
        }

        toast("데이터베이스에 저장 중입니다.")
        Thread {
            val result = runCatching {
                supabase.insertItem(
                    NewRemoteItem(
                        title = item.title,
                        category = item.category,
                        description = item.description,
                        dailyPrice = item.price,
                        deposit = item.deposit,
                        address = item.location
                    ),
                    token
                )
            }
            runOnUiThread {
                result.onSuccess { remote ->
                    items.add(0, remoteToItem(remote))
                    selectedItemId = items.first().id
                    registerTab = "내 물품"
                    registrationPhotos = 0
                    toast("Supabase에 물품이 등록되었습니다.")
                    render()
                }.onFailure { error ->
                    toast("등록 실패: ${error.message ?: "DB 정책을 확인하세요."}")
                }
            }
        }.start()
    }

    private fun remoteToItem(item: RemoteItem): Item = Item(
        id = item.id.hashCode().ushr(1),
        title = item.title,
        category = item.category,
        description = item.description,
        price = item.dailyPrice,
        deposit = item.deposit,
        distance = 0,
        location = item.address,
        ownerId = 1,
        tone = when (item.category) {
            "카메라" -> Color.rgb(219, 234, 254)
            "게임기" -> Color.rgb(237, 233, 254)
            "캠핑" -> Color.rgb(220, 252, 231)
            else -> Color.rgb(254, 243, 199)
        },
        remoteId = item.id,
        remoteOwnerId = item.ownerId
    )

    private fun createReservation(item: Item) {
        if (!currentUser().verified) {
            requestIdentityVerification("본인 인증 후 예약할 수 있습니다.")
            return
        }
        val total = item.price * 3 + item.deposit
        val startDate = LocalDate.now().plusDays(1).toString()
        val endDate = LocalDate.now().plusDays(3).toString()
        val token = sessionToken
        if (supabase.isConfigured && token != null && item.remoteId != null) {
            val remoteItemId = item.remoteId
            toast("예약을 서버에 저장 중입니다.")
            Thread {
                val result = runCatching {
                    supabase.createReservation(remoteItemId, startDate, endDate, item.price * 3, item.deposit, token)
                }
                runOnUiThread {
                    result.onSuccess {
                        addNotification("📅", "예약 신청 완료", "대여자에게 예약 요청을 보냈습니다.", Screen.RESERVATIONS)
                        screen = Screen.RESERVATIONS
                        loadRemoteReservations()
                        toast("예약 신청이 서버에 저장되었습니다.")
                        render()
                    }.onFailure { error -> toast("예약 실패: ${error.message ?: "날짜와 권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        reservations.add(Reservation(nextReservationId++, item.id, currentUser().id, startDate, endDate, total))
        addNotification("📅", "예약 신청 완료", "${item.title} 예약 요청을 보냈습니다.", Screen.RESERVATIONS)
        screen = Screen.RESERVATIONS
        toast("예약 신청이 완료되었습니다.")
        render()
    }

    private fun approveReservation(reservation: Reservation) {
        val token = sessionToken
        val remoteId = reservation.remoteId
        if (supabase.isConfigured && token != null && remoteId != null) {
            toast("예약을 승인 중입니다.")
            Thread {
                val result = runCatching { supabase.approveReservation(remoteId, token) }
                runOnUiThread {
                    result.onSuccess {
                        loadRemoteReservations()
                        toast("예약 승인과 계약 생성이 완료되었습니다.")
                    }.onFailure { error -> toast("승인 실패: ${error.message ?: "권한 또는 중복 예약을 확인하세요."}") }
                }
            }.start()
            return
        }
        reservation.approved = true
        contracts.add(Contract(nextContractId++, reservation.id, ContractStatus.APPROVED))
        addNotification("✓", "예약이 승인되었어요", "안전 결제와 전자 계약을 진행해주세요.", Screen.RESERVATIONS)
        toast("예약이 승인되었습니다.")
        render()
    }

    private fun rejectReservation(reservation: Reservation) {
        val token = sessionToken ?: return
        val remoteId = reservation.remoteId ?: return
        toast("예약을 거절 중입니다.")
        Thread {
            val result = runCatching { supabase.rejectReservation(remoteId, token) }
            runOnUiThread {
                result.onSuccess {
                    loadRemoteReservations()
                    toast("예약이 거절되었습니다.")
                }.onFailure { error -> toast("거절 실패: ${error.message ?: "권한을 확인하세요."}") }
            }
        }.start()
    }

    private fun updateItem(item: Item) {
        if (item.title.length < 2 || item.location.length < 2 || item.price < 0 || item.deposit < 0) {
            toast("물품명, 가격, 보증금, 위치를 확인하세요.")
            return
        }
        val token = sessionToken
        val remoteId = item.remoteId
        if (supabase.isConfigured && token != null && remoteId != null) {
            Thread {
                val result = runCatching {
                    supabase.updateItem(
                        remoteId,
                        NewRemoteItem(item.title, item.category, item.description, item.price, item.deposit, item.location),
                        token
                    )
                }
                runOnUiThread {
                    result.onSuccess { remote ->
                        val index = items.indexOfFirst { it.id == item.id }
                        if (index >= 0) items[index] = remoteToItem(remote)
                        editingItemId = null
                        registerTab = "내 물품"
                        toast("물품 정보가 수정되었습니다.")
                        render()
                    }.onFailure { error -> toast("수정 실패: ${error.message ?: "권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        editingItemId = null
        registerTab = "내 물품"
        toast("물품 정보가 수정되었습니다.")
        render()
    }

    private fun deleteItem(item: Item) {
        val token = sessionToken
        val remoteId = item.remoteId
        if (supabase.isConfigured && token != null && remoteId != null) {
            Thread {
                val result = runCatching { supabase.hideItem(remoteId, token) }
                runOnUiThread {
                    result.onSuccess {
                        items.removeAll { it.id == item.id }
                        toast("물품이 비공개 처리되었습니다.")
                        render()
                    }.onFailure { error -> toast("삭제 실패: ${error.message ?: "진행 중인 거래를 확인하세요."}") }
                }
            }.start()
            return
        }
        items.removeAll { it.id == item.id }
        toast("물품이 삭제되었습니다.")
        render()
    }

    private fun pay(contract: Contract) {
        if (!currentUser().verified) {
            requestIdentityVerification("본인 인증 후 결제할 수 있습니다.")
            return
        }
        val reservation = reservations.find { it.id == contract.reservationId }
        if (reservation == null) {
            toast("예약 정보를 찾을 수 없습니다.")
            return
        }

        val item = itemById(reservation.itemId)
        val clientKey = BuildConfig.TOSS_CLIENT_KEY.ifBlank { TOSS_DOCS_CLIENT_KEY }
        pendingPaymentContractId = contract.id
        startActivityForResult(
            TossPaymentActivity.intent(
                context = this,
                clientKey = clientKey,
                customerKey = tossCustomerKey(),
                orderId = "shareguard-${contract.id}-${System.currentTimeMillis()}",
                orderName = item.title.take(100),
                amount = reservation.totalAmount.toLong()
            ),
            TOSS_PAYMENT_REQUEST
        )
    }

    private fun confirmPayment(contract: Contract, paymentKey: String, orderId: String, amount: Long) {
        val confirmClient = TossConfirmClient(BuildConfig.TOSS_CONFIRM_URL)
        if (!confirmClient.isConfigured) {
            completeEscrowPayment(contract, "테스트 결제 인증이 완료되었습니다.")
            return
        }

        toast("결제 승인 확인 중입니다.")
        Thread {
            val result = runCatching { confirmClient.confirm(paymentKey, orderId, amount) }
            runOnUiThread {
                result.onSuccess {
                    completeEscrowPayment(contract, "토스 결제 승인과 에스크로 예치가 완료되었습니다.")
                }.onFailure { error ->
                    toast(error.message ?: "결제 승인에 실패했습니다.")
                }
            }
        }.start()
    }

    private fun tossCustomerKey(): String {
        val prefs = getSharedPreferences("shareguard_payment", MODE_PRIVATE)
        val existing = prefs.getString("toss_customer_key", null)
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString("toss_customer_key", generated).apply()
        return generated
    }

    private fun completeEscrowPayment(contract: Contract, successMessage: String) {
        val token = sessionToken
        val remoteReservationId = reservations.find { it.id == contract.reservationId }?.remoteId
        if (supabase.isConfigured && token != null && remoteReservationId != null) {
            toast("결제 상태를 서버에 저장 중입니다.")
            Thread {
                val result = runCatching { supabase.payDemoReservation(remoteReservationId, token) }
                runOnUiThread {
                    result.onSuccess {
                        addNotification("🔒", "안전 결제 완료", "대여료와 보증금이 에스크로에 안전하게 보관되었습니다.", Screen.TRANSACTION)
                        loadRemoteReservations()
                        toast(successMessage)
                    }.onFailure { error -> toast("결제 기록 실패: ${error.message ?: "예약 상태를 확인하세요."}") }
                }
            }.start()
            return
        }
        contract.status = ContractStatus.PAID
        addNotification("🔒", "안전 결제 완료", "대여료와 보증금이 에스크로에 안전하게 보관되었습니다.", Screen.TRANSACTION)
        toast(successMessage)
        render()
    }

    private fun sign(contract: Contract) {
        val token = sessionToken
        val remoteId = contract.remoteId
        if (supabase.isConfigured && token != null && remoteId != null) {
            toast("전자 서명을 서버에 저장 중입니다.")
            Thread {
                val result = runCatching { supabase.signContract(remoteId, token) }
                runOnUiThread {
                    result.onSuccess { signed ->
                        loadRemoteReservations()
                        val complete = signed.ownerSigned && signed.borrowerSigned
                        addNotification("✍", if (complete) "전자 계약 완료" else "전자 서명 완료", if (complete) "상태 사진을 등록하고 거래를 시작해주세요." else "상대방의 서명을 기다리고 있습니다.", Screen.QR)
                        toast(if (complete) "양측 서명이 완료되었습니다." else "내 서명이 저장되었습니다. 상대방 서명을 기다립니다.")
                    }.onFailure { error -> toast("서명 실패: ${error.message ?: "결제와 권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        contract.status = ContractStatus.SIGNED
        addNotification("✍", "전자 계약 완료", "대여 전 상태 사진을 등록하고 QR 인증을 진행해주세요.", Screen.QR)
        screen = Screen.QR
        toast("전자 계약서 서명이 완료되었습니다.")
        render()
    }

    private fun addNotification(icon: String, title: String, body: String, target: Screen) {
        notifications.add(0, AppNotification(nextNotificationId++, icon, title, body, "방금", target))
    }

    private fun loadAdminReports() {
        val token = sessionToken ?: return
        if (!supabase.isConfigured) return
        Thread {
            val result = runCatching { supabase.fetchReports(token) }
            runOnUiThread {
                result.onSuccess { remoteReports ->
                    reports.removeAll { it.remoteId != null }
                    remoteReports.map(::remoteToReport).forEach(reports::add)
                    if (screen == Screen.ADMIN) render()
                }.onFailure {
                    if (screen == Screen.ADMIN) toast("관리자 계정으로 로그인하면 실제 신고 목록을 불러올 수 있습니다.")
                }
            }
        }.start()
    }

    private fun remoteToReport(remote: RemoteReport): Report {
        val reservation = reservations.find { it.remoteId == remote.reservationId }
        val contract = reservation?.let { match -> contracts.find { it.reservationId == match.id } }
        val type = when (remote.type) {
            "damage" -> "파손 의심"
            "no_show" -> "약속 불이행"
            "fraud" -> "사기 의심"
            "abuse" -> "부적절한 행동"
            else -> "기타 신고"
        }
        val status = when (remote.status) {
            "reviewing" -> "검토중"
            "resolved" -> "처리 완료"
            "dismissed" -> "기각"
            else -> "접수"
        }
        return Report(
            id = remote.id.hashCode().ushr(1),
            contractId = contract?.id ?: 0,
            type = type,
            reason = remote.reason,
            open = remote.status in setOf("open", "reviewing"),
            status = status,
            remoteId = remote.id
        )
    }

    private fun resolveAdminReport(report: Report, status: String, resolution: String) {
        val token = sessionToken
        if (report.remoteId != null && token != null && supabase.isConfigured) {
            toast("관리자 처리 결과를 저장 중입니다.")
            Thread {
                val result = runCatching { supabase.resolveReport(report.remoteId, status, resolution, token) }
                runOnUiThread {
                    result.onSuccess {
                        report.open = false
                        report.status = if (status == "dismissed") "기각" else "처리 완료"
                        addNotification("⚖", "분쟁 처리가 완료되었어요", resolution, Screen.RESERVATIONS)
                        selectedAdminReportId = null
                        toast("처리 결과가 서버에 저장되었습니다.")
                        render()
                    }.onFailure { error -> toast("처리 실패: ${error.message ?: "관리자 권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        report.open = false
        report.status = if (status == "dismissed") "기각" else "처리 완료"
        contracts.find { it.id == report.contractId }?.apply {
            this.status = ContractStatus.COMPLETED
            refundHeld = false
            refunded = true
        }
        addNotification("⚖", "분쟁 처리가 완료되었어요", resolution, Screen.RESERVATIONS)
        selectedAdminReportId = null
        toast("관리자 처리가 완료되었습니다.")
        render()
    }

    private fun createReport(contract: Contract, reason: String = "반납 후 사진에서 물품 손상이 확인되어 검토를 요청합니다.") {
        val token = sessionToken
        val reservation = reservations.find { it.id == contract.reservationId }
        val item = reservation?.let { itemById(it.itemId) }
        val reportedUserId = when (sessionUserId) {
            reservation?.remoteBorrowerId -> item?.remoteOwnerId
            else -> reservation?.remoteBorrowerId
        }
        if (supabase.isConfigured && token != null && reservation?.remoteId != null && reportedUserId != null) {
            toast("분쟁 신고를 서버에 접수 중입니다.")
            Thread {
                val result = runCatching {
                    supabase.openDispute(
                        reservation.remoteId,
                        reportedUserId,
                        reason.ifBlank { "거래 중 문제가 발생해 검토를 요청합니다." },
                        token
                    )
                }
                runOnUiThread {
                    result.onSuccess {
                        loadRemoteReservations()
                        addNotification("⚠", "분쟁 신고 접수", "신고가 접수되어 보증금 환급이 보류되었습니다.", Screen.FEEDBACK)
                        toast("신고가 접수되고 거래 상태가 분쟁중으로 변경되었습니다.")
                    }.onFailure { error -> toast("신고 실패: ${error.message ?: "거래 권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        contract.status = ContractStatus.DISPUTED
        contract.refundHeld = true
        reports.add(Report(nextReportId++, contract.id, "거래 문제", reason.ifBlank { "거래 중 문제가 발생해 검토를 요청합니다." }))
        addNotification("⚠", "분쟁 신고 접수", "관리자가 상태 사진과 채팅 기록을 검토합니다.", Screen.FEEDBACK)
        screen = Screen.ADMIN
        toast("신고가 접수되고 환급이 보류되었습니다.")
        render()
    }

    private fun createReview(reservation: Reservation, comment: String) {
        val token = sessionToken
        val item = itemById(reservation.itemId)
        val revieweeId = when (sessionUserId) {
            reservation.remoteBorrowerId -> item.remoteOwnerId
            else -> reservation.remoteBorrowerId
        }
        if (supabase.isConfigured && token != null && reservation.remoteId != null && revieweeId != null) {
            toast("후기를 서버에 저장 중입니다.")
            Thread {
                val result = runCatching {
                    supabase.createReview(
                        reservation.remoteId,
                        revieweeId,
                        5,
                        comment.ifBlank { "안전하고 만족스러운 거래였습니다." },
                        token
                    )
                }
                runOnUiThread {
                    result.onSuccess {
                        screen = Screen.PROFILE
                        toast("후기가 등록되었습니다.")
                        render()
                    }.onFailure { error -> toast("후기 등록 실패: ${error.message ?: "이미 작성했는지 확인하세요."}") }
                }
            }.start()
            return
        }
        currentUser().trust += 8
        screen = Screen.PROFILE
        toast("후기가 등록되고 신뢰 지수가 반영되었습니다.")
        render()
    }

    private fun resetDemo() {
        screen = Screen.HOME
        selectedItemId = 501
        users[1].verified = false
        verificationStep = VerificationStep.NONE
        verificationCodeSent = false
        users[1].trust = 780
        users[1].deals = 12
        reservations.clear()
        contracts.clear()
        reports.clear()
        chatMessages.clear()
        chatMessages.add(ChatMessage(false, "가능합니다. 5월 1일부터 3일까지 비워둘게요."))
        chatMessages.add(ChatMessage(true, "거래 장소는 역삼역 근처가 괜찮습니다."))
        toast("데모 상태가 초기화되었습니다.")
        render()
    }

    private fun renderBottomNav() {
        val entries = listOf(
            Screen.HOME to "⌂\n홈",
            Screen.MAP to "▧\n지도",
            Screen.CHAT to "●\n채팅",
            Screen.RESERVATIONS to "▣\n예약",
            Screen.PROFILE to "♟\n내정보"
        )
        entries.forEach { (target, label) ->
            bottomNav.addView(Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setSingleLine(false)
                setTextColor(if (screen == target) blue else muted)
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(if (screen == target) Color.rgb(234, 241, 255) else Color.TRANSPARENT, 8)
                setOnClickListener {
                    screen = target
                    if (target == Screen.CHAT) chatDetailOpen = false
                    render()
                }
            }, LinearLayout.LayoutParams(0, match, 1f))
        }
    }

    private fun selectedItem(): Item = itemById(selectedItemId)
    private fun itemById(id: Int): Item = items.first { it.id == id }
    private fun currentUser(): User = users[1]
    private fun ownerOf(item: Item): User = users.first { it.id == item.ownerId }

    private fun sectionTitle(title: String, subtitle: String): LinearLayout = column {
        addView(textView(title, 18, true, text))
        addView(textView(subtitle, 13, false, muted).withTop(3))
    }

    private fun segmentedTabs(labels: List<String>, selected: String, onSelect: (String) -> Unit): LinearLayout = row {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = rounded(Color.rgb(238, 241, 247), 12)
        labels.forEach { label ->
            addView(coloredButton(
                label,
                if (label == selected) Color.WHITE else Color.TRANSPARENT,
                if (label == selected) blue else muted
            ) { onSelect(label) }, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
    }

    private fun statusChip(label: String): TextView = textView(label, 12, true, blue).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(Color.rgb(234, 241, 255), 8, Color.rgb(201, 219, 255))
    }

    private fun categoryChip(label: String, selected: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Color.WHITE else this@MainActivity.text)
            background = rounded(if (selected) Color.rgb(99, 91, 255) else Color.WHITE, 18, if (selected) Color.rgb(99, 91, 255) else line)
            setPadding(dp(14), 0, dp(14), 0)
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(42)
            setOnClickListener { onClick() }
        }

    private fun metric(label: String, value: String, valueColor: Int = text): LinearLayout = row {
        addView(textView(label, 14, false, muted), LinearLayout.LayoutParams(0, wrap, 1f))
        addView(textView(value, 14, true, valueColor))
    }.withTop(8)

    private fun field(label: String, view: View): LinearLayout = column {
        addView(textView(label, 13, true, muted))
        addView(view.withTop(5))
    }.withTop(10)

    private fun verificationProgress(activeStep: Int): LinearLayout = row {
        repeat(3) { index ->
            addView(View(this@MainActivity).apply {
                background = rounded(
                    if (index < activeStep) Color.rgb(99, 91, 255) else Color.rgb(226, 229, 236),
                    4
                )
            }, LinearLayout.LayoutParams(0, dp(8), 1f))
            if (index != 2) addView(space(8))
        }
    }

    private fun photoSlots(filled: Int): LinearLayout = row {
        repeat(5) { index ->
            addView(TextView(this@MainActivity).apply {
                text = if (index < filled) "촬영" else "+"
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (index < filled) teal else muted)
                background = rounded(if (index < filled) Color.rgb(231, 246, 243) else Color.rgb(248, 250, 252), 8, line)
            }, LinearLayout.LayoutParams(0, dp(58), 1f))
            if (index != 4) addView(space(6))
        }
    }

    private fun qrBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(Color.WHITE, 8, line)
        repeat(7) { rowIndex ->
            addView(row {
                repeat(7) { colIndex ->
                    val dark = (rowIndex + colIndex) % 2 == 0 || rowIndex in listOf(0, 1, 5) && colIndex in listOf(0, 1, 5)
                    addView(View(this@MainActivity).apply {
                        background = rounded(if (dark) Color.rgb(17, 24, 39) else Color.TRANSPARENT, 2)
                    }, LinearLayout.LayoutParams(dp(16), dp(16)).withRightMargin(4).withBottomMargin(4))
                }
            })
        }
    }

    private fun colorBlock(label: String, color: Int): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(blue)
        background = rounded(color, 8)
    }

    private fun chatBubble(message: ChatMessage): TextView = textView(message.text, 14, false, if (message.mine) Color.WHITE else text).apply {
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(if (message.mine) blue else Color.WHITE, 8, if (message.mine) blue else line)
        val width = (resources.displayMetrics.widthPixels * 0.72f).roundToInt()
        layoutParams = LinearLayout.LayoutParams(width, wrap).apply {
            gravity = if (message.mine) Gravity.END else Gravity.START
        }
    }

    private fun empty(textValue: String): TextView = textView(textValue, 14, false, muted).apply {
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(28), dp(18), dp(28))
        background = rounded(Color.rgb(248, 250, 252), 8, line)
    }

    private fun edit(value: String): EditText = EditText(this).apply {
        setText(value)
        textSize = 14f
        setSingleLine(false)
        minHeight = dp(42)
        setPadding(dp(10), 0, dp(10), 0)
        background = rounded(Color.WHITE, 8, line)
    }

    private fun textView(value: String, sp: Int, bold: Boolean, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = sp.toFloat()
        setTextColor(color)
        includeFontPadding = true
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, secondary: Boolean = false, dangerStyle: Boolean = false, goodStyle: Boolean = false, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            val fill = when {
                dangerStyle -> danger
                goodStyle -> good
                secondary -> Color.WHITE
                else -> blue
            }
            setTextColor(if (secondary) this@MainActivity.text else Color.WHITE)
            background = rounded(fill, 8, if (secondary) line else fill)
            setOnClickListener { onClick() }
        }

    private fun coloredButton(label: String, fill: Int, labelColor: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(labelColor)
            background = rounded(fill, 12, fill)
            minHeight = dp(52)
            setOnClickListener { onClick() }
        }

    private fun card(color: Int = surface): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(color, 8, line)
    }

    private fun row(builder: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        builder()
    }

    private fun column(builder: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        builder()
    }

    private fun space(widthDp: Int): Space = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), 1)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun gradientRounded(colors: IntArray, radius: Int): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(radius).toFloat()
        }

    private fun LinearLayout.LayoutParams.withLeftMargin(value: Int): LinearLayout.LayoutParams {
        leftMargin = value
        return this
    }

    private fun LinearLayout.LayoutParams.withRightMargin(value: Int): LinearLayout.LayoutParams {
        rightMargin = value
        return this
    }

    private fun LinearLayout.LayoutParams.withBottomMargin(value: Int): LinearLayout.LayoutParams {
        bottomMargin = value
        return this
    }

    private fun <T : View> T.withTop(dpValue: Int): T {
        val existing = layoutParams as? ViewGroup.MarginLayoutParams
        layoutParams = (existing ?: ViewGroup.MarginLayoutParams(match, wrap)).apply {
            topMargin = dp(dpValue)
        }
        return this
    }

    private fun money(value: Int): String = NumberFormat.getNumberInstance(Locale.KOREA).format(value) + "원"
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

}
