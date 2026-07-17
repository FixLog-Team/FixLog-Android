#!/usr/bin/env bash
#
# FixLog 안드로이드 macOS 자동 세팅 스크립트
# - gradlew 실행 권한 부여, local.properties(SDK 경로) 생성, 디버그 APK 빌드.
# - Android SDK/JDK가 없으면 감지해서 안내한다. 반복 실행 안전.
#
# 사용법:  cd FixLog && ./setup-mac.sh
#
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

info()  { printf "\033[1;34m[i]\033[0m %s\n" "$*"; }
ok()    { printf "\033[1;32m[✓]\033[0m %s\n" "$*"; }
warn()  { printf "\033[1;33m[!]\033[0m %s\n" "$*"; }

echo "==================================================="
echo " FixLog 안드로이드 macOS 세팅"
echo "==================================================="

# ---------------------------------------------------------------------------
# 1. JDK 17+ (AGP/Gradle 실행용)
# ---------------------------------------------------------------------------
if /usr/libexec/java_home -v 21 >/dev/null 2>&1 || command -v java >/dev/null 2>&1; then
  ok "JDK 감지됨"
else
  warn "JDK가 없습니다. Temurin 21 설치 권장: brew install --cask temurin@21 (또는 https://adoptium.net/)"
fi

# ---------------------------------------------------------------------------
# 2. Android SDK 경로 탐지 → local.properties 생성(없을 때만)
# ---------------------------------------------------------------------------
SDK_DIR=""
for cand in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
  if [ -n "$cand" ] && [ -d "$cand" ]; then SDK_DIR="$cand"; break; fi
done

if [ -f "local.properties" ] && grep -q "^sdk.dir=" local.properties 2>/dev/null; then
  ok "local.properties 존재 — 유지 ($(grep '^sdk.dir=' local.properties))"
elif [ -n "$SDK_DIR" ]; then
  info "local.properties 생성 (sdk.dir=$SDK_DIR)"
  echo "sdk.dir=$SDK_DIR" > local.properties
  ok "local.properties 작성 완료"
else
  warn "Android SDK를 찾지 못했습니다."
  warn "  - Android Studio 설치 시 SDK는 보통 \$HOME/Library/Android/sdk 에 있습니다."
  warn "  - 또는 명령줄 도구만: brew install --cask android-commandline-tools"
  warn "  - 이후 local.properties에 'sdk.dir=/절대/경로' 를 직접 넣고 다시 실행하세요."
fi

# ---------------------------------------------------------------------------
# 3. gradlew 권한 + 빌드
# ---------------------------------------------------------------------------
chmod +x ./gradlew || true

if [ -f "local.properties" ] && grep -q "^sdk.dir=" local.properties 2>/dev/null; then
  info "디버그 APK 빌드..."
  if ./gradlew :app:assembleDebug --console=plain; then
    ok "빌드 성공: app/build/outputs/apk/debug/app-debug.apk"
  else
    warn "빌드 실패 — JDK/SDK 설정을 확인하세요."
  fi
else
  warn "SDK 경로 미설정으로 빌드를 건너뜁니다. local.properties 설정 후 './gradlew :app:assembleDebug' 실행."
fi

echo ""
echo "==================================================="
ok "세팅 완료. 실행 순서:"
echo "    1) 백엔드 기동:  (FixLog-Server) ./gradlew bootRun"
echo "    2) 포워딩:       adb reverse tcp:8080 tcp:8080"
echo "    3) 설치:         adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo ""
info "Google 로그인: Console redirect URI = http://localhost:8080/fixlog/login/oauth2/code/google, Test users 등록 필요"
echo "==================================================="
