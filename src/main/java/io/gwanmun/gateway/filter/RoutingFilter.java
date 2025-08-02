package io.gwanmun.gateway.filter;

import io.gwanmun.gateway.route.RouteTable;
import io.gwanmun.gateway.route.RouteTable.Route;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 라우팅 필터 — 체인의 둘째 마디. 인증을 통과한 요청의 "메서드 + 경로"를 라우팅 테이블에서 찾는다.
 * 아는 라우트면 라우트 id를 요청에 실어 넘기고, <b>모르는 경로면 404</b>로 끊는다.
 *
 * <p>여기서 백엔드를 직접 부르지는 않는다. "갈 곳이 있는가"만 판정한다 — 실제 전문 왕복은
 * 체인을 다 통과한 뒤 컨트롤러가 GatewayService로 수행한다(관문은 흐름만 통제).
 */
@Component
public class RoutingFilter implements GatewayFilter {

	private final RouteTable routes;

	public RoutingFilter(RouteTable routes) {
		this.routes = routes;
	}

	@Override
	public void filter(GatewayRequest request, GatewayResponse response, GatewayFilterChain chain) {
		Optional<Route> match = routes.match(request.method(), request.path());
		if (match.isEmpty()) {
			response.block(404, "알 수 없는 라우트: " + request.method() + " " + request.path());
			return;
		}
		Route route = match.get();
		request.routeId(route.id());
		response.header("X-Gateway-Route", route.id()); // 통과: 어느 라우트로 잡혔는지 드러낸다.
		chain.next(request, response);
	}

	@Override
	public int order() {
		return 20;
	}
}
