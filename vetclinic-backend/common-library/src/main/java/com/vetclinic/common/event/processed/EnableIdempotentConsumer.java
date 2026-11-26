package com.vetclinic.common.event.processed;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Bật chống xử lý trùng cho service NHẬN sự kiện (appointment, clinical, billing-pharmacy, reporting): đăng ký entity /
 * repository {@code processed_events} và {@link IdempotentConsumer}. CSDL của service phải có bảng {@code processed_events}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({EnableIdempotentConsumer.PackageRegistrar.class, IdempotentConsumerConfiguration.class})
public @interface EnableIdempotentConsumer {

    /** Thêm package entity {@code processed_events} vào auto-configuration packages (JPA + Spring Data tự quét). */
    class PackageRegistrar implements ImportBeanDefinitionRegistrar {
        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            AutoConfigurationPackages.register(registry, ProcessedEvent.class.getPackageName());
        }
    }
}
