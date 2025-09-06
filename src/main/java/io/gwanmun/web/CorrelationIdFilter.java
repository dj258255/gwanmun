package io.gwanmun.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * correlation ID 필터 — 요청 하나에 ID 하나를 붙여, 그 요청이 남기는 <b>모든 로그 라인</b>과
 * <b>원장 기록</b>을 한 줄로 꿴다. 장애 때 "이 502가 어느 요청이었나"를 잇는 실이다.
 *
 * <ul>
 *   <li>수신 헤더 {@code X-Correlation-Id}가 있으면 <b>승계</b>한다(호출자가 이미 흐름을 추적 중).
 *       단, 로그 인젝션 방지를 위해 허용 문자·길이를 검증하고 어긋나면 새로 만든다.</li>
 *   <li>없으면 생성한다(UUID 기반 16자 hex).</li>
 *   <li>{@link MDC}에 넣어 로그 패턴({@code %X{correlationId}})이 모든 라인에 자동으로 찍게 하고,
 *       응답 헤더로도 돌려줘 호출자가 문의할 때 쓸 수 있게 한다.</li>
 * </ul>
 *
 * <p>MDC는 스레드 로컬이라 요청 처리가 끝나면 반드시 지운다 — 스레드 풀에서 스레드가 재사용될 때
 * 이전 요청의 ID가 다음 요청 로그에 묻어나는 오염을 막는다(finally).
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Correlation-Id";
	public static final String MDC_KEY = "correlationId";

	/** 승계 허용 형식: 영숫자·하이픈 8~64자(로그 인젝션·비정상 길이 방어). */
	private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9\\-]{8,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		String incoming = req.getHeader(HEADER);
		String cid = (incoming != null && ACCEPTABLE.matcher(incoming).matches())
				? incoming
				: newCorrelationId();
		MDC.put(MDC_KEY, cid);
		res.setHeader(HEADER, cid);
		try {
			chain.doFilter(req, res);
		} finally {
			MDC.remove(MDC_KEY); // 스레드 재사용 오염 방지
		}
	}

	private static String newCorrelationId() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
	}
}
