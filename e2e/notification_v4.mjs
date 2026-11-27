// Kiểm thử notification-service v3.0 qua Gateway + RabbitMQ thật: 9 sự kiện → thông báo đúng người, POST /{id}/read, bỏ send-email.
// Chạy: node e2e/notification_v4.mjs   (stack đang chạy, dữ liệu seed). Kiểm tra email chỉ chạy khi Mailpit truy cập được (MAILPIT_URL,
// mặc định http://localhost:8025 — chỉ mở khi dùng docker-compose.dev.yml); chủ nuôi khach_lan phải có email (script tự đặt qua API).
import { BILL, CLIN, NOTI, PHARM, call, check, finish, login as loginSession, run, sleep, step } from './lib/api.mjs';
import { seedIds } from './lib/ids.mjs';

const MAILPIT = process.env.MAILPIT_URL || 'http://localhost:8025';
const T = {};
const login = async (username) => (await loginSession(username)).accessToken;
// Hóa đơn thuốc NHÁP do billing tự tạo từ PrescriptionCreatedEvent sau khi chốt ca (outbox + RabbitMQ, thường dưới 1 giây)
async function draftOf(token, visitId) {
  for (let i = 0; i < 40; i += 1) {
    const res = await call('GET', `${BILL}/medicine-invoices?visitId=${visitId}`, { token });
    if (res.status === 200 && res.data.length) return res.data[0];
    await sleep(500);
  }
  return null;
}
const list = async (token) => (await call('GET', `${NOTI}?limit=100`, { token })).data || [];
// Chờ tới 15 giây cho đến khi người dùng có ≥ n thông báo thuộc type (và chứa `contains` trong JSON nếu có)
async function expectType(name, token, type, { n = 1, contains = '' } = {}) {
  for (let i = 0; i < 30; i += 1) {
    const items = (await list(token)).filter((m) => m.type === type && (!contains || JSON.stringify(m).includes(contains)));
    if (items.length >= n) { check(name, true); return items; }
    await sleep(500);
  }
  check(name, false, `không thấy ${type}`);
  return [];
}

