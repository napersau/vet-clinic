package com.vetclinic.auth.repository;

import com.vetclinic.auth.entity.PetOwnerProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PetOwnerProfileRepository extends JpaRepository<PetOwnerProfile, UUID> {

    /** Số thứ tự kế tiếp của mã khách hàng KHxxxx. UNIQUE(customer_code) là chốt chặn nếu hai quầy tạo đồng thời. */
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(customer_code, 3) AS UNSIGNED)), 0) + 1 FROM pet_owner_profiles", nativeQuery = true)
    long nextCustomerNumber();
}
