package com.vetclinic.appointment.event;

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
 * Queue của appointment-service. Theo mẫu: &lt;service&gt;.&lt;routing-key&gt;, bền vững, có DLQ. appointment NHẬN 3 sự kiện (Kiến trúc v4.0 §5.2):
 * {@code visit.checked_in} (chuyển lịch sang CHECKED_IN), {@code user.profile_updated} và {@code pet.updated} (cập nhật cột snapshot).
 */
@Configuration
public class AppointmentRabbitConfig {

    public static final String SERVICE_NAME = "appointment-service";
    // Phải là hằng số compile-time (dùng trong @RabbitListener), nên ghép từ hằng số chứ không gọi EventRouting.queueName()
    public static final String VISIT_CHECKED_IN_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_CHECKED_IN;
    public static final String USER_PROFILE_UPDATED_QUEUE = SERVICE_NAME + "." + EventRouting.USER_PROFILE_UPDATED;
    public static final String PET_UPDATED_QUEUE = SERVICE_NAME + "." + EventRouting.PET_UPDATED;

    @Bean
    public Declarables appointmentQueues(TopicExchange clinicEventsExchange, DirectExchange clinicEventsDeadLetterExchange) {
        List<Declarable> declarations = new ArrayList<>();
        String[][] queues = {
                {VISIT_CHECKED_IN_QUEUE, EventRouting.VISIT_CHECKED_IN},
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
