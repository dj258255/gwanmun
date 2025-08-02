package io.gwanmun.gateway.filter;

/**
 * 관문을 지키는 필터 하나. 요청이 순서대로 통과하는 체인의 한 마디다.
 *
 * <p>각 필터는 둘 중 하나를 한다.
 * <ul>
 *   <li><b>통과</b>: 검사에 문제가 없으면 {@code chain.next(request, response)}를 불러 다음 필터로 넘긴다.</li>
 *   <li><b>차단</b>: 문제가 있으면 {@code response.block(status, reason)}을 부르고 <b>next를 부르지 않는다</b>.
 *       그 순간 체인이 멈추고, 브릿지가 그 상태코드·사유로 응답한다.</li>
 * </ul>
 *
 * <p>서블릿 API에 의존하지 않는다 — 이 인터페이스는 순수 자바라 필터 로직을 단독으로 테스트할 수 있다.
 */
public interface GatewayFilter {

	void filter(GatewayRequest request, GatewayResponse response, GatewayFilterChain chain);

	/**
	 * 체인 안에서의 순서. 작을수록 먼저 실행된다. 인증(10) → 라우팅(20) → 유량제어(30)로 고정하되,
	 * 순서를 프레임워크 애노테이션이 아니라 코드로 드러내려고 인터페이스에 둔다.
	 */
	int order();
}
