package io.gwanmun.message.spec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 한 전문(DTO 한 종류)의 전체 레이아웃. {@link Field} 어노테이션이 붙은 자바 필드들을
 * order 순으로 정렬해 오프셋을 누적 계산하고, 총 길이를 확정한다.
 *
 * <p>스펙 해석은 리플렉션이라 매번 하면 낭비 → DTO 클래스별로 캐시한다.
 */
public final class MessageSpec {

	private static final ConcurrentHashMap<Class<?>, MessageSpec> CACHE = new ConcurrentHashMap<>();

	private final Class<?> dtoType;
	private final List<FieldSpec> fields;
	private final int totalLength;

	private MessageSpec(Class<?> dtoType, List<FieldSpec> fields, int totalLength) {
		this.dtoType = dtoType;
		this.fields = fields;
		this.totalLength = totalLength;
	}

	/** DTO 클래스에서 스펙을 해석(또는 캐시에서 반환)한다. */
	public static MessageSpec of(Class<?> dtoType) {
		return CACHE.computeIfAbsent(dtoType, MessageSpec::resolve);
	}

	private static MessageSpec resolve(Class<?> dtoType) {
		// @Field가 달린 필드만 모아 order로 정렬한다(자바 리플렉션의 필드 순서는 보장되지 않으므로 order를 신뢰).
		List<java.lang.reflect.Field> annotated = new ArrayList<>();
		for (java.lang.reflect.Field f : dtoType.getDeclaredFields()) {
			if (f.isAnnotationPresent(Field.class)) {
				f.setAccessible(true);
				annotated.add(f);
			}
		}
		if (annotated.isEmpty()) {
			throw new IllegalArgumentException(
					dtoType.getName() + " 에 @Field 필드가 없습니다. 전문 스펙을 선언하세요.");
		}
		annotated.sort(Comparator.comparingInt(f -> f.getAnnotation(Field.class).order()));

		List<FieldSpec> specs = new ArrayList<>(annotated.size());
		int offset = 0;
		int lastOrder = Integer.MIN_VALUE;
		for (java.lang.reflect.Field f : annotated) {
			Field meta = f.getAnnotation(Field.class);
			if (meta.order() == lastOrder) {
				throw new IllegalArgumentException(
						dtoType.getName() + " 에 order=" + meta.order() + " 가 중복됩니다.");
			}
			lastOrder = meta.order();
			if (meta.length() <= 0) {
				throw new IllegalArgumentException(
						dtoType.getName() + "#" + f.getName() + " 의 length는 1 이상이어야 합니다.");
			}
			specs.add(new FieldSpec(f, f.getName(), offset, meta.length(), meta.type()));
			offset += meta.length();
		}
		return new MessageSpec(dtoType, List.copyOf(specs), offset);
	}

	public Class<?> dtoType() {
		return dtoType;
	}

	public List<FieldSpec> fields() {
		return fields;
	}

	/** 전문 전체의 고정 byte 길이. */
	public int totalLength() {
		return totalLength;
	}
}
