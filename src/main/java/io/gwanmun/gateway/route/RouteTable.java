package io.gwanmun.gateway.route;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 라우팅 테이블 — "메서드 + 경로"를 백엔드 라우트로 매핑한다. 지금은 목업 계정계 하나로 가는
 * 잔액조회 라우트뿐이지만, 표로 두어 거래가 늘면 줄만 추가하면 되게 했다(하드코딩 분기 대신).
 *
 * <p>여기서 실제로 백엔드를 호출하지는 않는다. "이 요청이 어디로 갈 자격이 있는가"만 판정하고
 * (모르는 경로는 404), 실제 전문 왕복은 통과 이후 컨트롤러 → GatewayService가 맡는다.
 */
@Component
public class RouteTable {

	/** 한 라우트. id는 로그·헤더에 드러나는 이름, backend는 논리적 대상(확장 시 실제 주소가 된다). */
	public record Route(String id, String backend) {
	}

	private final Map<String, Route> routes = new LinkedHashMap<>();

	/** 경로에 변수가 붙는 라우트(예: /resolve/{tranId})는 접두어로 매칭한다. */
	private final Map<String, Route> prefixRoutes = new LinkedHashMap<>();

	public RouteTable() {
		// 메서드와 경로를 합친 키. 확장 지점: 거래코드/헤더 기반 라우팅도 같은 표에 얹을 수 있다.
		routes.put(key("POST", "/api/gateway/balance"),
				new Route("core-banking-balance", "mock-core@127.0.0.1:9099"));
		// Phase 6 — UNKNOWN 해소(상태조회·망취소). 거래ID가 경로 변수라 접두어 매칭.
		prefixRoutes.put(key("POST", "/api/gateway/resolve/"),
				new Route("core-banking-resolve", "mock-core@127.0.0.1:9099"));
	}

	/** 요청에 맞는 라우트를 찾는다(정확 일치 우선, 다음 접두어). 없으면 empty(→ 404). */
	public Optional<Route> match(String method, String path) {
		Route exact = routes.get(key(method, path));
		if (exact != null) {
			return Optional.of(exact);
		}
		String k = key(method, path);
		return prefixRoutes.entrySet().stream()
				.filter(e -> k.startsWith(e.getKey()))
				.map(Map.Entry::getValue)
				.findFirst();
	}

	private static String key(String method, String path) {
		return method.toUpperCase(Locale.ROOT) + " " + path;
	}
}
