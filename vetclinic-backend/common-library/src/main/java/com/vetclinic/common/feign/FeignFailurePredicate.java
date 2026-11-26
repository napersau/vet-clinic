package com.vetclinic.common.feign;

import com.vetclinic.common.exception.AppException;

import java.util.function.Predicate;

/**
 * Điều kiện "lời gọi Feign này tính là thất bại" cho Circuit Breaker (Resilience4j {@code record-failure-predicate}).
 * <ul>
 *   <li>{@link AppException} do {@code CustomFeignErrorDecoder} dựng từ phản hồi của service con: chỉ lỗi 5xx (service con hỏng hoặc đang
 *       sập) mới tính; lỗi nghiệp vụ 4xx (không tìm thấy, dữ liệu sai, không đủ quyền...) là service con vẫn khỏe nên KHÔNG làm mở mạch;</li>
 *   <li>mọi ngoại lệ khác (timeout đọc / kết nối, mất kết nối, {@code RetryableException}): tính là thất bại.</li>
 * </ul>
 * Không có phụ thuộc vào Resilience4j nên lớp này luôn nạp được; Resilience4j tự tạo bằng constructor không đối số.
 */
public class FeignFailurePredicate implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof AppException app) {
                return app.getErrorCode().getHttpStatus().is5xxServerError();
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return true;
    }
}
