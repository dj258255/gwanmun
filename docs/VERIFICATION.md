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

---

## Phase 5 — 관측 가능한 거래 원장 (거래ID 채번 · 3값 상태 · 마스킹 · 메트릭)

Phase 4까지의 게이트웨이는 거래를 흘려보내기만 하고 아무것도 기억하지 못했다. Phase 5는 모든 거래에
거래고유번호를 채번하고, 결과를 3값 상태(SUCCESS/FAILED/UNKNOWN)로 원장(DB)에 비동기 적재한다.
핵심 규칙: **타임아웃 등 응답을 못 받은 거래는 임의로 실패 처리하지 않고 UNKNOWN으로 적는다.**

### 검증 환경

```
docker compose up -d        # 원장 DB: PostgreSQL 16 (호스트 포트 25432)
java -jar build/libs/gwanmun-0.1.0.jar --spring.profiles.active=postgres
```

- 앱 8090 + 내장 목업 계정계(9099·9098). 원장은 PostgreSQL 컨테이너에 적재(로컬 개발·테스트는 H2).
- 목업 잔액조회 계정계에 지연 모드 추가: 계좌 `99999999999999`면 응답을 5초 늦춘다
  (read 타임아웃 3초 < 5초 → 반드시 타임아웃).

### 1) 3값 상태 — 셋 다 실제로 기록 (curl 실제 출력)

**(a) 정상 거래 → SUCCESS** (거래ID가 응답에 노출)

```
POST /api/gateway/balance {"accountNo":"12345678901234"}   (X-Correlation-Id: demo-cid-success-1)
HTTP/1.1 200
X-Correlation-Id: demo-cid-success-1
{"transactionId":"GWMNU20260709105002301","ledgerStatus":"SUCCESS", ..., "elapsedMs":2}
```

**(b) 없는 계좌 → FAILED** (응답은 받았고 오류 코드 0001 — UNKNOWN이 아니다)

```
POST /api/gateway/balance {"accountNo":"0"}
HTTP/1.1 200
{"transactionId":"GWMNU20260709105002302","ledgerStatus":"FAILED",
 "json":{"responseCode":"0001","responseMessage":"없는 계좌입니다"}}
```

**(c) 지연 계좌 → 타임아웃 → UNKNOWN** (3.06초 소요 = read 타임아웃 3000ms에서 포기, HTTP 504)

```
POST /api/gateway/balance {"accountNo":"99999999999999"}   (X-Correlation-Id: demo-cid-timeout-1)
HTTP/1.1 504
X-Correlation-Id: demo-cid-timeout-1
{"error":"계정계(127.0.0.1:9099) 통신 실패: Read timed out",
 "transactionId":"GWMNU20260709105002303","ledgerStatus":"UNKNOWN"}
```

계정계(목업) 로그에는 요청이 도달해 처리된 흔적이 남는다 — "실패"라고 단정하면 틀리는 상황이다:

```
io.gwanmun.core.MockCoreBankingServer : 지연 모드 계좌 — 응답을 5000ms 늦춥니다(게이트웨이 타임아웃 유발용)
```

### 2) 원장 DB 실체 — PostgreSQL 행 (psql 실제 출력)

```
gwanmun=# SELECT transaction_id, status, response_code, elapsed_ms, detail
          FROM transaction_ledger WHERE status <> 'SUCCESS' ORDER BY id;
     transaction_id     | status  | response_code | elapsed_ms |                      detail
------------------------+---------+---------------+------------+--------------------------------------------------
 GWMNU20260709105002302 | FAILED  | 0001          |          0 | 없는 계좌입니다
 GWMNU20260709105002303 | UNKNOWN |               |       3011 | 계정계(127.0.0.1:9099) 통신 실패: Read timed out
```

- UNKNOWN 행의 `response_code`가 비어 있다 — 응답 자체를 못 받았다는 정직한 기록.
- `account_masked` 컬럼은 `123456****1234` 형태만 저장(마스킹 규칙: 앞6+뒤4만 노출). 원문 없음.
- 적재는 비동기(전용 스레드 + 유한 큐)라 거래 지연을 만들지 않고, DB가 죽어도 거래는 진행(WARN만).

### 3) correlation ID — 모든 로그 라인 + 응답 헤더 + 원장

수신 헤더 `X-Correlation-Id`가 있으면 승계, 없으면 생성해 MDC에 넣는다. 로그 패턴이 전 라인에 찍는다
(앱 로그 실제 출력 — 계좌도 마스킹돼 있다):

```
... [cid:demo-cid-success-1] io.gwanmun.gateway.GatewayService : 게이트웨이 왕복 완료: 계좌=123456****1234 응답코드=0000 잔액=6879445000 (2ms)
... [cid:c1d066748a7944d7] io.gwanmun.gateway.GatewayService : 게이트웨이 왕복 완료: 계좌=* 응답코드=0001 잔액=0 (0ms)
```

