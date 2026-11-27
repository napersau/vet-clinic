package com.vetclinic.billing.event;

import com.vetclinic.common.event.EventRouting;
import com.vetclinic.common.event.RabbitCommonConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue của billing-pharmacy-service (mẫu &lt;service&gt;.&lt;routing-key&gt;, bền vững, có DLQ). billing NHẬN {@code prescription.created} của clinical
 * để tạo hóa đơn thuốc nháp (bước 5.2).
 */
@Configuration
public class BillingRabbitConfig {

    public static final String SERVICE_NAME = "billing-pharmacy-service";
    // Hằng số compile-time (dùng trong @RabbitListener)
    public static final String PRESCRIPTION_CREATED_QUEUE = SERVICE_NAME + "." + EventRouting.PRESCRIPTION_CREATED;

    @Bean
    public Queue prescriptionCreatedQueue() {
        return RabbitCommonConfig.durableQueueWithDlq(PRESCRIPTION_CREATED_QUEUE);
    }

    @Bean
    public Queue prescriptionCreatedDeadLetterQueue() {
        return RabbitCommonConfig.deadLetterQueue(PRESCRIPTION_CREATED_QUEUE);
    }

    @Bean
    public Binding prescriptionCreatedBinding(TopicExchange clinicEventsExchange) {
        return BindingBuilder.bind(prescriptionCreatedQueue()).to(clinicEventsExchange).with(EventRouting.PRESCRIPTION_CREATED);
    }

    @Bean
    public Binding prescriptionCreatedDeadLetterBinding(DirectExchange clinicEventsDeadLetterExchange) {
        return BindingBuilder.bind(prescriptionCreatedDeadLetterQueue()).to(clinicEventsDeadLetterExchange).with(PRESCRIPTION_CREATED_QUEUE);
    }
}
