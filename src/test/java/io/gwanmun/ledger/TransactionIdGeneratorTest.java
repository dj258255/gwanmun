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

	/** 테스트에서 시간을 밀 수 있는 가변 시계. */
	private static final class MutableClock extends Clock {
		private volatile Instant now;

		MutableClock(Instant start) {
			this.now = start;
		}

		void set(Instant instant) {
			this.now = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZONE;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	@Test
	@DisplayName("자정 롤오버(Phase 7): 날짜가 바뀌면 재시드된다 — 프로세스가 자정을 넘겨 살아도 시각 시드 불변식이 유지된다")
	void midnightRolloverReseeds() {
		// KST 23:50 기동 — 시드는 하루의 거의 끝(≈858,000,000)에 있다.
		MutableClock clock = new MutableClock(Instant.parse("2026-07-08T14:50:00Z")); // KST 2026-07-08 23:50
		TransactionIdGenerator gen = new TransactionIdGenerator(clock);
		Set<String> beforeMidnight = new HashSet<>();
		for (int i = 0; i < 1_000; i++) {
			beforeMidnight.add(gen.next());
		}
		assertThat(beforeMidnight.iterator().next()).startsWith("GWMNU20260708");

		// 자정을 넘겼다(KST 00:10). 재시드가 없으면 새 날짜의 일련번호가 858,000,000대에서 이어져
		// "시드 = 자정 이후 흐른 시간" 불변식이 깨진다 — 그 구간을 나중에(같은 날 23시대) 자연 시드가
		// 다시 밟으면 같은 날짜에 같은 일련번호가 두 번 나온다.
		clock.set(Instant.parse("2026-07-08T15:10:00Z")); // KST 2026-07-09 00:10
		Set<String> afterMidnight = new HashSet<>();
		for (int i = 0; i < 1_000; i++) {
			afterMidnight.add(gen.next());
		}
		assertThat(afterMidnight.iterator().next()).startsWith("GWMNU20260709");
		// 재시드 확인: 00:10의 시각 시드는 10분 × 60,000ms × 10 = 6,000,000 부근이어야 한다.
		long firstSeq = Long.parseLong(afterMidnight.stream().sorted().findFirst().orElseThrow().substring(13));
		assertThat(firstSeq).isBetween(6_000_000L, 6_100_000L);

		// 같은 날 저녁, 별개 재기동 인스턴스가 자연 시드로 그 시각대에 도달해도(23:50 ≈ 858,000,000)
		// 자정 직후 발급분(6,000,000대)과 절대 겹치지 않는다.
		MutableClock evening = new MutableClock(Instant.parse("2026-07-09T14:50:00Z")); // KST 2026-07-09 23:50
		TransactionIdGenerator eveningGen = new TransactionIdGenerator(evening);
		for (int i = 0; i < 1_000; i++) {
			String id = eveningGen.next();
			assertThat(afterMidnight).doesNotContain(id);
			assertThat(beforeMidnight).doesNotContain(id);
		}
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
