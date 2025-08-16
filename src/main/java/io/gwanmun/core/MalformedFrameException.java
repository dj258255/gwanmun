package io.gwanmun.core;

import java.io.IOException;

/**
 * 길이 헤더가 비정상일 때(숫자가 아님·음수·설정한 상한 초과) 던진다.
 *
 * <p>가변 프레이밍의 방어선이다. 스트림에 섞여 든 쓰레기 바이트나 손상된 헤더를 그대로 믿고
 * "그 길이만큼 더 읽자"고 하면, 오지도 않을 바이트를 무한정 기다리거나 거대한 버퍼를 잡으려다
 * 죽는다(자원 고갈 공격 표면이기도 하다). 그래서 헤더를 <b>검증하고, 어긋나면 즉시 실패</b>한다
 * (fail-closed). {@link IOException} 계열이라 연결 처리 루프가 자연스럽게 연결을 닫는다.
 */
public class MalformedFrameException extends IOException {

	public MalformedFrameException(String message) {
		super(message);
	}
}
