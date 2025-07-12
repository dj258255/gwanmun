package io.gwanmun.message;

/**
 * 전문 → DTO 파싱 실패. 길이 불일치(잘린 전문), 잘못된 hex 등.
 * 조용히 삼키지 않고 "무엇이 왜 어긋났는지"를 메시지에 담는다.
 */
public class GwanmunParseException extends RuntimeException {
	public GwanmunParseException(String message) {
		super(message);
	}

	public GwanmunParseException(String message, Throwable cause) {
		super(message, cause);
	}
}
