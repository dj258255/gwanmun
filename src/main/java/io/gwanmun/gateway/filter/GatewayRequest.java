package io.gwanmun.gateway.filter;

import java.util.Locale;
import java.util.Map;

/**
 * 필터 체인이 보는 요청. 서블릿 API(HttpServletRequest)에 직접 묶이지 않게, 필요한 것만 뽑아 담은
 * 얇은 값 객체다. 덕분에 필터 로직은 서블릿 없이도 단위 테스트할 수 있다(브릿지에서만 서블릿을 안다).
 *
 * <p>메서드·경로·헤더는 읽기 전용이고, 통과하며 채워지는 두 값(인증된 클라이언트 id, 매칭된 라우트 id)만
 * 가변이다 — 앞선 필터가 세우고 뒤 필터가 읽는다(인증 → 라우팅 → 유량제어의 연결고리).
 */
public final class GatewayRequest {

	private final String method;
	private final String path;
	private final Map<String, String> headers; // 키는 소문자로 정규화해 담는다(대소문자 무시).

	private String clientId; // 인증 필터가 채운다.
	private String routeId;  // 라우팅 필터가 채운다.

	public GatewayRequest(String method, String path, Map<String, String> lowerCaseHeaders) {
		this.method = method;
		this.path = path;
		this.headers = lowerCaseHeaders;
	}

	public String method() {
		return method;
	}

	public String path() {
		return path;
	}

	/** 헤더 값을 대소문자 무시로 조회한다. 없으면 null. */
	public String header(String name) {
		return headers.get(name.toLowerCase(Locale.ROOT));
	}

	public String clientId() {
		return clientId;
	}

	void clientId(String clientId) {
		this.clientId = clientId;
	}

	public String routeId() {
		return routeId;
	}

	void routeId(String routeId) {
		this.routeId = routeId;
	}
}
