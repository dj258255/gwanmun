package io.gwanmun.web;

import io.gwanmun.core.MockCoreBankingServer;
import io.gwanmun.core.MockTransactionHistoryServer;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerView;
import io.gwanmun.ledger.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 감사 결함의 회귀 테스트 — HTTP → 소켓 → 원장 전 구간을 실제로 태워 고정한다.
 *
 * <ul>
 *   <li><b>A1</b> 풀 고갈: 고갈 요청이 원장에 FAILED 로 남고, 클라이언트는 503을 받고,
 *       서킷은 CLOSED 를 유지한다(내부 사정은 백엔드 장애가 아니다).</li>
 *   <li><b>A2</b> 계정계 이상 응답(fault 계좌): 500 관통·원장 공백 대신 502 + 원장 FAILED.</li>
 *   <li><b>B2</b> /api/history 가 관문 필터 체인 안이다(무인증 401).</li>
 *   <li><b>B4</b> 실패 응답이 내부 host:port·예외 원문·입력 원문을 노출하지 않는다.</li>
 * </ul>
 *
 * <p>내장 목업을 전용 포트(29099/29098)에 띄우고 지연을 800ms 로 줄인 별도 컨텍스트다 —
 * 공유 컨텍스트(9099/9098)·해소 플로우 컨텍스트(19099/19098)와 포트가 겹치지 않아 나란히 돈다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"gwanmun.core.port=29099",
		"gwanmun.core.history.port=29098",
		"gwanmun.core.settlement.port=29097",
		"gwanmun.core.mock.delay-ms=800",       // 지연 계좌가 read 타임아웃(3s) 안에 성공하게 — 서킷 오염 방지
		"gwanmun.core.pool.max-size=1",          // 풀 고갈을 요청 2건으로 재현
		"gwanmun.core.pool.borrow-timeout-ms=100",
		"gwanmun.gateway.rate-capacity=50"       // 유량제어가 이 검증을 가리지 않게 여유
})
@AutoConfigureObservability
class AuditRegressionIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TransactionLedger ledger;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> post(String path, String body, String apiKey)
			throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (apiKey != null) {
			b.header("X-API-Key", apiKey);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String extract(String body, String field) {
		int i = body.indexOf("\"" + field + "\":\"");
		if (i < 0) {
			return null;
		}
		int start = i + field.length() + 4;
		return body.substring(start, body.indexOf('"', start));
	}

	/** 비동기 적재라 바로 안 보일 수 있다 — 잠깐 폴링해 원장에서 해당 거래ID를 찾는다. */
	private Optional<LedgerView> awaitLedgerEntry(String transactionId) throws InterruptedException {
		for (int i = 0; i < 40; i++) {
			Optional<LedgerView> found = ledger.recent(100).stream()
					.filter(v -> v.transactionId().equals(transactionId))
					.findFirst();
			if (found.isPresent()) {
				return found;
			}
			Thread.sleep(50);
		}
		return Optional.empty();
	}

	@Test
	@DisplayName("A1 회귀: 동시 요청 풀 고갈 → 고갈 요청이 503 + 원장 FAILED, 서킷은 CLOSED 유지(오보 없음)")
	void poolExhaustionRecordsFailedAndKeepsCircuitClosed() throws Exception {
		int concurrent = 3; // 풀 최대 1 — 1건이 지연 계좌(800ms)를 쥐고, 2건이 100ms 대기 후 고갈 거절
		ExecutorService pool = Executors.newFixedThreadPool(concurrent);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<HttpResponse<String>>> futures = new ArrayList<>();
		try {
			for (int i = 0; i < concurrent; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return post("/api/gateway/balance",
							"{\"accountNo\":\"" + MockCoreBankingServer.DELAY_ACCOUNT + "\"}",
							"demo-key-fintech-a");
				}));
			}
			start.countDown();

			int ok = 0;
			List<HttpResponse<String>> exhausted = new ArrayList<>();
			for (Future<HttpResponse<String>> f : futures) {
				HttpResponse<String> res = f.get(15, TimeUnit.SECONDS);
				if (res.statusCode() == 200) {
					ok++;
				} else {
					exhausted.add(res);
				}
			}

			// 풀을 쥔 요청은 성공하고, 못 쥔 요청은 500 이 아니라 503 으로 거절된다.
			assertThat(ok).isGreaterThanOrEqualTo(1);
			assertThat(exhausted).isNotEmpty();
			for (HttpResponse<String> res : exhausted) {
				assertThat(res.statusCode()).isEqualTo(503);
				assertThat(res.body()).contains("포화");
				// B4: 내부 host:port·예외 클래스명이 밖으로 안 나간다.
				assertThat(res.body()).doesNotContain("127.0.0.1").doesNotContain("Exception");

				// 수정 전에는 이 거래가 원장에서 통째로 증발했다 — FAILED 로 남아야 한다.
				String txId = extract(res.body(), "transactionId");
				assertThat(txId).isNotNull();
				LedgerView entry = awaitLedgerEntry(txId).orElseThrow(
						() -> new AssertionError("고갈 거래가 원장에 없습니다: " + txId));
				assertThat(entry.status()).isEqualTo(TransactionStatus.FAILED);
				assertThat(entry.detail()).contains("풀 고갈");
			}

			// 수정 전에는 고갈 3연속이 서킷을 열었다(계정계는 멀쩡한데) — CLOSED 여야 한다.
			HttpResponse<String> circuit = http.send(HttpRequest.newBuilder(
							URI.create("http://localhost:" + port + "/api/circuit/stats")).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(circuit.body()).contains("\"name\":\"core-banking\",\"state\":\"CLOSED\"");

			// 그리고 멀쩡한 계좌는 즉시 정상 처리된다(서킷 오보였다면 여기서 503이 났다).
			HttpResponse<String> healthy = post("/api/gateway/balance",
					"{\"accountNo\":\"12345678901234\"}", "demo-key-fintech-a");
			assertThat(healthy.statusCode()).isEqualTo(200);
			assertThat(healthy.body()).contains("\"ledgerStatus\":\"SUCCESS\"");
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("A2 회귀: fault 계좌(쓰레기 응답) → 500 관통·원장 공백 대신 502 + 원장 FAILED")
	void garbageCoreResponseRecordsFailedAndReturns502() throws Exception {
		HttpResponse<String> res = post("/api/history",
				"{\"accountNo\":\"" + MockTransactionHistoryServer.FAULT_TRUNCATED_ACCOUNT + "\",\"count\":3}",
				"demo-key-fintech-a");

		assertThat(res.statusCode()).isEqualTo(502);
		// B4: 외부 응답은 일반화 — 내부 예외 원문·host:port 미노출, correlationId 로 추적.
		assertThat(res.body()).contains("계정계 처리에 실패").doesNotContain("127.0.0.1")
				.doesNotContain("Exception");
		assertThat(res.body()).contains("correlationId");

		String txId = extract(res.body(), "transactionId");
		LedgerView entry = awaitLedgerEntry(txId).orElseThrow(
				() -> new AssertionError("fault 거래가 원장에 없습니다: " + txId));
		assertThat(entry.status()).isEqualTo(TransactionStatus.FAILED);
		assertThat(entry.detail()).contains("왕복은 성공");
	}

	@Test
	@DisplayName("A2 회귀: fault 계좌(쓰레기 건수 필드) — NumberFormatException 관통도 502 + 원장 FAILED")
	void garbageCountFieldAlsoRecordsFailed() throws Exception {
		HttpResponse<String> res = post("/api/history",
				"{\"accountNo\":\"" + MockTransactionHistoryServer.FAULT_GARBAGE_COUNT_ACCOUNT + "\",\"count\":3}",
				"demo-key-fintech-a");

		assertThat(res.statusCode()).isEqualTo(502);
		String txId = extract(res.body(), "transactionId");
		LedgerView entry = awaitLedgerEntry(txId).orElseThrow();
		assertThat(entry.status()).isEqualTo(TransactionStatus.FAILED);
	}

	@Test
	@DisplayName("B2 회귀: /api/history 는 이제 관문 안 — 키 없으면 401, 키 있으면 라우트 헤더와 함께 통과")
	void historyIsBehindGatewayGuard() throws Exception {
		HttpResponse<String> noKey = post("/api/history", "{\"accountNo\":\"12345678901234\"}", null);
		assertThat(noKey.statusCode()).isEqualTo(401);

		HttpResponse<String> withKey = post("/api/history",
				"{\"accountNo\":\"12345678901234\",\"count\":2}", "demo-key-fintech-b");
		assertThat(withKey.statusCode()).isEqualTo(200);
		assertThat(withKey.headers().firstValue("X-Gateway-Route")).contains("txn-history");
		assertThat(withKey.headers().firstValue("X-Gateway-Client")).contains("fintech-b");
	}

	@Test
	@DisplayName("B4 회귀: 400 응답이 입력 원문을 에코하지 않는다")
	void badRequestDoesNotEchoRawInput() throws Exception {
		HttpResponse<String> res = post("/api/gateway/balance",
				"{\"accountNo\":\"12ab<script>x\"}", "demo-key-fintech-a");
		assertThat(res.statusCode()).isEqualTo(400);
		assertThat(res.body()).doesNotContain("12ab<script>x").doesNotContain("<script>");
		assertThat(res.body()).contains("숫자 1~14자리");
	}
}
