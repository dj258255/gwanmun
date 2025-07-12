package io.gwanmun;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gwanmun(관문) — 레거시 고정길이 전문(電文)과 현대 REST/JSON을 잇는 연계 게이트웨이.
 * Phase 1은 그 심장인 "전문 파서/빌더"만 담는다(네트워크 계층은 Phase 2).
 */
@SpringBootApplication
public class GwanmunApplication {

	public static void main(String[] args) {
		SpringApplication.run(GwanmunApplication.class, args);
	}
}
