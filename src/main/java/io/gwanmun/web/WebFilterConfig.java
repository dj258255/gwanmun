package io.gwanmun.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * web 모듈의 서블릿 필터 등록. correlation ID 필터는 <b>모든 경로</b>에, 그리고 관문 필터(순서 1)보다
 * <b>앞(순서 0)</b>에 건다 — 관문이 차단을 로깅하는 순간에도 correlation ID가 이미 MDC에 있어야 한다.
 */
@Configuration
public class WebFilterConfig {

	@Bean
	public FilterRegistrationBean<CorrelationIdFilter> correlationIdRegistration() {
		FilterRegistrationBean<CorrelationIdFilter> reg =
				new FilterRegistrationBean<>(new CorrelationIdFilter());
		reg.addUrlPatterns("/*");
		reg.setName("correlationIdFilter");
		reg.setOrder(0); // 관문 필터(1)보다 먼저.
		return reg;
	}
}
