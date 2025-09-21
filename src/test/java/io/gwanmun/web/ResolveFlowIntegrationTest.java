package io.gwanmun.web;

import io.gwanmun.core.MockCoreBankingServer;
import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerView;
import io.gwanmun.ledger.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UNKNOWN 해소 플로우를 <b>실제 HTTP → 소켓 → 원장</b> 전 구간으로 검증한다 (Phase 6).
 *
 * <ul>
 *   <li>지연 계좌(계정계는 처리, 응답만 유실) → UNKNOWN → 해소: 상태조회 "처리됨" → 망취소 → CANCELED</li>
 *   <li>유실 계좌(계정계 미처리) → UNKNOWN → 해소: 상태조회 "미처리" → FAILED 확정</li>
 *   <li>UNKNOWN이 아닌 거래의 해소 시도 → 409</li>
 * </ul>
 *
 * <p>read 타임아웃·데드라인을 짧게 조여 테스트가 빨리 돌게 한다(목업 지연 5초보다 훨씬 짧게).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		// 다른 통합 테스트의 컨텍스트(9099/9098 점유)와 공존하도록 내장 목업 포트를 옮긴다.
		"gwanmun.core.port=19099",
		"gwanmun.core.history.port=19098",
		"gwanmun.core.read-timeout-ms=300",
		"gwanmun.core.resilience.transaction-deadline-ms=1200",
		"gwanmun.core.resilience.retry-max=1",
		"gwanmun.core.resilience.retry-backoff-ms=50",
})
class ResolveFlowIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TransactionLedger ledger;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> callBalance(String accountNo) throws IOException, InterruptedException {
		return http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/gateway/balance"))
				.header("Content-Type", "application/json")
				.header("X-API-Key", "demo-key-fintech-a")
				.POST(HttpRequest.BodyPublishers.ofString("{\"accountNo\":\"" + accountNo + "\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> callResolve(String tranId) throws IOException, InterruptedException {
		// 해소는 다른 클라이언트 키로 — 잔액조회가 쓴 토큰버킷을 소모하지 않게(테스트 안정성).
		return http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/gateway/resolve/" + tranId))
				.header("X-API-Key", "demo-key-fintech-b")
				.POST(HttpRequest.BodyPublishers.noBody())
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 비동기 적재라 바로 안 보일 수 있다 — 잠깐 폴링해 원장에서 해당 거래를 찾는다. */
	private Optional<LedgerView> awaitLedger(String transactionId) throws InterruptedException {
		for (int i = 0; i < 60; i++) {
			Optional<LedgerView> found = ledger.find(transactionId);
			if (found.isPresent()) {
				return found;
			}
			Thread.sleep(50);
		}
		return Optional.empty();
	}

	private static String extract(String body, String field) {
		int i = body.indexOf("\"" + field + "\":\"");
		if (i < 0) {
			return null;
		}
		int start = i + field.length() + 4;
		return body.substring(start, body.indexOf('"', start));
	}

	@Test
	@DisplayName("지연 계좌: UNKNOWN → 상태조회 '처리됨' → 망취소 → 원장 CANCELED(해소 이력 포함)")
	void unknownResolvedToCanceledViaNetCancel() throws Exception {
		// 1) 지연 계좌 — 계정계는 처리했지만 응답이 늦어 게이트웨이는 타임아웃(504·UNKNOWN).
		HttpResponse<String> res = callBalance(MockCoreBankingServer.DELAY_ACCOUNT);
		assertThat(res.statusCode()).isEqualTo(504);
		String txId = extract(res.body(), "transactionId");
		assertThat(res.body()).contains("\"ledgerStatus\":\"UNKNOWN\"");

		LedgerView before = awaitLedger(txId).orElseThrow();
		assertThat(before.status()).isEqualTo(TransactionStatus.UNKNOWN);

		// 2) 해소 — 상태조회가 "처리됨"을 답하므로 망취소까지 나가고 CANCELED로 확정된다.
		HttpResponse<String> resolve = callResolve(txId);
		assertThat(resolve.statusCode()).isEqualTo(200);
		assertThat(resolve.body())
				.contains("\"processedAtCore\":true")
				.contains("\"resolution\":\"NET_CANCELED\"")
				.contains("\"after\":\"CANCELED\"")
				.contains("\"resolutionMethod\":\"NET_CANCEL\"");

		// 3) 원장 실체 — 상태·해소 시각·방법이 남았다.
		LedgerView after = ledger.find(txId).orElseThrow();
		assertThat(after.status()).isEqualTo(TransactionStatus.CANCELED);
		assertThat(after.resolvedAt()).isNotNull();
		assertThat(after.resolutionMethod()).isEqualTo("NET_CANCEL");
	}

	@Test
	@DisplayName("유실 계좌: UNKNOWN → 상태조회 '미처리' → 망취소 없이 원장 FAILED 확정")
	void unknownResolvedToFailedWhenCoreNeverProcessed() throws Exception {
		// 1) 유실 계좌 — 계정계가 기록 없이 끊는다(EOF). 요청은 나갔으므로 UNKNOWN.
		HttpResponse<String> res = callBalance(MockCoreBankingServer.DROP_ACCOUNT);
		assertThat(res.statusCode()).isEqualTo(504);
		String txId = extract(res.body(), "transactionId");

		LedgerView before = awaitLedger(txId).orElseThrow();
		assertThat(before.status()).isEqualTo(TransactionStatus.UNKNOWN);

		// 2) 해소 — 계정계 "미처리" = 처리됐을 가능성 0. 이제야 FAILED로 확정할 수 있다.
		HttpResponse<String> resolve = callResolve(txId);
		assertThat(resolve.statusCode()).isEqualTo(200);
		assertThat(resolve.body())
				.contains("\"processedAtCore\":false")
				.contains("\"resolution\":\"CONFIRMED_UNPROCESSED\"")
				.contains("\"after\":\"FAILED\"")
				.contains("\"netCancel\":null"); // 미처리면 취소할 것이 없다 — 망취소 전문은 안 나간다.

		LedgerView after = ledger.find(txId).orElseThrow();
		assertThat(after.status()).isEqualTo(TransactionStatus.FAILED);
		assertThat(after.resolutionMethod()).isEqualTo("STATUS_INQUIRY");
	}

	@Test
	@DisplayName("UNKNOWN이 아닌 거래(SUCCESS)의 해소 시도 → 409(이미 확정된 거래는 건드리지 않는다)")
	void resolvingNonUnknownIsRejected() throws Exception {
		HttpResponse<String> res = callBalance("12345678901234");
		assertThat(res.statusCode()).isEqualTo(200);
		String txId = extract(res.body(), "transactionId");
		awaitLedger(txId).orElseThrow();

		HttpResponse<String> resolve = callResolve(txId);
		assertThat(resolve.statusCode()).isEqualTo(409);
		assertThat(resolve.body()).contains("UNKNOWN 거래만");
	}
}
