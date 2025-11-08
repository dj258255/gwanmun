package io.gwanmun.web;

import io.gwanmun.core.SettlementClient.SettlementException;
import io.gwanmun.ledger.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * EOD 대사 스케줄러(Phase 9). 마감(End Of Day) 시각에 그 날짜 대사를 자동으로 돌린다 —
 * Phase 6의 수동 트리거({@code POST /api/gateway/resolve})를 넘어, 미확인·불일치를 <b>주기적으로</b>
 * 훑는 것이 이번 단계의 요점이다.
 *
 * <p>기본값은 <b>비활성(cron="-")</b>이다 — 데모·테스트에서 예기치 않게 돌지 않게. 운영에선
 * {@code gwanmun.reconciliation.cron}(예: {@code 0 5 0 * * *} — 매일 00:05)으로 켠다.
 * 멱등키 TTL 청소도 함께 건다(만료 키 정리).
 */
@Component
public class ReconciliationScheduler {

	private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);
	private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

	private final ReconciliationService reconciliation;
	private final IdempotencyService idempotency;

	public ReconciliationScheduler(ReconciliationService reconciliation, IdempotencyService idempotency) {
		this.reconciliation = reconciliation;
		this.idempotency = idempotency;
	}

	/** 마감 대사 — 기본 비활성. cron을 설정하면 그 시각에 어제/오늘 기준일로 대사를 돌린다. */
	@Scheduled(cron = "${gwanmun.reconciliation.cron:-}")
	public void runDailyReconciliation() {
		String settleDate = LocalDate.now().format(YYYYMMDD);
		log.info("스케줄된 EOD 대사 시작: 기준일={}", settleDate);
		try {
			reconciliation.reconcile(settleDate);
		} catch (SettlementException e) {
			log.warn("스케줄된 대사 실패(계정계 불통): {}", e.getMessage());
		}
	}

	/** 멱등키 TTL 청소 — 기본 비활성. 설정 시 주기적으로 만료 키를 지운다. */
	@Scheduled(cron = "${gwanmun.idempotency.purge-cron:-}")
	public void purgeIdempotencyKeys() {
		long purged = idempotency.purgeExpired();
		if (purged > 0) {
			log.info("만료 멱등키 {}건 청소", purged);
		}
	}
}
