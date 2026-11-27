package com.vetclinic.reporting.repository;

import com.vetclinic.reporting.constant.RevenueEntryType;
import com.vetclinic.reporting.constant.RevenueType;
import com.vetclinic.reporting.entity.RevenueFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface RevenueFactRepository extends JpaRepository<RevenueFact, Long> {

    /**
     * Các ca (ngày tiếp đón trong khoảng) còn doanh thu RÒNG dương ở các nhóm cận lâm sàng: dịch vụ đã thu rồi hoàn hết (thu + hoàn = 0)
     * thì ca không tính là "có cận lâm sàng".
     */
    @Query("""
            select r.visitId from RevenueFact r
            where r.revenueType = com.vetclinic.reporting.constant.RevenueType.SERVICE and r.categoryCode in :categories
              and r.visitId in (select v.visitId from VisitFact v where v.businessDate between :from and :to)
            group by r.visitId having sum(r.netAmount) > 0""")
    List<UUID> visitsWithPositiveNet(@Param("categories") Collection<String> categories, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Dựng lại báo cáo: xóa các dòng từ ngày nghiệp vụ {@code from} trở đi (sẽ dựng lại từ outbox). */
    @Modifying
    @Query("DELETE FROM RevenueFact r WHERE r.businessDate >= :from")
    int deleteFromDate(@Param("from") LocalDate from);

    boolean existsBySourceInvoiceIdAndItemIdAndEntryType(UUID sourceInvoiceId, UUID itemId, RevenueEntryType entryType);

    List<RevenueFact> findBySourceInvoiceIdAndRevenueTypeAndEntryType(UUID sourceInvoiceId, RevenueType revenueType, RevenueEntryType entryType);

    @Modifying
    @Query("UPDATE RevenueFact r SET r.doctorName = :name WHERE r.doctorId = :userId")
    int renameDoctor(@Param("userId") UUID userId, @Param("name") String name);
}
