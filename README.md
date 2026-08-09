# ShareGuard MVP

`ShareGuard_통합문서.pdf`의 SRS/SDD/API 명세를 기준으로 만든 실행 가능한 웹 프로토타입입니다. 별도 서버 없이 [index.html](./index.html)을 브라우저에서 열면 동작합니다.

Android 네이티브 MVP는 [ShareGuardAndroid](./ShareGuardAndroid) 폴더에 추가했습니다. Kotlin + Android 기본 View로 구현되어 외부 UI 라이브러리 없이 빌드됩니다. Supabase 설정을 추가하면 이메일 로그인과 PostgreSQL 물품 조회/등록이 실제 서버와 연동됩니다.

이메일/Google 로그인과 PortOne PASS 본인인증을 연결하려면 [로그인 및 PASS 설정 가이드](./AUTH_SETUP.md)를 따라 외부 콘솔과 로컬 공개 식별자를 설정합니다.

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
