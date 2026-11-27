package com.vetclinic.notification.config;

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

/** Queue của notification-service: &lt;service&gt;.&lt;routing-key&gt;, bền vững, mỗi queue có DLQ; nghe 11 sự kiện (Kiến trúc §5.2, thêm queue.ticket_issued 17/11/2026 và user.status_changed ở v4.0). */
@Configuration
public class NotificationRabbitConfig {

    public static final String SERVICE_NAME = "notification-service";
    // Phải là hằng số compile-time (dùng trong @RabbitListener)
    public static final String APPOINTMENT_BOOKED_QUEUE = SERVICE_NAME + "." + EventRouting.APPOINTMENT_BOOKED;
    public static final String APPOINTMENT_STATUS_QUEUE = SERVICE_NAME + "." + EventRouting.APPOINTMENT_STATUS_CHANGED;
    public static final String APPOINTMENT_NO_SHOW_QUEUE = SERVICE_NAME + "." + EventRouting.APPOINTMENT_NO_SHOW;
    public static final String VISIT_CHECKED_IN_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_CHECKED_IN;
    public static final String PATIENT_CALLED_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_PATIENT_CALLED;
    public static final String RESULTS_READY_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_RESULTS_READY;
    public static final String VISIT_COMPLETED_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_COMPLETED;
    public static final String MEDICINE_INVOICE_PAID_QUEUE = SERVICE_NAME + "." + EventRouting.PHARMACY_MEDICINE_INVOICE_PAID;
    public static final String LOW_STOCK_QUEUE = SERVICE_NAME + "." + EventRouting.PHARMACY_LOW_STOCK;
    public static final String TICKET_ISSUED_QUEUE = SERVICE_NAME + "." + EventRouting.QUEUE_TICKET_ISSUED;
    public static final String USER_STATUS_CHANGED_QUEUE = SERVICE_NAME + "." + EventRouting.USER_STATUS_CHANGED;

    @Bean
    public Declarables notificationQueues(TopicExchange clinicEventsExchange, DirectExchange clinicEventsDeadLetterExchange) {
        List<Object> declarations = new ArrayList<>();
        String[][] queues = {
                {APPOINTMENT_BOOKED_QUEUE, EventRouting.APPOINTMENT_BOOKED},
                {APPOINTMENT_STATUS_QUEUE, EventRouting.APPOINTMENT_STATUS_CHANGED},
                {APPOINTMENT_NO_SHOW_QUEUE, EventRouting.APPOINTMENT_NO_SHOW},
                {VISIT_CHECKED_IN_QUEUE, EventRouting.VISIT_CHECKED_IN},
                {PATIENT_CALLED_QUEUE, EventRouting.VISIT_PATIENT_CALLED},
                {RESULTS_READY_QUEUE, EventRouting.VISIT_RESULTS_READY},
                {VISIT_COMPLETED_QUEUE, EventRouting.VISIT_COMPLETED},
                {MEDICINE_INVOICE_PAID_QUEUE, EventRouting.PHARMACY_MEDICINE_INVOICE_PAID},
                {LOW_STOCK_QUEUE, EventRouting.PHARMACY_LOW_STOCK},
                {TICKET_ISSUED_QUEUE, EventRouting.QUEUE_TICKET_ISSUED},
                {USER_STATUS_CHANGED_QUEUE, EventRouting.USER_STATUS_CHANGED},
        };
        for (String[] entry : queues) {
            String queueName = entry[0];
            Queue queue = RabbitCommonConfig.durableQueueWithDlq(queueName);
            Queue deadLetterQueue = RabbitCommonConfig.deadLetterQueue(queueName);
            Binding binding = BindingBuilder.bind(queue).to(clinicEventsExchange).with(entry[1]);
            Binding deadLetterBinding = BindingBuilder.bind(deadLetterQueue).to(clinicEventsDeadLetterExchange).with(queueName);
            declarations.addAll(List.of(queue, deadLetterQueue, binding, deadLetterBinding));
        }
        return new Declarables(declarations.stream().map(o -> (Declarable) o).toList());
    }
}
