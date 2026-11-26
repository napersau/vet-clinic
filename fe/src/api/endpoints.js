import { api, request } from './client';

// Một hàm cho mỗi endpoint của backend (qua Gateway). Không chứa logic nghiệp vụ; tên trường giữ nguyên theo backend.
const AUTH = '/api/v1/auth';
const APPT = '/api/v1/appointments';
const CLIN = '/api/v1/clinical';
const VISITS = `${CLIN}/pet-visits`;
const PHARM = '/api/v1/pharmacy';
const BILL = '/api/v1/billing';

export const authApi = {
  login: (username, password) => request(`${AUTH}/login`, { method: 'POST', body: { username, password }, auth: false }),
  register: (body) => request(`${AUTH}/register`, { method: 'POST', body, auth: false }),
  logout: (refreshToken) => request(`${AUTH}/logout`, { method: 'POST', body: { refreshToken }, auth: false }),
  me: () => api.get(`${AUTH}/me`),
  updateMe: (body) => api.put(`${AUTH}/me`, body),
  changePassword: (body) => api.post(`${AUTH}/change-password`, body),
};

const USERS = '/api/v1/users';
const STAFF = '/api/v1/staff';

// Tài khoản (khách hàng và hồ sơ chung): tra cứu / tạo KHÁCH ở /auth/users; hồ sơ, vai trò, khóa ở /users/{id}/...
export const usersApi = {
  list: (query) => api.get(`${AUTH}/users`, query),
  get: (id) => api.get(`${AUTH}/users/${id}`),
  create: (body) => api.post(`${AUTH}/users`, body),
  updateProfile: (id, body) => api.put(`${USERS}/${id}/profile`, body),
  setRoles: (id, roleCodes) => api.put(`${USERS}/${id}/roles`, { roleCodes }),
  lock: (id) => api.post(`${USERS}/${id}/lock`),
  unlock: (id) => api.post(`${USERS}/${id}/unlock`),
  deactivate: (id) => api.post(`${USERS}/${id}/deactivate`),
  resetPassword: (id, newPassword) => api.post(`${AUTH}/users/${id}/reset-password`, { newPassword }),
};

// Nhân sự (Admin): tài khoản + hồ sơ nhân sự + hồ sơ chuyên môn Bác sĩ
export const staffApi = {
  list: (query) => api.get(STAFF, query),
  doctors: (status) => api.get(`${STAFF}/doctors`, status ? { status } : undefined),
  get: (id) => api.get(`${STAFF}/${id}`),
  create: (body) => api.post(STAFF, body),
  update: (id, body) => api.put(`${STAFF}/${id}`, body),
  saveVetProfile: (id, body) => api.put(`${STAFF}/${id}/vet-profile`, body),
};

export const rolesApi = {
  list: () => api.get('/api/v1/roles'),
};

export const catalogApi = {
  species: () => api.get('/api/v1/species'),
  createSpecies: (body) => api.post('/api/v1/species', body),
  updateSpecies: (id, body) => api.put(`/api/v1/species/${id}`, body),
  // Dịch vụ kèm giá đang áp dụng (v4.0: /api/v1/catalog/**); nhóm dịch vụ và lịch sử giá là tài nguyên riêng
  services: (includeInactive = false, categoryCode) => api.get('/api/v1/catalog/services', { includeInactive, categoryCode }),
  createService: (body) => api.post('/api/v1/catalog/services', body),
  updateService: (id, body) => api.put(`/api/v1/catalog/services/${id}`, body),
  servicePrices: (id) => api.get(`/api/v1/catalog/services/${id}/prices`),
  changeServicePrice: (id, body) => api.post(`/api/v1/catalog/services/${id}/prices`, body),
  serviceCategories: (includeInactive = false) => api.get('/api/v1/catalog/service-categories', { includeInactive }),
  createServiceCategory: (body) => api.post('/api/v1/catalog/service-categories', body),
  updateServiceCategory: (id, body) => api.put(`/api/v1/catalog/service-categories/${id}`, body),
  labItems: () => api.get(`${CLIN}/lab-test-items`),
  createLabItem: (body) => api.post(`${CLIN}/lab-test-items`, body),
  updateLabItem: (id, body) => api.put(`${CLIN}/lab-test-items/${id}`, body),
  saveRange: (itemId, speciesId, body) => api.put(`${CLIN}/lab-test-items/${itemId}/ranges/${speciesId}`, body),
  serviceLabItems: (serviceId) => api.get(`${CLIN}/services/${serviceId}/lab-test-items`),
  setServiceLabItems: (serviceId, labTestItemIds) => api.put(`${CLIN}/services/${serviceId}/lab-test-items`, { labTestItemIds }),
};

export const petsApi = {
  list: (query) => api.get('/api/v1/pets', query),
  get: (id) => api.get(`/api/v1/pets/${id}`),
  create: (body) => api.post('/api/v1/pets', body),
  update: (id, body) => api.put(`/api/v1/pets/${id}`, body),
  remove: (id) => api.delete(`/api/v1/pets/${id}`),   // xóa mềm (deleted_at)
  visits: (id) => api.get(`/api/v1/pets/${id}/visits`),
  medicalHistory: (id, query) => api.get(`/api/v1/pets/${id}/medical-history`, query),
};

