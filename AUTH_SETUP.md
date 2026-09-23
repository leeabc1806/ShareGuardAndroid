# ShareGuard 로그인 및 PASS 본인인증 설정

앱에는 이메일 회원가입/로그인, Google 로그인, PortOne을 통한 PASS 본인인증 흐름이 구현되어 있습니다. Google과 PASS는 외부 서비스의 발급 정보가 필요하므로 아래 콘솔 설정을 완료해야 실제 기기에서 동작합니다.

## 1. Android Studio 준비

1. Android Studio에서 `ShareGuardAndroid` 폴더를 엽니다.
2. `Settings > Build, Execution, Deployment > Build Tools > Gradle`에서 Gradle JDK를 `Embedded JDK`로 선택합니다.
3. Android SDK Platform 36을 설치합니다.
4. `Sync Project with Gradle Files`를 실행합니다.

프로젝트는 저장소의 Gradle Wrapper와 Android Gradle Plugin 설정을 기준으로 빌드합니다. Android Studio에서는 프로젝트가 지정한 SDK를 설치하고 Embedded JDK 17을 사용하세요.

## 2. 로컬 공개 설정

각 팀원은 `ShareGuardAndroid/local.properties`에 다음 항목을 설정합니다. 기존 `sdk.dir`은 유지합니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
PORTONE_STORE_ID=store-YOUR_STORE_ID
PORTONE_IDENTITY_CHANNEL_KEY=channel-key-YOUR_PASS_CHANNEL_KEY
SHAREGUARD_API_BASE_URL=http://10.0.2.2:8080/
```

`PORTONE_STORE_ID`와 `PORTONE_IDENTITY_CHANNEL_KEY`는 앱 호출에 필요한 공개 식별자입니다. `PORTONE_API_SECRET`, Supabase `service_role`, `sb_secret_...` 값은 앱이나 GitHub에 넣으면 안 됩니다.

## 3. 이메일 회원가입

Supabase Dashboard의 `Authentication > Providers > Email`에서 Email Provider를 활성화합니다. 이메일 확인을 켠 경우 회원가입 후 확인 메일의 링크를 누른 다음 로그인해야 합니다.

## 4. Google 로그인

1. Google Cloud Console에서 OAuth 동의 화면을 설정합니다.
2. 웹 애플리케이션 유형의 OAuth Client ID를 만듭니다.
3. Google OAuth 클라이언트의 승인된 리디렉션 URI에 Supabase Dashboard의 Google Provider 화면에 표시되는 Callback URL을 등록합니다. 일반 형식은 다음과 같습니다.

```text
https://YOUR_PROJECT_REF.supabase.co/auth/v1/callback
```

4. Supabase Dashboard의 `Authentication > Providers > Google`에서 Google Client ID와 Client Secret을 입력하고 Provider를 활성화합니다.
5. Supabase Dashboard의 `Authentication > URL Configuration > Redirect URLs`에 다음 앱 딥링크를 추가합니다.

```text
shareguard://auth/callback
```

앱은 PKCE 방식으로 브라우저를 열고, 로그인 완료 후 위 딥링크로 돌아와 Supabase 세션을 교환합니다.

## 5. PASS 본인인증

PASS 인증은 PASS 개발사와 직접 연결하는 방식이 아니라 PortOne 본인인증 채널을 통해 호출합니다.

1. PortOne 콘솔에서 상점을 생성합니다.
2. 다날, KCP 또는 KG이니시스의 본인인증 계약을 완료합니다.
3. PortOne `연동 정보 > 채널 관리`에서 본인인증 채널을 추가하고 Store ID와 Channel Key를 확인합니다.
4. 두 공개 식별자를 각 팀원의 `local.properties`에 입력합니다.
5. PortOne V2 API Secret은 Spring Boot 서버의 환경 변수로만 등록합니다.

Spring Boot 실행 환경 예시:

```powershell
$env:PORTONE_API_SECRET="YOUR_PORTONE_V2_API_SECRET"
```

저장소의 `supabase/functions/verify-pass`는 팀 브랜치에서 합쳐진 선택적 Edge Function 예제입니다. 현재 Android 앱의 기본 검증 경로는 `POST /api/v1/auth/mfa/verify` Spring Boot API입니다.

인증 성공 콜백만으로 사용자를 인증 처리하지 않습니다. Spring Boot가 로그인 사용자를 확인하고 PortOne REST API에서 상태를 다시 조회한 뒤 `profiles.verified`와 신뢰 점수를 변경합니다. 인증 과정에서 받은 이름, 생년월일, 전화번호 같은 개인정보는 현재 DB에 저장하지 않습니다.

## 6. 확인 순서

1. 앱에서 이메일 회원가입 또는 Google 로그인을 진행합니다.
2. 로그인 후 화면에 `PASS 본인인증 필요`가 표시되는지 확인합니다.
3. `PASS 앱으로 본인인증`을 눌러 인증을 완료합니다.
4. 앱으로 돌아왔을 때 `로그인 및 PASS 본인인증 완료`가 표시되는지 확인합니다.
5. 앱을 종료하고 다시 실행해 로그인 세션과 인증 상태가 복원되는지 확인합니다.
6. 로그아웃 후 거래 기능이 다시 차단되는지 확인합니다.

PASS 앱 전환과 휴대폰 본인확인은 전화번호가 개통된 Android 실기기에서 최종 테스트하는 것이 필요합니다. 에뮬레이터에서는 PASS 앱 설치와 통신사 본인확인 환경이 없어 전체 인증을 완료하지 못할 수 있습니다.

## 7. 팀 공유 규칙

- GitHub에는 코드와 `local.properties.example`만 올립니다.
- 각 팀원은 자신의 `local.properties`를 직접 설정합니다.
- Google Client Secret과 PortOne API Secret은 프로젝트 관리자만 외부 콘솔과 Supabase Secret에서 관리합니다.
- DB 변경 파일은 `ShareGuardAndroid/database/migrations`에서 관리합니다.
- Edge Function 코드는 `ShareGuardAndroid/supabase/functions`에서 관리합니다.
