# VERIFICATION — 실측 기록

로드맵의 "실측 필수" 원칙에 따라, 각 Phase가 실제로 도는 증거를 남긴다. 지어낸 수치 없음.

---

## Phase 2 — TCP 프레이밍 · 프로토콜 변환

### 검증 환경 (앱 ↔ 계정계 두 프로세스, 실제 소켓)

```
# 프로세스 1: 목업 계정계 TCP 서버 (독립 JVM)
./gradlew runMockCore              # → 포트 9099 대기

# 프로세스 2: 게이트웨이 앱 (내장 계정계는 끔)
java -jar build/libs/gwanmun-0.1.0.jar --gwanmun.core.embedded=false   # → 8090
```

- 앱: 8090, 내장 목업 비활성(`gwanmun.core.embedded=false`)
- 계정계: 9099, 독립 프로세스
- 둘은 실제 TCP 소켓(127.0.0.1:9099)으로 대화한다(인메모리 호출 아님).

### 1) 정상 왕복 (curl, 실제 출력)

```
POST /api/gateway/balance {"accountNo":"12345678901234"}
```

```json
{
  "requestHex":  "303230303132333435363738393031323334494E30312020202020202020",
  "requestLength": 30,
  "responseHex": "303231303132333435363738393031323334494E3031303030303036383739343435303030303030
                  3030C1A4BBF320C3B3B8AEB5C7BEFABDC0B4CFB4D920",
  "responseLength": 61,
  "json": {
    "messageType": "0210", "accountNo": "12345678901234", "txCode": "IN01",
    "balance": "6879445000", "responseCode": "0000", "responseMessage": "정상 처리되었습니다"
  },
  "core": "127.0.0.1:9099", "elapsedMs": 12
}
```

- 요청 30byte / 응답 61byte가 소켓을 실제로 오갔다(hex는 그 바이트 그대로).
- 응답 꼬리 `C1A4 BBF3 20 C3B3 ...` = EUC-KR `정 상 (공백) 처 ...` → 소켓 건너온 한글이 안 깨지고 복원.
- 잔액은 계좌번호 해시 기반 결정론적 합성값(같은 계좌 → 같은 값).

### 2) 오류·검증 경로

| 입력 | 결과 |
|---|---|
| `accountNo=7` (짧은 계좌) | 요청 전문에서 좌측 제로패딩(`00000000000007`), 응답코드 `0000` |
| `accountNo=0` (없는 계좌) | 응답코드 `0001`, 메시지 "없는 계좌입니다" |
| `accountNo=ABC` (숫자 아님) | HTTP 400, "accountNo 는 숫자 1~14자리여야 합니다" |
| 계정계 다운 | HTTP 502, GatewayException("통신 실패") |

### 3) 프레이밍(partial read) 재조립 — 테스트로 강제

- 단위: 61byte 전문을 **한 바이트씩 61번** 나눠 feed → 60번째까지 `null`, 61번째에 원본과 동일 프레임.
- 소켓: 클라이언트가 30byte 요청을 **12+18로 쪼개**(시간차 80ms) 보내도 서버가 누적·재조립해 정상 응답.
- 뭉침: 두 전문이 붙어 한 조각(122byte)으로 와도 두 프레임으로 분리.
- keep-alive(한 연결 3건), 동시 10건도 통과.

### 4) 화면

`docs/images/gateway-roundtrip.png` — 계좌번호 입력 → 전송 시 (a)요청 전문 hex (b)응답 전문 hex (c)최종 JSON.
puppeteer-core 헤드리스 크롬으로 라이브 캡처(가짜 UI 아님).

### 5) 테스트

```
./gradlew test   # 37개 통과 (Phase 1: 20 + Phase 2: 17)
```

- `FixedLengthFramerTest` (10) — 경계 복원 불변식(반쪽·뭉침·한 바이트씩·용량 확장 등)
- `MockCoreBankingServerTest` (6) — 실제 소켓 왕복·결정론·없는 계좌·서버측 partial read·keep-alive·동시 10건
- `GatewayServiceTest` (2) — 전 구간 배선·계정계 다운 시 예외 래핑

