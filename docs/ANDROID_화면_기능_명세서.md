# FixLog Android 화면·기능 명세서

> 목적: 웹 프론트(React/FSD)의 기능을 **Android 네이티브(Kotlin + XML)** 로 동일하게 구현하기 위한 화면/기능 정의서
> 서버: 웹과 **동일한 REST API** 사용 (Base URL·Swagger 기준). 상세 계약은 `FRONTEND_API_GUIDE.md`(웹 배포본) 참조
> 기준일: 2026-09 · 패키지: `kr.co.fixlog`

---

## 0. 현재 Android 프로젝트 기준선

| 계층 | 이미 있음 | 추가 필요(파리티 목표) |
|---|---|---|
| Activity | Main, Home, Documents, Search, Settings, Editor, GoogleLogin | Trash, AdminConsole(+탭), Invite, (다이얼로그류: Share/Move/Rename/VersionHistory) |
| Remote API | `AuthApi`, `LoginApi`, `FolderApi`, `DocumentApi`, `AiApi` | `ShareApi`(권한/공유), `WorkspaceApi`, `AdminApi`, `InvitationApi`, `TrashApi`, `LabelApi`, `FavoriteApi`(또는 DocumentApi 확장) |
| DTO | Folder/Document/Search/Ai/Token/ApiResponse 계열 | Permission/Share, Workspace/Member/SecurityPolicy, Admin(권한·감사로그·통계·초대), Trash, Label, SharedWithMe/SharedByMe |
| 공통 | `ApiClient`, `AuthInterceptor`, `TokenAuthenticator` | `X-Workspace-Id` 인터셉터, 403/권한 에러 공통 처리 |

> 기존 `ApiClient`/`AuthInterceptor`/`TokenAuthenticator` 패턴을 그대로 재사용하고, 신규 API는 같은 스타일로 추가한다.

---

## 1. 공통 규칙 (모든 화면 공통)

### 1.1 Base URL / 인증
- Base URL: `https://fixlog.art/fixlog` (context-path `/fixlog` 포함)
- 모든 인증 요청 헤더: `Authorization: Bearer {accessToken}` (기존 `AuthInterceptor`)
- 토큰: accessToken 1시간 / refreshToken 14일. 401 → `POST /auth/token/refresh` 로 재발급 후 원요청 재시도(기존 `TokenAuthenticator`), 재발급 실패 시 로그인 화면
- 로그인: `GET /login` → Google OAuth(웹 Custom Tab/브라우저), 콜백 `?accessToken=&refreshToken=` 파싱 후 저장 (기존 `GoogleLoginActivity`)
- 세션 조회: `GET /auth/token` → `{ userId, userName, email }`

### 1.2 워크스페이스 스코프 (중요)
- 헤더 `X-Workspace-Id: {workspaceId}` 로 현재 워크스페이스를 지정. **생략 시 개인 워크스페이스**
- 워크스페이스 목록: `GET /api/workspaces`. 전환은 이 헤더만 바꾸면 됨(토큰 재발급 불필요)
- **구현**: 선택된 workspaceId를 로컬(SharedPreferences)에 저장하고 인터셉터에서 헤더 주입. 값 없으면 헤더 미포함(개인 WS)
- 속하지 않은 워크스페이스 지정 시 `NOT_FOUND`

### 1.3 공통 응답 래퍼
```json
성공: { "code": "SUCCESS", "message": "", "result": { ... } }
실패: { "code": "NOT_FOUND", "message": "..." }
```
- 페이지네이션: `{ items, page, size, totalElements, totalPages, hasNext }`
- **예외**: AI 대화방(6-1) 응답은 문서상 `{ success, data }` 형태로 기술돼 있으나, 실제 서버는 공통 `{ code, result }` 를 쓰는 것으로 확인됨 → **파서는 공통 래퍼 기준**으로 구현하고 실제 응답으로 검증할 것

