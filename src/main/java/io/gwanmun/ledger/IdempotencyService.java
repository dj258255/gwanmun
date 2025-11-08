package io.gwanmun.ledger;

import io.gwanmun.ledger.idempotency.IdempotencyEntry;
import io.gwanmun.ledger.idempotency.IdempotencyEntry.State;
import io.gwanmun.ledger.idempotency.IdempotencyEntryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 멱등키 처리(Phase 9). 호출자 재전송을 게이트웨이가 구분·차단한다.
 *
 * <p>흐름은 <b>begin → (처리) → complete</b> 두 단계다.
 * <ul>
 *   <li><b>begin</b>: (키+메서드+경로)로 첫 요청이면 IN_PROGRESS 행을 <b>DB 유니크 제약으로
 *       원자적으로</b> 선점하고 PROCEED. 이미 처리 중이면 IN_PROGRESS(→409), 완료됐으면
 *       저장된 원응답을 그대로 재반환(REPLAY, 재실행 없음), 같은 키인데 본문이 다르면 MISMATCH.</li>
 *   <li><b>complete</b>: 처리가 끝나면 원응답(HTTP 상태 + 본문)을 박아 둔다 — 다음 재수신이 이걸 되돌려준다.</li>
 * </ul>
 *
 * <p>동시성의 열쇠는 애플리케이션 락이 아니라 <b>DB 유니크 제약</b>이다 — 같은 키로 두 요청이
 * 동시에 들어오면 INSERT에 먼저 성공한 쪽만 PROCEED를 받고, 진 쪽은 제약 위반을 잡아 재조회로
 * IN_PROGRESS를 본다(409). 단일 노드 인메모리 락으로는 다중 인스턴스에서 깨지지만, DB 제약은 안 깨진다.
 *
 * <p>이 서비스는 원장(ledger)이 소유한 PG에 저장하되, HTTP·전문은 모른다 — begin/complete만 제공하고
 * 실제 요청 처리와 원응답 직렬화는 조립층(web)이 한다(모듈 경계).
 */
@Service
public class IdempotencyService {

	private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

	private final IdempotencyEntryRepository repository;
	private final MeterRegistry meterRegistry;

	public IdempotencyService(IdempotencyEntryRepository repository, MeterRegistry meterRegistry) {
		this.repository = repository;
		this.meterRegistry = meterRegistry;
	}

	/** begin 판정 — 조립층은 이 종류를 보고 PROCEED/409/재반환/거절을 정한다. */
	public enum Kind {
		/** 첫 요청(또는 만료된 키) — 처리하고 나중에 complete 하라. */
		PROCEED,
		/** 같은 키가 지금 처리 중 — 동시 재요청. 409로 거절하라. */
		IN_PROGRESS,
		/** 이미 완료된 요청 — 저장된 원응답을 재실행 없이 그대로 돌려주라. */
		REPLAY,
		/** 같은 키인데 요청 본문이 다름 — 재실행도 재반환도 하지 말고 거절(422)하라. */
		PAYLOAD_MISMATCH
	}

	/**
	 * begin 결과. {@code REPLAY}일 때만 {@code httpStatus}/{@code responseBody}/{@code tranId}가 있다.
	 */
	public record Decision(Kind kind, Integer httpStatus, String responseBody, String tranId) {
		static Decision proceed() {
			return new Decision(Kind.PROCEED, null, null, null);
		}

		static Decision inProgress() {
			return new Decision(Kind.IN_PROGRESS, null, null, null);
		}

		static Decision replay(IdempotencyEntry e) {
			return new Decision(Kind.REPLAY, e.getHttpStatus(), e.getResponseBody(), e.getTranId());
		}

		static Decision mismatch() {
			return new Decision(Kind.PAYLOAD_MISMATCH, null, null, null);
		}
	}

