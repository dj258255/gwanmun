package io.gwanmun.message;

/**
 * DTO → 전문 빌드 실패. 대표적으로 값이 필드 byte 길이를 초과하는 경우
 * (숫자가 너무 크거나, 한글이 많아 EUC-KR 인코딩 후 길이가 넘는 경우).
 * 잘라서 몰래 담으면 데이터 손상이라, 반드시 예외로 세운다.
 */
public class GwanmunBuildException extends RuntimeException {
	public GwanmunBuildException(String message) {
		super(message);
	}

	public GwanmunBuildException(String message, Throwable cause) {
		super(message, cause);
	}
}
