package io.gwanmun.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 커넥션 풀의 재사용·검증·고갈·동시성을 <b>소켓 없이</b> 가짜 연결로 검증한다. 풀의 계약(연결을
 * 새로 만들지 재사용할지, 죽으면 버릴지, 가득 차면 거절할지)만 본다.
 */
class ConnectionPoolTest {

	/** 실제 소켓 없이 풀 계약만 시험하는 가짜 연결. isValid를 마음대로 조작한다. */
	static final class FakeConnection implements PoolableConnection {
		final int id;
		volatile boolean valid = true;
		volatile boolean closed;

		FakeConnection(int id) {
			this.id = id;
		}

		@Override
		public boolean isValid() {
			return valid && !closed;
		}

		@Override
		public void setReadTimeout(int millis) {
			// 가짜 연결 — 풀 계약 시험에는 타임아웃이 필요 없다.
		}

		@Override
		public void close() {
			closed = true;
		}
	}

	private ConnectionPool<FakeConnection> pool(int maxSize, long borrowTimeoutMs, AtomicInteger createdIds) {
		return new ConnectionPool<>("test", maxSize, borrowTimeoutMs,
				() -> new FakeConnection(createdIds.incrementAndGet()));
	}

	@Test
	@DisplayName("재사용: 빌렸다 반납한 연결을 다시 빌리면 같은 객체(소켓)가 온다")
	void reusesSameConnection() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		ConnectionPool<FakeConnection> pool = pool(2, 1000, ids);

