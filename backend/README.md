# ShareGuard Spring Boot 서버

Java 17 / Spring Boot 4.1.1 기반 서버입니다. Android는 Supabase Auth·Data API·Storage를 사용하고, 이 서버는 PortOne 본인인증 결과 검증, 토스 결제 승인·환급, 일회용 QR, 관리자 분쟁 결정을 담당합니다.

## 1. DB 준비

[DB 설정 안내](../ShareGuardAndroid/database/README.md)에 따라 **신규 DB에만** 기본 스키마를 설치합니다. 이미 설치된 팀 DB에는 초기 스키마를 다시 실행하지 않습니다. 서버 시작 시 [Flyway 마이그레이션](./src/main/resources/db/migration)의 V1~V3가 적용됩니다.

Supabase Dashboard의 **Connect**에서 실제 호스트·포트·사용자명을 복사합니다.

| 연결 | 이 프로젝트에서의 용도 |
| --- | --- |
| Direct connection / 5432 | 장기 실행 서버·마이그레이션. 접속 환경의 IPv6 지원 또는 IPv4 옵션 확인 |
| Session pooler / 5432 | IPv4 개발 PC에서 사용할 수 있는 대안. JDBC prepared statement 사용 가능 |
| Transaction pooler / 6543 | 이 안내의 기본 연결로 사용하지 않음. Prepared statement 등 별도 제약 확인 필요 |

