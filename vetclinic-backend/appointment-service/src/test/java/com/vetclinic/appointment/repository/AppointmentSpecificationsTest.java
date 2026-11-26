package com.vetclinic.appointment.repository;

import com.vetclinic.appointment.entity.Appointment;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * appointments không còn cột owner_id: "lịch của một chủ nuôi" được lọc bằng petId IN (thú cưng của chủ đó).
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class AppointmentSpecificationsTest {

    private final Root<Appointment> root = mock(Root.class);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);
    private final Predicate predicate = mock(Predicate.class);

    @Test
    void nullOwner_addsNoOwnerCondition() {
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        AppointmentSpecifications.filter(null, null, null, null, null, null).toPredicate(root, query, cb);

        verify(root, never()).get("ownerId");
        verify(root, never()).get("petId");
    }

    @Test
    void ownerId_isFilteredDirectlyOnTheOwnerColumn() {
        UUID owner = UUID.fromString("019a9442-d810-7000-8000-000000000008");
        Path<Object> ownerPath = mock(Path.class);
        when(root.get("ownerId")).thenReturn(ownerPath);
        when(cb.equal(ownerPath, owner)).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        AppointmentSpecifications.filter(owner, null, null, null, null, null).toPredicate(root, query, cb);

        verify(cb).equal(ownerPath, owner);   // owner_id nằm ngay trên appointments (v4.0): không còn danh sách pet_id từ clinical-service
        verify(cb, never()).disjunction();
    }

    @Test
    void petId_isFilteredByEquality() {
        UUID pet = UUID.fromString("019a9442-d820-7000-8000-000000000001");
        Path<Object> petPath = mock(Path.class);
        when(root.get("petId")).thenReturn(petPath);
        when(cb.equal(petPath, pet)).thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);

        AppointmentSpecifications.filter(null, pet, null, null, null, null).toPredicate(root, query, cb);

        verify(cb).equal(petPath, pet);
    }
}
