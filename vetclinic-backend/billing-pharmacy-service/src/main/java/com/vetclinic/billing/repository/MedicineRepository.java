package com.vetclinic.billing.repository;

import com.vetclinic.billing.entity.Medicine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Danh mục thuốc. Tồn kho KHÔNG nằm ở đây: bảng {@code medicine_stocks} có repository riêng ({@link MedicineStockRepository}) với các câu UPDATE
 * nguyên tử. Các truy vấn đọc kèm nhóm + dòng tồn kho bằng entity graph để danh sách không bị N+1.
 */
@Repository
public interface MedicineRepository extends JpaRepository<Medicine, UUID>, JpaSpecificationExecutor<Medicine> {

    boolean existsByCodeIgnoreCase(String code);

    @Override
    @EntityGraph(attributePaths = {"category", "stock"})
    Page<Medicine> findAll(Specification<Medicine> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"category", "stock"})
    Optional<Medicine> findWithCategoryAndStockById(UUID id);

    @EntityGraph(attributePaths = {"category", "stock"})
    List<Medicine> findWithCategoryAndStockByIdIn(Collection<UUID> ids);
}
