package com.vetclinic.common.feign;

import feign.InvocationHandlerFactory;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.cloud.openfeign.FeignBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Circuit Breaker (Resilience4j) cho MỌI Feign client (Quy chuẩn 9.1, Kiến trúc 5.1), mỗi phương thức của mỗi client một mạch.
 * <p>
 * Tự bọc {@code InvocationHandler} của Feign thay vì dùng {@code spring.cloud.openfeign.circuitbreaker.enabled}: bộ Resilience4J của Spring Cloud
 * chạy lời gọi trong một luồng riêng và bọc mọi lỗi trong {@code ExecutionException} rồi {@code NoFallbackAvailableException}, khiến nơi gọi MẤT
 * {@code AppException} nghiệp vụ do {@code CustomFeignErrorDecoder} dựng (404 {@code PET_NOT_FOUND} thành 500 / 503). Cách này gọi ngay trên luồng hiện tại
 * (giữ nguyên {@code RequestContextHolder} và header người dùng), ném lại đúng ngoại lệ gốc, và mạch mở thì ném {@code CallNotPermittedException}
 * (xử lý thành 503 SYS_1004 ở {@code CircuitBreakerExceptionHandler}). Cấu hình mạch lấy từ {@code resilience4j.circuitbreaker.configs.default}
 * trong application.yml; điều kiện tính thất bại là {@link FeignFailurePredicate}. Chỉ nạp ở service có Feign và Resilience4j.
 */
@Configuration
@ConditionalOnClass(name = {"feign.Feign", "io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry", "org.springframework.cloud.openfeign.FeignBuilderCustomizer"})
public class ResilientFeignConfig {

    @Bean
    public FeignBuilderCustomizer circuitBreakerFeignCustomizer(ObjectProvider<CircuitBreakerRegistry> registryProvider) {
        return builder -> builder.invocationHandlerFactory(new CircuitBreakingInvocationHandlerFactory(registryProvider));
    }

    static final class CircuitBreakingInvocationHandlerFactory implements InvocationHandlerFactory {

        private final ObjectProvider<CircuitBreakerRegistry> registryProvider;
        private final InvocationHandlerFactory delegate = new InvocationHandlerFactory.Default();

        CircuitBreakingInvocationHandlerFactory(ObjectProvider<CircuitBreakerRegistry> registryProvider) {
            this.registryProvider = registryProvider;
        }

        @Override
        public InvocationHandler create(feign.Target target, java.util.Map<Method, MethodHandler> dispatch) {
            InvocationHandler inner = delegate.create(target, dispatch);
            return (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class || !dispatch.containsKey(method)) {
                    return inner.invoke(proxy, method, args);   // equals / hashCode / toString
                }
                CircuitBreaker breaker = registryProvider.getIfAvailable(CircuitBreakerRegistry::ofDefaults).circuitBreaker(target.name() + "." + method.getName());
                try {
                    return breaker.executeCheckedSupplier(() -> inner.invoke(proxy, method, args));
                } catch (InvocationTargetException e) {
                    throw e.getCause() != null ? e.getCause() : e;
                }
            };
        }
    }
}
