package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.ClinicService;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClinicServiceRepository extends JpaRepository<ClinicService, UUID> {
    boolean existsByCode(String code);

    Optional<ClinicService> findByCode(String code);

    /** Danh sách theo thứ tự hiển thị của nhóm rồi theo tên; tùy chọn lọc theo mã nhóm và chỉ lấy dịch vụ đang dùng. */
    @Query("select s from ClinicService s where (:activeOnly = false or s.active = true) "
            + "and (:categoryCode is null or s.category.code = :categoryCode) "
            + "order by s.category.displayOrder, s.name")
    List<ClinicService> search(@Param("activeOnly") boolean activeOnly, @Param("categoryCode") String categoryCode);

    List<ClinicService> findByIdInAndActiveTrue(Collection<UUID> ids);
}
