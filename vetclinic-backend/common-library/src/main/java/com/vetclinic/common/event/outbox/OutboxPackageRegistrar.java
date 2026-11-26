package com.vetclinic.common.event.outbox;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Thêm package {@code com.vetclinic.common.event.outbox} vào "auto-configuration packages" của Spring Boot, nhờ vậy JPA
 * tự quét entity {@link OutboxEvent} và Spring Data tự tạo {@link OutboxEventRepository} cạnh entity của service
 * (service không cần {@code @EntityScan} / {@code @EnableJpaRepositories} riêng).
 */
class OutboxPackageRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        AutoConfigurationPackages.register(registry, OutboxEvent.class.getPackageName());
    }
}
