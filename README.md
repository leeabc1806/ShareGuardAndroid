# ShareGuard MVP

`ShareGuard_통합문서.pdf`의 SRS/SDD/API 명세를 기준으로 만든 실행 가능한 웹 프로토타입입니다. 별도 서버 없이 [index.html](./index.html)을 브라우저에서 열면 동작합니다.

Android 네이티브 MVP는 [ShareGuardAndroid](./ShareGuardAndroid) 폴더에 추가했습니다. Kotlin + Android 기본 View로 구현되어 외부 UI 라이브러리 없이 빌드됩니다. 민감한 결제·QR·관리자 처리를 담당하는 [Spring Boot 백엔드](./backend/README.md)도 포함합니다.

## 팀원 빠른 시작

처음 참여하는 팀원은 [팀 협업 가이드](./CONTRIBUTING.md)를 순서대로 따라 하면 됩니다. GitHub 초대 승인, 프로젝트 복제, Android Studio 실행, Supabase 연결, 브랜치 생성과 Pull Request 방법까지 설명되어 있습니다.

저장소를 복제한 뒤 Android Studio에서는 저장소 루트가 아니라 내부의 `ShareGuardAndroid` 폴더를 엽니다.

```text
복제한 폴더/
├─ README.md
├─ CONTRIBUTING.md
├─ index.html
├─ backend/              <- Spring Boot 서버
└─ ShareGuardAndroid/    <- Android Studio에서 이 폴더 열기
```

## 구현 범위

- `AUTH-03`: 본인 인증 완료/해제 상태와 미인증 사용자 거래 차단
- `ITEM-01`: 위치 기반 물품 목록, 실제 카카오맵 마커, 카테고리 필터, 거리/가격/신뢰 정렬
- `ITEM-02`: 물품 등록 폼, 카카오맵 거래 위치 선택과 내 물품 목록
- `RENT-03`: 예약 신청, 승인 대기, 대여자 승인 흐름
- `RENT-01`: 에스크로 결제 상태 전환
- `FR-06`: 전자 계약 생성 및 서명 상태
- `RENT-04`: 대여 전/후 상태 사진 아카이브
- `RENT-02`: QR START/END 인증과 계약 상태 전환
- `FR-08`: 거래별 채팅방과 메시지 전송
- `FR-09`: 반납 완료 후 보증금 환급 상태
- `FR-13`: 거래 후기와 신뢰 지수 반영
- 관리자: 분쟁 신고, 환급 보류, 일부 차감 후 종결

## 외부 API 없이 추가 구현한 기능

1. Android 위치 권한과 GPS/네트워크 위치를 사용한 실제 거리 계산 및 반경 필터
2. 실제 QR 생성·카메라 스캔, 5분 만료, 단계/계약 검증, 무결성 서명과 재사용 차단
3. 물품 사진 최대 5장 선택·미리보기·삭제, 1920px JPEG 압축과 Supabase Storage 업로드
4. 예약 시작일/종료일 달력, 과거·역전 날짜 검증, 중복 예약 차단과 금액 자동 계산
5. 손가락 전자 서명, 계약 PDF 생성·보관·공유
6. 인증·거래·평점·무사고·분쟁 기반 신뢰 점수와 10/20/30% 차등 보증금
7. 5초 채팅 자동 갱신, 메시지 전송 시각과 읽음 시각 저장
8. 관리자 분쟁 결정 이력, 일부 차감, 전액 환급, 게시글 숨김, 사용자 경고·정지

기존 Supabase 프로젝트에는 최신 [schema.sql](./ShareGuardAndroid/database/schema.sql)을 SQL Editor에서 다시 적용해야 추가 테이블, 함수와 트리거가 생성됩니다. 운영 데이터가 있는 프로젝트에서는 실행 전에 백업하고 변경 구문을 검토하세요.

## 데모 흐름

1. 로그인 후 `PASS 본인인증 시작`을 눌러 포트원 테스트 인증을 완료합니다.
2. 홈에서 물품을 선택하고 `예약 신청`을 누릅니다.
3. 예약 화면에서 `예약 승인` -> `에스크로 결제` -> `전자 계약 서명`을 차례로 누릅니다.
4. QR 화면에서 대여 전 사진 업로드, `START 인증`, 반납 후 사진 업로드, `END 인증`을 진행합니다.
5. 완료 후 후기 작성 또는 분쟁 신고/관리자 처리를 확인합니다.

