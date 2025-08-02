/**
 * core 모듈 — 계정계(코어뱅킹) 연동. TCP 프레이밍(FixedLengthFramer·FramedConnection),
 * 목업 계정계 서버, 나가는 클라이언트(CoreBankingClient)를 담는다. message 모듈의 코덱·스펙에
 * 의존하되(전문을 만들고 되읽어야 하므로), gateway·web은 모른다(한 방향 의존).
 */
@org.springframework.modulith.ApplicationModule(displayName = "core · 계정계 연동")
package io.gwanmun.core;
