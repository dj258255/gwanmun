package io.gwanmun.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유량제어의 스레드 안전성. 여러 스레드가 <b>같은 클라이언트</b> 버킷을 동시에 두드려도 토큰이
 * 정확히 용량만큼만 소비돼야 한다(초과 통과·유실 없음). 시계를 고정해 보충을 배제하고, 순수하게
 * 동시 접근에서의 정확성만 본다.
 */
class RateLimitConcurrencyTest {

	@Test
	@DisplayName("8스레드가 같은 클라이언트로 동시에 쳐도 정확히 용량만큼만 통과한다")
	void sameClientBucketIsThreadSafe() throws InterruptedException {
		int capacity = 100;
		int threads = 8;
		int attemptsPerThread = 100; // 총 800회 시도 > 용량 100
		// 시계 고정(상수 0) → 경과 시간이 0이라 보충이 없다. 통과는 딱 capacity건이어야 한다.
		// refill 속도는 양수여야 하므로 아주 작게 두되, 시계가 안 흐르니 실제 보충은 0이다.
		RateLimitFilter filter = new RateLimitFilter(capacity, 0.0001, freezeAtZeroButValid());

		AtomicInteger passed = new AtomicInteger();
		AtomicInteger blocked = new AtomicInteger();

		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		for (int t = 0; t < threads; t++) {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < attemptsPerThread; i++) {
						GatewayResponse res = new GatewayResponse();
						GatewayRequest req = authenticatedRequest("fintech-a");
						filter.filter(req, res, terminalChain());
						if (res.blocked()) {
							blocked.incrementAndGet();
						} else {
							passed.incrementAndGet();
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}

		start.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(passed.get()).as("통과는 정확히 용량만큼").isEqualTo(capacity);
		assertThat(blocked.get()).as("나머지는 전부 429").isEqualTo(threads * attemptsPerThread - capacity);
	}

	/** capacity>0, refill 0인 버킷은 시계 값과 무관하게 보충이 없다. 상수 시계면 충분. */
	private static java.util.function.LongSupplier freezeAtZeroButValid() {
		return () -> 0L;
	}

	/** 인증 필터가 이미 채웠다고 가정하고 clientId만 세운 요청(같은 패키지라 세터 접근 가능). */
	private static GatewayRequest authenticatedRequest(String clientId) {
		GatewayRequest req = new GatewayRequest("POST", "/api/gateway/balance", Map.of());
		req.clientId(clientId);
		return req;
	}

	/** 유량제어 뒤에 아무 필터도 없는 종단 체인(통과 시 그냥 끝난다). */
	private static GatewayFilterChain terminalChain() {
		return new GatewayFilterChain(java.util.List.of());
	}
}
