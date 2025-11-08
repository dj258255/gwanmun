package io.gwanmun.web;

import io.gwanmun.ledger.TransactionLedger;
import io.gwanmun.ledger.TransactionLedger.LedgerView;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등키를 <b>실제 HTTP</b>로 검증한다(Phase 9). 핵심은 <b>이중 거래 0</b> —
 * 같은 멱등키로 두 번 이체해도 계정계는 한 번만 처리되고, 원장에 거래가 하나만 남으며,
 * 두 번째 응답은 저장된 원응답을 <b>재실행 없이</b> 되돌려받는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"gwanmun.core.port=39099",
		"gwanmun.core.history.port=39098",
		"gwanmun.core.settlement.port=39097",
		// 지연 계좌로 "처리 중" 창을 넓혀 동시 재요청 409를 안정적으로 재현한다.
		"gwanmun.core.mock.delay-ms=3000",
		"gwanmun.core.read-timeout-ms=1500",
		"gwanmun.core.resilience.transaction-deadline-ms=2000",
		"gwanmun.core.resilience.retry-max=0",
		"gwanmun.gateway.rate-capacity=1000000" // 유량제어가 동시 요청을 가리지 않게
})
class IdempotencyApiIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private TransactionLedger ledger;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> balance(String accountNo, String idempotencyKey)
			throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/gateway/balance"))
				.header("Content-Type", "application/json")
				.header("X-API-Key", "demo-key-fintech-a")
				.POST(HttpRequest.BodyPublishers.ofString("{\"accountNo\":\"" + accountNo + "\"}"));
		if (idempotencyKey != null) {
			b.header("Idempotency-Key", idempotencyKey);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String tranIdOf(String body) {
		int i = body.indexOf("\"transactionId\":\"");
		if (i < 0) {
			return null;
		}
		int start = i + "\"transactionId\":\"".length();
		return body.substring(start, body.indexOf('"', start));
	}

	private long countLedger(String tranId) {
		return ledger.recent(100).stream().filter(v -> tranId.equals(v.transactionId())).count();
	}

	@Test
	@DisplayName("같은 멱등키로 두 번 → 계정계 1회·원장 1건·같은 원응답 재반환(이중 거래 0)")
	void sameKeyReplaysWithoutReexecuting() throws Exception {
		String key = "idem-" + System.nanoTime();
		HttpResponse<String> first = balance("12345678901234", key);
		HttpResponse<String> second = balance("12345678901234", key);

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.headers().firstValue("X-Idempotent-Replay")).hasValue("false");
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(second.headers().firstValue("X-Idempotent-Replay")).hasValue("true");

		String tranId = tranIdOf(first.body());
		assertThat(tranId).isNotNull();
		// 두 번째는 저장된 원응답 그대로 — 같은 거래ID, 같은 본문(재실행 안 함).
		assertThat(tranIdOf(second.body())).isEqualTo(tranId);
		assertThat(second.body()).isEqualTo(first.body());

		// 원장에 이 거래가 정확히 하나 — 재전송이 새 거래를 만들지 않았다.
		Thread.sleep(300); // 비동기 적재 반영 대기
		assertThat(countLedger(tranId)).isEqualTo(1);
	}

	@Test
	@DisplayName("멱등키 없으면 매 요청이 새 거래 — 재전송이 곧 이중 거래(대조군)")
	void withoutKeyEachRequestIsNewTransaction() throws Exception {
		HttpResponse<String> a = balance("12345678901234", null);
		HttpResponse<String> b = balance("12345678901234", null);

		assertThat(a.statusCode()).isEqualTo(200);
		assertThat(b.statusCode()).isEqualTo(200);
		assertThat(tranIdOf(a.body())).isNotEqualTo(tranIdOf(b.body()));
	}

	@Test
	@DisplayName("같은 키에 다른 본문 → 422(멱등키 재사용 불가)")
	void differentPayloadSameKeyRejected() throws Exception {
		String key = "idem-mismatch-" + System.nanoTime();
		HttpResponse<String> first = balance("12345678901234", key);
		HttpResponse<String> second = balance("99998888777766", key);

		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(second.statusCode()).isEqualTo(422);
		assertThat(second.body()).contains("멱등키");
	}

	@Test
	@DisplayName("처리 중 동시 재요청 → 하나만 처리, 나머지는 409")
	void concurrentSameKeyConflicts() throws Exception {
		String key = "idem-conc-" + System.nanoTime();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			// 지연 계좌 — 첫 요청이 ~1.5초 붙잡혀 있는 동안 두 번째가 도착한다.
			Callable<HttpResponse<String>> call = () -> balance("99999999999999", key);
			Future<HttpResponse<String>> f1 = pool.submit(call);
			Thread.sleep(150); // 첫 요청이 IN_PROGRESS를 선점하도록 살짝 뒤에 두 번째.
			Future<HttpResponse<String>> f2 = pool.submit(call);

			List<Integer> codes = List.of(f1.get().statusCode(), f2.get().statusCode());
			// 하나는 처리(지연 계좌라 504 UNKNOWN), 다른 하나는 처리 중이라 409.
			assertThat(codes).contains(409);
			assertThat(codes).anyMatch(c -> c == 504 || c == 200);
		} finally {
			pool.shutdownNow();
		}
	}
}
