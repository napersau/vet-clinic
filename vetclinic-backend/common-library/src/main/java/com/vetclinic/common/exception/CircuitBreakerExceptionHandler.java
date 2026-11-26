package com.vetclinic.common.exception;

import com.vetclinic.common.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Mạch Circuit Breaker của một lời gọi Feign đang MỞ: gọi bị từ chối ngay (không chờ timeout) -> 503 {@code SYS_1004}, cùng mã với lỗi kết nối,
 * để giao diện xử lý một cách. Chỉ nạp ở service có Resilience4j trên classpath. Nơi gọi Feign chỉ để làm giàu hiển thị đã tự bắt mọi
 * {@code RuntimeException} nên vẫn chạy tiếp với bản chụp (snapshot); nơi kiểm tra điều kiện ghi thì lỗi này dừng thao tác, không ghi gì.
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "io.github.resilience4j.circuitbreaker.CallNotPermittedException")
public class CircuitBreakerExceptionHandler {

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ApiResponse<Object>> handleOpenCircuit(CallNotPermittedException ex) {
        log.warn("Circuit Breaker đang mở, từ chối gọi: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.error(ErrorCode.SERVICE_UNAVAILABLE,
                "Dịch vụ phụ thuộc đang quá tải hoặc tạm ngừng, vui lòng thử lại sau ít giây"));
    }
}