export const appointmentsApi = {
  // Công khai (Gateway cho qua không cần token) nên Portal xem được khi chưa đăng nhập
  slots: (date) => api.get(`${APPT}/slots`, { date }, { auth: false }),
  list: (query) => api.get(APPT, query),
  get: (id) => api.get(`${APPT}/${id}`),
  history: (id) => api.get(`${APPT}/${id}/history`),
  create: (body) => api.post(APPT, body),
  confirm: (id) => api.post(`${APPT}/${id}/confirm`),
  cancel: (id, reason) => api.post(`${APPT}/${id}/cancel`, { reason }),
  reschedule: (id, body) => api.post(`${APPT}/${id}/reschedule`, body),
  noShow: (id) => api.post(`${APPT}/${id}/no-show`),
  report: (query) => api.get(`${APPT}/reports/summary`, query),
};

export const visitsApi = {
  checkIn: (body) => api.post(`${VISITS}/check-in`, body),
  // Bảng điều phối: { unassigned, doctors: [{ doctorId, fullName, roomNumber, status, waitingCount, visits }], suggestedDoctorId }
  queue: () => api.get(`${VISITS}/queue`),
  myQueue: () => api.get(`${VISITS}/my-queue`),
  list: (query) => api.get(VISITS, query),
  withDebt: () => api.get(`${VISITS}/with-debt`),
  get: (id) => api.get(`${VISITS}/${id}`),
  history: (id) => api.get(`${VISITS}/${id}/history`),
  assignDoctor: (id, doctorId) => api.post(`${VISITS}/${id}/assign-doctor`, { doctorId }),
  unassignDoctor: (id) => api.post(`${VISITS}/${id}/unassign-doctor`),
  startExam: (id, body) => api.post(`${VISITS}/${id}/start-exam`, body),
  vitalSigns: (id) => api.get(`${VISITS}/${id}/vital-signs`),
  addVitalSigns: (id, body) => api.post(`${VISITS}/${id}/vital-signs`, body),   // mỗi lần đo một dòng (vital_signs)
  updateMedicalRecord: (id, body) => api.put(`${VISITS}/${id}/medical-record`, body),   // hồ sơ khám (medical_records), có version
  paraclinicalOrders: (id, serviceIds, notes) => api.post(`${VISITS}/${id}/paraclinical-orders`, { serviceIds, notes: notes || null }),
  inRoomServices: (id, services) => api.post(`${VISITS}/${id}/in-room-services`, { services }),
  startPhase2: (id) => api.post(`${VISITS}/${id}/start-phase2`),
  closeVisit: (id, body) => api.post(`${VISITS}/${id}/close-visit`, body),
  cancelExam: (id, reason) => api.post(`${VISITS}/${id}/cancel-exam`, { reason }),
  abandon: (id) => api.post(`${VISITS}/${id}/abandon`),
  cancel: (id, reason) => api.post(`${VISITS}/${id}/cancel`, { reason }),
  reopen: (id, reason) => api.post(`${VISITS}/${id}/reopen`, { reason }),
  doctorsStatus: () => api.get(`${CLIN}/doctors/status`),
  myVisits: () => api.get(`${CLIN}/doctors/me/visits`),
  // Hóa đơn dịch vụ (mỗi dịch vụ một hóa đơn, thu một phần được)
  serviceInvoices: (id) => api.get(`${VISITS}/${id}/service-invoices`),
  confirmServicePayment: (id, invoiceIds) => api.post(`${VISITS}/${id}/service-invoices/confirm-payment`, { invoiceIds }),
  discountServiceInvoice: (invoiceId, discountAmount) => api.post(`${CLIN}/service-invoices/${invoiceId}/discount`, { discountAmount }),
  // Màn hình thu ngân 3 mục: EXAM_FEE (chờ nộp phí khám), PARACLINIC (chờ nộp tiền XN / CĐHA), FINAL (đã chốt ca)
  cashierPending: (section) => api.get(`${CLIN}/cashier/pending`, { section }),
  // Đơn thuốc (v4.0: thuộc clinical): lưu CẢ đơn, kê vượt tồn chỉ cảnh báo (stockWarnings), trùng dị ứng chỉ cảnh báo (allergyWarning)
  prescription: (visitId) => api.get(`${VISITS}/${visitId}/prescription`),
  savePrescription: (visitId, body) => api.put(`${VISITS}/${visitId}/prescription`, body),
  printPrescription: (visitId) => api.get(`${VISITS}/${visitId}/prescription/print`),
};

/** Phiếu xếp hàng (SM-07): hàng đợi Bác sĩ / Lab / CĐHA. "Gọi" chỉ báo khách, không đổi thứ tự hay trạng thái. */
export const queueTicketsApi = {
  callNext: (queueType) => api.post(`${CLIN}/queue-tickets/call-next?queueType=${queueType}`),
  call: (ticketId) => api.post(`${CLIN}/queue-tickets/${ticketId}/call`),
  start: (ticketId) => api.post(`${CLIN}/queue-tickets/${ticketId}/start`),
  // Admin: dựng lại hàng đợi trên Redis từ MySQL (sau khi Redis bị xóa / mất dữ liệu); idempotent. Trả { ran, doctorTickets, roomTickets, alreadyIssued }
  rebuild: () => api.post(`${CLIN}/queue-tickets/rebuild`),
};

