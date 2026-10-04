# ShareGuard

개인 간 물품 대여를 위한 Android 앱과 Spring Boot 서버입니다. Supabase로 로그인·물품·예약 데이터를 관리하고, Spring 서버에서 본인인증 결과, 결제 승인·환급, 일회용 QR과 관리자 분쟁 결정을 검증합니다.

**현재 상태:** 지도사항 2~5의 코드 반영과 로컬 자동 검증을 마쳤습니다. 팀 Supabase 연결, 외부 결제·본인인증 서비스와 실기기를 함께 사용하는 최종 검증은 남아 있습니다. PASS 복귀 오류 및 거래별 채팅·자료 조회·관리자 처리의 남은 작업은 아래 표를 확인하세요.

## 문서와 프로젝트 구성

2026-10-05: 실제 물품 사진, 닉네임, 추가 예약과 날짜 합의, 거래·채팅방 삭제, 알림을 반영했습니다. 적용 순서와 검증 범위는 [1.3 기능 변경 안내](./docs/20261005-feature-update.md)를 참고하세요. 서버 마이그레이션은 V4입니다.

| 문서 | 내용 |
| --- | --- |
| [팀 협업 가이드](./CONTRIBUTING.md) | 복제, 개발 브랜치, 커밋과 Pull Request |
| [백엔드 실행 안내](./backend/README.md) | 환경변수, JWT 설정, API와 서버 테스트 |
| [DB 설정 안내](./ShareGuardAndroid/database/README.md) | 최초 스키마와 이후 Flyway 마이그레이션 |
| [로그인·PASS 설정 안내](./AUTH_SETUP.md) | 이메일·Google·PortOne 설정과 남은 검증 |
| [지도사항 2~5 변경 내역](./docs/교수님_지도사항_반영.md) | 변경된 동작, 실기기 연결 및 검증 기록 |

```text
저장소/
├─ ShareGuardAndroid/         # Android Studio에서 열 폴더
│  ├─ app/                    # Kotlin + Android Views / AndroidX 앱
│  ├─ database/schema.sql     # 신규 Supabase DB 최초 설치용
│  └─ local.properties.example
├─ backend/                   # Java 17 / Spring Boot 4.1.1
│  ├─ src/main/resources/db/migration/
│  └─ tools/                  # 환경 점검·로컬 DB 테스트
├─ tools/Configure-DeviceServer.ps1
├─ docs/
└─ index.html                 # 별도 웹 화면 프로토타입
```

앱은 Supabase Auth·Data API·Storage와 Spring 서버를 모두 사용합니다. 서버는 같은 Supabase 프로젝트의 PostgreSQL에 JDBC로 접속합니다.

[index.html](./index.html)은 서버 없이 열 수 있는 **별도 웹 프로토타입**이며 상태를 브라우저 `localStorage`에 저장합니다. 이 화면의 결제·인증 상태 전환은 Android 앱의 실제 서버 연동 검증과 별개입니다.

## 지도사항별 진행 상태

2026-09-27 코드 기준입니다. “연결”은 코드 구현을 뜻하며 외부 서비스까지 실제 성공했다는 뜻은 아닙니다.

| 번호 | 항목 | 현재 코드와 남은 작업 |
| --- | --- | --- |
| 1 | PASS 실제 인증 | WebView·딥링크·서버 조회 구현. 앱 복귀 시 로그인 토큰 유지 오류 수정과 실기기 인증 성공 확인 필요 |
| 2 | 실기기 ↔ 서버 | 공통 서버 주소, LAN 접속 설정, 환경 점검 도구와 Flyway V1~V4 반영. 팀 DB와 휴대폰에서 연결 확인 필요 |
| 3 | 로그인 사용자·신뢰 점수 | 현재 사용자 프로필, 인증 배지, 신뢰 점수·평점·거래 이력 조회 연결. 실제 계정 전환과 데이터 동기화 확인 필요 |
| 4 | QR 서버 검증 | Spring 발급·검증 API 연결. 만료·재사용·계약·단계·참여자 검사 구현. 두 계정·기기로 전체 거래 확인 필요 |
| 5 | 결제 승인·환급 | Spring 승인·조회·환급 호출 연결. 반납 완료와 환급 완료를 구분. 팀 토스 테스트 상점에서 승인·취소 확인 필요 |
| 6 | 거래별 채팅방 | 예약 ID별 채팅방 연결, 5초 갱신, 입력 보존, 날짜 합의와 채팅방 숨김 구현. 두 계정 실기기 검증 필요 |
| 7 | 사진·계약서 다시 보기 | 등록 물품의 저장 사진 조회·표시와 로컬 계약 PDF 생성 구현. 거래 전후 사진·서명·계약서의 다른 기기 열람 연결 필요 |
| 8 | 관리자 처리·환급 | Spring 분쟁 결정 API 호출 구현. 관리자 상세 조회·결정 후 실제 환급까지 이어지는 화면과 시연 확인 필요 |

