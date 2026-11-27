package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.ServiceCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceCategoryRepository extends JpaRepository<ServiceCategory, Short> {

    Optional<ServiceCategory> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Short id);

    List<ServiceCategory> findAllByOrderByDisplayOrderAscIdAsc();

    List<ServiceCategory> findByActiveTrueOrderByDisplayOrderAscIdAsc();
}
