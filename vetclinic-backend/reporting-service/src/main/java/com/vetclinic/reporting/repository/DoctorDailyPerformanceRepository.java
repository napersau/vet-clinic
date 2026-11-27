package com.vetclinic.reporting.repository;

import com.vetclinic.reporting.entity.DoctorDailyPerformance;
import com.vetclinic.reporting.entity.DoctorDailyPerformanceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DoctorDailyPerformanceRepository extends JpaRepository<DoctorDailyPerformance, DoctorDailyPerformanceId> {

    @Modifying
    @Query("DELETE FROM DoctorDailyPerformance d WHERE d.businessDate >= :from")
    int deleteFromDate(@Param("from") LocalDate from);

    List<DoctorDailyPerformance> findByBusinessDateBetweenOrderByBusinessDateAsc(LocalDate from, LocalDate to);

    /**
     * Cộng dồn nguyên tử số ca / doanh thu dịch vụ của một Bác sĩ trong ngày (upsert một câu lệnh). {@code doctorId} truyền dạng 16 byte
     * (cột BINARY(16), thứ tự byte như Hibernate ghi UUID) để không phụ thuộc cách bind UUID của truy vấn native.
     */
    @Modifying
    @Query(value = """
            INSERT INTO doctor_daily_performances (business_date, doctor_id, doctor_name, completed_visits, cancelled_visits, emergency_visits, service_net_revenue)
            VALUES (:businessDate, :doctorId, :doctorName, :completed, :cancelled, :emergency, :revenue) AS new
            ON DUPLICATE KEY UPDATE
                doctor_name = new.doctor_name,
                completed_visits = doctor_daily_performances.completed_visits + new.completed_visits,
                cancelled_visits = doctor_daily_performances.cancelled_visits + new.cancelled_visits,
                emergency_visits = doctor_daily_performances.emergency_visits + new.emergency_visits,
                service_net_revenue = doctor_daily_performances.service_net_revenue + new.service_net_revenue
            """, nativeQuery = true)
    int accumulate(@Param("businessDate") LocalDate businessDate, @Param("doctorId") byte[] doctorId, @Param("doctorName") String doctorName,
                   @Param("completed") int completed, @Param("cancelled") int cancelled, @Param("emergency") int emergency,
                   @Param("revenue") BigDecimal revenue);

    @Modifying
    @Query("UPDATE DoctorDailyPerformance d SET d.doctorName = :name WHERE d.doctorId = :userId")
    int renameDoctor(@Param("userId") UUID userId, @Param("name") String name);
}
