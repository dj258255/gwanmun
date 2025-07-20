package io.gwanmun.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Spring 앱 안에서 목업 계정계 TCP 서버를 별도 포트로 함께 띄운다(개발 편의).
 *
 * <p>{@code gwanmun.core.embedded=false}로 끄면, 계정계를 독립 프로세스
 * ({@link MockCoreBankingServer#main})로 따로 실행하는 "앱↔계정계 두 프로세스" 구성이 된다.
 * 어느 쪽이든 통신은 실제 TCP 소켓이다(인메모리 호출이 아님).
 */
@Component
public class MockCoreBankingLifecycle implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(MockCoreBankingLifecycle.class);

	private final boolean enabled;
	private final MockCoreBankingServer server;
	private volatile boolean running;

	public MockCoreBankingLifecycle(
			@Value("${gwanmun.core.embedded:true}") boolean enabled,
			@Value("${gwanmun.core.port:9099}") int port) {
		this.enabled = enabled;
		this.server = new MockCoreBankingServer(port);
	}

	@Override
	public void start() {
		if (!enabled) {
			log.info("내장 목업 계정계 비활성(gwanmun.core.embedded=false). 외부 계정계 프로세스에 접속합니다.");
			return;
		}
		try {
			server.start();
			running = true;
		} catch (IOException e) {
			throw new UncheckedIOException("목업 계정계 서버 기동 실패", e);
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
