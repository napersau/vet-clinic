package com.vetclinic.auth.service.impl;

import com.vetclinic.auth.constant.UserStatus;
import com.vetclinic.auth.dto.request.CreateStaffRequest;
import com.vetclinic.auth.dto.request.UpdateStaffRequest;
import com.vetclinic.auth.dto.request.VetProfileRequest;
import com.vetclinic.auth.dto.response.StaffResponse;
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
import com.vetclinic.auth.repository.VeterinarianProfileRepository;
import com.vetclinic.auth.service.UserEventPublisher;
import com.vetclinic.common.exception.AppException;
import com.vetclinic.common.exception.ErrorCode;
import com.vetclinic.common.security.RoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaffServiceImplTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private UserProfileRepository userProfileRepository;
    @Mock
    private StaffProfileRepository staffProfileRepository;
    @Mock
    private VeterinarianProfileRepository vetProfileRepository;
    @Mock
    private UserEventPublisher events;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-20T03:00:00Z"), ZoneOffset.UTC);
    private StaffServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffServiceImpl(userRepository, roleRepository, userProfileRepository, staffProfileRepository,
                vetProfileRepository, encoder, new UserMapper(), events, clock);
        lenient().when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User saved = inv.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(uid(50));
            }
            return saved;
        });
        for (RoleCode code : RoleCode.values()) {
            lenient().when(roleRepository.findByCode(code)).thenReturn(Optional.of(role(code)));
        }
        lenient().when(staffProfileRepository.nextEmployeeNumber()).thenReturn(8L);
    }

    private static UUID uid(long n) {
        return UUID.fromString(String.format("019a9442-d810-7000-8000-%012d", n));
    }

    private static Role role(RoleCode code) {
        return Role.builder().id((short) (code.ordinal() + 1)).code(code).name(code.name()).build();
    }

    private static void assertError(ErrorCode expected, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static VetProfileRequest vet(String license) {
        return new VetProfileRequest(license, LocalDate.of(2020, 1, 1), "Cục Thú y", LocalDate.of(2030, 1, 1),
                "Bác sĩ thú y", "Nội khoa", "ĐH Nông nghiệp", (short) 2018, null);
    }

    private static CreateStaffRequest createRequest(Set<RoleCode> roles, String room, VetProfileRequest vet) {
        return new CreateStaffRequest(null, "Staff@1234", "Nhân sự Mới", "0955555551", "ns@example.com", null, null, null,
                roles, room, null, null, null, null, null, null, null, null, vet);
    }

    private User savedUser() {
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private static User staffUser(UUID id, RoleCode code, String room) {
        User user = User.builder().id(id).username("u" + id).passwordHash("h").status(UserStatus.ACTIVE).build();
        user.getRoles().add(role(code));
        user.setProfile(UserProfile.builder().user(user).fullName("Người " + id).phoneNumber("0900000000").build());
        user.setStaffProfile(StaffProfile.builder().user(user).employeeCode("NV0001").positionTitle(code.name())
                .roomNumber(room).hireDate(LocalDate.of(2026, 1, 2)).build());
        return user;
    }

    // ================================================================== tạo nhân sự

    @Test
    void create_aDoctorWritesUserRolesProfilesAndVetProfileInOneSave() {
        StaffResponse response = service.create(createRequest(Set.of(RoleCode.VETERINARIAN), "p05", vet("CC-001")));

        User user = savedUser();   // một lần saveAndFlush duy nhất: cascade ghi cả 4 bảng cùng giao dịch
        assertThat(user.getRoles()).extracting(Role::getCode).containsExactly(RoleCode.VETERINARIAN);
        assertThat(user.getUsername()).isEqualTo("0955555551");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(encoder.matches("Staff@1234", user.getPasswordHash())).isTrue();
        assertThat(user.getProfile().getFullName()).isEqualTo("Nhân sự Mới");
        assertThat(user.getPetOwnerProfile()).isNull();
        StaffProfile staff = user.getStaffProfile();
        assertThat(staff.getEmployeeCode()).isEqualTo("NV0008");
        assertThat(staff.getDepartment()).isEqualTo("Khám bệnh");
        assertThat(staff.getRoomNumber()).isEqualTo("P05");
        assertThat(staff.getHireDate()).isEqualTo(LocalDate.of(2026, 11, 20));   // giờ phòng khám (UTC+7), không phải giờ máy
        assertThat(staff.getVetProfile().getLicenseNumber()).isEqualTo("CC-001");
        assertThat(staff.getVetProfile().getSpecialty()).isEqualTo("Nội khoa");
        assertThat(response.roomNumber()).isEqualTo("P05");
        assertThat(response.vetProfile()).isNotNull();
        verify(events).profileUpdated(user);
    }

    @Test
    void create_aDoctorWithoutAVetProfileIsRefusedBeforeAnyWrite() {
        assertError(ErrorCode.VET_PROFILE_REQUIRED, () -> service.create(createRequest(Set.of(RoleCode.VETERINARIAN), "P05", null)));

        verify(userRepository, never()).saveAndFlush(any());
        verify(events, never()).profileUpdated(any());
    }

    @Test
    void create_aDoctorWhoIsAlsoAdminStillNeedsTheVetProfile() {
        assertError(ErrorCode.VET_PROFILE_REQUIRED,
                () -> service.create(createRequest(Set.of(RoleCode.VETERINARIAN, RoleCode.ADMIN), "P05", null)));
    }

    @Test
    void create_aReceptionistNeedsNoVetProfileAndGetsTheReceptionDepartment() {
        service.create(createRequest(Set.of(RoleCode.RECEPTIONIST), null, null));

        User user = savedUser();
        assertThat(user.getStaffProfile().getDepartment()).isEqualTo("Tiếp đón");
        assertThat(user.getStaffProfile().getVetProfile()).isNull();
        assertThat(user.getStaffProfile().getRoomNumber()).isNull();
    }

    @Test
    void create_petOwnersAreNotStaff() {
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(createRequest(Set.of(RoleCode.PET_OWNER), null, null)));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_onlyDoctorsAndTechniciansMayHaveARoom() {
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(createRequest(Set.of(RoleCode.RECEPTIONIST), "P01", null)));
    }

    @Test
    void create_refusesDuplicates() {
        when(userProfileRepository.existsByPhoneNumber("0955555551")).thenReturn(true);
        assertError(ErrorCode.PHONE_NUMBER_ALREADY_EXISTS, () -> service.create(createRequest(Set.of(RoleCode.RECEPTIONIST), null, null)));

        when(userProfileRepository.existsByPhoneNumber("0955555551")).thenReturn(false);
        when(staffProfileRepository.existsByRoomNumber("P05")).thenReturn(true);
        assertError(ErrorCode.ROOM_NUMBER_ALREADY_ASSIGNED, () -> service.create(createRequest(Set.of(RoleCode.LAB_TECHNICIAN), "P05", null)));

        when(staffProfileRepository.existsByRoomNumber("P06")).thenReturn(false);
        when(vetProfileRepository.existsByLicenseNumber("CC-001")).thenReturn(true);
        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(createRequest(Set.of(RoleCode.VETERINARIAN), "P06", vet("CC-001"))));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void create_theLicenseExpiryMustBeAfterTheIssueDate() {
        VetProfileRequest bad = new VetProfileRequest("CC-009", LocalDate.of(2025, 5, 1), "Cục Thú y", LocalDate.of(2025, 5, 1),
                "Bác sĩ thú y", null, null, null, null);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.create(createRequest(Set.of(RoleCode.VETERINARIAN), "P05", bad)));
        verify(userRepository, never()).saveAndFlush(any());
    }

    // ================================================================== sửa hồ sơ

    @Test
    void update_changingTheRoomWritesAnOutboxEvent() {
        User doctor = staffUser(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));

        StaffResponse response = service.update(uid(7), new UpdateStaffRequest("p02", "Trưởng khoa", null, null, null, null, null, null, null, null));

        assertThat(doctor.getStaffProfile().getRoomNumber()).isEqualTo("P02");
        assertThat(doctor.getStaffProfile().getPositionTitle()).isEqualTo("Trưởng khoa");
        assertThat(response.roomNumber()).isEqualTo("P02");
        verify(events).profileUpdated(doctor);
    }

    @Test
    void update_notTouchingTheRoomWritesNoEvent() {
        User doctor = staffUser(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));

        service.update(uid(7), new UpdateStaffRequest(null, null, null, "PART_TIME", null, null, null, null, null, "ghi chú"));

        assertThat(doctor.getStaffProfile().getEmploymentType()).isEqualTo(com.vetclinic.auth.constant.EmploymentType.PART_TIME);
        assertThat(doctor.getStaffProfile().getNotes()).isEqualTo("ghi chú");
        verify(events, never()).profileUpdated(any());
    }

    @Test
    void update_anEmptyRoomClearsItAndARoomOfSomeoneElseIsRefused() {
        User doctor = staffUser(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));
        when(staffProfileRepository.existsByRoomNumberAndUserIdNot("P09", uid(7))).thenReturn(true);

        assertError(ErrorCode.ROOM_NUMBER_ALREADY_ASSIGNED,
                () -> service.update(uid(7), new UpdateStaffRequest("P09", null, null, null, null, null, null, null, null, null)));

        service.update(uid(7), new UpdateStaffRequest("", null, null, null, null, null, null, null, null, null));
        assertThat(doctor.getStaffProfile().getRoomNumber()).isNull();
    }

    @Test
    void update_terminationCannotPrecedeTheHireDate() {
        User doctor = staffUser(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));

        assertError(ErrorCode.INVALID_INPUT_DATA,
                () -> service.update(uid(7), new UpdateStaffRequest(null, null, null, null, null, LocalDate.of(2025, 12, 31), null, null, null, null)));
    }

    @Test
    void getById_aCustomerIsNotInTheStaffList() {
        User owner = User.builder().id(uid(8)).username("kh").passwordHash("h").build();
        owner.setProfile(UserProfile.builder().user(owner).fullName("Khách").phoneNumber("0911111111").build());
        when(userRepository.findDetailedById(uid(8))).thenReturn(Optional.of(owner));
        when(userRepository.findDetailedById(uid(99))).thenReturn(Optional.empty());

        assertError(ErrorCode.USER_NOT_FOUND, () -> service.getById(uid(8)));
        assertError(ErrorCode.USER_NOT_FOUND, () -> service.getById(uid(99)));
    }

    // ================================================================== hồ sơ chuyên môn

    @Test
    void upsertVetProfile_createsThenUpdatesTheSameProfile() {
        User receptionist = staffUser(uid(7), RoleCode.RECEPTIONIST, null);
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(receptionist));

        service.upsertVetProfile(uid(7), vet("CC-100"));
        VeterinarianProfile created = receptionist.getStaffProfile().getVetProfile();
        assertThat(created.getLicenseNumber()).isEqualTo("CC-100");

        service.upsertVetProfile(uid(7), vet("CC-101"));
        assertThat(receptionist.getStaffProfile().getVetProfile()).isSameAs(created);   // sửa tại chỗ, không tạo bản ghi thứ hai
        assertThat(created.getLicenseNumber()).isEqualTo("CC-101");
    }

    @Test
    void upsertVetProfile_refusesALicenseOfAnotherStaffMember() {
        User doctor = staffUser(uid(7), RoleCode.VETERINARIAN, "P01");
        when(userRepository.findDetailedById(uid(7))).thenReturn(Optional.of(doctor));
        when(vetProfileRepository.existsByLicenseNumberAndUserIdNot("CC-200", uid(7))).thenReturn(true);

        assertError(ErrorCode.INVALID_INPUT_DATA, () -> service.upsertVetProfile(uid(7), vet("CC-200")));
        assertThat(doctor.getStaffProfile().getVetProfile()).isNull();
    }
}
