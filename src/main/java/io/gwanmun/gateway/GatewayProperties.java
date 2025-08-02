package io.gwanmun.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 관문 설정. API 키-클라이언트 매핑과 유량제어 파라미터를 application.yml에서 주입받는다.
 * 값이 비어 있으면 학습용 기본값을 쓴다(데모가 설정 없이도 도는 게 목적).
 *
 * <pre>
 * gwanmun:
 *   gateway:
 *     api-keys:
 *       demo-key-fintech-a: fintech-a
 *       demo-key-fintech-b: fintech-b
 *     rate-capacity: 5          # 클라이언트별 버킷 용량(순간 허용량)
 *     rate-refill-per-minute: 30  # 분당 토큰 보충 속도
 * </pre>
 */
@ConfigurationProperties(prefix = "gwanmun.gateway")
public class GatewayProperties {

	/** API 키 → 클라이언트 id. 등록된 키만 통과한다(잘못된 키는 403). */
	private Map<String, String> apiKeys = new LinkedHashMap<>();

	/** 토큰버킷 용량. 순간적으로 이만큼까지는 연속 허용, 그다음부터 429. */
	private int rateCapacity = 5;

	/** 분당 보충 토큰 수. 이 속도로 버킷이 다시 찬다(초당 = 값/60). */
	private double rateRefillPerMinute = 30.0;

	public Map<String, String> getApiKeys() {
		return apiKeys;
	}

	public void setApiKeys(Map<String, String> apiKeys) {
		this.apiKeys = apiKeys;
	}

	public int getRateCapacity() {
		return rateCapacity;
	}

	public void setRateCapacity(int rateCapacity) {
		this.rateCapacity = rateCapacity;
	}

	public double getRateRefillPerMinute() {
		return rateRefillPerMinute;
	}

	public void setRateRefillPerMinute(double rateRefillPerMinute) {
		this.rateRefillPerMinute = rateRefillPerMinute;
	}
}
