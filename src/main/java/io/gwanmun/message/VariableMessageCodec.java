package io.gwanmun.message;

import io.gwanmun.message.spec.MessageSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 가변 전문(<b>고정 헤더 + 반복 레코드 N건</b>) ↔ DTO 변환 엔진.
 *
 * <p>{@link MessageCodec}는 "필드 레이아웃이 고정인 한 전문"을 다룬다. 하지만 거래내역 조회 응답처럼
 * 레코드가 몇 건 붙을지 런타임에 정해지는 전문은 전체 길이가 가변이다. 이 코덱은 그 구조를
 * <b>고정 코덱을 두 번 쓰는 것</b>으로 처리한다 — 앞의 고정 헤더 한 번, 뒤의 고정 레코드를 건수만큼.
 *
 * <p><b>경계는 길이가 알려준다.</b> 본문 전체 길이는 이미 전송 계층의 길이 헤더가 확정해 주므로
 * (레코드가 반쪽으로 잘려 오는 일은 프레이밍이 막는다), 여기서는 온전한 본문 byte[]를 받아
 * {@code (본문길이 - 헤더길이) / 레코드길이} 로 건수를 역산한다. 나머지가 0이 아니면(레코드가 딱
 * 떨어지지 않으면) 잘린 전문으로 보고 거절한다.
 */
public class VariableMessageCodec {

	private final MessageCodec codec;

	public VariableMessageCodec() {
		this(new MessageCodec());
	}

	public VariableMessageCodec(MessageCodec codec) {
		this.codec = codec;
	}

	/**
	 * 고정 헤더 DTO와 레코드 DTO 리스트를 이어붙여 하나의 본문 byte[]로 만든다.
	 *
	 * <p>헤더의 건수/전체길이 필드는 <b>호출자가 미리 채워</b> 넘긴다(무엇이 진실인지 한 곳에서 정하려고).
	 * 이 메서드는 채워진 값을 그대로 인코딩할 뿐, 값을 대신 계산해 몰래 덮어쓰지 않는다.
	 */
	public byte[] build(Object header, List<?> records) {
		byte[] headerBytes = codec.build(header);
		byte[][] recordBytes = new byte[records.size()][];
		int total = headerBytes.length;
		for (int i = 0; i < records.size(); i++) {
			recordBytes[i] = codec.build(records.get(i));
			total += recordBytes[i].length;
		}

		byte[] out = new byte[total];
		int pos = 0;
		System.arraycopy(headerBytes, 0, out, pos, headerBytes.length);
		pos += headerBytes.length;
		for (byte[] rec : recordBytes) {
			System.arraycopy(rec, 0, out, pos, rec.length);
			pos += rec.length;
		}
		return out;
	}

	/**
	 * 본문 byte[]를 헤더 + 레코드 리스트로 되돌린다.
	 *
	 * @throws GwanmunParseException 본문이 헤더보다 짧거나, 레코드가 딱 떨어지지 않을 때(잘린 전문)
	 */
	public <H, R> VariableMessage<H, R> parse(byte[] body, Class<H> headerType, Class<R> recordType) {
		if (body == null) {
			throw new GwanmunParseException("가변 전문 본문이 null 입니다.");
		}
		int headerLen = MessageSpec.of(headerType).totalLength();
		int recordLen = MessageSpec.of(recordType).totalLength();

		if (body.length < headerLen) {
			throw new GwanmunParseException(String.format(
					"가변 전문이 헤더보다 짧습니다: 헤더 %d byte 인데 본문은 %d byte 입니다.",
					headerLen, body.length));
		}
		int recordArea = body.length - headerLen;
		if (recordArea % recordLen != 0) {
			throw new GwanmunParseException(String.format(
					"레코드 영역(%d byte)이 레코드 길이(%d byte)로 나눠떨어지지 않습니다(잘린 전문).",
					recordArea, recordLen));
		}
		int count = recordArea / recordLen;

		H header = codec.parse(Arrays.copyOfRange(body, 0, headerLen), headerType);
		List<R> records = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			int start = headerLen + i * recordLen;
			records.add(codec.parse(Arrays.copyOfRange(body, start, start + recordLen), recordType));
		}
		return new VariableMessage<>(header, List.copyOf(records));
	}

	/** 헤더 + 레코드 리스트 파싱 결과. */
	public record VariableMessage<H, R>(H header, List<R> records) {
	}
}
