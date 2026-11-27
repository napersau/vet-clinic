package com.vetclinic.clinical.repository;

import java.util.UUID;
import com.vetclinic.clinical.constant.ServiceKind;
import com.vetclinic.clinical.constant.ServiceDetailStatus;
import com.vetclinic.clinical.entity.VisitServiceDetail;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface VisitServiceDetailRepository extends JpaRepository<VisitServiceDetail, UUID> {

    /** Khóa dòng chỉ định. KHÔNG fetch join dịch vụ: tránh khóa lây sang bảng danh mục services dùng chung. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from VisitServiceDetail d where d.id = :id")
    Optional<VisitServiceDetail> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = "service")
    List<VisitServiceDetail> findByVisitIdOrderByIdAsc(UUID visitId);

    /** Dịch vụ XN / CĐHA đang thực hiện của một phòng (theo queue_type của nhóm dịch vụ): tìm phiếu IN_SERVICE của phòng, vì phiếu đang phục vụ đã rời hàng đợi chờ trên Redis. */
    @Query("select d from VisitServiceDetail d where d.status = com.vetclinic.clinical.constant.ServiceDetailStatus.IN_PROGRESS "
            + "and d.service.category.queueType = :queueType")
    List<VisitServiceDetail> findInProgressByQueueType(@Param("queueType") String queueType);

    @EntityGraph(attributePaths = "service")
    List<VisitServiceDetail> findByVisitIdInOrderByIdAsc(Collection<UUID> visitIds);

    @EntityGraph(attributePaths = "service")
    List<VisitServiceDetail> findByVisitIdAndStatusOrderByIdAsc(UUID visitId, ServiceDetailStatus status);

    /** Số chỉ định của đợt khám thuộc loại (tại phòng / cận lâm sàng) và đang ở một trong các trạng thái. Loại suy ra từ {@code service_categories.queue_type}. */
    default long countByVisitAndKindAndStatuses(UUID visitId, ServiceKind kind, Collection<ServiceDetailStatus> statuses) {
        return countByVisitAndInRoomAndStatuses(visitId, kind == ServiceKind.IN_ROOM, statuses);
    }

    @Query("select count(d) from VisitServiceDetail d where d.visitId = :visitId "
            + "and ((:inRoom = true and d.service.category.queueType is null) "
            + "or (:inRoom = false and d.service.category.queueType in ('LABORATORY', 'IMAGING'))) "
            + "and d.status in :statuses")
    long countByVisitAndInRoomAndStatuses(@Param("visitId") UUID visitId, @Param("inRoom") boolean inRoom,
                                          @Param("statuses") Collection<ServiceDetailStatus> statuses);

    @Query("select count(d) > 0 from VisitServiceDetail d where d.visitId = :visitId and d.service.code = :code")
    boolean existsByVisitAndServiceCode(@Param("visitId") UUID visitId, @Param("code") String code);
}
