# FixLog Android

## 시작하기

새 맥에서 설정하려면 `bash setup-mac.sh` 실행. 자세한 절차는 [docs/SETUP.md](docs/SETUP.md).

---

## 기술 스택

| 영역 | 사용 기술 |
|---|---|
| 언어 | Kotlin |
| 최소/타깃 SDK | minSdk 30 / targetSdk 36 |
| 네트워크 | OkHttp 4 (+ logging-interceptor) |

의존성 버전은 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)에서 중앙 관리합니다.

Release key

Key store password : fixlog
Key alias : fixlog
Key password: fixlog