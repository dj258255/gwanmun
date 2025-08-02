/**
 * gateway 모듈 — 외부에 열린 통로의 문지기. Phase 3에서 손으로 짠 필터 체인(인증·라우팅·유량제어)과,
 * 전문 왕복 배선(GatewayService)이 여기 산다. 필터 구현 세부(filter·auth·route 하위 패키지)는
 * 모듈 내부로 감추고, GatewayService·GatewayException·필터 등록만 모듈 API로 노출한다.
 */
@org.springframework.modulith.ApplicationModule(displayName = "gateway · 관문(필터 체인)")
package io.gwanmun.gateway;