상태는 `localStorage`에 저장됩니다. 처음 상태로 되돌리려면 앱 우측 상단의 `데모 초기화`를 누르면 됩니다.

## Android 앱 실행

이 프로젝트의 Gradle JDK는 Java 17을 사용합니다. Android Studio의 `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`에서 JDK 17을 선택합니다.

```powershell
cd ShareGuardAndroid
.\gradlew.bat assembleDebug
```

빌드 결과 APK:

```text
ShareGuardAndroid/app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서는 `ShareGuardAndroid` 폴더를 열고 `app` 구성을 실행하면 됩니다. 로그인과 PASS 본인인증을 마친 뒤 홈의 물품 예약, 예약 승인, 에스크로 결제, 전자 계약, QR 인증, 반납 환급 흐름을 시연할 수 있습니다.

## PASS 휴대폰 본인인증 (PortOne V2)

현재 구현은 포트원 V2 본인인증을 사용합니다. V2의 서버 검증 식별자는 V1의 `imp_uid`가 아니라 `identityVerificationId`입니다. 백엔드는 이전 앱 마이그레이션을 위해 `imp_uid` JSON 별칭도 받지만, 이를 V2 조회 API의 식별자로만 사용하며 클라이언트 콜백 자체를 인증 성공으로 신뢰하지 않습니다.

포트원 콘솔에서 테스트 스토어와 본인인증 채널을 만든 뒤 Android의 `local.properties`에 다음 값을 넣습니다.

```properties
SHAREGUARD_API_BASE_URL=http://10.0.2.2:8080/
PORTONE_STORE_ID=store-...
PORTONE_IDENTITY_CHANNEL_KEY=channel-key-...
```

백엔드는 Android에 넣으면 안 되는 API Secret을 환경변수로 받습니다.

```powershell
$env:PORTONE_API_SECRET='PortOne V2 API Secret'
$env:PORTONE_STORE_ID='store-...'
$env:MFA_TRUST_BONUS='150'
```

인증 흐름은 `Android WebView -> PASS 앱 -> shareguard://mfa-complete -> POST /api/v1/auth/mfa/verify -> PortOne V2 서버 조회 -> profiles 업데이트` 순서입니다. 인증 원문 CI/DI와 전체 전화번호는 저장하지 않고, 계정 중복 가입 방지용 SHA-256 지문과 전화번호 마지막 4자리만 `private.mfa_verifications`에 보관합니다.

에뮬레이터에는 실제 PASS 앱을 정상 설치·가입하기 어려우므로 WebView와 서버 오류 흐름까지만 확인하고, PASS 앱 전환과 최종 인증은 통신사 SIM이 있는 실제 Android 기기에서 테스트하는 것이 안전합니다. 로컬 PC 백엔드는 에뮬레이터에서 `10.0.2.2`, 실제 기기에서는 PC의 같은 Wi-Fi 사설 IP 또는 HTTPS 개발 서버 주소를 사용합니다.

기존 Supabase 프로젝트에는 다음 중 하나로 DB 변경을 적용해야 합니다.

- Spring Boot가 같은 PostgreSQL에 연결된 경우 서버 시작 시 Flyway `V2__portone_mfa.sql` 적용
- Supabase SQL Editor에서 최신 `ShareGuardAndroid/database/schema.sql` 검토 후 적용

물품 등록은 Android가 Supabase Data API에 직접 쓰므로 `items_insert_own` RLS도 MFA를 검사합니다. 결제 API는 Spring Security 필터가, 데모 결제 RPC는 PostgreSQL 함수와 RLS가 각각 미인증 사용자를 차단합니다. 앱은 `MFA_REQUIRED`, HTTP 403 또는 RLS 권한 오류를 받으면 PASS 인증 화면으로 유도합니다.

## 카카오맵 Android SDK 설정

카카오맵 SDK v2를 사용하며 다음 기능이 연결되어 있습니다.

