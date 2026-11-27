package com.vetclinic.clinical.service;

import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.queue.QueueCoordinator;
import com.vetclinic.clinical.repository.PetVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * EX-17: Bác sĩ bị khóa / ngừng làm việc khi còn ca đang chờ trong hàng đợi. Các đợt khám {@code WAITING} của Bác sĩ đó được GỠ phân công
 * ({@code doctor_id = NULL}, xóa bản chụp tên) và phiếu {@code DOCTOR} đang chờ của chúng chuyển về danh sách chờ phân công, GIỮ NGUYÊN số phiếu
 * và giờ phát (thứ tự không đổi) để Lễ tân phân công lại. KHÔNG đụng tới:
 * <ul>
 *   <li>ca {@code IN_PROGRESS}: đang khám dở thì không ngắt, Quản trị viên chuyển tiếp thủ công nếu cần;</li>
 *   <li>ca {@code RESULTS_READY}: luôn thuộc Bác sĩ khám chính (State Machine Q2), phiếu ưu tiên giữ nguyên;</li>
 *   <li>ca đã hoàn tất: vẫn hiển thị tên Bác sĩ (bản chụp) kèm nhãn "Đã khóa".</li>
 * </ul>
 * Chạy trong giao dịch của {@code IdempotentConsumer} (cùng giao dịch với việc ghi processed_events).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorAvailabilityService {

    private final PetVisitRepository visitRepository;
    private final QueueCoordinator queue;

    /** @return số đợt khám đã gỡ phân công. */
    @Transactional
    public int releaseWaitingVisitsOf(UUID doctorId) {
        // Khóa dòng (FOR UPDATE) để một lần phân công đồng thời của Lễ tân không bị ghi đè nửa chừng
        List<PetVisit> waiting = visitRepository.findByDoctorIdAndStatusForUpdate(doctorId, VisitStatus.WAITING);
        for (PetVisit visit : waiting) {
            visit.setDoctorId(null);
            visit.setDoctorNameSnapshot(null);
            visitRepository.save(visit);
            queue.reassignDoctorTicket(visit.getId(), null);   // move_ticket.lua sau commit: phiếu DOCTOR đang chờ về chờ phân công, giữ số + score
        }
        if (!waiting.isEmpty()) {
            log.info("EX-17: gỡ phân công {} đợt khám đang chờ của Bác sĩ {}", waiting.size(), doctorId);
        }
        return waiting.size();
    }
}
