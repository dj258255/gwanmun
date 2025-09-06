package io.gwanmun.ledger.store;

import io.gwanmun.ledger.TransactionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 거래 원장 저장소(모듈 내부). */
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	/** 최근 거래 N건(최신 먼저). */
	List<LedgerEntry> findByOrderByIdDesc(Pageable pageable);

	/** 상태별 건수(원장 요약용). */
	long countByStatus(TransactionStatus status);
}
