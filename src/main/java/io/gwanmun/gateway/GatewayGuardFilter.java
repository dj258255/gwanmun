package io.gwanmun.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.gwanmun.gateway.filter.GatewayFilter;
import io.gwanmun.gateway.filter.GatewayFilterChain;
import io.gwanmun.gateway.filter.GatewayRequest;
import io.gwanmun.gateway.filter.GatewayResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 손으로 짠 관문 필터 체인을 서블릿 파이프라인에 잇는 브릿지. {@code /api/gateway/**}에만 걸린다.
 *
 * <p>HttpServletRequest에서 필요한 것만 뽑아 {@link GatewayRequest}로 만들고, 인증→라우팅→유량제어
 * 체인을 태운다. 체인이 <b>막으면</b> 그 상태코드·사유로 즉시 응답하고 백엔드(컨트롤러)로 넘기지
 * 않는다. <b>통과하면</b> 체인이 남긴 헤더(누구로 인증됐고 어느 라우트인지 등)를 응답에 실어 준 뒤
 * 컨트롤러로 넘긴다 — 통과/차단이 응답에 드러나게.
 */
public class GatewayGuardFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(GatewayGuardFilter.class);

	private final List<GatewayFilter> orderedFilters;
	private final ObjectMapper objectMapper;

	public GatewayGuardFilter(List<GatewayFilter> orderedFilters, ObjectMapper objectMapper) {
		this.orderedFilters = orderedFilters;
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain servletChain)
			throws ServletException, IOException {

		GatewayRequest gwReq = new GatewayRequest(req.getMethod(), req.getRequestURI(), headersOf(req));
		GatewayResponse gwRes = new GatewayResponse();

		new GatewayFilterChain(orderedFilters).next(gwReq, gwRes);

		// 통과/차단 공통: 체인이 남긴 헤더를 응답에 싣는다(X-Gateway-Client·Route·RateLimit 등).
		gwRes.headers().forEach(res::setHeader);

		if (gwRes.blocked()) {
			log.info("관문 차단: {} {} → {} ({})", req.getMethod(), req.getRequestURI(),
					gwRes.status(), gwRes.reason());
			writeBlock(res, gwRes);
			return; // 여기서 끝. 백엔드로 안 넘긴다.
		}

		res.setHeader("X-Gateway-Decision", "pass");
		servletChain.doFilter(req, res); // 통과: 컨트롤러 → GatewayService로.
	}

	/** 헤더를 소문자 키 맵으로 정규화한다(대소문자 무시 조회). */
	private static Map<String, String> headersOf(HttpServletRequest req) {
		Map<String, String> map = new LinkedHashMap<>();
		var names = req.getHeaderNames();
		while (names != null && names.hasMoreElements()) {
			String name = names.nextElement();
			map.put(name.toLowerCase(Locale.ROOT), req.getHeader(name));
		}
		return Collections.unmodifiableMap(map);
	}

	/** 차단 응답을 JSON으로 쓴다: 상태코드·사유·차단 사실을 바디에 담는다. */
	private void writeBlock(HttpServletResponse res, GatewayResponse gwRes) throws IOException {
		res.setStatus(gwRes.status());
		res.setContentType("application/json;charset=UTF-8");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("blocked", true);
		body.put("status", gwRes.status());
		body.put("reason", gwRes.reason());
		objectMapper.writeValue(res.getWriter(), body);
	}
}
