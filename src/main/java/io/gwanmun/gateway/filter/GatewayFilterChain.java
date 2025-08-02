package io.gwanmun.gateway.filter;

import java.util.List;

/**
 * 필터 체인 실행기. 넘겨받은 필터들을 순서대로 한 마디씩 태운다.
 *
 * <p>인스턴스는 요청 한 건당 하나씩 만들어 쓴다(진행 위치 {@code index}가 상태라 재사용 금지).
 * 필터가 {@code next}를 부르면 다음 필터로, 안 부르면 거기서 멈춘다. 또한 이미 차단된 응답이면
 * 더 진행하지 않는다 — 재귀로 태우다가 어느 필터가 막았을 때 안전판.
 */
public final class GatewayFilterChain {

	private final List<GatewayFilter> filters;
	private int index;

	public GatewayFilterChain(List<GatewayFilter> orderedFilters) {
		this.filters = orderedFilters;
		this.index = 0;
	}

	/** 다음 필터를 실행한다. 남은 필터가 없거나 이미 차단됐으면 조용히 끝난다(체인 종료 = 통과). */
	public void next(GatewayRequest request, GatewayResponse response) {
		if (response.blocked()) {
			return;
		}
		if (index < filters.size()) {
			GatewayFilter current = filters.get(index++);
			current.filter(request, response, this);
		}
	}
}
