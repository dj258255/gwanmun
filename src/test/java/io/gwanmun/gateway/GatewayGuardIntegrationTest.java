package io.gwanmun.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관문 필터 체인을 <b>실제 서블릿 파이프라인</b>에 얹어 HTTP로 검증한다. 앱을 임의 포트로 띄우고
 * (내장 목업 계정계 포함), /api/gateway/balance 로 진짜 요청을 쏴 401/403/200/429를 확인한다.
 * 유량제어는 클라이언트별로 독립이므로 테스트마다 다른 키를 써 서로 간섭하지 않게 한다.
 *
 * <p>JDK의 java.net.http.HttpClient를 쓴다(HttpURLConnection은 POST 바디 스트리밍 중 401을 받으면
 * HttpRetryException을 던지는 오래된 함정이 있어 피한다).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayGuardIntegrationTest {

	@LocalServerPort
	private int port;

	private final HttpClient client = HttpClient.newHttpClient();

	private HttpResponse<String> callBalance(String apiKey) throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/gateway/balance"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"accountNo\":\"12345678901234\"}"));
		if (apiKey != null) {
			b.header("X-API-Key", apiKey);
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@DisplayName("키 없이 호출하면 401, 백엔드로 넘어가지 않는다")
	void missingKey_401() throws Exception {
		HttpResponse<String> res = callBalance(null);
		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(res.body()).contains("X-API-Key");
	}

	@Test
	@DisplayName("잘못된 키면 403")
	void wrongKey_403() throws Exception {
		HttpResponse<String> res = callBalance("nope");
		assertThat(res.statusCode()).isEqualTo(403);
	}

	@Test
	@DisplayName("정상 키면 통과해 계정계 왕복 결과(응답코드 0000)와 인증 헤더가 온다")
	void validKey_roundTrip() throws Exception {
		HttpResponse<String> res = callBalance("demo-key-fintech-a");
		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).contains("\"responseCode\":\"0000\"");
		assertThat(res.headers().firstValue("X-Gateway-Client")).contains("fintech-a");
		assertThat(res.headers().firstValue("X-Gateway-Route")).contains("core-banking-balance");
	}

	@Test
	@DisplayName("빠르게 연속 호출하면 용량(5) 소진 후 429 + Retry-After가 뜬다")
	void rapidFire_429() throws Exception {
		int successes = 0;
		HttpResponse<String> lastBlocked = null;
		// 용량 5를 넘겨 확실히 429를 유발한다. fintech-b 버킷은 이 테스트만 쓴다.
		for (int i = 0; i < 8; i++) {
			HttpResponse<String> res = callBalance("demo-key-fintech-b");
			if (res.statusCode() == 429) {
				lastBlocked = res;
			} else if (res.statusCode() == 200) {
				successes++;
			}
		}
		assertThat(successes).isGreaterThan(0).isLessThanOrEqualTo(5);
		assertThat(lastBlocked).as("연속 호출 끝에 429가 최소 한 번은 떠야 한다").isNotNull();
		assertThat(lastBlocked.headers().firstValue("Retry-After")).isPresent();
		assertThat(lastBlocked.body()).contains("429");
	}
}
