package com.vetclinic.appointment.repository;

import com.vetclinic.appointment.constant.AppointmentStatus;
import com.vetclinic.appointment.dto.internal.SlotBookedCount;
import com.vetclinic.appointment.entity.Appointment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, UUID>, JpaSpecificationExecutor<Appointment> {

    /**
     * Khóa dòng (SELECT ... FOR UPDATE) cho mọi thao tác chuyển trạng thái. Bảng appointments không có cột version,
     * nên đây là chốt chặn để confirm / cancel / check-in / no-show xảy ra cùng lúc không ghi đè lên nhau.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Appointment a where a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") UUID id);

    long countByAppointmentDateAndStartTimeAndStatusIn(LocalDate date, LocalTime startTime,
                                                        Collection<AppointmentStatus> statuses);

    @Query("""
            select new com.vetclinic.appointment.dto.internal.SlotBookedCount(a.startTime, count(a))
            from Appointment a
            where a.appointmentDate = :date and a.status in :statuses
            group by a.startTime
            """)
    List<SlotBookedCount> countBookedBySlot(@Param("date") LocalDate date,
                                            @Param("statuses") Collection<AppointmentStatus> statuses);

    boolean existsByPetIdAndAppointmentDateAndStatusIn(UUID petId, LocalDate date,
                                                       Collection<AppointmentStatus> statuses);

    boolean existsByPetIdAndAppointmentDateAndStatusInAndIdNot(UUID petId, LocalDate date,
                                                               Collection<AppointmentStatus> statuses, UUID id);

    /**
     * Lịch hẹn ở trạng thái {@code status} có GIỜ BẮT ĐẦU trước mốc (cutoffDate, cutoffTime). Bảng appointments không còn cột
     * end_time (= start_time + độ dài khung) nên người gọi tự trừ độ dài khung / thời gian chờ vào mốc cắt. So sánh theo
     * (ngày, giờ) nên lịch của các ngày trước cũng được quét, kể cả khi service đã tắt lúc nửa đêm hay cuối tuần.
     */
    @Query("""
            select a.id from Appointment a
            where a.status = :status
              and (a.appointmentDate < :cutoffDate
                   or (a.appointmentDate = :cutoffDate and a.startTime < :cutoffTime))
            order by a.appointmentDate, a.startTime
            """)
    List<UUID> findIdsStartedBefore(@Param("status") AppointmentStatus status,
                                    @Param("cutoffDate") LocalDate cutoffDate,
                                    @Param("cutoffTime") LocalTime cutoffTime);

    boolean existsByAppointmentCode(String appointmentCode);

    /** Consumer {@code UserProfileUpdatedEvent}: cập nhật tên / SĐT chủ nuôi (snapshot) trên MỌI lịch hẹn của chủ đó bằng một câu UPDATE. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Appointment a set a.ownerNameSnapshot = :name, a.ownerPhoneSnapshot = :phone where a.ownerId = :ownerId")
    int updateOwnerSnapshot(@Param("ownerId") UUID ownerId, @Param("name") String name, @Param("phone") String phone);

    /** Consumer {@code PetUpdatedEvent}: cập nhật tên thú cưng (snapshot) trên mọi lịch hẹn của thú cưng đó. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Appointment a set a.petNameSnapshot = :name where a.petId = :petId")
    int updatePetNameSnapshot(@Param("petId") UUID petId, @Param("name") String name);

    /**
     * Thú cưng được chuyển sang chủ khác: các lịch CÒN HIỆU LỰC (chờ duyệt / đã duyệt) đi theo chủ mới; lịch đã kết thúc giữ nguyên chủ
     * lúc đó (lịch sử).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Appointment a set a.ownerId = :ownerId, a.ownerNameSnapshot = :name, a.ownerPhoneSnapshot = :phone "
            + "where a.petId = :petId and a.status in :statuses and a.ownerId <> :ownerId")
    int transferOwner(@Param("petId") UUID petId, @Param("ownerId") UUID ownerId, @Param("name") String name,
                      @Param("phone") String phone, @Param("statuses") Collection<AppointmentStatus> statuses);

    /** Báo cáo lịch hẹn: số lịch theo trạng thái / kênh đặt, ngày hẹn trong [from, to]. */
    @Query("select a.status, count(a) from Appointment a where a.appointmentDate >= :from and a.appointmentDate <= :to group by a.status")
    List<Object[]> countByStatusBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select a.bookingChannel, count(a) from Appointment a where a.appointmentDate >= :from and a.appointmentDate <= :to group by a.bookingChannel")
    List<Object[]> countByChannelBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
