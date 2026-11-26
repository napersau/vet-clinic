package com.vetclinic.auth.repository;

import com.vetclinic.auth.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    boolean existsByPhoneNumber(String phoneNumber);

    boolean existsByPhoneNumberAndUserIdNot(String phoneNumber, UUID userId);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCaseAndUserIdNot(String email, UUID userId);
}
