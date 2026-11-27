package com.vetclinic.clinical.event;

import com.vetclinic.clinical.service.DoctorAvailabilityService;
import com.vetclinic.common.event.UserStatusChangedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import com.vetclinic.common.security.RoleCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * EX-17: auth-service khóa / ngừng một tài khoản Bác sĩ: các đợt khám {@code WAITING} của Bác sĩ đó được gỡ phân công và phiếu chờ về danh sách
 * chờ phân công (notification báo Lễ tân phân công lại). Idempotent ({@link IdempotentConsumer} ghi processed_events cùng giao dịch); lỗi thì
 * retry 3 lần rồi vào DLQ. Sự kiện đến muộn không gây hại: lần phân công mới luôn hỏi lại trạng thái Bác sĩ ở auth-service (BR-39, CLIN_1316).
 * Mở khóa ({@code ACTIVE}) hoặc tài khoản không phải Bác sĩ thì không làm gì.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserStatusChangedListener {

    /** Tên listener ghi vào processed_events.consumer. */
    public static final String CONSUMER = "clinical.user-status-changed";

    private final DoctorAvailabilityService availability;
    private final IdempotentConsumer idempotentConsumer;

    @RabbitListener(queues = ClinicalRabbitConfig.USER_STATUS_CHANGED_QUEUE)
    public void onUserStatusChanged(UserStatusChangedEvent event) {
        boolean doctor = event.roles() != null && event.roles().contains(RoleCode.VETERINARIAN.name());
        if (!doctor || "ACTIVE".equals(event.toStatus())) {
            return;   // chỉ Bác sĩ bị khóa / ngừng mới ảnh hưởng hàng đợi
        }
        idempotentConsumer.run(event.eventId(), CONSUMER, () -> availability.releaseWaitingVisitsOf(event.userId()));
    }
}
