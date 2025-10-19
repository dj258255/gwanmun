package io.gwanmun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * 모듈러 모놀리스의 경계를 <b>코드가 강제</b>한다는 증거. Spring Modulith가 io.gwanmun 하위 각
 * 패키지를 애플리케이션 모듈로 보고, 모듈 간 순환참조·내부 타입 침범이 없는지 검증한다.
 *
 * <p>{@link #verifiesModuleBoundaries()}가 그린이면 "경계가 지켜진다"는 뜻이고, 누가 실수로
 * 순환 의존이나 다른 모듈 내부 패키지 직접 참조를 넣으면 이 테스트가 빨갛게 막는다.
 */
class ModularityTest {

	private final ApplicationModules modules = ApplicationModules.of(GwanmunApplication.class);

	@Test
	@DisplayName("모듈 경계 검증: 순환참조 없음, 내부 타입 침범 없음(verify 통과)")
	void verifiesModuleBoundaries() {
		modules.verify();
	}

	@Test
	@DisplayName("모듈 다이어그램(PlantUML/C4) 생성 — docs/modules 에 남긴다")
	void writesModuleDocumentation() {
		// Modulith 1.4에서 출력 폴더 지정이 Documenter.withOutputFolder(String)(체이닝)에서
		// 생성자의 Documenter.Options로 옮겨졌다(Boot 3.5 업그레이드에 맞춘 적응).
		Documenter.Options options = Documenter.Options.defaults().withOutputFolder("docs/modules");
		new Documenter(modules, options)
				.writeModulesAsPlantUml()
				.writeIndividualModulesAsPlantUml()
				.writeModuleCanvases();
	}
}
