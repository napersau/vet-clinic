package com.vetclinic.billing.repository;

import com.vetclinic.billing.entity.Medicine;
import com.vetclinic.billing.entity.MedicineIngredient;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MedicineSpecifications {

    private MedicineSpecifications() {
    }

    /**
     * Tham số null / false thì bỏ qua điều kiện tương ứng. keyword tìm theo mã, tên hoặc tên hoạt chất; categoryCode lọc theo nhóm;
     * lowStockOnly = tồn kho chạm ngưỡng cảnh báo (so với dòng medicine_stocks).
     */
    public static Specification<Medicine> filter(String keyword, String categoryCode, Boolean active, boolean lowStockOnly) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + escape(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
                Subquery<Long> byIngredient = query.subquery(Long.class);
                Root<MedicineIngredient> ingredient = byIngredient.from(MedicineIngredient.class);
                byIngredient.select(cb.literal(1L)).where(cb.equal(ingredient.get("medicine"), root),
                        cb.like(cb.lower(ingredient.get("ingredientName")), like, '\\'));
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.exists(byIngredient)));
            }
            if (categoryCode != null && !categoryCode.isBlank()) {
                predicates.add(cb.equal(root.join("category", JoinType.INNER).get("code"), categoryCode.trim().toUpperCase(Locale.ROOT)));
            }
            if (active != null) {
                predicates.add(cb.equal(root.get("active"), active));
            }
            if (lowStockOnly) {
                var stock = root.join("stock", JoinType.INNER);
                predicates.add(cb.lessThanOrEqualTo(stock.<Integer>get("quantityOnHand"), root.<Integer>get("minStockAlert")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
