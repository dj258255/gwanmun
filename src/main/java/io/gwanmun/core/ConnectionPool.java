package io.gwanmun.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 스레드 안전한 커넥션 풀. Phase 2의 {@link CoreBankingClient}는 요청마다 소켓을 새로 열고 닫았다 —
 * TCP 3-way 핸드셰이크·소켓 자원을 매번 지불하는 비용이다. 풀은 <b>연 소켓을 유휴 상태로 쥐고 있다가
 * 다음 요청에 재사용</b>해 그 비용을 없앤다.
 *
 * <p><b>이 풀이 지키는 다섯 가지.</b>
 * <ul>
 *   <li><b>최대 크기</b>: 최대 {@code maxSize}개까지만 연다. 그 이상은 만들지 않는다.</li>
 *   <li><b>유휴 반납·재사용</b>: 다 쓴 연결은 닫지 않고 유휴 큐로 돌려, 다음 {@link #borrow()}가 집어 쓴다.</li>
 *   <li><b>검증</b>: 빌려주기/반납 시 {@link PoolableConnection#isValid()}로 죽은 소켓을 걸러 버린다.</li>
 *   <li><b>유휴 TTL (Phase 7)</b>: 반납 후 {@code idleTtlMs}가 지난 유휴 연결은 재사용하지 않고 폐기한다.
 *       {@code isValid()}는 로컬 소켓 플래그만 본다 — 상대가 FIN을 보내고 죽은 소켓도 로컬에서는 멀쩡해
 *       보이고, write는 OS 버퍼에 들어가 "성공"한 뒤 read에서야 EOF가 난다. 오래 논 연결을 수명으로
 *       걸러야 재기동한 계정계의 낡은 소켓에 변경성 전문을 태우는 사고(억울한 UNKNOWN)를 막는다.</li>
 *   <li><b>고갈 정책</b>: 가득 차면 {@code borrowTimeoutMs}만큼 기다리고, 그래도 자리가 없으면
 *       무한 대기 대신 {@link PoolExhaustedException}으로 <b>거절</b>한다(빠른 실패).</li>
 * </ul>
 *
 * <p>동시성은 하나의 {@link ReentrantLock} + {@link Condition}으로 관리한다. 소켓을 새로 여는 느린
 * 작업({@link ConnectionFactory#create()})만 락 밖에서 하고, 카운터·큐 갱신은 락 안에서 한다.
 *
 * @param <C> 풀이 담는 연결 타입
 */
public final class ConnectionPool<C extends PoolableConnection> implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(ConnectionPool.class);

	/** 새 연결(소켓)을 여는 팩토리. 소켓 open은 IO라 실패할 수 있다. */
	@FunctionalInterface
	public interface ConnectionFactory<C extends PoolableConnection> {
		C create() throws IOException;
	}

	private final String name;
	private final int maxSize;
	private final long borrowTimeoutMs;
	private final long idleTtlMs;
	private final ConnectionFactory<C> factory;
	private final java.util.function.LongSupplier nanoTime;

	private final ReentrantLock lock = new ReentrantLock();
	private final Condition slotAvailable = lock.newCondition();
	private final Deque<Entry> idle = new ArrayDeque<>();

	private int total;   // 살아 있는 연결 수(유휴 + 대여 중)
	private int active;  // 지금 대여 중인 수
	private long createdCount;
	private long reusedCount;
	private long destroyedCount;
	private long expiredCount; // 유휴 TTL로 폐기된 수(Phase 7)
	private boolean closed;

	/** 유휴 TTL 없이(0 = 비활성) 만드는 생성자 — Phase 6까지의 동작 그대로. */
	public ConnectionPool(String name, int maxSize, long borrowTimeoutMs, ConnectionFactory<C> factory) {
		this(name, maxSize, borrowTimeoutMs, 0, factory, System::nanoTime);
	}

	/** 유휴 TTL을 지정하는 생성자(Phase 7). {@code idleTtlMs <= 0}이면 TTL 비활성. */
	public ConnectionPool(String name, int maxSize, long borrowTimeoutMs, long idleTtlMs,
			ConnectionFactory<C> factory) {
		this(name, maxSize, borrowTimeoutMs, idleTtlMs, factory, System::nanoTime);
	}

	/** 테스트용: 시계를 주입해 유휴 TTL 경과를 시간 조작으로 검증한다. */
	ConnectionPool(String name, int maxSize, long borrowTimeoutMs, long idleTtlMs,
			ConnectionFactory<C> factory, java.util.function.LongSupplier nanoTime) {
		if (maxSize <= 0) {
			throw new IllegalArgumentException("풀 최대 크기는 1 이상이어야 합니다: " + maxSize);
		}
		this.name = name;
		this.maxSize = maxSize;
		this.borrowTimeoutMs = borrowTimeoutMs;
		this.idleTtlMs = idleTtlMs;
		this.factory = factory;
		this.nanoTime = nanoTime;
	}

	/** 풀 내부에서 연결 하나와 그 재사용 횟수·마지막 반납 시각을 묶어 든다. */
	private final class Entry {
		final C conn;
		int reuseCount;
		long idleSinceNanos; // 마지막으로 유휴 큐에 들어간 시각(TTL 판정 기준)

		Entry(C conn) {
			this.conn = conn;
		}
	}

	/**
	 * 연결 하나를 빌린다. 유휴가 있으면 재사용하고, 없으면 최대 크기 안에서 새로 열며, 가득 차면
	 * 대기 후 거절한다. try-with-resources로 {@link Lease#close()}가 자동 반납되게 쓴다.
	 *
	 * @throws IOException              새 연결을 여는 데 실패했을 때
	 * @throws InterruptedException     대기 중 인터럽트
	 * @throws PoolExhaustedException   가득 찼고 시간 안에 자리가 안 났을 때
	 */
	public Lease borrow() throws IOException, InterruptedException {
		long deadlineNanos = System.nanoTime() + borrowTimeoutMs * 1_000_000L;
		lock.lock();
		try {
			while (true) {
				if (closed) {
					throw new IllegalStateException("풀 '" + name + "' 이 이미 닫혔습니다.");
				}

				// 1) 유휴 연결이 있으면 수명·생존 검증 후 재사용.
				Entry e = idle.pollFirst();
				if (e != null) {
					if (idleTtlMs > 0 && (nanoTime.getAsLong() - e.idleSinceNanos) / 1_000_000 > idleTtlMs) {
						// 유휴 TTL 초과 — 로컬 플래그로는 멀쩡해 보여도 상대가 이미 닫았을 수 있는
						// 나이든 연결이다. 재사용하지 않고 폐기한 뒤 다시 시도(새로 열 여지가 생긴다).
						log.debug("풀 '{}' 유휴 TTL({}ms) 초과 연결 폐기", name, idleTtlMs);
						closeQuietly(e.conn);
						total--;
						destroyedCount++;
						expiredCount++;
						continue;
					}
					if (e.conn.isValid()) {
						active++;
						e.reuseCount++;
						reusedCount++;
						return new Lease(e);
					}
					// 죽은 연결이면 버리고 슬롯을 비운 뒤 다시 시도(새로 열 여지가 생긴다).
					closeQuietly(e.conn);
					total--;
					destroyedCount++;
					continue;
				}

				// 2) 아직 최대 크기에 여유가 있으면 새로 연다(느린 소켓 open은 락 밖에서).
				if (total < maxSize) {
					total++; // 슬롯 선점(다른 스레드가 초과 생성하지 않게)
					lock.unlock();
					C conn;
					try {
						conn = factory.create();
					} catch (IOException | RuntimeException ex) {
						lock.lock();
						total--;
						slotAvailable.signalAll();
						lock.unlock();
						throw ex;
					}
					lock.lock();
					createdCount++;
					active++;
					return new Lease(new Entry(conn));
				}

				// 3) 가득 참 — 반납을 기다리다 시간 초과면 거절.
				long remaining = deadlineNanos - System.nanoTime();
				if (remaining <= 0) {
					throw new PoolExhaustedException(name, maxSize, borrowTimeoutMs);
				}
				slotAvailable.awaitNanos(remaining);
			}
		} finally {
			if (lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}

	private void release(Entry e, boolean broken) {
		lock.lock();
		try {
			active--;
			if (!closed && !broken && e.conn.isValid()) {
				e.idleSinceNanos = nanoTime.getAsLong(); // TTL 기산점 — 지금부터 논다.
				idle.addLast(e); // 유휴로 반납 → 다음 borrow가 재사용
			} else {
				closeQuietly(e.conn);
				total--;
				destroyedCount++;
			}
			slotAvailable.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/** 현재 풀 상태 스냅샷(화면·로그용). */
	public Stats stats() {
		lock.lock();
		try {
			return new Stats(name, maxSize, active, idle.size(), total,
					createdCount, reusedCount, destroyedCount, expiredCount);
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void close() {
		lock.lock();
		try {
			closed = true;
			Entry e;
			while ((e = idle.pollFirst()) != null) {
				closeQuietly(e.conn);
				total--;
				destroyedCount++;
			}
			slotAvailable.signalAll();
		} finally {
			lock.unlock();
		}
	}

	private void closeQuietly(C conn) {
		try {
			conn.close();
		} catch (IOException ex) {
			log.debug("연결 종료 중 예외(무시): {}", ex.getMessage());
		}
	}

	/**
	 * 빌린 연결의 대여증. {@link #close()}가 풀로 반납한다. 처리 중 프로토콜이 깨졌으면
	 * {@link #invalidate()}로 표시해, 반납 시 재사용 대신 폐기되게 한다.
	 */
	public final class Lease implements Closeable {
		private final Entry entry;
		private boolean released;
		private boolean broken;

		private Lease(Entry entry) {
			this.entry = entry;
		}

		public C connection() {
			return entry.conn;
		}

		/** 이 연결이 이번 대여에서 몇 번째 재사용인지(0=갓 만들어 처음 씀). */
		public int reuseCount() {
			return entry.reuseCount;
		}

		/** 처리 중 오류로 이 연결을 더는 믿을 수 없을 때 호출. 반납 시 폐기된다. */
		public void invalidate() {
			this.broken = true;
		}

		@Override
		public void close() {
			if (!released) {
				released = true;
				release(entry, broken);
			}
		}
	}

	/** 풀 상태 스냅샷. {@code expired}는 유휴 TTL로 폐기된 누적 수(destroyed에 포함, Phase 7). */
	public record Stats(
			String name,
			int maxSize,
			int active,
			int idle,
			int total,
			long created,
			long reused,
			long destroyed,
			long expired
	) {
	}
}
