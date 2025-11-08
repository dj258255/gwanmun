package io.gwanmun.ledger.recon;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 대사 실행 이력 저장소(모듈 내부, Phase 9). */
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, Long> {

	List<ReconciliationRun> findByOrderByIdDesc(Pageable pageable);
}
