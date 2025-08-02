package io.gwanmun.gateway.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 토큰버킷 자체를 가짜 시계로 검증한다. 시간을 손으로 흘려(실제 sleep 없이) 보충 로직을 결정론적으로 본다.
 */
class TokenBucketTest {

	@Test
	@DisplayName("가득 찬 용량만큼 통과하고 그다음은 거절, 시간이 흐르면 다시 채워진다")
	void consumesThenRefills() {
		AtomicLong now = new AtomicLong(0);
		// 용량 3, 초당 1개 보충.
		TokenBucket bucket = new TokenBucket(3, 1.0, now::get);

		assertThat(bucket.tryConsume()).isTrue();  // 3 → 2
		assertThat(bucket.tryConsume()).isTrue();  // 2 → 1
		assertThat(bucket.tryConsume()).isTrue();  // 1 → 0
		assertThat(bucket.tryConsume()).isFalse(); // 0 → 거절
		assertThat(bucket.remaining()).isZero();

		// 1초 흐르면 토큰 1개 보충 → 딱 한 건 더 통과.
		now.addAndGet(1_000_000_000L);
		assertThat(bucket.tryConsume()).isTrue();
		assertThat(bucket.tryConsume()).isFalse();
	}

	@Test
	@DisplayName("거절 상태에서 다음 토큰까지 남은 시간(Retry-After)이 양수로 계산된다")
	void reportsRetryAfter() {
		AtomicLong now = new AtomicLong(0);
		TokenBucket bucket = new TokenBucket(1, 1.0, now::get); // 초당 1개

		assertThat(bucket.tryConsume()).isTrue();
		assertThat(bucket.tryConsume()).isFalse();

		long ms = bucket.millisUntilRefill();
		assertThat(ms).isPositive().isLessThanOrEqualTo(1000);
	}

	@Test
	@DisplayName("시계가 거꾸로 가도(음수 경과) 토큰이 폭증하지 않는다")
	void handlesClockGoingBackwards() {
		AtomicLong now = new AtomicLong(5_000_000_000L);
		TokenBucket bucket = new TokenBucket(2, 1.0, now::get);

		assertThat(bucket.tryConsume()).isTrue();
		assertThat(bucket.tryConsume()).isTrue();
		// 시계를 과거로 되돌린다(벽시계 보정 흉내). 경과가 음수라 보충은 0이어야 한다.
		now.set(1_000_000_000L);
		assertThat(bucket.tryConsume()).isFalse();
	}
}
