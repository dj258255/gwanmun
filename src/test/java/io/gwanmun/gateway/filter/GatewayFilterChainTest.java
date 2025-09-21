package io.gwanmun.gateway.filter;

import io.gwanmun.gateway.GatewayProperties;
import io.gwanmun.gateway.auth.ApiKeyRegistry;
import io.gwanmun.gateway.route.RouteTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 손으로 짠 필터 체인을 서블릿 없이 검증한다. 인증 → 라우팅 → 유량제어를 순서대로 태워,
 * 각 단계의 통과/차단(401·403·404·429·통과)이 정확히 나오는지 본다.
 */
class GatewayFilterChainTest {

	private static final String VALID_KEY = "demo-key-fintech-a";
	private static final String BALANCE_PATH = "/api/gateway/balance";

	/** 고정 시계: 시간이 흐르지 않아 토큰버킷 보충이 없다(용량만큼만 통과). */
	private static final LongSupplier FROZEN_CLOCK = () -> 0L;

	private ApiKeyRegistry registry() {
		GatewayProperties props = new GatewayProperties();
		props.setApiKeys(Map.of(VALID_KEY, "fintech-a"));
		return new ApiKeyRegistry(props);
	}

	private List<GatewayFilter> chain(double rateCapacity) {
		return List.of(
				new AuthenticationFilter(registry()),
				new RoutingFilter(new RouteTable()),
				new RateLimitFilter(rateCapacity, 0.0001, FROZEN_CLOCK));
	}

	private GatewayResponse run(GatewayRequest req, List<GatewayFilter> filters) {
		List<GatewayFilter> ordered = filters.stream()
				.sorted(Comparator.comparingInt(GatewayFilter::order))
				.toList();
		GatewayResponse res = new GatewayResponse();
		new GatewayFilterChain(ordered).next(req, res);
		return res;
	}

	private GatewayRequest request(String method, String path, Map<String, String> headers) {
		Map<String, String> lower = new LinkedHashMap<>();
		headers.forEach((k, v) -> lower.put(k.toLowerCase(), v));
		return new GatewayRequest(method, path, lower);
	}

	@Test
	@DisplayName("API 키가 없으면 401에서 끊긴다(라우팅·유량제어까지 가지 않음)")
	void missingKey_returns401() {
		GatewayResponse res = run(request("POST", BALANCE_PATH, Map.of()), chain(5));

		assertThat(res.blocked()).isTrue();
		assertThat(res.status()).isEqualTo(401);
		assertThat(res.reason()).contains("X-API-Key");
		assertThat(res.headers()).doesNotContainKey("X-Gateway-Route"); // 라우팅 필터에 닿지 않았다.
	}

	@Test
	@DisplayName("등록되지 않은 API 키면 403")
	void wrongKey_returns403() {
		GatewayResponse res = run(
				request("POST", BALANCE_PATH, Map.of("X-API-Key", "bogus-key")), chain(5));

		assertThat(res.blocked()).isTrue();
		assertThat(res.status()).isEqualTo(403);
		assertThat(res.reason()).contains("등록되지 않은");
	}

	@Test
	@DisplayName("인증은 통과했지만 알 수 없는 경로면 404")
	void unknownRoute_returns404() {
		GatewayResponse res = run(
				request("POST", "/api/gateway/unknown", Map.of("X-API-Key", VALID_KEY)), chain(5));

		assertThat(res.blocked()).isTrue();
		assertThat(res.status()).isEqualTo(404);
		assertThat(res.headers()).containsKey("X-Gateway-Client"); // 인증은 통과해 헤더가 찍혔다.
		assertThat(res.headers()).doesNotContainKey("X-Gateway-Route");
	}

	@Test
	@DisplayName("경로 변수 라우트(/resolve/{tranId})는 접두어 매칭으로 통과한다 (Phase 6)")
	void resolveRouteMatchesByPrefix() {
		GatewayResponse res = run(
				request("POST", "/api/gateway/resolve/GWMNU20260709000000001",
						Map.of("X-API-Key", VALID_KEY)), chain(5));

		assertThat(res.blocked()).isFalse();
		assertThat(res.headers()).containsEntry("X-Gateway-Route", "core-banking-resolve");
	}

	@Test
	@DisplayName("정상 키 + 아는 경로면 통과하고, 통과 흔적 헤더가 남는다")
	void validRequest_passes() {
		GatewayResponse res = run(
				request("POST", BALANCE_PATH, Map.of("X-API-Key", VALID_KEY)), chain(5));

		assertThat(res.blocked()).isFalse();
		assertThat(res.headers()).containsEntry("X-Gateway-Client", "fintech-a");
		assertThat(res.headers()).containsKey("X-Gateway-Route");
		assertThat(res.headers()).containsKey("X-RateLimit-Remaining");
	}

	@Test
	@DisplayName("용량 N을 넘는 N+1번째 요청은 429 + Retry-After")
	void rateLimit_blocksAfterCapacity() {
		int capacity = 3;
		List<GatewayFilter> chain = chain(capacity);

		// 앞의 N건은 통과.
		for (int i = 1; i <= capacity; i++) {
			GatewayResponse ok = run(
					request("POST", BALANCE_PATH, Map.of("X-API-Key", VALID_KEY)), chain);
			assertThat(ok.blocked()).as("%d번째 요청은 통과해야 한다", i).isFalse();
		}

		// N+1번째에서 막힌다.
		GatewayResponse blocked = run(
				request("POST", BALANCE_PATH, Map.of("X-API-Key", VALID_KEY)), chain);

		assertThat(blocked.blocked()).isTrue();
		assertThat(blocked.status()).isEqualTo(429);
		assertThat(blocked.headers()).containsKey("Retry-After");
		assertThat(blocked.headers()).containsEntry("X-RateLimit-Remaining", "0");
	}
}
