package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.constant.VisitStatus;
import com.vetclinic.clinical.entity.PetVisit;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface PetVisitRepository extends JpaRepository<PetVisit, UUID>, JpaSpecificationExecutor<PetVisit> {

    /**
     * Khóa dòng (SELECT ... FOR UPDATE) cho mọi thao tác ghi lên đợt khám: serialize các thao tác cùng lúc
     * (hai KTV cùng hoàn tất, Bác sĩ bấm hai lần...) và đảm bảo kiểm tra trạng thái nguồn không bị chen ngang.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PetVisit v where v.id = :id")
    Optional<PetVisit> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    Optional<PetVisit> findWithPetById(UUID id);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    List<PetVisit> findWithPetByIdIn(Collection<UUID> ids);

    /** Số ca đang chờ (WAITING, RESULTS_READY) của từng Bác sĩ: các dòng [doctorId, count]. */
    @Query("select v.doctorId, count(v) from PetVisit v where v.doctorId is not null "
            + "and v.status in (com.vetclinic.clinical.constant.VisitStatus.WAITING, com.vetclinic.clinical.constant.VisitStatus.RESULTS_READY) "
            + "group by v.doctorId")
    List<Object[]> countWaitingByDoctor();

    /** Ca COMPLETED còn hóa đơn dịch vụ PENDING (khách bỏ về, công nợ), mới nhất trước. */
    @EntityGraph(attributePaths = {"pet", "pet.species"})
    @Query("select v from PetVisit v where v.status = com.vetclinic.clinical.constant.VisitStatus.COMPLETED "
            + "and exists (select 1 from ServiceInvoice i, VisitServiceDetail d where d.id = i.visitServiceDetailId "
            + "and d.visitId = v.id and i.paymentStatus = com.vetclinic.clinical.constant.PaymentStatus.PENDING) "
            + "order by v.id desc")
    List<PetVisit> findCompletedWithDebt();

    /** Các ca của một Bác sĩ theo trạng thái (thứ tự hàng đợi thật nằm ở queue_tickets), cấp cứu trước rồi theo giờ tiếp đón. */
    @EntityGraph(attributePaths = {"pet", "pet.species"})
    @Query("select v from PetVisit v where v.doctorId = :doctorId and v.status in :statuses "
            + "order by case when v.emergency = true then 0 else 1 end asc, v.checkedInAt asc, v.id asc")
    List<PetVisit> findDoctorVisits(@Param("doctorId") UUID doctorId, @Param("statuses") Collection<VisitStatus> statuses);

    /**
     * Cân nặng gần nhất của mỗi thú cưng = lần đo (vital_signs) có cân ghi sau cùng; pets không còn cột weight.
     * Trả các dòng [petId, weightKg].
     */
    @Query("select v.pet.id, s.weightKg from VitalSign s, PetVisit v where s.visitId = v.id and s.weightKg is not null and s.id in "
            + "(select max(s2.id) from VitalSign s2, PetVisit v2 where s2.visitId = v2.id and v2.pet.id in :petIds and s2.weightKg is not null group by v2.pet.id)")
    List<Object[]> findLatestWeights(@Param("petIds") Collection<UUID> petIds);

    /** Bác sĩ đổi họ tên (UserProfileUpdatedEvent): cập nhật bản chụp tên trên mọi đợt khám đã gán Bác sĩ đó, một câu UPDATE. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PetVisit v set v.doctorNameSnapshot = :name where v.doctorId = :doctorId and (v.doctorNameSnapshot is null or v.doctorNameSnapshot <> :name)")
    int updateDoctorNameSnapshot(@Param("doctorId") UUID doctorId, @Param("name") String name);

    /** EX-17: các đợt khám đang chờ của một Bác sĩ, khóa dòng (FOR UPDATE) để gỡ phân công không đè lên một lần phân công đồng thời. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from PetVisit v where v.doctorId = :doctorId and v.status = :status order by v.id")
    List<PetVisit> findByDoctorIdAndStatusForUpdate(@Param("doctorId") UUID doctorId, @Param("status") VisitStatus status);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    List<PetVisit> findByPetIdOrderByIdDesc(UUID petId);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    Page<PetVisit> findByPetId(UUID petId, Pageable pageable);

    @EntityGraph(attributePaths = {"pet", "pet.species"})
    Page<PetVisit> findByPetIdAndStatus(UUID petId, VisitStatus status,
                                                                        Pageable pageable);

    boolean existsByPetIdAndStatusIn(UUID petId, Collection<VisitStatus> statuses);

    boolean existsByAppointmentId(UUID appointmentId);

    boolean existsByDoctorIdAndStatus(UUID doctorId, VisitStatus status);

    // ---- Trạng thái Bác sĩ (tính động): chỉ cần biết bác sĩ nào đang ở tình trạng nào
    @Query("select distinct v.doctorId from PetVisit v where v.doctorId is not null and v.status in :statuses")
    Set<UUID> findDoctorIdsWithStatusIn(@Param("statuses") Collection<VisitStatus> statuses);

    /** Ca theo trạng thái, cũ nhất trước (màn hình Thu ngân). */
    @EntityGraph(attributePaths = {"pet", "pet.species"})
    List<PetVisit> findByStatusOrderByIdAsc(VisitStatus status);
}
