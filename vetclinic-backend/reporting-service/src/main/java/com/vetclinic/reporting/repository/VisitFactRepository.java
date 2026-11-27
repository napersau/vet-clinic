package com.vetclinic.reporting.repository;

import com.vetclinic.reporting.entity.VisitFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface VisitFactRepository extends JpaRepository<VisitFact, UUID> {

    /** Số ca và số ca cấp cứu theo trạng thái, theo ngày tiếp đón trong khoảng. */
    @Query("""
            select new com.vetclinic.reporting.repository.StatusCount(v.currentStatus, count(v),
                   coalesce(sum(case when v.emergency = true then 1L else 0L end), 0L))
            from VisitFact v where v.businessDate between :from and :to group by v.currentStatus""")
    List<StatusCount> countByStatus(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Số ca được giao / hoàn tất của từng Bác sĩ (ca chưa có Bác sĩ không tính). */
    @Query("""
            select new com.vetclinic.reporting.repository.DoctorLoadRow(v.doctorId, max(v.doctorName), count(v),
                   coalesce(sum(case when v.currentStatus = 'COMPLETED' then 1L else 0L end), 0L))
            from VisitFact v where v.doctorId is not null and v.businessDate between :from and :to
            group by v.doctorId order by count(v) desc""")
    List<DoctorLoadRow> doctorLoads(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Dựng lại báo cáo: xóa các ca tiếp đón từ ngày {@code from} trở đi (check-in sẽ phát lại từ outbox). */
    @Modifying
    @Query("DELETE FROM VisitFact v WHERE v.businessDate >= :from")
    int deleteFromDate(@Param("from") LocalDate from);

    /**
     * Dựng lại báo cáo: ca tiếp đón TRƯỚC cửa sổ nhưng hoàn tất / hủy TRONG cửa sổ được đưa về WAITING để sự kiện hoàn tất / hủy phát lại
     * cộng lại đúng (nếu không, ca đã COMPLETED sẽ bị bỏ qua và hiệu suất Bác sĩ của ngày đó bị thiếu).
     */
    @Modifying
    @Query("UPDATE VisitFact v SET v.currentStatus = 'WAITING', v.completedAt = NULL WHERE v.visitId IN :ids AND v.businessDate < :from")
    int reopenBefore(@Param("ids") java.util.Collection<UUID> ids, @Param("from") LocalDate from);

    @Modifying
    @Query("UPDATE VisitFact v SET v.doctorName = :name WHERE v.doctorId = :userId")
    int renameDoctor(@Param("userId") UUID userId, @Param("name") String name);

    @Modifying
    @Query("UPDATE VisitFact v SET v.ownerName = :name WHERE v.ownerId = :userId")
    int renameOwner(@Param("userId") UUID userId, @Param("name") String name);

    @Modifying
    @Query("UPDATE VisitFact v SET v.petName = :petName, v.ownerId = :ownerId, v.ownerName = :ownerName WHERE v.petId = :petId")
    int updatePet(@Param("petId") UUID petId, @Param("petName") String petName, @Param("ownerId") UUID ownerId, @Param("ownerName") String ownerName);
}
