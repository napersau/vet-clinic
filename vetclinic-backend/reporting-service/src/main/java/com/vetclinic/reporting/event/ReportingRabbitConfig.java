package com.vetclinic.reporting.event;

import com.vetclinic.common.event.EventRouting;
import com.vetclinic.common.event.RabbitCommonConfig;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Queue của reporting-service: mỗi listener một queue &lt;service&gt;.&lt;routing-key&gt;, bền vững, có DLQ (Kiến trúc v4.0 §5.2: reporting NHẬN 8 sự kiện).
 */
@Configuration
public class ReportingRabbitConfig {

    public static final String SERVICE_NAME = "reporting-service";
    // Phải là hằng số compile-time (dùng trong @RabbitListener)
    public static final String VISIT_CHECKED_IN_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_CHECKED_IN;
    public static final String VISIT_COMPLETED_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_COMPLETED;
    public static final String VISIT_CANCELLED_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_CANCELLED;
    public static final String INVOICE_SERVICE_PAID_QUEUE = SERVICE_NAME + "." + EventRouting.INVOICE_SERVICE_PAID;
    public static final String INVOICE_SERVICE_REFUNDED_QUEUE = SERVICE_NAME + "." + EventRouting.INVOICE_SERVICE_REFUNDED;
    public static final String MEDICINE_INVOICE_PAID_QUEUE = SERVICE_NAME + "." + EventRouting.PHARMACY_MEDICINE_INVOICE_PAID;
    public static final String USER_PROFILE_UPDATED_QUEUE = SERVICE_NAME + "." + EventRouting.USER_PROFILE_UPDATED;
    public static final String PET_UPDATED_QUEUE = SERVICE_NAME + "." + EventRouting.PET_UPDATED;

    @Bean
    public Declarables reportingQueues(TopicExchange clinicEventsExchange, DirectExchange clinicEventsDeadLetterExchange) {
        List<Declarable> declarations = new ArrayList<>();
        String[][] queues = {
                {VISIT_CHECKED_IN_QUEUE, EventRouting.VISIT_CHECKED_IN},
                {VISIT_COMPLETED_QUEUE, EventRouting.VISIT_COMPLETED},
                {VISIT_CANCELLED_QUEUE, EventRouting.VISIT_CANCELLED},
                {INVOICE_SERVICE_PAID_QUEUE, EventRouting.INVOICE_SERVICE_PAID},
                {INVOICE_SERVICE_REFUNDED_QUEUE, EventRouting.INVOICE_SERVICE_REFUNDED},
                {MEDICINE_INVOICE_PAID_QUEUE, EventRouting.PHARMACY_MEDICINE_INVOICE_PAID},
                {USER_PROFILE_UPDATED_QUEUE, EventRouting.USER_PROFILE_UPDATED},
                {PET_UPDATED_QUEUE, EventRouting.PET_UPDATED},
        };
        for (String[] entry : queues) {
            String queueName = entry[0];
            Queue queue = RabbitCommonConfig.durableQueueWithDlq(queueName);
            Queue deadLetterQueue = RabbitCommonConfig.deadLetterQueue(queueName);
            declarations.add(queue);
            declarations.add(deadLetterQueue);
            declarations.add(BindingBuilder.bind(queue).to(clinicEventsExchange).with(entry[1]));
            Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(clinicEventsDeadLetterExchange).with(queueName);
            declarations.add(deadLetterBinding);
        }
        return new Declarables(declarations);
    }
}
