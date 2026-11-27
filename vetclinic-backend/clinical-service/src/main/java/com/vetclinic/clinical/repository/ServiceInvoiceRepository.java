package com.vetclinic.clinical.repository;

import java.util.UUID;
import com.vetclinic.clinical.entity.ServiceInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceInvoiceRepository extends JpaRepository<ServiceInvoice, UUID> {

    /** Mỗi dòng chỉ định có đúng một hóa đơn (UNIQUE visit_service_detail_id). */
    Optional<ServiceInvoice> findByVisitServiceDetailId(UUID visitServiceDetailId);

    /** Tra cứu theo lô để dựng response của cả một đợt khám bằng MỘT câu truy vấn (không N+1). */
    List<ServiceInvoice> findByVisitServiceDetailIdIn(Collection<UUID> visitServiceDetailIds);
}