		FakeConnection first;
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			first = lease.connection();
			assertThat(lease.reuseCount()).isZero(); // 갓 만든 연결
		}
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			assertThat(lease.connection()).isSameAs(first); // 같은 객체 재사용
			assertThat(lease.reuseCount()).isEqualTo(1);
		}

		ConnectionPool.Stats stats = pool.stats();
		assertThat(stats.created()).isEqualTo(1); // 새로 만든 건 한 번뿐
		assertThat(stats.reused()).isEqualTo(1);
		assertThat(ids.get()).isEqualTo(1);
		pool.close();
	}

	@Test
	@DisplayName("검증: 유휴 중 죽은 연결은 재사용하지 않고 폐기 후 새로 만든다")
	void discardsDeadConnection() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		ConnectionPool<FakeConnection> pool = pool(2, 1000, ids);

		FakeConnection first;
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			first = lease.connection();
		}
		first.valid = false; // 유휴로 놓인 사이 소켓이 죽었다고 가정

		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			assertThat(lease.connection()).isNotSameAs(first); // 죽은 건 안 쓴다
			assertThat(lease.connection().isValid()).isTrue();
		}
		assertThat(first.closed).isTrue();       // 폐기됨
		assertThat(pool.stats().created()).isEqualTo(2);
		assertThat(pool.stats().destroyed()).isEqualTo(1);
		pool.close();
	}

	@Test
	@DisplayName("invalidate: 처리 중 깨졌다고 표시하면 반납 시 재사용 대신 폐기")
	void invalidatedLeaseIsDiscarded() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		ConnectionPool<FakeConnection> pool = pool(2, 1000, ids);

		FakeConnection broken;
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			broken = lease.connection();
			lease.invalidate();
		}
		assertThat(broken.closed).isTrue();
		assertThat(pool.stats().idle()).isZero();
		assertThat(pool.stats().destroyed()).isEqualTo(1);
		pool.close();
	}

	@Test
	@DisplayName("고갈 정책: 최대 크기까지 다 빌린 상태에서 또 빌리면 대기 후 거절(PoolExhausted)")
	void rejectsWhenExhausted() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		ConnectionPool<FakeConnection> pool = pool(1, 150, ids); // 최대 1개, 대기 150ms

		ConnectionPool<FakeConnection>.Lease held = pool.borrow(); // 유일한 연결을 쥐고 안 놓는다
		try {
			long start = System.nanoTime();
			assertThatThrownBy(pool::borrow).isInstanceOf(PoolExhaustedException.class);
			long waitedMs = (System.nanoTime() - start) / 1_000_000;
			assertThat(waitedMs).isGreaterThanOrEqualTo(140); // 곧장 실패가 아니라 대기 후 거절
		} finally {
			held.close();
		}
		// 반납 후에는 다시 빌릴 수 있어야 한다.
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			assertThat(lease.connection()).isNotNull();
		}
		pool.close();
	}

	@Test
	@DisplayName("가득 차 대기하던 borrow는, 다른 스레드가 반납하면 그 연결을 이어받는다")
	void waiterGetsReleasedConnection() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		ConnectionPool<FakeConnection> pool = pool(1, 2000, ids);

		ConnectionPool<FakeConnection>.Lease held = pool.borrow();
		ExecutorService exec = Executors.newSingleThreadExecutor();
		Future<Boolean> waiter = exec.submit(() -> {
			try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
				return lease.connection() != null;
			}
		});
		Thread.sleep(100);     // 대기자가 확실히 기다리게 둔 뒤
		held.close();          // 반납 → 대기자가 이어받아야 한다
		assertThat(waiter.get(2, TimeUnit.SECONDS)).isTrue();
		assertThat(pool.stats().created()).isEqualTo(1); // 새로 만들지 않고 재사용
		exec.shutdownNow();
		pool.close();
	}

	@Test
	@DisplayName("동시성: 8스레드×50회가 최대 3짜리 풀을 쳐도 활성 수가 3을 절대 넘지 않는다")
	void concurrencyNeverExceedsMax() throws Exception {
		int maxSize = 3, threads = 8, perThread = 50;
		AtomicInteger ids = new AtomicInteger();
		ConnectionPool<FakeConnection> pool = pool(maxSize, 3000, ids);
		AtomicInteger maxObservedActive = new AtomicInteger();

		ExecutorService exec = Executors.newFixedThreadPool(threads);
		try {
			Future<?>[] futures = new Future<?>[threads];
			for (int t = 0; t < threads; t++) {
				futures[t] = exec.submit(() -> {
					for (int i = 0; i < perThread; i++) {
						try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
							int active = pool.stats().active();
							maxObservedActive.accumulateAndGet(active, Math::max);
							assertThat(active).isLessThanOrEqualTo(maxSize);
							Thread.yield();
						} catch (IOException | InterruptedException e) {
							throw new RuntimeException(e);
						}
					}
				});
			}
			for (Future<?> f : futures) {
				f.get(10, TimeUnit.SECONDS);
			}
		} finally {
			exec.shutdownNow();
		}

		assertThat(maxObservedActive.get()).isLessThanOrEqualTo(maxSize);
		assertThat(pool.stats().active()).isZero();       // 전부 반납됨
		assertThat(ids.get()).isLessThanOrEqualTo(maxSize); // 최대 크기만큼만 만들어짐
		assertThat(pool.stats().reused()).isGreaterThan(0); // 재사용이 실제로 일어남
		pool.close();
	}

	@Test
	@DisplayName("유휴 TTL(Phase 7): 반납 후 TTL이 지난 연결은 재사용하지 않고 폐기 후 새로 연다")
	void idleTtlExpiresStaleConnection() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		java.util.concurrent.atomic.AtomicLong nowNanos = new java.util.concurrent.atomic.AtomicLong();
		ConnectionPool<FakeConnection> pool = new ConnectionPool<>("test", 2, 1000, 500,
				() -> new FakeConnection(ids.incrementAndGet()), nowNanos::get);

		FakeConnection first;
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			first = lease.connection();
		}

		// TTL(500ms) 안에는 재사용된다.
		nowNanos.addAndGet(400L * 1_000_000);
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			assertThat(lease.connection()).isSameAs(first);
		}

		// 마지막 반납에서 TTL을 넘겨 놀았다 — isValid()가 true여도(로컬 플래그) 폐기돼야 한다.
		nowNanos.addAndGet(501L * 1_000_000);
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			assertThat(lease.connection()).isNotSameAs(first);
			assertThat(lease.reuseCount()).isZero(); // 갓 만든 연결
		}
		assertThat(first.closed).isTrue();
		assertThat(pool.stats().expired()).isEqualTo(1);
		assertThat(pool.stats().destroyed()).isEqualTo(1);
		assertThat(pool.stats().created()).isEqualTo(2);
		pool.close();
	}

	@Test
	@DisplayName("유휴 TTL 비활성(0)이면 아무리 오래 놀아도 폐기하지 않는다(Phase 6까지의 동작 보존)")
	void idleTtlDisabledKeepsOldConnections() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		java.util.concurrent.atomic.AtomicLong nowNanos = new java.util.concurrent.atomic.AtomicLong();
		ConnectionPool<FakeConnection> pool = new ConnectionPool<>("test", 2, 1000, 0,
				() -> new FakeConnection(ids.incrementAndGet()), nowNanos::get);

		FakeConnection first;
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			first = lease.connection();
		}
		nowNanos.addAndGet(3_600_000L * 1_000_000); // 1시간 방치
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			assertThat(lease.connection()).isSameAs(first); // TTL 0 = 비활성
		}
		assertThat(pool.stats().expired()).isZero();
		pool.close();
	}

	@Test
	@DisplayName("닫힌 풀은 유휴 연결을 모두 닫고, 이후 borrow는 막는다")
	void closedPoolRejectsBorrow() throws Exception {
		AtomicInteger ids = new AtomicInteger();
		ConnectionPool<FakeConnection> pool = pool(2, 500, ids);
		try (ConnectionPool<FakeConnection>.Lease lease = pool.borrow()) {
			assertThat(lease.connection()).isNotNull();
		}
		pool.close();
		assertThatThrownBy(pool::borrow).isInstanceOf(IllegalStateException.class);
	}
}
