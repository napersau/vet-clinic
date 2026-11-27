package com.vetclinic.common.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Cầu nối "sự kiện nội bộ -> RabbitMQ" chạy SAU KHI transaction commit, dành cho các sự kiện KHÔNG đi qua outbox.
 * Từ v4.0 (quyết định D9), mọi sự kiện nghiệp vụ đi qua Transactional Outbox ({@code OutboxWriter}); chỉ còn hai sự kiện thông báo
 * tức thời được gửi thẳng bằng cách này vì mất cũng không sai dữ liệu (Kiến trúc v4.0 mục 5.2):
 * {@code QueueTicketIssuedEvent} và {@code PatientCalledEvent} (clinical-service).
 * <pre>
 * eventPublisher.publishEvent(new PatientCalledEvent(...)); // trong method @Transactional
 * </pre>
 * Nếu transaction rollback, sự kiện bị bỏ. Nếu không có transaction (fallbackExecution) thì gửi ngay. Service nghiệp vụ KHÔNG
 * gọi rabbitTemplate trực tiếp.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springframework.amqp.rabbit.core.RabbitTemplate")
public class DomainEventRabbitPublisher {

    private final RabbitTemplate rabbitTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void publish(DomainEvent event) {
        rabbitTemplate.convertAndSend(EventRouting.EXCHANGE, event.routingKey(), event);
        log.info("Published {} [{}] eventId={}", event.getClass().getSimpleName(), event.routingKey(), event.eventId());
    }
}