## Android 빠른 시작

### 1. 개발 환경

- JDK **17**
- Android SDK Platform **33** (`compileSdk = 33`, `targetSdk = 33`)
- 실행 기기 Android **8.0 / API 26 이상** (`minSdk = 26`)
- 저장소의 Gradle Wrapper 사용

버전의 기준은 [앱 Gradle 설정](./ShareGuardAndroid/app/build.gradle.kts)입니다. Android Studio에서 **내부 `ShareGuardAndroid` 폴더**를 열고, `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`를 JDK 17로 맞춥니다. 터미널의 `JAVA_HOME`도 같은 JDK를 사용합니다. [Android 공식 JDK 안내](https://developer.android.com/build/jdks)

### 2. 앱 설정

Android Studio가 만든 `ShareGuardAndroid/local.properties`의 `sdk.dir`을 유지하고 아래 값을 추가합니다. 현재 프로젝트는 [Gradle 코드](./ShareGuardAndroid/app/build.gradle.kts)에서 이 파일의 앱 설정을 직접 읽습니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
KAKAO_NATIVE_APP_KEY=YOUR_NATIVE_APP_KEY
TOSS_CLIENT_KEY=test_gck_YOUR_CLIENT_KEY
SHAREGUARD_API_BASE_URL=http://10.0.2.2:8080/
TOSS_CONFIRM_URL=
PORTONE_STORE_ID=store-YOUR_STORE_ID
PORTONE_IDENTITY_CHANNEL_KEY=channel-key-YOUR_CHANNEL_KEY
```

| 설정 | 용도 |
| --- | --- |
| `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY` | 앱의 로그인·데이터 접근. 현재 코드는 HTTPS URL과 `sb_publishable_` 형식을 확인 |
| `KAKAO_NATIVE_APP_KEY` | 카카오맵 SDK |
| `TOSS_CLIENT_KEY` | 결제위젯. 팀 테스트 상점의 클라이언트 키 사용 |
| `SHAREGUARD_API_BASE_URL` | 본인인증·QR·결제·환급을 처리할 Spring 서버 주소 |
| `TOSS_CONFIRM_URL` | 비워 두면 기본 서버 주소 + `api/v1/payments/toss/confirm`으로 자동 생성 |
| `PORTONE_STORE_ID`, `PORTONE_IDENTITY_CHANNEL_KEY` | 본인인증 요청에 필요한 공개 식별자 |

앱에는 공개 식별자만 넣습니다. DB 비밀번호, `TOSS_SECRET_KEY`, `PORTONE_API_SECRET`, Supabase `sb_secret_`·`service_role`은 넣지 않습니다. Publishable key와 로그인 사용자의 토큰은 역할이 다르며 데이터 접근 권한은 RLS 정책으로 제한합니다. [Supabase 공식 API 키 안내](https://supabase.com/docs/guides/getting-started/api-keys)

### 3. 실행 환경에 맞는 서버 주소

| 실행 환경 | `SHAREGUARD_API_BASE_URL` 예시 |
| --- | --- |
| Android 에뮬레이터 → 개발 PC | `http://10.0.2.2:8080/` |
| 같은 Wi-Fi의 휴대폰 → 개발 PC | `http://<PC의 LAN IP>:8080/` |
| USB 기기 + `adb reverse tcp:8080 tcp:8080` | `http://127.0.0.1:8080/` |
| 배포된 서버 | `https://<서버 도메인>/` |

`10.0.2.2`는 에뮬레이터에서 개발 PC의 루프백으로 연결하는 특수 주소입니다. 휴대폰에서는 LAN IP 또는 배포 주소를 사용합니다. [Android 공식 에뮬레이터 네트워크 안내](https://developer.android.com/studio/run/emulator-networking-address)

휴대폰용 주소는 저장소 루트에서 다음 도구로 설정할 수 있습니다. 아래 IP는 예시이며 `ipconfig`에서 확인한 PC 주소로 바꿉니다.

```powershell
.\tools\Configure-DeviceServer.ps1 -BaseUrl 'http://192.168.0.12:8080/'
```

서버 실행 후 `-CheckHealth`를 붙이면 PC에서 헬스 체크도 합니다. 휴대폰 브라우저에서도 `http://<PC의 LAN IP>:8080/actuator/health`가 열리는지 별도로 확인합니다. PC에서만 열리면 같은 Wi-Fi, 공유기 단말 간 통신 제한, Windows 개인 네트워크 방화벽의 8080 포트 설정을 확인합니다. HTTP 허용은 현재 디버그 앱 설정에만 있으며 배포 주소에는 HTTPS를 사용합니다.

### 4. 빌드와 실행

1. [DB 설정](./ShareGuardAndroid/database/README.md)과 [백엔드 실행](./backend/README.md)을 담당자와 완료합니다.
2. Android Studio에서 `File > Sync Project with Gradle Files`를 실행합니다.
3. 동기화가 끝나면 상단 `app` 구성과 기기를 선택하고 Run을 누릅니다.
4. 주소·키를 바꿀 때마다 앱을 다시 빌드하고 설치합니다.

저장소 루트에서 빌드하려면:

```powershell
.\ShareGuardAndroid\gradlew.bat -p ShareGuardAndroid testDebugUnitTest assembleDebug
```

결과 APK는 `ShareGuardAndroid/app/build/outputs/apk/debug/app-debug.apk`입니다. 빌드 성공만으로 서버 연결이나 결제·PASS 성공을 확인할 수는 없습니다.

## 외부 서비스 연결

### Supabase와 서버 DB

새 DB는 기본 스키마를 한 번 설치한 뒤 Spring 시작 시 Flyway V1~V4를 적용합니다. 기존 팀 DB에는 초기 `schema.sql`을 다시 실행하지 않습니다. V3 적용 뒤 초기 스키마로 권한을 덮어쓰면 차단했던 데모 결제·QR 경로가 다시 열릴 수 있습니다. [DB 적용 순서](./ShareGuardAndroid/database/README.md)

Spring 서버의 DB 연결·JWT 공개키 주소와 앱의 Supabase URL은 같은 프로젝트를 가리켜야 합니다. 서버의 기본 JWT 허용 알고리즘과 프로젝트 서명 알고리즘을 맞추는 방법은 [백엔드 안내](./backend/README.md)를 참고하세요.

### 카카오맵

1. Kakao Developers에서 앱·Android 플랫폼을 등록합니다. 패키지명은 `com.shareguard.app`입니다.
2. 사용하는 서명 키의 **키 해시**를 등록하고 카카오맵 사용을 활성화합니다. 팀원별 디버그 서명 키가 다를 수 있습니다.
3. 네이티브 앱 키를 `KAKAO_NATIVE_APP_KEY`에 입력하고 재빌드합니다.

설정 기준: [카카오맵 Android SDK 시작하기](https://apis.map.kakao.com/android_v2/docs/getting-started/quickstart/). 키 해시 등록 절차도 해당 문서에서 연결된 플랫폼 안내를 따릅니다.

현재 앱에는 물품 마커, 현재 위치, 반경 필터, 저장된 거래 주소 검색과 거래 좌표 선택이 연결되어 있습니다. 현재 위치는 Android `LocationManager`, 좌표의 주소 변환은 `Geocoder`를 사용합니다. 지역 검색은 저장된 `items.address` 문자열을 찾습니다. 지도 인증 오류는 네이티브 앱 키·패키지명·서명 키 해시·카카오맵 사용 설정을 확인하고, 위치 오류는 앱 권한과 기기 위치 설정을 확인합니다.

### 로그인과 PASS

이메일·Google 로그인은 Supabase Auth, 본인인증은 PortOne V2와 Spring 서버를 사용합니다. Google의 Supabase 콜백 URL과 앱 복귀 딥링크는 서로 다릅니다. [로그인·PASS 설정 및 확인 순서](./AUTH_SETUP.md)

서버는 `identityVerificationId`로 PortOne의 인증 내역을 조회하고 `VERIFIED` 상태를 확인합니다. [PortOne 공식 V2 본인인증 안내](https://developers.portone.io/opi/ko/extra/identity-verification/readme-v2)

### 토스 결제와 보증금 환급

앱에는 클라이언트 키, 서버에는 같은 상점·환경의 시크릿 키를 설정합니다. 테스트 키와 라이브 키를 섞지 않습니다. [토스 공식 API 키 안내](https://docs.tosspayments.com/reference/using-api/api-keys)

현재 앱과 서버의 처리 순서는 다음과 같습니다.

1. 앱이 결제위젯에서 인증 결과를 받습니다.
2. 앱이 `reservationId, paymentKey, orderId, amount`와 로그인 토큰을 Spring 승인 API로 보냅니다.
3. 서버가 DB 예약 금액과 토스 승인 응답을 확인한 뒤 결제 상태를 저장합니다.
4. 반납 QR 성공 시 예약·계약이 완료됩니다. 보증금은 이 단계에서 자동 환급되지 않습니다.
5. 앱이 환급 가능 금액을 조회하고 환급 API를 호출합니다. 서버가 반납·분쟁·차감·기환급 금액을 검사한 뒤 토스 취소 API를 호출합니다.
6. 앱이 서버 상태를 다시 조회해 처리 중·일부 환급·환급 완료를 표시합니다.

토스 승인·취소 규격은 [공식 코어 API](https://docs.tosspayments.com/reference)를 참고합니다. 현재 구현은 결제 승인과 취소를 이용한 보증금 처리이며, 별도 에스크로 서비스 연동 완료를 의미하지 않습니다. 서버 주소가 없거나 승인에 실패하면 결제 완료로 대체 처리하지 않습니다.

## 검증 기록과 재실행

**2026-09-27, 코드 커밋 `dd6fb7a` 기준 확인 결과:**

| 항목 | 결과 |
| --- | --- |
| 백엔드 테스트 | 32개 통과, 실패·건너뜀 0개. 로컬 PostgreSQL 통합 8개와 서버 시작·헬스·인증 테스트 2개 포함 |
| Android JVM HTTP 테스트 | 10개 통과 |
| 산출물 | `bootJar`, `assembleDebug` 성공 |
| 미확인 | 팀 Supabase 접속·마이그레이션, 휴대폰 UI·통신, 실제 PortOne·토스 테스트 상점 연동 |

서버 테스트의 외부 결제·인증 응답에는 테스트 대역을 사용합니다. 로컬 DB 검증은 팀 DB 적용 결과를 대신하지 않습니다.

백엔드 일반 테스트와 JAR 빌드:

```powershell
.\backend\gradlew.bat -p backend test bootJar
```

DB 테스트 환경변수가 없으면 DB 관련 10개 테스트가 건너뛰어집니다. Docker가 실행 중이고 JDK 17과 로컬 포트 55432가 준비되어 있으면 다음 명령으로 임시 PostgreSQL을 사용하는 전체 서버 검증을 실행합니다. 스크립트가 만든 컨테이너는 종료 시 정리됩니다.

```powershell
.\backend\tools\Test-LocalDatabase.ps1
```

## 자주 막히는 부분

| 증상 | 확인할 내용 |
| --- | --- |
| Gradle 동기화·빌드 실패 | 내부 Android 폴더를 열었는지, JDK 17·SDK 33 설치 여부, 동기화 폴더의 빌드 파일 잠금 |
| Supabase 대신 예시 데이터 표시 | `local.properties` 위치와 URL·Publishable key 형식, 변경 후 재빌드 여부 |
| 휴대폰에서 서버 접속 실패 | `10.0.2.2` 사용 여부, 서버 실행·LAN IP·방화벽·같은 Wi-Fi |
| 로그인은 되지만 Spring API가 401 | 같은 Supabase 프로젝트인지, 토큰 만료, JWKS 주소와 JWT 서명 알고리즘 |
| 거래 기능에서 `MFA_REQUIRED` 또는 403 | 서버의 인증 상태·역할과 RLS 정책. PASS 복귀 문제는 [별도 안내](./AUTH_SETUP.md) 확인 |
| 반납 후 아직 환급 대기 | 결제 상태 조회, 실제 환급 요청, 미해결 분쟁·차감·토스 취소 결과 |
| DB 권한·함수 오류 | [마이그레이션 확인 SQL](./backend/tools/verify-migrations.sql)로 V1~V4 및 권한 확인 |

공식 문서 확인일: **2026-09-27**. 문서의 설정 값은 저장소 구현에 맞춘 것이며, 공식 문서의 최신 SDK 예제 버전을 그대로 적용한 것은 아닙니다.