### 1.4 에러 코드 → UX
| code | HTTP | 처리 |
|---|---|---|
| `UNAUTHORIZED` | 401 | 토큰 재발급→재시도, 실패 시 로그인 이동 |
| `FORBIDDEN` | 403 | "권한이 없습니다" 안내(동작별 문구). 저장 거부 시 "편집 권한이 없어 저장할 수 없어요" |
| `NOT_FOUND` | 404 | "찾을 수 없거나 접근 권한이 없습니다"(존재 비노출) |
| `INVALID_REQUEST` | 400 | 서버 message 우선 노출 |
| `UNKNOWN` | 500 | "잠시 후 다시 시도" 폴백 |

### 1.5 권한 모델 (서버 기준 — 화면 게이팅에 필요)
- 권한 타입은 **ALLOW 하나로 통일**(DENY 폐기). 접근 차단 = **공유 취소(회수)** + 폴더/워크스페이스 `baseAccess=DENY`
- 접근 우선순위: **직접 권한(DIRECT) > 상위 폴더 상속(INHERITED) > 기본 접근(baseAccess)**
- `baseAccess`: 명시적 권한 없는 구성원의 기본 접근. 개인 WS=항상 ALLOW, 새 협업 WS=기본 DENY
- **공유 관리(부여/회수)는 소유자(생성자) 또는 협업 워크스페이스 관리자만** → 화면에서 폼/회수 버튼 게이팅
- 개인 워크스페이스 교차 공유: 다른 사용자 개인 WS에서 직접 공유받은 폴더·문서에 접근 가능(구성원 아니어도). 목록은 `shared-with-me` 로 확인

---

## 2. 정보 구조 / 내비게이션

웹 사이드바 = Android **Navigation Drawer 또는 하단 탭 + 좌측 Drawer** 로 매핑 권장.

- 상단/드로어 헤더: **워크스페이스 스위처**(현재 WS 이름·역할, 목록·전환·생성)
- 주요 이동: **홈 / AI 검색 / 문서 / 휴지통 / 설정**
- 드로어 하위: **폴더 목록**(공유 허용된 것만 — §5 필터 규칙), **최근 AI 대화방**(최대 5)
- 조건부: **관리자 콘솔**(협업 WS의 ADMIN/OWNER 에게만 노출)
- 좌측 하단(드로어 하단): **계정**(이름·이메일) → 로그아웃

라우트↔화면 매핑:
| 웹 라우트 | Android 화면 |
|---|---|
| `/` `/login` `/login/callback` | Splash/Login/OAuthCallback |
| `/workspace` | HomeActivity |
| `/documents` | DocumentsActivity(목록/폴더) |
| `/documents/{id}` | EditorActivity |
| `/search` `/search/{conversationId}` | SearchActivity |
| `/trash` | TrashActivity (신규) |
| `/settings` | SettingsActivity |
| `/admin/*` | AdminConsoleActivity + 탭 Fragment (신규) |
| `/invite/{token}` | InviteActivity (신규, 딥링크) |

---

## 3. 화면별 명세

각 화면: **목적 / 진입 / UI 구성 / 동작(→API) / 상태 / 권한·조건**

### 3.1 로그인 · OAuth 콜백
- **목적**: Google OAuth 로그인
- **UI**: 랜딩(로고·소개·"FixLog 시작하기"), 로그인 버튼
- **동작**:
  - 로그인 → `GET /login`(브라우저/Custom Tab) → 콜백 URL의 `accessToken`,`refreshToken` 파싱·저장 → 홈 이동
  - 세션 확인 `GET /auth/token`
- **상태**: 로그인 진행/실패
- **권한**: 미인증 상태에서만. 앱 재실행 시 저장 토큰으로 자동 세션 복원, 실패 시 로그인

### 3.2 홈 (HomeActivity)
- **목적**: 진입점 — 최근 문서/폴더, 빠른 작업, 검색 진입
- **UI**:
  - 시간대 인사 + 실제 사용자 이름("○○○님"), "무엇을 찾고 계신가요?" 검색 입력
  - 빠른 작업: **새 문서 / 새 폴더**(문서 가져오기는 미구현)
  - **최근 문서**(수정 최신순), **최근 수정한 폴더**, **고정됨(즐겨찾기)**
