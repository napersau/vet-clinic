package com.vetclinic.common.event.outbox;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

/**
 * Cấu hình outbox. CỐ Ý không gắn {@code @Configuration}: các service quét {@code com.vetclinic} nên nếu gắn thì mọi service
 * (kể cả service chưa có bảng {@code outbox_events}) đều nạp và hỏng khi {@code ddl-auto: validate}. Lớp này chỉ được nạp
 * qua {@link EnableOutbox}.
 */
@EnableScheduling
public class OutboxConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "vetclinic.outbox")
    public OutboxProperties outboxProperties() {
        return new OutboxProperties();
    }

    @Bean
    public OutboxWriter outboxWriter(OutboxEventRepository repository, ObjectProvider<ObjectMapper> objectMapper,
                                     ObjectProvider<Clock> clock) {
        return new OutboxWriter(repository, objectMapper.getIfAvailable(JsonMapper::new), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    public OutboxRelay outboxRelay(OutboxEventRepository repository, RabbitTemplate rabbitTemplate,
                                   OutboxProperties properties, ObjectProvider<Clock> clock) {
        return new OutboxRelay(repository, rabbitTemplate, properties, clock.getIfAvailable(Clock::systemUTC));
    }
}
