package com.vetclinic.appointment.event;

import com.vetclinic.appointment.service.AppointmentService;
import com.vetclinic.common.event.VisitCheckedInEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * clinical-service tạo đợt khám trong transaction của nó rồi phát VisitCheckedInEvent; appointment-service chuyển lịch
 * hẹn sang CHECKED_IN tại đây (không ghi chéo sang DB của service khác).
 * Idempotent: {@link IdempotentConsumer} ghi {@code processed_events} cùng giao dịch với việc chuyển trạng thái; nhận trùng
 * sự kiện (eventId + tên listener) thì bỏ qua. Lỗi thì retry 3 lần rồi vào DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisitCheckedInListener {

    /** Tên listener ghi vào processed_events.consumer (một service có thể có nhiều listener cho cùng sự kiện). */
    public static final String CONSUMER = "appointment.visit-checked-in";

    private final AppointmentService appointmentService;
    private final IdempotentConsumer idempotentConsumer;

    @RabbitListener(queues = AppointmentRabbitConfig.VISIT_CHECKED_IN_QUEUE)
    public void onVisitCheckedIn(VisitCheckedInEvent event) {
        if (event.appointmentId() == null) {
            // Khách vãng lai (walk-in): không có lịch hẹn nào để cập nhật
            return;
        }
        log.info("Nhận VisitCheckedInEvent: visitCode={}, appointmentId={}", event.visitCode(), event.appointmentId());
        idempotentConsumer.run(event.eventId(), CONSUMER,
                () -> appointmentService.markCheckedIn(event.appointmentId(), event.actorUserId(), event.visitCode()));
    }
}
