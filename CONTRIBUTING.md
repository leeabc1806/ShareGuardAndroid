# ShareGuard 팀 협업 가이드

이 문서는 새 팀원이 ShareGuard를 내려받아 실행하고, 다른 팀원의 작업을 덮어쓰지 않으면서 GitHub로 협업하기 위한 절차입니다.

## 1. 준비 사항

- GitHub 계정과 `leeabc1806/ShareGuardAndroid` 저장소 초대
- Git
- Android Studio
- Android SDK Platform 32
- 실행할 Android 에뮬레이터 또는 USB 디버깅이 허용된 Android 기기

GitHub에서 받은 저장소 초대를 먼저 승인합니다. 이 저장소는 비공개이므로 초대를 승인한 계정만 복제할 수 있습니다.

프로젝트는 OneDrive, Google Drive 같은 동기화 폴더보다 `C:\Projects`처럼 일반 로컬 폴더에 복제하는 것을 권장합니다. 동기화 프로그램이 Gradle 빌드 파일을 잠그면 `Failed to clean up output files` 오류가 날 수 있습니다.

## 2. 프로젝트 복제

### Android Studio에서 복제

1. Android Studio 시작 화면에서 `Get from VCS`를 선택합니다.
2. Version control은 `Git`을 선택합니다.
3. URL에 다음 주소를 입력합니다.

```text
https://github.com/leeabc1806/ShareGuardAndroid.git
```

4. Directory는 예를 들어 `C:\Projects\ShareGuardAndroid`로 지정합니다.
5. GitHub 로그인이 나오면 저장소 초대를 승인한 계정으로 로그인합니다.

### 터미널에서 복제

```powershell
cd C:\Projects
git clone https://github.com/leeabc1806/ShareGuardAndroid.git
```

## 3. Android Studio에서 열기

복제된 저장소에는 웹 데모와 Android 프로젝트가 함께 있습니다. Android Studio에서 다음 내부 폴더를 엽니다.

```text
C:\Projects\ShareGuardAndroid\ShareGuardAndroid
```

1. `File > Open`에서 위 폴더를 선택합니다.
2. Gradle Sync가 끝날 때까지 기다립니다.
3. SDK 설치 안내가 나오면 Android SDK Platform 32를 설치합니다.
4. Gradle JDK는 Android Studio의 Embedded JDK를 우선 사용합니다.

## 4. Supabase 연결

`local.properties`에는 컴퓨터별 Android SDK 경로와 앱이 사용할 Supabase 설정이 들어갑니다. 이 파일은 GitHub에 올라가지 않으므로 팀원마다 설정해야 합니다.

1. Android Studio에서 프로젝트를 한 번 열어 `ShareGuardAndroid/local.properties`를 생성합니다.
2. 기존 `sdk.dir` 줄은 삭제하지 않습니다.
3. [local.properties.example](./ShareGuardAndroid/local.properties.example)의 `SUPABASE_URL`과 `SUPABASE_PUBLISHABLE_KEY` 두 줄을 복사해 `local.properties` 아래에 붙여 넣습니다.

완성된 파일 형식은 다음과 같습니다.

```properties
sdk.dir=C\:\\Users\\WINDOWS_USER\\AppData\\Local\\Android\\Sdk
SUPABASE_URL=https://YOUR_PROJECT_REF.supabase.co
SUPABASE_PUBLISHABLE_KEY=sb_publishable_YOUR_KEY
PORTONE_STORE_ID=store-YOUR_STORE_ID
PORTONE_IDENTITY_CHANNEL_KEY=channel-key-YOUR_PASS_CHANNEL_KEY
SHAREGUARD_API_BASE_URL=http://10.0.2.2:8080/
```

키 이름 앞에 `NEXT_PUBLIC_`을 붙이지 않습니다. Android 앱이 읽는 정확한 이름은 다음 두 개입니다.

```text
SUPABASE_URL
SUPABASE_PUBLISHABLE_KEY
```

`sb_secret_...` 또는 `service_role` 키는 앱, 문서, GitHub에 절대 넣지 않습니다. Supabase 관리자 화면이 필요한 팀원은 프로젝트 소유자가 Supabase Dashboard에서도 별도로 초대합니다.

Google 로그인과 PASS 본인인증을 작업하거나 테스트하는 팀원은 [로그인 및 PASS 설정 가이드](./AUTH_SETUP.md)를 먼저 확인합니다. `PORTONE_API_SECRET`은 `local.properties`가 아니라 Spring Boot 서버 환경 변수에만 등록합니다.

현재 팀의 Supabase 프로젝트를 함께 사용한다면 `database/schema.sql`을 다시 실행하지 않습니다. 새 Supabase 프로젝트를 만드는 담당자만 [데이터베이스 설정 문서](./ShareGuardAndroid/database/README.md)를 따라 스키마를 한 번 실행합니다.