- **동작**:
  - 검색 입력 후 실행 → AI 검색 화면으로 이동(질의 전달)
  - 새 문서 → `POST /api/documents { folderId:null, title }` → EditorActivity
  - 새 폴더 → 이름 입력 다이얼로그 → `POST /api/folders`
  - 최근 문서/폴더 목록 → `GET /api/documents?size=N`(updateTime desc), `GET /api/folders/tree` 또는 `GET /api/folders`
  - 고정됨 → `GET /api/documents/favorites`
- **상태**: 로딩/빈 상태("아직 문서가 없습니다")
- **권한**: 로그인 필요

### 3.3 문서 목록 / 폴더 (DocumentsActivity)
- **목적**: 폴더 탐색 + 문서/폴더 관리
- **UI**:
  - 상단 브레드크럼(My Documents > …), **새 폴더 / 새 문서**
  - 검색 입력, **필터**(즐겨찾기 체크박스) — *정렬 버튼은 웹에서 제거됨, 필터만*
  - 리스트: 이름 / 유형(폴더·문서) / 소유자 / 수정일, 각 행 **⋮ 더보기**
  - "문서 위치를 모르시겠나요? AI 검색" 힌트 배너
- **동작**:
  - 루트 조회 `GET /api/folders`(폴더+문서), 폴더 진입 `GET /api/folders/{id}/contents`
  - 폴더 클릭 → 하위로 이동(브레드크럼 push), 문서 클릭 → EditorActivity(진입 폴더 경로 전달)
  - 새 폴더/문서 생성(§3.2와 동일 API), 생성 후 해당 폴더로 이동
  - **⋮ 더보기 메뉴**(폴더/문서 공통·조건부):
    - 이름 변경: 폴더 `PUT /api/folders/{id} {folderName}`, 문서 `PATCH /api/documents/{id}/title {title}`
    - 이동: 대상 폴더 선택 다이얼로그 → 폴더 `PATCH /api/folders/{id}/move {parentId}`, 문서 `PATCH /api/documents/{id}/move {folderId}` (루트=null). 이동 후 **목적지 폴더를 연다**
    - 복제(문서만) `POST /api/documents/{id}/duplicate`
    - 다운로드(문서만) `GET /api/documents/{id}/download`(application/pdf)
    - 공유하기(소유자·관리자만) → 공유 다이얼로그(§3.10)
    - 삭제(소유자·관리자만) → 확인("휴지통으로 이동, 복원 가능") → `DELETE`
  - **필터**: 즐겨찾기 ON → `GET /api/documents/favorites` 만 표시
- **접근 제어(구성원)**: 일반 구성원의 협업 WS에서는 **공유받은 것 + 내가 만든 것만** 노출(§5 필터). 공유 안 된 항목 숨김
- **상태**: 로딩/빈 폴더/에러
- **권한**: 소유자·관리자만 공유·삭제 노출(⋮ 메뉴에서 게이팅)

### 3.4 문서 편집 (EditorActivity) — 핵심 화면
- **목적**: 블록 기반 문서 작성/편집 + AI·버전·공유
- **UI(헤더)**: 브레드크럼, **공유 / 다운로드 / 저장 / 요약 / 버전 기록 / 삭제 / 즐겨찾기(별)** (모두 아이콘/버튼)
- **UI(본문)**: 제목 입력(큰 텍스트), 작성자·수정일, **태그(라벨)** 행, 본문 에디터
  - 본문 에디터: 웹은 BlockNote(블록). Android는 **블록 기반 리치 에디터**가 이상적이나, 1차 MVP는 **문단/제목/리스트/코드 최소 블록** 또는 마크다운/서식 에디터로 대체 가능(서버 blocks 포맷 호환 필요 — 아래 주의)
- **동작**:
  - 로드: 진입 시 `GET /api/documents/{id}/my-permission` 로 접근 확인(access=false면 "접근 권한 없음") → `GET /api/documents/{id}` (blocks 포함)
  - 저장: `PUT /api/documents/{id} { title, blocks }` → "저장했어요" 토스트. (버전 자동 축적)
    - **저장 403 처리**: "편집 권한이 없어 저장할 수 없어요" 안내
  - 제목만 변경: `PATCH /api/documents/{id}/title`
  - 즐겨찾기: 추가 `POST /api/documents/{id}/favorite` / 해제 `DELETE /api/documents/{id}/favorite`
  - 다운로드(PDF): `GET /api/documents/{id}/download`
  - 삭제(소유자·관리자): 확인 팝업 → `DELETE /api/documents/{id}` → 휴지통
  - **요약(§3.4a)**, **버전 기록(§3.4b)**, **공유(§3.10)**, **태그(§3.4c)**