원장에도 같은 correlation ID가 저장돼, "이 504가 어느 요청이었나"를 앱 로그↔원장↔호출자 사이에서
한 줄로 꿸 수 있다.

### 4) 커스텀 메트릭 — /actuator/prometheus (실제 출력)

429를 2건 유발(fintech-b로 7연속)한 직후의 자체 구현물 메트릭:

```
gwanmun_core_roundtrip_seconds_count{tx="balance"} 7
gwanmun_ledger_transactions_total{status="FAILED"} 1.0
gwanmun_ledger_transactions_total{status="SUCCESS"} 7.0
gwanmun_ledger_transactions_total{status="UNKNOWN"} 1.0
gwanmun_pool_active{pool="core-banking"} 0.0
gwanmun_pool_idle{pool="core-banking"} 1.0
gwanmun_pool_opened_total{pool="core-banking"} 2.0
gwanmun_pool_reused_total{pool="core-banking"} 6.0
gwanmun_pool_destroyed_total{pool="core-banking"} 1.0
gwanmun_ratelimit_consumed_total{client="fintech-b"} 5.0
gwanmun_ratelimit_rejected_total{client="fintech-b"} 2.0
```

- `destroyed_total=1` — 타임아웃 난 소켓을 풀이 폐기한 것까지 메트릭에 드러난다.
- `opened_total=2, reused_total=6` — 소켓 2개로 8왕복(풀 재사용).
- 헬스는 liveness/readiness 분리: `/actuator/health/liveness`·`/actuator/health/readiness` 각각 UP.
- 종료는 graceful: SIGTERM 시 "Commencing graceful shutdown → complete" 후 풀·원장 스레드 정리(실측).

### 5) 화면

`docs/images/transaction-ledger.png` — 상태별 카운트(SUCCESS 7 · FAILED 1 · UNKNOWN 1)와 최근 거래 표
(거래ID·코드·3값 상태 색상·소요ms·마스킹된 계좌·correlation ID). UNKNOWN 행의 소요 3011ms가
타임아웃(3초)의 흔적이다. puppeteer-core 헤드리스 크롬 라이브 캡처(가짜 UI 아님).

### 6) 테스트

```
./gradlew test   # 104개 통과 (Phase 1~4: 80 + Phase 5: 24)
```

- `TransactionIdGeneratorTest` (3) — 형식(GWMN+U+날짜8+일련번호9), 16스레드×2000 동시 채번 무충돌,
  재기동 시드가 이전 발급 구간을 앞지름(주입 시계).
- `TransactionStatusTest` (6) — 0000→SUCCESS, 오류코드→FAILED, 타임아웃(중첩 포함)→UNKNOWN,
  EOF→UNKNOWN, 연결 거부→FAILED, null→FAILED.
- `TimeoutClassificationTest` (2) — 지연 모드 목업 + 짧은 read 타임아웃으로 **진짜 소켓 타임아웃**을
  일으켜 UNKNOWN 판정, 같은 서버의 일반 계좌는 정상.
- `AccountMaskerTest` (6) — 앞6+뒤4 규칙, 짧은 입력 보수적 마스킹, null 안전.
- `TransactionLedgerTest` (2) — 저장 직전 마스킹(원문 미영속), save가 예외를 던져도 record()는 안 던짐
  (적재 실패가 거래를 안 막음).
- `LedgerApiIntegrationTest` (5) — 실제 HTTP→소켓→비동기 적재 전 구간: SUCCESS/FAILED 기록,
  correlation ID 승계·생성, 프로메테우스 커스텀 메트릭, liveness/readiness.
- `ModularityTest.verify()` 계속 그린 — 새 `ledger` 모듈 포함 5모듈 단방향 DAG
  (web → gateway·message·core·ledger, ledger → message). Documenter 다이어그램 갱신.

### 잔여 (정직하게 안 함)

- **UNKNOWN 해소 없음**: 기록까지다. 망취소(취소 전문)·거래 상태 조회(대사)로 UNKNOWN을 확정 짓는
  흐름은 다음 단계.
- **멱등키 없음**: 같은 요청의 재시도를 게이트웨이가 구분하지 못한다(호출자가 거래ID를 들고 문의는
  가능하지만, 중복 실행 방지는 안 된다).
- **채번은 단일 노드 전제**: 다중 인스턴스면 노드 식별자나 중앙 채번이 필요.
- **JWT/OAuth 여전히 미구현**(Phase 3 잔여 그대로), 원장 보존 기한·파티셔닝·감사 추적(변경 이력) 없음.