### 잔여 (정직하게 안 함)

커넥션 풀 없음(요청당 소켓), 길이 헤더(가변 전문) 미구현, 전문 암호화·전용선 미적용(평문 로컬 소켓). 학습판 경계.

---

## Phase 3 — API 게이트웨이 층(인증·라우팅·유량제어) + 모듈러 모놀리스

Phase 2까지는 통로(전문↔JSON 왕복)만이었다. Phase 3은 그 통로 앞에 문지기를 세운다 —
`/api/gateway/**`에만 걸리는 손으로 짠 필터 체인(인증 → 라우팅 → 유량제어). 프레임워크 완제품
(Spring Security 등)에 맡기지 않고, `GatewayFilter` 인터페이스 + 체인 실행기 + 서블릿 브릿지로 직접 짰다.

동시에 코드베이스를 Spring Modulith 기반 모듈러 모놀리스로 재정렬했다(io.gwanmun 하위 각 패키지 = 모듈).

### 필터 체인 구성

```
[요청] → AuthenticationFilter(#10) → RoutingFilter(#20) → RateLimitFilter(#30) → [컨트롤러 → GatewayService]
           X-API-Key 검증               경로→라우트 매핑         클라이언트별 토큰버킷
           없음 401 / 잘못 403          모르는 경로 404          초과 429 + Retry-After
```

기동 로그(실제):
```
io.gwanmun.gateway.auth.ApiKeyRegistry   : API 키 2개 로드: [demo-key-fintech-a, demo-key-fintech-b]
io.gwanmun.gateway.GatewayFilterConfig   : 관문 필터 체인 등록(순서): [AuthenticationFilter#10, RoutingFilter#20, RateLimitFilter#30]
```

### 1) 인증 — 키 없음 401 / 잘못된 키 403 (curl 실제 출력)

```
$ curl -i -X POST /api/gateway/balance -d '{"accountNo":"12345678901234"}'
HTTP/1.1 401
{"blocked":true,"status":401,"reason":"인증 실패: X-API-Key 헤더가 없습니다."}

$ curl -i -X POST /api/gateway/balance -H "X-API-Key: wrong-key" -d '...'
HTTP/1.1 403
{"blocked":true,"status":403,"reason":"인증 실패: 등록되지 않은 API 키입니다."}
```

### 2) 통과 — 정상 키로 계정계 왕복 성공(판정 헤더가 응답에 드러남)

```
$ curl -i -X POST /api/gateway/balance -H "X-API-Key: demo-key-fintech-a" -d '{"accountNo":"12345678901234"}'
HTTP/1.1 200
X-Gateway-Client: fintech-a
X-Gateway-Route: core-banking-balance
X-RateLimit-Remaining: 4
X-Gateway-Decision: pass
...
{"...","json":{"balance":"6879445000","responseCode":"0000","responseMessage":"정상 처리되었습니다"}, "core":"127.0.0.1:9099"}
```

인증→라우팅→유량제어를 다 통과한 뒤에야 Phase 2의 전문 왕복이 실행된다(문지기 통과 후 통역).

### 3) 유량제어 — 용량 5, N+1번째(6번째)에서 429 (fintech-b로 8회 연속, 실제 출력)

```
요청 1 → 200  (remaining: 4)  통과
요청 2 → 200  (remaining: 3)  통과
요청 3 → 200  (remaining: 2)  통과
요청 4 → 200  (remaining: 1)  통과
요청 5 → 200  (remaining: 0)  통과
요청 6 → 429  (Retry-After: 2s)  차단
요청 7 → 429  (Retry-After: 2s)  차단
요청 8 → 429  (Retry-After: 2s)  차단

마지막 429 바디: {"blocked":true,"status":429,"reason":"요청이 너무 잦습니다(클라이언트 'fintech-b' 분당 한도 초과). 2초 후 재시도하세요."}
```

### 4) 알 수 없는 라우트 → 404 (인증은 통과, 라우팅에서 차단)

