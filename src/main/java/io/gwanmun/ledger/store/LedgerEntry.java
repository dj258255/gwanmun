package io.gwanmun.ledger.store;

import io.gwanmun.ledger.TransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 거래 원장 한 줄(영속 엔티티). 모듈 내부(store)에 감춰 두고, 밖에는
 * {@code TransactionLedger.LedgerView}로만 나간다.
 *
 * <p>계좌번호는 <b>마스킹된 형태만</b> 저장한다 — 이 테이블 어디에도 계좌 원문이 없다.
 */
@Entity
@Table(name = "transaction_ledger", indexes = {
		@Index(name = "idx_ledger_tx_id", columnList = "transactionId", unique = true),
		@Index(name = "idx_ledger_status", columnList = "status")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 채번된 거래고유번호 (GWMNU + 날짜8 + 일련번호9). */
	@Column(nullable = false, length = 32)
	private String transactionId;

	/** 거래코드 (IN01=잔액조회, HI01=거래내역조회). */
	@Column(nullable = false, length = 8)
	private String txCode;

	/** 마스킹된 계좌번호 — 원문은 저장하지 않는다. */
	@Column(nullable = false, length = 40)
	private String accountMasked;

	/** 3값 상태: SUCCESS / FAILED / UNKNOWN. */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private TransactionStatus status;

	/** 계정계 응답코드(응답을 받은 경우만, 못 받았으면 null). */
	@Column(length = 8)
	private String responseCode;

	/**
	 * 거래 금액(원). 잔액조회는 계정계가 돌려준 잔액을 싣는다 — EOD 대사에서 계정계측 기록과
	 * 대조할 유일한 수치다(Phase 9). 금액 개념이 없는 거래(입력 오류·거래내역 등)는 null.
	 */
	@Column
	private Long amount;

	/** 실패·미확인 사유(짧게 자름). */
	@Column(length = 200)
	private String detail;

	/** 요청 시각. */
	@Column(nullable = false)
	private Instant requestedAt;

	/** 응답(또는 포기) 시각. */
	@Column(nullable = false)
	private Instant respondedAt;

	/** 왕복 소요 밀리초. */
	@Column(nullable = false)
	private long elapsedMs;

	/** 이 거래가 속한 HTTP 요청의 correlation ID. */
	@Column(length = 64)
	private String correlationId;

	/** UNKNOWN 해소 시각(해소된 경우만, Phase 6). */
	@Column
	private Instant resolvedAt;

	/** 해소 방법: NET_CANCEL(망취소로 무효화) / STATUS_INQUIRY(상태조회로 미처리 확인). */
	@Column(length = 20)
	private String resolutionMethod;

	public LedgerEntry(String transactionId, String txCode, String accountMasked,
			TransactionStatus status, String responseCode, String detail,
			Instant requestedAt, Instant respondedAt, long elapsedMs, String correlationId,
			Long amount) {
		this.transactionId = transactionId;
		this.txCode = txCode;
		this.accountMasked = accountMasked;
		this.status = status;
		this.responseCode = responseCode;
		this.detail = detail;
		this.requestedAt = requestedAt;
		this.respondedAt = respondedAt;
		this.elapsedMs = elapsedMs;
		this.correlationId = correlationId;
		this.amount = amount;
	}

	/**
	 * UNKNOWN 거래를 확정 짓는다 (Phase 6). 상태와 함께 해소 시각·방법·사유를 남겨,
	 * "언제 어떻게 UNKNOWN에서 벗어났는지"가 원장 스스로 읽히게 한다.
	 */
	public void resolve(TransactionStatus newStatus, String method, String resolutionDetail, Instant at) {
		this.status = newStatus;
		this.resolutionMethod = method;
		this.detail = resolutionDetail;
		this.resolvedAt = at;
	}
}
