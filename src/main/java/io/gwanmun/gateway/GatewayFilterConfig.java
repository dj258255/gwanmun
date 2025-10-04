package io.gwanmun.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gwanmun.gateway.filter.GatewayFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;

/**
 * 관문 필터 체인을 서블릿에 등록한다. 스프링이 모아 준 {@link GatewayFilter} 빈들을
 * {@code order()} 순으로 정렬해(인증 10 → 라우팅 20 → 유량제어 30) 브릿지에 넘긴다.
 *
 * <p>{@code /api/gateway/*} 경로에만 건다 — 통역만 하던 나머지 API(/api/build 등)는 문지기 밖이다.
 */
@Configuration
public class GatewayFilterConfig {

	private static final Logger log = LoggerFactory.getLogger(GatewayFilterConfig.class);

	@Bean
	public FilterRegistrationBean<GatewayGuardFilter> gatewayGuardRegistration(
			List<GatewayFilter> filters, ObjectMapper objectMapper) {

		List<GatewayFilter> ordered = filters.stream()
				.sorted(Comparator.comparingInt(GatewayFilter::order))
				.toList();
		log.info("관문 필터 체인 등록(순서): {}",
				ordered.stream().map(f -> f.getClass().getSimpleName() + "#" + f.order()).toList());

		FilterRegistrationBean<GatewayGuardFilter> reg = new FilterRegistrationBean<>(
				new GatewayGuardFilter(ordered, objectMapper));
		// Phase 7(B2): /api/history 도 관문 안으로 — 실제 계정계 거래를 유발하는 경로는 전부 검문한다.
		// /api/ledger·/api/pool/stats·/api/circuit/stats 같은 읽기 전용 관측 경로만 예외로 남긴다
		// (계정계 호출이 없고, 장애 관찰 요청이 유량제어에 막히면 관측이 안 된다 — RouteTable 주석 참조).
		reg.addUrlPatterns("/api/gateway/*", "/api/history");
		reg.setName("gatewayGuardFilter");
		reg.setOrder(1); // 애플리케이션 서블릿 필터 중 앞쪽에서 검문.
		return reg;
	}
}
