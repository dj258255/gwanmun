package io.gwanmun.web;

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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래 원장을 <b>실제 HTTP → 소켓 왕복 → 비동기 적재</b> 전 구간으로 검증한다.
 * 정상 거래가 SUCCESS로, 없는 계좌가 FAILED로 원장에 남고, 응답 JSON의 거래ID와 원장의 거래ID가
 * 같은 값인지, correlation ID가 응답 헤더로 돌아오고 원장에도 저장되는지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability // 테스트에서는 메트릭 익스포터가 기본 비활성 — 프로메테우스 검증을 위해 켠다.
class LedgerApiIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TransactionLedger ledger;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> callBalance(String accountNo, String correlationId)
			throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/gateway/balance"))
				.header("Content-Type", "application/json")
				.header("X-API-Key", "demo-key-fintech-a")
				.POST(HttpRequest.BodyPublishers.ofString("{\"accountNo\":\"" + accountNo + "\"}"));
		if (correlationId != null) {
			b.header("X-Correlation-Id", correlationId);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 비동기 적재라 바로 안 보일 수 있다 — 잠깐 폴링해 원장에서 해당 거래ID를 찾는다. */
	private Optional<LedgerView> awaitLedgerEntry(String transactionId) throws InterruptedException {
		for (int i = 0; i < 40; i++) {
			Optional<LedgerView> found = ledger.recent(50).stream()
					.filter(v -> v.transactionId().equals(transactionId))
					.findFirst();
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
	@DisplayName("정상 거래: 응답에 거래ID가 오고, 원장에 SUCCESS + 마스킹된 계좌 + correlation ID가 남는다")
	void successIsRecorded() throws Exception {
		HttpResponse<String> res = callBalance("12345678901234", "it-test-success-01");

		assertThat(res.statusCode()).isEqualTo(200);
		String txId = extract(res.body(), "transactionId");
		assertThat(txId).matches("GWMNU\\d{17}");
		assertThat(res.body()).contains("\"ledgerStatus\":\"SUCCESS\"");
		// correlation ID 승계 — 보낸 값이 응답 헤더로 그대로 돌아온다.
		assertThat(res.headers().firstValue("X-Correlation-Id")).contains("it-test-success-01");

		LedgerView entry = awaitLedgerEntry(txId).orElseThrow();
		assertThat(entry.status()).isEqualTo(TransactionStatus.SUCCESS);
		assertThat(entry.accountMasked()).isEqualTo("123456****1234"); // 원문이 원장에 없다.
		assertThat(entry.correlationId()).isEqualTo("it-test-success-01");
		assertThat(entry.txCode()).isEqualTo("IN01");
	}

	@Test
	@DisplayName("없는 계좌(응답코드 0001): 응답은 받았으므로 UNKNOWN이 아니라 FAILED로 남는다")
	void errorResponseIsFailed() throws Exception {
		HttpResponse<String> res = callBalance("0", null);

		assertThat(res.statusCode()).isEqualTo(200); // HTTP 왕복 자체는 성공(계정계가 오류 응답을 준 것).
		assertThat(res.body()).contains("\"ledgerStatus\":\"FAILED\"");
		String txId = extract(res.body(), "transactionId");

		LedgerView entry = awaitLedgerEntry(txId).orElseThrow();
		assertThat(entry.status()).isEqualTo(TransactionStatus.FAILED);
		assertThat(entry.responseCode()).isEqualTo("0001");
	}

	@Test
	@DisplayName("correlation ID를 안 보내면 게이트웨이가 생성해 응답 헤더로 돌려준다")
	void correlationIdGeneratedWhenAbsent() throws Exception {
		HttpResponse<String> res = http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/ledger/summary")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(res.statusCode()).isEqualTo(200);
		Optional<String> cid = res.headers().firstValue("X-Correlation-Id");
		assertThat(cid).isPresent();
		assertThat(cid.get()).matches("[0-9a-f]{16}");
		// summary는 3값 키를 항상 노출한다.
		assertThat(res.body()).contains("SUCCESS").contains("FAILED").contains("UNKNOWN").contains("TOTAL");
	}

	@Test
	@DisplayName("/actuator/prometheus 에 자체 구현 커스텀 메트릭이 노출된다")
	void prometheusExposesCustomMetrics() throws Exception {
		HttpResponse<String> res = http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/actuator/prometheus")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body())
				.contains("gwanmun_pool_active")       // 커넥션 풀 게이지(현재값)
				.contains("gwanmun_pool_opened_total") // 누적 카운터
				.contains("gwanmun_pool_reused_total");
	}

	@Test
	@DisplayName("/actuator/health 의 liveness/readiness 프로브가 분리 노출된다")
	void healthProbes() throws Exception {
		HttpResponse<String> live = http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/actuator/health/liveness")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		HttpResponse<String> ready = http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/actuator/health/readiness")).GET().build(),
				HttpResponse.BodyHandlers.ofString());

		assertThat(live.statusCode()).isEqualTo(200);
		assertThat(live.body()).contains("UP");
		assertThat(ready.statusCode()).isEqualTo(200);
		assertThat(ready.body()).contains("UP");
	}
}
