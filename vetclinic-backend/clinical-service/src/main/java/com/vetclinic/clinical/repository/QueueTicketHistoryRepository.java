package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.QueueTicketHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface QueueTicketHistoryRepository extends JpaRepository<QueueTicketHistory, Long> {

    boolean existsByTicketId(UUID ticketId);

    /** Số phiếu đã có trong lịch sử của ngày / loại hàng đợi đó (UNIQUE queue_type + service_date + ticket_no). */
    boolean existsByQueueTypeAndServiceDateAndTicketNo(com.vetclinic.clinical.constant.QueueType queueType, java.time.LocalDate serviceDate, int ticketNo);

    List<QueueTicketHistory> findByVisitIdOrderByIdAsc(UUID visitId);

    /** Số phiếu lớn nhất đã dùng trong lịch sử của ngày / loại hàng đợi (null nếu chưa có): mốc để khởi tạo lại bộ đếm Redis. */
    @Query("select max(h.ticketNo) from QueueTicketHistory h where h.queueType = :queueType and h.serviceDate = :serviceDate")
    Integer findMaxTicketNo(@Param("queueType") com.vetclinic.clinical.constant.QueueType queueType, @Param("serviceDate") java.time.LocalDate serviceDate);
}
