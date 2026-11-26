package com.vetclinic.auth.repository;

import com.vetclinic.auth.entity.VeterinarianProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VeterinarianProfileRepository extends JpaRepository<VeterinarianProfile, UUID> {

    boolean existsByLicenseNumber(String licenseNumber);

    boolean existsByLicenseNumberAndUserIdNot(String licenseNumber, UUID userId);
}
