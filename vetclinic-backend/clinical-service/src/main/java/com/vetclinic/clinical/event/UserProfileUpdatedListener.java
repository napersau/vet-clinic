package com.vetclinic.clinical.event;

import com.vetclinic.clinical.repository.PetVisitRepository;
import com.vetclinic.clinical.service.PetService;
import com.vetclinic.common.event.UserProfileUpdatedEvent;
import com.vetclinic.common.event.processed.IdempotentConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * auth-service đổi họ tên / SĐT của một tài khoản: cập nhật {@code pets.owner_name_snapshot / owner_phone_snapshot} của mọi thú cưng
 * thuộc chủ đó. Idempotent: {@link IdempotentConsumer} ghi {@code processed_events} cùng giao dịch với việc cập nhật; nhận trùng thì bỏ
 * qua. Cũng cập nhật {@code pet_visits.doctor_name_snapshot} nếu tài khoản là Bác sĩ đã được gán ca. Lỗi thì retry 3 lần rồi vào DLQ. Tài khoản không có thú cưng (nhân viên) thì câu UPDATE không ảnh hưởng dòng nào.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileUpdatedListener {

    /** Tên listener ghi vào processed_events.consumer. */
    public static final String CONSUMER = "clinical.user-profile-updated";

    private final PetService petService;
    private final PetVisitRepository visitRepository;
    private final IdempotentConsumer idempotentConsumer;

    @RabbitListener(queues = ClinicalRabbitConfig.USER_PROFILE_UPDATED_QUEUE)
    public void onUserProfileUpdated(UserProfileUpdatedEvent event) {
        idempotentConsumer.run(event.eventId(), CONSUMER, () -> {
            int updated = petService.refreshOwnerSnapshot(event.userId(), event.fullName(), event.phoneNumber());
            if (updated > 0) {
                log.info("Cập nhật snapshot chủ nuôi {} trên {} thú cưng", event.userId(), updated);
            }
            // Bác sĩ đổi tên: cập nhật doctor_name_snapshot trên các đợt khám đã gán Bác sĩ đó (tài khoản khác thì không ảnh hưởng dòng nào)
            if (event.fullName() != null) {
                int visits = visitRepository.updateDoctorNameSnapshot(event.userId(), event.fullName());
                if (visits > 0) {
                    log.info("Cập nhật doctor_name_snapshot của Bác sĩ {} trên {} đợt khám", event.userId(), visits);
                }
            }
        });
    }
}
