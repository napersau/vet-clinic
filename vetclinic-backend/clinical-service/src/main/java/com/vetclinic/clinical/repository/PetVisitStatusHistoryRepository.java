package com.vetclinic.clinical.repository;

import java.util.UUID;
import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisitStatusHistory;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PetVisitStatusHistoryRepository extends JpaRepository<PetVisitStatusHistory, Long> {
    List<PetVisitStatusHistory> findByVisitIdOrderByIdAsc(UUID visitId);

    /** Đã từng vào trạng thái {@code toStatus} (vd. đã qua WAITING_RESULTS = đang ở Giai đoạn 2). */
    boolean existsByVisitIdAndToStatus(UUID visitId, VisitStatus toStatus);

    /** Tra cứu theo lô để suy ra các mốc thời gian của cả danh sách đợt khám (VisitTimeline), không N+1. */
    List<PetVisitStatusHistory> findByVisitIdInOrderByIdAsc(Collection<UUID> visitIds);
}
