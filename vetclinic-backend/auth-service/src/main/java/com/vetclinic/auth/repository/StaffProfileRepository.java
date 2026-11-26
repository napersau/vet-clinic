package com.vetclinic.auth.repository;

import com.vetclinic.auth.entity.StaffProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface StaffProfileRepository extends JpaRepository<StaffProfile, UUID> {

    boolean existsByRoomNumber(String roomNumber);

    boolean existsByRoomNumberAndUserIdNot(String roomNumber, UUID userId);

    boolean existsByCitizenId(String citizenId);

    boolean existsByCitizenIdAndUserIdNot(String citizenId, UUID userId);

    /** Số thứ tự kế tiếp của mã nhân viên NVxxxx. UNIQUE(employee_code) là chốt chặn nếu hai Admin tạo đồng thời. */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(employee_code, 3) AS UNSIGNED)), 0) + 1 FROM staff_profiles", nativeQuery = true)
    long nextEmployeeNumber();
}
