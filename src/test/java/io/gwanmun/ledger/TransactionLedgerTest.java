package io.gwanmun.ledger;

import io.gwanmun.ledger.TransactionLedger.LedgerRecord;
import io.gwanmun.ledger.store.LedgerEntry;
import io.gwanmun.ledger.store.LedgerEntryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 원장 적재의 두 계약을 검증한다 — (1) 계좌는 저장 직전 마스킹된다,
 * (2) <b>적재가 실패해도 거래는 막히지 않는다</b>(record는 예외를 던지지 않는다).
 *
 * <p>비동기성 자체는 걷어내고(동기 실행기 주입) 계약만 본다 — 스레드 스케줄링이 아니라
 * "무엇이 저장되고, 실패가 어디서 멈추는가"가 검증 대상이다.
 */
class TransactionLedgerTest {

	private final LedgerEntryRepository repository = mock(LedgerEntryRepository.class);
	private final TransactionLedger ledger =
			new TransactionLedger(repository, new SimpleMeterRegistry(), Runnable::run);

	private static LedgerRecord sample(TransactionStatus status) {
		return new LedgerRecord("GWMNU20260709000000001", "IN01", "12345678901234",
				status, "0000", null, Instant.now(), 12, "cid-test-0001");
	}

	@Test
	@DisplayName("계좌번호는 저장 직전 마스킹된다 — 원장에 원문이 절대 안 남는다")
	void masksAccountBeforePersist() {
		ledger.record(sample(TransactionStatus.SUCCESS));

		ArgumentCaptor<LedgerEntry> saved = ArgumentCaptor.forClass(LedgerEntry.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue().getAccountMasked()).isEqualTo("123456****1234");
		assertThat(saved.getValue().getAccountMasked()).doesNotContain("12345678901234");
		assertThat(saved.getValue().getTransactionId()).isEqualTo("GWMNU20260709000000001");
		assertThat(saved.getValue().getStatus()).isEqualTo(TransactionStatus.SUCCESS);
	}

	@Test
	@DisplayName("원장 DB가 죽어 save가 예외를 던져도 record()는 예외를 안 던진다(거래를 막지 않음)")
	void persistFailureDoesNotBlockTransaction() {
		when(repository.save(any())).thenThrow(new RuntimeException("DB connection refused"));

		// 거래 스레드가 이 호출에서 죽으면 안 된다 — WARN 로그만 남기고 삼킨다.
		assertThatCode(() -> ledger.record(sample(TransactionStatus.UNKNOWN)))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("적재 실패(unique 위반 등)는 삼키되 dropped 카운터에 남는다 — 유실은 보이는 유실이어야 한다 (Phase 7)")
	void persistFailureIncrementsDroppedCounter() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		LedgerEntryRepository failing = mock(LedgerEntryRepository.class);
		when(failing.save(any())).thenThrow(new RuntimeException("duplicate key value violates unique constraint"));
		TransactionLedger failingLedger = new TransactionLedger(failing, meters, Runnable::run);

		failingLedger.record(sample(TransactionStatus.SUCCESS));
		failingLedger.record(sample(TransactionStatus.SUCCESS));

		assertThat(meters.counter("gwanmun.ledger.dropped", "reason", "persist_error").count())
				.isEqualTo(2.0);
	}

	@Test
	@DisplayName("적재 큐 포화(RejectedExecutionException)도 dropped 카운터에 남는다 (Phase 7)")
	void queueSaturationIncrementsDroppedCounter() {
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		TransactionLedger saturated = new TransactionLedger(repository, meters, task -> {
			throw new java.util.concurrent.RejectedExecutionException("큐 포화");
		});

		assertThatCode(() -> saturated.record(sample(TransactionStatus.SUCCESS)))
				.doesNotThrowAnyException();
		assertThat(meters.counter("gwanmun.ledger.dropped", "reason", "queue_full").count())
				.isEqualTo(1.0);
	}

	@Test
	@DisplayName("resolve(): UNKNOWN 거래를 CANCELED로 확정하고 해소 시각·방법을 남긴다 (Phase 6)")
	void resolveConfirmsUnknownTransaction() {
		LedgerEntry unknown = new LedgerEntry("GWMNU20260709000000002", "IN01", "123456****1234",
				TransactionStatus.UNKNOWN, null, "Read timed out",
				Instant.now(), Instant.now(), 3011, "cid-test-0002", null);
		when(repository.findByTransactionId("GWMNU20260709000000002"))
				.thenReturn(Optional.of(unknown));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		var view = ledger.resolve("GWMNU20260709000000002", TransactionStatus.CANCELED,
				"NET_CANCEL", "상태조회 처리됨 → 망취소 성공");

		assertThat(view.status()).isEqualTo(TransactionStatus.CANCELED);
		assertThat(view.resolutionMethod()).isEqualTo("NET_CANCEL");
		assertThat(view.resolvedAt()).isNotNull();
		assertThat(unknown.getStatus()).isEqualTo(TransactionStatus.CANCELED);
	}

	@Test
	@DisplayName("resolve(): UNKNOWN이 아닌 거래는 건드리지 않는다 — IllegalStateException")
	void resolveRejectsNonUnknown() {
		LedgerEntry success = new LedgerEntry("GWMNU20260709000000003", "IN01", "123456****1234",
				TransactionStatus.SUCCESS, "0000", null,
				Instant.now(), Instant.now(), 3, "cid-test-0003", null);
		when(repository.findByTransactionId("GWMNU20260709000000003"))
				.thenReturn(Optional.of(success));

		assertThatThrownBy(() ->
				ledger.resolve("GWMNU20260709000000003", TransactionStatus.CANCELED, "NET_CANCEL", ""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("UNKNOWN");
	}
}
