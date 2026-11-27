package com.vetclinic.billing.repository;

import com.vetclinic.billing.entity.MedicineCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MedicineCategoryRepository extends JpaRepository<MedicineCategory, Short> {

    Optional<MedicineCategory> findByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<MedicineCategory> findAllByOrderByNameAsc();

    List<MedicineCategory> findByActiveTrueOrderByNameAsc();
}
