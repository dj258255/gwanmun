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
