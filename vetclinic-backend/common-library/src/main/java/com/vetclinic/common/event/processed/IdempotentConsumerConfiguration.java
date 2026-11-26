package com.vetclinic.common.event.processed;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** CỐ Ý không gắn {@code @Configuration} (lý do như {@code OutboxConfiguration}); chỉ nạp qua {@link EnableIdempotentConsumer}. */
public class IdempotentConsumerConfiguration {

    @Bean
    public IdempotentConsumer idempotentConsumer(ProcessedEventRepository repository, ObjectProvider<Clock> clock) {
        return new IdempotentConsumer(repository, clock.getIfAvailable(Clock::systemUTC));
    }
}