- 지도 탭에서 좌표가 저장된 물품을 실제 카카오맵 마커로 표시
- 물품 마커를 누르면 해당 물품 상세 화면으로 이동
- `내 위치로 이동`을 눌러 지도 중심과 내 위치 마커 갱신
- 물품 등록 시 지도 중앙점 또는 현재 위치를 거래 좌표로 선택
- 선택한 `latitude`, `longitude`를 Supabase `items` 테이블에 저장

공식 문서:

- [Kakao Maps API](https://apis.map.kakao.com/)
- [Android SDK v2 시작하기](https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/)
- [Android 패키지명과 키 해시 설정](https://developers.kakao.com/docs/ko/android/getting-started)

### 1. 앱과 Android 플랫폼 등록

1. [Kakao Developers](https://developers.kakao.com/)에 로그인하여 앱을 생성합니다.
2. 앱 관리 화면의 `카카오맵 > 사용 설정`에서 상태를 `ON`으로 변경합니다.
3. `앱 > 플랫폼 키 > 네이티브 앱 키`에서 Android 앱 정보를 등록합니다.
4. 패키지명은 `com.shareguard.app`을 사용합니다.

지도 초기화에는 JavaScript 키나 REST API 키가 아닌 `네이티브 앱 키`를 사용합니다. 어드민 키는 Android 앱에 넣지 않습니다.

### 2. 키 해시 등록

Windows에서 현재 개발자의 디버그 키 해시를 구해 네이티브 앱 키의 Android 앱 정보에 등록합니다.

```powershell
keytool -exportcert -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -keypass android | openssl sha1 -binary | openssl base64
```

팀원마다 디버그 키스토어가 다를 수 있으므로 각 개발자의 키 해시를 등록합니다. Play Store 배포 시에는 릴리즈 서명 키 해시도 별도로 등록해야 합니다.

### 3. 로컬 앱 키 설정

`ShareGuardAndroid/local.properties`에 발급받은 네이티브 앱 키를 추가합니다.

```properties
KAKAO_NATIVE_APP_KEY=your_kakao_native_app_key
```

`local.properties`는 Git에서 제외됩니다. 실제 앱 키를 README, Kotlin 소스 또는 GitHub에 직접 기록하지 않습니다. 값을 추가한 뒤 Gradle Sync 또는 Debug 빌드를 다시 실행합니다.

### 4. 현재 위치 권한

`내 위치로 이동`을 처음 누르면 Android 위치 권한 창이 표시됩니다. `앱 사용 중에만 허용`을 선택하면 되며 정확한 위치와 대략적인 위치를 모두 지원합니다.

- 위치는 버튼을 누른 시점에 한 번만 조회하며 백그라운드 위치 권한은 사용하지 않습니다.
- 휴대폰의 위치 기능이 꺼져 있으면 기능을 켠 뒤 다시 시도합니다.
- 권한을 다시 설정하려면 `설정 > 애플리케이션 > ShareGuard > 권한 > 위치`에서 변경합니다.

### 문제 해결

- `401`: 네이티브 앱 키, `com.shareguard.app` 패키지명과 현재 서명 키 해시가 일치하는지 확인합니다.
- `403`: 앱 관리 화면의 `카카오맵 > 사용 설정` 상태가 `ON`인지 확인합니다.
- 지도가 비어 있음: 인터넷 연결을 확인하고 Logcat에서 `k3f`를 필터로 인증 로그를 확인합니다.
- 내 위치를 찾지 못함: 앱 위치 권한과 휴대폰 위치 기능(GPS)이 켜져 있는지 확인합니다.
- 물품 마커가 없음: 해당 `items` 행의 `latitude`, `longitude` 값이 `null`인지 확인합니다.

## Supabase 데이터베이스 연결

DB 스키마와 설정 순서는 [ShareGuardAndroid/database/README.md](./ShareGuardAndroid/database/README.md)에 있습니다. Supabase 프로젝트를 만들고 [schema.sql](./ShareGuardAndroid/database/schema.sql)을 실행한 뒤 `ShareGuardAndroid/local.properties`에 Project URL과 Publishable key를 추가하면 됩니다.

현재 Supabase 프로젝트를 함께 사용하는 팀원은 스키마를 다시 실행하지 않아도 됩니다. 각자 `local.properties`만 설정하면 됩니다.

## 토스페이먼츠 테스트 결제

Android 앱의 예약 화면에서 `에스크로 결제`를 누르면 토스페이먼츠 결제위젯이 열립니다. `ShareGuardAndroid/local.properties`에 `TOSS_CLIENT_KEY`를 넣으면 해당 클라이언트 키를 사용하고, 값이 없으면 토스페이먼츠 문서용 테스트 클라이언트 키로 결제 인증만 테스트합니다.

실제 결제 완료 처리는 서버가 필요합니다. `TOSS_CONFIRM_URL`에는 `paymentKey`, `orderId`, `amount`를 받아 토스페이먼츠 결제 승인 API를 호출하는 백엔드 주소를 넣습니다. 토스 시크릿 키는 Android 앱에 넣지 않습니다.

### 필요한 키와 설정

토스페이먼츠 개발자센터의 [API 키](https://docs.tosspayments.com/reference/using-api/api-keys) 메뉴에서 테스트 키를 확인합니다. 토스 문서 기준으로 클라이언트 키와 시크릿 키는 같은 세트로 사용해야 하며, 테스트 키와 라이브 키를 섞으면 `INVALID_API_KEY` 오류가 발생합니다.

Android 앱에는 클라이언트 키와 결제 승인 서버 주소만 넣습니다.

```properties
# ShareGuardAndroid/local.properties
TOSS_CLIENT_KEY=test_gck_...
TOSS_CONFIRM_URL=http://10.0.2.2:8080/api/v1/payments/toss/confirm
```

에뮬레이터에서 PC의 로컬 서버로 접근할 때는 `localhost` 대신 `10.0.2.2`를 사용합니다. 실제 배포 환경에서는 HTTPS 백엔드 주소를 넣습니다.

서버에는 시크릿 키를 환경변수로 넣습니다. 이 값은 Android 앱, GitHub, README 예시 커밋에 넣지 않습니다.

```env
TOSS_SECRET_KEY=test_gsk_...
```

서버는 앱에서 받은 `paymentKey`, `orderId`, `amount`를 검증한 뒤 토스페이먼츠 [결제 승인 API](https://docs.tosspayments.com/reference)를 호출합니다. 토스페이먼츠 [인증 가이드](https://docs.tosspayments.com/reference/using-api/authorization)에 따라 시크릿 키 뒤에 `:`를 붙여 Base64 인코딩한 값을 `Authorization: Basic ...` 헤더로 보냅니다.

정리하면 지금 필요한 값은 다음과 같습니다.

- Android 앱: `TOSS_CLIENT_KEY`
- Android 앱: `TOSS_CONFIRM_URL`
- 백엔드 서버: `TOSS_SECRET_KEY`

### 프론트엔드와 백엔드 역할

토스페이먼츠 결제는 앱에서 결제수단 인증을 받고, 서버에서 최종 승인을 하는 구조입니다. 토스 공식 문서에서도 결제 승인 API는 `paymentKey`, `orderId`, `amount`를 받아 `POST /v1/payments/confirm`으로 호출하며, 시크릿 키 기반 Basic 인증을 사용합니다.

| 영역 | 담당 | 사용하면 안 되는 값 |
| --- | --- | --- |
| Android 앱 | 결제위젯 표시, 주문번호 생성, 결제 인증 결과 수신, 백엔드 승인 URL 호출 | `TOSS_SECRET_KEY` |
| 백엔드 서버 | 주문 금액 검증, 토스 결제 승인 API 호출, 결제 결과 저장, 실패/중복 요청 처리 | 없음 |
| 토스페이먼츠 | 결제수단 인증, 승인 API 결과 반환, 결제 상태 관리 | 없음 |

결제 흐름:

```text
1. Android 앱이 예약 금액으로 토스 결제위젯을 엽니다.
2. 사용자가 결제수단을 선택하고 인증을 마칩니다.
3. 앱이 paymentKey, orderId, amount를 받습니다.
4. 앱이 백엔드의 TOSS_CONFIRM_URL로 reservationId, paymentKey, orderId, amount와 Supabase access token을 보냅니다.
5. 백엔드는 DB의 예약 금액과 amount가 같은지 검증합니다.
6. 백엔드는 TOSS_SECRET_KEY로 토스 결제 승인 API를 호출합니다.
7. 승인 성공 시 백엔드는 결제 완료 상태를 저장하고 앱에 성공 응답을 줍니다.
8. 앱은 계약 상태를 결제 완료로 바꿉니다.
```

현재 Android 쪽 코드 위치:

```text
ShareGuardAndroid/app/build.gradle.kts
  - 토스페이먼츠 SDK 의존성

ShareGuardAndroid/app/src/main/java/com/shareguard/app/MainActivity.kt
  - 에스크로 결제 버튼
  - orderId 생성
  - TossPaymentActivity 실행
  - TOSS_CONFIRM_URL 호출

ShareGuardAndroid/app/src/main/java/com/shareguard/app/TossPaymentActivity.kt
  - 토스 결제위젯 표시
  - paymentKey, orderId, amount 수신

ShareGuardAndroid/app/src/main/java/com/shareguard/app/data/TossConfirmClient.kt
  - 앱에서 백엔드 승인 URL로 POST 요청
```

Android 앱 설정 예시:

```properties
# ShareGuardAndroid/local.properties
TOSS_CLIENT_KEY=test_gck_...
TOSS_CONFIRM_URL=http://10.0.2.2:8080/api/v1/payments/toss/confirm
```

Android에서 백엔드로 보내는 JSON 형식:

```json
{
  "reservationId": "00000000-0000-0000-0000-000000000000",
  "paymentKey": "토스가 반환한 결제 키",
  "orderId": "shareguard-1-1720000000000",
  "amount": 350000
}
```

백엔드가 처리해야 하는 최소 로직:

```text
1. 요청의 orderId로 우리 DB의 예약/주문을 조회합니다.
2. DB에 저장된 결제 예정 금액과 요청의 amount가 같은지 확인합니다.
3. 이미 승인된 orderId인지 확인해서 중복 승인을 막습니다.
4. TOSS_SECRET_KEY로 Basic 인증 헤더를 만듭니다.
5. 토스페이먼츠 POST /v1/payments/confirm을 호출합니다.
6. 성공 응답의 paymentKey, orderId, totalAmount, status를 DB에 저장합니다.
```

Node.js 백엔드 예시:

```js
import express from "express";

const app = express();
app.use(express.json());

app.post("/payments/toss/confirm", async (req, res) => {
  const { paymentKey, orderId, amount } = req.body;

  // TODO: 실제 DB에서 orderId로 예약/주문을 조회하세요.
  const order = await findOrderByOrderId(orderId);
  if (!order) {
    return res.status(404).json({ message: "주문을 찾을 수 없습니다." });
  }

  if (order.amount !== amount) {
    return res.status(400).json({ message: "결제 금액이 일치하지 않습니다." });
  }

  if (order.paymentStatus === "DONE") {
    return res.status(409).json({ message: "이미 승인된 결제입니다." });
  }

  const secretKey = process.env.TOSS_SECRET_KEY;
  const encodedSecret = Buffer.from(`${secretKey}:`).toString("base64");

  const tossResponse = await fetch("https://api.tosspayments.com/v1/payments/confirm", {
    method: "POST",
    headers: {
      Authorization: `Basic ${encodedSecret}`,
      "Content-Type": "application/json",
      "Idempotency-Key": orderId
    },
    body: JSON.stringify({ paymentKey, orderId, amount })
  });

  const payment = await tossResponse.json();
  if (!tossResponse.ok) {
    return res.status(tossResponse.status).json(payment);
  }

  // TODO: payment.status, payment.paymentKey, payment.totalAmount 등을 DB에 저장하세요.
  await markOrderPaid(orderId, payment);

  return res.json(payment);
});
```

위 예시는 구조 설명용입니다. 실제 백엔드에서는 `findOrderByOrderId`, `markOrderPaid`를 프로젝트 DB 코드로 구현해야 합니다. 결제 승인 요청에는 중복 요청 방지를 위해 `Idempotency-Key`를 함께 보내는 것을 권장합니다.
