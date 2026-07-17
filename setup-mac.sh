#!/usr/bin/env bash
#
# FixLog Android — macOS 원클릭 환경 설정 스크립트
#
# 새 맥에서 이 저장소를 "바로 빌드·실행"할 수 있도록 필요한 도구를 자동 설치/설정한다.
#   1) Homebrew            (없으면 설치)
#   2) JDK 17              (AGP/Gradle 실행용, 없으면 Temurin 17 설치)
#   3) Android SDK         (없으면 command-line tools 설치 → platform-tools/플랫폼/빌드툴)
#   4) SDK 라이선스 동의    (자동)
#   5) local.properties    (sdk.dir 작성)
#   6) 디버그 APK 빌드
#   7) (기기 연결 시) adb reverse + 설치
#
# 반복 실행 안전(idempotent): 이미 있는 건 건너뛴다.
#
# 사용법:
#   cd FixLog && bash setup-mac.sh              # 전체 설정 + 빌드
#   bash setup-mac.sh --no-build               # 도구 설정만(빌드 생략)
#   bash setup-mac.sh --run                     # 빌드 후 연결된 기기에 자동 설치까지
#
set -uo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- 옵션 ----------------------------------------------------------------
DO_BUILD=1
DO_RUN=0
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --run)      DO_RUN=1 ;;
    -h|--help)  grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "알 수 없는 옵션: $arg" ;;
  esac
done

# ---- 출력 헬퍼 -----------------------------------------------------------
info()  { printf "\033[1;34m[i]\033[0m %s\n" "$*"; }
ok()    { printf "\033[1;32m[✓]\033[0m %s\n" "$*"; }
warn()  { printf "\033[1;33m[!]\033[0m %s\n" "$*"; }
err()   { printf "\033[1;31m[x]\033[0m %s\n" "$*"; }
step()  { printf "\n\033[1;36m▶ %s\033[0m\n" "$*"; }

COMPILE_SDK=36
BUILD_TOOLS="36.0.0"
ANDROID_SDK_HOME="$HOME/Library/Android/sdk"

echo "==================================================="
echo " FixLog Android — macOS 자동 환경 설정"
echo "==================================================="

# 사전: macOS 여부
if [ "$(uname -s)" != "Darwin" ]; then
  err "이 스크립트는 macOS 전용입니다. (현재: $(uname -s))"
  exit 1
fi

# ---------------------------------------------------------------------------
# 1. Homebrew
# ---------------------------------------------------------------------------
step "1/7 Homebrew 확인"
if ! command -v brew >/dev/null 2>&1; then
  # 이미 설치돼 있으나 PATH에 없을 수 있어 표준 경로를 먼저 시도
  for b in /opt/homebrew/bin/brew /usr/local/bin/brew; do
    [ -x "$b" ] && eval "$("$b" shellenv)" && break
  done
fi
if ! command -v brew >/dev/null 2>&1; then
  info "Homebrew 설치 중... (관리자 비밀번호를 물어볼 수 있습니다)"
  NONINTERACTIVE=1 /bin/bash -c \
    "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)" || {
      err "Homebrew 설치 실패. https://brew.sh 를 참고해 수동 설치 후 다시 실행하세요."; exit 1; }
  for b in /opt/homebrew/bin/brew /usr/local/bin/brew; do
    [ -x "$b" ] && eval "$("$b" shellenv)" && break
  done
fi
command -v brew >/dev/null 2>&1 && ok "Homebrew: $(brew --prefix)" || { err "Homebrew를 찾을 수 없습니다."; exit 1; }

# ---------------------------------------------------------------------------
# 2. JDK 17 (AGP 8.x / Gradle 실행용)
# ---------------------------------------------------------------------------
step "2/7 JDK 17 확인"
JAVA_HOME_17=""
if /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  JAVA_HOME_17="$(/usr/libexec/java_home -v 17)"
fi
if [ -z "$JAVA_HOME_17" ]; then
  info "JDK 17 미감지 → Temurin 17 설치"
  brew install --cask temurin@17 || warn "temurin@17 설치 실패(수동 설치: https://adoptium.net/)"
  /usr/libexec/java_home -v 17 >/dev/null 2>&1 && JAVA_HOME_17="$(/usr/libexec/java_home -v 17)"
fi
if [ -n "$JAVA_HOME_17" ]; then
  export JAVA_HOME="$JAVA_HOME_17"
  export PATH="$JAVA_HOME/bin:$PATH"
  ok "JDK 17: $JAVA_HOME"
else
  warn "JDK 17을 확보하지 못했습니다. 빌드가 실패할 수 있습니다."
fi

# ---------------------------------------------------------------------------
# 3. Android SDK 경로 확보 (기존 우선, 없으면 command-line tools로 부트스트랩)
# ---------------------------------------------------------------------------
step "3/7 Android SDK 확인"
SDK_DIR=""
for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$ANDROID_SDK_HOME"; do
  if [ -n "$cand" ] && [ -d "$cand" ] && { [ -d "$cand/platform-tools" ] || [ -d "$cand/cmdline-tools" ] || [ -d "$cand/licenses" ]; }; then
    SDK_DIR="$cand"; break
  fi
done

if [ -z "$SDK_DIR" ]; then
  info "Android SDK 미감지 → command-line tools 설치 후 $ANDROID_SDK_HOME 에 구성"
  brew install --cask android-commandline-tools || warn "android-commandline-tools 설치 실패"
  SDK_DIR="$ANDROID_SDK_HOME"
  mkdir -p "$SDK_DIR"
