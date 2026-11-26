package com.vetclinic.appointment.repository;

import com.vetclinic.appointment.entity.AppointmentStatusHistory;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AppointmentStatusHistoryRepository extends JpaRepository<AppointmentStatusHistory, Long> {

    List<AppointmentStatusHistory> findByAppointmentIdOrderByIdAsc(UUID appointmentId);

    /** Tra cứu theo lô để suy ra người/lý do duyệt, hủy của cả danh sách lịch hẹn bằng MỘT câu truy vấn (không N+1). */
    List<AppointmentStatusHistory> findByAppointmentIdInOrderByIdAsc(Collection<UUID> appointmentIds);
}
