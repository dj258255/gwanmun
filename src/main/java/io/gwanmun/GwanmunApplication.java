package io.gwanmun;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * gwanmun(관문) — 레거시 고정길이 전문(電文)과 현대 REST/JSON을 잇는 연계 게이트웨이.
 *
 * <p>Spring Modulith 기반 모듈러 모놀리스. io.gwanmun 바로 아래 각 패키지가 애플리케이션 모듈이다:
 * message(전문 코덱) · core(계정계 연동) · gateway(관문 필터 체인) · web(REST 조립).
 * 단일 배포 단위지만 모듈 경계는 코드가 강제한다(ApplicationModules.verify() 테스트).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GwanmunApplication {

	public static void main(String[] args) {
		SpringApplication.run(GwanmunApplication.class, args);
	}
}
