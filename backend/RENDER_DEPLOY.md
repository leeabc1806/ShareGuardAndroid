# Render 무료 백엔드 배포

저장소 루트의 [`render.yaml`](../render.yaml)은 `backend/`를 Docker Web Service로
배포합니다. `main`이 아닌 `integration/shareguard-all-2026-09-23` 브랜치를 사용합니다.
실제 DB 비밀번호와 PortOne API Secret은 Git에 저장하지 않습니다.

1. Render에 로그인하고 **New > Blueprint**에서 이 GitHub 저장소와 통합 브랜치를 선택합니다.
2. 처음 생성할 때 아래 환경변수를 Render의 비공개 입력란에 넣습니다. 값은 기존
   로컬 백엔드 환경과 같은 Supabase 프로젝트/PortOne 상점 기준이어야 합니다.

   | 변수 | 입력 값 |
   | --- | --- |
   | `SUPABASE_DB_URL` | Supabase **Session pooler**의 JDBC URL, `?sslmode=require` 포함 |
   | `SUPABASE_DB_USER` | Session pooler 사용자명 (`postgres.<project-ref>`) |
   | `SUPABASE_DB_PASSWORD` | 해당 DB 비밀번호 |
   | `SUPABASE_JWKS_URI` | `https://<project-ref>.supabase.co/auth/v1/.well-known/jwks.json` |
   | `PORTONE_API_SECRET` | PortOne V2 서버 API Secret |
   | `PORTONE_STORE_ID` | 앱과 같은 PortOne 상점 ID |

   `PORTONE_API_SECRET`, DB 비밀번호는 Android APK, `local.properties.example`,
   `render.yaml`, GitHub Issues에 입력하지 않습니다. JWT 알고리즘 `ES256`은 현재
   프로젝트 기준으로 Blueprint에 지정되어 있으므로, Supabase 서명 키가 바뀌면
   배포 전 JWT 알고리즘을 다시 확인합니다.

3. 서비스의 **Deploy** 상태와 `https://<서비스명>.onrender.com/actuator/health`의
   `UP` 응답을 확인합니다. `UP`은 서버/DB 연결 확인일 뿐 PASS 실인증 성공을
   뜻하지 않습니다. 실제 인증 완료, DB 반영은 휴대폰에서 별도로 검증합니다.
4. 팀 배포용 Android APK는 `ShareGuardAndroid/`에서 다음처럼 빌드합니다.
   `local.properties`의 에뮬레이터 주소는 그대로 두고 배포 빌드에만 공개 HTTPS
   주소를 적용합니다. `10.0.2.2`는 에뮬레이터 전용입니다.

   ```powershell
   .\gradlew.bat :app:assembleDebug -PshareguardApiBaseUrl=https://<서비스명>.onrender.com/
   ```

   디버그 APK를 다른 개발자가 다시 빌드하면 서명 키 해시가 달라질 수 있습니다.
   Kakao Developers에 등록한 키 해시는 실제로 배포할 APK의 서명 기준이어야 합니다.

무료 서비스는 15분 유휴 후 잠들고 다음 요청에 약 1분이 걸릴 수 있습니다. PASS
시연 전에 `/actuator/health`를 열어 깨우고, 사용량 제한과 서비스 상태를 확인합니다.
무료 서비스는 실서비스가 아닌 팀 데모용입니다. 서버 시작 시 Flyway V1~V3가
기존 Supabase DB에 적용될 수 있으므로 최초 배포 전에 팀 DB의 스키마/백업과
마이그레이션 이력을 확인하세요.

Render: [Blueprint](https://render.com/docs/blueprints),
[무료 서비스](https://render.com/docs/free),
Supabase: [DB 연결](https://supabase.com/docs/guides/database/connecting-to-postgres).
