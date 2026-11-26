package com.vetclinic.auth.repository;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.entity.User;
import com.vetclinic.common.security.RoleCode;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class UserSpecifications {

    /** Ký tự thoát của LIKE (một dấu gạch chéo ngược). */
    private static final char LIKE_ESCAPE = (char) 92;

    private UserSpecifications() {
    }

    /**
     * Lọc theo vai trò, trạng thái và từ khóa (họ tên / SĐT / tên đăng nhập). Tham số null thì bỏ qua điều kiện.
     * Họ tên và SĐT nằm ở user_profiles nên nối bảng hồ sơ; vai trò nối user_roles (mỗi người chỉ khớp một dòng cho một mã vai trò).
     */
    public static Specification<User> filter(RoleCode role, UserStatus status, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (role != null) {
                Join<Object, Object> roles = root.join("roles");
                predicates.add(cb.equal(roles.get("code"), role));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
                Join<Object, Object> profile = root.join("profile");
                predicates.add(cb.or(
                        cb.like(cb.lower(profile.get("fullName")), like, '\\'),
                        cb.like(cb.lower(root.get("username")), like, '\\'),
                        cb.like(profile.get("phoneNumber"), like, '\\')));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * Danh sách NHÂN SỰ: chỉ tài khoản có hồ sơ nhân sự; từ khóa tìm thêm theo mã nhân viên và số phòng.
     * Dùng cho {@code GET /api/v1/staff} (Admin).
     */
    public static Specification<User> staff(RoleCode role, UserStatus status, String keyword) {
        return (root, query, cb) -> {
            Join<Object, Object> staff = root.join("staffProfile");
            Predicate base = filter(role, status, null).toPredicate(root, query, cb);
            if (keyword == null || keyword.isBlank()) {
                return base;
            }
            String like = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
            Join<Object, Object> profile = root.join("profile");
            return cb.and(base, cb.or(
                    cb.like(cb.lower(profile.get("fullName")), like, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("username")), like, LIKE_ESCAPE),
                    cb.like(profile.get("phoneNumber"), like, LIKE_ESCAPE),
                    cb.like(cb.lower(staff.get("employeeCode")), like, LIKE_ESCAPE),
                    cb.like(cb.lower(staff.get("roomNumber")), like, LIKE_ESCAPE)));
        };
    }

    // Người dùng gõ % hoặc _ không được biến thành ký tự đại diện
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