- **상태**: 로딩/접근불가/저장중/에러
- **권한**: 삭제·공유는 소유자(createUser==나) 또는 협업 관리자만. 개인 WS "관리자(=본인)"는 남의 문서 관리 불가

#### 3.4a AI 요약 패널
- **동작**:
  - 요약 실행: **현재 본문 텍스트(blocks→plainText)를 직접** `POST /ai/summarize { content }` (서버 DB 조회 방식 아님 — 화면 내용과 일치 보장)
  - 결과: 마크다운 요약 표시(로딩 "요약하는 중…", 실패 안내)
  - **이어서 질문**: 요약 패널 하단 입력 → AI 대화방(§3.5)과 동일 API. **첫 질문에 "문서 본문 + 요약"을 컨텍스트로 실어** 전송(그 문서 근거로 답변). 이후 턴은 대화 맥락 유지
  - 태그 제안: 요약과 함께 `POST /ai/tags { content }` → 제안 태그, 선택 시 라벨 추가
- **주의**: AI 응답 지연·간헐 실패 가능 → 로딩/에러 UI 필수

#### 3.4b 버전 기록
- **동작**:
  - 목록 `GET /api/documents/{id}/history` (최신순, 본문 제외)
  - 상세/미리보기 `GET /api/documents/{id}/history/{historyId}` (본문 포함)
  - 복원 `POST /api/documents/{id}/history/{historyId}/restore` → 확인 팝업("복원 후에도 다시 되돌릴 수 있습니다") → 본문 갱신
- **UI**: 사이드 패널/바텀시트 — 현재 버전 + 과거 버전(상대시간/제목변경·복원 배지), 미리보기, "이 버전으로 복원"

#### 3.4c 태그(라벨)
- **동작**: 문서 태그 `GET /api/documents/{id}/labels`, 추가 `POST /api/documents/{id}/labels { labelName }`(없으면 생성), 삭제 `DELETE /api/documents/{id}/labels/{labelId}` (편집 권한 필요)

### 3.5 AI 검색 (SearchActivity)
- **목적**: 자연어(의미 기반) 문서 검색 + 멀티턴 대화
- **UI**: 대화 리스트(질문 버블/답변 + 근거 문서 카드), 하단 입력, 헤더 "새 대화" / "대화 삭제"
- **동작**:
  - 대화방 생성 `POST /api/ai/conversations { title }` (첫 메시지 시)
  - 메시지 전송 `POST /api/ai/conversations/{id}/messages { content }` → 답변 + references(근거 문서)
  - 이력 `GET /api/ai/conversations/{id}/messages` (커서 `beforeSequence`, `size`)
  - 대화방 목록(드로어) `GET /api/ai/conversations?page&size`
  - 대화방 삭제 `DELETE /api/ai/conversations/{id}` (확인 팝업)
  - 근거 문서 카드 탭 → 해당 문서 열기
  - (홈에서 넘어온 질의 자동 전송)
- **상태**: 답변 대기(로딩 점), 실패 시 해당 턴 에러
- **주의**: RAG/AI 지연 가능

### 3.6 휴지통 (TrashActivity, 신규)
- **목적**: 삭제 문서·폴더 복원/영구삭제
- **동작**:
  - 목록 `GET /api/trash` (문서·폴더 혼합, 최근 삭제순: resourceType/resourceId/name/deletedBy/deletedAt)
  - 복원 `POST /api/trash/{resourceType}/{resourceId}/restore` (부모가 휴지통이면 루트로 복원)
  - 영구삭제 `DELETE /api/trash/{resourceType}/{resourceId}` → 확인 팝업("복구할 수 없습니다")
- **권한**: 지운 본인·관리자만 조회/복원

