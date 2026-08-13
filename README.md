# ShareGuard MVP

`ShareGuard_통합문서.pdf`의 SRS/SDD/API 명세를 기준으로 만든 실행 가능한 웹 프로토타입입니다. 별도 서버 없이 [index.html](./index.html)을 브라우저에서 열면 동작합니다.

Android 네이티브 MVP는 [ShareGuardAndroid](./ShareGuardAndroid) 폴더에 추가했습니다. Kotlin + Android 기본 View로 구현되어 외부 UI 라이브러리 없이 빌드됩니다. Supabase 설정을 추가하면 이메일 로그인과 PostgreSQL 물품 조회/등록이 실제 서버와 연동됩니다.

## 팀원 빠른 시작

처음 참여하는 팀원은 [팀 협업 가이드](./CONTRIBUTING.md)를 순서대로 따라 하면 됩니다. GitHub 초대 승인, 프로젝트 복제, Android Studio 실행, Supabase 연결, 브랜치 생성과 Pull Request 방법까지 설명되어 있습니다.

저장소를 복제한 뒤 Android Studio에서는 저장소 루트가 아니라 내부의 `ShareGuardAndroid` 폴더를 엽니다.

```text
복제한 폴더/
├─ README.md
├─ CONTRIBUTING.md
├─ index.html
└─ ShareGuardAndroid/    <- Android Studio에서 이 폴더 열기
```

## 구현 범위

- `AUTH-03`: 본인 인증 완료/해제 상태와 미인증 사용자 거래 차단
- `ITEM-01`: 위치 기반 물품 목록, 카테고리 필터, 거리/가격/신뢰 정렬
- `ITEM-02`: 물품 등록 폼과 내 물품 목록
- `RENT-03`: 예약 신청, 승인 대기, 대여자 승인 흐름
- `RENT-01`: 에스크로 결제 상태 전환
- `FR-06`: 전자 계약 생성 및 서명 상태
- `RENT-04`: 대여 전/후 상태 사진 아카이브
- `RENT-02`: QR START/END 인증과 계약 상태 전환
- `FR-08`: 거래별 채팅방과 메시지 전송
- `FR-09`: 반납 완료 후 보증금 환급 상태
- `FR-13`: 거래 후기와 신뢰 지수 반영
- 관리자: 분쟁 신고, 환급 보류, 일부 차감 후 종결

## 데모 흐름

1. 상단 알림에서 `본인 인증 완료`를 누릅니다.
2. 홈에서 물품을 선택하고 `예약 신청`을 누릅니다.
3. 예약 화면에서 `예약 승인` -> `에스크로 결제` -> `전자 계약 서명`을 차례로 누릅니다.
4. QR 화면에서 대여 전 사진 업로드, `START 인증`, 반납 후 사진 업로드, `END 인증`을 진행합니다.
5. 완료 후 후기 작성 또는 분쟁 신고/관리자 처리를 확인합니다.

상태는 `localStorage`에 저장됩니다. 처음 상태로 되돌리려면 앱 우측 상단의 `데모 초기화`를 누르면 됩니다.

## Android 앱 실행

```powershell
cd ShareGuardAndroid
.\gradlew.bat assembleDebug
```

빌드 결과 APK:

```text
ShareGuardAndroid/app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서는 `ShareGuardAndroid` 폴더를 열고 `app` 구성을 실행하면 됩니다. 앱 안에서 `본인 인증 완료`를 누른 뒤 홈의 물품 예약, 예약 승인, 에스크로 결제, 전자 계약, QR 인증, 반납 환급 흐름을 시연할 수 있습니다.

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
TOSS_CONFIRM_URL=http://10.0.2.2:8080/payments/toss/confirm
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
4. 앱이 백엔드의 TOSS_CONFIRM_URL로 paymentKey, orderId, amount를 보냅니다.
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
TOSS_CONFIRM_URL=http://10.0.2.2:8080/payments/toss/confirm
```

Android에서 백엔드로 보내는 JSON 형식:

```json
{
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
