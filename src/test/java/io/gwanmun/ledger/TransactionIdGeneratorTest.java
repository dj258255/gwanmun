package io.gwanmun.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 거래고유번호 채번의 두 가지 유일성 보장을 검증한다 — 스레드 안전(동시 채번 무충돌)과
 * 재기동 안전(시각 기반 시드가 이전 발급 구간을 앞지름).
 */
class TransactionIdGeneratorTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	@Test
	@DisplayName("형식: GWMN + U + 날짜8 + 일련번호9 = 22자 자기설명 구조")
	void format() {
		Clock clock = Clock.fixed(Instant.parse("2026-07-09T03:00:00Z"), ZONE); // KST 2026-07-09 12:00
		TransactionIdGenerator gen = new TransactionIdGenerator(clock);

		String id = gen.next();

		assertThat(id).hasSize(22);
		assertThat(id).matches("GWMNU\\d{17}");
		assertThat(id).startsWith("GWMNU20260709"); // 날짜부가 ID에 그대로 읽힌다.
	}

	@Test
	@DisplayName("동시 채번: 16스레드 × 2000건이 동시에 뽑아도 전부 유일하다")
	void concurrentUniqueness() throws Exception {
		TransactionIdGenerator gen = new TransactionIdGenerator();
		int threads = 16;
		int perThread = 2000;
		Set<String> ids = ConcurrentHashMap.newKeySet();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int t = 0; t < threads; t++) {
				pool.submit(() -> {
					start.await();
					for (int i = 0; i < perThread; i++) {
						ids.add(gen.next());
					}
					return null;
				});
			}
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}

		// 중복이 하나라도 있으면 Set 크기가 준다.
		assertThat(ids).hasSize(threads * perThread);
	}

	@Test
	@DisplayName("재기동 안전: 5초 뒤 재기동한 채번기의 시드가 이전 발급 구간을 앞질러 겹치지 않는다")
	void restartDoesNotCollide() {
		Instant bootA = Instant.parse("2026-07-09T03:00:00Z");
		TransactionIdGenerator beforeRestart = new TransactionIdGenerator(Clock.fixed(bootA, ZONE));
		Set<String> issuedBefore = new HashSet<>();
		for (int i = 0; i < 10_000; i++) {
			issuedBefore.add(beforeRestart.next());
		}

		// 프로세스가 죽고 5초 뒤 재기동 — 시드 = 자정 이후 ms × 10 이므로 5초면 50,000 앞서 있다.
		Instant bootB = bootA.plus(Duration.ofSeconds(5));
		TransactionIdGenerator afterRestart = new TransactionIdGenerator(Clock.fixed(bootB, ZONE));
		for (int i = 0; i < 10_000; i++) {
			assertThat(issuedBefore).doesNotContain(afterRestart.next());
		}
	}
}