### 3.7 설정 (SettingsActivity)
- **목적**: 워크스페이스 정보·공유 목록·보안 정책·나가기/삭제
- **UI/동작**:
  - 현재 워크스페이스 카드(이름·역할 배지)
  - **공유받은 폴더·문서**(트리): `GET /api/documents/shared-with-me` → `{ folders, documents }`. 폴더 펼치면 하위 로드(`/folders/{id}/contents`), 문서 탭→열람. **개인 워크스페이스에서만 표시**(협업 WS에선 숨김)
  - **내가 공유한 폴더·문서**(트리): `GET /api/documents/shared-by-me`. 동일하게 **개인 워크스페이스에서만 표시**
  - 보안 정책(읽기): `GET /api/workspaces/{id}/security-policy` → 공유 허용/다운로드 허용/감사보관일/휴지통보관일
  - 워크스페이스 나가기 `POST /api/workspaces/{id}/leave` / 삭제(소유자) `DELETE /api/workspaces/{id}` — 확인 팝업, 개인 WS는 불가 안내
- **권한**: 공유 회수는 각 리소스 공유 다이얼로그에서(소유자·관리자만)

### 3.8 관리자 콘솔 (AdminConsoleActivity + 탭, 신규)
- **노출 조건**: **협업(비개인) 워크스페이스의 ADMIN/OWNER 만**. 그 외엔 진입/메뉴 미노출
- **탭**: 구성원 / 권한 / 초대 / 감사 로그 / 설정
  - **구성원**: `GET /api/workspaces/{id}/admin/users`(+상세 `/users/{userId}`, 접근목록 `/users/{userId}/access`). 역할 변경 `PATCH /api/workspaces/{id}/members/{userId} {role}`(최대 ADMIN, OWNER 부여 불가), 제거 `DELETE .../members/{userId}`
  - **권한**: 좌측 폴더/문서 트리(`/api/folders/tree` 등) 선택 → 우측 구성원별 **공유(허용)/공유 취소** 스위치 + 다운로드 토글
    - 리소스별 권한 `GET /api/workspaces/{id}/admin/permissions/resources/{type}/{id}`
    - 부여 `POST /api/workspaces/{id}/admin/permissions {resourceType,resourceId,principalType,principalId,permissionType:"ALLOW",canDownload}` / 회수 `DELETE .../permissions/{permissionId}`
    - 폴더 상속·기본 접근 `PATCH .../permissions/resources/folders/{folderId}/settings {inheritFromParent, baseAccess}`
    - (참고) 유효 접근 `GET .../users/{userId}/access` 로 "실제 접근 가능 여부" 표시 가능
  - **초대**: 목록 `GET /api/workspaces/{id}/admin/invitations`, 발송 `POST .../admin/invitations {email, role}`(role: MEMBER|ADMIN, OWNER 불가), 취소 `DELETE .../admin/invitations/{invitationId}`
  - **감사 로그**: `GET /api/workspaces/{id}/admin/audit-logs` (+필터 actor/target/action/result/from/to). 접근·권한변경 구분 표시(permissionChange), 날짜 `yyyy년 MM월 dd일 HH:mm`
  - **설정**: 통계 `GET .../admin/stats`(문서·폴더·휴지통·사용자별 분포), 보안 정책 수정 `PATCH /api/workspaces/{id}/security-policy` (Admin)

### 3.9 이메일 초대 링크 (InviteActivity, 신규 · 딥링크)
- **진입**: 이메일 링크 `{frontend}/invite/{token}` → Android **딥링크/앱링크**로 InviteActivity 매핑(또는 웹뷰). token 파싱
- **동작**:
  - 미리보기(미인증 가능) `GET /api/workspaces/invitations/{token}` → 워크스페이스·초대자·역할·이메일·만료·status
  - 상태별 안내: PENDING 수락/거절 모달, EXPIRED/ACCEPTED/DECLINED/NOT_FOUND 안내
  - 수락(로그인 필요, 이메일 일치) `POST /api/workspaces/invitations/{token}/accept` → 홈 이동 + "참여했습니다" 토스트
    - 비로그인 → 로그인 유도(token 보존) → 로그인 후 자동 수락
    - 이메일 불일치 → "초대된 이메일로 로그인해 주세요" 토스트
  - 거절(미인증 가능) `POST /api/workspaces/invitations/{token}/decline` → 홈/랜딩 + "거절했습니다"

