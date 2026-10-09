package com.shareguard.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.text.TextWatcher
import android.text.Editable
import com.shareguard.app.data.RemoteProfile
import com.shareguard.app.data.RemoteChatRoom
import com.shareguard.app.data.RemoteDateRequest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.ClipData
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import com.shareguard.app.data.AuthResult
import com.shareguard.app.data.ChatMedia
import com.shareguard.app.data.NewRemoteItem
import com.shareguard.app.data.RemoteContract
import com.shareguard.app.data.RemoteItem
import com.shareguard.app.data.RemoteReport
import com.shareguard.app.data.RemoteReservation
import com.shareguard.app.data.SupabaseRestClient
import com.shareguard.app.data.TossConfirmClient
import com.shareguard.app.data.ServerApiClient
import com.shareguard.app.data.IssuedQr
import com.shareguard.app.data.PaymentState
import com.shareguard.app.data.RemoteReview
import com.shareguard.app.data.ContractPaymentDetails
import org.json.JSONObject
import java.time.Instant
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.NumberFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : Activity() {
    companion object {
        private const val SESSION_PREFS = "shareguard_session"
        private const val OAUTH_REDIRECT_URI = "shareguard://auth/callback"
        private const val OAUTH_CODE_VERIFIER = "oauth_code_verifier"
        private const val KAKAO_OAUTH_REDIRECT_URI = "shareguard://auth-callback"
        private const val KAKAO_CODE_VERIFIER = "kakao_pkce_verifier"
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
        private const val LOCATION_PERMISSION_REQUEST = 9002
        private const val MAP_BROWSE_REQUEST = 9003
        private const val MAP_PICK_REQUEST = 9004
        private const val MFA_VERIFICATION_REQUEST = 9005
        private const val DEFAULT_MAP_LATITUDE = 37.4979
        private const val DEFAULT_MAP_LONGITUDE = 127.0276
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
        RENTAL_FLOW("대여 진행", "물품 인도부터 반납까지 단계별로 확인합니다."),
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
        var name: String,
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
        var distance: Int,
        var location: String,
        val ownerId: Int,
        val tone: Int,
        val remoteId: String? = null,
        val remoteOwnerId: String? = null,
        var latitude: Double? = null,
        var longitude: Double? = null,
        var imagePaths: List<String> = emptyList(),
        var status: String = "active",
        val available: Boolean = true,
        var components: String? = null,
        var knownDefects: String? = null,
        var itemValue: Int? = null
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
        val remoteBorrowerId: String? = null,
        val depositAmount: Int = 0
    )

    private data class Contract(
        val id: Int,
        val reservationId: Int,
        var status: ContractStatus,
        var beforePhotos: Int = 0,
        var afterPhotos: Int = 0,
        var beforePhotoPaths: List<String> = emptyList(),
        var afterPhotoPaths: List<String> = emptyList(),
        var refunded: Boolean = false,
        var refundHeld: Boolean = false,
        val remoteId: String? = null,
        var ownerSigned: Boolean = false,
        var borrowerSigned: Boolean = false,
        var ownerSignedAt: String? = null,
        var borrowerSignedAt: String? = null,
        var specialTerms: String? = null,
        var snapshot: JSONObject = JSONObject()
    )

    private data class ChatMessage(val mine: Boolean, val text: String, val time: String = "방금", val read: Boolean = false)
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
        var unread: Boolean = true,
        val remoteId: Long? = null,
        val reservationId: String? = null,
        val contractId: Int? = null
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
    private var selectedChatReservationId: Int? = null
    private var remoteChatRooms = emptyList<RemoteChatRoom>()
    private var dateRequests = emptyList<RemoteDateRequest>()
    private var archivedReservations = emptySet<String>()
    private val hiddenDemoReservations = mutableSetOf<Int>()
    private val chatDrafts = mutableMapOf<Int, String>()
    private var chatMessagesContainer: LinearLayout? = null
    private var dateRequestsContainer: LinearLayout? = null
    private var chatLoading = false
    private var reservationLoading = false
    private var notificationLoading = false
    private var activityVisible = false
    private var registrationSaving = false
    private var reservationSaving = false
    private var pendingNotificationReservation: String? = null
    private var pendingNotificationKind: String? = null
    private val registrationDraft = mutableMapOf<String, String>()
    private val notificationPoll = object : Runnable {
        override fun run() {
            if (!activityVisible) return
            loadRemoteNotifications()
            if (screen == Screen.CHAT && !chatDetailOpen || screen == Screen.RESERVATIONS) loadRemoteReservations()
            chatHandler.postDelayed(this, 8_000L)
        }
    }
    private var nextItemId = 505
    private var nextReservationId = 1
    private var nextContractId = 1
    private var nextReportId = 1

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var mainScroll: ScrollView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val pendingRefreshLoads = mutableSetOf<String>()
    private var pullRefreshFailed = false
    private var itemsLoading = false
    private var chatComposer: LinearLayout? = null
    private var chatInput: EditText? = null
    private var chatMessagesView: LinearLayout? = null
    private var paymentRefreshInProgress = false
    private var paymentLastRefreshedAt: String? = null
    private lateinit var supabase: SupabaseRestClient
    private val server by lazy { ServerApiClient(BuildConfig.SHAREGUARD_API_BASE_URL) }
    private var serverUser = User(2, "로그인 필요", "회원", false, 0, 0.0, 0, 0)
    private var profileLoading = false
    private var profileLoaded = false
    private var profileError: String? = null
    private val ownerProfiles = mutableMapOf<String, RemoteProfile>()
    private var profileReviews = emptyList<RemoteReview>()
    private var profileReviewsError: String? = null
    private val paymentStates = mutableMapOf<String, PaymentState>()
    private val refundRequests = mutableSetOf<String>()
    private var paymentStateError: String? = null
    private var paymentReadVersion = 0
    private var qrRequestInProgress = false
    private var sessionToken: String? = null
    private var pendingPaymentContractId: Int? = null
    private var sessionUserId: String? = null
    private var profileSyncVersion = 0L
    private var activeChatRoomId: String? = null
    private var pendingPhotoContractId: String? = null
    private var pendingPhotoPhase: String? = null
    private var pendingPhotoUri: Uri? = null
    private var pendingPhotoUserId: String? = null
    private var conditionPhotoUploading = false
    private var conditionPhotoAwaitingResult = false
    private var conditionCameraPermissionPending = false
    private val reviewRatings = mutableMapOf<Int, Int>()
    private val reviewComments = mutableMapOf<Int, String>()
    private var reviewSaving = false
    private var authPanel = AuthPanel.CLOSED
    private var verificationStep = VerificationStep.NONE
    private var verificationCodeSent = false
    private var searchQuery = ""
    private var selectedCategory = "전체"
    private val itemCategories = listOf(
        "카메라", "렌즈", "드론", "게임기", "게임 타이틀", "캠핑", "등산",
        "IT기기", "노트북", "태블릿", "음향기기", "영상기기", "생활",
        "생활가전", "스포츠", "공구", "차량용품", "여행용품", "의류/패션",
        "악기", "취미용품", "파티용품", "유아용품", "기타"
    )
    private var homeCategoryScrollX = 0
    private var filterCategoryScrollX = 0
    private var registerCategoryScrollX = 0
    private var sortMode = "거리순"
    private var maxDistanceKm = 10
    private var nearbyOnly = false
    private var registerTab = "새 물품"
    private var registerCategory = "카메라"

    private var editingItemId: Int? = null
    private val registrationPhotoUris = mutableListOf<Uri>()
    private var pendingItemCameraUri: Uri? = null
    private var itemCameraPermissionPending = false
    private val itemCameraRequest = 7107
    private val itemCameraPermissionRequest = 7108
    private var chatDetailOpen = false
    private var reservationTab = "빌린 물품"
    private var activeContractId: Int? = null
    private var rentalStep = RentalStep.HANDOVER_PHOTOS
    private val inspectionChecks = mutableMapOf<Int, Pair<Boolean, Boolean>>()
    private var notificationFilter = "전체"
    private var adminTab = "신고 관리"
    private var selectedAdminReportId: Int? = null
    private var nextNotificationId = 10
    private var sessionNotice: String? = null
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    private var selectedRegistrationLatitude: Double? = null
    private var selectedRegistrationLongitude: Double? = null
    private var selectedRegistrationAddress: String? = null
    private var registrationLocationInput: EditText? = null
    private var registrationCoordinateText: TextView? = null
    private var locating = false
    private var reservationStartDate: LocalDate = LocalDate.now().plusDays(1)
    private var reservationEndDate: LocalDate = LocalDate.now().plusDays(3)
    private var pendingQrContractId: Int? = null
    private var pendingQrAction: String? = null
    private val qrPayloads = mutableMapOf<String, IssuedQr>()
    private val contractPdfFiles = mutableMapOf<Int, File>()
    private val contractSpecialDrafts = mutableMapOf<Int, String>()
    private val chatHandler = Handler(Looper.getMainLooper())
    private val chatPoll = object : Runnable {
        override fun run() {
            if (screen == Screen.CHAT && chatDetailOpen) {
                loadRemoteChat()
                chatHandler.postDelayed(this, 5_000L)
            }
        }
    }
    private val rentalPoll = object : Runnable {
        override fun run() {
            if (!activityVisible || screen != Screen.RENTAL_FLOW ||
                rentalStep !in setOf(RentalStep.HANDOVER_QR, RentalStep.RETURN_QR) ||
                activeContract()?.remoteId == null) return
            loadRemoteReservations(refreshPayments = false)
            chatHandler.postDelayed(this, 4_000L)
        }
    }

    private val beforePhotoRequest = 7101
    private val afterPhotoRequest = 7102
    private val itemPhotoRequest = 7103
    private val chatMediaRequest = 7104
    private val conditionCameraPermissionRequest = 7105
    private val conditionGalleryRequest = 7106

    private val users = mutableListOf(
        User(1, "김민수", "대여자", true, 850, 4.9, 28, 120),
        User(2, "이지수", "차용자", false, 780, 4.8, 12, 64)
    )
    private val items = mutableListOf(
        Item(501, "소니 A7III 풀프레임 미러리스", "카메라", "렌즈, 배터리 2개, 충전기 포함. 대여 전후 사진 기록 후 안전하게 거래합니다.", 50000, 200000, 520, "강남구 역삼동", 1, Color.rgb(219, 234, 254), latitude = 37.5007, longitude = 127.0365),
        Item(502, "PS5 디지털 에디션", "게임기", "듀얼센스 2개와 인기 타이틀 3종을 함께 제공합니다.", 30000, 150000, 1180, "서초구 서초동", 1, Color.rgb(237, 233, 254), latitude = 37.4918, longitude = 127.0077),
        Item(503, "캠핑용 텐트 6인용", "캠핑", "방수 플라이와 그라운드시트 포함. 구성품 체크리스트를 제공합니다.", 25000, 100000, 2100, "송파구 잠실동", 1, Color.rgb(220, 252, 231), latitude = 37.5133, longitude = 127.1001),
        Item(504, "캠핑용 빔프로젝터", "IT", "HDMI 케이블, 삼각대, 전용 파우치를 포함합니다.", 15000, 80000, 850, "강남구 논현동", 1, Color.rgb(254, 243, 199), latitude = 37.5112, longitude = 127.0286)
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
        AppNotification(4, "📷", "대여 전 사진을 등록해주세요", "거래 시작 전 물품 상태 사진이 필요합니다.", "2일 전", Screen.RENTAL_FLOW, false)
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
        val hasStoredSession = !storedToken.isNullOrBlank() && !storedUserId.isNullOrBlank()
        when {
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
            // Never treat a locally cached badge as a server identity verification.
            resetServerProfile()
            notifications.clear()
            items.clear()
            chatMessages.clear()
        }
        captureNotificationIntent(intent)
        savedInstanceState?.let { state ->
            if (state.getString("draft_user") != sessionUserId) return@let
            pendingItemCameraUri = state.getString("item_camera_uri")?.let(Uri::parse)
            itemCameraPermissionPending = state.getBoolean("item_camera_permission")
            registrationPhotoUris.addAll(state.getStringArrayList("item_photos").orEmpty().map(Uri::parse))
            state.getBundle("item_draft")?.let { draft -> draft.keySet().forEach { registrationDraft[it] = draft.getString(it).orEmpty() } }
            if (state.containsKey("editing_item")) editingItemId = state.getInt("editing_item")
            registerCategory = state.getString("item_category") ?: registerCategory
            registerTab = state.getString("item_tab") ?: registerTab
            if (state.containsKey("item_lat")) selectedRegistrationLatitude = state.getDouble("item_lat")
            if (state.containsKey("item_lon")) selectedRegistrationLongitude = state.getDouble("item_lon")
            selectedRegistrationAddress = state.getString("item_address")
            if (state.getBoolean("item_screen")) screen = Screen.REGISTER
            activeContractId = if (state.containsKey("active_contract")) state.getInt("active_contract") else null
            rentalStep = runCatching { RentalStep.valueOf(state.getString("rental_step") ?: rentalStep.name) }.getOrDefault(rentalStep)
            pendingPhotoContractId = state.getString("condition_contract")
            pendingPhotoPhase = state.getString("condition_phase")
            pendingPhotoUserId = state.getString("condition_user")
            pendingPhotoUri = state.getString("condition_uri")?.let(Uri::parse)
            conditionPhotoAwaitingResult = state.getBoolean("condition_awaiting")
            conditionCameraPermissionPending = state.getBoolean("condition_permission")
            if (pendingPhotoContractId != null) {
                screen = Screen.RENTAL_FLOW
                rentalStep = if (pendingPhotoPhase == "after") RentalStep.RETURN_INSPECT else RentalStep.HANDOVER_PHOTOS
            }
            state.getBundle("review_ratings")?.let { drafts ->
                drafts.keySet().forEach { reviewRatings[it.toInt()] = drafts.getInt(it) }
            }
            state.getBundle("review_comments")?.let { drafts ->
                drafts.keySet().forEach { reviewComments[it.toInt()] = drafts.getString(it).orEmpty() }
            }
        }
        buildShell()
        render()
        sessionNotice?.let(::toast)
        // Handle either provider before refreshing an older persisted session.
        if (handleKakaoOAuthRedirect(intent) || handleGoogleAuthCallback(intent)) {
            return
        } else if (!sessionToken.isNullOrBlank() && !preferences.getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()) {
            refreshPersistedSession()
        } else {
            loadCurrentProfile()
            loadRemoteItems()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft_user", sessionUserId)
        outState.putString("item_camera_uri", pendingItemCameraUri?.toString())
        outState.putBoolean("item_camera_permission", itemCameraPermissionPending)
        outState.putStringArrayList("item_photos", ArrayList(registrationPhotoUris.map(Uri::toString)))
        outState.putBundle("item_draft", Bundle().apply { registrationDraft.forEach { (key, value) -> putString(key, value) } })
        editingItemId?.let { outState.putInt("editing_item", it) }
        outState.putString("item_category", registerCategory)
        outState.putString("item_tab", registerTab)
        selectedRegistrationLatitude?.let { outState.putDouble("item_lat", it) }
        selectedRegistrationLongitude?.let { outState.putDouble("item_lon", it) }
        outState.putString("item_address", selectedRegistrationAddress)
        outState.putBoolean("item_screen", screen == Screen.REGISTER)
        activeContractId?.let { outState.putInt("active_contract", it) }
        outState.putString("rental_step", rentalStep.name)
        outState.putString("condition_contract", pendingPhotoContractId)
        outState.putString("condition_phase", pendingPhotoPhase)
        outState.putString("condition_user", pendingPhotoUserId)
        outState.putString("condition_uri", pendingPhotoUri?.toString())
        outState.putBoolean("condition_awaiting", conditionPhotoAwaitingResult)
        outState.putBoolean("condition_permission", conditionCameraPermissionPending)
        outState.putBundle("review_ratings", Bundle().apply { reviewRatings.forEach { (id, value) -> putInt(id.toString(), value) } })
        outState.putBundle("review_comments", Bundle().apply { reviewComments.forEach { (id, value) -> putString(id.toString(), value) } })
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Do not load previous-account data while a social login is being exchanged.
        if (handleKakaoOAuthRedirect(intent) || handleGoogleAuthCallback(intent)) return
        captureNotificationIntent(intent)
        loadRemoteReservations()
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        chatHandler.removeCallbacks(notificationPoll)
        chatHandler.post(notificationPoll)
        if (screen == Screen.CHAT && chatDetailOpen) { chatHandler.removeCallbacks(chatPoll); chatHandler.post(chatPoll) }
        scheduleRentalPoll()
        if (::supabase.isInitialized && ::root.isInitialized && !sessionToken.isNullOrBlank()) {
            loadCurrentProfile()
            if (profileLoaded) loadRemoteReservations()
        }
    }

    override fun onStop() {
        super.onStop()
        activityVisible = false
        chatHandler.removeCallbacks(notificationPoll)
        chatHandler.removeCallbacks(chatPoll)
        chatHandler.removeCallbacks(rentalPoll)
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ACTIVE_AT, System.currentTimeMillis())
            .putInt(KEY_APP_VERSION, BuildConfig.VERSION_CODE)
            .apply()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            MAP_BROWSE_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    val itemId = data.getIntExtra(MapActivity.EXTRA_SELECTED_ITEM_ID, -1)
                    if (items.any { it.id == itemId }) {
                        selectedItemId = itemId
                        screen = Screen.DETAIL
                        render()
                    }
                }
                return
            }
            MAP_PICK_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    selectedRegistrationLatitude = data.getDoubleExtra(
                        MapActivity.EXTRA_SELECTED_LATITUDE,
                        Double.NaN
                    ).takeUnless(Double::isNaN)
                    selectedRegistrationLongitude = data.getDoubleExtra(
                        MapActivity.EXTRA_SELECTED_LONGITUDE,
                        Double.NaN
                    ).takeUnless(Double::isNaN)
                    selectedRegistrationAddress = data
                        .getStringExtra(MapActivity.EXTRA_SELECTED_ADDRESS)
                        ?.takeIf(String::isNotBlank)
                    selectedRegistrationAddress?.let { registrationLocationInput?.setText(it) }
                    registrationCoordinateText?.text = registrationCoordinatesLabel()
                }
                return
            }
            MFA_VERIFICATION_REQUEST -> {
                if (resultCode == RESULT_OK && data != null) {
                    completeIdentityVerification()
                } else {
                    toast("PASS 본인인증이 완료되지 않았습니다.")
                }
                return
            }
        }
        val qrResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (qrResult != null) {
            val contract = pendingQrContractId?.let { id -> contracts.find { it.id == id } }
            val action = pendingQrAction
            pendingQrContractId = null
            pendingQrAction = null
            if (qrResult.contents == null) toast("QR 스캔을 취소했습니다.")
            else if (contract != null && action != null) handleScannedQr(contract, action, qrResult.contents)
            return
        }
        if (requestCode == TOSS_PAYMENT_REQUEST) {
            handleTossPaymentResult(resultCode, data)
            return
        }
        if (requestCode == itemCameraRequest) {
            val uri = pendingItemCameraUri
            pendingItemCameraUri = null
            if (uri != null) {
                runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val hasImage = runCatching { contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true }.getOrDefault(false)
                if (resultCode == RESULT_OK && hasImage && itemPhotoCount() < 5) {
                    registrationPhotoUris.add(uri)
                } else {
                    runCatching { contentResolver.delete(uri, null, null) }
                    if (resultCode == RESULT_OK) toast("사진을 추가하지 못했습니다. 사진 개수를 확인하고 다시 촬영해주세요.")
                }
            }
            render()
            return
        }
        if (requestCode == itemPhotoRequest) {
            if (resultCode == RESULT_OK) {
                val selected = mutableListOf<Uri>()
                data?.clipData?.let { clip ->
                    repeat(clip.itemCount) { index -> selected.add(clip.getItemAt(index).uri) }
                } ?: data?.data?.let(selected::add)
                val remaining = (5 - registrationPhotoUris.size - (items.find { it.id == editingItemId }?.imagePaths?.size ?: 0)).coerceAtLeast(0)
                if (selected.size > remaining) toast("물품 사진은 최대 5장입니다.")
                selected.take(remaining).forEach { uri ->
                    if (registrationPhotoUris.size < 5 && uri !in registrationPhotoUris) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        registrationPhotoUris.add(uri)
                    }
                }
                render()
            }
            return
        }
        if (requestCode == chatMediaRequest) {
            if (resultCode == RESULT_OK) data?.data?.let(::uploadChatMedia)
            return
        }
        if (requestCode !in listOf(beforePhotoRequest, afterPhotoRequest, conditionGalleryRequest)) return
        conditionPhotoAwaitingResult = false
        if (resultCode != RESULT_OK) {
            clearPendingConditionPhoto()
            render()
            return
        }
        if (requestCode == conditionGalleryRequest) {
            pendingPhotoUri = data?.data
            pendingPhotoUri?.let { uri -> runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } }
        }
        // Camera apps write to EXTRA_OUTPUT and commonly return a null Intent.
        if (pendingPhotoUri == null) {
            clearPendingConditionPhoto()
            toast("사진을 읽을 수 없습니다. 다시 촬영해주세요.")
            return
        }
        uploadPendingConditionPhoto()
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
        if (screen == Screen.RENTAL_FLOW) {
            backRentalFlow()
            return
        }
        if (screen in setOf(Screen.TRANSACTION, Screen.FEEDBACK, Screen.NOTIFICATIONS, Screen.ADMIN)) {
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
        mainScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(match, 0, 1f)
            setColorSchemeColors(blue)
            setOnChildScrollUpCallback { _, _ -> mainScroll.canScrollVertically(-1) }
            setOnRefreshListener { refreshCurrentTab() }
            addView(mainScroll)
        }
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.WHITE, 0)
        }
        root.addView(swipeRefresh)
        root.addView(bottomNav, LinearLayout.LayoutParams(match, dp(66)))
        setContentView(root)
    }

    private fun render() {
        chatHandler.removeCallbacks(chatPoll)
        chatHandler.removeCallbacks(rentalPoll)
        if (screen == Screen.CHAT && chatDetailOpen) {
            selectedChatReservationId?.let { id -> chatInput?.let { chatDrafts[id] = it.text.toString() } }
        }
        chatComposer?.let(root::removeView)
        chatComposer = null
        chatInput = null
        chatMessagesView = null
        content.removeAllViews()
        bottomNav.removeAllViews()
        if ((screen == Screen.DETAIL || (screen == Screen.CHAT && chatDetailOpen)) && items.none { it.id == selectedItemId }) {
            screen = Screen.HOME
            chatDetailOpen = false
        }
        val showInitialAuth = supabase.isConfigured && sessionToken.isNullOrBlank()
        val showVerification = supabase.isConfigured &&
                !sessionToken.isNullOrBlank() &&
                verificationStep != VerificationStep.NONE
        swipeRefresh.isEnabled = !showInitialAuth && !showVerification && screen in setOf(
            Screen.HOME, Screen.MAP, Screen.CHAT, Screen.RESERVATIONS, Screen.PROFILE
        )
        swipeRefresh.isRefreshing = swipeRefresh.isEnabled && pendingRefreshLoads.isNotEmpty()
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
        bottomNav.visibility = if (screen in setOf(Screen.FILTER, Screen.DETAIL, Screen.NOTIFICATIONS, Screen.ADMIN) ||
            screen == Screen.CHAT && chatDetailOpen) View.GONE else View.VISIBLE
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
            Screen.RENTAL_FLOW -> renderRentalFlow()
            Screen.FEEDBACK -> renderFeedback()
            Screen.NOTIFICATIONS -> renderNotifications()
            Screen.PROFILE -> renderProfile()
            Screen.ADMIN -> renderAdmin()
        }
        renderBottomNav()
        scheduleRentalPoll()
    }

    private fun refreshCurrentTab() {
        if (pendingRefreshLoads.isNotEmpty()) return
        if (!swipeRefresh.isEnabled) {
            swipeRefresh.isRefreshing = false
            return
        }
        if (!supabase.isConfigured || sessionToken == null) {
            swipeRefresh.isRefreshing = false
            render()
            return
        }
        val load = when (screen) {
            Screen.HOME, Screen.MAP -> "items"
            Screen.CHAT -> if (chatDetailOpen) "chat" else "reservations"
            Screen.RESERVATIONS -> "reservations"
            Screen.PROFILE -> "profile"
            else -> { swipeRefresh.isRefreshing = false; return }
        }
        pendingRefreshLoads.add(load)
        pullRefreshFailed = false
        swipeRefresh.isRefreshing = true
        // Existing in-flight requests complete the same load, avoiding duplicate calls.
        when (load) {
            "items" -> loadRemoteItems()
            "chat" -> loadRemoteChat()
            "reservations" -> loadRemoteReservations()
            "profile" -> loadCurrentProfile()
        }
    }

    private fun finishPullRefresh(load: String, succeeded: Boolean = false) {
        if (!pendingRefreshLoads.remove(load)) return
        if (!succeeded) pullRefreshFailed = true
        if (pendingRefreshLoads.isEmpty()) {
            swipeRefresh.isRefreshing = false
            if (!pullRefreshFailed && activityVisible) toast("새로고침 완료")
        }
    }

    private fun renderHeader() {
        content.addView(row {
            if (screen in setOf(Screen.TRANSACTION, Screen.RENTAL_FLOW, Screen.FEEDBACK, Screen.NOTIFICATIONS, Screen.ADMIN)) {
                addView(coloredButton("‹", Color.rgb(245, 247, 251), text) {
                    if (screen == Screen.RENTAL_FLOW) backRentalFlow()
                    else {
                        screen = if (screen == Screen.NOTIFICATIONS) Screen.HOME else if (screen == Screen.ADMIN) Screen.PROFILE else Screen.RESERVATIONS
                        render()
                    }
                }, LinearLayout.LayoutParams(dp(46), dp(46)))
                addView(space(10))
            }
            addView(column {
                addView(textView(if (screen == Screen.RENTAL_FLOW) rentalStepTitle() else screen.title, 23, true, text))
                addView(textView(if (screen == Screen.RENTAL_FLOW) rentalStepSubtitle() else screen.subtitle, 12, false, muted).withTop(2))
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            if (screen == Screen.PROFILE) addView(coloredButton("⚙", Color.rgb(245, 247, 251), text) {
                showSettings()
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
        })
        if (screen in setOf(Screen.REGISTER, Screen.RESERVATIONS, Screen.CHAT)) {
            content.addView(textView("안전한 이웃 간 대여 · ShareGuard", 12, true, blue).withTop(8))
        }
    }

    private fun openScreen(target: Screen) {
        screen = target
        if (target == Screen.CHAT) {
            chatDetailOpen = false
        }
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
            startKakaoLogin()
        }.withTop(24))
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
        val nickname = edit("").apply { hint = "닉네임 (2~30자)" }
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
            if (createAccount) addView(field("닉네임", nickname))
            addView(field("이메일", email))
            addView(field("비밀번호", password))
            addView(button(if (createAccount) "회원가입" else "로그인") {
                authenticate(email.text.toString(), password.text.toString(), createAccount, nickname.text.toString())
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
        content.addView(textView("PASS로 본인인증을 진행해요", 26, true, text).withTop(34))
        content.addView(textView("포트원 인증창에서 통신사를 선택하면 설치된 PASS 앱으로 전환됩니다.", 14, false, muted).withTop(6))
        content.addView(card(Color.rgb(247, 246, 255)).apply {
            addView(textView("인증 정보 보호", 15, true, text))
            addView(textView("이름·휴대폰 번호 등의 원문은 앱이 저장하지 않으며, 서버가 포트원 결과를 다시 검증합니다.", 13, false, muted).withTop(5))
        }.withTop(24))
        content.addView(card(Color.rgb(234, 248, 239)).apply {
            addView(textView("인증 완료 후 활성화", 15, true, good))
            addView(textView("물품 등록 · 예약 · 보증금 결제 · 인증 배지", 13, false, muted).withTop(5))
        }.withTop(12))
        content.addView(button("PASS 본인인증 시작") { startPassVerification() }.withTop(28))
    }

    private fun renderVerificationIdentity() {
        // 이전 프로토타입 화면 상태가 복원된 경우에도 가짜 입력 인증 대신 PASS 인증으로 보냅니다.
        verificationStep = VerificationStep.PHONE
        renderVerificationPhone()
    }

    private fun renderHome() {
        content.addView(row {

            // 전체 물품 보기
            addView(
                categoryChip("전체 물품", !nearbyOnly) {
                    nearbyOnly = false
                    render()
                },
                LinearLayout.LayoutParams(dp(96), dp(44))
            )

            addView(space(8))

            // 현재 위치 기준 주변 물품 보기
            addView(
                categoryChip(
                    if (locating) "위치 확인 중…" else "📍 내 주변",
                    nearbyOnly
                ) {
                    if (!locating) {
                        requestCurrentLocation()
                    }
                },
                LinearLayout.LayoutParams(dp(124), dp(44))
            )

            // 오른쪽 빈 공간
            addView(
                Space(this@MainActivity),
                LinearLayout.LayoutParams(0, dp(1), 1f)
            )

            addView(space(8))

            // 알림
            val unread = notifications.count { it.unread }

            addView(
                coloredButton(
                    if (unread > 0) "🔔 $unread" else "🔔",
                    Color.rgb(245, 247, 251),
                    text
                ) {
                    screen = Screen.NOTIFICATIONS
                    render()
                },
                LinearLayout.LayoutParams(
                    dp(if (unread > 0) 72 else 48),
                    dp(44)
                )
            )
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

        content.addView(categoryStrip(listOf("전체") + itemCategories, selectedCategory,
            homeCategoryScrollX, { homeCategoryScrollX = it }) { category ->
            selectedCategory = category
            render()
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

        val resultLabel = if (nearbyOnly) {
            "내 주변 ${maxDistanceKm}km · ${results.size}개의 대여 가능 물품"
        } else {
            "전체 ${results.size}개의 대여 가능 물품"
        }

        content.addView(
            textView(
                resultLabel,
                14,
                true,
                muted
            ).withTop(18)
        )
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
                addView(itemPhoto(item), LinearLayout.LayoutParams(dp(94), dp(94)))
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
        content.addView(categoryStrip(listOf("전체") + itemCategories, selectedCategory,
            filterCategoryScrollX, { filterCategoryScrollX = it }) { category ->
            selectedCategory = category
            render()
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
        content.addView(card(Color.rgb(234, 241, 255)).apply {
            addView(textView("실제 카카오맵에서 물품 위치를 확인하세요.", 16, true, text))
            addView(textView("물품 마커를 누르면 상세 화면으로 이동하고, 내 위치 버튼으로 주변 물품을 확인할 수 있습니다.", 13, false, muted).withTop(5))
            addView(button("카카오맵 열기") { openItemMap() }.withTop(12))
        }.withTop(12))
        val nearby = filteredItems().take(3)
        content.addView(textView("주변 ${nearby.size}개의 대여 가능 물품", 16, true, text).withTop(20))
        nearby.forEach { item -> content.addView(homeItemRow(item).withTop(10)) }
    }

    private fun openItemMap() {
        val mappedItems = filteredItems().filter { it.latitude != null && it.longitude != null }
        if (mappedItems.isEmpty()) {
            toast("지도에 표시할 위치 정보가 없습니다.")
            return
        }

        val intent = Intent(this, MapActivity::class.java).apply {
            putExtra(MapActivity.EXTRA_MODE, MapActivity.MODE_BROWSE)
            putExtra(MapActivity.EXTRA_ITEM_IDS, mappedItems.map { it.id }.toIntArray())
            putStringArrayListExtra(MapActivity.EXTRA_TITLES, ArrayList(mappedItems.map { it.title }))
            putStringArrayListExtra(MapActivity.EXTRA_ADDRESSES, ArrayList(mappedItems.map { it.location }))
            putExtra(MapActivity.EXTRA_LATITUDES, mappedItems.map { it.latitude!! }.toDoubleArray())
            putExtra(MapActivity.EXTRA_LONGITUDES, mappedItems.map { it.longitude!! }.toDoubleArray())
        }
        startActivityForResult(intent, MAP_BROWSE_REQUEST)
    }

    private fun openLocationPicker(editing: Item?) {
        val intent = Intent(this, MapActivity::class.java).apply {
            putExtra(MapActivity.EXTRA_MODE, MapActivity.MODE_PICK)
            putExtra(
                MapActivity.EXTRA_INITIAL_LATITUDE,
                selectedRegistrationLatitude
                    ?: editing?.latitude
                    ?: currentLatitude
                    ?: DEFAULT_MAP_LATITUDE
            )
            putExtra(
                MapActivity.EXTRA_INITIAL_LONGITUDE,
                selectedRegistrationLongitude
                    ?: editing?.longitude
                    ?: currentLongitude
                    ?: DEFAULT_MAP_LONGITUDE
            )
        }
        startActivityForResult(intent, MAP_PICK_REQUEST)
    }

    private fun registrationCoordinatesLabel(): String {
        val latitude = selectedRegistrationLatitude
        val longitude = selectedRegistrationLongitude
        if (latitude != null && longitude != null) {
            return String.format(Locale.US, "선택 좌표: %.6f, %.6f", latitude, longitude)
        }

        val currentLat = currentLatitude
        val currentLng = currentLongitude
        return if (currentLat != null && currentLng != null) {
            String.format(Locale.US, "미선택 시 현재 위치 사용: %.6f, %.6f", currentLat, currentLng)
        } else {
            "좌표 미선택 · 좌표가 없으면 물품이 지도에 표시되지 않습니다."
        }
    }

    private fun clearRegistrationCoordinates() {
        selectedRegistrationLatitude = null
        selectedRegistrationLongitude = null
        selectedRegistrationAddress = null
        registrationLocationInput = null
        registrationCoordinateText = null
    }

    private fun renderItemDetail() {
        val item = items.find { it.id == selectedItemId } ?: run { content.addView(empty("물품을 다시 선택해주세요.")); return }
        val owner = ownerOf(item)
        content.addView(row {
            addView(button("‹", secondary = true) {
                screen = Screen.HOME
                render()
            }, LinearLayout.LayoutParams(dp(48), dp(42)))
            addView(textView("물품 상세", 20, true, text).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(button("♡", secondary = true) { toast("관심 물품에 저장했습니다.") }, LinearLayout.LayoutParams(dp(52), dp(42)))
        })
        content.addView(itemPhoto(item).also {
            it.layoutParams = LinearLayout.LayoutParams(match, dp(270)).apply { topMargin = dp(12) }
        })
        if (item.imagePaths.size > 1) content.addView(HorizontalScrollView(this).apply {
            addView(row { item.imagePaths.forEach { path -> addView(itemPhoto(item, path), LinearLayout.LayoutParams(dp(160), dp(140)).withRightMargin(8)) } })
        }.withTop(8))
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
            val trust = calculateTrustScore(currentUser())
            val discountedDeposit = adjustedDeposit(item.deposit, trust)
            addView(metric("하루 대여료", money(item.price)))
            addView(metric("기본 보증금", money(item.deposit)))
            addView(metric("신뢰 할인", "${depositDiscountPercent(trust)}%"))
            addView(metric("적용 보증금", money(discountedDeposit), good))
        }.withTop(22))
        val days = rentalDays(reservationStartDate, reservationEndDate)
        val conflict = reservationConflict(item.id, reservationStartDate, reservationEndDate)
        content.addView(card().apply {
            addView(textView("대여 기간 선택", 17, true, text))
            addView(row {
                addView(button("시작  $reservationStartDate", secondary = true) {
                    showReservationDatePicker(true)
                }, LinearLayout.LayoutParams(0, dp(46), 1f))
                addView(space(8))
                addView(button("종료  $reservationEndDate", secondary = true) {
                    showReservationDatePicker(false)
                }, LinearLayout.LayoutParams(0, dp(46), 1f))
            }.withTop(10))
            addView(metric("대여 기간", "${days}일").withTop(10))
            addView(metric("대여료", money(item.price * days)))
            addView(metric("결제 예정액", money(item.price * days + adjustedDeposit(item.deposit, calculateTrustScore(currentUser())))))
            if (conflict) addView(textView("선택한 기간에 이미 진행 중인 예약이 있습니다.", 12, true, danger).withTop(8))
        }.withTop(12))
        content.addView(button("대여 신청하기") { createReservation(item) }.withTop(18))
    }

    private fun showReservationDatePicker(isStart: Boolean) {
        val initial = if (isStart) reservationStartDate else reservationEndDate
        DatePickerDialog(this, { _, year, month, day ->
            val selected = LocalDate.of(year, month + 1, day)
            if (selected.isBefore(LocalDate.now())) {
                toast("과거 날짜는 선택할 수 없습니다.")
                return@DatePickerDialog
            }
            if (isStart) {
                reservationStartDate = selected
                if (reservationEndDate.isBefore(selected)) reservationEndDate = selected
            } else {
                if (selected.isBefore(reservationStartDate)) {
                    toast("종료일은 시작일보다 빠를 수 없습니다.")
                    return@DatePickerDialog
                }
                reservationEndDate = selected
            }
            render()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis() - 1_000L
        }.show()
    }

    private fun rentalDays(start: LocalDate, end: LocalDate): Int =
        (ChronoUnit.DAYS.between(start, end) + 1L).coerceAtLeast(1L).toInt()

    private fun reservationConflict(itemId: Int, start: LocalDate, end: LocalDate): Boolean =
        reservations.any { reservation ->
            reservation.itemId == itemId && ReservationRules.overlaps(reservation.status, start, end,
                LocalDate.parse(reservation.startDate), LocalDate.parse(reservation.endDate))
        }

    private fun calculateTrustScore(user: User): Int {
        if (::supabase.isInitialized && supabase.isConfigured) return user.trust.coerceIn(0, 1000)
        val verified = if (user.verified) 150 else 0
        val deals = (user.deals * 8).coerceAtMost(160)
        val rating = (((user.rating - 3.0).coerceAtLeast(0.0)) * 80).roundToInt().coerceAtMost(160)
        val safeHistory = (user.noAccidentDays / 2).coerceAtMost(120)
        val disputes = reports.count { it.open } * 80
        return (500 + verified + deals + rating + safeHistory - disputes).coerceIn(0, 1000)
    }

    private fun depositDiscountPercent(trustScore: Int): Int = DepositBenefitPolicy.discountPercent(trustScore)

    private fun adjustedDeposit(baseDeposit: Int, trustScore: Int): Int =
        DepositBenefitPolicy.adjustedDeposit(baseDeposit, trustScore)

    private fun filteredItems(): List<Item> {
        val categoryFiltered = items.filter { item ->
            val categoryMatches = selectedCategory == "전체" ||
                    item.category == selectedCategory ||
                    selectedCategory == "IT기기" && item.category == "IT" ||
                    selectedCategory == "기타" && item.category !in itemCategories && item.category != "IT"
            val queryMatches = searchQuery.isBlank() || listOf(item.title, item.category, item.description, item.location)
                .any { it.contains(searchQuery, ignoreCase = true) }
            item.available &&
                    item.status == "active" &&
                    categoryMatches &&
                    queryMatches &&
                    (!nearbyOnly || item.distance <= maxDistanceKm * 1000)
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

    private fun requestCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
            return
        }
        locating = true
        render()
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            locating = false
            toast("기기의 위치 서비스를 켜주세요.")
            render()
            return
        }
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let(::applyCurrentLocation)
        runCatching {
            manager.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) = applyCurrentLocation(location)
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) = Unit
                @Deprecated("Deprecated in Android")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }, mainLooper)
        }.onFailure {
            locating = false
            toast("현재 위치를 가져오지 못했습니다.")
            render()
        }
    }

    private fun applyCurrentLocation(location: Location) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        nearbyOnly = true

        locating = false
        items.forEach { item ->
            val latitude = item.latitude
            val longitude = item.longitude
            if (latitude != null && longitude != null) {
                val result = FloatArray(1)
                Location.distanceBetween(location.latitude, location.longitude, latitude, longitude, result)
                item.distance = result[0].roundToInt()
            }
        }
        toast("현재 위치에서 실제 거리를 다시 계산했습니다.")
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == itemCameraPermissionRequest) {
            itemCameraPermissionPending = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openItemCamera()
            else toast("카메라 권한을 허용하면 촬영할 수 있습니다. 사진 선택으로도 추가할 수 있습니다.")
            return
        }
        if (requestCode == conditionCameraPermissionRequest) {
            conditionCameraPermissionPending = false
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) launchConditionCamera()
            else {
                clearPendingConditionPhoto()
                toast("카메라 권한을 허용하면 바로 촬영할 수 있습니다. 갤러리에서 선택도 가능합니다.")
                render()
            }
            return
        }
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestCurrentLocation()
            else toast("위치 권한이 없어 데모 거리를 사용합니다.")
        }
    }

    private fun renderRegister() {
        content.addView(segmentedTabs(listOf("새 물품", "내 물품"), registerTab) { tab ->
            registerTab = tab
            editingItemId = null
            registrationDraft.clear(); registrationPhotoUris.clear()
            clearRegistrationCoordinates()
            render()
        }.withTop(14))
        if (registerTab == "내 물품") {
            renderMyItems()
            return
        }

        val editing = editingItemId?.let { id -> items.find { it.id == id } }
        if (editingItemId != null && editing == null) {
            content.addView(empty("수정할 물품 정보를 불러오는 중입니다."))
            content.addView(button("다시 불러오기", secondary = true) { loadRemoteItems() })
            return
        }
        val titleInput = draftInput("title", editing?.title.orEmpty(), "물품명을 입력하세요")
        val priceInput = draftInput("price", editing?.price?.toString().orEmpty(), "하루 대여료").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val depositInput = draftInput("deposit", editing?.deposit?.toString().orEmpty(), "보증금").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val componentsInput = draftInput("components", editing?.components.orEmpty(), "구성품 (선택, 최대 500자)")
        val defectsInput = draftInput("known_defects", editing?.knownDefects.orEmpty(), "기존 하자 (선택, 최대 500자)")
        val valueInput = draftInput("item_value", editing?.itemValue?.toString().orEmpty(), "물품 가액 (선택)").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        val locationInput = draftInput("location", selectedRegistrationAddress ?: editing?.location.orEmpty(), "거래 위치")
        registrationLocationInput = locationInput
        val descriptionInput = draftInput("description", editing?.description.orEmpty(), "물품 사용 설명을 적어주세요")
        content.addView(textView(if (editing == null) "사진과 정보를 등록해요" else "물품 정보를 수정해요", 21, true, text).withTop(18))
        content.addView(textView("정확한 정보와 사진은 더 빠르고 안전한 거래로 이어집니다.", 13, false, muted).withTop(3))
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            val existingPhotoCount = editing?.imagePaths?.size ?: 0
            addView(textView("사진 등록  ${existingPhotoCount + registrationPhotoUris.size}/5", 15, true, text))
            if (editing != null && existingPhotoCount > 0) addView(HorizontalScrollView(this@MainActivity).apply {
                addView(row { editing.imagePaths.forEach { path -> addView(itemPhoto(editing, path), LinearLayout.LayoutParams(dp(96), dp(96)).withRightMargin(8)) } })
            }.withTop(8))
            if (registrationPhotoUris.isNotEmpty()) {
                addView(HorizontalScrollView(this@MainActivity).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(row {
                        registrationPhotoUris.forEachIndexed { index, uri ->
                            addView(column {
                                addView(ImageView(this@MainActivity).apply {
                                    setImageURI(uri)
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    contentDescription = "물품 사진 ${index + 1}"
                                    background = rounded(Color.rgb(235, 239, 246), 10)
                                }, LinearLayout.LayoutParams(dp(96), dp(96)))
                                addView(coloredButton("삭제", Color.TRANSPARENT, danger) {
                                    registrationPhotoUris.removeAt(index)
                                    render()
                                }, LinearLayout.LayoutParams(dp(96), dp(36)))
                            })
                            addView(space(8))
                        }
                    })
                }.withTop(10))
            } else addView(textView("대표 사진을 포함해 최대 5장을 선택하세요.", 12, false, muted).withTop(8))
            addView(button(if (registrationPhotoUris.isEmpty()) "+ 사진 선택" else "+ 사진 더 선택", secondary = true) {
                if (registrationPhotoUris.size + existingPhotoCount >= 5) toast("사진은 최대 5장까지 등록할 수 있습니다.")
                else openItemPhotoPicker()
            }.withTop(10))
            addView(button("+ 카메라로 촬영", secondary = true) { openItemCamera() }.withTop(8))
        }.withTop(12))
        content.addView(textView("카테고리", 14, true, text).withTop(18))
        content.addView(categoryStrip(itemCategories, if (registerCategory == "IT") "IT기기" else registerCategory,
            registerCategoryScrollX, { registerCategoryScrollX = it }) { category ->
            registerCategory = category
            render()
        }.withTop(8))
        content.addView(card().apply {
            addView(field("제목", titleInput))
            addView(field("설명", descriptionInput))
            addView(field("구성품", componentsInput))
            addView(field("기존 하자", defectsInput))
            addView(field("물품 가액", valueInput))
            addView(field("하루 대여료", priceInput))
            addView(field("보증금", depositInput))
            addView(field("거래 위치", locationInput))
            registrationCoordinateText = textView(registrationCoordinatesLabel(), 12, false, muted)
            addView(registrationCoordinateText!!.withTop(6))
            addView(button("지도에서 거래 위치 선택", secondary = true) {
                openLocationPicker(editing)
            }.withTop(8))
            addView(button(if (editing == null) "물품 등록하기" else "수정 내용 저장") {
                if (!currentUser().verified) {
                    requestIdentityVerification("본인 인증 후 물품을 등록할 수 있습니다.")
                    return@button
                }
                val item = editing?.copy() ?: Item(
                    nextItemId++, "", registerCategory, "", 0, 0, 960, "",
                    currentUser().id, Color.rgb(219, 234, 254)
                )
                item.title = titleInput.text.toString().trim()
                item.category = registerCategory
                item.description = descriptionInput.text.toString().trim()
                item.components = componentsInput.text.toString().trim().ifBlank { null }
                item.knownDefects = defectsInput.text.toString().trim().ifBlank { null }
                item.itemValue = valueInput.text.toString().trim().takeIf(String::isNotBlank)?.toIntOrNull()
                if (componentsInput.text.length > 500 || defectsInput.text.length > 500 ||
                    valueInput.text.isNotBlank() && item.itemValue == null) {
                    toast("구성품·하자는 500자 이내, 물품 가액은 0 이상의 숫자로 입력하세요.")
                    return@button
                }
                item.price = priceInput.text.toString().toIntOrNull() ?: 0
                item.deposit = depositInput.text.toString().toIntOrNull() ?: 0
                item.location = locationInput.text.toString().trim()
                if (editing == null || selectedRegistrationLatitude != null) {
                    item.latitude = selectedRegistrationLatitude ?: currentLatitude
                    item.longitude = selectedRegistrationLongitude ?: currentLongitude
                }
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
                    addView(itemPhoto(item), LinearLayout.LayoutParams(dp(78), dp(78)))
                    addView(column {
                        addView(textView(item.title, 16, true, text))
                        addView(textView("${money(item.price)} / 일 · 보증금 ${money(item.deposit)}", 12, false, muted).withTop(4))
                        addView(statusChip("대여 가능").withTop(7))
                    }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
                })
                addView(row {
                    addView(button("수정", secondary = true) {
                        registrationDraft.clear(); registrationPhotoUris.clear()
                        editingItemId = item.id
                        registerCategory = item.category
                        registerTab = "새 물품"
                        selectedRegistrationLatitude = item.latitude
                        selectedRegistrationLongitude = item.longitude
                        selectedRegistrationAddress = item.location
                        render()
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                    addView(space(8))
                    addView(button("삭제", dangerStyle = true) { confirmDeleteItem(item) }, LinearLayout.LayoutParams(0, dp(42), 1f))
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
        val visible = reservations.filter { it.remoteId !in archivedReservations && it.id !in hiddenDemoReservations }.filter { reservation ->
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
                addView(itemPhoto(item), LinearLayout.LayoutParams(dp(68), dp(68)))
                addView(column {
                    addView(row {
                        addView(statusChip(when (reservation.status) {
                            "cancelled" -> "예약 취소"
                            "rejected" -> "예약 거절"
                            else -> contract?.status?.label ?: if (reservation.approved) "예약 승인" else "승인 대기"
                        }))
                        addView(textView("D-${reservation.id}", 11, true, muted), LinearLayout.LayoutParams(0, wrap, 1f).withLeftMargin(8))
                    })
                    addView(textView(item.title, 16, true, text).withTop(6))
                    addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 12, false, muted).withTop(2))
                }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
            })
            addView(metric("결제 예정 금액", money(reservation.totalAmount)).withTop(12))
            addView(row {
                when {
                    reservation.status in setOf("rejected", "cancelled") -> addView(statusChip(if (reservation.status == "cancelled") "예약 취소" else "예약 거절"), LinearLayout.LayoutParams(0, dp(40), 1f))
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
                    contract.status in setOf(ContractStatus.SIGNED, ContractStatus.READY) -> addView(button("대여 시작하기") {
                        openRentalFlow(contract)
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.RENTING -> {
                        addView(button("대여 중 보기") { openRentalFlow(contract) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                        addView(space(8))
                        addView(button("반납 진행", secondary = true) { openRentalFlow(contract, RentalStep.RETURN_INSPECT) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    }
                    contract.status == ContractStatus.COMPLETED -> addView(button("반납 완료 보기") {
                        openRentalFlow(contract)
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.DISPUTED -> addView(button("분쟁 상태 확인", secondary = true) {
                        activeContractId = contract.id
                        screen = Screen.FEEDBACK
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
                openReservationChat(reservation)
            }.withTop(8))
            addView(button(if (reservation.status in ReservationRules.finishedStatuses) "내 목록에서 삭제" else "거래 삭제", dangerStyle = true) {
                removeReservation(reservation)
            }.withTop(8))
            if (contract != null) addView(paymentStatusCard(contract).withTop(10))
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
        val appliedDeposit = reservation.depositAmount
        val rentalFee = (reservation.totalAmount - appliedDeposit).coerceAtLeast(0)
        val paid = contract.status.ordinal >= ContractStatus.PAID.ordinal
        val signed = contract.status.ordinal >= ContractStatus.SIGNED.ordinal
        content.addView(transactionProgress(if (signed) 2 else if (paid) 1 else 0).withTop(18))
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(row {
                addView(itemPhoto(item), LinearLayout.LayoutParams(dp(64), dp(64)))
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
            addView(metric("보증금", money(appliedDeposit)))
            addView(View(this@MainActivity).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(match, dp(1)).withBottomMargin(8))
            addView(metric("총 결제 금액", money(reservation.totalAmount), blue))
            addView(textView("반납 확인 후 보증금 환급을 요청할 수 있습니다. 환급 상태는 결제 내역에서 확인해주세요.", 12, false, muted).withTop(10))
            if (!paid) addView(button("${money(reservation.totalAmount)} 안전 결제") { pay(contract) }.withTop(14))
            else addView(statusChip("✓ 결제 승인 완료").withTop(12))
        }.withTop(10))
        if (paid) {
            content.addView(textView("전자 대여 계약", 19, true, text).withTop(18))
            // V8 terms must not be presented as if an already-signed legacy contract agreed to them.
            val legacySigned = (contract.ownerSigned || contract.borrowerSigned) &&
                contract.snapshot.optString("terms_version") != ContractTerms.TERMS_VERSION
            if (legacySigned) {
                content.addView(card().apply {
                    addView(textView("기존 서명 계약", 15, true, text))
                    addView(textView("이 계약의 서명 당시 조항은 현재 조항 ${ContractTerms.TERMS_VERSION}과 다를 수 있습니다. 새 조항을 소급 적용하거나 새 계약서 PDF로 표시하지 않습니다.", 13, false, muted).withTop(8))
                    if (signed) addView(button("대여 시작하기") { openRentalFlow(contract) }.withTop(12))
                }.withTop(10))
                return
            }
            content.addView(card().apply {
                addView(textView("계약 주요 내용 · 조항 ${ContractTerms.TERMS_VERSION}", 15, true, text))
                addView(metric("대여 물품", item.title).withTop(8))
                addView(metric("거래 장소", item.location))
                addView(textView("계약 조항 전문 (아래 영역을 스크롤해 확인)", 13, true, text).withTop(12))
                addView(ScrollView(this@MainActivity).apply {
                    addView(column {
                        ContractTerms.clauses.forEach { (title, body) ->
                            addView(textView(title, 13, true, text).withTop(10))
                            addView(textView(body, 12, false, text).withTop(3))
                        }
                    }.apply { setPadding(dp(10), dp(5), dp(10), dp(10)) })
                    background = rounded(Color.rgb(248, 248, 255), 8)
                }, LinearLayout.LayoutParams(match, dp(260)).apply { topMargin = dp(8) })
                val ownSigned = if (item.remoteOwnerId == sessionUserId) contract.ownerSigned else contract.borrowerSigned
                if (!contract.ownerSigned && !contract.borrowerSigned && contract.remoteId != null) {
                    val specialInput = edit(contractSpecialDrafts[contract.id] ?: contract.specialTerms.orEmpty()).apply {
                        hint = "특약사항 (선택, 최대 500자)"
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { contractSpecialDrafts[contract.id] = s.toString() }
                            override fun afterTextChanged(s: Editable?) = Unit
                        })
                    }
                    addView(field("특약사항", specialInput).withTop(10))
                    addView(button("특약 저장", secondary = true) {
                        saveContractSpecialTerms(contract, specialInput.text.toString())
                    }.withTop(8))
                } else addView(metric("특약사항", contract.specialTerms?.ifBlank { "없음" } ?: "없음").withTop(10))
                if (!ownSigned) {
                    val consent = CheckBox(this@MainActivity).apply {
                        text = "계약 조항을 확인했으며 이에 동의합니다"
                        textSize = 13f
                    }
                    addView(consent.withTop(12))
                    addView(textView("아래 영역에 손가락으로 서명하세요.", 13, true, text).withTop(14))
                    val signaturePad = SignaturePadView(this@MainActivity).apply { isEnabled = false; alpha = 0.5f }
                    consent.setOnCheckedChangeListener { _, checked ->
                        signaturePad.isEnabled = checked
                        signaturePad.alpha = if (checked) 1f else 0.5f
                        if (!checked) signaturePad.clear()
                    }
                    addView(signaturePad, LinearLayout.LayoutParams(match, dp(150)).apply { topMargin = dp(8) })
                    addView(row {
                        addView(button("다시 쓰기", secondary = true) { signaturePad.clear() }, LinearLayout.LayoutParams(0, dp(44), 1f))
                        addView(space(8))
                        addView(button("서명·PDF 저장") {
                            if (!consent.isChecked) toast("계약 조항에 동의한 뒤 서명하세요.")
                            else if (!signaturePad.isSigned) toast("전자 서명을 먼저 입력하세요.")
                            else sign(contract, signaturePad.toBitmap())
                        }, LinearLayout.LayoutParams(0, dp(44), 2f))
                    }.withTop(10))
                } else addView(statusChip(if (signed) "양측 서명 완료" else "내 서명 완료 · 상대방 서명 대기").withTop(10))
                if (contract.ownerSigned || contract.borrowerSigned) {
                    addView(button("계약서 PDF 열람", secondary = true) { generateContractPdf(contract, share = false) }.withTop(12))
                    addView(button("계약서 PDF 공유", secondary = true) { generateContractPdf(contract, share = true) }.withTop(8))
                }
                if (signed) {
                    addView(button("대여 시작하기") { openRentalFlow(contract) }.withTop(12))
                }
            }.withTop(10))
        }
    }

    private fun renderChat() {
        chatMessagesContainer = null
        dateRequestsContainer = null
        if (!chatDetailOpen) {
            content.addView(textView("채팅방을 길게 눌러 알림과 고정을 설정하세요.", 13, false, muted).withTop(12))
            val account = sessionUserId
            val visible = remoteChatRooms.filterNot { it.hidden }
                .sortedWith(compareByDescending { ChatRoomSettings.isPinned(this, account, it.reservationId) })
            if (visible.isEmpty()) content.addView(empty("예약한 물품의 대화가 여기에 표시됩니다.").withTop(12))
            visible.forEach { room ->
                val reservation = reservations.find { it.remoteId == room.reservationId } ?: return@forEach
                val item = items.find { it.id == reservation.itemId } ?: return@forEach
                val chatCard = card().apply {
                    addView(row {
                        addView(textView(counterpartyName(reservation), 17, true, text), LinearLayout.LayoutParams(0, wrap, 1f))
                        if (ChatRoomSettings.isMuted(this@MainActivity, account, room.reservationId)) {
                            addView(textView("🔕", 16, false, muted))
                        }
                        if (ChatRoomSettings.isPinned(this@MainActivity, account, room.reservationId)) {
                            addView(textView("📌", 16, false, blue))
                        }
                        addView(textView("⋮", 23, true, muted).apply {
                            contentDescription = "채팅방 메뉴"
                            gravity = Gravity.CENTER
                            setPadding(dp(12), 0, dp(4), 0)
                            setOnClickListener { showChatRoomMenu(room, reservation) }
                        })
                    })
                    addView(textView(item.title, 14, true, blue).withTop(4))
                    addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 12, false, muted))
                    val lastMedia = ChatMedia.fromBody(room.lastBody)
                    addView(textView(lastMedia?.let { if (it.kind == "image") "사진" else "영상" }
                        ?: room.lastBody.ifBlank { "대화를 시작해보세요." }, 14, false, text).withTop(6))
                    if (room.unreadCount > 0) addView(statusChip("새 메시지 ${room.unreadCount}").withTop(4))
                    setOnClickListener { openReservationChat(reservation) }
                    setOnLongClickListener { showChatRoomMenu(room, reservation); true }
                }
                enableSwipeDelete(chatCard) { confirmHideChat(room) }
                content.addView(chatCard.withTop(10))
            }
            return
        }
        val reservation = reservations.find { it.id == selectedChatReservationId }
        val item = reservation?.let { r -> items.find { it.id == r.itemId } }
        if (reservation == null || item == null) { content.addView(empty("예약 목록에서 대화를 선택해주세요.")); return }
        content.addView(button("‹ 채팅 목록", secondary = true) { chatDetailOpen = false; render(); loadRemoteReservations() }.withTop(8))
        content.addView(textView(counterpartyName(reservation), 20, true, text).withTop(12))
        content.addView(card().apply {
            addView(textView(item.title, 17, true, text))
            addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 14, false, muted).withTop(5))
            addView(metric("예약 금액", money(reservation.totalAmount)).withTop(6))
            if (reservation.status in ReservationRules.editableStatuses) addView(button("예약 날짜 변경 제안", secondary = true) { proposeDates(reservation) }.withTop(8))
            else addView(textView("날짜 변경은 결제 전에 상대방과 합의할 수 있습니다.", 12, false, muted).withTop(8))
            if (item.remoteOwnerId != sessionUserId) addView(button("다른 기간으로 바로 예약", secondary = true) {
                showChatReservationDialog(item)
            }.withTop(8))
        }.withTop(10))
        dateRequestsContainer = column { }
        content.addView(dateRequestsContainer)
        renderDateRequests(reservation)
        chatMessagesContainer = column { }
        chatMessagesView = chatMessagesContainer
        content.addView(chatMessagesContainer)
        updateChatMessages()
        val input = edit(chatDrafts[reservation.id].orEmpty()).apply {
            hint = "메시지를 입력하세요"
            maxLines = 4
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { chatDrafts[reservation.id] = s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        chatInput = input
        chatComposer = row {
            addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(space(8))
            addView(coloredButton("+", Color.rgb(245, 247, 251), text) { chooseChatMedia() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(space(8))
            addView(coloredButton("➤", blue, Color.WHITE) { sendChatText(input) }, LinearLayout.LayoutParams(dp(50), dp(48)))
        }.apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(surface)
        }
        root.addView(chatComposer, 1, LinearLayout.LayoutParams(match, wrap))
        if (activityVisible) chatHandler.postDelayed(chatPoll, 5_000L)
    }

    private fun updateChatMessages() {
        val container = chatMessagesView ?: return
        container.removeAllViews()
        chatMessages.forEach { msg -> container.addView(chatBubble(msg).withTop(8)) }
        mainScroll.post { mainScroll.smoothScrollTo(0, content.height) }
    }

    private fun sendChatText(input: EditText) {
        val body = input.text.toString().trim()
        if (body.isBlank()) return
        val token = sessionToken
        val roomId = activeChatRoomId
        if (supabase.isConfigured) {
            if (token == null || roomId == null) {
                toast("채팅방을 연결 중입니다. 잠시 후 다시 시도해주세요.")
                loadRemoteChat()
                return
            }
            Thread {
                val result = runCatching { supabase.sendChatMessage(roomId, body, token) }
                runOnUiThread {
                    if (sessionToken != token || activeChatRoomId != roomId) return@runOnUiThread
                    result.onSuccess {
                        if (chatInput === input && input.text.toString().trim() == body) {
                            input.text.clear()
                            selectedChatReservationId?.let(chatDrafts::remove)
                        }
                        loadRemoteChat()
                    }.onFailure { error -> toast("전송 실패: ${error.message ?: "채팅방 권한을 확인하세요."}") }
                }
            }.start()
        } else {
            chatMessages.add(ChatMessage(true, body))
            input.text.clear()
            selectedChatReservationId?.let(chatDrafts::remove)
            updateChatMessages()
        }
    }

    private fun chooseChatMedia() {
        if (supabase.isConfigured && (activeChatRoomId == null || sessionToken == null)) {
            toast("채팅방을 연결 중입니다. 잠시 후 다시 시도해주세요.")
            return
        }
        if (!supabase.isConfigured) {
            toast("사진·영상 전송은 로그인한 거래에서 사용할 수 있습니다.")
            return
        }
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/jpeg", "image/png", "image/webp", "video/mp4", "video/webm", "video/quicktime"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(picker, chatMediaRequest)
    }

    private fun uploadChatMedia(uri: Uri) {
        val token = sessionToken ?: return
        val roomId = activeChatRoomId ?: return
        val userId = sessionUserId ?: return
        val mimeType = contentResolver.getType(uri).orEmpty()
        toast("첨부파일을 보내는 중입니다.")
        Thread {
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 20 * 1024 * 1024) { "첨부파일은 20MB 이하여야 합니다." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: throw IllegalStateException("선택한 파일을 읽을 수 없습니다.")
                supabase.sendChatMedia(roomId, userId, bytes, mimeType, token)
            }
            runOnUiThread {
                if (sessionToken != token || activeChatRoomId != roomId) return@runOnUiThread
                result.onSuccess { loadRemoteChat() }
                    .onFailure { error -> toast("첨부파일 전송 실패: ${error.message ?: "파일과 권한을 확인하세요."}") }
            }
        }.start()
    }

    private fun openChatMedia(media: ChatMedia) {
        val token = sessionToken ?: return
        toast("첨부파일을 여는 중입니다.")
        Thread {
            val result = runCatching { supabase.downloadChatMedia(media, token) }
            runOnUiThread {
                if (sessionToken != token) return@runOnUiThread
                result.onSuccess { bytes ->
                    if (media.kind == "image") {
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap == null) {
                            toast("사진 형식을 읽을 수 없습니다.")
                        } else {
                            val preview = ImageView(this).apply {
                                setImageBitmap(bitmap)
                                adjustViewBounds = true
                                scaleType = ImageView.ScaleType.FIT_CENTER
                            }
                            AlertDialog.Builder(this).setView(preview).setPositiveButton("닫기", null).show()
                        }
                    } else {
                        runCatching {
                            val dir = File(cacheDir, "chat_media").apply { mkdirs() }
                            val filename = media.path.substringAfterLast('/')
                            val file = File(dir, filename).apply { writeBytes(bytes) }
                            val viewUri = FileProvider.getUriForFile(this, "$packageName.files", file)
                            val mime = when (file.extension.lowercase()) {
                                "webm" -> "video/webm"
                                "mov" -> "video/quicktime"
                                else -> "video/mp4"
                            }
                            startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(viewUri, mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                        }.onFailure { toast("영상을 재생할 앱을 확인해주세요.") }
                    }
                }.onFailure { error -> toast("첨부파일 열기 실패: ${error.message ?: "권한을 확인하세요."}") }
            }
        }.start()
    }

    private fun showChatReservationDialog(item: Item) {
        var start = reservationStartDate
        var end = reservationEndDate
        val panel = column { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val startButton = button("시작  $start", secondary = true) { }
        val endButton = button("종료  $end", secondary = true) { }
        val amount = textView("", 13, true, text)
        fun updateSummary() {
            startButton.text = "시작  $start"
            endButton.text = "종료  $end"
            val days = rentalDays(start, end)
            val deposit = adjustedDeposit(item.deposit, calculateTrustScore(currentUser()))
            amount.text = "${days}일 · 대여료 ${money(item.price * days)} · 적용 보증금 ${money(deposit)}"
        }
        startButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                start = LocalDate.of(year, month + 1, day)
                if (end.isBefore(start)) end = start
                updateSummary()
            }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
                datePicker.minDate = System.currentTimeMillis() - 1_000L
            }.show()
        }
        endButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                val selected = LocalDate.of(year, month + 1, day)
                if (selected.isBefore(start)) toast("종료일은 시작일보다 빠를 수 없습니다.")
                else {
                    end = selected
                    updateSummary()
                }
            }, end.year, end.monthValue - 1, end.dayOfMonth).apply {
                datePicker.minDate = System.currentTimeMillis() - 1_000L
            }.show()
        }
        panel.addView(startButton.withTop(8))
        panel.addView(endButton.withTop(8))
        panel.addView(amount.withTop(12))
        updateSummary()
        AlertDialog.Builder(this)
            .setTitle("채팅에서 예약")
            .setView(panel)
            .setNegativeButton("취소", null)
            .setPositiveButton("대여 신청") { _, _ ->
                reservationStartDate = start
                reservationEndDate = end
                createReservation(item)
            }
            .show()
    }

    private fun stepFor(contract: Contract): RentalStep? = RentalFlowRules.stepFor(contract.status.name, contract.beforePhotos)

    private fun isRentalOwner(contract: Contract): Boolean {
        val reservation = reservations.find { it.id == contract.reservationId } ?: return false
        val item = itemById(reservation.itemId)
        return if (item.remoteId != null) item.remoteOwnerId == sessionUserId else item.ownerId == currentUser().id
    }

    private fun inspectionComplete(contract: Contract): Boolean =
        inspectionChecks[contract.id]?.let { it.first && it.second } == true

    private fun canEnterRentalStep(contract: Contract, step: RentalStep): Boolean = RentalFlowRules.canEnter(
        step, contract.status.name, contract.beforePhotos, contract.afterPhotos,
        isRentalOwner(contract), inspectionComplete(contract)
    )

    private fun openRentalFlow(contract: Contract, requested: RentalStep? = null) {
        activeContractId = contract.id
        if (contract.status == ContractStatus.DISPUTED) {
            screen = Screen.FEEDBACK
        } else {
            val entry = stepFor(contract) ?: run {
                screen = Screen.TRANSACTION
                toast("계약과 결제를 먼저 완료하세요.")
                render()
                return
            }
            rentalStep = requested?.takeIf { canEnterRentalStep(contract, it) } ?: entry
            screen = Screen.RENTAL_FLOW
        }
        render()
    }

    private fun moveRentalStep(contract: Contract, next: RentalStep) {
        if (!canEnterRentalStep(contract, next)) {
            toast("사진 기록과 거래 상태를 먼저 확인해주세요.")
            return
        }
        rentalStep = next
        render()
    }

    private fun backRentalFlow() {
        val contract = activeContract()
        val previous = RentalFlowRules.backFrom(rentalStep)
        if (contract != null && previous != null && canEnterRentalStep(contract, previous)) rentalStep = previous
        else screen = Screen.RESERVATIONS
        render()
    }

    private fun scheduleRentalPoll() {
        chatHandler.removeCallbacks(rentalPoll)
        if (activityVisible && screen == Screen.RENTAL_FLOW &&
            rentalStep in setOf(RentalStep.HANDOVER_QR, RentalStep.RETURN_QR) && activeContract()?.remoteId != null) {
            chatHandler.postDelayed(rentalPoll, 4_000L)
        }
    }

    private fun rentalStepTitle(): String {
        val owner = activeContract()?.let(::isRentalOwner) == true
        return when (rentalStep) {
            RentalStep.HANDOVER_PHOTOS -> if (owner) "물품 인도 전 상태 기록" else "물품 수령 전 상태 확인"
            RentalStep.HANDOVER_QR -> if (owner) "물품 인도 QR 인증" else "물품 수령 QR 인증"
            RentalStep.STARTED -> "대여가 시작되었습니다"
            RentalStep.RENTING -> "대여 중"
            RentalStep.RETURN_INSPECT -> if (owner) "반납 물품 검수" else "반납 전 상태 기록"
            RentalStep.RETURN_QR -> if (owner) "반납 확인 QR 인증" else "반납 QR 인증"
            RentalStep.RETURNED -> "반납이 완료되었습니다"
        }
    }

    private fun rentalStepSubtitle(): String = when (rentalStep) {
        RentalStep.HANDOVER_PHOTOS -> "물품 상태를 사진으로 남깁니다."
        RentalStep.HANDOVER_QR -> "상대방과 QR로 물품 인도를 확인합니다."
        RentalStep.STARTED -> "대여 시작이 확인되었습니다."
        RentalStep.RENTING -> "반납 예정일과 결제 상태를 확인합니다."
        RentalStep.RETURN_INSPECT -> "반납 상태를 기록하고 확인합니다."
        RentalStep.RETURN_QR -> "상대방과 QR로 반납을 확인합니다."
        RentalStep.RETURNED -> "반납 완료와 보증금 환급은 별도 단계입니다."
    }

    private fun rentalProgress(): LinearLayout = row {
        val current = RentalFlowRules.progressIndex(rentalStep)
        listOf("인도", "대여 중", "반납", "완료").forEachIndexed { index, label ->
            addView(textView("${if (index <= current) "●" else "○"}\n$label", 11, true, if (index <= current) blue else muted).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(0, wrap, 1f))
        }
    }

    private fun renderRentalFlow() {
        val contract = activeContract()
        if (contract == null) {
            content.addView(empty("진행 중인 계약이 없습니다.").withTop(16))
            return
        }
        if (contract.status == ContractStatus.DISPUTED) {
            screen = Screen.FEEDBACK
            render()
            return
        }
        if (!canEnterRentalStep(contract, rentalStep)) {
            rentalStep = stepFor(contract) ?: run {
                content.addView(empty("계약과 결제를 먼저 완료하세요.").withTop(16))
                return
            }
        }
        content.addView(rentalProgress().withTop(16))
        when (rentalStep) {
            RentalStep.HANDOVER_PHOTOS -> renderHandoverPhotos(contract)
            RentalStep.HANDOVER_QR -> renderHandoverQr(contract)
            RentalStep.STARTED -> renderRentalStarted(contract)
            RentalStep.RENTING -> renderRenting(contract)
            RentalStep.RETURN_INSPECT -> renderReturnInspect(contract)
            RentalStep.RETURN_QR -> renderReturnQr(contract)
            RentalStep.RETURNED -> renderReturned(contract)
        }
    }

    private fun renderConditionPhotos(contract: Contract, after: Boolean) {
        val count = if (after) contract.afterPhotos else contract.beforePhotos
        val phase = if (after) "after" else "before"
        content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(textView(if (after) "반납 후 상태 사진" else "대여 전 상태 사진", 19, true, text))
            addView(textView(if (after) "반납 직후 같은 각도로 촬영하면 분쟁을 예방할 수 있어요." else "물품 전체와 흠집, 구성품을 최대 5장으로 남겨주세요.", 12, false, muted).withTop(4))
            if (contract.remoteId == null) {
                addView(metric("시연 상태 사진", "$count/5").withTop(12))
                addView(button("상태 사진 기록 시연") {
                    if (!after && contract.status in setOf(ContractStatus.SIGNED, ContractStatus.READY)) {
                        contract.beforePhotos = 5
                        contract.status = ContractStatus.READY
                        render()
                    } else if (after && contract.status == ContractStatus.RENTING) {
                        contract.afterPhotos = 5
                        render()
                    }
                }.apply { isEnabled = count < 5 }.withTop(10))
            } else {
                val paths = if (after) contract.afterPhotoPaths else contract.beforePhotoPaths
                addView(photoSlots(paths) { openPhotoPicker(contract, phase) }.withTop(12))
                addView(button(if (conditionPhotoUploading) "사진 업로드 중…" else "카메라로 상태 사진 촬영  $count/5") {
                    openPhotoPicker(contract, phase)
                }.apply { isEnabled = count < 5 && !conditionPhotoUploading }.withTop(10))
                addView(button("갤러리에서 선택", secondary = true) { openPhotoPicker(contract, phase, camera = false) }
                    .apply { isEnabled = count < 5 && !conditionPhotoUploading }.withTop(8))
                if (pendingPhotoUri != null && !conditionPhotoAwaitingResult && !conditionPhotoUploading && pendingPhotoContractId == contract.remoteId && pendingPhotoPhase == phase) {
                    addView(button("사진 업로드 다시 시도", secondary = true) { uploadPendingConditionPhoto() }.withTop(8))
                }
            }
        }.withTop(12))
    }

    private fun renderHandoverPhotos(contract: Contract) {
        renderConditionPhotos(contract, after = false)
        content.addView(button("다음 · QR 인증") { moveRentalStep(contract, RentalStep.HANDOVER_QR) }
            .apply { isEnabled = contract.beforePhotos > 0 }.withTop(12))
    }

    private fun renderQrStage(contract: Contract, action: String) {
        val isReturn = action == "end"
        content.addView(card().apply {
            addView(textView(if (isReturn) "QR로 반납을 확인해요" else "QR로 물품 인도를 확인해요", 19, true, text))
            addView(textView("상대방 화면의 QR을 스캔하거나 내 QR을 보여주세요.", 12, false, muted).withTop(4))
            if (contract.remoteId == null) {
                addView(button("QR 인증 시연") {
                    contract.status = if (isReturn) ContractStatus.COMPLETED else ContractStatus.RENTING
                    rentalStep = if (isReturn) RentalStep.RETURNED else RentalStep.STARTED
                    render()
                }.withTop(12))
            } else {
                val issued = qrPayloads["${contract.remoteId}:$action"]?.takeIf { it.expiresAt.isAfter(Instant.now()) }
                if (issued != null) {
                    val bitmap = BarcodeEncoder().encodeBitmap(issued.payload, BarcodeFormat.QR_CODE, dp(240), dp(240))
                    addView(ImageView(this@MainActivity).apply {
                        setImageBitmap(bitmap)
                        contentDescription = "상대방이 스캔할 거래 인증 QR"
                    }, LinearLayout.LayoutParams(match, dp(260)).apply { topMargin = dp(12) })
                    addView(textView("상대방만 인증 가능 · 한 번만 사용 가능", 12, true, muted).withTop(8))
                } else addView(textView("인증 QR을 만들어 상대방에게 보여주세요.", 13, false, muted).withTop(12))
                addView(button(if (qrRequestInProgress) "처리 중" else "새 QR 만들기", secondary = true) {
                    issueServerQr(contract, action)
                }.apply { isEnabled = !qrRequestInProgress }.withTop(12))
                addView(button(if (isReturn) "반납 QR 스캔" else "대여 시작 QR 스캔", goodStyle = isReturn) {
                    startQrScan(contract, action)
                }.apply { isEnabled = !qrRequestInProgress }.withTop(12))
                addView(textView("상대방이 인증하면 화면이 자동으로 넘어갑니다 (최대 약 4초).", 12, false, muted).withTop(8))
            }
        }.withTop(12))
    }

    private fun renderHandoverQr(contract: Contract) {
        renderQrStage(contract, "start")
        content.addView(paymentStatusCard(contract).withTop(12))
    }

    private fun rentalDueLabel(reservation: Reservation): String {
        val days = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(reservation.endDate)) }.getOrNull()
        return when {
            days == null -> reservation.endDate
            days > 0 -> "${reservation.endDate} (D-$days)"
            days == 0L -> "${reservation.endDate} (오늘)"
            else -> "${reservation.endDate} (${-(days)}일 지남)"
        }
    }

    private fun rentalSummary(contract: Contract): LinearLayout = card().apply {
        val reservation = reservations.find { it.id == contract.reservationId } ?: return@apply
        addView(metric("대여료", money((reservation.totalAmount - reservation.depositAmount).coerceAtLeast(0))))
        addView(metric("대여 기간", "${reservation.startDate} ~ ${reservation.endDate}"))
        addView(metric("반납 예정일", rentalDueLabel(reservation)))
    }

    private fun depositHeldCard(contract: Contract): LinearLayout = card(Color.rgb(239, 246, 255)).apply {
        val reservation = reservations.find { it.id == contract.reservationId } ?: return@apply
        val payment = paymentStates[reservation.remoteId]
        if (payment?.status == "paid" && paymentStateError == null) {
            addView(textView("보증금 ${money(reservation.depositAmount)} 확보 완료", 18, true, blue))
            addView(textView("보증금은 ShareGuard가 보관하며, 반납 시 물품에 문제가 있으면 이 금액에서 보상받을 수 있습니다.", 12, false, muted).withTop(8))
        } else {
            addView(textView("결제 상태 확인 중", 17, true, warn))
            addView(textView("보증금 확보 여부를 아직 확인하지 못했습니다.", 12, false, muted).withTop(6))
            paymentStateError?.let { addView(textView(it, 12, false, warn).withTop(6)) }
            if (reservation.remoteId != null) addView(button(if (paymentRefreshInProgress) "확인 중…" else "결제 상태 새로고침", secondary = true) {
                if (!paymentRefreshInProgress) loadPaymentStates()
            }.apply { isEnabled = !paymentRefreshInProgress }.withTop(8))
        }
        addView(metric("대여료", money((reservation.totalAmount - reservation.depositAmount).coerceAtLeast(0))).withTop(10))
        addView(metric("대여 기간", "${reservation.startDate} ~ ${reservation.endDate}"))
        addView(metric("반납 예정일", rentalDueLabel(reservation)))
    }

    private fun renderRentalStarted(contract: Contract) {
        content.addView(card(Color.rgb(240, 253, 244)).apply {
            addView(textView("✓ 대여가 시작되었습니다", 21, true, good))
            addView(textView("QR 인증으로 물품 인도가 확인되었습니다.", 13, false, muted).withTop(8))
        }.withTop(12))
        content.addView(if (isRentalOwner(contract)) depositHeldCard(contract).withTop(12) else rentalSummary(contract).withTop(12))
        content.addView(button("확인 · 대여 중 보기") { moveRentalStep(contract, RentalStep.RENTING) }.withTop(12))
    }

    private fun renderRenting(contract: Contract) {
        content.addView(if (isRentalOwner(contract)) depositHeldCard(contract).withTop(12) else rentalSummary(contract).withTop(12))
        content.addView(button("반납 진행") { moveRentalStep(contract, RentalStep.RETURN_INSPECT) }.withTop(12))
        content.addView(paymentStatusCard(contract).withTop(12))
    }

    private fun renderReturnInspect(contract: Contract) {
        renderConditionPhotos(contract, after = true)
        val owner = isRentalOwner(contract)
        val next = button("다음 · 반납 QR 인증") { moveRentalStep(contract, RentalStep.RETURN_QR) }
        if (owner) {
            val checks = inspectionChecks[contract.id] ?: (false to false)
            content.addView(card().apply {
                addView(textView("반납 검수 체크리스트", 17, true, text))
                val components = CheckBox(this@MainActivity).apply {
                    text = "구성품이 모두 반납되었습니다"
                    isChecked = checks.first
                    setOnCheckedChangeListener { _, checked ->
                        val old = inspectionChecks[contract.id] ?: (false to false)
                        inspectionChecks[contract.id] = checked to old.second
                        next.isEnabled = canEnterRentalStep(contract, RentalStep.RETURN_QR)
                    }
                }
                val damage = CheckBox(this@MainActivity).apply {
                    text = "새로운 파손이나 고장이 없습니다"
                    isChecked = checks.second
                    setOnCheckedChangeListener { _, checked ->
                        val old = inspectionChecks[contract.id] ?: (false to false)
                        inspectionChecks[contract.id] = old.first to checked
                        next.isEnabled = canEnterRentalStep(contract, RentalStep.RETURN_QR)
                    }
                }
                addView(components.withTop(8))
                addView(damage)
            }.withTop(12))
            content.addView(button("문제가 있어요", dangerStyle = true) {
                screen = Screen.FEEDBACK
                render()
            }.withTop(12))
        }
        content.addView(next.apply { isEnabled = canEnterRentalStep(contract, RentalStep.RETURN_QR) }.withTop(12))
    }

    private fun renderReturnQr(contract: Contract) {
        renderQrStage(contract, "end")
        content.addView(paymentStatusCard(contract).withTop(12))
    }

    private fun renderReturned(contract: Contract) {
        content.addView(card(Color.rgb(240, 253, 244)).apply {
            addView(textView("✓ 반납이 완료되었습니다", 21, true, good))
            addView(textView("반납 확인과 보증금 환급 완료는 다른 상태입니다.", 13, false, muted).withTop(8))
        }.withTop(12))
        content.addView(paymentStatusCard(contract, showRefundButton = false).withTop(12))
        val reservation = reservations.find { it.id == contract.reservationId }
        val id = reservation?.remoteId
        val payment = paymentStates[id]
        if (isRentalOwner(contract)) {
            content.addView(button("이상 없음, 보증금 환급 승인") { requestRefund(contract) }
                .apply { isEnabled = payment?.refundAllowed == true && id !in refundRequests && !paymentRefreshInProgress }
                .withTop(12))
            content.addView(button("문제 신고", dangerStyle = true) {
                screen = Screen.FEEDBACK
                render()
            }.withTop(8))
        }
        if (payment?.refunded == true || contract.remoteId == null) {
            content.addView(button("후기 작성", secondary = true) {
                screen = Screen.FEEDBACK
                render()
            }.withTop(12))
        }
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
                addView(itemPhoto(item), LinearLayout.LayoutParams(dp(60), dp(60)))
                addView(column {
                    addView(textView(item.title, 16, true, text))
                    addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 12, false, muted).withTop(3))
                    addView(statusChip(contract.status.label).withTop(5))
                }.apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, wrap, 1f))
            })
        }.withTop(14))
        content.addView(textView("거래는 어떠셨나요?", 21, true, text).withTop(18))
        content.addView(textView("후기는 상대방의 신뢰 지수에 반영됩니다.", 12, false, muted).withTop(3))
        val reviewInput = edit(reviewComments[reservation.id].orEmpty()).apply {
            hint = "거래 경험을 남겨주세요."
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { reviewComments[reservation.id] = s.toString() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        content.addView(card().apply {
            val scoreLabel = textView("별을 눌러 1~5점을 선택해주세요.", 13, true, text).apply { gravity = Gravity.CENTER }
            val stars = (1..5).map { score -> Button(this@MainActivity).apply {
                textSize = 28f
                setPadding(0, 0, 0, 0)
                minWidth = 0
                contentDescription = "별점 ${score}점"
            } }
            fun updateStars() {
                val score = reviewRatings[reservation.id] ?: 0
                stars.forEachIndexed { index, star ->
                    star.text = if (index < score) "★" else "☆"
                    star.setTextColor(if (index < score) warn else muted)
                    star.isSelected = index + 1 == score
                }
                scoreLabel.text = if (score == 0) "별을 눌러 1~5점을 선택해주세요." else "$score / 5점"
            }
            stars.forEachIndexed { index, star -> star.setOnClickListener {
                reviewRatings[reservation.id] = index + 1
                updateStars()
            } }
            addView(row { stars.forEach { addView(it, LinearLayout.LayoutParams(0, dp(56), 1f)) } })
            addView(scoreLabel.withTop(4))
            updateStars()
            addView(field("상세 후기", reviewInput).withTop(12))
            addView(button("후기 등록하기") {
                if (contract.status != ContractStatus.COMPLETED) toast("반납 완료 후 후기를 작성할 수 있습니다.")
                else createReview(reservation, reviewRatings[reservation.id] ?: 0, reviewInput.text.toString().trim())
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
                markNotificationsRead(notifications.filter { it.unread })
            }, LinearLayout.LayoutParams(dp(112), dp(40)))
        }.withTop(14))
        content.addView(segmentedTabs(listOf("전체", "거래", "채팅"), notificationFilter) { filter ->
            notificationFilter = filter
            render()
        }.withTop(12))
        val visible = notifications.filter { notification ->
            when (notificationFilter) {
                "거래" -> notification.target in setOf(Screen.RESERVATIONS, Screen.TRANSACTION, Screen.RENTAL_FLOW, Screen.FEEDBACK)
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
                    markNotificationsRead(listOf(notification))
                    pendingNotificationReservation = notification.reservationId
                    pendingNotificationKind = if (notification.target == Screen.CHAT) "chat" else "reservation"
                    screen = notification.target
                    notification.contractId?.let { activeContractId = it }
                    if (screen == Screen.RENTAL_FLOW) {
                        val contract = notification.contractId?.let { contractId -> contracts.find { it.id == contractId } }
                            ?: notification.reservationId?.let { remoteId ->
                            val reservation = reservations.find { it.remoteId == remoteId }
                            contracts.find { it.reservationId == reservation?.id }
                        } ?: activeContract()
                        if (contract != null) {
                            activeContractId = contract.id
                            rentalStep = stepFor(contract) ?: RentalStep.HANDOVER_PHOTOS
                        } else screen = Screen.RESERVATIONS
                    }
                    chatDetailOpen = false
                    render(); loadRemoteReservations()
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
            val prefs = getSharedPreferences("shareguard_settings", MODE_PRIVATE)
            addView(textView("알림 설정", 16, true, text))
            val enabled = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
            addView(metric("거래·채팅 알림", if (enabled) "켜짐" else "권한 필요").withTop(8))
            addView(metric("기기 거래 알림", if (prefs.getBoolean("trade_notifications", true)) "켜짐" else "꺼짐"))
            addView(metric("기기 채팅 알림", if (prefs.getBoolean("chat_notifications", true)) "켜짐" else "꺼짐"))
            addView(textView("앱 사용 중에는 새 소식을 확인하고, 백그라운드에서는 주기적으로 확인합니다. 절전 상태에 따라 알림이 늦어질 수 있습니다.", 12, false, muted).withTop(8))
            addView(button("알림 권한 설정", secondary = true) {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }.withTop(8))
            addView(metric("혜택 및 마케팅", if (prefs.getBoolean("marketing_notifications", false)) "켜짐" else "꺼짐"))
        }.withTop(16))
    }

    private fun renderProfile() {
        if (supabase.isConfigured) {
            renderServerProfile()
            return
        }
        val user = currentUser()
        user.trust = calculateTrustScore(user)
        val discount = depositDiscountPercent(user.trust)
        content.addView(card().apply {
            addView(textView(user.name, 24, true, text))
            if (sessionUserId != null) addView(button("닉네임 수정", secondary = true) { editNickname() }.withTop(8))
            addView(textView("${user.role} · ${if (user.verified) "본인 인증 완료" else "본인 인증 필요"}", 14, false, muted).withTop(2))
            addView(textView("${user.trust} / 1000", 28, true, blue).withTop(14))
            addView(metric("거래 이력", "${user.deals}건"))
            addView(metric("평균 평점", user.rating.toString()))
            addView(metric("무사고 기록", "${user.noAccidentDays}일"))
            addView(metric("본인 인증 점수", if (user.verified) "+150" else "+0"))
            addView(metric("거래·평점·무사고", "+${(user.deals * 8).coerceAtMost(160) + (((user.rating - 3.0).coerceAtLeast(0.0)) * 80).roundToInt().coerceAtMost(160) + (user.noAccidentDays / 2).coerceAtMost(120)}"))
            addView(metric("분쟁 감점", "-${reports.count { it.open } * 80}"))
            addView(metric("보증금 혜택", if (discount > 0) "보증금 $discount% 감면 (최대 10%)" else "신뢰 지수 600부터 적용"))
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
                addView(textView("앱을 종료하거나 업데이트해도 로그인은 유지됩니다. ${INACTIVITY_DAYS}일 미사용 또는 세션 만료 시 다시 로그인해주세요.", 12, false, muted).withTop(4))
                addView(metric("본인 인증", if (user.verified) "이 계정에 저장됨" else "인증 필요").withTop(8))
                addView(metric("토큰 관리", "자동 갱신"))
            }.withTop(12))
        }
        if (BuildConfig.DEBUG && user.role == "관리자") content.addView(card(Color.rgb(248, 248, 255)).apply {
            addView(textView("운영자 도구", 16, true, text))
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
        addMyPostsToProfile()
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("shareguard_settings", MODE_PRIVATE)
        lateinit var dialog: AlertDialog
        val panel = column {
            setPadding(dp(18), dp(8), dp(18), dp(8))
            listOf(
                Triple("기기 거래 알림", "trade_notifications", true),
                Triple("기기 채팅 알림", "chat_notifications", true),
                Triple("혜택 및 마케팅", "marketing_notifications", false)
            ).forEach { (label, key, defaultValue) ->
                addView(Switch(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    isChecked = prefs.getBoolean(key, defaultValue)
                    setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
                }.withTop(10))
            }
            addView(textView("기기 알림 표시 여부에 적용됩니다. 앱 알림함의 거래 기록은 유지됩니다.", 11, false, muted).withTop(12))
            if (!sessionToken.isNullOrBlank() && profileLoaded && currentUser().verified) {
                addView(button("PASS 인증 다시 확인", secondary = true) {
                    dialog.dismiss()
                    startPassVerification()
                }.withTop(16))
                addView(textView("기존 인증 상태와 신뢰점수는 유지됩니다.", 11, false, muted).withTop(6))
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("설정")
            .setView(panel)
            .setNegativeButton("닫기", null)
            .apply {
                if (!sessionToken.isNullOrBlank()) setPositiveButton("로그아웃") { _, _ -> signOut() }
            }
            .create()
        dialog.show()
    }

    private fun addMyPostsToProfile() {
        val mine = items.filter { it.available && (it.remoteOwnerId == sessionUserId && sessionUserId != null ||
                !supabase.isConfigured && it.ownerId == currentUser().id) }
        content.addView(sectionTitle("내 게시물", "등록한 물품을 확인하거나 삭제합니다.").withTop(16))
        if (mine.isEmpty()) {
            content.addView(empty("등록한 게시물이 없습니다.").withTop(8))
            return
        }
        mine.forEach { item ->
            content.addView(card().apply {
                addView(textView(item.title, 15, true, text))
                addView(textView("${money(item.price)} / 일", 12, false, muted).withTop(4))
                addView(row {
                    addView(button("보기", secondary = true) {
                        selectedItemId = item.id
                        screen = Screen.DETAIL
                        render()
                    }, LinearLayout.LayoutParams(0, dp(42), 1f))
                    addView(space(8))
                    addView(button("삭제", dangerStyle = true) { confirmDeleteItem(item) }, LinearLayout.LayoutParams(0, dp(42), 1f))
                }.withTop(8))
            }.withTop(8))
        }
    }

    private fun confirmDeleteItem(item: Item) {
        AlertDialog.Builder(this)
            .setTitle("게시물 삭제")
            .setMessage("'${item.title}'을(를) 목록에서 숨길까요? 진행 중인 거래 기록은 유지됩니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ -> deleteItem(item) }
            .show()
    }

    private fun renderAdmin() {
        if (!BuildConfig.DEBUG || (supabase.isConfigured && (!profileLoaded || serverUser.role != "관리자"))) {
            content.addView(empty("관리자 계정에서 사용할 수 있습니다.").withTop(14))
            return
        }
        val allReports = if (!supabase.isConfigured && reports.isEmpty()) listOf(demoAdminReport) else reports.toList()
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
            addView(adminMetricCard("조회된 신고", allReports.size.toString(), good), LinearLayout.LayoutParams(0, wrap, 1f))
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
                            addView(textView("신고 상세 확인  ›", 11, true, blue))
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
            addView(metric("서버 신고 ID", report.remoteId ?: "시연 데이터").withTop(12))
        }.withTop(12))
        content.addView(textView("거래 및 증거 자료", 19, true, text).withTop(18))
        content.addView(card().apply {
            val contract = contracts.find { it.id == report.contractId }
            val reservation = contract?.let { c -> reservations.find { it.id == c.reservationId } }
            if (reservation == null) {
                addView(textView("연결된 거래 자료를 확인한 뒤 처리해주세요.", 13, false, muted))
            } else {
                addView(metric("물품", itemById(reservation.itemId).title))
                addView(metric("보증금", money(reservation.depositAmount)))
                addView(metric("상태 사진", "대여 전 ${contract.beforePhotos}장 · 반납 후 ${contract.afterPhotos}장"))
            }
        }.withTop(10))
        content.addView(textView("처리 결정", 19, true, text).withTop(18))
        content.addView(card().apply {
            val deductionInput = edit("50000").apply { inputType = InputType.TYPE_CLASS_NUMBER }
            val noteInput = edit("대여 전후 사진과 채팅 기록을 검토한 관리자 결정입니다.")
            addView(field("차감 금액", deductionInput))
            addView(field("처리 근거 및 메모", noteInput))
            addView(button("신고 기각 · 환급 요청 허용", goodStyle = true) {
                resolveAdminReport(report, "dismissed", 0, noteInput.text.toString(), "none")
            })
            addView(button("일부 차감 · 게시글 숨김", dangerStyle = true) {
                val amount = deductionInput.text.toString().toIntOrNull() ?: 0
                resolveAdminReport(report, "partial", amount, noteInput.text.toString(), "hide_item")
            }.withTop(8))
            addView(button("신고 인용 · 사용자 정지", dangerStyle = true) {
                val amount = deductionInput.text.toString().toIntOrNull() ?: 0
                resolveAdminReport(report, "uphold", amount, noteInput.text.toString(), "suspend_user")
            }.withTop(8))
            addView(button("추가 자료 요청", secondary = true) {
                if (supabase.isConfigured) {
                    toast("추가 자료 요청 전송 기능은 아직 연결되지 않았습니다.")
                    return@button
                }
                report.status = "자료 요청"
                addNotification("📎", "관리자가 추가 자료를 요청했어요", "분쟁 검토를 위해 물품 상태 사진을 추가해주세요.", Screen.FEEDBACK)
                toast("양측 사용자에게 자료 요청 알림을 보냈습니다.")
                selectedAdminReportId = null
                render()
            }.withTop(8))
        }.withTop(10))
    }

    private fun renderAdminTransactions() {
        if (supabase.isConfigured) {
            content.addView(textView("조회된 거래 ${reservations.size}건", 18, true, text).withTop(18))
            reservations.forEach { content.addView(reservationCard(it).withTop(10)) }
            return
        }
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
        if (supabase.isConfigured) {
            content.addView(textView("조회된 사용자", 18, true, text).withTop(18))
            ownerProfiles.values.forEach { profile ->
                content.addView(card().apply {
                    addView(textView(profile.displayName, 16, true, text))
                    addView(textView("${if (profile.verified) "인증 완료" else "미인증"} · 신뢰 ${profile.trustScore} · 평점 ${profile.rating}", 12, false, muted).withTop(5))
                }.withTop(10))
            }
            return
        }
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
            box.addView(paymentStatusCard(contract).withTop(10))
        }
        return box
    }

    private fun flowSummary(): LinearLayout {
        val contract = contracts.lastOrNull()
        val active = contract?.status
        val steps = listOf(
            ContractStatus.APPROVED to "예약 승인",
            ContractStatus.PAID to "결제 승인",
            ContractStatus.SIGNED to "전자 계약",
            ContractStatus.READY to "상태 사진",
            ContractStatus.RENTING to "QR 대여 인증",
            ContractStatus.COMPLETED to "반납 확인"
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
        if (sessionUserId != userId) {
            clearAccountViews()
            reservations.clear()
            contracts.clear()
            paymentStates.clear()
            qrPayloads.clear()
            reports.clear()
            notifications.clear()
            chatMessages.clear()
            activeContractId = null
            activeChatRoomId = null
        }
        sessionToken = token
        sessionUserId = userId
        resetServerProfile()
        val editor = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putLong(KEY_EXPIRES_AT, auth.expiresAt)
            .putLong(KEY_LAST_ACTIVE_AT, System.currentTimeMillis())
            .putInt(KEY_APP_VERSION, BuildConfig.VERSION_CODE)
        auth.refreshToken?.let { editor.putString(KEY_REFRESH_TOKEN, it) }
        editor.apply()
        NotificationDelivery.schedule(this)
        val settings = getSharedPreferences("notification_settings", MODE_PRIVATE)
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !settings.getBoolean("permission_asked", false)) {
            settings.edit().putBoolean("permission_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9010)
        }
        return true
    }

    private fun refreshPersistedSession() {
        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        preferences.getString(KEY_REFRESH_TOKEN, null) ?: run {
            loadCurrentProfile()
            loadRemoteItems()
            return
        }
        val previousToken = sessionToken
        Thread {
            val result = runCatching { SessionTokens.refresh(this, supabase) }
            runOnUiThread {
                if (sessionToken != previousToken) return@runOnUiThread
                result.onSuccess { auth ->
                    if (auth.isSignedIn && persistAuthenticatedSession(auth)) {
                        loadCurrentProfile()
                        loadRemoteItems()
                    } else {
                        clearStoredSession(preferences)
                        sessionToken = null
                        sessionUserId = null
                        resetServerProfile()
                        verificationStep = VerificationStep.NONE
                        toast("보안 세션이 만료되어 다시 로그인이 필요합니다.")
                        render()
                    }
                }.onFailure {
                    // A temporary network failure must not sign the user out.
                    loadCurrentProfile()
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

    private fun authenticate(email: String, password: String, createAccount: Boolean, nickname: String = "") {
        if (createAccount && nickname.trim().length !in 2..30) { toast("닉네임은 2~30자로 입력하세요."); return }
        if (!email.contains('@') || password.length < 8) {
            toast("이메일과 8자 이상 비밀번호를 입력하세요.")
            return
        }
        toast(if (createAccount) "회원가입 중입니다." else "로그인 중입니다.")
        Thread {
            val result = runCatching {
                if (createAccount) {
                    supabase.signUp(email, password, nickname.trim())
                } else {
                    supabase.signIn(email, password)
                }
            }
            runOnUiThread {
                result.onSuccess { auth ->
                    if (auth.isSignedIn) {
                        if (!persistAuthenticatedSession(auth)) return@onSuccess
                        authPanel = AuthPanel.CLOSED
                        verificationStep = VerificationStep.NONE
                        loadCurrentProfile()
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

    // Start Kakao's hosted OAuth flow through the existing Supabase Auth project.
    // This deliberately does not initialize another Kakao Android login SDK.
    private fun startKakaoLogin() {
        if (!supabase.isConfigured) {
            toast("Supabase 설정을 확인해주세요.")
            return
        }
        val verifierBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(KAKAO_CODE_VERIFIER, verifier)
            .apply()

        val oauthUri = Uri.parse(BuildConfig.SUPABASE_URL.trimEnd('/') + "/auth/v1/authorize")
            .buildUpon()
            .appendQueryParameter("provider", "kakao")
            .appendQueryParameter("redirect_to", KAKAO_OAUTH_REDIRECT_URI)
            .appendQueryParameter("flow_type", "pkce")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, oauthUri))
        } catch (error: android.content.ActivityNotFoundException) {
            toast("카카오 로그인 화면을 열 수 없습니다. 브라우저를 확인해주세요.")
        }
    }

    // Return true only for our Kakao Deep Link. Google keeps its separate callback.
    private fun handleKakaoOAuthRedirect(callbackIntent: Intent?): Boolean {
        val uri = callbackIntent?.data ?: return false
        if (uri.scheme != "shareguard" || uri.host != "auth-callback") return false

        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        val verifier = preferences.getString(KAKAO_CODE_VERIFIER, null)
        // Consume the one-time verifier even on a failed callback.
        preferences.edit().remove(KAKAO_CODE_VERIFIER).apply()
        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            toast("카카오 로그인 실패: $error")
            return true
        }
        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
            toast("카카오 인증 정보를 확인할 수 없습니다. 다시 로그인해주세요.")
            return true
        }

        toast("카카오 로그인 처리 중입니다.")
        Thread {
            // The integration branch already has an equivalent PKCE token exchange method.
            val result = runCatching { supabase.exchangeCodeForSession(code, verifier) }
            runOnUiThread {
                result.onSuccess { auth ->
                    if (auth.isSignedIn && !auth.userId.isNullOrBlank() && persistAuthenticatedSession(auth)) {
                        authPanel = AuthPanel.CLOSED
                        // Identity verification in this branch is based on its backend profile,
                        // not the local badge stored by an older MainActivity.
                        verificationStep = VerificationStep.NONE
                        loadCurrentProfile()
                        loadRemoteItems()
                        toast("카카오 로그인 완료")
                    } else {
                        toast("카카오 로그인 실패: ${auth.message}")
                    }
                    render()
                }.onFailure { callbackError ->
                    toast(callbackError.message ?: "카카오 로그인에 실패했습니다.")
                }
            }
        }.start()
        return true
    }

    private fun startGoogleSignIn() {
        if (!supabase.isConfigured) {
            toast("Supabase 연결 정보를 먼저 설정해주세요.")
            return
        }
        val verifierBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
            .edit()
            .putString(OAUTH_CODE_VERIFIER, verifier)
            .apply()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(supabase.googleSignInUrl(OAUTH_REDIRECT_URI, challenge))))
    }

    private fun handleGoogleAuthCallback(callbackIntent: Intent?): Boolean {
        val uri = callbackIntent?.data ?: return false
        if (uri.scheme != "shareguard" || uri.host != "auth" || uri.path != "/callback") return false

        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            toast("Google 로그인 실패: $error")
            return true
        }

        val preferences = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE)
        val code = uri.getQueryParameter("code")
        val verifier = preferences.getString(OAUTH_CODE_VERIFIER, null)
        preferences.edit().remove(OAUTH_CODE_VERIFIER).apply()
        if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
            toast("Google 로그인 정보를 확인할 수 없습니다. 다시 시도해주세요.")
            return true
        }

        toast("Google 로그인 확인 중입니다.")
        Thread {
            val result = runCatching { supabase.exchangeCodeForSession(code, verifier) }
            runOnUiThread {
                result.onSuccess { auth ->
                    if (auth.isSignedIn && persistAuthenticatedSession(auth)) {
                        authPanel = AuthPanel.CLOSED
                        verificationStep = VerificationStep.NONE
                        loadCurrentProfile()
                        loadRemoteItems()
                    }
                    toast(auth.message)
                    render()
                }.onFailure { callbackError ->
                    toast(callbackError.message ?: "Google 로그인에 실패했습니다.")
                }
            }
        }.start()
        return true
    }

    private fun signOut() {
        clearAccountViews()
        NotificationDelivery.cancel(this)
        currentUser().name = "사용자"
        profileSyncVersion++
        val token = sessionToken
        sessionToken = null
        sessionUserId = null
        authPanel = AuthPanel.CLOSED
        verificationStep = VerificationStep.NONE
        resetServerProfile()
        reservations.clear()
        contracts.clear()
        reports.clear()
        paymentStates.clear()
        qrPayloads.clear()
        refundRequests.clear()
        notifications.clear()
        chatMessages.clear()
        activeContractId = null
        activeChatRoomId = null
        clearStoredSession(getSharedPreferences(SESSION_PREFS, MODE_PRIVATE))
        render()
        if (token != null) {
            Thread { runCatching { supabase.signOut(token) } }.start()
        }
    }

    private fun requestIdentityVerification(message: String) {
        if (supabase.isConfigured && !profileLoaded && sessionToken != null) {
            loadCurrentProfile()
            toast("사용자 인증 상태를 확인한 뒤 다시 시도해주세요.")
            return
        }
        toast(message)
        verificationStep = VerificationStep.CONSENT
        render()
    }

    private fun startPassVerification() {
        val token = sessionToken
        if (token.isNullOrBlank()) {
            toast("로그인 후 본인인증을 진행해주세요.")
            authPanel = AuthPanel.LOGIN
            verificationStep = VerificationStep.NONE
            render()
            return
        }
        if (BuildConfig.PORTONE_STORE_ID.isBlank() ||
            BuildConfig.PORTONE_IDENTITY_CHANNEL_KEY.isBlank() ||
            BuildConfig.SHAREGUARD_API_BASE_URL.isBlank()
        ) {
            toast("포트원 및 백엔드 연결 설정을 확인해주세요.")
            return
        }
        startActivityForResult(
            MfaVerificationActivity.intent(this, token),
            MFA_VERIFICATION_REQUEST
        )
    }

    private fun completeIdentityVerification() {
        if (sessionUserId == null) return
        verificationStep = VerificationStep.NONE
        verificationCodeSent = false
        resetServerProfile()
        loadCurrentProfile()
        screen = Screen.HOME
        toast("본인 인증 결과와 신뢰 점수를 확인하고 있습니다.")
        render()
    }

    private fun handleMfaForbidden(error: Throwable, fallbackMessage: String) {
        val details = error.message.orEmpty()
        val requiresMfa = listOf(
            "MFA_REQUIRED",
            "PASS 휴대폰"
        ).any { marker -> details.contains(marker, ignoreCase = true) }
        if (requiresMfa) {
            profileSyncVersion++
            currentUser().verified = false
            requestIdentityVerification("물품 등록과 결제 전에 PASS 본인인증이 필요합니다.")
        } else {
            toast("$fallbackMessage: ${details.ifBlank { "잠시 후 다시 시도해주세요." }}")
        }
    }

    private fun renderServerProfile() {
        if (sessionToken == null) {
            content.addView(empty("로그인하면 내 프로필과 거래 이력을 확인할 수 있습니다."))
            return
        }
        val user = currentUser()
        content.addView(card().apply {
            addView(textView(user.name, 24, true, text))
            if (sessionUserId != null) addView(button("닉네임 수정", secondary = true) { editNickname() }.withTop(8))
            if (profileLoaded) {
                addView(statusChip(if (user.verified) "본인 인증 완료" else "본인 인증 필요").withTop(8))
                addView(textView("${user.trust} / 1000", 28, true, blue).withTop(14))
                addView(metric("완료한 거래", "${user.deals}건"))
                addView(metric("평균 평점", if (user.rating > 0) user.rating.toString() else "등록된 평점 없음"))
                addView(metric("보증금 혜택", "신뢰점수 자동 ${depositDiscountPercent(user.trust)}% 감면 (최대 10%)"))
            } else {
                addView(textView(profileError ?: "프로필을 불러오는 중입니다.", 14, false, muted).withTop(8))
            }
            addView(button(if (profileLoading) "불러오는 중" else "프로필 새로고침", secondary = true) {
                loadCurrentProfile()
                loadRemoteReservations()
            }.withTop(12))
            if (profileLoaded && !user.verified) addView(button("본인 인증 시작") { startPassVerification() }.withTop(8))
            if (BuildConfig.DEBUG && profileLoaded && user.role == "관리자") addView(button("관리자 화면", secondary = true) {
                screen = Screen.ADMIN
                loadAdminReports()
                render()
            }.withTop(8))
        })
        addMyPostsToProfile()
        content.addView(sectionTitle("내 거래 이력", "참여한 예약과 거래 상태를 확인합니다.").withTop(16))
        val mine = reservations.filter { reservation ->
            reservation.remoteId != null && (reservation.remoteBorrowerId == sessionUserId ||
                    items.find { it.id == reservation.itemId }?.remoteOwnerId == sessionUserId)
        }
        if (mine.isEmpty()) content.addView(empty("표시할 거래가 없습니다. 예약 화면에서 새로고침할 수 있습니다."))
        mine.take(10).forEach { reservation ->
            content.addView(card().apply {
                addView(textView(itemById(reservation.itemId).title, 15, true, text))
                addView(textView("${reservation.startDate} ~ ${reservation.endDate}", 12, false, muted))
                addView(statusChip(contracts.find { it.reservationId == reservation.id }?.status?.label ?: reservation.status))
            }.withTop(8))
        }
        content.addView(sectionTitle("받은 후기", "최근 등록된 후기입니다.").withTop(16))
        if (profileReviews.isEmpty()) content.addView(empty(profileReviewsError ?: "등록된 후기가 없습니다."))
        profileReviews.forEach { review -> content.addView(card().apply {
            addView(textView("★ ${review.rating} / 5", 16, true, warn))
            addView(textView(review.content, 14, false, text).withTop(4))
            addView(textView(review.createdAt.take(10), 12, false, muted).withTop(4))
        }.withTop(8)) }
    }

    private fun loadRemoteItems() {
        if (!supabase.isConfigured || itemsLoading) return
        itemsLoading = true
        loadCurrentProfile()
        val token = sessionToken
        val account = sessionUserId
        Thread {
            val result = runCatching {
                val remote = supabase.fetchItems(token)
                val profiles = if (token != null) supabase.fetchProfiles(remote.map { it.ownerId }, token) else emptyList()
                remote to profiles
            }
            runOnUiThread {
                try {
                    if (account != sessionUserId) return@runOnUiThread
                    result.onSuccess { (remote, profiles) ->
                        profiles.forEach { ownerProfiles[it.id] = it }
                        val referenced = reservations.map { it.itemId }.toSet()
                        items.removeAll { it.id !in referenced }
                        for (index in items.indices) items[index] = items[index].copy(available = false)
                        remote.forEach { upsertItem(remoteToItem(it)) }
                        if (sessionToken != null) loadRemoteReservations()
                        if (screen in setOf(Screen.HOME, Screen.MAP, Screen.DETAIL) || (screen == Screen.REGISTER && editingItemId != null)) render()
                    }.onFailure { toast("물품 조회 실패: ${it.message}") }
                } finally {
                    itemsLoading = false
                    finishPullRefresh("items", result.isSuccess)
                }
            }
        }.start()
    }

    private fun openPhotoPicker(contract: Contract, phase: String, camera: Boolean = true) {
        if (conditionPhotoUploading || conditionPhotoAwaitingResult || conditionCameraPermissionPending) {
            toast("사진 처리가 끝난 뒤 다시 눌러주세요.")
            return
        }
        if (phase == "before" && contract.status !in listOf(ContractStatus.SIGNED, ContractStatus.READY)) {
            toast("양측 전자 서명 후 대여 전 사진을 등록할 수 있습니다.")
            return
        }
        if (phase == "after" && contract.status != ContractStatus.RENTING) {
            toast("대여중 상태에서 반납 후 사진을 등록할 수 있습니다.")
            return
        }
        if ((if (phase == "before") contract.beforePhotos else contract.afterPhotos) >= 5) {
            toast("단계별 상태 사진은 최대 5장까지 등록할 수 있습니다.")
            return
        }
        if (contract.remoteId == null || sessionUserId == null || sessionToken == null) {
            toast("로그인된 서버 거래에서 상태 사진을 등록할 수 있습니다.")
            return
        }
        clearPendingConditionPhoto()
        pendingPhotoContractId = contract.remoteId
        pendingPhotoPhase = phase
        pendingPhotoUserId = sessionUserId
        if (camera) {
            // CAMERA is also declared by the QR scanner dependency.
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                conditionCameraPermissionPending = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), conditionCameraPermissionRequest)
            } else launchConditionCamera()
        } else {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            runCatching {
                conditionPhotoAwaitingResult = true
                startActivityForResult(intent, conditionGalleryRequest)
            }.onFailure {
                clearPendingConditionPhoto()
                toast("사진 선택 앱을 열 수 없습니다.")
            }
        }
    }

    private fun launchConditionCamera() {
        if (pendingPhotoUserId != sessionUserId || pendingPhotoContractId == null) {
            clearPendingConditionPhoto()
            return
        }
        runCatching {
            val directory = File(cacheDir, "condition_photos").apply { mkdirs() }
            val photo = File.createTempFile("condition_", ".jpg", directory)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", photo)
            pendingPhotoUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("물품 상태 사진", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            conditionPhotoAwaitingResult = true
            startActivityForResult(intent, if (pendingPhotoPhase == "before") beforePhotoRequest else afterPhotoRequest)
        }.onFailure {
            clearPendingConditionPhoto()
            toast("카메라를 열 수 없습니다. 권한을 확인하거나 갤러리에서 선택해주세요.")
        }
    }

    private fun clearPendingConditionPhoto() {
        pendingPhotoUri?.takeIf { it.authority == "$packageName.files" }?.let { uri ->
            runCatching { revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            runCatching { contentResolver.delete(uri, null, null) }
        }
        pendingPhotoUri = null
        pendingPhotoContractId = null
        pendingPhotoPhase = null
        pendingPhotoUserId = null
        conditionPhotoAwaitingResult = false
        conditionCameraPermissionPending = false
    }

    private fun uploadPendingConditionPhoto() {
        if (conditionPhotoUploading) return
        val uri = pendingPhotoUri ?: return
        val account = pendingPhotoUserId ?: return
        val contractId = pendingPhotoContractId ?: return
        val phase = pendingPhotoPhase ?: return
        if (sessionUserId != account || sessionToken == null) {
            clearPendingConditionPhoto()
            toast("촬영한 계정으로 다시 로그인해주세요.")
            return
        }
        conditionPhotoUploading = true
        toast("상태 사진을 업로드 중입니다.")
        render()
        Thread {
            val result = runCatching {
                val auth = SessionTokens.refresh(this, supabase)
                check(auth.userId == account) { "로그인 계정이 변경되었습니다." }
                val bytes = ConditionPhotoEncoder.encode(contentResolver, uri)
                val path = supabase.uploadConditionPhoto(contractId, phase, account, bytes, "image/jpeg", auth.accessToken!!)
                auth.accessToken to path
            }
            runOnUiThread {
                conditionPhotoUploading = false
                if (sessionUserId != account) return@runOnUiThread
                result.onSuccess { (token, path) ->
                    sessionToken = token
                    contracts.find { it.remoteId == contractId }?.let { contract ->
                        activeContractId = contract.id
                        if (phase == "before") {
                            contract.beforePhotoPaths = contract.beforePhotoPaths + path
                            contract.beforePhotos++
                            if (contract.status == ContractStatus.SIGNED) contract.status = ContractStatus.READY
                        } else {
                            contract.afterPhotoPaths = contract.afterPhotoPaths + path
                            contract.afterPhotos++
                        }
                        Unit
                    }
                    clearPendingConditionPhoto()
                    loadRemoteReservations()
                    toast("상태 사진이 서버에 저장되었습니다.")
                }.onFailure { error ->
                    toast("사진 업로드 실패: ${error.message ?: "연결을 확인하세요."} · 다시 시도할 수 있습니다.")
                }
                render()
            }
        }.start()
    }

    private fun issueServerQr(contract: Contract, action: String) {
        val token = sessionToken
        val contractId = contract.remoteId
        if (token == null || contractId == null || !server.isConfigured) {
            toast("서버에 연결된 거래에서 QR을 생성할 수 있습니다.")
            return
        }
        if (qrRequestInProgress) return
        qrRequestInProgress = true
        Thread {
            val result = runCatching { server.issueQr(contractId, action, token) }
            runOnUiThread {
                qrRequestInProgress = false
                if (token != sessionToken) return@runOnUiThread
                result.onSuccess { qrPayloads["$contractId:$action"] = it }
                    .onFailure { toast(it.message ?: "QR 발급에 실패했습니다.") }
                render()
            }
        }.start()
    }

    private fun itemPhotoCount(): Int = registrationPhotoUris.size + (items.find { it.id == editingItemId }?.imagePaths?.size ?: 0)

    private fun openItemCamera() {
        if (registrationSaving || pendingItemCameraUri != null || itemCameraPermissionPending) return
        if (itemPhotoCount() >= 5) {
            toast("사진은 최대 5장까지 등록할 수 있습니다.")
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            itemCameraPermissionPending = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), itemCameraPermissionRequest)
            return
        }
        runCatching {
            val directory = File(cacheDir, "item_photos").apply { mkdirs() }
            val photo = File.createTempFile("item_", ".jpg", directory)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", photo)
            pendingItemCameraUri = uri
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("물품 사진", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }, itemCameraRequest)
        }.onFailure {
            pendingItemCameraUri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
            pendingItemCameraUri = null
            toast("카메라를 열 수 없습니다. 사진 선택으로 추가해주세요.")
        }
    }

    private fun openItemPhotoPicker() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, itemPhotoRequest)
    }

    private fun compressedItemPhoto(uri: Uri): Pair<ByteArray, String> =
        ConditionPhotoEncoder.encode(contentResolver, uri) to "image/jpeg"

    private fun startQrScan(contract: Contract, action: String) {
        if (contract.remoteId == null || sessionToken == null || !server.isConfigured) {
            toast("로그인된 서버 거래에서 QR을 인증할 수 있습니다.")
            return
        }
        val hasPhotos = if (action == "start") contract.beforePhotos > 0 else contract.afterPhotos > 0
        val validState = if (action == "start") {
            contract.status in setOf(ContractStatus.SIGNED, ContractStatus.READY)
        } else contract.status == ContractStatus.RENTING
        if (!hasPhotos || !validState) {
            toast(if (action == "start") "서명과 대여 전 사진을 먼저 완료하세요." else "반납 후 사진을 먼저 등록하세요.")
            return
        }
        pendingQrContractId = contract.id
        pendingQrAction = action
        IntentIntegrator(this).apply {
            setPrompt(if (action == "start") "상대방의 대여 시작 QR을 스캔하세요" else "상대방의 반납 QR을 스캔하세요")
            setBeepEnabled(true)
            setOrientationLocked(false)
            setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        }.initiateScan()
    }

    private fun handleScannedQr(contract: Contract, action: String, payload: String) {
        val token = sessionToken ?: return
        val contractId = contract.remoteId ?: return
        if (qrRequestInProgress) return
        qrRequestInProgress = true
        toast(if (action == "start") "대여 시작을 인증 중입니다." else "반납 완료를 인증 중입니다.")
        Thread {
            val result = runCatching { server.verifyQr(payload, contractId, action, token, currentLatitude, currentLongitude) }
            runOnUiThread {
                qrRequestInProgress = false
                if (sessionToken != token) return@runOnUiThread
                result.onSuccess {
                    qrPayloads.remove("$contractId:$action")
                    // The server already confirmed the one-time QR; keep the result visible until the read catches up.
                    contract.status = if (action == "end") ContractStatus.COMPLETED else ContractStatus.RENTING
                    addNotification(
                        if (action == "end") "🔒" else "📦",
                        if (action == "end") "반납 확인 완료" else "대여가 시작되었어요",
                        if (action == "end") "반납이 확인되었습니다. 결제 내역에서 보증금 환급 상태를 확인해주세요." else "QR 인증이 완료되어 거래 상태가 대여중으로 변경되었습니다.",
                        Screen.RESERVATIONS
                    )
                    screen = Screen.RENTAL_FLOW
                    rentalStep = if (action == "end") RentalStep.RETURNED else RentalStep.STARTED
                    loadRemoteReservations()
                    loadCurrentProfile()
                    toast(if (action == "end") "반납이 확인되었습니다. 보증금 환급은 별도로 진행해주세요." else "대여중 상태로 변경되었습니다.")
                    render()
                }.onFailure { error ->
                    toast("QR 인증 확인 필요: ${error.message ?: "잠시 후 거래 상태를 새로고침하세요."}")
                    // A response can be lost after server commit; read before another action.
                    loadRemoteReservations()
                }
            }
        }.start()
    }

    private fun loadRemoteReservations(refreshPayments: Boolean = true) {
        val token = sessionToken ?: return
        val account = sessionUserId
        if (reservationLoading) return
        reservationLoading = true
        Thread {
            val result = runCatching {
                val remoteReservations = supabase.fetchReservations(token)
                val remoteItems = supabase.fetchItemsByIds(remoteReservations.map { it.itemId }, token)
                val profiles = supabase.fetchProfiles(remoteItems.map { it.ownerId } + remoteReservations.map { it.borrowerId }, token)
                ReservationSnapshot(remoteReservations, supabase.fetchContracts(token), remoteItems, profiles,
                    supabase.fetchChatRooms(token), supabase.fetchArchivedReservations(token))
            }
            runOnUiThread {
                try {
                    reservationLoading = false
                    if (account != sessionUserId) return@runOnUiThread
                    result.onSuccess { snapshot ->
                        val changed = remoteChatRooms != snapshot.rooms
                        val previousChatReservation = reservations.find { it.id == selectedChatReservationId }
                        snapshot.profiles.forEach { ownerProfiles[it.id] = it }
                        snapshot.items.forEach { upsertItem(remoteToItem(it)) }
                        reservations.clear(); contracts.clear()
                        snapshot.reservations.mapNotNull(::remoteToReservation).forEach(reservations::add)
                        snapshot.contracts.mapNotNull(::remoteToContract).forEach(contracts::add)
                        remoteChatRooms = snapshot.rooms
                        archivedReservations = snapshot.archived
                        if (screen == Screen.RENTAL_FLOW) {
                            val active = activeContract()
                            if (active != null) {
                                if (rentalStep == RentalStep.HANDOVER_QR && active.status == ContractStatus.RENTING)
                                    rentalStep = RentalStep.STARTED
                                else if (rentalStep == RentalStep.RETURN_QR && active.status == ContractStatus.COMPLETED)
                                    rentalStep = RentalStep.RETURNED
                            }
                        }
                        if ("reservations" in pendingRefreshLoads && server.isConfigured && refreshPayments) {
                            pendingRefreshLoads.add("payments")
                        }
                        if (refreshPayments) loadPaymentStates()
                        val chatReservationChanged = previousChatReservation != reservations.find { it.id == selectedChatReservationId }
                        if (pendingNotificationReservation != null) {
                            val reservation = reservations.find { it.remoteId == pendingNotificationReservation }
                            if (reservation != null) {
                                val kind = pendingNotificationKind
                                pendingNotificationReservation = null
                                if (kind == "chat" || kind == "schedule") openReservationChat(reservation)
                                else {
                                    reservationTab = if (itemById(reservation.itemId).remoteOwnerId == sessionUserId) "빌려준 물품" else "빌린 물품"
                                    val contract = contracts.find { it.reservationId == reservation.id }
                                    if (screen == Screen.RENTAL_FLOW && contract != null) openRentalFlow(contract)
                                    else { screen = Screen.RESERVATIONS; render() }
                                }
                            }
                        } else if (screen in setOf(Screen.RESERVATIONS, Screen.TRANSACTION, Screen.RENTAL_FLOW, Screen.PROFILE) || screen == Screen.CHAT && (!chatDetailOpen && changed || chatDetailOpen && chatReservationChanged)) render()
                        if (screen == Screen.CHAT && chatDetailOpen) loadRemoteChat()
                    }.onFailure { toast("예약·채팅 조회 실패: ${it.message}") }
                } finally {
                    finishPullRefresh("reservations", result.isSuccess)
                }
            }
        }.start()
    }

    private data class ReservationSnapshot(
        val reservations: List<RemoteReservation>, val contracts: List<RemoteContract>, val items: List<RemoteItem>,
        val profiles: List<RemoteProfile>, val rooms: List<RemoteChatRoom>, val archived: Set<String>
    )

    private fun loadRemoteChat() {
        if (chatLoading) return
        if (screen != Screen.CHAT || !chatDetailOpen) { finishPullRefresh("chat"); return }
        val token = sessionToken ?: run { finishPullRefresh("chat"); return }
        val account = sessionUserId ?: run { finishPullRefresh("chat"); return }
        val selected = selectedChatReservationId
        val reservation = reservations.find { it.id == selected } ?: run { finishPullRefresh("chat"); return }
        val reservationId = reservation.remoteId ?: run { finishPullRefresh("chat"); return }
        chatLoading = true
        Thread {
            val result = runCatching {
                val room = supabase.fetchChatRoom(reservationId, token)
                val messages = if (room == null) emptyList() else supabase.fetchChatMessages(room, token)
                Triple(room, messages, supabase.fetchDateRequests(reservationId, token))
            }
            runOnUiThread {
                try {
                    chatLoading = false
                    if (sessionUserId != account || selectedChatReservationId != selected || screen != Screen.CHAT || !chatDetailOpen) return@runOnUiThread
                    result.onSuccess { (room, messages, requests) ->
                        activeChatRoomId = room
                        val mapped = messages.map { message ->
                            val time = runCatching { java.time.Instant.parse(message.createdAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")) }.getOrDefault("")
                            ChatMessage(message.senderId == account, message.body, time, false)
                        }
                        if (chatMessages != mapped) {
                            chatMessages.clear(); chatMessages.addAll(mapped)
                            updateChatMessages()
                        }
                        dateRequests = requests
                        renderDateRequests(reservation)
                        if (room != null) Thread { runCatching { supabase.markChatRead(room, account, token) } }.start()
                    }.onFailure { toast("채팅 조회 실패: ${it.message}") }
                } finally {
                    finishPullRefresh("chat", result.isSuccess)
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
            remoteBorrowerId = remote.borrowerId,
            depositAmount = remote.deposit
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
            beforePhotoPaths = remote.beforePhotoPaths,
            afterPhotoPaths = remote.afterPhotoPaths,
            afterPhotos = remote.afterPhotos,
            refunded = paymentStates[remote.reservationId]?.refunded == true,
            refundHeld = remote.status == "disputed",
            ownerSigned = remote.ownerSigned,
            borrowerSigned = remote.borrowerSigned,
            ownerSignedAt = remote.ownerSignedAt,
            borrowerSignedAt = remote.borrowerSignedAt,
            specialTerms = remote.specialTerms,
            snapshot = remote.snapshot
        )
    }

    private fun saveItem(item: Item) {
        if (registrationSaving) return
        if (registrationPhotoUris.isEmpty()) {
            toast("물품 사진을 1장 이상 선택하세요.")
            return
        }
        if (!supabase.isConfigured) {
            items.add(0, item)
            registerTab = "내 물품"
            registrationPhotoUris.clear()
            clearRegistrationCoordinates()
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

        val photoUris = registrationPhotoUris.toList()
        val userId = sessionUserId
        if (userId == null) {
            toast("로그인 사용자 정보를 확인할 수 없습니다.")
            return
        }
        toast("물품 정보와 사진을 저장 중입니다.")
        registrationSaving = true
        Thread {
            val result = runCatching {
                val remote = supabase.insertItem(
                    NewRemoteItem(
                        title = item.title,
                        category = item.category,
                        description = item.description,
                        dailyPrice = item.price,
                        deposit = item.deposit,
                        address = item.location,
                        latitude = item.latitude,
                        longitude = item.longitude,
                        components = item.components,
                        knownDefects = item.knownDefects,
                        itemValue = item.itemValue
                    ),
                    token
                )
                photoUris.forEachIndexed { index, uri ->
                    val (bytes, mimeType) = compressedItemPhoto(uri)
                    supabase.uploadItemImage(remote.id, userId, index, bytes, mimeType, token)
                }
                supabase.publishItem(remote.id, token)
                supabase.fetchItemsByIds(listOf(remote.id), token).single()
            }
            runOnUiThread {
                registrationSaving = false
                if (sessionUserId != userId) return@runOnUiThread
                result.onSuccess { remote ->
                    items.add(0, remoteToItem(remote))
                    selectedItemId = items.first().id
                    registerTab = "내 물품"
                    registrationPhotoUris.clear()
                    registrationDraft.clear()
                    clearRegistrationCoordinates()
                    toast("물품과 사진 ${photoUris.size}장이 저장되었습니다.")
                    render()
                }.onFailure { error ->
                    handleMfaForbidden(error, "등록 실패")
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
        ownerId = if (item.ownerId == sessionUserId) currentUser().id else 1,
        tone = when (item.category) {
            "카메라" -> Color.rgb(219, 234, 254)
            "게임기" -> Color.rgb(237, 233, 254)
            "캠핑" -> Color.rgb(220, 252, 231)
            else -> Color.rgb(254, 243, 199)
        },
        remoteId = item.id,
        remoteOwnerId = item.ownerId,
        latitude = item.latitude,
        longitude = item.longitude,
        components = item.components,
        knownDefects = item.knownDefects,
        itemValue = item.itemValue,
        imagePaths = item.imagePaths,
        status = item.status,
        available = item.status == "active"
    )

    private fun createReservation(item: Item) {
        if (reservationSaving) return
        if (item.remoteOwnerId != null && item.remoteOwnerId == sessionUserId) { toast("본인 물품은 예약할 수 없습니다."); return }
        if (!currentUser().verified) {
            requestIdentityVerification("본인 인증 후 예약할 수 있습니다.")
            return
        }
        if (reservationEndDate.isBefore(reservationStartDate)) {
            toast("예약 종료일을 확인하세요.")
            return
        }
        if (reservationConflict(item.id, reservationStartDate, reservationEndDate)) {
            toast("선택한 기간에는 이미 진행 중인 예약이 있습니다.")
            return
        }
        if (reservationStartDate.isBefore(LocalDate.now())) { toast("과거 날짜는 예약할 수 없습니다."); return }
        val rentalFee = runCatching { ReservationRules.rentalFee(item.price, reservationStartDate, reservationEndDate) }
            .getOrElse { toast("날짜와 금액을 확인하세요."); return }
        val appliedDeposit = adjustedDeposit(item.deposit, calculateTrustScore(currentUser()))
        val total = runCatching { Math.addExact(rentalFee, appliedDeposit) }.getOrElse { toast("대여 기간이 너무 깁니다."); return }
        val startDate = reservationStartDate.toString()
        val endDate = reservationEndDate.toString()
        val token = sessionToken
        if (supabase.isConfigured && token != null && item.remoteId != null) {
            val remoteItemId = item.remoteId
            val account = sessionUserId
            reservationSaving = true
            toast("예약을 서버에 저장 중입니다.")
            Thread {
                val result = runCatching {
                    supabase.createReservation(remoteItemId, startDate, endDate, token)
                }
                runOnUiThread {
                    reservationSaving = false
                    if (account != sessionUserId) return@runOnUiThread
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
        reservations.add(Reservation(
            nextReservationId++, item.id, currentUser().id, startDate, endDate, total,
            depositAmount = appliedDeposit
        ))
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
        if (registrationSaving) return
        if (item.title.length < 2 || item.location.length < 2 || item.price < 0 || item.deposit < 0) {
            toast("물품명, 가격, 보증금, 위치를 확인하세요.")
            return
        }
        val token = sessionToken
        val remoteId = item.remoteId
        if (supabase.isConfigured && token != null && remoteId != null) {
            val account = sessionUserId ?: return
            val photos = registrationPhotoUris.toList()
            if (photos.size + item.imagePaths.size > 5) { toast("기존 사진을 포함해 최대 5장까지 등록할 수 있습니다."); return }
            registrationSaving = true
            Thread {
                val result = runCatching {
                    photos.forEachIndexed { index, uri ->
                        val (bytes, mime) = compressedItemPhoto(uri)
                        supabase.uploadItemImage(remoteId, account, item.imagePaths.size + index, bytes, mime, token)
                    }
                    supabase.updateItem(
                        remoteId,
                        NewRemoteItem(item.title, item.category, item.description, item.price, item.deposit, item.location, item.latitude, item.longitude,
                            item.components, item.knownDefects, item.itemValue),
                        token
                    )
                    supabase.fetchItemsByIds(listOf(remoteId), token).single()
                }
                runOnUiThread {
                    registrationSaving = false
                    if (account != sessionUserId) return@runOnUiThread
                    result.onSuccess { remote ->
                        val index = items.indexOfFirst { it.id == item.id }
                        if (index >= 0) items[index] = remoteToItem(remote)
                        editingItemId = null
                        registrationPhotoUris.clear(); registrationDraft.clear()
                        registerTab = "내 물품"
                        clearRegistrationCoordinates()
                        toast("물품 정보가 수정되었습니다.")
                        render()
                    }.onFailure { error -> toast("수정 실패: ${error.message ?: "권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        editingItemId = null
        registerTab = "내 물품"
        clearRegistrationCoordinates()
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

        if (sessionToken == null || reservation.remoteId == null || reservation.remoteBorrowerId != sessionUserId) {
            toast("로그인한 차용자의 서버 예약에서 결제할 수 있습니다.")
            return
        }
        if (!server.isConfigured || !TossConfirmClient(BuildConfig.TOSS_CONFIRM_URL).isConfigured) {
            toast("결제 서버 연결을 먼저 설정해주세요.")
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
            toast("결제 서버 주소가 없어 승인을 요청하지 못했습니다. 결제 완료 상태로 변경하지 않았습니다.")
            return
        }
        val token = sessionToken
        val remoteReservationId = reservations.find { it.id == contract.reservationId }?.remoteId
        if (token.isNullOrBlank() || remoteReservationId.isNullOrBlank()) {
            toast("로그인된 원격 예약만 실제 결제를 승인할 수 있습니다.")
            return
        }

        toast("결제 승인 확인 중입니다.")
        Thread {
            val result = runCatching { confirmClient.confirm(paymentKey, orderId, amount, remoteReservationId, token) }
            runOnUiThread {
                if (token != sessionToken) return@runOnUiThread
                result.onSuccess {
                    addNotification("🔒", "결제 승인 완료", "서버에서 대여료와 보증금 결제 승인을 확인했습니다.", Screen.TRANSACTION)
                    loadRemoteReservations()
                    toast("토스 결제 승인이 완료되었습니다.")
                }.onFailure { error ->
                    handleMfaForbidden(error, "결제 승인 확인 필요")
                    loadRemoteReservations()
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

    private fun loadPaymentStates() {
        val token = sessionToken ?: return
        if (!server.isConfigured) {
            paymentStateError = "결제 서버 연결을 확인해주세요."
            paymentRefreshInProgress = false
            if (screen in setOf(Screen.RESERVATIONS, Screen.TRANSACTION, Screen.RENTAL_FLOW, Screen.PROFILE)) render()
            return
        }
        val ids = reservations.mapNotNull { it.remoteId }.distinct()
        paymentRefreshInProgress = true
        if (screen in setOf(Screen.RESERVATIONS, Screen.TRANSACTION, Screen.RENTAL_FLOW, Screen.PROFILE)) render()
        val readVersion = ++paymentReadVersion
        Thread {
            val results = ids.associateWith { id -> runCatching { server.payment(id, token) } }
            runOnUiThread {
                try {
                    if (sessionToken != token || readVersion != paymentReadVersion) return@runOnUiThread
                    paymentStates.clear()
                    results.forEach { (id, result) -> result.getOrNull()?.let { paymentStates[id] = it } }
                    paymentStateError = if (results.values.any { it.isFailure }) "일부 결제 상태를 확인하지 못했습니다. 새로고침해주세요." else null
                    paymentRefreshInProgress = false
                    paymentLastRefreshedAt = java.time.LocalTime.now().toString().take(8)
                    contracts.forEach { contract ->
                        val id = reservations.find { it.id == contract.reservationId }?.remoteId
                        contract.refunded = paymentStates[id]?.refunded == true
                    }
                    if (screen in setOf(Screen.RESERVATIONS, Screen.TRANSACTION, Screen.RENTAL_FLOW, Screen.PROFILE)) render()
                } finally {
                    finishPullRefresh("payments", results.values.all { it.isSuccess })
                }
            }
        }.start()
    }

    private fun paymentStatusCard(contract: Contract, showRefundButton: Boolean = true): LinearLayout = card().apply {
        val reservation = reservations.find { it.id == contract.reservationId }
        val id = reservation?.remoteId
        val payment = paymentStates[id]
        val statusLabel = when {
            id == null -> "서버 결제 내역 없음"
            payment == null -> "결제 상태 확인 필요"
            id in refundRequests -> "환급 처리 확인 중"
            payment.status == "refunded" -> "보증금 환급 완료"
            payment.status == "partially_refunded" -> "보증금 일부 환급"
            payment.status == "refund_pending" -> "환급 처리 확인 중"
            contract.refundHeld -> "분쟁 확인 중 · 환급 보류"
            payment.status == "paid" && contract.status == ContractStatus.COMPLETED -> "반납 확인 · 보증금 환급 대기"
            payment.status == "paid" -> "결제 승인 완료"
            payment.status == "confirming" -> "결제 승인 확인 중"
            payment.status == "failed" -> "결제 승인 실패"
            else -> "결제 전"
        }
        addView(textView(statusLabel, 16, true, if (payment?.refunded == true) good else text))
        payment?.let {
            addView(metric("누적 환급액", money(it.refundedAmount)).withTop(6))
            if (contract.status == ContractStatus.COMPLETED && (reservation?.depositAmount ?: 0) == 0) {
                addView(textView("환급할 보증금이 없습니다.", 12, false, muted))
            }
        }
        paymentStateError?.let { addView(textView(it, 12, false, warn).withTop(6)) }
        if (id != null) {
            paymentLastRefreshedAt?.let { addView(textView("마지막 확인 $it", 11, false, muted).withTop(5)) }
            addView(button(if (paymentRefreshInProgress) "확인 중…" else "결제·환급 상태 새로고침", secondary = true) {
                if (!paymentRefreshInProgress) {
                    toast("결제·환급 상태를 확인 중입니다.")
                    loadPaymentStates()
                }
            }.withTop(8))
            if (showRefundButton && payment?.refundAllowed == true && id !in refundRequests) {
                addView(button("보증금 ${money(payment.refundableAmount)} 환급 요청") { requestRefund(contract) }.withTop(8))
            }
        }
    }

    private fun requestRefund(contract: Contract) {
        val token = sessionToken ?: return
        val id = reservations.find { it.id == contract.reservationId }?.remoteId ?: return
        if (!refundRequests.add(id)) return
        paymentReadVersion++
        render()
        Thread {
            val result = runCatching {
                val fresh = server.payment(id, token)
                check(fresh.refundAllowed && fresh.refundableAmount > 0) { "현재 환급 가능한 보증금이 없습니다. 상태를 다시 확인해주세요." }
                server.refund(id, fresh.refundableAmount, token)
                // Re-read authoritative totals and eligibility after the money-changing call.
                server.payment(id, token)
            }
            runOnUiThread {
                refundRequests.remove(id)
                if (sessionToken != token) return@runOnUiThread
                paymentReadVersion++
                result.onSuccess { state ->
                    paymentStates[id] = state
                    contract.refunded = state.refunded
                    val label = when (state.status) {
                        "refunded" -> "보증금 환급 완료"
                        "partially_refunded" -> "보증금 일부 환급"
                        else -> "환급 처리 상태 확인 필요"
                    }
                    toast(label)
                    addNotification("🔒", label, "결제 내역에서 확인한 누적 환급액은 ${money(state.refundedAmount)}입니다.", Screen.RESERVATIONS)
                }.onFailure { error ->
                    toast("환급 상태 확인 필요: ${error.message.orEmpty()}")
                    // A timeout does not mean the payment provider failed to cancel.
                    paymentStates.remove(id)
                    loadPaymentStates()
                }
                render()
            }
        }.start()
    }

    private fun sign(contract: Contract, signature: Bitmap) {
        val reservation = reservations.find { it.id == contract.reservationId }
        val item = reservation?.let { itemById(it.itemId) }
        if (reservation == null || item == null) {
            toast("계약 정보를 찾을 수 없습니다.")
            return
        }
        val token = sessionToken
        val remoteId = contract.remoteId
        if (supabase.isConfigured && token != null && remoteId != null) {
            toast("전자 서명을 서버에 저장 중입니다.")
            val role = if (item.remoteOwnerId == sessionUserId) "owner" else "borrower"
            val account = sessionUserId
            Thread {
                val result = runCatching {
                    val bytes = ByteArrayOutputStream().use { stream ->
                        check(signature.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        stream.toByteArray()
                    }
                    runCatching { supabase.uploadContractSignature(remoteId, role, bytes, token) }
                        .getOrElse {
                            // A previous unsigned attempt may have left an object at this fixed path.
                            supabase.deleteUnsignedContractSignature(remoteId, role, token)
                            supabase.uploadContractSignature(remoteId, role, bytes, token)
                        }
                    val signed = try { supabase.signContract(remoteId, token) }
                    catch (error: Exception) {
                        runCatching { supabase.deleteUnsignedContractSignature(remoteId, role, token) }
                        throw error
                    }
                    val pdf = runCatching {
                        val ownerImage = loadContractSignerBitmap(remoteId, "owner", signed.ownerSignedAt, token)
                        val borrowerImage = loadContractSignerBitmap(remoteId, "borrower", signed.borrowerSignedAt, token)
                        val payment = supabase.fetchContractPaymentDetails(remoteId, token)
                        ContractPdfExporter.create(this, contractDocument(signed, ownerImage, borrowerImage, payment))
                    }
                    signed to pdf
                }
                runOnUiThread {
                    if (account != sessionUserId) return@runOnUiThread
                    result.onSuccess { (signed, pdf) ->
                        pdf.onSuccess { contractPdfFiles[contract.id] = it }
                            .onFailure { toast("서명은 저장됐지만 PDF 생성은 실패했습니다. 계약서에서 다시 시도하세요.") }
                        loadRemoteReservations()
                        val complete = signed.ownerSigned && signed.borrowerSigned
                        addNotification("✍", if (complete) "전자 계약 완료" else "전자 서명 완료", if (complete) "상태 사진을 등록하고 거래를 시작해주세요." else "상대방의 서명을 기다리고 있습니다.", if (complete) Screen.RENTAL_FLOW else Screen.TRANSACTION, contract.id)
                        toast(if (complete) "양측 서명이 완료되었습니다." else "내 서명이 저장되었습니다. 상대방 서명을 기다립니다.")
                    }.onFailure { error -> toast("서명 실패: ${error.message ?: "결제와 권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        val demoSnapshot = JSONObject()
            .put("item_title", item.title).put("item_category", item.category)
            .put("components", item.components).put("known_defects", item.knownDefects)
            .put("item_value", item.itemValue).put("daily_price", item.price)
            .put("location", item.location).put("start_date", reservation.startDate)
            .put("end_date", reservation.endDate)
            .put("rental_fee", reservation.totalAmount - reservation.depositAmount)
            .put("deposit", reservation.depositAmount).put("original_deposit", item.deposit)
            .put("terms_version", ContractTerms.TERMS_VERSION)
        contractPdfFiles[contract.id] = ContractPdfExporter.create(this, ContractDocument(
            contract.id.toString(), demoSnapshot,
            ContractSigner(currentUser().name, currentUser().verified, currentUser().trust, Instant.now().toString(), signature),
            ContractSigner("상대방", false, 0, null, null), null
        ))
        contract.status = ContractStatus.SIGNED
        addNotification("✍", "전자 계약 완료", "대여 전 상태 사진을 등록하고 QR 인증을 진행해주세요.", Screen.RENTAL_FLOW, contract.id)
        openRentalFlow(contract)
        toast("전자 계약서 서명이 완료되었습니다.")
    }

    private fun saveContractSpecialTerms(contract: Contract, terms: String) {
        if (terms.length > 500) { toast("특약은 500자 이하여야 합니다."); return }
        val id = contract.remoteId ?: return
        val token = sessionToken ?: return
        val account = sessionUserId
        Thread {
            val result = runCatching { supabase.setContractSpecialTerms(id, terms, token) }
            runOnUiThread {
                if (account != sessionUserId) return@runOnUiThread
                result.onSuccess { contractSpecialDrafts.remove(contract.id); loadRemoteReservations(); toast("특약이 저장되었습니다.") }
                    .onFailure { toast("특약 저장 실패: ${it.message ?: "권한을 확인하세요."}") }
            }
        }.start()
    }

    private fun contractDocument(remote: RemoteContract, ownerImage: Bitmap?, borrowerImage: Bitmap?, payment: ContractPaymentDetails): ContractDocument {
        val snapshot = remote.snapshot
        return ContractDocument(
            remote.id, snapshot,
            ContractSigner(snapshot.optString("owner_nickname", "대여자"), snapshot.optBoolean("owner_verified"),
                snapshot.optInt("owner_trust_score"), remote.ownerSignedAt, ownerImage),
            ContractSigner(snapshot.optString("borrower_nickname", "임차인"), snapshot.optBoolean("borrower_verified"),
                snapshot.optInt("borrower_trust_score"), remote.borrowerSignedAt, borrowerImage),
            remote.specialTerms,
            currentBeforePhotoCount = remote.beforePhotos,
            paymentOrderId = payment.orderId,
            paidAt = payment.paidAt
        )
    }

    private fun loadContractSignerBitmap(id: String, role: String, signedAt: String?, token: String): Bitmap? {
        if (signedAt == null) return null
        val bytes = supabase.downloadContractSignature(id, role, token)
            ?: error("서명 완료된 $role 이미지가 저장소에 없습니다.")
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("서명 완료된 $role 이미지를 읽을 수 없습니다.")
    }

    private fun generateContractPdf(contract: Contract, share: Boolean) {
        val action: (File) -> Unit = if (share) ::shareContractPdf else ::openContractPdf
        val id = contract.remoteId ?: return contractPdfFiles[contract.id]?.let(action) ?: Unit
        val token = sessionToken ?: return
        val account = sessionUserId
        toast("서버의 최신 계약서와 양측 서명을 불러오는 중입니다.")
        Thread {
            val result = runCatching {
                val latest = supabase.fetchContracts(token).first { it.id == id }
                check(latest.snapshot.optString("terms_version") == ContractTerms.TERMS_VERSION) {
                    "기존 서명 계약에는 새 조항을 적용한 PDF를 만들 수 없습니다."
                }
                val ownerImage = loadContractSignerBitmap(id, "owner", latest.ownerSignedAt, token)
                val borrowerImage = loadContractSignerBitmap(id, "borrower", latest.borrowerSignedAt, token)
                val payment = supabase.fetchContractPaymentDetails(id, token)
                ContractPdfExporter.create(this, contractDocument(latest, ownerImage, borrowerImage, payment))
            }
            runOnUiThread {
                if (account != sessionUserId) return@runOnUiThread
                result.onSuccess { file -> contractPdfFiles[contract.id] = file; action(file) }
                    .onFailure { toast("계약서 열람 실패: ${it.message ?: "네트워크를 확인하세요."}") }
            }
        }.start()
    }

    private fun shareContractPdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "전자 계약서 공유"))
    }

    private fun openContractPdf(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure { toast("PDF 뷰어가 없습니다. 공유 버튼으로 열어주세요.") }
    }

    private fun addNotification(icon: String, title: String, body: String, target: Screen, contractId: Int? = null) {
        notifications.add(0, AppNotification(nextNotificationId++, icon, title, body, "방금", target, contractId = contractId))
    }

    private fun loadAdminReports() {
        val token = sessionToken ?: return
        if (!supabase.isConfigured) return
        Thread {
            val result = runCatching { supabase.fetchReports(token) }
            runOnUiThread {
                if (token != sessionToken) return@runOnUiThread
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

    private fun resolveAdminReport(report: Report, decision: String, deductionAmount: Int, note: String, sanction: String) {
        if (note.trim().length < 5 || deductionAmount < 0) {
            toast("차감액과 5자 이상의 처리 근거를 입력하세요.")
            return
        }
        val resolution = when (decision) {
            "dismissed" -> "신고 기각 · 환급은 결제 내역에서 별도 요청 · ${note.trim()}"
            "partial" -> "${money(deductionAmount)} 차감 결정 · 환급은 별도 요청 · ${note.trim()}"
            else -> "신고 인용 및 제재 · ${note.trim()}"
        }
        val token = sessionToken
        if (report.remoteId != null && token != null && supabase.isConfigured) {
            toast("관리자 처리 결과를 저장 중입니다.")
            Thread {
                val result = runCatching {
                    server.resolveReport(report.remoteId, decision, deductionAmount, note.trim(), sanction, token)
                }
                runOnUiThread {
                    if (token != sessionToken) return@runOnUiThread
                    result.onSuccess {
                        report.open = false
                        report.status = if (decision == "dismissed") "기각" else "처리 완료"
                        addNotification("⚖", "분쟁 처리가 완료되었어요", resolution, Screen.RESERVATIONS)
                        selectedAdminReportId = null
                        toast("처리 결과가 서버에 저장되었습니다.")
                        loadRemoteReservations()
                        loadCurrentProfile()
                        render()
                    }.onFailure { error -> toast("처리 실패: ${error.message ?: "관리자 권한을 확인하세요."}") }
                }
            }.start()
            return
        }
        report.open = false
        report.status = if (decision == "dismissed") "기각" else "처리 완료"
        contracts.find { it.id == report.contractId }?.apply {
            this.status = ContractStatus.COMPLETED
            refundHeld = false
            refunded = false
            val reservation = reservations.find { it.id == reservationId }
            val item = reservation?.let { itemById(it.itemId) }
            if (sanction == "hide_item" && item != null) items.remove(item)
            if (sanction in setOf("warning", "suspend_user")) currentUser().trust = (currentUser().trust - 80).coerceAtLeast(0)
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
        screen = if (BuildConfig.DEBUG && currentUser().role == "관리자") Screen.ADMIN else Screen.RESERVATIONS
        toast("신고가 접수되고 환급이 보류되었습니다.")
        render()
    }

    private fun createReview(reservation: Reservation, rating: Int, comment: String) {
        if (rating !in 1..5) { toast("별을 눌러 1~5점을 선택해주세요."); return }
        if (reviewSaving) return
        val token = sessionToken
        val item = itemById(reservation.itemId)
        val revieweeId = when (sessionUserId) {
            reservation.remoteBorrowerId -> item.remoteOwnerId
            else -> reservation.remoteBorrowerId
        }
        if (supabase.isConfigured && token != null && reservation.remoteId != null && revieweeId != null) {
            val account = sessionUserId
            reviewSaving = true
            toast("후기를 서버에 저장 중입니다.")
            Thread {
                val result = runCatching {
                    supabase.createReview(
                        reservation.remoteId,
                        revieweeId,
                        rating,
                        comment.ifBlank { "${rating}점으로 평가했습니다." },
                        token
                    )
                }
                runOnUiThread {
                    reviewSaving = false
                    if (sessionUserId != account) return@runOnUiThread
                    result.onSuccess {
                        reviewRatings.remove(reservation.id)
                        reviewComments.remove(reservation.id)
                        screen = Screen.PROFILE
                        loadCurrentProfile()
                        toast("후기가 등록되었습니다.")
                        render()
                    }.onFailure { error -> toast("후기 등록 실패: ${error.message ?: "이미 작성했는지 확인하세요."}") }
                }
            }.start()
            return
        }
        if (supabase.isConfigured) { toast("로그인과 거래 정보를 확인한 뒤 다시 시도해주세요."); return }
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

    private fun clearAccountViews() {
        pendingRefreshLoads.clear()
        chatHandler.removeCallbacks(rentalPoll)
        inspectionChecks.clear()
        rentalStep = RentalStep.HANDOVER_PHOTOS
        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
        pendingItemCameraUri?.let { uri -> runCatching { contentResolver.delete(uri, null, null) } }
        pendingItemCameraUri = null
        itemCameraPermissionPending = false

        reviewRatings.clear(); reviewComments.clear()
        clearPendingConditionPhoto()
        if (::supabase.isInitialized && supabase.isConfigured) items.clear()
        reservations.clear(); contracts.clear(); chatMessages.clear(); notifications.clear()
        paymentStates.clear(); qrPayloads.clear(); reports.clear(); activeContractId = null
        ownerProfiles.clear(); remoteChatRooms = emptyList(); archivedReservations = emptySet()
        selectedChatReservationId = null; activeChatRoomId = null; chatDrafts.clear()
        dateRequests = emptyList(); pendingNotificationReservation = null
        registrationDraft.clear(); registrationPhotoUris.clear()
    }

    private fun upsertItem(item: Item) {
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) items.add(item) else items[index] = item
    }

    private fun itemPhoto(item: Item, path: String? = item.imagePaths.firstOrNull()): FrameLayout = FrameLayout(this).apply {
        background = rounded(item.tone, 12)
        val placeholder = textView(if (path == null) "사진 없음" else "사진 불러오는 중", 13, false, muted).apply { gravity = Gravity.CENTER }
        addView(placeholder, FrameLayout.LayoutParams(match, match))
        if (path != null) {
            val image = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = item.title + " 등록 사진" }
            addView(image, FrameLayout.LayoutParams(match, match))
            ItemImageLoader.load(image, supabase.itemImageUrl(path)) { placeholder.text = "사진을 불러오지 못했어요" }
        }
    }

    private fun draftInput(key: String, initial: String, placeholder: String): EditText = edit(registrationDraft[key] ?: initial).apply {
        hint = placeholder
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { registrationDraft[key] = s.toString() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun editNickname() {
        val input = edit(currentUser().name)
        val dialog = AlertDialog.Builder(this).setTitle("닉네임 수정").setMessage("다른 회원에게 표시할 이름을 입력하세요 (2~30자).")
            .setView(input).setNegativeButton("취소", null).setPositiveButton("저장", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.length !in 2..30) { input.error = "2~30자로 입력하세요"; return@setOnClickListener }
                val user = sessionUserId ?: return@setOnClickListener
                val token = sessionToken ?: return@setOnClickListener
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                Thread {
                    val result = runCatching { supabase.updateDisplayName(name, token) }
                    runOnUiThread {
                        if (user != sessionUserId) { dialog.dismiss(); return@runOnUiThread }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        result.onSuccess { currentUser().name = name; ownerProfiles[user]?.let { ownerProfiles[user] = it.copy(displayName = name) }; dialog.dismiss(); render(); toast("닉네임을 변경했습니다.") }
                            .onFailure { toast("이름 수정 실패: ${it.message}") }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun counterpartyName(reservation: Reservation): String {
        val item = itemById(reservation.itemId)
        val id = if (item.remoteOwnerId == sessionUserId) reservation.remoteBorrowerId else item.remoteOwnerId
        return ownerProfiles[id]?.displayName ?: "거래 상대방"
    }

    private fun openReservationChat(reservation: Reservation) {
        selectedChatReservationId?.let { oldId -> chatInput?.let { chatDrafts[oldId] = it.text.toString() } }
        chatInput = null
        activeContractId = contracts.find { it.reservationId == reservation.id }?.id
        selectedChatReservationId = reservation.id
        selectedItemId = reservation.itemId
        activeChatRoomId = remoteChatRooms.find { it.reservationId == reservation.remoteId }?.id
        chatMessages.clear(); dateRequests = emptyList()
        chatDetailOpen = true; screen = Screen.CHAT
        render(); loadRemoteChat()
    }

    private fun removeReservation(reservation: Reservation) {
        val cancellable = reservation.status in ReservationRules.editableStatuses
        if (!cancellable && reservation.status !in ReservationRules.finishedStatuses) {
            AlertDialog.Builder(this).setTitle("진행 중인 거래").setMessage("결제·대여·분쟁 처리 중인 거래는 삭제할 수 없습니다. 반납 또는 환불 처리를 먼저 완료해주세요.").setPositiveButton("확인", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(if (cancellable) "예약 취소 및 삭제" else "내 거래 목록에서 삭제")
            .setMessage(if (cancellable) "예약을 취소하고 내 목록에서 삭제할까요? 상대방에게 취소 알림이 전달됩니다." else "내 목록에서 숨깁니다. 상대방의 거래 내역과 결제 기록은 유지됩니다.")
            .setNegativeButton("유지", null).setPositiveButton("삭제") { _, _ ->
                val id = reservation.remoteId
                val token = sessionToken
                if (id == null || token == null) { hiddenDemoReservations.add(reservation.id); if (cancellable) reservation.status = "cancelled"; render(); return@setPositiveButton }
                val account = sessionUserId
                Thread {
                    val result = runCatching { supabase.removeReservation(id, token) }
                    runOnUiThread {
                        if (account != sessionUserId) return@runOnUiThread
                        result.onSuccess { loadRemoteReservations(); toast("내 거래 목록에서 삭제했습니다.") }.onFailure { toast("삭제 실패: ${it.message}") }
                    }
                }.start()
            }.show()
    }

    private fun enableSwipeDelete(view: View, onDelete: () -> Unit) {
        var x = 0f; var y = 0f; var horizontal = false; var longPressed = false
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onLongPress(event: MotionEvent) {
                if (!horizontal) {
                    longPressed = true
                    view.performLongClick()
                }
            }
        })
        view.setOnTouchListener { target, event ->
            gestures.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = event.x; y = event.y; horizontal = false; longPressed = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - x) > dp(24) && kotlin.math.abs(event.x - x) > kotlin.math.abs(event.y - y) * 1.5f) {
                        horizontal = true; target.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    if (horizontal) target.alpha = 0.6f
                    true
                }
                MotionEvent.ACTION_UP -> {
                    target.alpha = 1f; target.parent.requestDisallowInterceptTouchEvent(false)
                    if (!longPressed && horizontal && event.x - x < -dp(80)) onDelete()
                    else if (!longPressed && !horizontal && kotlin.math.abs(event.y-y) < dp(16)) target.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { target.alpha = 1f; target.parent.requestDisallowInterceptTouchEvent(false); true }
                else -> false
            }
        }
    }

    private fun showChatRoomMenu(room: RemoteChatRoom, reservation: Reservation) {
        val account = sessionUserId ?: return
        val reservationId = room.reservationId
        val isMuted = ChatRoomSettings.isMuted(this, account, reservationId)
        val isPinned = ChatRoomSettings.isPinned(this, account, reservationId)
        val dialog = Dialog(this)
        val sheet = column {
            setPadding(dp(20), dp(12), dp(20), dp(18))
            setBackgroundColor(bg)
        }
        sheet.addView(textView("${counterpartyName(reservation)} · ${itemById(reservation.itemId).title}", 16, true, text).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(22))
        })
        val actions = column { background = rounded(surface, 18) }
        fun option(label: String, color: Int = text, action: () -> Unit) {
            actions.addView(textView(label, 17, true, color).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(20), dp(12), dp(20))
                setOnClickListener { dialog.dismiss(); action() }
            }, LinearLayout.LayoutParams(match, dp(68)))
        }
        option(if (isMuted) "알림 켜기" else "알림 끄기") {
            ChatRoomSettings.setMuted(this, account, reservationId, !isMuted)
            render()
        }
        actions.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(match, dp(1)))
        option(if (isPinned) "채팅방 상단 고정 해제" else "채팅방 상단 고정") {
            ChatRoomSettings.setPinned(this, account, reservationId, !isPinned)
            render()
        }
        actions.addView(View(this).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(match, dp(1)))
        option("채팅방 나가기", danger) { confirmHideChat(room) }
        sheet.addView(actions)
        sheet.addView(textView("취소", 17, true, text).apply {
            gravity = Gravity.CENTER
            background = rounded(surface, 18)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(match, dp(68)).apply { topMargin = dp(14) })
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(match, wrap)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.setLayout(match, wrap)
    }

    private fun confirmHideChat(room: RemoteChatRoom) {
        AlertDialog.Builder(this).setTitle("채팅방 나가기").setMessage("내 채팅 목록에서 숨깁니다. 거래 기록과 상대방의 대화는 유지되며 새 메시지가 오면 다시 표시됩니다.")
            .setNegativeButton("취소", null).setPositiveButton("나가기") { _, _ ->
                val token = sessionToken ?: return@setPositiveButton
                val account = sessionUserId
                Thread {
                    val result = runCatching { supabase.hideChatRoom(room.id, token) }
                    runOnUiThread {
                        if (account != sessionUserId) return@runOnUiThread
                        result.onSuccess {
                            if (account != null) ChatRoomSettings.clear(this, account, room.reservationId)
                            remoteChatRooms = remoteChatRooms.map { if (it.id == room.id) it.copy(hidden = true) else it }
                            render()
                        }
                            .onFailure { toast("채팅방 나가기 실패: ${it.message}") }
                    }
                }.start()
            }.show()
    }

    private fun proposeDates(reservation: Reservation) {
        val initial = LocalDate.parse(reservation.startDate).coerceAtLeast(LocalDate.now())
        DatePickerDialog(this, { _, year, month, day ->
            val start = LocalDate.of(year, month + 1, day)
            val endInitial = LocalDate.parse(reservation.endDate).coerceAtLeast(start)
            DatePickerDialog(this, endDateSelected@ { _, endYear, endMonth, endDay ->
                val end = LocalDate.of(endYear, endMonth + 1, endDay)
                val fee = runCatching { ReservationRules.rentalFee(itemById(reservation.itemId).price, start, end) }.getOrElse { toast("날짜와 금액을 확인하세요."); return@endDateSelected }
                val total = fee.toLong() + reservation.depositAmount
                if (total > Int.MAX_VALUE) { toast("대여 기간이 너무 깁니다."); return@endDateSelected }
                AlertDialog.Builder(this).setTitle("날짜 변경 제안").setMessage("$start ~ $end\n예상 금액 ${money(total.toInt())}\n상대방이 수락하면 일정과 대여료가 변경됩니다.")
                    .setNegativeButton("취소", null).setPositiveButton("제안 보내기") { _, _ ->
                        val id = reservation.remoteId ?: return@setPositiveButton
                        val token = sessionToken ?: return@setPositiveButton
                        Thread {
                            val result = runCatching { supabase.proposeDates(id, start.toString(), end.toString(), token) }
                            runOnUiThread { result.onSuccess { loadRemoteChat(); toast("상대방에게 일정 변경을 제안했습니다.") }.onFailure { toast("변경 제안 실패: ${it.message}") } }
                        }.start()
                    }.show()
            }, endInitial.year, endInitial.monthValue-1, endInitial.dayOfMonth).apply {
                setTitle("변경할 종료일"); datePicker.minDate = start.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.show()
        }, initial.year, initial.monthValue-1, initial.dayOfMonth).apply {
            setTitle("변경할 시작일"); datePicker.minDate = System.currentTimeMillis()-1000
        }.show()
    }

    private fun renderDateRequests(reservation: Reservation) {
        val container = dateRequestsContainer ?: return
        container.removeAllViews()
        dateRequests.forEach { request ->
            container.addView(card(Color.rgb(239,246,255)).apply {
                addView(textView("일정 변경 제안", 16, true, blue))
                addView(textView("${request.startDate} ~ ${request.endDate}", 14, true, text).withTop(4))
                addView(textView("변경 후 금액 ${money(request.rentalFee + reservation.depositAmount)}", 14, false, text).withTop(4))
                if (request.requestedBy == sessionUserId) addView(textView("상대방의 응답을 기다리고 있어요.", 13, false, muted).withTop(6))
                else addView(row {
                    addView(button("수락") { respondDates(request, true) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                    addView(space(8))
                    addView(button("거절", secondary = true) { respondDates(request, false) }, LinearLayout.LayoutParams(0, dp(44), 1f))
                }.withTop(8))
            }.withTop(10))
        }
    }

    private fun respondDates(request: RemoteDateRequest, accept: Boolean) {
        val token = sessionToken ?: return
        val account = sessionUserId
        Thread {
            val result = runCatching { supabase.respondDates(request.id, accept, token) }
            runOnUiThread {
                if (account != sessionUserId) return@runOnUiThread
                result.onSuccess { loadRemoteReservations(); toast(if (accept) "예약 일정이 변경되었습니다." else "변경 제안을 거절했습니다.") }
                    .onFailure { toast("일정 처리 실패: ${it.message}"); loadRemoteChat() }
            }
        }.start()
    }

    private fun captureNotificationIntent(source: Intent?) {
        pendingNotificationReservation = source?.getStringExtra(NotificationDelivery.EXTRA_RESERVATION)
        pendingNotificationKind = source?.getStringExtra(NotificationDelivery.EXTRA_KIND)
        source?.removeExtra(NotificationDelivery.EXTRA_RESERVATION)
        source?.removeExtra(NotificationDelivery.EXTRA_KIND)
    }

    private fun loadRemoteNotifications() {
        if (!::supabase.isInitialized || notificationLoading) return
        val account = sessionUserId ?: return
        if (sessionToken == null) return
        notificationLoading = true
        Thread {
            val result = runCatching {
                val auth = SessionTokens.refresh(this, supabase)
                val token = auth.accessToken ?: error("다시 로그인해주세요.")
                check(auth.userId == account)
                NotificationDelivery.sync(this, supabase, token, account)
                token to supabase.fetchNotifications(token)
            }
            runOnUiThread {
                notificationLoading = false
                if (sessionUserId != account) return@runOnUiThread
                result.onSuccess { (token, events) ->
                    sessionToken = token
                    val mapped = events.map { event -> AppNotification(event.id.hashCode(), if (event.kind == "chat") "💬" else "📅", event.title, event.body,
                        event.createdAt.take(10), if (event.kind in setOf("chat", "schedule")) Screen.CHAT else Screen.RESERVATIONS,
                        event.unread, event.id, event.reservationId) }
                    val changed = notifications != mapped
                    notifications.clear(); notifications.addAll(mapped)
                    if (screen == Screen.NOTIFICATIONS && changed) render()
                }
            }
        }.start()
    }

    private fun markNotificationsRead(selected: List<AppNotification>) {
        val ids = selected.mapNotNull { it.remoteId }
        val token = sessionToken ?: return
        val account = sessionUserId
        Thread {
            val result = runCatching { supabase.markNotificationsRead(ids, token) }
            runOnUiThread {
                if (sessionUserId != account) return@runOnUiThread
                result.onSuccess { selected.forEach { it.unread = false }; if (screen == Screen.NOTIFICATIONS) render() }
                    .onFailure { toast("알림 읽음 처리 실패: ${it.message}") }
            }
        }.start()
    }

    private fun selectedItem(): Item = itemById(selectedItemId)
    private fun itemById(id: Int): Item = items.first { it.id == id }
    private fun currentUser(): User = if (supabase.isConfigured) serverUser else users[1]
    private fun ownerOf(item: Item): User {
        if (item.remoteOwnerId != null) {
            if (item.remoteOwnerId == sessionUserId) return currentUser()
            return ownerProfiles[item.remoteOwnerId]?.toUser(item.ownerId)
                ?: User(item.ownerId, "사용자 정보 확인 중", "대여자", false, 0, 0.0, 0, 0)
        }
        return users.first { it.id == item.ownerId }
    }

    private fun RemoteProfile.toUser(localId: Int = 2) = User(
        localId, displayName, if (role == "admin") "관리자" else "회원", verified, trustScore, rating, dealCount, 0
    )

    private fun resetServerProfile() {
        // Invalidate any profile response that was started before a login change
        // or successful PASS verification.
        profileSyncVersion++
        serverUser = User(2, if (sessionToken == null) "로그인 필요" else "프로필 확인 중", "회원", false, 0, 0.0, 0, 0)
        profileLoaded = false
        profileLoading = false
        profileError = null
        profileReviews = emptyList()
        profileReviewsError = null
    }

    private fun loadCurrentProfile() {
        val token = sessionToken ?: return
        val userId = sessionUserId ?: return
        if (!supabase.isConfigured || profileLoading) return
        val version = ++profileSyncVersion
        profileLoading = true
        Thread {
            val result = runCatching { supabase.fetchCurrentProfile(token) }
            val reviews = runCatching { supabase.fetchProfileReviews(userId, token) }
            runOnUiThread {
                try {
                    if (sessionUserId != userId ||
                        version != profileSyncVersion || isFinishing) return@runOnUiThread
                    profileLoading = false
                    result.onSuccess { profile ->
                        if (profile.id != userId) return@onSuccess
                        serverUser = profile.toUser()
                        ownerProfiles[profile.id] = profile
                        profileLoaded = true
                        profileError = null
                        if (profile.verified) verificationStep = VerificationStep.NONE
                    }.onFailure {
                        profileLoaded = false
                        serverUser.verified = false
                        profileError = "프로필을 확인할 수 없습니다. 새로고침해주세요."
                    }
                    profileReviews = reviews.getOrDefault(emptyList())
                    profileReviewsError = if (reviews.isFailure) "후기를 불러오지 못했습니다." else null
                    if (screen != Screen.CHAT || !chatDetailOpen) render()
                } finally {
                    finishPullRefresh("profile", result.isSuccess && reviews.isSuccess)
                }
            }
        }.start()
    }

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

    private fun categoryStrip(
        categories: List<String>, selected: String, savedScrollX: Int,
        onScroll: (Int) -> Unit, onSelect: (String) -> Unit
    ): HorizontalScrollView = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        setOnScrollChangeListener { _, scrollX, _, _, _ -> onScroll(scrollX) }
        addView(row {
            categories.forEachIndexed { index, category ->
                if (index > 0) addView(space(8))
                addView(categoryChip(category, selected == category) { onSelect(category) })
            }
        })
        post { scrollTo(savedScrollX, 0) }
    }

    private fun metric(label: String, value: String, valueColor: Int = text): LinearLayout = row {
        addView(textView(label, 14, false, muted), LinearLayout.LayoutParams(0, wrap, 1f))
        addView(textView(value, 14, true, valueColor))
    }.withTop(8)

    private fun field(label: String, view: View): LinearLayout = column {
        addView(textView(label, 13, true, muted))
        if (view is EditText) view.minHeight = dp(48)
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

    private fun conditionPhotoView(path: String, expanded: Boolean = false): FrameLayout = FrameLayout(this).apply {
        background = rounded(Color.rgb(248, 250, 252), 8, line)
        val label = textView("사진 불러오는 중", 11, false, muted).apply { gravity = Gravity.CENTER }
        addView(label, FrameLayout.LayoutParams(match, match))
        val image = ImageView(this@MainActivity).apply {
            scaleType = if (expanded) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
            contentDescription = "등록한 물품 상태 사진"
        }
        addView(image, FrameLayout.LayoutParams(match, match))
        val account = sessionUserId
        fun load() {
            label.text = "사진 불러오는 중"
            val token = sessionToken
            if (account == null || token == null || account != sessionUserId) {
                label.text = "로그인이 필요합니다"
                return
            }
            ItemImageLoader.loadResolved(image, "condition:$account:$path", {
                supabase.conditionPhotoUrl(path, token)
            }) { label.text = "불러오기 실패\n눌러서 재시도" }
        }
        load()
        setOnClickListener {
            if (account != sessionUserId) return@setOnClickListener
            if (image.drawable == null) load()
            else if (!expanded) {
                val preview = conditionPhotoView(path, true)
                AlertDialog.Builder(this@MainActivity).setTitle("물품 상태 사진")
                    .setView(preview)
                    .setPositiveButton("닫기", null).create().apply {
                        setOnShowListener { preview.layoutParams = preview.layoutParams.apply { height = dp(360) } }
                        show()
                    }
            }
        }
    }

    private fun photoSlots(paths: List<String>, onCapture: () -> Unit): LinearLayout = row {
        repeat(maxOf(5, paths.size)) { index ->
            val photo = paths.getOrNull(index)
            val tile: View = if (photo != null) conditionPhotoView(photo) else TextView(this@MainActivity).apply {
                text = "+"
                gravity = Gravity.CENTER
                textSize = 20f
                setTextColor(muted)
                background = rounded(Color.rgb(248, 250, 252), 8, line)
                contentDescription = "상태 사진 ${index + 1} 촬영"
                isFocusable = true
                setOnClickListener { onCapture() }
            }
            addView(tile, LinearLayout.LayoutParams(0, dp(72), 1f))
            if (index != maxOf(5, paths.size) - 1) addView(space(6))
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

    private fun chatBubble(message: ChatMessage): LinearLayout = column {
        val width = (resources.displayMetrics.widthPixels * 0.72f).roundToInt()
        layoutParams = LinearLayout.LayoutParams(width, wrap).apply {
            gravity = if (message.mine) Gravity.END else Gravity.START
        }
        background = rounded(if (message.mine) blue else Color.WHITE, 8, if (message.mine) blue else line)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        val media = ChatMedia.fromBody(message.text)
        if (media == null) {
            addView(textView(message.text, 14, false, if (message.mine) Color.WHITE else text))
        } else {
            addView(button(if (media.kind == "image") "📷 사진 보기" else "▶ 영상 보기", secondary = true) {
                openChatMedia(media)
            })
        }
        addView(textView("${message.time}${if (message.mine && message.read) " · 읽음" else ""}", 10, false, if (message.mine) Color.rgb(219, 234, 254) else muted).apply {
            gravity = Gravity.END
        }.withTop(4))
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