fi
export ANDROID_HOME="$SDK_DIR"
export ANDROID_SDK_ROOT="$SDK_DIR"
ok "SDK 경로: $SDK_DIR"

# sdkmanager 위치 결정 (SDK 내부 우선, 없으면 brew가 깐 것)
SDKMANAGER=""
for m in \
  "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" \
  "$(command -v sdkmanager 2>/dev/null || true)"; do
  [ -n "$m" ] && [ -x "$m" ] && SDKMANAGER="$m" && break
done

# ---------------------------------------------------------------------------
# 4. 필수 SDK 패키지 설치 + 라이선스 동의
# ---------------------------------------------------------------------------
step "4/7 SDK 패키지 / 라이선스"
if [ -n "$SDKMANAGER" ]; then
  info "패키지 설치: platform-tools, platforms;android-$COMPILE_SDK, build-tools;$BUILD_TOOLS, cmdline-tools;latest"
  "$SDKMANAGER" --sdk_root="$SDK_DIR" \
    "platform-tools" "platforms;android-$COMPILE_SDK" "build-tools;$BUILD_TOOLS" "cmdline-tools;latest" \
    >/dev/null 2>&1 && ok "SDK 패키지 설치 완료" || warn "일부 패키지 설치 실패(수동 확인 필요)"
  yes | "$SDKMANAGER" --sdk_root="$SDK_DIR" --licenses >/dev/null 2>&1 && ok "라이선스 동의 완료" || warn "라이선스 동의 실패"
else
  warn "sdkmanager를 찾지 못했습니다. Android Studio로 SDK를 설치하거나 command-line tools를 확인하세요."
fi

# adb를 이번 세션 PATH에 추가
[ -d "$SDK_DIR/platform-tools" ] && export PATH="$SDK_DIR/platform-tools:$PATH"

# ---------------------------------------------------------------------------
# 5. local.properties
# ---------------------------------------------------------------------------
step "5/7 local.properties"
if [ -d "$SDK_DIR" ]; then
  printf "# 자동 생성됨 (setup-mac.sh)\nsdk.dir=%s\n" "$SDK_DIR" > local.properties
  ok "local.properties → sdk.dir=$SDK_DIR"
else
  warn "SDK 경로가 유효하지 않아 local.properties를 건너뜁니다."
fi

# ---------------------------------------------------------------------------
# 6. 빌드
# ---------------------------------------------------------------------------
step "6/7 디버그 APK 빌드"
chmod +x ./gradlew || true
BUILT=0
if [ "$DO_BUILD" -eq 1 ]; then
  if ./gradlew :app:assembleDebug --console=plain; then
    ok "빌드 성공: app/build/outputs/apk/debug/app-debug.apk"
    BUILT=1
  else
    warn "빌드 실패 — 위 로그에서 JDK/SDK 관련 오류를 확인하세요."
  fi
else
  info "--no-build: 빌드를 건너뜁니다."
fi

# ---------------------------------------------------------------------------
# 7. (선택) 기기 연결 시 설치
# ---------------------------------------------------------------------------
step "7/7 기기 설치 (선택)"
APK="app/build/outputs/apk/debug/app-debug.apk"
if command -v adb >/dev/null 2>&1; then
  DEVICE_LINE="$(adb devices | sed -n '2p')"
  if [ -n "$DEVICE_LINE" ] && echo "$DEVICE_LINE" | grep -q "device$"; then
    info "기기 감지됨 → 서버 포워딩(adb reverse tcp:8080)"
    adb reverse tcp:8080 tcp:8080 >/dev/null 2>&1 && ok "adb reverse 설정" || warn "adb reverse 실패"
    if [ "$DO_RUN" -eq 1 ] && [ "$BUILT" -eq 1 ] && [ -f "$APK" ]; then
      adb install -r "$APK" >/dev/null 2>&1 && ok "APK 설치 완료" || warn "APK 설치 실패"
    else
      info "APK 설치는 --run 옵션일 때 자동 수행됩니다. (수동: adb install -r $APK)"
    fi
  else
    info "연결된 기기/에뮬레이터가 없습니다. 기기 연결 후: adb reverse tcp:8080 tcp:8080 && adb install -r $APK"
  fi
else
  warn "adb를 PATH에서 찾지 못했습니다. 새 터미널을 열거나 platform-tools를 PATH에 추가하세요."
fi

# ---------------------------------------------------------------------------
# 마무리 안내
# ---------------------------------------------------------------------------
echo ""
echo "==================================================="
ok "환경 설정 완료"
echo "---------------------------------------------------"
echo "다음에 새 터미널을 위해 셸 프로필(~/.zshrc)에 추가 권장:"
echo "    export ANDROID_HOME=\"$SDK_DIR\""
echo "    export PATH=\"\$ANDROID_HOME/platform-tools:\$PATH\""
echo ""
echo "실행 순서:"
echo "    1) 백엔드 기동:  (FixLog-Server) bash gradlew bootRun   # http://localhost:8080/fixlog"
echo "    2) 포워딩:       adb reverse tcp:8080 tcp:8080          # 기기/에뮬 재부팅마다"
echo "    3) 설치:         adb install -r $APK"
echo ""
info "서버 주소는 localhost 유지(에뮬레이터도). 10.0.2.2로 바꾸면 구글 OAuth가 막힙니다."
info "Google 로그인: Console redirect URI에 http://localhost:8080/fixlog/login/oauth2/code/google 등록 + Test users 등록"
echo "==================================================="