## 5. 앱 실행 확인

1. Android Studio 상단 실행 구성에서 `app`을 선택합니다.
2. 실행할 에뮬레이터 또는 연결된 기기를 선택합니다.
3. Run 버튼을 누릅니다.
4. 앱 상단에 Supabase 로그인 영역이 표시되는지 확인합니다.
5. 테스트 계정으로 로그인한 뒤 물품 목록 조회와 등록을 확인합니다.

터미널 빌드 명령은 다음과 같습니다.

```powershell
cd C:\Projects\ShareGuardAndroid\ShareGuardAndroid
.\gradlew.bat assembleDebug
```

## 6. 작업 시작 절차

`main`에서 직접 개발하지 않습니다. 작업을 시작하기 전에 최신 코드를 받고 기능별 브랜치를 만듭니다.

```powershell
git switch main
git pull origin main
git switch -c feature/google-login
```

브랜치 이름은 다음 규칙을 사용합니다.

- `feature/google-login`: 새로운 기능
- `fix/login-error`: 오류 수정
- `docs/team-guide`: 문서 변경
- `refactor/auth-client`: 동작을 바꾸지 않는 코드 정리

한 브랜치에는 가능한 한 하나의 기능만 담습니다. 같은 파일을 여러 명이 동시에 크게 수정하기 전에 담당 범위를 먼저 정합니다.

## 7. 작업 저장 및 공유

변경 내용을 확인한 뒤 자신의 브랜치에 커밋하고 GitHub로 올립니다.

```powershell
git status
git add ShareGuardAndroid/app/src
git commit -m "구글 로그인 화면 구현"
git push -u origin feature/google-login
```

`local.properties`, API 비밀 키, APK, `build` 폴더는 커밋하지 않습니다. `git status`에서 예상하지 못한 파일이 보이면 바로 `git add .`을 실행하지 말고 파일 내용을 먼저 확인합니다.

## 8. Pull Request로 main에 합치기

1. GitHub 저장소에서 `Pull requests > New pull request`를 선택합니다.
2. base는 `main`, compare는 자신의 작업 브랜치를 선택합니다.
3. 구현 내용과 테스트 결과를 작성합니다.
4. 다른 팀원 한 명에게 검토를 요청합니다.
5. 검토와 충돌 해결이 끝나면 main에 병합합니다.

Pull Request 설명에는 다음 내용을 포함합니다.

```text
구현 내용:
- 구글 로그인 버튼과 콜백 처리

확인 방법:
- 에뮬레이터에서 로그인 성공 확인
- 로그아웃 후 세션 제거 확인

남은 작업:
- 오류 메시지 문구 정리
```

병합된 뒤에는 로컬 main을 갱신합니다.

```powershell
git switch main
git pull origin main
git branch -d feature/google-login
```

## 9. 충돌과 오류 대응

### Git 충돌

작업 브랜치에서 최신 main을 가져온 뒤 충돌 파일을 수정합니다.

```powershell
git switch feature/google-login
git fetch origin
git merge origin/main
```

충돌 표시인 `<<<<<<<`, `=======`, `>>>>>>>`를 정리하고 앱을 다시 빌드한 뒤 커밋합니다. 확신이 없으면 강제 푸시하지 말고 팀원과 함께 확인합니다.

### Gradle 리소스 정리 오류

Android Studio를 닫고 다음 명령을 실행한 뒤 다시 빌드합니다.

```powershell
.\gradlew.bat --stop
.\gradlew.bat clean assembleDebug
```

OneDrive 폴더에서 계속 발생하면 프로젝트를 `C:\Projects` 같은 일반 로컬 경로에 다시 복제합니다.

### Supabase 대신 데모 모드가 나오는 경우

1. `local.properties`가 Android 프로젝트 루트에 있는지 확인합니다.
2. 키 이름이 `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`인지 확인합니다.
3. `File > Sync Project with Gradle Files`를 실행합니다.
4. 앱을 삭제한 뒤 다시 실행하거나 `Build > Clean Project` 후 재실행합니다.

## 10. 팀 기본 규칙

- 작업 시작 전에 `main`을 Pull합니다.
- 기능마다 새 브랜치를 만듭니다.
- main에는 Pull Request로만 합칩니다.
- 커밋 메시지에는 무엇을 변경했는지 구체적으로 적습니다.
- 비밀번호와 비밀 키는 GitHub에 올리지 않습니다.
- 작업 담당과 진행 상황은 GitHub Issue 또는 팀 문서에 기록합니다.
- 실행 가능한 상태에서 Push하고 확인한 테스트를 Pull Request에 적습니다.
