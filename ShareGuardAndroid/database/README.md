# ShareGuard Supabase 설정

현재 앱은 Supabase 설정이 없으면 기존 데모 모드로 실행되고, 설정이 있으면 이메일/Google 로그인부터 예약·계약·거래 완료까지 서버 데이터를 사용합니다. 고위험 기능은 PortOne PASS 본인인증과 Spring Boot 검증 API로 보호합니다.

## 1. Supabase 프로젝트 만들기

1. <https://supabase.com/dashboard>에서 새 프로젝트를 만듭니다.
2. 프로젝트가 준비되면 `SQL Editor`를 엽니다.
3. [schema.sql](./schema.sql) 전체를 붙여 넣고 `Run`을 누릅니다.
4. `Authentication > Providers`에서 Email과 Google 로그인을 활성화합니다.

`schema.sql`에는 사용자 프로필, 물품, 예약, 결제, 계약, 채팅, 상태 사진, QR 인증, 후기, 신고 테이블과 RLS 정책이 포함되어 있습니다. 결제 성공/환불 상태는 모바일 앱에서 직접 수정할 수 없고 이후 Spring Boot 서버나 결제 웹훅에서 처리해야 합니다.

## 2. Android 앱 연결하기

Supabase 프로젝트의 `Connect` 화면에서 Project URL과 Publishable key를 확인합니다. `ShareGuardAndroid/local.properties`의 기존 `sdk.dir` 아래에 다음 두 줄을 추가합니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
```

`local.properties`는 Git 제외 대상입니다. 모바일 앱에는 Publishable key만 사용하고 `sb_secret_...` 또는 `service_role` 키를 넣으면 안 됩니다.

## 3. 실행 확인

Android Studio에서 `Sync Project with Gradle Files`를 누른 뒤 에뮬레이터에서 `app`을 실행합니다.

1. 상단 Supabase 영역에서 이메일과 8자 이상 비밀번호로 회원가입합니다.
2. 이메일 확인 옵션이 켜져 있다면 받은 메일에서 가입을 확인합니다.
3. 앱에서 로그인합니다.
4. `등록` 탭에서 물품을 등록합니다.
5. Supabase `Table Editor > items`와 앱 홈에 같은 물품이 나타나는지 확인합니다.

빌드 명령:

```powershell
.\gradlew.bat assembleDebug
```

## 현재 연결 범위

- 실제 Supabase Auth 이메일 회원가입/로그인/로그아웃
- Google OAuth PKCE 로그인과 앱 딥링크 복귀
- PortOne PASS 본인인증 및 서버 재검증
- 공개된 활성 물품 목록 조회
- 로그인 사용자의 물품 등록
- 예약 신청, 대여자 승인/거절, 중복 기간 차단
- 테스트 결제 및 보증금 예치 상태 기록
- 대여자·차용자 양측 전자 서명
- 비공개 Storage 대여 전/후 상태 사진 업로드
- 서버 검증 기반 대여 시작/반납 완료 상태 전환
- 예약별 채팅 메시지 저장과 조회
- 거래 완료 후 후기 저장
- 분쟁 신고와 계약 상태 전환
- 세션 토큰 로컬 보관
- Supabase 미설정 시 기존 데모 모드 유지

`demo` 결제는 결제사 승인 없이 개발 흐름을 검증하기 위한 테스트 구현입니다. 실제 금전 결제 전에는 결제사 서버 승인과 웹훅 검증으로 `pay_demo_reservation`을 교체해야 합니다. QR 단계는 서버 상태·권한·사진 선행 조건을 검증하지만, 카메라 바코드 스캔 UI는 별도 구현 대상입니다.
