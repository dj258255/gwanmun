package io.gwanmun.ledger.recon;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * EOD 대사 1회 실행의 요약 결과(영속 엔티티, Phase 9). 게이트웨이 원장 vs 계정계 당일 처리내역을
 * 전량 대조한 뒤, 불일치 4유형의 건수와 UNKNOWN 자동 해소 건수를 원장에 남긴다 —
 * <b>"원장이 검증된 진실"임을 매일 이 한 줄로 증명</b>한다.
 */
@Entity
@Table(name = "reconciliation_run")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationRun {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Instant ranAt;

	/** 대사 기준일(YYYYMMDD). */
	@Column(nullable = false, length = 8)
	private String settleDate;

	/** 양쪽있음+일치. */
	@Column(nullable = false)
	private int matchCount;

	/** 양쪽있음+금액/상태 상이. */
	@Column(nullable = false)
	private int mismatchCount;

	/** 우리만있음(계정계 미처리인데 원장 SUCCESS). */
	@Column(nullable = false)
	private int ledgerOnlyCount;

	/** 저쪽만있음(계정계 처리했는데 원장 UNKNOWN/누락). */
	@Column(nullable = false)
	private int coreOnlyCount;

	/** 이번 대사에서 UNKNOWN을 상태조회·망취소로 자동 해소한 건수. */
	@Column(nullable = false)
	private int unknownResolvedCount;

	@Column(nullable = false)
	private int ledgerTotal;

	@Column(nullable = false)
	private int coreTotal;

	public ReconciliationRun(Instant ranAt, String settleDate, int matchCount, int mismatchCount,
			int ledgerOnlyCount, int coreOnlyCount, int unknownResolvedCount,
			int ledgerTotal, int coreTotal) {
		this.ranAt = ranAt;
		this.settleDate = settleDate;
		this.matchCount = matchCount;
		this.mismatchCount = mismatchCount;
		this.ledgerOnlyCount = ledgerOnlyCount;
		this.coreOnlyCount = coreOnlyCount;
		this.unknownResolvedCount = unknownResolvedCount;
		this.ledgerTotal = ledgerTotal;
		this.coreTotal = coreTotal;
	}
}
