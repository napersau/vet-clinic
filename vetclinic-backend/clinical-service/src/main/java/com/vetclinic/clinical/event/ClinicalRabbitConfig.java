package com.vetclinic.clinical.event;

import com.vetclinic.common.event.RabbitCommonConfig;
import com.vetclinic.common.event.EventRouting;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue của clinical-service (mẫu &lt;service&gt;.&lt;routing-key&gt;, bền vững, có DLQ). clinical NHẬN các sự kiện của auth-service để giữ
 * snapshot đúng và xử lý ngoại lệ phân tán (Kiến trúc v4.0 §5.2): {@code user.profile_updated} (bước 4.1: tên / SĐT chủ nuôi trên thú cưng;
 * bước 4.2: tên Bác sĩ trên đợt khám) và {@code user.status_changed} (bước 4.4, EX-17: Bác sĩ bị khóa thì gỡ phân công các ca đang chờ).
 */
@Configuration
public class ClinicalRabbitConfig {

    public static final String SERVICE_NAME = "clinical-service";
    // Hằng số compile-time (dùng trong @RabbitListener)
    public static final String USER_PROFILE_UPDATED_QUEUE = SERVICE_NAME + "." + EventRouting.USER_PROFILE_UPDATED;
    public static final String USER_STATUS_CHANGED_QUEUE = SERVICE_NAME + "." + EventRouting.USER_STATUS_CHANGED;
    // Bước 6.2: clinical tự nhận sự kiện của chính mình để phát phiếu trên Redis (QueueIssuerListener)
    public static final String INVOICE_PAID_QUEUE = SERVICE_NAME + "." + EventRouting.INVOICE_SERVICE_PAID;
    public static final String VISIT_CHECKED_IN_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_CHECKED_IN;
    public static final String RESULTS_READY_QUEUE = SERVICE_NAME + "." + EventRouting.VISIT_RESULTS_READY;

    @Bean
    public Queue userProfileUpdatedQueue() {
        return RabbitCommonConfig.durableQueueWithDlq(USER_PROFILE_UPDATED_QUEUE);
    }

    @Bean
    public Queue userProfileUpdatedDeadLetterQueue() {
        return RabbitCommonConfig.deadLetterQueue(USER_PROFILE_UPDATED_QUEUE);
    }

    @Bean
    public Binding userProfileUpdatedBinding(TopicExchange clinicEventsExchange) {
        return BindingBuilder.bind(userProfileUpdatedQueue()).to(clinicEventsExchange).with(EventRouting.USER_PROFILE_UPDATED);
    }

    @Bean
    public Binding userProfileUpdatedDeadLetterBinding(DirectExchange clinicEventsDeadLetterExchange) {
        return BindingBuilder.bind(userProfileUpdatedDeadLetterQueue()).to(clinicEventsDeadLetterExchange)
                .with(USER_PROFILE_UPDATED_QUEUE);
    }

    @Bean
    public Queue userStatusChangedQueue() {
        return RabbitCommonConfig.durableQueueWithDlq(USER_STATUS_CHANGED_QUEUE);
    }

    @Bean
    public Queue userStatusChangedDeadLetterQueue() {
        return RabbitCommonConfig.deadLetterQueue(USER_STATUS_CHANGED_QUEUE);
    }

    @Bean
    public Binding userStatusChangedBinding(TopicExchange clinicEventsExchange) {
        return BindingBuilder.bind(userStatusChangedQueue()).to(clinicEventsExchange).with(EventRouting.USER_STATUS_CHANGED);
    }

    @Bean
    public Binding userStatusChangedDeadLetterBinding(DirectExchange clinicEventsDeadLetterExchange) {
        return BindingBuilder.bind(userStatusChangedDeadLetterQueue()).to(clinicEventsDeadLetterExchange)
                .with(USER_STATUS_CHANGED_QUEUE);
    }

    @Bean
    public Queue invoicePaidQueue() {
        return RabbitCommonConfig.durableQueueWithDlq(INVOICE_PAID_QUEUE);
    }

    @Bean
    public Queue invoicePaidDeadLetterQueue() {
        return RabbitCommonConfig.deadLetterQueue(INVOICE_PAID_QUEUE);
    }

    @Bean
    public Binding invoicePaidBinding(TopicExchange clinicEventsExchange) {
        return BindingBuilder.bind(invoicePaidQueue()).to(clinicEventsExchange).with(EventRouting.INVOICE_SERVICE_PAID);
    }

    @Bean
    public Binding invoicePaidDeadLetterBinding(DirectExchange clinicEventsDeadLetterExchange) {
        return BindingBuilder.bind(invoicePaidDeadLetterQueue()).to(clinicEventsDeadLetterExchange).with(INVOICE_PAID_QUEUE);
    }

    @Bean
    public Queue visitCheckedInQueue() {
        return RabbitCommonConfig.durableQueueWithDlq(VISIT_CHECKED_IN_QUEUE);
    }

    @Bean
    public Queue visitCheckedInDeadLetterQueue() {
        return RabbitCommonConfig.deadLetterQueue(VISIT_CHECKED_IN_QUEUE);
    }

    @Bean
    public Binding visitCheckedInBinding(TopicExchange clinicEventsExchange) {
        return BindingBuilder.bind(visitCheckedInQueue()).to(clinicEventsExchange).with(EventRouting.VISIT_CHECKED_IN);
    }

    @Bean
    public Binding visitCheckedInDeadLetterBinding(DirectExchange clinicEventsDeadLetterExchange) {
        return BindingBuilder.bind(visitCheckedInDeadLetterQueue()).to(clinicEventsDeadLetterExchange).with(VISIT_CHECKED_IN_QUEUE);
    }

    @Bean
    public Queue resultsReadyQueue() {
        return RabbitCommonConfig.durableQueueWithDlq(RESULTS_READY_QUEUE);
    }

    @Bean
    public Queue resultsReadyDeadLetterQueue() {
        return RabbitCommonConfig.deadLetterQueue(RESULTS_READY_QUEUE);
    }

    @Bean
    public Binding resultsReadyBinding(TopicExchange clinicEventsExchange) {
        return BindingBuilder.bind(resultsReadyQueue()).to(clinicEventsExchange).with(EventRouting.VISIT_RESULTS_READY);
    }

    @Bean
    public Binding resultsReadyDeadLetterBinding(DirectExchange clinicEventsDeadLetterExchange) {
        return BindingBuilder.bind(resultsReadyDeadLetterQueue()).to(clinicEventsDeadLetterExchange).with(RESULTS_READY_QUEUE);
    }
}
