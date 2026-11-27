package com.vetclinic.auth.service.impl;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.ChangePasswordRequest;
import com.vetclinic.auth.dto.request.CreateUserRequest;
import com.vetclinic.auth.dto.request.RegisterRequest;
import com.vetclinic.auth.dto.request.UpdateProfileRequest;
import com.vetclinic.auth.dto.request.UpdateUserProfileRequest;
import com.vetclinic.auth.dto.response.RoleResponse;
import com.vetclinic.auth.dto.response.UserResponse;
import com.vetclinic.auth.dto.response.UserSummaryResponse;
import com.vetclinic.auth.constant.Gender;
import com.vetclinic.auth.constant.RegisteredChannel;
import com.vetclinic.auth.entity.PetOwnerProfile;
import com.vetclinic.auth.entity.Role;
import com.vetclinic.auth.entity.StaffProfile;
import com.vetclinic.auth.entity.User;
import com.vetclinic.auth.entity.UserProfile;
import com.vetclinic.auth.mapper.UserMapper;
import com.vetclinic.auth.repository.PetOwnerProfileRepository;
import com.vetclinic.auth.repository.RefreshTokenRepository;
import com.vetclinic.auth.repository.RoleRepository;
import com.vetclinic.auth.repository.UserProfileRepository;
import com.vetclinic.auth.repository.UserRepository;
import com.vetclinic.auth.repository.UserSpecifications;
import com.vetclinic.auth.service.AccountRules;
import com.vetclinic.auth.service.UserEventPublisher;
import com.vetclinic.auth.service.UserService;
import com.vetclinic.common.dto.PageResponse;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import com.vetclinic.common.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.vetclinic.auth.service.AccountRules.blankToNull;
import static com.vetclinic.auth.service.AccountRules.hasText;
import static com.vetclinic.auth.service.AccountRules.requireEncodablePassword;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_BATCH_IDS = 200;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserProfileRepository userProfileRepository;
    private final PetOwnerProfileRepository petOwnerProfileRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final UserEventPublisher events;

    // ------------------------------------------------------------------ người dùng hiện tại

    @Override
    @Transactional(readOnly = true)
    public UserResponse getMe() {
        User user = findCurrentUser();
        // Access Token còn hạn nhưng tài khoản đã bị khóa sau đó: không cho tiếp tục dùng
        AccountRules.assertUsable(user);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse updateMe(UpdateProfileRequest request) {
        User user = findCurrentUser();
        AccountRules.assertUsable(user);
        applyProfile(user, new UpdateUserProfileRequest(request.fullName(), request.phoneNumber(), request.email(),
                request.address(), null, null));
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User user = findCurrentUser();
        AccountRules.assertUsable(user);

        if (AccountRules.exceedsBcryptLimit(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new AppException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        requireEncodablePassword(request.newPassword());
        if (request.currentPassword().equals(request.newPassword())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Mật khẩu mới phải khác mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        // flushAutomatically ghi mật khẩu mới xuống DB trước khi thu hồi token
        refreshTokenRepository.revokeAllUserTokens(user.getId());
        log.info("Người dùng userId={} đã đổi mật khẩu, thu hồi toàn bộ phiên", user.getId());
    }

    // ------------------------------------------------------------------ khách hàng

    @Override
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (request.roleCode() != RoleCode.PET_OWNER) {
            // Nhân sự phải kèm hồ sơ nhân sự (và hồ sơ chuyên môn với Bác sĩ) nên chỉ tạo được qua POST /api/v1/staff của Admin
            throw new AppException(ErrorCode.INVALID_INPUT_DATA,
                    "Chỉ tạo được tài khoản khách hàng ở đây; tài khoản nhân sự tạo qua POST /api/v1/staff");
        }
        currentRole();   // phải đăng nhập (đã chặn vai trò ở @PreAuthorize)
        log.info("Tạo tài khoản khách hàng bởi userId={}", SecurityUtils.getCurrentUserId());
        return userMapper.toResponse(persistNewOwner(request, RegisteredChannel.AT_CLINIC));
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        User user = persistNewOwner(new CreateUserRequest(null, request.password(), request.fullName(), request.phoneNumber(),
                request.email(), request.address(), null, RoleCode.PET_OWNER), RegisteredChannel.ONLINE);
        log.info("Chủ nuôi tự đăng ký: userId={}", user.getId());
        return userMapper.toResponse(user);
    }

    /** Một giao dịch ghi {@code users + user_roles(PET_OWNER) + user_profiles + pet_owner_profiles}. */
    private User persistNewOwner(CreateUserRequest request, RegisteredChannel channel) {
        requireEncodablePassword(request.password());

        String phone = request.phoneNumber().trim();
        String username = hasText(request.username()) ? request.username().trim() : phone;
        String email = blankToNull(request.email());
        // Kiểm tra SĐT trước: khi tên đăng nhập mặc định là SĐT thì "trùng SĐT" là thông báo đúng với người dùng
        if (userProfileRepository.existsByPhoneNumber(phone)) {
            throw new AppException(ErrorCode.PHONE_NUMBER_ALREADY_EXISTS);
        }
        if (userRepository.existsByUsername(username)) {
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }
        if (email != null && userProfileRepository.existsByEmailIgnoreCase(email)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email đã được sử dụng cho tài khoản khác");
        }
        if (hasText(request.roomNumber())) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Khách hàng không có số phòng làm việc");
        }

        Role role = roleRepository.findByCode(RoleCode.PET_OWNER).orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));
        User user = User.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode(request.password()))
                .status(UserStatus.ACTIVE)
                .build();
        user.getRoles().add(role);
        user.setProfile(UserProfile.builder()
                .user(user)
                .fullName(request.fullName().trim())
                .phoneNumber(phone)
                .email(email)
                .address(blankToNull(request.address()))
                .build());
        user.setPetOwnerProfile(PetOwnerProfile.builder()
                .user(user)
                .customerCode(String.format("KH%04d", petOwnerProfileRepository.nextCustomerNumber()))
                .registeredChannel(channel)
                .build());
        return saveAndFlush(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(RoleCode role, UserStatus status, String keyword, int page, int size) {
        // Lễ tân chỉ tra cứu được khách hàng (FR-08), không xem danh sách nhân sự
        if (!actsAsAdmin()) {
            role = RoleCode.PET_OWNER;
        }
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));   // UUIDv7 xếp được theo thời gian: mới nhất trước
        Page<User> result = userRepository.findAll(UserSpecifications.filter(role, status, keyword), pageable);
        return PageResponse.of(result.getContent(), result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isLast(), userMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getById(UUID id) {
        return userMapper.toResponse(findById(id));
    }

    // ------------------------------------------------------------------ hồ sơ, vai trò, trạng thái

    @Override
    @Transactional
    public UserResponse updateProfile(UUID id, UpdateUserProfileRequest request) {
        boolean admin = actsAsAdmin();
        User user = findById(id);
        // Lễ tân chỉ sửa hồ sơ khách hàng tại quầy, không đụng tới hồ sơ nhân sự
        if (!admin && !UserMapper.rolesOf(user).equals(List.of(RoleCode.PET_OWNER))) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        applyProfile(user, request);
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public UserResponse setRoles(UUID id, Set<RoleCode> roleCodes) {
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT);
        }
        User user = findById(id);
        List<RoleCode> oldRoles = UserMapper.rolesOf(user);
        Set<RoleCode> newRoles = EnumSet.noneOf(RoleCode.class);
        newRoles.addAll(roleCodes);
        if (oldRoles.contains(RoleCode.PET_OWNER) || newRoles.contains(RoleCode.PET_OWNER)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Không đổi vai trò giữa chủ nuôi và nhân sự");
        }
        if (new LinkedHashSet<>(oldRoles).equals(newRoles)) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tài khoản đã có đúng các vai trò này");
        }
        StaffProfile staff = user.getStaffProfile();
        if (staff == null) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tài khoản không có hồ sơ nhân sự");
        }
        if (newRoles.contains(RoleCode.VETERINARIAN) && staff.getVetProfile() == null) {
            throw new AppException(ErrorCode.VET_PROFILE_REQUIRED);   // phải bổ sung hồ sơ chuyên môn trước khi gán vai trò Bác sĩ
        }

        List<Role> roles = new ArrayList<>();
        for (RoleCode code : newRoles) {
            roles.add(roleRepository.findByCode(code).orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND)));
        }
        user.getRoles().clear();
        user.getRoles().addAll(roles);

        RoleCode oldPrimary = oldRoles.isEmpty() ? null : oldRoles.get(0);
        RoleCode newPrimary = newRoles.stream().min(Comparator.naturalOrder()).orElseThrow();
        if (oldPrimary != newPrimary) {   // vai trò chính đổi thì chức danh / bộ phận đi theo
            staff.setPositionTitle(roles.stream().filter(r -> r.getCode() == newPrimary).findFirst().orElseThrow().getName());
            staff.setDepartment(AccountRules.departmentOf(newPrimary));
        }
        boolean roomCleared = false;
        if (newRoles.stream().noneMatch(AccountRules.ROOM_ROLES::contains) && staff.getRoomNumber() != null) {
            staff.setRoomNumber(null);   // không còn vai trò nào có phòng cố định
            roomCleared = true;
        }
        saveAndFlush(user);
        if (roomCleared) {
            events.profileUpdated(user);
        }
        // Access Token cũ vẫn mang vai trò cũ tối đa 30 phút; Refresh Token bị thu hồi nên phiên kế tiếp phải đăng nhập lại
        refreshTokenRepository.revokeAllUserTokens(user.getId());
        log.info("Đổi vai trò userId={}: {} -> {} bởi userId={}", id, oldRoles, newRoles, SecurityUtils.getCurrentUserId());
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        boolean admin = actsAsAdmin();
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT);
        }
        requireEncodablePassword(newPassword);
        User user = findById(id);
        // Lễ tân chỉ đặt lại mật khẩu cho chủ nuôi tại quầy, không đụng tới tài khoản nhân sự
        if (!admin && !UserMapper.rolesOf(user).equals(List.of(RoleCode.PET_OWNER))) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        refreshTokenRepository.revokeAllUserTokens(user.getId());
        log.info("Đặt lại mật khẩu userId={} bởi userId={}", id, SecurityUtils.getCurrentUserId());
    }

    @Override
    @Transactional
    public UserResponse lock(UUID id) {
        return transition(id, UserStatus.LOCKED);
    }

    @Override
    @Transactional
    public UserResponse unlock(UUID id) {
        return transition(id, UserStatus.ACTIVE);
    }

    @Override
    @Transactional
    public UserResponse deactivate(UUID id) {
        return transition(id, UserStatus.INACTIVE);
    }

    /** Khóa / mở khóa / ngừng sử dụng: kiểm tra chuyển hợp lệ, thu hồi phiên khi rời ACTIVE, ghi sự kiện cùng giao dịch. */
    private UserResponse transition(UUID id, UserStatus to) {
        if (id.equals(SecurityUtils.getCurrentUserId())) {
            throw new AppException(ErrorCode.CANNOT_MODIFY_OWN_ACCOUNT);
        }
        User user = findById(id);
        UserStatus from = user.getStatus();
        if (from == to) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tài khoản đã ở trạng thái này");
        }
        if (to == UserStatus.LOCKED && from != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Chỉ khóa được tài khoản đang hoạt động");
        }
        user.setStatus(to);
        saveAndFlush(user);
        if (to != UserStatus.ACTIVE) {
            // flushAutomatically ghi trạng thái mới trước; Access Token còn lại tự hết hạn trong tối đa 30 phút
            refreshTokenRepository.revokeAllUserTokens(user.getId());
        }
        events.statusChanged(user, from, to);
        log.info("Đổi trạng thái tài khoản userId={}: {} -> {} bởi userId={}", id, from, to, SecurityUtils.getCurrentUserId());
        return userMapper.toResponse(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles() {
        return roleRepository.findAll(Sort.by("id")).stream().map(userMapper::toRole).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getActiveDoctors() {
        return getDoctors(UserStatus.ACTIVE);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getDoctors(UserStatus status) {
        return userRepository.findByRoleAndStatus(RoleCode.VETERINARIAN, status == null ? UserStatus.ACTIVE : status)
                .stream().map(userMapper::toSummary).toList();
    }

    // ------------------------------------------------------------------ nội bộ

    @Override
    @Transactional(readOnly = true)
    public UserSummaryResponse getSummary(UUID id) {
        return userMapper.toSummary(findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserSummaryResponse> getSummaries(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > MAX_BATCH_IDS) {
            throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Tối đa " + MAX_BATCH_IDS + " id mỗi lần tra cứu");
        }
        return userRepository.findDetailedByIdIn(ids).stream().map(userMapper::toSummary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countActiveDoctors() {
        return userRepository.countByRoleAndStatus(RoleCode.VETERINARIAN, UserStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ helper

    private User findById(UUID id) {
        return userRepository.findDetailedById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private User findCurrentUser() {
        return findById(SecurityUtils.getCurrentUserId());
    }

    private RoleCode currentRole() {
        return SecurityUtils.getCurrentRole().orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
    }

    /** Có vai trò nào đó (nếu không đăng nhập thì ném UNAUTHORIZED) và vai trò đó có ADMIN hay không. */
    private boolean actsAsAdmin() {
        currentRole();
        return SecurityUtils.hasRole(RoleCode.ADMIN);
    }

    /**
     * Cập nhật các trường hồ sơ có gửi lên (null = giữ nguyên), kiểm tra trùng SĐT / email, ghi xuống DB và phát
     * {@code UserProfileUpdatedEvent} (outbox, cùng giao dịch) nếu họ tên / SĐT / email thật sự đổi.
     */
    private void applyProfile(User user, UpdateUserProfileRequest request) {
        UserProfile profile = user.getProfile();
        String before = signature(profile);
        if (request.fullName() != null) {
            if (request.fullName().isBlank()) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Họ tên không được để trống");
            }
            profile.setFullName(request.fullName().trim());
        }
        if (request.phoneNumber() != null) {
            String phone = request.phoneNumber().trim();
            if (!phone.equals(profile.getPhoneNumber())) {
                if (userProfileRepository.existsByPhoneNumberAndUserIdNot(phone, user.getId())) {
                    throw new AppException(ErrorCode.PHONE_NUMBER_ALREADY_EXISTS);
                }
                profile.setPhoneNumber(phone);
            }
        }
        if (request.email() != null) {
            String newEmail = blankToNull(request.email());
            if (newEmail != null && !newEmail.equalsIgnoreCase(profile.getEmail())
                    && userProfileRepository.existsByEmailIgnoreCaseAndUserIdNot(newEmail, user.getId())) {
                throw new AppException(ErrorCode.INVALID_INPUT_DATA, "Email đã được sử dụng cho tài khoản khác");
            }
            profile.setEmail(newEmail);
        }
        if (request.address() != null) {
            profile.setAddress(blankToNull(request.address()));
        }
        if (request.gender() != null) {
            profile.setGender(Gender.parse(request.gender()));
        }
        if (request.dateOfBirth() != null) {
            profile.setDateOfBirth(request.dateOfBirth());
        }
        saveAndFlush(user);
        if (!Objects.equals(before, signature(profile))) {
            events.profileUpdated(user);
        }
    }

    /** Phần của hồ sơ mà các service khác sao chép (snapshot); khác nhau thì phải báo bằng sự kiện. */
    private static String signature(UserProfile profile) {
        return profile.getFullName() + "|" + profile.getPhoneNumber() + "|" + profile.getEmail();
    }

    private User saveAndFlush(User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Hai request đồng thời cùng qua bước kiểm tra trùng; ràng buộc UNIQUE của DB là chốt chặn cuối
            throw new AppException(ErrorCode.USERNAME_ALREADY_EXISTS, "Tên đăng nhập, số điện thoại hoặc email đã tồn tại");
        }
    }
}
