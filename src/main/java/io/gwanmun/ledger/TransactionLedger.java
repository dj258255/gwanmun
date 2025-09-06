package io.gwanmun.ledger;

import io.gwanmun.ledger.store.LedgerEntry;
import io.gwanmun.ledger.store.LedgerEntryRepository;
import io.gwanmun.message.AccountMasker;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 거래 원장 서비스. 게이트웨이를 지나간 거래 하나를 원장 한 줄로 적는다.
 *
 * <p><b>원칙 1 — 적재가 거래를 막지 않는다.</b> DB insert는 전용 스레드에서 비동기로 한다.
 * 거래 스레드는 큐에 넣고 즉시 돌아가며, 큐가 가득 차거나 DB가 죽어 있어도 <b>거래는 정상 진행</b>되고
 * WARN 로그만 남는다. 관측은 부가 기능이지 거래 경로의 관문이 아니다.
 *
 * <p><b>원칙 2 — 계좌 원문은 저장하지 않는다.</b> 적재 직전에 {@link AccountMasker}로 마스킹한다.
 * 마스킹 지점을 저장 직전 한 곳으로 못 박아, 어떤 경로로 기록돼도 원장에 원문이 남을 수 없다.
 *
 * <p>상태별 카운터({@code gwanmun.ledger.transactions})는 적재 성공 여부와 무관하게 거래 시점에
 * 올린다 — 메트릭은 DB와 별개의 관측 축이다.
 */
@Service
public class TransactionLedger {

	private static final Logger log = LoggerFactory.getLogger(TransactionLedger.class);

	private static final int MAX_DETAIL_LENGTH = 200;
	private static final int DEFAULT_QUEUE_CAPACITY = 1000;
	private static final int MAX_RECENT_LIMIT = 100;

	private final LedgerEntryRepository repository;
	private final MeterRegistry meterRegistry;
	private final Executor writer;
	private final ExecutorService ownedWriter; // 스프링 기동 시 직접 만든 경우만 종료 책임을 진다.

	@Autowired
	public TransactionLedger(LedgerEntryRepository repository, MeterRegistry meterRegistry) {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(DEFAULT_QUEUE_CAPACITY), r -> {
					Thread t = new Thread(r, "ledger-writer");
					t.setDaemon(true);
					return t;
				});
		this.repository = repository;
		this.meterRegistry = meterRegistry;
		this.writer = executor;
		this.ownedWriter = executor;
	}

	/** 테스트용: 실행기를 주입한다(동기 실행기로 비동기성을 걷어내고 계약만 검증). */
	TransactionLedger(LedgerEntryRepository repository, MeterRegistry meterRegistry, Executor writer) {
		this.repository = repository;
		this.meterRegistry = meterRegistry;
		this.writer = writer;
		this.ownedWriter = null;
	}

	/**
	 * 거래 하나를 원장에 비동기 적재한다. <b>이 메서드는 예외를 던지지 않는다</b> — 원장이 어떤 상태여도
	 * 호출한 거래는 계속 진행된다.
	 */
	public void record(LedgerRecord record) {
		meterRegistry.counter("gwanmun.ledger.transactions", "status", record.status().name())
				.increment();
		try {
			writer.execute(() -> persist(record));
		} catch (RejectedExecutionException e) {
			log.warn("원장 적재 큐 포화 — 이 거래의 원장 기록을 건너뜁니다(거래는 정상 진행): txId={}",
					record.transactionId());
		}
	}

	private void persist(LedgerRecord r) {
		try {
			repository.save(new LedgerEntry(
					r.transactionId(), r.txCode(),
					AccountMasker.mask(r.accountNo()), // 저장 직전 마스킹 — 원문은 여기서 끝난다.
					r.status(), r.responseCode(), truncate(r.detail()),
					r.requestedAt(), r.requestedAt().plusMillis(r.elapsedMs()),
					r.elapsedMs(), r.correlationId()));
		} catch (RuntimeException e) {
			// 원장 DB 장애가 거래 장애로 번지면 안 된다. 거래는 이미 끝났고, 여기선 기록만 실패했다.
			log.warn("원장 적재 실패(거래는 이미 정상 진행됨): txId={} status={} 원인={}",
					r.transactionId(), r.status(), e.toString());
		}
	}

	/** 최근 거래 N건(최신 먼저). 화면·API용 읽기 전용 뷰. */
	public List<LedgerView> recent(int limit) {
		int n = Math.max(1, Math.min(MAX_RECENT_LIMIT, limit));
		return repository.findByOrderByIdDesc(PageRequest.of(0, n)).stream()
				.map(e -> new LedgerView(e.getTransactionId(), e.getTxCode(), e.getAccountMasked(),
						e.getStatus(), e.getResponseCode(), e.getDetail(),
						e.getRequestedAt(), e.getElapsedMs(), e.getCorrelationId()))
				.toList();
	}

	/** 상태별 누적 건수(3값 전부, 없으면 0). */
	public Map<TransactionStatus, Long> summary() {
		Map<TransactionStatus, Long> counts = new EnumMap<>(TransactionStatus.class);
		for (TransactionStatus status : TransactionStatus.values()) {
			counts.put(status, repository.countByStatus(status));
		}
		return counts;
	}

	@PreDestroy
	void shutdown() {
		if (ownedWriter == null) {
			return;
		}
		// graceful shutdown — 큐에 남은 적재를 마저 쓰고 닫는다(무한정은 아니고 3초까지만).
		ownedWriter.shutdown();
		try {
			if (!ownedWriter.awaitTermination(3, TimeUnit.SECONDS)) {
				log.warn("원장 적재 스레드가 3초 안에 못 비웠습니다 — 남은 기록을 버리고 종료합니다.");
				ownedWriter.shutdownNow();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			ownedWriter.shutdownNow();
		}
	}

	private static String truncate(String s) {
		if (s == null) {
			return null;
		}
		return s.length() <= MAX_DETAIL_LENGTH ? s : s.substring(0, MAX_DETAIL_LENGTH);
	}

	/**
	 * 적재 입력. 계좌번호는 원문으로 받되 저장 시 마스킹된다(원문은 영속되지 않는다).
	 *
	 * @param responseCode 계정계 응답코드(응답을 못 받았으면 null)
	 * @param detail       실패·미확인 사유(정상이면 null)
	 */
	public record LedgerRecord(
			String transactionId,
			String txCode,
			String accountNo,
			TransactionStatus status,
			String responseCode,
			String detail,
			Instant requestedAt,
			long elapsedMs,
			String correlationId
	) {
	}

	/** 원장 한 줄의 읽기 전용 뷰(계좌는 이미 마스킹된 값). */
	public record LedgerView(
			String transactionId,
			String txCode,
			String accountMasked,
			TransactionStatus status,
			String responseCode,
			String detail,
			Instant requestedAt,
			long elapsedMs,
			String correlationId
	) {
	}
}
