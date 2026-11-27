package com.vetclinic.clinical.repository;

import com.vetclinic.clinical.entity.Pet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PetRepository extends JpaRepository<Pet, UUID>, JpaSpecificationExecutor<Pet> {

    // open-in-view đã tắt nên luôn nạp kèm loài. Hồ sơ đã xóa mềm (deleted_at) coi như không tồn tại với mọi thao tác nghiệp vụ.
    @EntityGraph(attributePaths = "species")
    Optional<Pet> findWithSpeciesByIdAndDeletedAtIsNull(UUID id);

    @Override
    @EntityGraph(attributePaths = "species")
    Page<Pet> findAll(Specification<Pet> spec, Pageable pageable);

    boolean existsByPetCode(String petCode);

    boolean existsByMicrochipNumber(String microchipNumber);

    boolean existsByMicrochipNumberAndIdNot(String microchipNumber, UUID id);

    /**
     * Consumer của {@code UserProfileUpdatedEvent}: cập nhật snapshot tên / SĐT chủ nuôi trên MỌI thú cưng của chủ đó bằng một câu UPDATE
     * (không nạp từng entity).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Pet p set p.ownerNameSnapshot = :name, p.ownerPhoneSnapshot = :phone where p.ownerId = :ownerId")
    int updateOwnerSnapshot(@Param("ownerId") UUID ownerId, @Param("name") String name, @Param("phone") String phone);
}
