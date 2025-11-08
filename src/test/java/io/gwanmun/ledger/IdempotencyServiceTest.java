package io.gwanmun.ledger;

import io.gwanmun.ledger.IdempotencyService.Decision;
import io.gwanmun.ledger.IdempotencyService.Kind;
import io.gwanmun.ledger.idempotency.IdempotencyEntry;
import io.gwanmun.ledger.idempotency.IdempotencyEntryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 멱등키 판정 분기를 저장소를 목킹해 격리 검증한다 — 첫 요청 PROCEED / 처리중 재요청 IN_PROGRESS /
 * 완료 재수신 REPLAY / 같은 키 다른 본문 MISMATCH / 만료 키 새 처리 / 동시 선점 레이스.
 */
class IdempotencyServiceTest {

	private static final String KEY = "idem-key-1";
	private static final String METHOD = "POST";
	private static final String PATH = "/api/gateway/balance";
	private static final String FP = "fingerprint-a";
	private static final Duration TTL = Duration.ofHours(24);

	private final IdempotencyEntryRepository repo = mock(IdempotencyEntryRepository.class);
	private final IdempotencyService service = new IdempotencyService(repo, new SimpleMeterRegistry());

	private IdempotencyEntry entry(String fingerprint, Instant expiresAt) {
		Instant now = Instant.now();
		return new IdempotencyEntry(KEY, METHOD, PATH, fingerprint, "GWMNU...001", now, expiresAt);
	}

	@Test
	@DisplayName("첫 요청은 IN_PROGRESS 선점 후 PROCEED")
	void freshKeyProceeds() {
		when(repo.findByIdempotencyKeyAndMethodAndPath(KEY, METHOD, PATH)).thenReturn(Optional.empty());

		Decision d = service.begin(KEY, METHOD, PATH, FP, "GWMNU...001", TTL);

		assertThat(d.kind()).isEqualTo(Kind.PROCEED);
		verify(repo).saveAndFlush(any(IdempotencyEntry.class));
	}

	@Test
	@DisplayName("완료된 요청 재수신 → 저장된 원응답을 재반환(REPLAY)")
	void completedKeyReplays() {
		IdempotencyEntry e = entry(FP, Instant.now().plus(Duration.ofHours(1)));
		e.complete(200, "{\"ledgerStatus\":\"SUCCESS\"}");
		when(repo.findByIdempotencyKeyAndMethodAndPath(KEY, METHOD, PATH)).thenReturn(Optional.of(e));

		Decision d = service.begin(KEY, METHOD, PATH, FP, "GWMNU...002", TTL);

		assertThat(d.kind()).isEqualTo(Kind.REPLAY);
		assertThat(d.httpStatus()).isEqualTo(200);
		assertThat(d.responseBody()).contains("SUCCESS");
		assertThat(d.tranId()).isEqualTo("GWMNU...001"); // 원거래 ID — 새 거래 안 만든다.
	}

	@Test
	@DisplayName("처리 중인 같은 키의 재요청 → IN_PROGRESS(호출 측은 409)")
	void inProgressKeyConflicts() {
		IdempotencyEntry e = entry(FP, Instant.now().plus(Duration.ofHours(1))); // IN_PROGRESS 기본
		when(repo.findByIdempotencyKeyAndMethodAndPath(KEY, METHOD, PATH)).thenReturn(Optional.of(e));

		Decision d = service.begin(KEY, METHOD, PATH, FP, "GWMNU...003", TTL);

		assertThat(d.kind()).isEqualTo(Kind.IN_PROGRESS);
	}

	@Test
	@DisplayName("같은 키인데 본문이 다르면 재실행도 재반환도 안 하고 거절(PAYLOAD_MISMATCH)")
	void differentPayloadRejected() {
		IdempotencyEntry e = entry("fingerprint-DIFFERENT", Instant.now().plus(Duration.ofHours(1)));
		when(repo.findByIdempotencyKeyAndMethodAndPath(KEY, METHOD, PATH)).thenReturn(Optional.of(e));

		Decision d = service.begin(KEY, METHOD, PATH, FP, "GWMNU...004", TTL);

		assertThat(d.kind()).isEqualTo(Kind.PAYLOAD_MISMATCH);
	}

	@Test
	@DisplayName("만료된 키는 없는 것으로 보고 지운 뒤 새로 처리(PROCEED)")
	void expiredKeyIsReplaced() {
		IdempotencyEntry expired = entry(FP, Instant.now().minusSeconds(10));
		when(repo.findByIdempotencyKeyAndMethodAndPath(KEY, METHOD, PATH)).thenReturn(Optional.of(expired));

		Decision d = service.begin(KEY, METHOD, PATH, FP, "GWMNU...005", TTL);

		assertThat(d.kind()).isEqualTo(Kind.PROCEED);
		verify(repo).delete(expired);
		verify(repo).saveAndFlush(any(IdempotencyEntry.class));
	}

	@Test
	@DisplayName("동시 선점 레이스 — INSERT가 유니크 제약에 걸리면 재조회로 IN_PROGRESS를 본다")
	void concurrentInsertLosesToUnique() {
		IdempotencyEntry winner = entry(FP, Instant.now().plus(Duration.ofHours(1)));
		// 첫 조회는 없음(선점 시도) → INSERT는 제약 위반 → 재조회는 승자의 IN_PROGRESS.
		when(repo.findByIdempotencyKeyAndMethodAndPath(KEY, METHOD, PATH))
				.thenReturn(Optional.empty(), Optional.of(winner));
		when(repo.saveAndFlush(any(IdempotencyEntry.class)))
				.thenThrow(new DataIntegrityViolationException("duplicate key"));

		Decision d = service.begin(KEY, METHOD, PATH, FP, "GWMNU...006", TTL);

		assertThat(d.kind()).isEqualTo(Kind.IN_PROGRESS);
	}
}
