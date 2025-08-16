package io.gwanmun.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Spring 앱 안에서 목업 계정계(거래내역, 가변 전문) TCP 서버를 별도 포트로 함께 띄운다.
 * {@link MockCoreBankingLifecycle}(잔액조회)과 같은 {@code gwanmun.core.embedded} 플래그를 따른다 —
 * 끄면 계정계를 독립 프로세스로 따로 실행하는 구성이 된다. 어느 쪽이든 통신은 실제 TCP 소켓이다.
 */
@Component
public class MockTransactionHistoryLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(MockTransactionHistoryLifecycle.class);

	private final boolean enabled;
	private final MockTransactionHistoryServer server;
	private volatile boolean running;

	public MockTransactionHistoryLifecycle(
			@Value("${gwanmun.core.embedded:true}") boolean enabled,
			@Value("${gwanmun.core.history.port:9098}") int port) {
		this.enabled = enabled;
		this.server = new MockTransactionHistoryServer(port);
	}

	@Override
	public void start() {
		if (!enabled) {
			log.info("내장 목업 계정계(거래내역) 비활성(gwanmun.core.embedded=false). 외부 프로세스에 접속합니다.");
			return;
		}
		try {
			server.start();
			running = true;
		} catch (IOException e) {
			throw new UncheckedIOException("목업 계정계(거래내역) 서버 기동 실패", e);
		}
	}

	@Override
	public void stop() {
		if (running) {
			server.close();
			running = false;
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}
}
