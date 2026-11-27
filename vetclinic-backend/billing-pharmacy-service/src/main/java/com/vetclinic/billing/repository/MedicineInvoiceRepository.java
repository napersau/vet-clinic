package com.vetclinic.billing.repository;

import com.vetclinic.billing.constant.PaymentStatus;
import com.vetclinic.billing.entity.MedicineInvoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MedicineInvoiceRepository extends JpaRepository<MedicineInvoice, UUID> {

    /** Khóa dòng cho mọi thao tác ghi lên hóa đơn (sửa / hủy / thu tiền): hai thu ngân bấm đồng thời thì người sau chờ (EX-16). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MedicineInvoice i where i.id = :id")
    Optional<MedicineInvoice> findByIdForUpdate(@Param("id") UUID id);

    /** Khóa theo đơn thuốc: dựng nháp từ sự kiện không chen vào lúc Thu ngân đang sửa / thu hóa đơn đó. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MedicineInvoice i where i.prescriptionId = :prescriptionId")
    Optional<MedicineInvoice> findByPrescriptionIdForUpdate(@Param("prescriptionId") UUID prescriptionId);

    @EntityGraph(attributePaths = {"items", "items.medicine", "items.medicine.stock"})
    Optional<MedicineInvoice> findWithItemsById(UUID id);

    @EntityGraph(attributePaths = {"items", "items.medicine", "items.medicine.stock"})
    List<MedicineInvoice> findWithItemsByVisitIdOrderByCreatedAtDesc(UUID visitId);

    Optional<MedicineInvoice> findByPrescriptionId(UUID prescriptionId);

}
