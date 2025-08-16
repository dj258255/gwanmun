package io.gwanmun.core;

import java.io.Closeable;

/**
 * {@link ConnectionPool}이 관리할 수 있는 연결. 풀은 이 두 가지만 알면 된다 —
 * "지금도 쓸 수 있나({@link #isValid()})"와 "닫는 법({@link Closeable#close()})".
 *
 * <p>풀이 프레이밍이나 전문 스펙 같은 상위 개념을 몰라도 되게 최소한으로 좁혔다. 실제 구현은
 * {@link FramedConnection}(고정길이)·{@link LengthPrefixedConnection}(가변길이)이다.
 */
public interface PoolableConnection extends Closeable {

	/**
	 * 이 연결을 지금 재사용해도 되는가. 유휴 상태로 풀에 놓였던 소켓이 그새 상대방에 의해 닫혔을 수
	 * 있으므로, 빌려주기/반납 시점에 검증해 죽은 연결을 걸러 낸다.
	 */
	boolean isValid();
}