호스트를 리전 이름으로 추측하지 않습니다. Direct와 Session pooler의 사용자명도 다릅니다. [Supabase 공식 DB 연결 안내](https://supabase.com/docs/guides/database/connecting-to-postgres)

## 2. 환경변수

서버를 실행할 **같은 PowerShell 터미널**에 다음 값을 설정합니다. 예시 문자열을 실제 프로젝트 값으로 바꿉니다.

```powershell
cd backend
$env:SUPABASE_DB_URL='jdbc:postgresql://YOUR_SESSION_POOLER_HOST:5432/postgres?sslmode=require'
$env:SUPABASE_DB_USER='postgres.YOUR_PROJECT_REF'
$env:SUPABASE_DB_PASSWORD='YOUR_DATABASE_PASSWORD'
$env:SUPABASE_JWKS_URI='https://YOUR_PROJECT_REF.supabase.co/auth/v1/.well-known/jwks.json'
$env:TOSS_SECRET_KEY='test_gsk_YOUR_SECRET_KEY'
$env:TOSS_WEBHOOK_PATH_TOKEN='YOUR_RANDOM_SECRET_PATH_TOKEN'
$env:PORTONE_API_SECRET='YOUR_PORTONE_V2_API_SECRET'
$env:PORTONE_STORE_ID='store-YOUR_STORE_ID'
```

위 예시는 Session pooler용입니다. Direct 연결이면 Dashboard의 Direct 호스트와 사용자명을 사용합니다. 웹훅 경로 토큰은 기본값 `change-me` 대신 충분히 긴 무작위 비밀값으로 설정합니다.

| 필수 변수 | 역할 |
| --- | --- |
| `SUPABASE_DB_URL`, `SUPABASE_DB_USER`, `SUPABASE_DB_PASSWORD` | 앱과 같은 Supabase 프로젝트의 PostgreSQL 연결 |
| `SUPABASE_JWKS_URI` | 로그인 토큰의 서명을 검증할 공개키 주소 |
| `TOSS_SECRET_KEY` | 앱 클라이언트 키와 같은 상점·테스트 환경의 서버 키 |
| `TOSS_WEBHOOK_PATH_TOKEN` | 토스 웹훅 경로의 비밀 토큰 |
| `PORTONE_API_SECRET`, `PORTONE_STORE_ID` | PortOne V2 본인인증 내역 조회 |

선택 설정과 현재 기본값은 [application.yml](./src/main/resources/application.yml)에 있습니다.

| 변수 | 기본값 |
| --- | --- |
| `SERVER_ADDRESS`, `PORT` | `0.0.0.0`, `8080` |
| `DB_POOL_MAX_SIZE`, `DB_POOL_MIN_IDLE` | `5`, `1` |
| `QR_TOKEN_TTL_SECONDS` | `300` |
| `MFA_TRUST_BONUS` | `150` |
| `FLYWAY_ENABLED` | `true` |

현재 실행 방식은 `.env` 파일을 자동으로 읽지 않습니다. 비밀값은 프로세스 환경변수 또는 배포 환경의 비밀 설정으로 공급합니다. Android `local.properties`, 소스, Git에 서버 비밀값을 저장하지 않습니다.

### JWT 서명 알고리즘

현재 서버는 JWKS 기반 Spring Security 자동 설정을 사용합니다. Supabase에서 사용하는 JWT 서명 알고리즘과 서버의 허용 알고리즘이 일치해야 합니다.

- Supabase의 비대칭 서명 키는 JWKS로 공개키를 제공합니다. 레거시 `HS256` 공유 비밀 방식은 현재 JWKS 설정만으로 검증할 수 없습니다. [Supabase 공식 서명 키 안내](https://supabase.com/docs/guides/auth/signing-keys)
- Spring의 기본 허용 알고리즘은 `RS256`입니다. 프로젝트 토큰이 `ES256`이면 실행 시 `spring.security.oauth2.resourceserver.jwt.jws-algorithms=ES256`을 지정합니다. [Spring Security 공식 JWT 안내](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)

`ES256` 프로젝트의 실행 예:

```powershell
.\gradlew.bat bootRun --args='--spring.security.oauth2.resourceserver.jwt.jws-algorithms=ES256'
```

기존 팀 프로젝트의 서명 키를 임의로 교체하지 말고 담당자에게 현재 알고리즘을 확인합니다. 위 명령은 아래 기본 `bootRun` 대신 사용합니다.

## 3. 실행과 확인

환경변수 설정을 마친 `backend` 폴더에서:

```powershell
.\tools\Check-BackendEnvironment.ps1
.\gradlew.bat bootRun
```

점검 스크립트는 필수 변수 존재 여부와 일부 기본값을 검사하며 비밀값은 출력하지 않습니다. DB 인증이나 외부 서비스 성공을 확인하는 도구는 아닙니다.

서버 시작 후 다른 터미널에서:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/health'
```

`status: UP`과 서버 로그의 Flyway 성공을 확인하고, 관리자 DB 연결에서 [verify-migrations.sql](./tools/verify-migrations.sql)을 실행합니다. `UP`은 토스·PortOne 인증 성공을 보장하지 않습니다.

현재 [Gradle 설정](./build.gradle.kts)은 `spring-boot-starter-flyway`와 PostgreSQL용 Flyway 모듈을 포함합니다. Spring은 시작 시 마이그레이션을 실행합니다. [Spring Boot 공식 DB 초기화 안내](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

휴대폰·에뮬레이터의 서버 주소는 [루트 README](../README.md)의 연결 표를 따릅니다. Android의 기본 서버 주소를 변경한 후에는 재빌드가 필요합니다.

## 4. API

`/actuator/health`와 토스 웹훅을 제외한 요청은 `Authorization: Bearer <Supabase access token>`이 필요합니다. Publishable key는 사용자 로그인 토큰을 대신할 수 없습니다.

| 메서드 / 경로 | 요청 또는 역할 |
| --- | --- |
| `POST /api/v1/auth/mfa/verify` | `identityVerificationId`로 본인인증 결과 검증 |
| `POST /api/v1/payments/toss/confirm` | `reservationId, paymentKey, orderId, amount`로 결제 승인 |
| `GET /api/v1/payments/{reservationId}` | `status, approvedAmount, refundedAmount, refundableAmount, refundAllowed` 조회 |
| `POST /api/v1/payments/{reservationId}/refund` | `amount, reason`으로 보증금 환급 요청 |
| `POST /api/v1/qr/issue` | `contractId, action`으로 일회용 QR 발급 |
| `POST /api/v1/qr/verify` | `payload, contractId, action`, 선택적 `latitude, longitude`로 QR 검증 |
| `POST /api/v1/admin/reports/{reportId}/resolve` | `decision, deductionAmount, sanction, note`로 분쟁 결정 |
| `POST /api/v1/webhooks/toss/{pathToken}` | 비밀 경로 토큰 확인 후 토스 상태 재조회·동기화 |
| `GET /actuator/health` | 공개 헬스 체크 |

QR `action` 값은 소문자 `start` 또는 `end`입니다. 새 검증 API에는 선택한 계약·단계가 필수이므로 **앱과 서버를 함께 업데이트**합니다. 발급자 자신의 스캔, 제3자, 만료, 재사용, 다른 계약·단계의 QR은 거부합니다.

결제 POST 요청은 본인인증도 필요합니다. 미인증이면 `403 / MFA_REQUIRED`를 반환합니다. 물품 등록은 Spring API가 아닌 Supabase Data API를 사용하며 DB의 RLS 정책이 인증 상태를 검사합니다.

### 본인인증

서버는 `identityVerificationId`로 PortOne V2 단건 조회를 수행하여 `VERIFIED`를 확인합니다. 앱 콜백만으로 인증 상태를 바꾸지 않습니다. [PortOne 공식 V2 본인인증 안내](https://developers.portone.io/opi/ko/extra/identity-verification/readme-v2)

성공 시 프로필 인증 상태와 신뢰 점수를 갱신합니다. `private.mfa_verifications`에는 인증 건 ID, 사용자 ID, 신원 정보의 해시와 전화번호 마지막 4자리 등을 저장합니다. CI/DI 원문과 전체 전화번호를 저장하지 않습니다. 앱 복귀 시 토큰 유실 문제는 [로그인·PASS 안내](../AUTH_SETUP.md)에 남은 작업으로 기록했습니다.

### 결제·환급·웹훅

승인은 DB의 예약 금액과 결제사 응답의 주문·금액·상태를 확인합니다. 환급은 반납 완료, 미해결 분쟁, 차감 결정, 누적 환급액과 남은 환급 한도를 재검사합니다.

- 반납 QR 성공으로 결제를 `refunded`로 바꾸지 않습니다.
- 환급 요청 후 처리 중은 `refund_pending`, 일부 환급은 `partially_refunded`, 보증금 전액 환급은 `refunded`로 구분합니다.
- 같은 환급 재시도에는 동일한 멱등 키를 사용합니다. 결제사에서 확인한 성공 취소 내역만 누적 환급액에 반영합니다. 토스 취소 내역의 성공 상태는 `cancelStatus = DONE`입니다. [토스 공식 코어 API](https://docs.tosspayments.com/reference)
- 관리자 분쟁 결정은 차감·제재·처리 결과를 기록합니다. 환급이 필요하면 거래 당사자 또는 관리자의 권한과 본인인증을 충족하는 별도 환급 요청이 필요합니다.
- 웹훅 URL은 `https://<서버 도메인>/api/v1/webhooks/toss/<비밀 경로 토큰>`입니다. 토스에서 접근 가능한 HTTPS 주소가 필요하며, 서버는 웹훅 본문만 믿지 않고 토스 상태를 다시 조회합니다.

## 5. 검증과 산출물

`backend` 폴더에서 기본 검증:

```powershell
.\gradlew.bat test bootJar
```

JAR은 `build/libs/shareguard-backend-1.0.0.jar`, 테스트 보고서는 `build/reports/tests/test/index.html`에 생성됩니다.

DB 환경변수가 없으면 DB 관련 테스트 10개는 건너뜁니다. 전체 검증은 JDK 17·Docker와 비어 있는 로컬 포트 55432를 준비한 뒤 실행합니다.

```powershell
.\tools\Test-LocalDatabase.ps1
```

이 도구는 임시 PostgreSQL 16 컨테이너, Supabase Auth·Storage용 테스트 스키마와 기본 스키마를 준비해 테스트하고 컨테이너를 정리합니다. 팀 DB 접속 정보는 사용하지 않습니다. 테스트용 DB 변수에 운영·공유 DB를 연결하지 않습니다.

2026-09-27 코드 `dd6fb7a`에서 서버 **32개 테스트 통과, 실패·건너뜀 0개**와 `bootJar` 생성을 확인했습니다. 실제 팀 Supabase 마이그레이션, 토스 테스트 상점, PortOne과 휴대폰을 함께 사용하는 검증은 남아 있습니다. 외부 서비스 응답에는 테스트 대역을 사용했습니다.

공식 문서 확인일: **2026-09-27**.
