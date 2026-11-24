package app.hanare.common;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Như {@code @Size} nhưng đo độ dài SAU KHI cắt khoảng trắng đầu/cuối (chuỗi chỉ gồm khoảng trắng, hay {@code " ab "} khi
 * tối thiểu là 3, đều bị từ chối). {@code null} hợp lệ — kết hợp {@code @NotNull} khi bắt buộc. Service vẫn {@code trim()} khi lưu,
 * nên giá trị được kiểm chính là giá trị được lưu (T-02).
 */
@Documented
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = TrimmedSize.Validator.class)
public @interface TrimmedSize {

	int min() default 0;

	int max() default Integer.MAX_VALUE;

	String message() default "Độ dài phải từ {min} đến {max} ký tự (không tính khoảng trắng đầu/cuối).";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<TrimmedSize, CharSequence> {

		private int min;
		private int max;

		@Override
		public void initialize(TrimmedSize annotation) {
			this.min = annotation.min();
			this.max = annotation.max();
		}

		@Override
		public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
			if (value == null) {
				return true;
			}
			int length = value.toString().strip().length();
			return length >= min && length <= max;
		}
	}
}
