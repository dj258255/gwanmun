package io.gwanmun.gateway.filter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 필터 체인의 결정을 모으는 객체. 통과면 헤더만 쌓이고, 어느 필터가 막으면 상태코드와 사유가 채워진다.
 *
 * <p>차단은 <b>한 번</b>만 유효하다({@link #block}이 이미 걸렸으면 이후 호출은 무시). 첫 번째로 막은
 * 필터의 사유가 응답이 되도록, 그리고 체인이 그 뒤로 진행하지 않도록 하기 위함이다.
 */
public final class GatewayResponse {

	private boolean blocked;
	private int status;
	private String reason;
	private final Map<String, String> headers = new LinkedHashMap<>();

	/** 이 요청을 여기서 끊는다. 상태코드와 사람이 읽을 사유를 남긴다. 이미 막혔으면 아무 일도 안 한다. */
	public void block(int status, String reason) {
		if (blocked) {
			return;
		}
		this.blocked = true;
		this.status = status;
		this.reason = reason;
	}

	/** 응답 헤더를 하나 남긴다(통과·차단 어느 단계까지 갔는지 드러내는 용도로도 쓴다). */
	public void header(String name, String value) {
		headers.put(name, value);
	}

	public boolean blocked() {
		return blocked;
	}

	public int status() {
		return status;
	}

	public String reason() {
		return reason;
	}

	public Map<String, String> headers() {
		return headers;
	}
}
