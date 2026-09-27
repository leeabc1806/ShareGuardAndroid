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
$env:PORTONE_API_SECRET='PortOne V2 API Secret'
$env:PORTONE_STORE_ID='store-...'
$env:MFA_TRUST_BONUS='150'
$env:TOSS_SECRET_KEY='test_gsk_...'
$env:TOSS_WEBHOOK_PATH_TOKEN='32자-이상의-무작위-비밀값'
.\gradlew.bat bootRun
```

비밀번호와 시크릿 키는 `application.yml`, `local.properties`, Git에 저장하지 않습니다. Flyway가 시작 시 `private.payment_orders`, `private.qr_tokens`, `private.mfa_verifications`, 웹훅/감사 로그 테이블을 생성합니다. 해당 `private` 테이블은 Supabase Data API 역할(`anon`, `authenticated`)에 공개하지 않습니다.

Android 실기기 디버그 설정(PC의 실제 LAN IP로 변경):

```properties
TOSS_CLIENT_KEY=test_gck_...
TOSS_CONFIRM_URL=
SHAREGUARD_API_BASE_URL=http://192.168.0.12:8080/
PORTONE_STORE_ID=store-...
PORTONE_IDENTITY_CHANNEL_KEY=channel-key-...
```

## API

웹훅을 제외한 모든 요청은 `Authorization: Bearer <Supabase access token>`이 필요합니다.

| 메서드/경로 | 역할 |
| --- | --- |
| `POST /api/v1/auth/mfa/verify` | 포트원 V2 본인인증 서버 검증, 인증 상태와 신뢰 점수 반영 |
| `POST /api/v1/payments/toss/confirm` | 예약 금액 검증 후 토스 결제 승인 |
| `GET /api/v1/payments/{reservationId}` | 실제 결제·누적 환급·남은 환급 가능 금액 조회 |
| `POST /api/v1/payments/{reservationId}/refund` | 반납 및 분쟁 처리 완료 후 실제 보증금 환급 |
| `POST /api/v1/qr/issue` | 5분 유효 1회용 START/END QR 발급 |
| `POST /api/v1/qr/verify` | QR 검증 및 계약/예약 상태 원자적 변경 |
| `POST /api/v1/admin/reports/{reportId}/resolve` | 관리자 분쟁 결정·제재·감사 로그 |
| `POST /api/v1/webhooks/toss/{pathToken}` | 토스 상태 재조회 기반 웹훅 동기화 |
| `GET /actuator/health` | 헬스 체크 |

`TOSS_CONFIRM_URL`을 비워 두면 앱 빌드 시 기본 서버 주소에서 자동 생성합니다. QR 검증 요청은 `payload` 외에 선택한 `contractId`, `action`을 함께 보내야 합니다. [실기기·마이그레이션 점검 안내](../docs/교수님_지도사항_반영.md)에 전체 실행 절차를 정리했습니다.

본인인증 요청 예시:

```json
{
  "identityVerificationId": "identity-verification-앱에서-생성한-고유값"
}
```

포트원 V2에서는 V1의 `imp_uid` 대신 `identityVerificationId`를 사용합니다. 호환을 위해 `imp_uid` 별칭도 역직렬화하지만, 서버는 어떤 경우에도 앱의 성공 콜백만 신뢰하지 않고 `GET https://api.portone.io/identity-verifications/{identityVerificationId}` 결과가 `VERIFIED`인지 확인합니다. 성공 시 `profiles.verified`, `verified_at`, `verification_provider`, `trust_score`를 한 트랜잭션에서 변경합니다. 기본 인증 가산점은 기존 신뢰 지수 규칙과 같은 150점이며 `MFA_TRUST_BONUS`로 조정할 수 있습니다.

`POST /api/v1/items`와 `POST /api/v1/payments/**`는 인증 전이면 다음과 같이 `403 Forbidden`을 반환합니다.

```json
{
  "code": "MFA_REQUIRED",
  "message": "PASS 휴대폰 본인인증 후 이용할 수 있습니다."
}
```

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
