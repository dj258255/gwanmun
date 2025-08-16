package io.gwanmun.core;

/**
 * 커넥션 풀이 가득 찼고, 대기 시간 안에 반납되는 연결도 없을 때 던진다(풀 고갈 거절 정책).
 *
 * <p>풀은 무한정 커지지 않는다(백엔드 소켓은 유한 자원). 최대 크기까지 다 빌려 나간 상태에서 또
 * 요청이 오면, 잠깐 기다리다({@code borrow-timeout}) 그래도 자리가 안 나면 <b>무한 대기 대신 거절</b>한다.
 * 이렇게 해야 과부하가 스레드 무한 적체로 번지지 않고 빠른 실패로 드러난다.
 */
public class PoolExhaustedException extends RuntimeException {

	public PoolExhaustedException(String poolName, int maxSize, long borrowTimeoutMs) {
		super(String.format(
				"커넥션 풀 '%s' 고갈: 최대 %d개가 모두 사용 중이고 %dms 안에 반납된 연결이 없습니다(거절).",
				poolName, maxSize, borrowTimeoutMs));
	}
}
