package com.vetclinic.clinical.service;

import java.util.UUID;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import com.vetclinic.clinical.repository.PetVisitStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lược đồ v3.0 bỏ các cột {@code started_at, closed_at, completed_at, cancel_reason} khỏi pet_visits vì chúng trùng với
 * bảng lịch sử. Lớp này suy ra các mốc đó từ pet_visit_status_histories bằng MỘT truy vấn theo lô cho cả danh sách.
 */
@Component
@RequiredArgsConstructor
public class VisitTimeline {

    /**
     * @param startedAt   lúc Bác sĩ bắt đầu khám lần đầu (T02), null nếu chưa
     * @param closedAt    lúc chốt chuyên môn (T05/T09) hoặc lúc ca kết thúc theo đường khác; null nếu ca chưa chốt hoặc đã được mở lại
     * @param completedAt lúc COMPLETED (T10 / khách bỏ về), null nếu ca chưa hoàn tất
     * @param cancelReason lý do hủy (notes của dòng lịch sử CANCELLED), null nếu ca không bị hủy
     */
    public record Times(Instant startedAt, Instant closedAt, Instant completedAt, String cancelReason) {
        static final Times EMPTY = new Times(null, null, null, null);
    }

    private final PetVisitStatusHistoryRepository historyRepository;

    public Times of(PetVisit visit) {
        return ofAll(List.of(visit)).getOrDefault(visit.getId(), Times.EMPTY);
    }

    public Map<UUID, Times> ofAll(Collection<PetVisit> visits) {
        if (visits.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<PetVisitStatusHistory>> histories = historyRepository
                .findByVisitIdInOrderByIdAsc(visits.stream().map(PetVisit::getId).toList()).stream()
                .collect(Collectors.groupingBy(PetVisitStatusHistory::getVisitId));
        Map<UUID, Times> result = new HashMap<>();
        for (PetVisit v : visits) {
            result.put(v.getId(), compute(v.getStatus(), histories.getOrDefault(v.getId(), List.of())));
        }
        return result;
    }

    /** {@code history} đã sắp theo id tăng dần. */
    static Times compute(VisitStatus current, List<PetVisitStatusHistory> history) {
        Instant started = null;
        Instant lastPaymentWait = null;
        Instant lastCompleted = null;
        PetVisitStatusHistory lastCancelled = null;
        for (PetVisitStatusHistory h : history) {
            switch (h.getToStatus()) {
                case IN_PROGRESS -> {
                    if (started == null) {
                        started = h.getCreatedAt();   // lần vào IN_PROGRESS đầu tiên = bắt đầu khám GĐ 1
                    }
                }
                case WAITING_PAYMENT -> lastPaymentWait = h.getCreatedAt();
                case COMPLETED -> lastCompleted = h.getCreatedAt();
                case CANCELLED -> lastCancelled = h;
                default -> { }
            }
        }
        boolean finishedOrClosed = current == VisitStatus.WAITING_PAYMENT || current == VisitStatus.COMPLETED
                || current == VisitStatus.CANCELLED;
        Instant completedAt = current == VisitStatus.COMPLETED ? lastCompleted : null;
        String cancelReason = current == VisitStatus.CANCELLED && lastCancelled != null ? lastCancelled.getNotes() : null;
        Instant closedAt = null;
        if (finishedOrClosed) {
            closedAt = lastPaymentWait != null ? lastPaymentWait
                    : current == VisitStatus.COMPLETED ? lastCompleted
                    : lastCancelled != null ? lastCancelled.getCreatedAt() : null;
        }
        return new Times(started, closedAt, completedAt, cancelReason);
    }
}
