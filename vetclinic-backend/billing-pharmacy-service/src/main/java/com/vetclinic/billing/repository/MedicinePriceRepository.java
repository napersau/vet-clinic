package com.vetclinic.billing.repository;

import com.vetclinic.billing.entity.MedicinePrice;
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
public interface MedicinePriceRepository extends JpaRepository<MedicinePrice, Long> {

    /** Giá hiệu lực tại thời điểm {@code at}: effective_from <= at và (chưa đóng hoặc đóng sau at). */
    @Query("select p from MedicinePrice p where p.medicine.id = :medicineId and p.effectiveFrom <= :at "
            + "and (p.effectiveTo is null or p.effectiveTo > :at)")
    Optional<MedicinePrice> findEffective(@Param("medicineId") UUID medicineId, @Param("at") Instant at);

    /** Giá hiệu lực của nhiều thuốc cùng lúc (danh sách thuốc kèm giá, không N+1). */
    @Query("select p from MedicinePrice p join fetch p.medicine where p.medicine.id in :medicineIds and p.effectiveFrom <= :at "
            + "and (p.effectiveTo is null or p.effectiveTo > :at)")
    List<MedicinePrice> findEffectiveForMedicines(@Param("medicineIds") Collection<UUID> medicineIds, @Param("at") Instant at);

    /** Dòng đang mở (effective_to IS NULL): giá hiện hành hoặc giá đã lên lịch trong tương lai. */
    Optional<MedicinePrice> findByMedicineIdAndEffectiveToIsNull(UUID medicineId);

    List<MedicinePrice> findByMedicineIdOrderByEffectiveFromDesc(UUID medicineId);
}
