package io.gwanmun.gateway.filter;

import io.gwanmun.gateway.auth.ApiKeyRegistry;
import org.springframework.stereotype.Component;

/**
 * 인증 필터 — 체인의 첫 마디. {@code X-API-Key} 헤더를 보고 등록된 클라이언트만 들여보낸다.
 *
 * <ul>
 *   <li>헤더가 <b>없으면</b> 401(누구인지 밝히지 않았다).</li>
 *   <li>헤더는 있는데 <b>등록되지 않은 키</b>면 403(밝혔지만 권한 없다).</li>
 *   <li>유효하면 클라이언트 id를 요청에 실어 다음 필터로 넘긴다(라우팅·유량제어가 이 id를 쓴다).</li>
 * </ul>
 */
@Component
public class AuthenticationFilter implements GatewayFilter {

	public static final String API_KEY_HEADER = "X-API-Key";

	private final ApiKeyRegistry registry;

	public AuthenticationFilter(ApiKeyRegistry registry) {
		this.registry = registry;
	}

	@Override
	public void filter(GatewayRequest request, GatewayResponse response, GatewayFilterChain chain) {
		String apiKey = request.header(API_KEY_HEADER);
		if (apiKey == null || apiKey.isBlank()) {
			response.block(401, "인증 실패: " + API_KEY_HEADER + " 헤더가 없습니다.");
			return;
		}
		String clientId = registry.clientFor(apiKey);
		if (clientId == null) {
			response.block(403, "인증 실패: 등록되지 않은 API 키입니다.");
			return;
		}
		request.clientId(clientId);
		response.header("X-Gateway-Client", clientId); // 통과: 누구로 인증됐는지 응답에 드러낸다.
		chain.next(request, response);
	}

	@Override
	public int order() {
		return 10;
	}
}
