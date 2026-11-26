package com.vetclinic.auth.service.impl;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.CreateStaffRequest;
import com.vetclinic.auth.dto.request.UpdateStaffRequest;
import com.vetclinic.auth.dto.request.VetProfileRequest;
import com.vetclinic.auth.dto.response.StaffResponse;
import com.vetclinic.auth.constant.EmploymentType;
import com.vetclinic.auth.constant.Gender;
import com.vetclinic.auth.entity.Role;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.auth.entity.UserProfile;
import com.vetclinic.auth.entity.VeterinarianProfile;
import com.vetclinic.auth.mapper.UserMapper;
import com.vetclinic.auth.repository.RoleRepository;
import com.vetclinic.auth.repository.StaffProfileRepository;
import com.vetclinic.auth.repository.UserProfileRepository;
import com.vetclinic.auth.repository.UserRepository;
import com.vetclinic.auth.repository.UserSpecifications;
import com.vetclinic.auth.repository.VeterinarianProfileRepository;
import com.vetclinic.auth.service.AccountRules;
import com.vetclinic.auth.service.StaffService;
import com.vetclinic.auth.service.UserEventPublisher;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.vetclinic.auth.service.AccountRules.blankToNull;
import static com.vetclinic.auth.service.AccountRules.hasText;
import static com.vetclinic.auth.service.AccountRules.requireEncodablePassword;

