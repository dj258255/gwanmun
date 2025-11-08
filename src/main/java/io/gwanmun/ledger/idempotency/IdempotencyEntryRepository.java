package io.gwanmun.ledger.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

/** 멱등키 저장소(모듈 내부, Phase 9). */
public interface IdempotencyEntryRepository extends JpaRepository<IdempotencyEntry, Long> {

	Optional<IdempotencyEntry> findByIdempotencyKeyAndMethodAndPath(String key, String method, String path);

	/** TTL 청소 — 만료된 항목을 지운다(주기 배치용). 지운 건수를 돌려준다. */
	long deleteByExpiresAtBefore(Instant cutoff);
}
