package com.vetclinic.appointment.event;

import com.vetclinic.appointment.service.AppointmentService;
import com.vetclinic.common.event.PetUpdatedEvent;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Giữ các cột snapshot trên {@code appointments} đúng khi dữ liệu gốc đổi (Kiến trúc v4.0 §5.2), mỗi listener một queue + DLQ:
 * <ul>
 *   <li>{@code UserProfileUpdatedEvent} của auth-service: tên / SĐT chủ nuôi;</li>
 *   <li>{@code PetUpdatedEvent} của clinical-service: tên thú cưng, chuyển chủ.</li>
 * </ul>
 * Idempotent: {@link IdempotentConsumer} ghi {@code processed_events} cùng giao dịch với lệnh UPDATE (nhận trùng thì bỏ qua); lỗi thì retry
 * 3 lần rồi vào DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SnapshotListeners {

    public static final String USER_CONSUMER = "appointment.user-profile-updated";
    public static final String PET_CONSUMER = "appointment.pet-updated";

    private final AppointmentService appointmentService;
    private final IdempotentConsumer idempotentConsumer;

    @RabbitListener(queues = AppointmentRabbitConfig.USER_PROFILE_UPDATED_QUEUE)
    public void onUserProfileUpdated(UserProfileUpdatedEvent event) {
        idempotentConsumer.run(event.eventId(), USER_CONSUMER, () -> {
            int updated = appointmentService.refreshOwnerSnapshot(event.userId(), event.fullName(), event.phoneNumber());
            if (updated > 0) {
                log.info("Cập nhật snapshot chủ nuôi {} trên {} lịch hẹn", event.userId(), updated);
            }
        });
    }

    @RabbitListener(queues = AppointmentRabbitConfig.PET_UPDATED_QUEUE)
    public void onPetUpdated(PetUpdatedEvent event) {
        idempotentConsumer.run(event.eventId(), PET_CONSUMER, () -> {
            int updated = appointmentService.refreshPetSnapshot(event.petId(), event.petName(), event.ownerId(),
                    event.ownerName(), event.ownerPhone());
            if (updated > 0) {
                log.info("Cập nhật snapshot thú cưng {} trên {} lịch hẹn", event.petId(), updated);
            }
        });
    }
}
