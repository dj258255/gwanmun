package io.gwanmun.ledger;

import io.gwanmun.ledger.recon.ReconciliationRun;
import io.gwanmun.ledger.recon.ReconciliationRunRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 대사 실행 이력의 원장 기록(Phase 9). 조립층(web)의 대사 서비스가 한 번 돌 때마다 요약을 여기에
 * 남긴다 — 대사가 언제 돌았고 4유형이 각각 몇 건이었는지가 원장 스스로 읽히게.
 */
@Service
public class ReconciliationLog {

	private static final int MAX_RECENT = 50;

	private final ReconciliationRunRepository repository;

	public ReconciliationLog(ReconciliationRunRepository repository) {
		this.repository = repository;
	}

	/** 대사 1회 요약을 저장하고 저장된 뷰를 돌려준다. */
	public RunView save(String settleDate, int match, int mismatch, int ledgerOnly, int coreOnly,
			int unknownResolved, int ledgerTotal, int coreTotal) {
		ReconciliationRun saved = repository.save(new ReconciliationRun(
				Instant.now(), settleDate, match, mismatch, ledgerOnly, coreOnly,
				unknownResolved, ledgerTotal, coreTotal));
		return view(saved);
	}

	/** 최근 대사 실행 N건(최신 먼저). */
	public List<RunView> recent(int limit) {
		int n = Math.max(1, Math.min(MAX_RECENT, limit));
		return repository.findByOrderByIdDesc(PageRequest.of(0, n)).stream()
				.map(ReconciliationLog::view)
				.toList();
	}

	private static RunView view(ReconciliationRun r) {
		return new RunView(r.getId(), r.getRanAt(), r.getSettleDate(), r.getMatchCount(),
				r.getMismatchCount(), r.getLedgerOnlyCount(), r.getCoreOnlyCount(),
				r.getUnknownResolvedCount(), r.getLedgerTotal(), r.getCoreTotal());
	}

	/** 대사 실행 한 줄의 읽기 전용 뷰. */
	public record RunView(Long id, Instant ranAt, String settleDate, int matchCount, int mismatchCount,
			int ledgerOnlyCount, int coreOnlyCount, int unknownResolvedCount,
			int ledgerTotal, int coreTotal) {
	}
}
