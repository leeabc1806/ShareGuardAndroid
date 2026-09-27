# 로그인·PASS 본인인증 설정

이메일·Google 로그인은 Supabase Auth, PASS 본인인증 요청은 PortOne V2, 인증 결과 검증은 Spring 서버를 사용합니다.

**남은 문제:** 현재 `MfaVerificationActivity.onNewIntent()`는 복귀 Intent로 원래 Intent를 바꿉니다. 이후 검증 요청에서 읽는 `EXTRA_ACCESS_TOKEN`이 복귀 Intent에 없으면 로그인 토큰이 유실될 수 있습니다. 이 오류 수정과 실기기 인증 성공 확인은 아직 완료하지 않았습니다. 아래 설정만으로 전체 PASS 흐름의 성공을 보장하지 않습니다.

## 1. 개발 환경과 공개 설정

Android Studio에서 내부 `ShareGuardAndroid` 폴더를 엽니다. 현재 프로젝트 기준은 **JDK 17, SDK Platform 33, 최소 실행 API 26**입니다. Embedded JDK의 버전을 확인하고 17이 아니면 별도 JDK 17을 선택합니다. [Android 공식 JDK 안내](https://developer.android.com/build/jdks)

`ShareGuardAndroid/local.properties`의 `sdk.dir`은 유지하고 다음 값을 추가합니다.

```properties
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
SHAREGUARD_API_BASE_URL=http://10.0.2.2:8080/
PORTONE_STORE_ID=store-YOUR_STORE_ID
PORTONE_IDENTITY_CHANNEL_KEY=channel-key-YOUR_CHANNEL_KEY
```

`10.0.2.2`는 에뮬레이터용입니다. 휴대폰에서는 같은 Wi-Fi의 PC LAN IP 또는 HTTPS 서버 주소를 사용합니다. 설정을 바꾼 뒤 Gradle Sync와 재빌드·설치를 수행합니다. [서버 주소 설정](./README.md)

앱에는 Publishable key와 공개 식별자만 넣습니다. 서버 비밀키, Supabase `sb_secret_`·`service_role`은 앱에 넣지 않습니다. [Supabase 공식 API 키 안내](https://supabase.com/docs/guides/getting-started/api-keys)

## 2. 이메일 회원가입

Supabase Dashboard의 Authentication에서 Email Provider를 활성화합니다. 이메일 확인을 켠 프로젝트에서는 가입 확인 메일을 처리한 뒤 로그인합니다. 앱이 로그인한 프로젝트와 Spring 서버의 DB·JWKS 프로젝트가 일치해야 합니다.

## 3. Google 로그인

1. Google Cloud에서 OAuth 동의 화면과 **웹 애플리케이션** 유형의 OAuth Client ID를 준비합니다.
2. Google의 승인된 리디렉션 URI에는 Supabase Google Provider 화면의 콜백 URL을 등록합니다.
3. Google Client ID와 Client Secret을 Supabase의 Google Provider 설정에 입력하고 활성화합니다.

Google에 등록하는 Supabase 콜백 URL의 형식:

```text
https://YOUR_PROJECT_REF.supabase.co/auth/v1/callback
```

설정 기준: [Supabase 공식 Google 로그인 안내](https://supabase.com/docs/guides/auth/social-login/auth-google).

4. Supabase의 URL Configuration → Redirect URLs에는 다음 **앱 복귀 주소**를 추가합니다.

```text
shareguard://auth/callback
```

현재 앱은 PKCE 방식으로 브라우저에서 인증한 뒤 딥링크로 돌아와 세션을 교환합니다. Google에 등록하는 HTTPS 콜백과 앱 딥링크를 혼동하지 않습니다.

## 4. PortOne V2 본인인증

1. PortOne 콘솔에서 상점과 사용할 본인인증 채널의 계약·설정을 준비합니다.
2. 해당 채널의 Store ID와 Channel Key를 앱의 `local.properties`에 입력합니다.
3. 서버에 `PORTONE_API_SECRET`, `PORTONE_STORE_ID`를 설정합니다. 전체 서버 환경변수와 실행 방법은 [백엔드 안내](./backend/README.md)를 따릅니다.
4. 앱 요청의 PG별 옵션을 채널과 맞춥니다. 현재 코드에는 다날용 `bypass.danal.CPTITLE`이 포함되어 있습니다.

`identityVerificationId`는 본인인증 건의 식별자입니다. 서버는 이 값으로 조회한 내역의 `VERIFIED` 상태를 확인해야 합니다. [PortOne 공식 V2 본인인증 안내](https://developers.portone.io/opi/ko/extra/identity-verification/readme-v2)

현재 구현 경로:

```text
Android WebView → 외부 인증 앱
→ shareguard://mfa-complete
→ POST /api/v1/auth/mfa/verify (로그인 토큰 + identityVerificationId)
→ PortOne V2 내역 조회
→ Supabase 프로필 인증 상태·신뢰 점수 갱신
```

Spring 서버는 CI/DI 원문·전체 전화번호를 보관하지 않습니다. `private.mfa_verifications`에는 인증 식별자, 사용자 ID, 신원 정보의 해시와 전화번호 마지막 4자리 등이 저장됩니다. “개인정보를 전혀 저장하지 않는다”는 설명은 현재 구현에 맞지 않습니다.

저장소의 기존 Edge Function 예제는 현재 Android의 기본 인증 검증 경로가 아닙니다. 현재 앱은 위 Spring API를 호출합니다.

## 5. 수정 후 확인할 시나리오

아래 목록은 **앞으로 확인할 항목**이며 실기기 성공 기록이 아닙니다.

- 이메일·Google 로그인 후 실제 사용자 프로필 조회
- 미인증 계정의 물품 등록·결제 차단
- PASS 앱 전환 후 복귀 시 로그인 토큰 유지 및 서버 인증 요청
- 인증 취소·오류 시 프로필을 인증 완료로 바꾸지 않음
- PortOne 서버 검증 성공 후 인증 배지와 신뢰 점수 갱신
- 앱 재시작·계정 전환 시 세션과 표시 정보 확인
- 기기 두 대에서 같은 계정의 서버 인증 상태 확인

PASS 앱 전환을 포함한 흐름은 해당 인증 채널을 사용할 수 있는 실제 Android 기기에서 확인합니다. 에뮬레이터의 화면 표시와 API 오류 테스트만으로 본인인증 완료를 판정하지 않습니다.

## 6. 설정·DB 변경 공유

- 각 팀원은 자신의 `local.properties`를 설정하고 Git에는 커밋하지 않습니다.
- Google Client Secret은 Supabase Google Provider 설정에, PortOne API Secret은 Spring 서버 환경에 보관합니다.
- 현재 서버 DB 변경은 [backend/src/main/resources/db/migration](./backend/src/main/resources/db/migration)의 Flyway 파일로 관리합니다.
- 새 DB와 기존 DB의 적용 순서는 [DB 설정 안내](./ShareGuardAndroid/database/README.md)를 따릅니다.

공식 문서 확인일: **2026-09-27**.