### 3.10 공유 다이얼로그 (공통 컴포넌트)
- **진입**: 문서 목록 ⋮ → 공유하기, 에디터 헤더 공유
- **UI/동작**:
  - 대상 이메일 입력 + **다운로드 허용** 토글 → 공유 `POST /api/documents/{id}/permissions { email, canDownload }` (권한은 항상 ALLOW). 폴더는 `/api/folders/{id}/permissions`
  - 그룹 공유: `{ principalType:"GROUP", principalId }` (협업 WS)
  - 공유 대상 목록 `GET /api/{documents|folders}/{id}/permissions`
  - 회수 `DELETE /api/{documents|folders}/{id}/permissions/{permissionId}`
  - **권한 게이팅**: 소유자(생성자)·관리자만 폼/회수 노출. 그 외는 읽기 전용 + "소유자·관리자만 가능" 안내
  - 정책 `공유 허용=금지` WS에서는 공유 제한 가능

---

## 4. API ↔ 화면 매핑 요약 (신규 API 클래스 기준)

| Android API(신규/기존) | 주요 엔드포인트 | 사용 화면 |
|---|---|---|
| AuthApi(기존) | `/auth/token`, `/auth/token/refresh`, `/login` | 로그인 |
| WorkspaceApi(신규) | `/api/workspaces`(목록/생성/단건/나가기/삭제/멤버/보안정책) | 드로어·설정·관리자 |
| FolderApi(기존+확장) | `/api/folders`(root/tree/{id}/contents/생성/이름/이동/삭제) | 목록·드로어·관리자 |
| DocumentApi(기존+확장) | `/api/documents`(목록/{id}/저장/제목/이동/복제/삭제/download/save-state/history/favorites/favorite) | 목록·에디터·홈 |
| ShareApi(신규) | `/api/{documents\|folders}/{id}/my-permission\|permissions`, `/api/documents/shared-with-me`, `/api/documents/shared-by-me` | 에디터·목록·설정 |
| LabelApi(신규) | `/api/labels`, `/api/documents/{id}/labels` | 에디터 |
| AiApi(기존+확장) | `/ai/summarize`, `/ai/tags`, `/ai/ask`, `/api/ai/conversations(+/messages)` | 요약·AI검색 |
| TrashApi(신규) | `/api/trash(+/{type}/{id}/restore)` | 휴지통 |
| AdminApi(신규) | `/api/workspaces/{id}/admin/*`(users/permissions/invitations/audit-logs/stats) | 관리자 콘솔 |
| InvitationApi(신규) | `/api/workspaces/invitations/{token}(/accept|/decline)` | 초대 |

> Swagger UI: `https://fixlog.art/fixlog/swagger-ui.html` (엔드포인트·요청/응답 스키마 검증용)

---

## 5. 접근/공유 필터 규칙 (목록·드로어 공통)

일반 **구성원**의 **협업 워크스페이스**에서 문서 목록·사이드바 폴더는 **공유받은 것 + 내가 만든 것만** 노출한다(웹 동작 미러링):
- 기준 데이터: `GET /api/documents/shared-with-me` 의 folder/document id 집합
- 노출 조건: `createUser == 나` **또는** `id ∈ shared-with-me` **또는** (문서 목록에서) 공유받은 폴더 하위(상속)
- **관리자·소유자·개인 워크스페이스는 필터 미적용(전체 표시)**
- (서버가 목록을 권한으로 걸러 주면 이 클라이언트 필터는 제거 가능)

---

## 6. 데이터 모델(DTO) 요약 — 신규 필요 중심

기존 DTO(`DocumentDto`, `FolderDto/FolderContentsDto/FolderTreeDto`, `SearchResultDto`, `AskResponse`, `ApiResponse` 등) 재사용 + 아래 추가:

