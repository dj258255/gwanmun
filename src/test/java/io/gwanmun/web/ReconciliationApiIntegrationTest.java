package io.gwanmun.web;

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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EOD 대사를 <b>실제 HTTP → 소켓 왕복 → 원장</b> 전 구간으로 검증한다(Phase 9). 정상 거래는 계정계와
 * 일치(MATCH)로 잡히고, 지연 계좌가 만든 UNKNOWN은 대사가 상태조회·망취소로 자동 해소해 원장을
 * CANCELED로 확정한 뒤 계정계(취소됨)와 일치로 수렴한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"gwanmun.core.port=49099",
		"gwanmun.core.history.port=49098",
		"gwanmun.core.settlement.port=49097",
		// 지연 계좌가 read 타임아웃을 넘겨 UNKNOWN이 되게(계정계는 처리·기록함).
		"gwanmun.core.mock.delay-ms=800",
		"gwanmun.core.read-timeout-ms=250",
		"gwanmun.core.resilience.transaction-deadline-ms=500",
		"gwanmun.core.resilience.retry-max=0",
		"gwanmun.gateway.rate-capacity=1000000"
})
class ReconciliationApiIntegrationTest {

	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	@LocalServerPort
	private int port;

	@Autowired
	private TransactionLedger ledger;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> balance(String accountNo) throws IOException, InterruptedException {
		return http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/gateway/balance"))
				.header("Content-Type", "application/json")
				.header("X-API-Key", "demo-key-fintech-a")
				.POST(HttpRequest.BodyPublishers.ofString("{\"accountNo\":\"" + accountNo + "\"}"))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> runReconciliation(String date) throws IOException, InterruptedException {
		return http.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/reconciliation/run?date=" + date))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String tranIdOf(String body) {
		int i = body.indexOf("\"transactionId\":\"");
		int start = i + "\"transactionId\":\"".length();
		return body.substring(start, body.indexOf('"', start));
	}

	private Optional<LedgerView> awaitLedger(String tranId) throws InterruptedException {
		for (int i = 0; i < 50; i++) {
			Optional<LedgerView> f = ledger.recent(100).stream()
					.filter(v -> v.transactionId().equals(tranId)).findFirst();
			if (f.isPresent()) {
				return f;
			}
			Thread.sleep(50);
		}
		return Optional.empty();
	}

	@Test
	@DisplayName("정상 거래는 MATCH, 지연 계좌 UNKNOWN은 대사가 자동 해소해 CANCELED로 일치시킨다")
	void reconcilesAndAutoResolvesUnknown() throws Exception {
		String today = LocalDate.now().format(YYYYMMDD);

		// 정상 거래 → SUCCESS(계정계와 금액 일치).
		String okTran = tranIdOf(balance("12345678901234").body());
		// 지연 계좌 → 504 UNKNOWN(계정계는 처리·기록함).
		HttpResponse<String> slow = balance("99999999999999");
		assertThat(slow.statusCode()).isEqualTo(504);
		String slowTran = tranIdOf(slow.body());

		assertThat(awaitLedger(okTran)).hasValueSatisfying(v ->
				assertThat(v.status()).isEqualTo(TransactionStatus.SUCCESS));
		assertThat(awaitLedger(slowTran)).hasValueSatisfying(v ->
				assertThat(v.status()).isEqualTo(TransactionStatus.UNKNOWN));

		// EOD 대사 실행.
		HttpResponse<String> recon = runReconciliation(today);
		assertThat(recon.statusCode()).isEqualTo(200);
		assertThat(recon.body()).contains("\"UNKNOWN_RESOLVED\"");

		// 지연 계좌 UNKNOWN이 CANCELED로 자동 해소됐다.
		Optional<LedgerView> slowAfter = ledger.find(slowTran);
		assertThat(slowAfter).hasValueSatisfying(v -> {
			assertThat(v.status()).isEqualTo(TransactionStatus.CANCELED);
			assertThat(v.resolutionMethod()).isEqualTo("NET_CANCEL");
		});
		// 리포트: UNKNOWN 해소 최소 1건, 정상 거래는 일치로 잡힘.
		assertThat(recon.body()).contains("\"MATCH\"");
		assertThat(recon.body()).contains(okTran);
	}
}