export const paraclinicApi = {
  labQueue: () => api.get(`${CLIN}/lab-queue`),
  imagingQueue: () => api.get(`${CLIN}/imaging-queue`),
  sampleCollected: (detailId) => api.post(`${CLIN}/service-details/${detailId}/sample-collected`),
  perform: (detailId) => api.post(`${CLIN}/service-details/${detailId}/perform`),
  // reasonType: CUSTOMER (khách tự hủy, không hoàn tiền) | CLINIC (phòng khám không làm được, hoàn tiền nếu đã thu)
  cancel: (detailId, reason, reasonType) => api.post(`${CLIN}/service-details/${detailId}/cancel`, { reason, reasonType: reasonType || null }),
  labForm: (detailId) => api.get(`${CLIN}/service-details/${detailId}/lab-form`),
  saveLabResults: (detailId, results) => api.post(`${CLIN}/lab-results/${detailId}`, { results }),
  saveImagingResult: (detailId, { description, conclusion, files }) => {
    const form = new FormData();
    form.append('description', description);
    form.append('conclusion', conclusion || '');
    files.forEach((file) => form.append('files', file));
    return api.upload(`${CLIN}/imaging-results/${detailId}`, form);
  },
  // URL ký tạm (hạn 1 giờ) của ảnh CĐHA: { url: '/files/imaging/...?exp=..&sig=..', expiresAt }. Ảnh tải thẳng bằng thẻ img, không cần Authorization.
  attachmentUrl: (attachmentId) => api.get(`${CLIN}/imaging-attachments/${attachmentId}/url`),
};

export const pharmacyApi = {
  medicines: (query) => api.get(`${PHARM}/medicines`, query),
  medicine: (id) => api.get(`${PHARM}/medicines/${id}`),
  createMedicine: (body) => api.post(`${PHARM}/medicines`, body),
  updateMedicine: (id, body) => api.put(`${PHARM}/medicines/${id}`, body),
  transactions: (id, query) => api.get(`${PHARM}/medicines/${id}/transactions`, query),
  // Giá bán có lịch sử (đóng dòng đang mở + thêm dòng mới) và tồn kho tách bảng (v4.0)
  medicinePrices: (id) => api.get(`${PHARM}/medicines/${id}/prices`),
  changeMedicinePrice: (id, body) => api.post(`${PHARM}/medicines/${id}/prices`, body),
  importStock: (medicineId, body) => api.post(`${PHARM}/medicines/${medicineId}/import`, body),
  adjustStock: (medicineId, body) => api.post(`${PHARM}/medicines/${medicineId}/adjust`, body),
  categories: (query) => api.get(`${PHARM}/medicine-categories`, query),
};

export const billingApi = {
  // Hóa đơn thuốc NHÁP tự tạo khi Bác sĩ chốt ca (không có API lập hóa đơn); mảng rỗng = chưa chốt ca hoặc đơn chưa tới billing
  medicineInvoicesByVisit: (visitId) => api.get(`${BILL}/medicine-invoices`, { visitId }),
  medicineInvoice: (id) => api.get(`${BILL}/medicine-invoices/${id}`),
  updateMedicineInvoiceItems: (id, body) => api.put(`${BILL}/medicine-invoices/${id}/items`, body),
  cancelMedicineInvoice: (id) => api.post(`${BILL}/medicine-invoices/${id}/cancel`),
  confirmMedicinePayment: (id) => api.post(`${BILL}/medicine-invoices/${id}/confirm-payment`),
};

// Báo cáo (chỉ ADMIN): MỘT nguồn là reporting-service (mô hình đọc dựng từ sự kiện), đã gộp dịch vụ + thuốc và trừ hoàn tiền.
// Tham số ngày là from / to (yyyy-MM-dd, giờ phòng khám); visitsSummary còn nhận date=.
const REPORTS = '/api/v1/reports';
export const reportsApi = {
  revenueDaily: (query) => api.get(`${REPORTS}/revenue/daily`, query),
  revenueByCategory: (query) => api.get(`${REPORTS}/revenue/by-category`, query),
  doctorPerformance: (query) => api.get(`${REPORTS}/doctors/performance`, query),
  visitsSummary: (query) => api.get(`${REPORTS}/visits/summary`, query),
  // Công cụ quản trị (Task 4.5.4): dựng lại báo cáo của `days` ngày gần nhất từ outbox của các service nguồn
  rebuild: (days) => api.post(`${REPORTS}/rebuild`, undefined, { query: { days } }),
};

export const notificationsApi = {
  list: (limit = 50) => api.get('/api/v1/notifications', { limit }),
  markRead: (id) => api.post(`/api/v1/notifications/${id}/read`),
};
