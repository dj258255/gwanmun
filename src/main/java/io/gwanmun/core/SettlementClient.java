package io.gwanmun.core;

import io.gwanmun.message.MessageCodec;
import io.gwanmun.message.VariableMessageCodec;
import io.gwanmun.message.VariableMessageCodec.VariableMessage;
import io.gwanmun.message.dto.DailySettlementHeader;
import io.gwanmun.message.dto.DailySettlementRecord;
import io.gwanmun.message.dto.DailySettlementRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * 당일 처리내역 조회(가변 전문) 클라이언트 — EOD 대사가 계정계측 기록을 끌어오는 통로(Phase 9).
 *
 * <p>대사는 하루에 한 번(또는 수동) 도는 배치라, 커넥션 풀·재시도·서킷을 두르지 않고 <b>호출마다
 * 소켓 하나</b>를 열고 닫는다(단순·명확). 요청 전문은 길이 프리픽스로 프레이밍하고, 레코드 N건이
 * 가변으로 붙은 응답을 헤더 + 레코드로 파싱한다(거래내역 조회와 같은 방식, 대상 포트만 다르다).
 */
@Component
public class SettlementClient {

	private static final Logger log = LoggerFactory.getLogger(SettlementClient.class);

	private static final String REQUEST_MESSAGE_TYPE = "0500";
	private static final int MAX_BODY_LENGTH = 9999;

	private final String host;
	private final int port;
	private final int connectTimeoutMs;
	private final int readTimeoutMs;
	private final MessageCodec codec = new MessageCodec();
	private final VariableMessageCodec variableCodec = new VariableMessageCodec();

	@Autowired
	public SettlementClient(
			@Value("${gwanmun.core.host:127.0.0.1}") String host,
			@Value("${gwanmun.core.settlement.port:9097}") int port,
			@Value("${gwanmun.core.connect-timeout-ms:2000}") int connectTimeoutMs,
			// 대사는 하루치 전량이라 넉넉히 — 조회성 read 타임아웃보다 길게 잡는다.
			@Value("${gwanmun.core.settlement.read-timeout-ms:10000}") int readTimeoutMs) {
		this.host = host;
		this.port = port;
		this.connectTimeoutMs = connectTimeoutMs;
		this.readTimeoutMs = readTimeoutMs;
	}

	/** 그 날짜(YYYYMMDD)에 계정계가 처리한 거래 전량을 조회한다. */
	public SettlementResult queryDay(String yyyymmdd) {
		DailySettlementRequest request = new DailySettlementRequest(REQUEST_MESSAGE_TYPE, yyyymmdd, "");
		byte[] requestBody = codec.build(request);

		byte[] responseBody;
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
			socket.setSoTimeout(readTimeoutMs);
			try (LengthPrefixedConnection conn = new LengthPrefixedConnection(socket, MAX_BODY_LENGTH)) {
				conn.writeFrame(requestBody);
				responseBody = conn.readFrame();
				if (responseBody == null) {
					throw new EOFException("계정계가 대사 응답 없이 연결을 닫았습니다.");
				}
			}
		} catch (IOException e) {
			throw new SettlementException("당일 처리내역 계정계(" + host + ":" + port + ") 통신 실패: "
					+ e.getMessage(), e);
		}

		VariableMessage<DailySettlementHeader, DailySettlementRecord> parsed =
				variableCodec.parse(responseBody, DailySettlementHeader.class, DailySettlementRecord.class);
		log.info("당일 처리내역 조회 완료: 기준일={} 건수={}", yyyymmdd, parsed.records().size());
		return new SettlementResult(parsed.header(), parsed.records());
	}

	/** 조회 결과(헤더 + 계정계가 그 날 처리한 거래 레코드 전량). */
	public record SettlementResult(DailySettlementHeader header, List<DailySettlementRecord> records) {
	}

	/** 당일 처리내역 계정계 통신 실패. */
	public static class SettlementException extends RuntimeException {
		public SettlementException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
