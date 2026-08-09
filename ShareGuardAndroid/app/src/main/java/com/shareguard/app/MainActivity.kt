package com.shareguard.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.shareguard.app.data.AuthResult
import com.shareguard.app.data.NewRemoteItem
import com.shareguard.app.data.ProfileResult
import com.shareguard.app.data.RemoteItem
import com.shareguard.app.data.SupabaseRestClient
import io.portone.sdk.android.PortOne
import io.portone.sdk.android.identityverification.IdentityVerificationCallback
import io.portone.sdk.android.type.request.IdentityVerificationRequest
import io.portone.sdk.android.type.response.IdentityVerificationResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    companion object {
        private const val OAUTH_REDIRECT_URI = "shareguard://auth/callback"
        private const val SESSION_PREFERENCES = "shareguard_session"
        private const val OAUTH_CODE_VERIFIER = "oauth_code_verifier"
        private const val match = ViewGroup.LayoutParams.MATCH_PARENT
        private const val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private enum class Screen(val title: String, val subtitle: String) {
        HOME("위치 기반 물품 탐색", "주변 물품을 거리, 가격, 신뢰도 기준으로 확인합니다."),
        REGISTER("물품 등록", "사진, 가격, 보증금, 거래 위치를 입력해 물품을 등록합니다."),
        RESERVATIONS("예약 및 거래 관리", "예약 승인부터 결제, 계약, QR 인증, 환급까지 진행합니다."),
        CHAT("실시간 채팅", "대여 조건, 장소, 시간을 거래 당사자끼리 조율합니다."),
        QR("QR 거래 인증", "상태 사진 기록 후 QR로 대여 시작과 반납 완료를 인증합니다."),
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
        val title: String,
        val category: String,
        val description: String,
        val price: Int,
        val deposit: Int,
        val distance: Int,
        val location: String,
        val ownerId: Int,
        val tone: Int,
        val remoteId: String? = null
    )

    private data class Reservation(
        val id: Int,
        val itemId: Int,
        val borrowerId: Int,
        val startDate: String,
        val endDate: String,
        val totalAmount: Int,
        var approved: Boolean = false
    )

    private data class Contract(
        val id: Int,
        val reservationId: Int,
        var status: ContractStatus,
        var beforePhotos: Int = 0,
        var afterPhotos: Int = 0,
        var refunded: Boolean = false,
        var refundHeld: Boolean = false
    )

    private data class ChatMessage(val mine: Boolean, val text: String)
    private data class Report(val id: Int, val contractId: Int, val type: String, val reason: String, var open: Boolean = true)

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
    private var sessionRefreshToken: String? = null
    private var authenticatedUserId: String? = null
    private var authenticatedEmail: String? = null
    private var authenticatedDisplayName: String? = null

    private val identityVerificationLauncher =
        PortOne.registerForIdentityVerificationActivity(
            this,
            callback = object : IdentityVerificationCallback {
                override fun onSuccess(response: IdentityVerificationResponse) {
                    verifyPassOnServer(response.identityVerificationId)
                }

                override fun onFail(response: IdentityVerificationResponse) {
                    toast(response.message ?: response.pgMessage ?: "PASS 본인인증이 취소되었거나 실패했습니다.")
                }
            }
        )

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
    private val chatMessages = mutableListOf(
        ChatMessage(false, "가능합니다. 5월 1일부터 3일까지 비워둘게요."),
        ChatMessage(true, "거래 장소는 역삼역 근처가 괜찮습니다.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supabase = SupabaseRestClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY)
        val preferences = getSharedPreferences(SESSION_PREFERENCES, MODE_PRIVATE)
        sessionToken = preferences.getString("access_token", null)
        sessionRefreshToken = preferences.getString("refresh_token", null)
        authenticatedUserId = preferences.getString("user_id", null)
        authenticatedEmail = preferences.getString("email", null)
        authenticatedDisplayName = preferences.getString("display_name", null)
        if (supabase.isConfigured) {
            currentUser().verified = false
        }
        buildShell()
        render()
        if (!handleGoogleAuthCallback(intent)) {
            restoreSession()
        }
        loadRemoteItems()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGoogleAuthCallback(intent)
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
        renderHeader()
        renderAuthNotice()
        when (screen) {
            Screen.HOME -> renderHome()
            Screen.REGISTER -> renderRegister()
            Screen.RESERVATIONS -> renderReservations()
            Screen.CHAT -> renderChat()
            Screen.QR -> renderQr()
            Screen.PROFILE -> renderProfile()
            Screen.ADMIN -> renderAdmin()
        }
        renderBottomNav()
    }

    private fun renderHeader() {
        content.addView(textView(screen.title, 26, true, text))
        content.addView(textView(screen.subtitle, 14, false, muted).withTop(4))
        content.addView(row {
            addView(button("데모 초기화", secondary = true) {
                resetDemo()
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
            addView(space(8))
            addView(button("내 신뢰 지수", secondary = true) {
                screen = Screen.PROFILE
                render()
            }, LinearLayout.LayoutParams(0, dp(40), 1f))
        }.withTop(14))
    }

    private fun renderAuthNotice() {
        val user = currentUser()
        if (supabase.isConfigured) {
            val signedIn = !sessionToken.isNullOrBlank()
            val fullyVerified = signedIn && user.verified
            val box = card(if (fullyVerified) Color.rgb(234, 248, 239) else Color.rgb(255, 245, 221))
            val title = when {
                fullyVerified -> "로그인 및 PASS 본인인증 완료"
                signedIn -> "로그인 완료 · PASS 본인인증 필요"
                else -> "로그인 필요"
            }
            box.addView(textView(title, 14, true, if (fullyVerified) good else warn))
            if (signedIn) {
                val account = authenticatedEmail ?: authenticatedDisplayName ?: "ShareGuard 사용자"
                box.addView(textView("계정: $account", 13, false, muted).withTop(2))
                box.addView(textView(
                    if (fullyVerified) "거래와 물품 등록 기능을 사용할 수 있습니다."
                    else "거래와 물품 등록 전에 PASS 앱 본인인증을 완료하세요.",
                    13,
                    false,
                    muted
                ).withTop(2))
                if (!fullyVerified) {
                    box.addView(button("PASS 앱으로 본인인증") { startPassVerification() }.withTop(10))
                }
                box.addView(button("로그아웃", secondary = true) { signOut() }.withTop(10))
            } else {
                box.addView(textView("Google 계정 또는 이메일로 가입하고 로그인할 수 있습니다.", 13, false, muted).withTop(2))
                val displayName = edit("홍길동")
                val email = edit("shareguard@example.com").apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                }
                val password = edit("").apply {
                    hint = "8자 이상 비밀번호"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                box.addView(field("이름", displayName))
                box.addView(field("이메일", email))
                box.addView(field("비밀번호", password))
                box.addView(row {
                    addView(button("로그인") {
                        authenticate(email.text.toString(), password.text.toString(), displayName.text.toString(), false)
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    addView(space(8))
                    addView(button("회원가입", secondary = true) {
                        authenticate(email.text.toString(), password.text.toString(), displayName.text.toString(), true)
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                }.withTop(10))
                box.addView(button("Google 계정으로 로그인", secondary = true) {
                    startGoogleSignIn()
                }.withTop(10))
            }
            content.addView(box.withTop(14))
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

    private fun renderHome() {
        content.addView(sectionTitle("ITEM-01 위치 기반 검색", "카테고리와 거리 정보를 포함한 물품 목록입니다."))
        items.sortedBy { it.distance }.forEach { item ->
            content.addView(itemCard(item).withTop(10))
        }
        content.addView(sectionTitle("선택한 물품 상세", "FR-03, FR-05 정보를 같은 흐름에서 확인합니다.").withTop(18))
        content.addView(detailCard(selectedItem()).withTop(10))
    }

    private fun itemCard(item: Item): LinearLayout {
        val owner = ownerOf(item)
        return card().apply {
            addView(row {
                addView(colorBlock(item.category, item.tone), LinearLayout.LayoutParams(dp(86), dp(86)))
                addView(column {
                    addView(textView(item.title, 17, true, text))
                    addView(textView("${item.location} · ${item.distance}m · ${owner.name} 신뢰 ${owner.trust}", 12, false, muted).withTop(2))
                    addView(textView("${money(item.price)} /일 · 보증금 ${money(item.deposit)}", 13, true, blue).withTop(6))
                    addView(row {
                        addView(button("상세 보기", secondary = true) {
                            selectedItemId = item.id
                            render()
                        }, LinearLayout.LayoutParams(0, dp(36), 1f))
                        addView(space(8))
                        addView(button("예약 신청") {
                            selectedItemId = item.id
                            createReservation(item)
                        }, LinearLayout.LayoutParams(0, dp(36), 1f))
                    }.withTop(8))
                }, LinearLayout.LayoutParams(0, wrap, 1f).withLeftMargin(12))
            })
        }
    }

    private fun detailCard(item: Item): LinearLayout {
        val days = 3
        val discount = if (currentUser().trust >= 850) (item.deposit * 0.1).roundToInt() else 0
        val total = item.price * days + item.deposit - discount
        return card().apply {
            addView(textView(item.title, 19, true, text))
            addView(textView(item.description, 13, false, muted).withTop(6))
            addView(metric("대여 기간", "2026-05-01 ~ 2026-05-03").withTop(10))
            addView(metric("대여료", money(item.price * days)))
            addView(metric("보증금", money(item.deposit)))
            addView(metric("신뢰 지수 할인", if (discount > 0) "-${money(discount)}" else "없음"))
            addView(metric("결제 예정액", money(total), blue))
            addView(button("이 물품 예약 신청") {
                createReservation(item)
            }.withTop(12))
        }
    }

    private fun renderRegister() {
        content.addView(sectionTitle("ITEM-02 물품 등록", "최대 5장 사진, 가격, 보증금, 위치 정보를 저장합니다."))
        val title = edit("니콘 Z6 II 미러리스")
        val price = edit("45000")
        val deposit = edit("180000")
        val location = edit("강남구 삼성동")
        val description = edit("렌즈, 배터리, 스트랩 포함. 거래 전후 사진 기록을 필수로 진행합니다.")
        content.addView(card().apply {
            addView(field("물품명", title))
            addView(field("하루 대여료", price))
            addView(field("보증금", deposit))
            addView(field("거래 위치", location))
            addView(field("설명", description))
            addView(photoSlots(2).withTop(10))
            addView(button("물품 등록") {
                if (!currentUser().verified) {
                    toast("본인 인증 후 등록할 수 있습니다.")
                    return@button
                }
                val newItem = Item(
                    nextItemId++,
                    title.text.toString().trim(),
                    "카메라",
                    description.text.toString().trim(),
                    price.text.toString().toIntOrNull() ?: 0,
                    deposit.text.toString().toIntOrNull() ?: 0,
                    960,
                    location.text.toString().trim(),
                    1,
                    Color.rgb(219, 234, 254)
                )
                saveItem(newItem)
            }.withTop(12))
        }.withTop(10))
    }

    private fun renderReservations() {
        content.addView(sectionTitle("RENT-03 예약 목록", "예약 승인 후 결제, 계약, 상태 인증을 순서대로 진행합니다."))
        if (reservations.isEmpty()) {
            content.addView(empty("아직 예약이 없습니다. 홈에서 물품을 선택해 예약을 신청하세요.").withTop(10))
        } else {
            reservations.forEach { reservation ->
                content.addView(reservationCard(reservation).withTop(10))
            }
        }
        content.addView(contractSummary().withTop(16))
        content.addView(flowSummary().withTop(10))
    }

    private fun reservationCard(reservation: Reservation): LinearLayout {
        val item = itemById(reservation.itemId)
        val contract = contracts.find { it.reservationId == reservation.id }
        return card().apply {
            addView(textView(item.title, 18, true, text))
            addView(textView("${reservation.startDate} ~ ${reservation.endDate} · ${money(reservation.totalAmount)}", 13, false, muted).withTop(3))
            addView(statusChip(contract?.status?.label ?: if (reservation.approved) "예약 승인" else "승인 대기").withTop(8))
            addView(row {
                when {
                    contract == null -> addView(button("예약 승인") { approveReservation(reservation) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.APPROVED -> addView(button("에스크로 결제") { pay(contract) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.PAID -> addView(button("전자 계약 서명") { sign(contract) }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.SIGNED || contract.status == ContractStatus.READY || contract.status == ContractStatus.RENTING -> addView(button("QR 인증으로 이동") {
                        screen = Screen.QR
                        render()
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    contract.status == ContractStatus.COMPLETED -> addView(button("후기 작성") {
                        currentUser().trust += 8
                        toast("후기가 등록되고 신뢰 지수가 반영되었습니다.")
                        screen = Screen.PROFILE
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
                        createReport(contract)
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                }
            }.withTop(10))
        }
    }

    private fun renderChat() {
        content.addView(sectionTitle("FR-08 채팅", "거래 조건, 장소, 시간을 조율합니다."))
        content.addView(card().apply {
            addView(textView(selectedItem().title, 18, true, text))
            addView(textView("예약 조건, 장소, 시간을 조율합니다.", 13, false, muted).withTop(2))
            addView(button("이 물품 예약 신청") {
                createReservation(selectedItem())
            }.withTop(10))
        }.withTop(10))
        content.addView(card().apply {
            chatMessages.forEach { msg ->
                addView(chatBubble(msg).withTop(8))
            }
            val input = edit("내일 오전 10시에 거래하겠습니다.")
            addView(field("메시지", input).withTop(10))
            addView(button("전송") {
                chatMessages.add(ChatMessage(true, input.text.toString()))
                render()
            }.withTop(8))
        }.withTop(10))
    }

    private fun renderQr() {
        val contract = contracts.lastOrNull()
        if (contract == null) {
            content.addView(empty("진행 중인 계약이 없습니다. 예약 승인 후 QR 인증을 사용할 수 있습니다."))
            return
        }
        content.addView(sectionTitle("RENT-04 상태 사진 / RENT-02 QR 인증", "대여 전 사진이 있어야 START 인증이 가능합니다."))
        content.addView(card().apply {
            addView(textView("상태 사진 아카이브", 18, true, text))
            addView(textView("대여 전 ${contract.beforePhotos}/5 · 반납 후 ${contract.afterPhotos}/5", 13, false, muted).withTop(2))
            addView(photoSlots(contract.beforePhotos).withTop(10))
            addView(photoSlots(contract.afterPhotos).withTop(8))
            addView(row {
                addView(button("대여 전 사진 업로드", secondary = true) {
                    if (contract.status == ContractStatus.SIGNED) {
                        contract.beforePhotos = 5
                        contract.status = ContractStatus.READY
                        render()
                    } else toast("전자 계약 서명 후 업로드할 수 있습니다.")
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(space(8))
                addView(button("반납 후 사진 업로드", secondary = true) {
                    if (contract.status == ContractStatus.RENTING) {
                        contract.afterPhotos = 5
                        render()
                    } else toast("대여중 상태에서 업로드할 수 있습니다.")
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            }.withTop(10))
        }.withTop(10))
        content.addView(card().apply {
            addView(textView("QR-SG-${contract.id}-${contract.status.name}", 18, true, text))
            addView(qrBox().withTop(10))
            addView(row {
                addView(button("START 인증") {
                    if (contract.status == ContractStatus.READY && contract.beforePhotos > 0) {
                        contract.status = ContractStatus.RENTING
                        render()
                    } else toast("대여 전 사진 업로드가 필요합니다.")
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(space(8))
                addView(button("END 인증", goodStyle = true) {
                    if (contract.status == ContractStatus.RENTING && contract.afterPhotos > 0) {
                        contract.status = ContractStatus.COMPLETED
                        contract.refunded = true
                        currentUser().deals += 1
                        currentUser().trust += 12
                        screen = Screen.RESERVATIONS
                        toast("반납 완료 및 보증금 환급 처리되었습니다.")
                        render()
                    } else toast("반납 후 사진 업로드가 필요합니다.")
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
            }.withTop(12))
        }.withTop(10))
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
        })
        content.addView(sectionTitle("최근 후기", "FR-13 후기가 신뢰 지수에 반영됩니다.").withTop(16))
        content.addView(card().apply {
            addView(textView("★★★★★", 20, true, warn))
            addView(textView("카메라 상태가 좋고 약속 시간이 정확했어요.", 14, false, text).withTop(4))
            addView(textView("지수 · 2일 전", 12, false, muted).withTop(2))
        }.withTop(10))
    }

    private fun renderAdmin() {
        content.addView(sectionTitle("신고 및 분쟁 큐", "파손, 미반납, 허위 등록 신고를 검토합니다."))
        if (reports.isEmpty()) {
            content.addView(empty("접수된 신고가 없습니다.").withTop(10))
        } else {
            reports.forEach { report ->
                content.addView(card().apply {
                    addView(textView("DIS-${report.id} · ${report.type}", 18, true, text))
                    addView(textView(report.reason, 13, false, muted).withTop(4))
                    addView(statusChip(if (report.open) "검토중" else "종결").withTop(8))
                    addView(button("일부 차감 후 종결", dangerStyle = true) {
                        val contract = contracts.find { it.id == report.contractId }
                        report.open = false
                        contract?.status = ContractStatus.COMPLETED
                        contract?.refundHeld = false
                        contract?.refunded = true
                        toast("관리자 처리가 완료되었습니다.")
                        render()
                    }.withTop(10))
                }.withTop(10))
            }
        }
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

    private fun authenticate(email: String, password: String, displayName: String, createAccount: Boolean) {
        if (!email.contains('@') || password.length < 8) {
            toast("이메일과 8자 이상 비밀번호를 입력하세요.")
            return
        }
        if (createAccount && displayName.trim().length !in 2..30) {
            toast("회원가입 이름은 2~30자로 입력하세요.")
            return
        }
        toast(if (createAccount) "회원가입 중입니다." else "로그인 중입니다.")
        Thread {
            val result = runCatching {
                val auth = if (createAccount) {
                    supabase.signUp(email, password, displayName)
                } else {
                    supabase.signIn(email, password)
                }
                val profile = auth.accessToken?.let(supabase::fetchProfile)
                auth to profile
            }
            runOnUiThread {
                result.onSuccess { (auth, profile) ->
                    if (auth.isSignedIn) {
                        applySession(auth, profile)
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

    private fun startGoogleSignIn() {
        val verifierBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val verifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        getSharedPreferences(SESSION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString(OAUTH_CODE_VERIFIER, verifier)
            .apply()
        val url = supabase.googleSignInUrl(OAUTH_REDIRECT_URI, challenge)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun handleGoogleAuthCallback(callbackIntent: Intent?): Boolean {
        val uri = callbackIntent?.data ?: return false
        if (uri.scheme != "shareguard" || uri.host != "auth" || uri.path != "/callback") return false

        val error = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            toast("Google 로그인 실패: $error")
            return true
        }
        val code = uri.getQueryParameter("code")
        val preferences = getSharedPreferences(SESSION_PREFERENCES, MODE_PRIVATE)
        val verifier = preferences.getString(OAUTH_CODE_VERIFIER, null)
        preferences.edit().remove(OAUTH_CODE_VERIFIER).apply()
        if (code.isNullOrBlank() || verifier.isNullOrBlank()) {
            toast("Google 로그인 정보를 확인할 수 없습니다. 다시 시도하세요.")
            return true
        }

        toast("Google 로그인 확인 중입니다.")
        Thread {
            val result = runCatching {
                val auth = supabase.exchangeCodeForSession(code, verifier)
                val profile = auth.accessToken?.let(supabase::fetchProfile)
                auth to profile
            }
            runOnUiThread {
                result.onSuccess { (auth, profile) ->
                    if (auth.isSignedIn) {
                        applySession(auth, profile)
                        loadRemoteItems()
                    }
                    toast(auth.message)
                    render()
                }.onFailure { errorResult ->
                    toast(errorResult.message ?: "Google 로그인에 실패했습니다.")
                }
            }
        }.start()
        return true
    }

    private fun restoreSession() {
        val storedAccessToken = sessionToken
        val storedRefreshToken = sessionRefreshToken
        if (storedAccessToken.isNullOrBlank() && storedRefreshToken.isNullOrBlank()) return

        Thread {
            val result = runCatching {
                val refreshed = storedRefreshToken?.let(supabase::refreshSession)
                val auth = if (refreshed?.isSignedIn == true) {
                    refreshed
                } else {
                    AuthResult(
                        accessToken = storedAccessToken,
                        refreshToken = storedRefreshToken,
                        userId = authenticatedUserId,
                        email = authenticatedEmail,
                        message = "세션 복원 완료"
                    )
                }
                val token = auth.accessToken ?: error("저장된 로그인 세션이 없습니다.")
                auth to supabase.fetchProfile(token)
            }
            runOnUiThread {
                result.onSuccess { (auth, profile) ->
                    applySession(auth, profile)
                    render()
                    loadRemoteItems()
                }.onFailure {
                    clearLocalSession()
                    render()
                    toast("로그인 세션이 만료되었습니다. 다시 로그인하세요.")
                }
            }
        }.start()
    }

    private fun applySession(auth: AuthResult, profile: ProfileResult?) {
        sessionToken = auth.accessToken
        sessionRefreshToken = auth.refreshToken ?: sessionRefreshToken
        authenticatedUserId = profile?.userId ?: auth.userId
        authenticatedEmail = profile?.email ?: auth.email
        authenticatedDisplayName = profile?.displayName
        currentUser().verified = profile?.verified ?: false
        getSharedPreferences(SESSION_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putString("access_token", sessionToken)
            .putString("refresh_token", sessionRefreshToken)
            .putString("user_id", authenticatedUserId)
            .putString("email", authenticatedEmail)
            .putString("display_name", authenticatedDisplayName)
            .apply()
    }

    private fun startPassVerification() {
        val userId = authenticatedUserId
        if (sessionToken.isNullOrBlank() || userId.isNullOrBlank()) {
            toast("먼저 로그인하세요.")
            return
        }
        if (BuildConfig.PORTONE_STORE_ID.isBlank() || BuildConfig.PORTONE_CHANNEL_KEY.isBlank()) {
            toast("PortOne Store ID와 PASS 채널 키를 local.properties에 설정하세요.")
            return
        }
        val identityVerificationId = "shareguard-$userId-${UUID.randomUUID()}"
        runCatching {
            PortOne.requestIdentityVerification(
                this,
                request = IdentityVerificationRequest(
                    storeId = BuildConfig.PORTONE_STORE_ID,
                    identityVerificationId = identityVerificationId,
                    channelKey = BuildConfig.PORTONE_CHANNEL_KEY
                ),
                resultLauncher = identityVerificationLauncher
            )
        }.onFailure { error ->
            toast(error.message ?: "PASS 본인인증 화면을 열지 못했습니다.")
        }
    }

    private fun verifyPassOnServer(identityVerificationId: String) {
        val token = sessionToken ?: return
        toast("PASS 인증 결과를 서버에서 확인 중입니다.")
        Thread {
            val result = runCatching {
                val verification = supabase.verifyPass(
                    identityVerificationId,
                    token,
                    BuildConfig.PASS_VERIFY_FUNCTION
                )
                val profile = if (verification.verified) supabase.fetchProfile(token) else null
                verification to profile
            }
            runOnUiThread {
                result.onSuccess { (verification, profile) ->
                    currentUser().verified = profile?.verified == true
                    toast(verification.message)
                    render()
                }.onFailure { error ->
                    toast(error.message ?: "PASS 인증 결과 확인에 실패했습니다.")
                }
            }
        }.start()
    }

    private fun signOut() {
        val token = sessionToken
        clearLocalSession()
        render()
        if (token != null) {
            Thread { runCatching { supabase.signOut(token) } }.start()
        }
    }

    private fun clearLocalSession() {
        sessionToken = null
        sessionRefreshToken = null
        authenticatedUserId = null
        authenticatedEmail = null
        authenticatedDisplayName = null
        currentUser().verified = false
        getSharedPreferences(SESSION_PREFERENCES, MODE_PRIVATE).edit().clear().apply()
    }

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
                    if (remoteItems.isNotEmpty() && screen == Screen.HOME) render()
                }.onFailure { error ->
                    toast("DB 조회 실패: ${error.message ?: "연결을 확인하세요."}")
                }
            }
        }.start()
    }

    private fun saveItem(item: Item) {
        if (!supabase.isConfigured) {
            items.add(0, item)
            screen = Screen.HOME
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
                    screen = Screen.HOME
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
        remoteId = item.id
    )

    private fun createReservation(item: Item) {
        if (!currentUser().verified) {
            toast("본인 인증 후 예약할 수 있습니다.")
            return
        }
        val total = item.price * 3 + item.deposit
        reservations.add(Reservation(nextReservationId++, item.id, currentUser().id, "2026-05-01", "2026-05-03", total))
        screen = Screen.RESERVATIONS
        toast("예약 신청이 완료되었습니다.")
        render()
    }

    private fun approveReservation(reservation: Reservation) {
        reservation.approved = true
        contracts.add(Contract(nextContractId++, reservation.id, ContractStatus.APPROVED))
        toast("예약이 승인되었습니다.")
        render()
    }

    private fun pay(contract: Contract) {
        if (!currentUser().verified) {
            toast("본인 인증 후 결제할 수 있습니다.")
            return
        }
        contract.status = ContractStatus.PAID
        toast("에스크로 결제가 완료되었습니다.")
        render()
    }

    private fun sign(contract: Contract) {
        contract.status = ContractStatus.SIGNED
        screen = Screen.QR
        toast("전자 계약서 서명이 완료되었습니다.")
        render()
    }

    private fun createReport(contract: Contract) {
        contract.status = ContractStatus.DISPUTED
        contract.refundHeld = true
        reports.add(Report(nextReportId++, contract.id, "파손 의심", "반납 후 사진에서 렌즈 가장자리 스크래치가 확인됨"))
        screen = Screen.ADMIN
        toast("신고가 접수되고 환급이 보류되었습니다.")
        render()
    }

    private fun resetDemo() {
        screen = Screen.HOME
        selectedItemId = 501
        users[1].verified = false
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
            Screen.HOME to "홈",
            Screen.REGISTER to "등록",
            Screen.RESERVATIONS to "예약",
            Screen.CHAT to "채팅",
            Screen.QR to "QR"
        )
        entries.forEach { (target, label) ->
            bottomNav.addView(Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setTextColor(if (screen == target) blue else muted)
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(if (screen == target) Color.rgb(234, 241, 255) else Color.TRANSPARENT, 8)
                setOnClickListener {
                    screen = target
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

    private fun statusChip(label: String): TextView = textView(label, 12, true, blue).apply {
        gravity = Gravity.CENTER
        setPadding(dp(9), dp(5), dp(9), dp(5))
        background = rounded(Color.rgb(234, 241, 255), 8, Color.rgb(201, 219, 255))
    }

    private fun metric(label: String, value: String, valueColor: Int = text): LinearLayout = row {
        addView(textView(label, 14, false, muted), LinearLayout.LayoutParams(0, wrap, 1f))
        addView(textView(value, 14, true, valueColor))
    }.withTop(8)

    private fun field(label: String, view: View): LinearLayout = column {
        addView(textView(label, 13, true, muted))
        addView(view.withTop(5))
    }.withTop(10)

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