```
$ curl -i -X POST /api/gateway/unknown -H "X-API-Key: demo-key-fintech-a" -d '{}'
HTTP/1.1 404
X-Gateway-Client: fintech-a        # 인증은 통과해 헤더가 찍힘
{"blocked":true,"status":404,"reason":"알 수 없는 라우트: POST /api/gateway/unknown"}
```

### 5) 화면

`docs/images/gateway-guard.png` — API 키·횟수를 정해 연속 전송하면 통과 5건(초록) → 429 차단 3건(빨강)이
줄줄이 찍히고, 합계가 뜬다. puppeteer-core 헤드리스 크롬 라이브 캡처(가짜 UI 아님).

### 6) 모듈러 모놀리스 — 경계를 코드가 강제

`ApplicationModules.of(GwanmunApplication.class).verify()`가 그린이다(순환참조·내부 타입 침범 없음).
Documenter로 생성한 모듈 다이어그램은 `docs/modules/`에 있다(components.puml, module-*.puml, module-*.adoc).

모듈 의존(생성된 components.puml 기준, 단방향 DAG):
```
web      → gateway, message
gateway  → message, core
core     → message
message  → (없음, 순수)
```

message 모듈은 dto·spec 하위 패키지를 @NamedInterface로만 공개하고 나머지는 내부에 감춘다.

### 7) 테스트

```
./gradlew test   # 52개 통과 (Phase 1~2: 37 + Phase 3: 15)
```

- `ModularityTest` (2) — 모듈 경계 verify(), 모듈 다이어그램 생성
- `GatewayFilterChainTest` (5) — 401·403·404·429·통과를 서블릿 없이 체인 단위로
- `TokenBucketTest` (3) — 보충·Retry-After·시계 역행 방어(가짜 시계)
- `RateLimitConcurrencyTest` (1) — 8스레드×100회가 같은 클라이언트 버킷을 쳐도 정확히 용량만큼만 통과
- `GatewayGuardIntegrationTest` (4) — 실제 HTTP로 401/403/200 왕복/429

### 잔여 (정직하게 안 함)

- **분산 환경 rate limit 공유 안 됨**: 토큰버킷은 단일 노드 인메모리다. 여러 인스턴스로 늘리면
  각자 세므로 전역 한도가 안 맞는다. 공유하려면 Redis 등 외부 저장소가 필요(확장 지점).
- **JWT/OAuth 미구현**: 인증은 정적 API 키 검증까지다. 만료·서명 검증(JWT)이나 OAuth 흐름은 범위 밖.
- **API 키 평문 보관**: 설정/인메모리에 평문. 실서비스라면 시크릿 스토어 + 해시 대조.
- **모듈러 모놀리스 ≠ 마이크로서비스**: 단일 배포 단위다. 모듈 경계가 코드로 강제될 뿐,
  프로세스·DB·배포는 하나다. 경계가 명확하니 훗날 쪼갤 수 있는 선택지를 남겨둔 것뿐.

---

## Phase 4 — 가변길이 전문(길이 헤더) · 커넥션 풀

Phase 2는 "고정 61byte" 전문만 프레이밍했고, 커넥션 풀과 길이 헤더(가변 전문)를 잔여로 남겼다.
Phase 4가 그 둘을 채운다 — 레코드가 건수만큼 붙어 길이가 매번 다른 거래내역 조회 전문을 4byte ASCII
길이 헤더로 2단계 프레이밍하고, 요청당 소켓을 새로 열던 클라이언트를 스레드 안전한 풀로 바꿔 재사용한다.

### 검증 환경

```
java -jar build/libs/gwanmun-0.1.0.jar    # 8090 + 내장 목업 계정계 2개
```

- 앱: 8090
- 잔액조회 계정계(고정 61byte): 9099
- 거래내역 계정계(가변, 길이 프리픽스): 9098
- 통신은 모두 실제 TCP 소켓.

### 1) 가변 전문 왕복 (curl, 실제 출력)

```
POST /api/history {"accountNo":"12345678901234","count":5}
```

응답 전선 309byte = 길이 헤더 4 + 본문 305(헤더 30 + 레코드 55 × 5). 앞 4byte가 길이 헤더다.

