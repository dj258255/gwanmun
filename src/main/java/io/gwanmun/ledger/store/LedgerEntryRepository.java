package io.gwanmun.ledger.store;

import io.gwanmun.ledger.TransactionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 거래 원장 저장소(모듈 내부). */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	/** 최근 거래 N건(최신 먼저). */
	List<LedgerEntry> findByOrderByIdDesc(Pageable pageable);

	/** 상태별 건수(원장 요약용). */
	long countByStatus(TransactionStatus status);

	/** 거래고유번호로 한 건(해소 대상 찾기, Phase 6). */
	Optional<LedgerEntry> findByTransactionId(String transactionId);

	/** 특정 상태의 거래 N건(최신 먼저) — UNKNOWN 목록용(Phase 6). */
	List<LedgerEntry> findByStatusOrderByIdDesc(TransactionStatus status, Pageable pageable);

	/** 거래고유번호 접두어로 하루치 전량 — EOD 대사 대상(Phase 9). */
	List<LedgerEntry> findByTransactionIdStartingWithOrderByIdDesc(String prefix);
}
