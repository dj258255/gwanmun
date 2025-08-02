/**
 * 전문 DTO. 다른 모듈(core·gateway·web)이 잔액조회 요청/응답 필드를 직접 다뤄야 하므로,
 * 모듈 내부에 감추지 않고 명명된 인터페이스("dto")로 공개한다.
 */
@NamedInterface("dto")
package io.gwanmun.message.dto;

import org.springframework.modulith.NamedInterface;