```
0000  30 33 30 35 ...    "0305" = 본문 305 byte 선언
...
"header": {"messageType":"0310","recordCount":"5","totalLength":"305","responseCode":"0000"},
"records": [ {"seq":"1","txType":"입금","amount":"477000","summary":"급여이체"}, ... 5건 ]
```

- 헤더 `30 33 30 35` = ASCII "0305". 305 = 30 + 55×5.
- txType·summary가 한글(EUC-KR)이라 레코드 슬라이스도 byte 오프셋으로 잘라 디코딩.
- 건수를 바꾸면 전체 길이가 따라 변한다(3건→본문 195, 10건→본문 580).

### 2) 커넥션 풀 재사용 (실제 출력)

순차 6회 — 첫 왕복만 소켓을 열고 이후 재사용(created 고정):

```
조회 1: created(신규 소켓)       pool[created=1 reused=0 idle=1]
조회 2: reused #1            pool[created=1 reused=1 idle=1]
...
조회 6: reused #5            pool[created=1 reused=5 idle=1]
```

동시 10회 폭주 — 최대 크기(4) 이상은 열지 않는다:

```
동시 폭주 후: created=4 (== max 4) · reused=12 · idle=4 · destroyed=0
```

잔액조회(고정 61byte) 풀도 재사용: 3회 조회 시 `created=1 reused=2`.

### 3) 프레이밍·풀 방어 — 테스트로 강제

- **헤더 반쪽**: 4byte 헤더 중 2byte만 오면 본문 길이를 못 읽어 대기(1단계).
- **본문 반쪽**: 헤더로 길이를 안 뒤 본문이 다 모여야 완성(2단계).
- **뭉침**: 길이 다른 세 전문이 붙어 와도 각각 분리.
- **비정상 길이 거절**: 헤더가 숫자 아님·상한 초과면 `MalformedFrameException`(fail-closed).
- **서버측 partial read**: 길이 헤더 중간·본문 중간에서 쪼갠 요청도 재조립.
- **풀 재사용**: 빌렸다 반납한 연결을 다시 빌리면 같은 객체(소켓).
- **풀 동시성**: 8스레드×50회가 최대 3짜리 풀을 쳐도 활성이 3을 절대 안 넘고, 만들어진 소켓도 3 이하.
- **풀 고갈**: 최대까지 다 나간 상태에서 borrow하면 대기 후 `PoolExhaustedException`.

### 4) 화면

`docs/images/variable-length-demo.png` — (5)가변 전문 왕복: 길이 헤더가 노랗게 강조된 hex 덤프 +
레코드 5건 표. (6)커넥션 풀: 활성/유휴/created/reused 카운터 + 순차 재사용·동시 최대 크기 로그.
puppeteer-core 헤드리스 크롬 라이브 캡처(가짜 UI 아님).

### 5) 테스트

```
./gradlew test   # 80개 통과 (Phase 1~3: 52 + Phase 4: 28)
```

- `LengthPrefixedFramerTest` (11) — 헤더/본문 반쪽·한 바이트씩·뭉침·빈 본문·비정상 길이 거절 등
- `VariableMessageCodecTest` (4) — 레코드 N건 왕복 무손실(한글)·가변 건수·잘린 전문 거절
- `ConnectionPoolTest` (7) — 재사용·죽은 연결 폐기·invalidate·고갈 거절·대기자 인계·동시성·닫힘
- `MockTransactionHistoryServerTest` (6) — 실제 소켓 가변 왕복·건수별 길이·결정론·풀 재사용·서버 partial·동시 8건
- `ModularityTest.verify()` 계속 그린(새 클래스도 올바른 모듈에). Documenter 다이어그램 갱신.

### 잔여 (정직하게 안 함)

- 길이 헤더는 4byte ASCII 십진수 한 종류(본문 최대 9999byte). 2/4byte 바이너리 헤더는 확장 지점.
- 풀은 최소 기능(최대 크기·유휴 반납·검증·고갈 거절). 유휴 최대 생존·주기 헬스체크·warm pool·누수 감지 없음.
- 파이프라이닝 없음(요청→응답 완료 후 다음). 가변 전문은 대표 한 종(중첩 가변·선택 필드 제외).
