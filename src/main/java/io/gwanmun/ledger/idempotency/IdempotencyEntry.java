package io.gwanmun.ledger.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 멱등키 한 줄(영속 엔티티, Phase 9). 호출자가 보낸 {@code Idempotency-Key}를
 * <b>(키 + 메서드 + 경로)</b>로 못 박아, 같은 요청의 재전송을 게이트웨이가 구분한다.
 *
 * <p>이 테이블이 곧 "이 요청을 이미 처리했는가"의 단일 진실이다 — DB의 유니크 제약이 동시
 * 재요청을 원자적으로 가른다(먼저 INSERT에 성공한 쪽이 처리 주체, 진 쪽은 409).
 *
 * <p>완료된 요청은 <b>원응답(HTTP 상태 + 본문 JSON)을 그대로 저장</b>해 두었다가 재수신 시
 * 재실행 없이 되돌려준다(토스페이먼츠·오픈뱅킹 관례). 채번된 거래고유번호도 함께 실어,
 * 재전송이 새 거래를 만들지 않고 원거래 하나로 수렴함을 보인다.
 */
@Entity
@Table(name = "idempotency_key", uniqueConstraints =
		@UniqueConstraint(name = "uq_idem_key_method_path", columnNames = {"idem_key", "method", "path"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyEntry {

	/** 처리 진행 단계 — 동시 재요청 판정의 핵심. */
	public enum State {
		/** 첫 요청이 처리 중(원응답 아직 없음). 이 상태의 재요청은 409. */
		IN_PROGRESS,
		/** 처리 완료(원응답 저장됨). 이 상태의 재요청은 저장된 원응답을 재반환. */
		COMPLETED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 호출자가 보낸 멱등키(권장 UUID). */
	@Column(name = "idem_key", nullable = false, length = 128)
	private String idempotencyKey;

	/** HTTP 메서드 — 키 재사용을 메서드/경로로 못 박는다(다른 자원에 같은 키가 겹쳐도 안전). */
	@Column(nullable = false, length = 8)
	private String method;

	/** 요청 경로. */
	@Column(nullable = false, length = 200)
	private String path;

	/**
	 * 요청 본문 지문(SHA-256 hex). 같은 키인데 <b>본문이 다르면</b> 계약 위반이다 —
	 * 재실행도 재반환도 하지 않고 거절한다(토스 규격: 같은 키 다른 요청 = 오류).
	 */
	@Column(nullable = false, length = 64)
	private String fingerprint;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 12)
	private State state;

	/** 저장된 원응답 HTTP 상태(완료 시). */
	@Column
	private Integer httpStatus;

	/** 저장된 원응답 본문 JSON(완료 시). */
	@Column(length = 20000)
	private String responseBody;

	/** 이 요청이 채번한 거래고유번호(멱등키 ↔ tranId 매핑, Phase 5 채번 인프라와 결합). */
	@Column(length = 32)
	private String tranId;

	@Column(nullable = false)
	private Instant createdAt;

	/** 만료 시각(TTL). 지난 항목은 없는 것으로 보고 새로 처리한다. */
	@Column(nullable = false)
	private Instant expiresAt;

	public IdempotencyEntry(String idempotencyKey, String method, String path, String fingerprint,
			String tranId, Instant createdAt, Instant expiresAt) {
		this.idempotencyKey = idempotencyKey;
		this.method = method;
		this.path = path;
		this.fingerprint = fingerprint;
		this.state = State.IN_PROGRESS;
		this.tranId = tranId;
		this.createdAt = createdAt;
		this.expiresAt = expiresAt;
	}

	/** 처리 완료 — 원응답을 박아 두었다가 재수신 시 그대로 재반환한다. */
	public void complete(int httpStatus, String responseBody) {
		this.state = State.COMPLETED;
		this.httpStatus = httpStatus;
		this.responseBody = responseBody;
	}

	public boolean isExpired(Instant now) {
		return now.isAfter(expiresAt);
	}
}
