-- ==============================================================================
-- SEED DỮ LIỆU DEMO v4.0 (chỉ dùng cho môi trường dev/demo, chạy SAU schema_init.sql)
-- UUID cố định; mọi lệnh curl kiểm thử trong Lộ trình
-- (LICH_TRINH_XAY_DUNG_MA_NGUON_VA_HUONG_DAN_TEST.md, Task 1.2.3) và các bộ e2e dựa trên các định danh này.
--
-- Mật khẩu chung của MỌI tài khoản demo (kể cả admin): Admin@123
-- (cùng hash BCrypt strength 12 với admin trong schema_init.sql)
--
-- Tài khoản: id người dùng là UUIDv7 cố định 019a9442-d810-7000-8000-0000000000NN
--   01 admin (đã có trong schema_init.sql, @u_admin)
--   02 bs_tien (VETERINARIAN, P101) · 03 bs_an (P102) · 04 bs_mai (P103) · 05 letan_hoa (RECEPTIONIST)
--   06 ktv_lab_minh (LAB_TECHNICIAN, P201) · 07 ktv_cdha_dung (IMAGING_TECHNICIAN, P202)
--   08 khach_lan (PET_OWNER; thú cưng Lu, Milo) · 09 khach_nam · 10 khach_huong · 11 khach_duc · 12 khach_thao · 13 khach_binh
-- Thú cưng: UUIDv7 cố định 019a9442-d820-7000-8000-0000000000NN (NN = số thứ tự: Lu = 01, Milo = 02 ... Cam = 14).
-- Dịch vụ, thuốc, nhóm, loài, vai trò: đã có trong schema_init.sql (UUID / mã ổn định theo bảng danh mục).
-- Hồ sơ chuyên môn của 3 Bác sĩ (veterinarian_profiles) là bắt buộc với vai trò VETERINARIAN; owner_*_snapshot của thú cưng lấy
-- từ user_profiles của chủ nuôi (ứng dụng cập nhật về sau bằng UserProfileUpdatedEvent).
-- LƯU Ý: seed CHỈ thêm người dùng và thú cưng. KHÔNG thêm lịch hẹn, đợt khám, hóa đơn hay biến động kho, để các luồng chuẩn của
-- Lộ trình chạy trên dữ liệu sạch (luồng chuẩn dịch vụ 530.000đ + thuốc 157.500đ; Clavamox tồn 200 -> 190).
-- ==============================================================================
SET NAMES utf8mb4;

-- Mỗi file .sql trong docker-entrypoint-initdb.d chạy bằng một phiên mysql riêng nên biến phải khai báo lại ở đây.
SET @u_bs_tien = UUID_TO_BIN('019a9442-d810-7000-8000-000000000002');
SET @u_bs_an = UUID_TO_BIN('019a9442-d810-7000-8000-000000000003');
SET @u_bs_mai = UUID_TO_BIN('019a9442-d810-7000-8000-000000000004');
SET @u_letan_hoa = UUID_TO_BIN('019a9442-d810-7000-8000-000000000005');
SET @u_ktv_lab_minh = UUID_TO_BIN('019a9442-d810-7000-8000-000000000006');
SET @u_ktv_cdha_dung = UUID_TO_BIN('019a9442-d810-7000-8000-000000000007');
SET @u_khach_lan = UUID_TO_BIN('019a9442-d810-7000-8000-000000000008');
SET @u_khach_nam = UUID_TO_BIN('019a9442-d810-7000-8000-000000000009');
SET @u_khach_huong = UUID_TO_BIN('019a9442-d810-7000-8000-000000000010');
SET @u_khach_duc = UUID_TO_BIN('019a9442-d810-7000-8000-000000000011');
SET @u_khach_thao = UUID_TO_BIN('019a9442-d810-7000-8000-000000000012');
SET @u_khach_binh = UUID_TO_BIN('019a9442-d810-7000-8000-000000000013');

