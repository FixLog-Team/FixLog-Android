# Google 로그인 Custom Tabs 전환 가이드 (권장 방식)

> 목적: 기존 **WebView 기반 Google 로그인**(정책 위반·보안 취약)을 Google 권장 방식인
> **외부 브라우저(Custom Tabs) + 백엔드 브로커드 OAuth + 1회용 code 딥링크 교환** 으로 전환한다.
> 대상: FixLog Android (`kr.co.fixlog`) / 서버 `https://fixlog.art/fixlog`
> 문서 구성: (1) 권장 방식 개요 · (2) 클라이언트 작업 · **(3) 서버 작업 — 서버 담당자 전달용**

---

## 1. 권장 방식 개요 (RFC 8252)

Google 공식 권장(“OAuth 2.0 for Native Apps”)의 핵심:

- **임베디드 WebView 금지** → 인가 화면은 **시스템 브라우저 / Custom Tabs** 에서 표시한다.
  - WebView는 앱이 사용자 자격증명을 훔쳐볼 수 있어 위험하고, Google이 `disallowed_useragent`로 차단한다.
- **Authorization Code Flow (+ PKCE)** 사용. 토큰을 URL로 직접 던지는 Implicit 방식은 지양.
- **토큰을 브라우저 URL/히스토리에 남기지 않는다** → 딥링크로는 **1회용 code**만 전달하고, 토큰은 별도 POST로 교환.
- 리다이렉트는 **앱이 소유한 URI**(역DNS 커스텀 스킴 또는 App Links)로 받는다.

### 본 프로젝트 채택안: “백엔드 브로커드 + Custom Tabs + code 교환”
서버가 이미 Google OAuth를 대행하고 **자체 JWT(access/refresh)** 를 발급하므로, 앱은 Google과 직접 통신하지 않는다.
Google과의 PKCE/토큰 교환은 **서버 책임**이고, 앱은 아래만 담당한다.

1. Custom Tab으로 **서버 로그인 시작 URL** 을 연다.
2. 서버가 Google로 리다이렉트 → **시스템 브라우저에서 Google 로그인**(= WebView 아님, 정책 충족).
3. 로그인 성공 후 서버가 **앱 딥링크로 1회용 `code`** 를 리다이렉트한다.
4. 앱이 `code` 를 서버에 보내 **자체 JWT(access/refresh)로 교환**한다.

> 장점: 앱 변경 최소, 토큰이 브라우저에 노출되지 않음, Google 권장(외부 브라우저) 충족.

---

## 2. 플로우 (시퀀스)

```
[앱] 로그인 버튼
  │  state(nonce) 생성·저장
  ▼
[앱] CustomTabsIntent.launchUrl(
        GET {BASE}/login/app?redirect_uri=kr.co.fixlog://oauth2callback&state={state} )
  ▼
[서버] 세션에 (state, redirect_uri) 저장 → Google 인가로 302
  ▼
[브라우저] accounts.google.com 로그인/동의  (시스템 브라우저)
  ▼
[서버] Google 콜백 수신 → code↔token 교환(PKCE) → 사용자 확인
        → 1회용 code(app_code) 발급(단기 TTL, state에 바인딩)
        → 302  kr.co.fixlog://oauth2callback?code={app_code}&state={state}
  ▼
[앱] 딥링크 수신(onNewIntent) → state 검증
  ▼
[앱] POST {BASE}/auth/exchange { code: app_code }
  ▼
[서버] app_code 검증(1회용·만료·state 일치) → { accessToken, refreshToken } 반환
  ▼
[앱] 토큰 저장 → 홈(문서) 진입
```

실패 시: `kr.co.fixlog://oauth2callback?error={code}&state={state}` 로 리다이렉트.

---

## 3. 서버 작업 (⭐ 서버 담당자 전달용)

기존 `/login/swag`, `/login/swag/callback`(토큰을 쿼리로 노출) 흐름을 유지하되, **모바일 앱 전용 흐름**을 추가한다.

### 3.1 로그인 시작 엔드포인트
```
GET {BASE}/login/app?redirect_uri={URL-encoded}&state={opaque-nonce}
```
- 동작: 세션(또는 서명된 임시 저장소)에 `redirect_uri`, `state` 를 보관하고 Google 인가 엔드포인트로 302.
- **`redirect_uri` 화이트리스트 필수**: `kr.co.fixlog://oauth2callback` 만 허용(그 외는 400). → 토큰/코드 유출 방지.
- `state` 는 그대로 최종 콜백까지 왕복시켜 앱이 검증할 수 있게 한다.

### 3.2 Google 콜백 처리 → 앱 딥링크로 1회용 code 리다이렉트
- Google에서 돌아온 뒤(서버가 code↔token 교환, PKCE는 서버가 수행) 사용자 인증을 완료하면,
- **1회용 앱 code(`app_code`) 발급**:
  - 랜덤·추측 불가(≥128bit), **TTL 60초**, **단 1회 사용**, 발급 시 `state`/사용자에 바인딩.
  - 서버에 `app_code → {userId, accessToken, refreshToken}` 매핑 임시 저장(Redis 등).