@Slf4j
@Service
@RequiredArgsConstructor
public class StaffServiceImpl implements StaffService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserProfileRepository userProfileRepository;
    private final StaffProfileRepository staffProfileRepository;
    private final VeterinarianProfileRepository vetProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final UserEventPublisher events;
    private final Clock clock;

    @Override
    @Transactional
    public StaffResponse create(CreateStaffRequest request) {
        requireEncodablePassword(request.password());
        Set<RoleCode> roleCodes = EnumSet.noneOf(RoleCode.class);
        roleCodes.addAll(request.roleCodes());
        if (!AccountRules.STAFF_ROLES.containsAll(roleCodes)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Khách hàng (PET_OWNER) không phải nhân sự: Lễ tân tạo ở mục khách hàng");
        }
        // Bác sĩ bắt buộc có hồ sơ chuyên môn (FR-01, BR-37): kiểm tra TRƯỚC mọi thao tác ghi
        if (roleCodes.contains(RoleCode.VETERINARIAN) && request.vetProfile() == null) {
            throw new AppException(ErrorCode.VET_PROFILE_REQUIRED);
        }

        String phone = request.phoneNumber().trim();
        String username = hasText(request.username()) ? request.username().trim() : phone;
        String email = blankToNull(request.email());
        String citizenId = blankToNull(request.citizenId());
        if (userProfileRepository.existsByPhoneNumber(phone)) {
            throw new AppException(ErrorCode.PHONE_NUMBER_ALREADY_EXISTS);
        }
        if (userRepository.existsByUsername(username)) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (email != null && userProfileRepository.existsByEmailIgnoreCase(email)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email đã được sử dụng cho tài khoản khác");
        }
        if (citizenId != null && staffProfileRepository.existsByCitizenId(citizenId)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số CCCD đã tồn tại");
        }
        String room = AccountRules.normalizeRoom(request.roomNumber(), roleCodes);
        if (room != null && staffProfileRepository.existsByRoomNumber(room)) {
            throw new AppException(ErrorCode.ROOM_NUMBER_ALREADY_ASSIGNED);
        }
        if (request.vetProfile() != null) {
            validateVetProfile(request.vetProfile(), null);
        }

        Set<Role> roles = new java.util.LinkedHashSet<>();
        for (RoleCode code : roleCodes) {
            roles.add(roleRepository.findByCode(code).orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND)));
        }
        RoleCode primary = roleCodes.stream().min(Comparator.naturalOrder()).orElseThrow();
        Role primaryRole = roles.stream().filter(r -> r.getCode() == primary).findFirst().orElseThrow();

        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .build();
        user.getRoles().addAll(roles);
        user.setProfile(UserProfile.builder()
                .user(user)
                .fullName(request.fullName().trim())
                .phoneNumber(phone)
                .email(email)
                .address(blankToNull(request.address()))
                .gender(Gender.parse(request.gender()))
                .dateOfBirth(request.dateOfBirth())
                .build());
        StaffProfile staff = StaffProfile.builder()
                .user(user)
                .employeeCode(String.format("NV%04d", staffProfileRepository.nextEmployeeNumber()))
                .citizenId(citizenId)
                .positionTitle(hasText(request.positionTitle()) ? request.positionTitle().trim() : primaryRole.getName())
                .department(hasText(request.department()) ? request.department().trim() : AccountRules.departmentOf(primary))
                .roomNumber(room)
                .employmentType(hasText(request.employmentType()) ? EmploymentType.parse(request.employmentType()) : EmploymentType.FULL_TIME)
                .hireDate(request.hireDate() != null ? request.hireDate() : LocalDate.now(clock.withZone(CLINIC_ZONE)))
                .emergencyContactName(blankToNull(request.emergencyContactName()))
                .emergencyContactPhone(blankToNull(request.emergencyContactPhone()))
                .notes(blankToNull(request.notes()))
                .build();
        user.setStaffProfile(staff);
        if (request.vetProfile() != null) {
            staff.setVetProfile(toEntity(staff, request.vetProfile()));
        }
        User saved = saveAndFlush(user);
        events.profileUpdated(saved);   // các service khác lấy tên / phòng của nhân sự mới (snapshot)
        log.info("Tạo nhân sự userId={}, vai trò={}, bác sĩ có hồ sơ chuyên môn={}", saved.getId(), roleCodes, request.vetProfile() != null);
        return userMapper.toStaff(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StaffResponse> search(RoleCode role, UserStatus status, String keyword, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<User> result = userRepository.findAll(UserSpecifications.staff(role, status, keyword), pageable);
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), userMapper::toStaff);
    }

    @Override
    @Transactional(readOnly = true)
    public StaffResponse getById(UUID id) {
        return userMapper.toStaff(findStaff(id));
    }

    @Override
    @Transactional
    public StaffResponse update(UUID id, UpdateStaffRequest request) {
        User user = findStaff(id);
        StaffProfile staff = user.getStaffProfile();
        String roomBefore = staff.getRoomNumber();

        if (request.roomNumber() != null) {
            Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
            roles.addAll(UserMapper.rolesOf(user));
            String room = AccountRules.normalizeRoom(request.roomNumber(), roles);
            if (room != null && staffProfileRepository.existsByRoomNumberAndUserIdNot(room, user.getId())) {
                throw new AppException(ErrorCode.ROOM_NUMBER_ALREADY_ASSIGNED);
            }
            staff.setRoomNumber(room);
        }
        if (request.positionTitle() != null) {
            staff.setPositionTitle(request.positionTitle().trim());
        }
        if (request.department() != null) {
            staff.setDepartment(blankToNull(request.department()));
        }
        if (hasText(request.employmentType())) {
            staff.setEmploymentType(EmploymentType.parse(request.employmentType()));
        }
        if (request.hireDate() != null) {
            staff.setHireDate(request.hireDate());
        }
        if (request.terminationDate() != null) {
            staff.setTerminationDate(request.terminationDate());
        }
        if (staff.getTerminationDate() != null && staff.getTerminationDate().isBefore(staff.getHireDate())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ngày nghỉ việc không được trước ngày vào làm");
        }
        if (request.citizenId() != null) {
            String citizenId = blankToNull(request.citizenId());
            if (citizenId != null && staffProfileRepository.existsByCitizenIdAndUserIdNot(citizenId, user.getId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số CCCD đã tồn tại");
            }
            staff.setCitizenId(citizenId);
        }
        if (request.emergencyContactName() != null) {
            staff.setEmergencyContactName(blankToNull(request.emergencyContactName()));
        }
        if (request.emergencyContactPhone() != null) {
            staff.setEmergencyContactPhone(blankToNull(request.emergencyContactPhone()));
        }
        if (request.notes() != null) {
            staff.setNotes(blankToNull(request.notes()));
        }
        saveAndFlush(user);
        if (!Objects.equals(roomBefore, staff.getRoomNumber())) {
            events.profileUpdated(user);   // phòng làm việc nằm trong snapshot của service khác
        }
        return userMapper.toStaff(user);
    }

    @Override
    @Transactional
    public StaffResponse upsertVetProfile(UUID id, VetProfileRequest request) {
        User user = findStaff(id);
        StaffProfile staff = user.getStaffProfile();
        validateVetProfile(request, user.getId());
        if (staff.getVetProfile() == null) {
            staff.setVetProfile(toEntity(staff, request));
        } else {
            VeterinarianProfile vet = staff.getVetProfile();
            vet.setLicenseNumber(request.licenseNumber().trim());
            vet.setLicenseIssuedDate(request.licenseIssuedDate());
            vet.setLicenseIssuedBy(request.licenseIssuedBy().trim());
            vet.setLicenseExpiryDate(request.licenseExpiryDate());
            vet.setAcademicDegree(request.academicDegree().trim());
            vet.setSpecialty(blankToNull(request.specialty()));
            vet.setGraduatedFrom(blankToNull(request.graduatedFrom()));
            vet.setCareerStartYear(request.careerStartYear());
            vet.setBiography(blankToNull(request.biography()));
        }
        saveAndFlush(user);
        return userMapper.toStaff(user);
    }

    // ------------------------------------------------------------------ helper

    private User findStaff(UUID id) {
        User user = userRepository.findDetailedById(id).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (user.getStaffProfile() == null) {
            // Khách hàng không có hồ sơ nhân sự: coi như không tìm thấy trong danh sách nhân sự
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    /** Ngày hết hạn phải sau ngày cấp (ràng buộc CSDL) và số chứng chỉ không trùng với người khác. */
    private void validateVetProfile(VetProfileRequest request, UUID currentUserId) {
        if (request.licenseExpiryDate() != null && !request.licenseExpiryDate().isAfter(request.licenseIssuedDate())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Ngày hết hạn chứng chỉ phải sau ngày cấp");
        }
        String license = request.licenseNumber().trim();
        boolean taken = currentUserId == null
                ? vetProfileRepository.existsByLicenseNumber(license)
                : vetProfileRepository.existsByLicenseNumberAndUserIdNot(license, currentUserId);
        if (taken) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Số chứng chỉ hành nghề đã được dùng cho nhân sự khác");
        }
    }

    private static VeterinarianProfile toEntity(StaffProfile staff, VetProfileRequest request) {
        return VeterinarianProfile.builder()
                .staff(staff)
                .licenseNumber(request.licenseNumber().trim())
                .licenseIssuedDate(request.licenseIssuedDate())
                .licenseIssuedBy(request.licenseIssuedBy().trim())
                .licenseExpiryDate(request.licenseExpiryDate())
                .academicDegree(request.academicDegree().trim())
                .specialty(blankToNull(request.specialty()))
                .graduatedFrom(blankToNull(request.graduatedFrom()))
                .careerStartYear(request.careerStartYear())
                .biography(blankToNull(request.biography()))
                .build();
    }

    private User saveAndFlush(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Hai request đồng thời cùng qua bước kiểm tra trùng; ràng buộc UNIQUE của DB là chốt chặn cuối
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS, "Tên đăng nhập, số điện thoại, email, CCCD hoặc số chứng chỉ đã tồn tại");
        }
    }
}
