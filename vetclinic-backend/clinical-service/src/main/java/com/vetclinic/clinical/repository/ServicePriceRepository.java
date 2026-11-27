package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.ServicePrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServicePriceRepository extends JpaRepository<ServicePrice, Long> {

    /** Giá hiệu lực tại thời điểm {@code at}: effective_from <= at và (chưa đóng hoặc đóng sau at). */
    @Query("select p from ServicePrice p where p.service.id = :serviceId and p.effectiveFrom <= :at "
            + "and (p.effectiveTo is null or p.effectiveTo > :at)")
    Optional<ServicePrice> findEffective(@Param("serviceId") UUID serviceId, @Param("at") Instant at);

    /** Giá hiệu lực của nhiều dịch vụ cùng lúc (danh sách dịch vụ kèm giá, không N+1). */
    @Query("select p from ServicePrice p where p.service.id in :serviceIds and p.effectiveFrom <= :at "
            + "and (p.effectiveTo is null or p.effectiveTo > :at)")
    List<ServicePrice> findEffectiveForServices(@Param("serviceIds") Collection<UUID> serviceIds, @Param("at") Instant at);

    /** Dòng đang mở (effective_to IS NULL): giá hiện hành hoặc giá đã lên lịch trong tương lai. */
    Optional<ServicePrice> findByServiceIdAndEffectiveToIsNull(UUID serviceId);

    List<ServicePrice> findByServiceIdOrderByEffectiveFromDesc(UUID serviceId);
}