async function main() {
  for (const [key, user] of Object.entries({ admin: 'admin', doc: 'bs_tien', rec: 'letan_hoa', lab: 'ktv_lab_minh', owner: 'khach_lan' })) T[key] = await login(user);
  const ID = await seedIds();
  const AMOX = ID.medicine.MED_AMOX;
  // chủ nuôi cần email để nhận thư
  await call('PUT', '/api/v1/auth/me', { token: T.owner, body: { fullName: 'Hoàng Thị Lan', email: 'lan.e2e@example.com' } });

  step('Gọi số / gọi lại / có kết quả / hoàn tất / bán thuốc / tồn kho thấp');
  const pet = (await call('POST', '/api/v1/pets', { token: T.rec, body: { ownerId: ID.user.khach_lan, speciesId: ID.species.CAT, name: `Noti-${Date.now() % 100000}` } })).data.id;
  const visit = (await call('POST', `${CLIN}/pet-visits/check-in`, { token: T.rec, body: { petId: pet, doctorId: ID.user.bs_tien, isEmergency: false } })).data.id;
  await expectType('chủ nuôi nhận VISIT_CHECKED_IN', T.owner, 'VISIT_CHECKED_IN');
  await expectType('bác sĩ được làm mới hàng đợi riêng (VISIT_CHECKED_IN)', T.doc, 'VISIT_CHECKED_IN');
  await expectType('lễ tân nhận VISIT_CHECKED_IN (kênh vai trò)', T.rec, 'VISIT_CHECKED_IN');
  const fee = (await call('GET', `${CLIN}/pet-visits/${visit}/service-invoices`, { token: T.rec })).data.invoices.find((i) => i.category === 'EXAMINATION');
  const paidFee = await call('POST', `${CLIN}/pet-visits/${visit}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: [fee.id] } });
  let ticketId;   // phiếu phát SAU khi thu tiền (sự kiện + Redis): chờ có thời hạn
  for (let i = 0; i < 60 && !ticketId; i += 1) {
    ticketId = (await call('GET', `${CLIN}/pet-visits/${visit}/service-invoices`, { token: T.rec })).data.tickets.find((t) => t.queueType === 'DOCTOR')?.id;
    if (!ticketId) await sleep(300);
  }
  check('nộp phí khám → phát phiếu DOCTOR (UUID từ Redis)', paidFee.status === 200 && /^[0-9a-f-]{36}$/.test(ticketId || ''), ticketId);
  await expectType('nộp phí khám → chủ nuôi nhận số phiếu (TICKET_ISSUED)', T.owner, 'TICKET_ISSUED', { contains: '"DOCTOR"' });
  await expectType('bác sĩ được làm mới hàng đợi khi có phiếu mới (TICKET_ISSUED)', T.doc, 'TICKET_ISSUED');
  await call('POST', `${CLIN}/queue-tickets/call-next?queueType=DOCTOR`, { token: T.doc });
  await expectType('gọi số → chủ nuôi nhận PATIENT_CALLED (CALLED)', T.owner, 'PATIENT_CALLED', { contains: '"CALLED"' });
  await call('POST', `${CLIN}/queue-tickets/${ticketId}/call`, { token: T.doc });
  await expectType('gọi lại một phiếu theo id → chủ nuôi nhận PATIENT_CALLED (CALLED)', T.owner, 'PATIENT_CALLED', { contains: '"CALLED"' });
  await call('POST', `${CLIN}/pet-visits/${visit}/start-exam`, { token: T.doc, body: { vitalSigns: { weightKg: 4, temperatureC: 38.5 } } });
  await call('POST', `${CLIN}/pet-visits/${visit}/paraclinical-orders`, { token: T.doc, body: { serviceIds: [ID.service.LAB_CBC] } });
  const clsInv = (await call('GET', `${CLIN}/pet-visits/${visit}/service-invoices`, { token: T.rec })).data.invoices.filter((i) => i.category === 'LABORATORY').map((i) => i.id);
  await call('POST', `${CLIN}/pet-visits/${visit}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: clsInv } });
  // phiếu phòng phát SAU khi thu tiền (sự kiện + Redis); thông báo TICKET_ISSUED cũ của các lần chạy trước có thể làm expectType đạt sớm, nên chờ phiếu thật
  for (let i = 0; i < 60; i += 1) {
    const ts = (await call('GET', `${CLIN}/pet-visits/${visit}/service-invoices`, { token: T.rec })).data.tickets;
    if (ts.some((t) => t.queueType === 'LABORATORY')) break;
    await sleep(300);
  }
  await expectType('nộp tiền XN → KTV Lab nhận phiếu mới (TICKET_ISSUED)', T.lab, 'TICKET_ISSUED', { contains: '"LABORATORY"' });
  await call('POST', `${CLIN}/queue-tickets/call-next?queueType=LABORATORY`, { token: T.lab });
  await expectType('Lab gọi số → chủ nuôi nhận PATIENT_CALLED (LAB)', T.owner, 'PATIENT_CALLED', { contains: '"LAB"' });
  await expectType('KTV Lab được làm mới hàng đợi (kênh vai trò)', T.lab, 'PATIENT_CALLED');
  const detail = ((await call('GET', `${CLIN}/lab-queue`, { token: T.lab })).data.find((i) => i.visitId === visit) || {}).serviceDetailId;
  await call('POST', `${CLIN}/service-details/${detail}/cancel`, { token: T.doc, body: { reason: 'e2e' } });
  await expectType('hết CLS → bác sĩ nhận RESULTS_READY', T.doc, 'RESULTS_READY');
  await expectType('chủ nuôi nhận RESULTS_READY', T.owner, 'RESULTS_READY');
  // phiếu ưu tiên 1 phát ngay sau đó qua sự kiện (Redis): chờ có thời hạn trước khi bác sĩ bắt đầu Giai đoạn 2
  for (let i = 0; i < 60; i += 1) {
    const ts = (await call('GET', `${CLIN}/pet-visits/${visit}/service-invoices`, { token: T.rec })).data.tickets;
    if (ts.some((t) => t.queueType === 'DOCTOR' && t.priority === 1)) break;
    await sleep(300);
  }
  await call('POST', `${CLIN}/queue-tickets/call-next?queueType=DOCTOR`, { token: T.doc }); await call('POST', `${CLIN}/pet-visits/${visit}/start-phase2`, { token: T.doc });
  await call('PUT', `${CLIN}/pet-visits/${visit}/prescription`, { token: T.doc, body: { items: [{ medicineId: AMOX, quantity: 10, dosage: '1', frequency: '2', durationDays: 5 }] } });
  await call('POST', `${CLIN}/pet-visits/${visit}/close-visit`, { token: T.doc, body: { finalDiagnosis: 'Viêm da' } });
  const stock = (await call('GET', `${PHARM}/medicines/${AMOX}`, { token: T.admin })).data;
  // đưa tồn về ngưỡng + 10 để lần bán này làm chạm ngưỡng cảnh báo
  await call('POST', `${PHARM}/medicines/${AMOX}/adjust`, { token: T.admin, body: { delta: -(stock.stockQuantity - stock.minStockAlert - 5), reason: 'e2e đưa tồn gần ngưỡng' } });
  const invoice = await draftOf(T.rec, visit);   // nháp tự tạo khi chốt ca
  await call('POST', `${BILL}/medicine-invoices/${invoice.id}/confirm-payment`, { token: T.rec });
  await expectType('thu thuốc → chủ nuôi nhận MEDICINE_INVOICE_PAID (có số tiền)', T.owner, 'MEDICINE_INVOICE_PAID', { contains: '157500' });
  await expectType('tồn chạm ngưỡng → Admin nhận LOW_STOCK', T.admin, 'LOW_STOCK');
  const st = (await call('GET', `${CLIN}/pet-visits/${visit}/service-invoices`, { token: T.rec })).data;
  await call('POST', `${CLIN}/pet-visits/${visit}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: st.invoices.filter((i) => i.paymentStatus === 'PENDING').map((i) => i.id) } });
  await expectType('thu đủ → chủ nuôi nhận VISIT_COMPLETED', T.owner, 'VISIT_COMPLETED');
  await expectType('lễ tân nhận VISIT_COMPLETED', T.rec, 'VISIT_COMPLETED');

  step('Lịch hẹn: đặt → duyệt → vắng mặt (chỉ một thông báo mỗi lần)');
  const day = new Date(Date.now() + 5 * 86400000).toISOString().slice(0, 10);
  const booked = await call('POST', '/api/v1/appointments', { token: T.owner, body: { petId: ID.pet.lu, appointmentDate: day, startTime: '14:00', reason: 'e2e thông báo' } });
  await expectType('đặt lịch → chủ nuôi nhận APPOINTMENT_BOOKED', T.owner, 'APPOINTMENT_BOOKED');
  await expectType('lịch PENDING → lễ tân nhận APPOINTMENT_BOOKED', T.rec, 'APPOINTMENT_BOOKED');
  await call('POST', `/api/v1/appointments/${booked.data.id}/confirm`, { token: T.rec });
  await expectType('duyệt → chủ nuôi nhận APPOINTMENT_UPDATED', T.owner, 'APPOINTMENT_UPDATED');
  const noShow = await call('POST', `/api/v1/appointments/${booked.data.id}/no-show`, { token: T.rec });
  check('đánh dấu vắng mặt sớm bị từ chối (APPT_1206: chưa quá giờ hẹn + 30 phút)', noShow.status === 400 && noShow.code === 'APPT_1206');

  step('POST /notifications/{id}/read và API đã bỏ');
  const mine = await list(T.owner);
  const first = mine[0];
  check('thông báo mới nhất chưa đọc', first && first.read === false);
  check('đánh dấu đã đọc → 200', (await call('POST', `${NOTI}/${first.id}/read`, { token: T.owner })).status === 200);
  check('danh sách: chỉ thông báo đó read=true', (await list(T.owner)).filter((m) => m.read).length === 1 && (await list(T.owner))[0].read === true);
  check('đánh dấu lại (idempotent) → 200', (await call('POST', `${NOTI}/${first.id}/read`, { token: T.owner })).status === 200);
  check('trạng thái đọc riêng từng người (lễ tân không bị ảnh hưởng)', (await list(T.rec)).every((m) => m.read === false));
  check('thông báo của người khác / id lạ → 404', (await call('POST', `${NOTI}/${first.id}/read`, { token: T.lab })).status === 404 && (await call('POST', `${NOTI}/00000000-0000-0000-0000-000000000000/read`, { token: T.owner })).status === 404);
  check('POST /notifications/send-email đã bỏ (404/405)', [404, 405].includes((await call('POST', `${NOTI}/send-email`, { token: T.admin, body: { to: 'a@b.c', subject: 'x', content: 'y' } })).status));
  check('không token → 401', (await call('GET', NOTI)).status === 401);

  step('Tài khoản: Bác sĩ bị khóa → Lễ tân được báo phân công lại (UserStatusChangedEvent qua outbox)');
  const docPhone = `096${String(Date.now()).slice(-7)}`;
  const newDoc = await call('POST', '/api/v1/staff', { token: T.admin, body: { roleCodes: ['VETERINARIAN'], fullName: `BS Noti ${docPhone}`, phoneNumber: docPhone, password: 'Doctor@123', roomNumber: `N${docPhone.slice(-4)}`,
    vetProfile: { licenseNumber: `NT-${docPhone}`, licenseIssuedDate: '2020-01-01', licenseIssuedBy: 'Cục Thú y', academicDegree: 'Bác sĩ thú y' } } });
  check('tạo Bác sĩ thử để khóa', newDoc.status === 201, JSON.stringify(newDoc.data));
  await call('POST', `/api/v1/users/${newDoc.data.id}/lock`, { token: T.admin });   // để Bác sĩ thử ở trạng thái khóa: không đổi sức chứa đặt lịch
  await expectType('khóa Bác sĩ → lễ tân nhận USER_STATUS_CHANGED', T.rec, 'USER_STATUS_CHANGED', { contains: newDoc.data.id });

  step('Email (Mailpit, nếu truy cập được)');
  const mail = await fetch(`${MAILPIT}/api/v1/messages`).then((r) => r.json()).catch(() => null);
  if (!mail) console.log(`  (bỏ qua: không truy cập được Mailpit tại ${MAILPIT}; mở bằng docker-compose.dev.yml để kiểm tra email)`);
  else {
    const subjects = mail.messages.map((m) => Buffer.from(m.Subject, 'latin1').toString('utf8') + ' | ' + m.Subject);
    const has = (needle) => mail.messages.some((m) => m.Subject.includes(needle) || Buffer.from(m.Subject, 'latin1').toString('utf8').includes(needle));
    check('có email xác nhận đặt lịch', has('Xác nhận đặt lịch khám'));
    check('có email phiếu thu thuốc', has('Phiếu thu thuốc'));
    check('có email phiếu thu điện tử khi hoàn tất ca', has('Phiếu thu điện tử'));
    void subjects;
  }

  finish();
}
run(main);