- **DocumentDto 확장**: `createUserName`, `createUserPictureUrl`(상세·shared 응답에만 포함; 목록엔 없음) — 작성자 표시용
- **PermissionDto**: `{ permissionId, principalType(USER|GROUP), principalId, principalName, permissionType("ALLOW"), canDownload, createAt }`
- **MyPermissionDto**: `{ access, canDownload, source(DIRECT|INHERITED|WORKSPACE_DEFAULT), sourceDetail }` (canEdit는 서버엔 있으나 웹은 미사용 — 앱은 필요 시 사용)
- **SharedWithMeDto / SharedByMeDto**: `{ folders: FolderDto[], documents: DocumentDto[] }`
- **WorkspaceDto**: `{ workspaceId, workspaceName, personal, role(OWNER|ADMIN|MEMBER), baseAccess(ALLOW|DENY), createAt }`
- **WorkspaceMemberDto**: `{ userId, userName, email, role, joinedAt }`
- **SecurityPolicyDto**: `{ allowSharing, allowDownload, enforceWatermark, auditRetentionDays, trashRetentionDays }`
- **TrashItemDto**: `{ resourceType(DOCUMENT|FOLDER), resourceId, name, deletedBy, deletedAt }`
- **LabelDto**: `{ labelId, labelName }`
- **HistoryDto / HistoryDetailDto**: `{ historyId, title, source, createUser, createTime (+blocks 상세) }` (서버 실제 필드는 source 값 등 확인 필요)
- **InvitationDto / InvitationPreviewDto**: 초대 정보(email, role, status, expiresAt, workspaceName, inviterName)
- **AI 대화방**: Conversation `{ conversationId, title, ... }`, Message `{ messageId, role(USER|ASSISTANT), content, status, sequence, references[] }` — 실제 응답 필드로 검증
- **Admin**: AdminUser, AdminPermission, EffectivePermission, AuditLog, WorkspaceStats

> 필드명/유무는 **Swagger 실제 응답으로 최종 검증**(문서와 일부 상이 가능: 예 AI 대화방 래퍼, 히스토리 source 값).

---

## 7. 구현 우선순위(권장 단계)

1. **공통 인프라**: X-Workspace-Id 인터셉터, 공통 응답 파서, 403/권한 에러 핸들링, 세션 복원
2. **핵심 열람/편집**: 홈 → 문서 목록/폴더 → 에디터(로드/저장/제목/삭제/즐겨찾기/다운로드)
3. **AI**: 요약(+이어서 질문 컨텍스트), AI 검색(대화방)
4. **버전 기록 / 태그 / 휴지통**
5. **공유**: 공유 다이얼로그(부여/회수·게이팅), 설정의 shared-with-me/by-me(개인 WS 전용)
6. **협업**: 워크스페이스 스위처/생성, 관리자 콘솔(구성원·권한·초대·감사로그·설정), 이메일 초대 딥링크

---

## 8. 주의사항 / 서버 의존 (촬영·검증 시)

- **AI(요약·검색·태그)**: 백엔드 AI 응답 지연·간헐 실패 → 로딩/에러 UI 필수, 데모 전 리허설
- **저장 권한(403)**: 세션 중 권한 회수 시 저장 거부는 **서버가 403을 줘야** 동작(앱은 안내만)
- **DENY/차단**: 서버가 DENY를 강제하지 않음(ALLOW 통일) → 차단은 공유 취소 + baseAccess=DENY로만 성립
- **워크스페이스 baseAccess 토글**: `PATCH /api/workspaces/{id}/base-access` 존재(웹 UI 미구현). 앱에서 필요 시 관리자 설정에 추가 검토
- **AI 대화방 응답 래퍼 / 히스토리 source 값**: 문서와 실제 응답이 다를 수 있으니 **Swagger·실 응답으로 파서 검증**
- **에디터 블록 포맷**: 서버는 blocks(JSON)로 저장. 웹은 BlockNote↔Editor.js 변환을 거침. Android 에디터도 **서버 blocks 스키마(paragraph/header/list/code/image/table)** 와 호환되게 직렬화해야 함(1차엔 기본 블록만 지원 가능)

---

## 참고 문서
- `FRONTEND_API_GUIDE.md` — 서버 REST API 전체 계약(공통 규칙·엔드포인트·요청/응답 예시)
- Swagger UI — `https://fixlog.art/fixlog/swagger-ui.html`
- 웹 프론트 소스(FixLog-front) — 화면별 정확한 동작 참조(FSD: `src/pages`, `src/widgets`, `src/domains`)