	/**
	 * 멱등키를 선점하거나, 이미 처리된 요청의 판정을 돌려준다.
	 *
	 * @param key         호출자가 보낸 {@code Idempotency-Key}
	 * @param fingerprint 요청 본문 지문({@link #fingerprint})
	 * @param tranId      이 요청이 채번한 거래고유번호(PROCEED로 이어질 때 저장)
	 * @param ttl         이 키의 보존 기간
	 */
	public Decision begin(String key, String method, String path, String fingerprint,
			String tranId, Duration ttl) {
		Instant now = Instant.now();

		Optional<IdempotencyEntry> existing =
				repository.findByIdempotencyKeyAndMethodAndPath(key, method, path);
		if (existing.isPresent()) {
			IdempotencyEntry e = existing.get();
			if (e.isExpired(now)) {
				// 만료된 키는 없는 것으로 본다 — 지우고 새로 처리한다.
				repository.delete(e);
			} else {
				return decide(e, fingerprint);
			}
		}

		// IN_PROGRESS 선점 — 유니크 제약이 동시 선점을 하나로 만든다.
		try {
			repository.saveAndFlush(new IdempotencyEntry(
					key, method, path, fingerprint, tranId, now, now.plus(ttl)));
			count("proceed");
			return Decision.proceed();
		} catch (DataIntegrityViolationException race) {
			// 조회~INSERT 사이에 다른 스레드가 같은 키를 선점했다 — 재조회로 판정한다.
			IdempotencyEntry e = repository.findByIdempotencyKeyAndMethodAndPath(key, method, path)
					.orElse(null);
			if (e == null) {
				count("proceed");
				return Decision.proceed(); // 그 사이 만료·삭제됐다(희귀) — 처리 진행.
			}
			return decide(e, fingerprint);
		}
	}

	private Decision decide(IdempotencyEntry e, String fingerprint) {
		if (!e.getFingerprint().equals(fingerprint)) {
			count("mismatch");
			log.warn("멱등키 본문 불일치: key={} method={} path={} — 같은 키에 다른 요청",
					e.getIdempotencyKey(), e.getMethod(), e.getPath());
			return Decision.mismatch();
		}
		if (e.getState() == State.COMPLETED) {
			count("replay");
			log.info("멱등키 재수신 — 원응답 재반환(재실행 안 함): key={} tranId={}",
					e.getIdempotencyKey(), e.getTranId());
			return Decision.replay(e);
		}
		count("in_progress");
		return Decision.inProgress();
	}

	/** 처리 완료 — 원응답을 저장해 다음 재수신이 그대로 되돌려받게 한다. */
	@Transactional
	public void complete(String key, String method, String path, int httpStatus, String responseBody) {
		repository.findByIdempotencyKeyAndMethodAndPath(key, method, path).ifPresent(e -> {
			e.complete(httpStatus, responseBody);
			repository.save(e);
		});
	}

	/**
	 * 선점만 하고 완료로 못 간 키를 놓아준다(예: 내부 풀 고갈 503 — 계정계로 나가지도 못한 일시 실패).
	 * 이런 건은 원응답으로 굳히면 안 된다 — 같은 키로 재시도할 수 있게 IN_PROGRESS 행을 지운다.
	 */
	@Transactional
	public void release(String key, String method, String path) {
		repository.findByIdempotencyKeyAndMethodAndPath(key, method, path)
				.filter(e -> e.getState() == State.IN_PROGRESS)
				.ifPresent(repository::delete);
	}

	/** TTL 청소 — 만료된 키를 지운다(주기 배치). 지운 건수. */
	@Transactional
	public long purgeExpired() {
		return repository.deleteByExpiresAtBefore(Instant.now());
	}

	private void count(String outcome) {
		meterRegistry.counter("gwanmun.idempotency", "outcome", outcome).increment();
	}

	/** 요청 본문 지문(SHA-256 hex). 같은 키·같은 본문인지 대조하는 데 쓴다. */
	public static String fingerprint(String canonicalRequest) {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] hash = md.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 미지원", e);
		}
	}
}
