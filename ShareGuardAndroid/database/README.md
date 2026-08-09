# ShareGuard Supabase 설정

현재 앱은 Supabase 설정이 없으면 기존 데모 모드로 실행되고, 설정이 있으면 이메일/Google 로그인, PASS 본인인증과 PostgreSQL 물품 조회/등록을 사용합니다.

## 1. Supabase 프로젝트 만들기

1. <https://supabase.com/dashboard>에서 새 프로젝트를 만듭니다.
2. 프로젝트가 준비되면 `SQL Editor`를 엽니다.
3. [schema.sql](./schema.sql) 전체를 붙여 넣고 `Run`을 누릅니다.
4. `Authentication > Providers > Email`에서 이메일 로그인을 활성화합니다.

`schema.sql`에는 사용자 프로필, 물품, 예약, 결제, 계약, 채팅, 상태 사진, QR 인증, 후기, 신고 테이블과 RLS 정책이 포함되어 있습니다. 결제 성공/환불 상태는 모바일 앱에서 직접 수정할 수 없고 이후 Spring Boot 서버나 결제 웹훅에서 처리해야 합니다.

## 2. Android 앱 연결하기

Supabase 프로젝트의 `Connect` 화면에서 Project URL과 Publishable key를 확인합니다. `ShareGuardAndroid/local.properties`의 기존 `sdk.dir` 아래에 공개 설정을 추가합니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
PORTONE_STORE_ID=store-YOUR_STORE_ID
PORTONE_CHANNEL_KEY=channel-key-YOUR_PASS_CHANNEL_KEY
```

`local.properties`는 Git 제외 대상입니다. 모바일 앱에는 Publishable key만 사용하고 `sb_secret_...` 또는 `service_role` 키를 넣으면 안 됩니다.

## 3. 실행 확인

Android Studio에서 `Sync Project with Gradle Files`를 누른 뒤 에뮬레이터에서 `app`을 실행합니다.

1. 상단 로그인 영역에서 이메일과 8자 이상 비밀번호로 회원가입하거나 Google 로그인을 진행합니다.
2. 이메일 확인 옵션이 켜져 있다면 받은 메일에서 가입을 확인합니다.
3. 앱에서 로그인합니다.
4. PASS 본인인증을 완료합니다.
5. `등록` 탭에서 물품을 등록합니다.
6. Supabase `Table Editor > items`와 앱 홈에 같은 물품이 나타나는지 확인합니다.

빌드 명령:

```powershell
.\gradlew.bat assembleDebug
```

## 현재 연결 범위

- 실제 Supabase Auth 이메일 회원가입/로그인/로그아웃
- Google OAuth PKCE 로그인과 세션 갱신
- PortOne Android SDK 본인인증 호출
- Supabase Edge Function의 PortOne 결과 재검증 및 프로필 인증 처리
- 공개된 활성 물품 목록 조회
- 로그인 사용자의 물품 등록
- 액세스/갱신 토큰 로컬 보관 및 앱 재시작 시 세션 복원
- Supabase 미설정 시 기존 데모 모드 유지

예약, 계약, 채팅, 사진, QR, 후기, 신고용 테이블과 보안 정책은 준비되어 있습니다. 해당 화면은 아직 로컬 데모 상태 전환을 사용하며 다음 단계에서 각 REST 호출과 Storage 업로드를 연결하면 됩니다.