USE pet_clinic_auth_db;
-- Tài khoản nhân sự (admin đã có trong schema_current.sql)
INSERT INTO users (id, username, password_hash, status) VALUES
(@u_bs_tien, 'bs_tien', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_bs_an, 'bs_an', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_bs_mai, 'bs_mai', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_letan_hoa, 'letan_hoa', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_ktv_lab_minh, 'ktv_lab_minh', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_ktv_cdha_dung, 'ktv_cdha_dung', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE');
INSERT INTO user_roles (user_id, role_id) VALUES
(@u_bs_tien, (SELECT id FROM roles WHERE code = 'VETERINARIAN')),
(@u_bs_an, (SELECT id FROM roles WHERE code = 'VETERINARIAN')),
(@u_bs_mai, (SELECT id FROM roles WHERE code = 'VETERINARIAN')),
(@u_letan_hoa, (SELECT id FROM roles WHERE code = 'RECEPTIONIST')),
(@u_ktv_lab_minh, (SELECT id FROM roles WHERE code = 'LAB_TECHNICIAN')),
(@u_ktv_cdha_dung, (SELECT id FROM roles WHERE code = 'IMAGING_TECHNICIAN'));
INSERT INTO user_profiles (user_id, full_name, phone_number, email) VALUES
(@u_bs_tien, 'Nguyễn Văn Tiến', '0902000002', NULL),
(@u_bs_an, 'Trần Bình An', '0902000003', NULL),
(@u_bs_mai, 'Lê Thị Mai', '0902000004', NULL),
(@u_letan_hoa, 'Phạm Thu Hoa', '0902000005', NULL),
(@u_ktv_lab_minh, 'Đỗ Quang Minh', '0902000006', NULL),
(@u_ktv_cdha_dung, 'Vũ Tiến Dũng', '0902000007', NULL);
INSERT INTO staff_profiles (user_id, employee_code, position_title, department, room_number, hire_date) VALUES
(@u_bs_tien, 'NV0002', 'Bác sĩ thú y', 'Khám bệnh', 'P101', '2026-01-02'),
(@u_bs_an, 'NV0003', 'Bác sĩ thú y', 'Khám bệnh', 'P102', '2026-01-02'),
(@u_bs_mai, 'NV0004', 'Bác sĩ thú y', 'Khám bệnh', 'P103', '2026-01-02'),
(@u_letan_hoa, 'NV0005', 'Lễ tân / Thu ngân', 'Tiếp đón', NULL, '2026-01-02'),
(@u_ktv_lab_minh, 'NV0006', 'KTV xét nghiệm', 'Xét nghiệm', 'P201', '2026-01-02'),
(@u_ktv_cdha_dung, 'NV0007', 'KTV chẩn đoán hình ảnh', 'Chẩn đoán hình ảnh', 'P202', '2026-01-02');
-- Hồ sơ chuyên môn của Bác sĩ (bắt buộc với vai trò VETERINARIAN)
INSERT INTO veterinarian_profiles (user_id, license_number, license_issued_date, license_issued_by, academic_degree, specialty, career_start_year) VALUES
(@u_bs_tien, 'CCHN-TY-2020-0002', '2020-05-10', 'Chi cục Chăn nuôi và Thú y TP.HCM', 'Bác sĩ thú y', 'Nội khoa', 2014),
(@u_bs_an, 'CCHN-TY-2020-0003', '2020-05-10', 'Chi cục Chăn nuôi và Thú y TP.HCM', 'Bác sĩ thú y', 'Ngoại khoa', 2016),
(@u_bs_mai, 'CCHN-TY-2021-0004', '2020-05-10', 'Chi cục Chăn nuôi và Thú y TP.HCM', 'Bác sĩ thú y', 'Da liễu', 2018);

-- Chủ nuôi (mật khẩu chung Admin@123): tài khoản + vai trò PET_OWNER + hồ sơ cá nhân + hồ sơ khách hàng
INSERT INTO users (id, username, password_hash, status) VALUES
(@u_khach_lan, 'khach_lan', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_khach_nam, 'khach_nam', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_khach_huong, 'khach_huong', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_khach_duc, 'khach_duc', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_khach_thao, 'khach_thao', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE'),
(@u_khach_binh, 'khach_binh', '$2a$12$QfxJtMwsg2Wd72ZkvcQ.BeADCJqgMjJ7OPeBqNkj1um98Jt/dcBPG', 'ACTIVE');
INSERT INTO user_roles (user_id, role_id) SELECT u.id, r.id FROM users u JOIN roles r ON r.code = 'PET_OWNER' WHERE u.username LIKE 'khach\_%';
INSERT INTO user_profiles (user_id, full_name, phone_number, email) VALUES
(@u_khach_lan, 'Hoàng Thị Lan', '0912000008', NULL),
(@u_khach_nam, 'Nguyễn Hoàng Nam', '0912000009', 'nam.demo@example.com'),
(@u_khach_huong, 'Lê Thị Hương', '0912000010', 'huong.demo@example.com'),
(@u_khach_duc, 'Trần Minh Đức', '0912000011', NULL),
(@u_khach_thao, 'Phạm Thanh Thảo', '0912000012', 'thao.demo@example.com'),
(@u_khach_binh, 'Võ Quốc Bình', '0912000013', NULL);
INSERT INTO pet_owner_profiles (user_id, customer_code, registered_channel) VALUES
(@u_khach_lan, 'KH0001', 'AT_CLINIC'),
(@u_khach_nam, 'KH0002', 'AT_CLINIC'),
(@u_khach_huong, 'KH0003', 'AT_CLINIC'),
(@u_khach_duc, 'KH0004', 'AT_CLINIC'),
(@u_khach_thao, 'KH0005', 'AT_CLINIC'),
(@u_khach_binh, 'KH0006', 'AT_CLINIC');

USE pet_clinic_clinical_db;
-- species_id: 1 = Chó (DOG), 2 = Mèo (CAT) theo seed của schema_current.sql
-- Thú cưng: UUID cố định 019a9442-d820-7000-8000-0000000000NN (D11, NN = số thứ tự cũ: Lu = 01, Milo = 02...).
-- owner_*_snapshot lấy từ user_profiles của chủ nuôi (cùng máy MySQL; ứng dụng cập nhật bằng UserProfileUpdatedEvent).
INSERT INTO pets (id, owner_id, owner_name_snapshot, owner_phone_snapshot, species_id, pet_code, name, breed, gender) VALUES
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000001'), @u_khach_lan, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_lan), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_lan), 2, 'PET-2026-0001', 'Lu', 'Mèo Anh lông ngắn', 'FEMALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000002'), @u_khach_lan, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_lan), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_lan), 1, 'PET-2026-0002', 'Milo', 'Poodle', 'MALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000003'), @u_khach_nam, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_nam), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_nam), 1, 'PET-2026-0003', 'Bông', 'Poodle', 'FEMALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000004'), @u_khach_nam, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_nam), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_nam), 1, 'PET-2026-0004', 'Mực', 'Labrador', 'MALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000005'), @u_khach_huong, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_huong), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_huong), 2, 'PET-2026-0005', 'Kitty', 'Mèo Ba Tư', 'FEMALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000006'), @u_khach_huong, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_huong), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_huong), 2, 'PET-2026-0006', 'Tom', 'Mèo mướp', 'MALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000007'), @u_khach_huong, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_huong), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_huong), 2, 'PET-2026-0007', 'Luna', 'Mèo Anh lông dài', 'FEMALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000008'), @u_khach_duc, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_duc), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_duc), 1, 'PET-2026-0008', 'Béo', 'Corgi', 'MALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000009'), @u_khach_duc, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_duc), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_duc), 1, 'PET-2026-0009', 'Sóc', 'Phốc sóc', 'FEMALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000010'), @u_khach_thao, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_thao), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_thao), 1, 'PET-2026-0010', 'Vàng', 'Golden Retriever', 'MALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000011'), @u_khach_thao, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_thao), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_thao), 2, 'PET-2026-0011', 'Mimi', 'Mèo Xiêm', 'FEMALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000012'), @u_khach_binh, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_binh), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_binh), 1, 'PET-2026-0012', 'Rex', 'Becgie Đức', 'MALE'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000013'), @u_khach_binh, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_binh), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_binh), 1, 'PET-2026-0013', 'Nâu', 'Chó ta', 'UNKNOWN'),
(UUID_TO_BIN('019a9442-d820-7000-8000-000000000014'), @u_khach_binh, (SELECT full_name FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_binh), (SELECT phone_number FROM pet_clinic_auth_db.user_profiles WHERE user_id = @u_khach_binh), 2, 'PET-2026-0014', 'Cam', 'Mèo tam thể', 'FEMALE');
