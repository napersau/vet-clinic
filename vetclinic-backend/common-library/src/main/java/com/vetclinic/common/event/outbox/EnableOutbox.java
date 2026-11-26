package com.vetclinic.common.event.outbox;

import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bật Transactional Outbox cho service PHÁT sự kiện (auth, appointment, clinical, billing-pharmacy – Kiến trúc v4.0 mục 5.3):
 * đăng ký entity / repository {@code outbox_events}, {@link OutboxWriter}, {@link OutboxRelay} (lịch 500 ms) và bật
 * {@code @EnableScheduling}. CSDL của service phải có bảng {@code outbox_events}; nên đặt
 * {@code spring.rabbitmq.publisher-confirm-type: correlated} để relay chờ RabbitMQ xác nhận.
 * <pre>
 * &#64;EnableOutbox
 * &#64;SpringBootApplication(scanBasePackages = "com.vetclinic")
 * public class ClinicalServiceApplication { ... }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({OutboxPackageRegistrar.class, OutboxConfiguration.class})
public @interface EnableOutbox {
}
