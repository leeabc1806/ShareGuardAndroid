# ShareGuard Spring Boot backend

Spring Boot 4.1.1 / Java 17 기반의 보안 경계입니다. Android 앱은 일반 CRUD를 Supabase Data API로 처리하고, 이 서버는 토스 결제 승인·환급, 결제 웹훅, 1회용 QR 상태 전환, 관리자 분쟁 결정을 담당합니다.

## 실행 전 준비

먼저 `ShareGuardAndroid/database/schema.sql`을 Supabase SQL Editor에 적용합니다. 이후 Supabase Dashboard의 **Connect > Session pooler** 연결값을 사용합니다. Hibernate/JDBC처럼 prepared statement를 사용하는 장기 실행 서버는 transaction pooler가 아닌 Session mode(5432)가 알맞습니다.

PowerShell 예시:

```powershell
$env:SUPABASE_DB_URL='jdbc:postgresql://aws-0-ap-northeast-2.pooler.supabase.com:5432/postgres?sslmode=require'
$env:SUPABASE_DB_USER='postgres.PROJECT_REF'
$env:SUPABASE_DB_PASSWORD='your-database-password'
$env:SUPABASE_JWKS_URI='https://PROJECT_REF.supabase.co/auth/v1/.well-known/jwks.json'
$env:TOSS_SECRET_KEY='test_gsk_...'
$env:TOSS_WEBHOOK_PATH_TOKEN='32자-이상의-무작위-비밀값'
.\gradlew.bat bootRun
```

비밀번호와 시크릿 키는 `application.yml`, `local.properties`, Git에 저장하지 않습니다. Flyway가 시작 시 `private.payment_orders`, `private.qr_tokens`, 웹훅/감사 로그 테이블을 생성합니다. 해당 `private` 테이블은 Supabase Data API 역할(`anon`, `authenticated`)에 공개하지 않습니다.

Android 에뮬레이터 설정:

```properties
TOSS_CLIENT_KEY=test_gck_...
TOSS_CONFIRM_URL=http://10.0.2.2:8080/api/v1/payments/toss/confirm
```

## API

웹훅을 제외한 모든 요청은 `Authorization: Bearer <Supabase access token>`이 필요합니다.

| 메서드/경로 | 역할 |
| --- | --- |
| `POST /api/v1/payments/toss/confirm` | 예약 금액 검증 후 토스 결제 승인 |
| `POST /api/v1/payments/{reservationId}/refund` | 완료/분쟁 거래의 실제 보증금 환급 |
| `POST /api/v1/qr/issue` | 5분 유효 1회용 START/END QR 발급 |
| `POST /api/v1/qr/verify` | QR 검증 및 계약/예약 상태 원자적 변경 |
| `POST /api/v1/admin/reports/{reportId}/resolve` | 관리자 분쟁 결정·제재·감사 로그 |
| `POST /api/v1/webhooks/toss/{pathToken}` | 토스 상태 재조회 기반 웹훅 동기화 |
| `GET /actuator/health` | 헬스 체크 |

결제 승인 요청 예시:

```json
{
  "reservationId": "00000000-0000-0000-0000-000000000000",
  "paymentKey": "토스가 반환한 결제 키",
  "orderId": "shareguard-order-1720000000000",
  "amount": 350000
}
```

관리자 분쟁 결정 응답의 `refundableDeposit`이 0보다 크면, 같은 관리자 토큰으로 환급 API를 호출해야 실제 결제가 환급됩니다. 분쟁 결정만으로 결제 상태를 가짜 `refunded`로 바꾸지는 않습니다.

## 검증

```powershell
.\gradlew.bat test bootJar
```

실행 JAR은 `build/libs/shareguard-backend-1.0.0.jar`에 생성됩니다. 실제 Supabase/토스 통합 검증은 위 환경변수와 테스트 결제 키가 설정된 환경에서 수행해야 합니다.
