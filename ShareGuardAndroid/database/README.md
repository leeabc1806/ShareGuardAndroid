# ShareGuard Supabase DB 설정

Android는 Supabase Auth·Data API·Storage를 사용하고 Spring 서버는 같은 프로젝트의 PostgreSQL에 접속합니다. 이 문서는 **신규 DB 최초 설치**와 **기존 DB 업데이트**를 구분합니다.

## 1. 신규 Supabase 프로젝트

1. Supabase Dashboard에서 새 프로젝트를 만듭니다.
2. 관리자 SQL Editor에서 [schema.sql](./schema.sql)을 **최초 한 번** 실행합니다.
3. [백엔드 안내](../../backend/README.md)에 따라 같은 프로젝트의 DB·JWKS·외부 서비스 환경변수를 설정합니다.
4. Spring 서버를 시작하여 Flyway V1~V4를 적용합니다.
5. [verify-migrations.sql](../../backend/tools/verify-migrations.sql)로 적용 버전과 권한을 확인합니다.

초기 스키마만 적용한 상태로 최종 설정이 끝난 것은 아닙니다. 결제·QR·관리자 우회 경로 차단은 V3까지 적용해야 반영됩니다.

초기 스키마는 Supabase의 `auth`·`storage` 구조를 전제로 합니다. 일반 PostgreSQL에서 단독 실행하는 용도가 아닙니다. 로컬 자동 검증은 별도의 테스트 스키마를 준비하는 [DB 테스트 도구](../../backend/tools/Test-LocalDatabase.ps1)를 사용합니다.

## 2. 이미 사용하는 팀 DB

1. 현재 스키마와 Flyway 이력을 담당자가 확인합니다.
2. 변경 구문을 검토하고 서버를 시작하여 아직 적용하지 않은 버전만 실행합니다.
3. 확인 SQL로 V1·V2·V3·V4 성공과 권한을 점검합니다.

V4는 예약 날짜 합의, 거래 보관, 채팅방 숨김, 회원별 알림과 닉네임 수정 RPC를 추가합니다. SQL Editor 적용 방법과 앱 변경은 [1.3 기능 변경 안내](../../docs/20261005-feature-update.md)를 참고하세요.

**기존 DB에 초기 `schema.sql` 전체를 다시 실행하지 않습니다.** 특히 V3 이후 초기 함수·권한을 덮어쓰면 차단한 데모 결제·로컬 QR·기존 관리자 RPC가 다시 허용될 수 있습니다.

현재 서버는 `baseline-on-migrate: true`, `baseline-version: 0`을 사용합니다. 기본 스키마가 있고 Flyway 이력이 없는 DB는 기준 버전 0을 기록한 뒤 V1부터 적용합니다. 초기 스키마가 없는 빈 DB를 이 설정만으로 준비할 수는 없습니다.

이후 변경의 기준 경로는 [backend/src/main/resources/db/migration](../../backend/src/main/resources/db/migration)입니다. 이미 적용된 버전 파일을 고치는 대신 다음 버전의 마이그레이션을 추가합니다. Spring의 시작 시 Flyway 실행 방식은 [공식 DB 초기화 안내](https://docs.spring.io/spring-boot/how-to/data-initialization.html)를 참고합니다.

## 3. 주요 데이터와 접근 경로

| 데이터·동작 | 현재 접근 경로 |
| --- | --- |
| 로그인·세션 | Supabase Auth |
| 프로필·물품·예약·계약·후기·채팅 | Android → Supabase Data API, RLS 및 RPC의 권한 검사 |
| 물품·대여 전후 사진 업로드 | Android → Supabase Storage |
| 인증 결과 확인 | Android → Spring → PortOne → DB |
| 결제 승인·환급 | Android → Spring → 토스 → DB |
| QR 발급·검증, 관리자 분쟁 결정 | Android → Spring → DB |
| 결제 내부 주문·QR 토큰·인증 내역·감사 기록 | Spring의 `private` 스키마 테이블 |

V3는 데모 결제, 기존 QR 상태 전환과 관리자 환급 RPC의 앱 실행 권한 및 직접 결제 상태 수정 경로를 제한합니다. 프로필의 완료 거래 수와 신뢰 점수를 갱신하고, 거래 당사자가 비공개 물품의 과거 거래를 읽을 수 있는 정책도 반영합니다.

RLS(Row Level Security)는 로그인 사용자에 따라 접근 가능한 행을 제한하는 DB 정책입니다. 공개 클라이언트 키를 사용하더라도 사용자별 권한 검사가 필요합니다. [Supabase 공식 API 키·RLS 설명](https://supabase.com/docs/guides/getting-started/api-keys)

## 4. Android 설정

Dashboard의 Connect 또는 API Keys에서 프로젝트 URL과 Publishable key를 확인합니다. `ShareGuardAndroid/local.properties`의 기존 `sdk.dir`을 유지하고 추가합니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
```

현재 앱의 설정 판정은 HTTPS URL과 `sb_publishable_` 접두사를 요구합니다. `sb_secret_`·`service_role`과 DB 비밀번호는 앱에 넣지 않습니다. Google·PASS는 [별도 설정](../../AUTH_SETUP.md), 서버 주소는 [루트 README](../../README.md)를 참고합니다.

앱 설정이 비어 있거나 형식이 맞지 않으면 기존 예시 화면이 보일 수 있습니다. 이를 서버 연결 성공으로 해석하지 않습니다. 설정을 바꾼 뒤 재빌드·설치합니다.

## 5. 적용 후 확인

- 이메일 확인 설정에 맞춰 회원가입·로그인이 되는지 확인합니다.
- 실제 계정의 이름·인증 상태·신뢰 점수·거래 이력이 표시되는지 확인합니다.
- 물품 목록을 조회하고, **본인인증을 완료한 계정**으로 물품 등록과 `items` 반영을 확인합니다.
- 두 거래 당사자의 조회 권한과 제3자의 접근 차단을 확인합니다.
- `verify-migrations.sql`로 데모 RPC와 직접 결제 상태 변경이 막혔는지 확인합니다.
- 반납 QR 완료와 결제 환급을 별도 단계로 확인합니다.

현재 카메라 QR 스캔과 Spring 검증 호출은 구현되어 있습니다. 거래별 채팅방 선택, 저장 사진·계약서의 다른 기기 열람, 관리자 처리부터 환급까지의 전체 흐름은 [진행 상태](../../README.md)를 참고합니다.

2026-09-27의 로컬 PostgreSQL 자동 테스트 통과는 **팀 Supabase DB에 적용했다는 기록이 아닙니다**. 팀 DB 적용 후 위 확인이 별도로 필요합니다.

공식 문서 확인일: **2026-09-27**.