- 최종 302:
  ```
  kr.co.fixlog://oauth2callback?code={app_code}&state={state}
  ```
- 실패:
  ```
  kr.co.fixlog://oauth2callback?error={error_code}&state={state}
  ```

> ⚠️ **딥링크 URL에 accessToken/refreshToken을 절대 싣지 말 것.** (브라우저 히스토리/Referer 노출) → `app_code` 만.

### 3.3 code 교환 엔드포인트 (신규)
```
POST {BASE}/auth/exchange
Content-Type: application/json

{ "code": "<app_code>" }
```
- 검증: `app_code` 존재·미만료·미사용 → **즉시 소비(1회용)**.
- 응답(공통 래퍼 `{ code, message, result }`):
  ```json
  {
    "code": "SUCCESS",
    "message": "",
    "result": {
      "accessToken": "<JWT accessToken>",
      "refreshToken": "<JWT refreshToken>"
    }
  }
  ```
- 실패: `code != "SUCCESS"` + 사유(만료/이미 사용/없음). HTTP 400/401 권장.
- accessToken 1시간 / refreshToken 14일 정책은 기존과 동일. 재발급은 기존 `POST /auth/token/refresh` 그대로.

### 3.4 보안 체크리스트 (서버)
- [ ] `redirect_uri` 화이트리스트(정확히 `kr.co.fixlog://oauth2callback`)
- [ ] `app_code` : 단기 TTL·1회용·바인딩·안전 난수
- [ ] 딥링크에 토큰 미포함(오직 `app_code`)
- [ ] `state` 왕복 보존(앱이 CSRF 검증)
- [ ] `app_code`/토큰 **로그 미기록**
- [ ] Google Cloud OAuth 콘솔의 **Authorized redirect URI 는 서버 콜백 URL 그대로**(앱 스킴은 등록 불필요 — 서버→앱 구간이라서)

### 3.5 (선택) App Links(HTTPS)로 갈 경우만
커스텀 스킴 대신 `https://fixlog.art/app/oauth2callback` 로 받으려면:
- `https://fixlog.art/.well-known/assetlinks.json` 호스팅:
  ```json
  [{
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "kr.co.fixlog",
      "sha256_cert_fingerprints": ["<릴리스 서명 SHA-256>"]
    }
  }]
  ```
- 위 3.1~3.3의 `redirect_uri` 를 해당 HTTPS URL로 교체.
- (커스텀 스킴 방식이면 이 절은 불필요)

---

## 4. 클라이언트 작업 (앱 — 본 저장소에서 구현)

- [x] `androidx.browser`(Custom Tabs) 사용 (이미 의존성 보유)
- [x] `AndroidManifest`: `GoogleLoginActivity` `launchMode="singleTask"` + 딥링크 인텐트필터
      (`kr.co.fixlog://oauth2callback`, VIEW/DEFAULT/BROWSABLE)
- [x] 로그인 버튼 → `CustomTabsIntent` 로 `{BASE}/login/app?redirect_uri=...&state=...` 오픈
- [x] `state` 생성/저장(SharedPreferences)·콜백에서 검증
- [x] 딥링크 콜백 파싱(`code`/`error`/`state`) → `POST /auth/exchange` 로 토큰 교환 → 저장 → 문서 화면
- [x] 기존 WebView/UA 위장 제거
- [x] 브라우저 없음/사용자 취소 등 예외 처리

### 앱 상수 (합의값)
| 항목 | 값 |
|---|---|
| 로그인 시작 | `GET {BASE}/login/app?redirect_uri={enc}&state={state}` |
| 리다이렉트(딥링크) | `kr.co.fixlog://oauth2callback` |
| 콜백 성공 | `...?code={app_code}&state={state}` |
| 콜백 실패 | `...?error={code}&state={state}` |
| 교환 | `POST {BASE}/auth/exchange { "code": "<app_code>" }` → `result.{accessToken, refreshToken}` |

> 서버가 위 3장 계약을 구현하면 앱은 수정 없이 동작한다. 계약 필드명이 달라지면 앱의 `AuthApi.exchange`/콜백 파서만 맞추면 된다.

---

## 5. 마이그레이션 순서(권장)
1. 서버: `/login/app` + `/auth/exchange` + `app_code` 저장소 구현, `redirect_uri` 화이트리스트.
2. 앱: 본 문서대로 배포(이미 구현됨). 서버 미배포 시엔 로그인 실패하므로 **서버 배포와 동시 릴리스**.
3. 검증: 실기기에서 로그인 → 시스템 브라우저 Google 로그인 → 앱 복귀 → 토큰 교환 성공 확인.
4. 안정화 후 기존 `/login/swag(/callback)` 웹 전용 흐름은 유지(웹) 또는 정리.
```
