# FixLog Android — 환경 설정 가이드 (macOS)

새 맥에서 이 프로젝트를 빌드·실행하기까지의 전체 절차입니다.
빠르게 하려면 [자동 설정](#1-자동-설정-권장)을, 직접 설치하려면 [수동 설정](#3-수동-설정)을 보세요.

---

## 0. 사전 준비물

| 항목 | 설명 |
|---|---|
| macOS | Apple Silicon / Intel 모두 지원 |
| 인터넷 | Homebrew·JDK·SDK·Gradle 의존성 다운로드 |
| 백엔드 | **FixLog-Server** (앱이 `http://localhost:8080/fixlog`에 붙습니다) |
| 기기 | Android 에뮬레이터(AVD) 또는 USB 연결 실기기 (Android 11 / API 30 이상) |

> 앱만 빌드하는 데는 서버·기기가 필요 없지만, **실행(로그인·문서·AI)** 하려면 서버 기동 + 기기가 필요합니다.

---

## 1. 자동 설정 (권장)

저장소 루트의 `setup-mac.sh`가 없는 도구를 자동 설치하고 빌드까지 합니다.
(Homebrew → JDK 17 → Android SDK/command-line tools → 필수 패키지 + 라이선스 → `local.properties` → 빌드)

```bash
cd FixLog
bash setup-mac.sh            # 전체 설정 + 디버그 APK 빌드
```

옵션:

```bash
bash setup-mac.sh --no-build # 도구 설정만(빌드 생략)
bash setup-mac.sh --run      # 빌드 후 연결된 기기에 자동 설치까지
bash setup-mac.sh --help     # 사용법 출력
```

- 반복 실행해도 안전합니다(이미 있는 건 건너뜀).
- 실행 중 Homebrew 설치 단계에서 관리자 비밀번호를 물어볼 수 있습니다.
- 끝나면 `~/.zshrc`에 아래를 추가하면 새 터미널에서도 `adb`를 바로 씁니다.
  ```bash
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  export PATH="$ANDROID_HOME/platform-tools:$PATH"
  ```

---

## 2. Android Studio로 설정

1. **Android Studio 설치** 후 이 프로젝트 폴더를 **Open**.
2. 열면 Gradle Sync가 자동 실행되고 `local.properties`(SDK 경로)가 생성됩니다.
   Android Studio는 자체 JDK와 SDK(`~/Library/Android/sdk`)를 제공합니다.
3. 필요하면 스튜디오 내장 **Terminal** 탭에서 `bash setup-mac.sh`를 돌려 빌드까지 확인할 수 있습니다
   (이미 있는 SDK/JDK를 감지해 재설치 없이 진행).
4. **Device Manager**에서 에뮬레이터를 만들면 실행 테스트가 편합니다.
   - 에뮬레이터는 **Google Play/APIs 이미지** 권장(Chrome/인터넷 필요 — 구글 로그인).

---

## 3. 수동 설정

자동 스크립트를 쓰지 않을 때:

```bash
# JDK 17 (AGP/Gradle 실행용)
brew install --cask temurin@17

# Android command-line tools (Android Studio가 없을 때)
brew install --cask android-commandline-tools

# 필수 SDK 패키지 + 라이선스 동의
sdkmanager --sdk_root="$HOME/Library/Android/sdk" \
  "platform-tools" "platforms;android-36" "build-tools;36.0.0" "cmdline-tools;latest"
yes | sdkmanager --sdk_root="$HOME/Library/Android/sdk" --licenses

# SDK 경로 지정
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties

# 빌드
./gradlew :app:assembleDebug
```

> `compileSdk`/`build-tools` 버전은 `app/build.gradle.kts` 기준(현재 36 / 36.0.0)입니다. 값이 바뀌면 위 명령도 맞춰주세요.

---

## 4. 실행하기

```bash
# 1) 백엔드 기동 (FixLog-Server 저장소에서)
bash gradlew bootRun          # http://localhost:8080/fixlog

# 2) 기기/에뮬레이터에 서버 포워딩  ※ 기기·에뮬 재부팅마다 다시 실행
adb reverse tcp:8080 tcp:8080

# 3) 설치
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- **서버 주소는 `localhost` 유지** (에뮬레이터도 동일). `10.0.2.2`로 바꾸면 구글 OAuth가 redirect_uri를 거부해 로그인이 막힙니다.
- **Google 로그인**: Google Cloud Console의 Authorized redirect URIs에
  `http://localhost:8080/fixlog/login/oauth2/code/google` 등록 + OAuth 동의화면 **Test users**에 로그인 계정 등록.

---

## 5. 자주 겪는 문제

| 증상 | 원인 / 해결 |
|---|---|
| `SDK location not found` | `local.properties`의 `sdk.dir` 경로 확인(맥 경로여야 함). `setup-mac.sh` 재실행. |
| 빌드 시 JDK 관련 오류 | JDK 17 필요. `/usr/libexec/java_home -v 17` 확인, 없으면 `brew install --cask temurin@17`. |
| `adb: command not found` | `$ANDROID_HOME/platform-tools`를 PATH에 추가(위 [1번](#1-자동-설정-권장) 참고). |
| 앱에서 로그인/문서/검색 실패, `unexpected end of stream` | 백엔드 미기동 또는 `adb reverse` 미설정. 서버 기동 + `adb reverse tcp:8080 tcp:8080`. |
| AI 요약/검색이 "AI 서버 오류(할당량 초과 가능)" | Gemini 무료 할당량(429) 소진 가능. 잠시 후 재시도. |
| 에뮬레이터에서 구글 로그인 차단(`disallowed_useragent`) | Custom Tab/수동 토큰 방식으로 로그인(브라우저로 `/login/swag` 로그인 후 토큰 사용). |

---

관련 문서: [README](../README.md)
