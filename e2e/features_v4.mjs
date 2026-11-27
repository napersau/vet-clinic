// Kiểm thử các chức năng NGOÀI luồng khám chính (v3.0) qua Gateway: tài khoản, danh mục, thú cưng, lịch hẹn, quản lý đợt khám,
// báo cáo, thuốc, WebSocket/STOMP. Bổ sung cho backend_v4.mjs / notification_v4.mjs / fe_smoke_v4.mjs (không lặp lại nội dung của chúng).
// Chạy: node e2e/features_v4.mjs   (stack đang chạy + dữ liệu seed; chạy SAU backend_v4.mjs vì script này tạo thêm bác sĩ -> đổi sức chứa khung giờ)
// CẢNH BÁO: tạo dữ liệu thật, không dọn lại.
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import { API, APPT, AUTH, BILL, CLIN, PHARM, ROOT, RUN, brief, call, check, finish, login, loginRes, redis, run, sleep, sql, step } from './lib/api.mjs';
import { seedIds, uuidSql } from './lib/ids.mjs';

const WebSocket = createRequire(path.join(ROOT, 'fe', 'package.json'))('ws');
const WS_URL = process.env.WS_URL || API.replace(/^http/, 'ws') + '/ws';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
let ID;   // id seed tra theo mã ổn định (lib/ids.mjs)
const phone = (n) => `09${RUN}${String(n).padStart(2, '0')}`.slice(0, 10);
const clinicNow = () => new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Ho_Chi_Minh' }));
const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const hhmm = (d) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:00`;
const dayOffset = (n) => { const d = clinicNow(); d.setDate(d.getDate() + n); return iso(d); };

const T = {};
/** Chèn thẳng một lịch hẹn vào CSDL (để dựng tình huống quá giờ / sát giờ): id UUID, owner + snapshot lấy từ chính hồ sơ thú cưng. */
const insertAppointment = (code, petId, date, time, status) => {
  const id = randomUUID();
  sql(`INSERT INTO pet_clinic_appointment_db.appointments (id,appointment_code,pet_id,owner_id,pet_name_snapshot,owner_name_snapshot,owner_phone_snapshot,appointment_date,start_time,reason,booking_channel,status) `
    + `SELECT ${uuidSql(id)},'${code}',p.id,p.owner_id,p.name,p.owner_name_snapshot,p.owner_phone_snapshot,'${date}','${time}','e2e','PHONE','${status}' FROM pet_clinic_clinical_db.pets p WHERE p.id=${uuidSql(petId)}`);
  return id;
};
const newPet = async (name, owner = ID.user.khach_lan, species = ID.species.DOG) => {
  const res = await call('POST', '/api/v1/pets', { token: T.rec, body: { ownerId: owner, speciesId: species, name: `${name}-${RUN}` } });
  if (res.status !== 201) throw new Error(`newPet ${name}: ${brief(res)}`);
  return res.data.id;
};
const checkIn = (petId, doctorId, extra = {}) => call('POST', `${CLIN}/pet-visits/check-in`, { token: T.rec, body: { petId, doctorId, isEmergency: false, reasonForVisit: 'e2e', ...extra } });
const visitOf = async (id) => (await call('GET', `${CLIN}/pet-visits/${id}`, { token: T.rec })).data;
const docToken = (id) => (id === ID.user.bs_tien ? T.doc2 : id === ID.user.bs_an ? T.doc3 : T.doc4);
const callNext = (token) => call('POST', `${CLIN}/queue-tickets/call-next?queueType=DOCTOR`, { token });
const callTicket = (token, ticketId) => call('POST', `${CLIN}/queue-tickets/${ticketId}/call`, { token });
const startTicket = (token, ticketId) => call('POST', `${CLIN}/queue-tickets/${ticketId}/start`, { token });
const myQueue = async (token) => (await call('GET', `${CLIN}/pet-visits/my-queue`, { token })).data;
// Thu trước (17/11/2026): phí khám thu xong mới có phiếu Bác sĩ; tiền XN / CĐHA thu xong mới có phiếu phòng
async function payWhere(v, filter) {
  const st = (await call('GET', `${CLIN}/pet-visits/${v}/service-invoices`, { token: T.rec })).data;
  return call('POST', `${CLIN}/pet-visits/${v}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: st.invoices.filter((i) => i.paymentStatus === 'PENDING' && filter(i)).map((i) => i.id) } });
}
// Từ v4.0 phiếu xếp hàng phát SAU khi thu tiền / tiếp đón cấp cứu (sự kiện qua outbox + RabbitMQ + Redis, thường dưới 1 giây): chờ có thời hạn, không đọc ngay
async function waitTickets(visitId, predicate, ms = 20000) {
  for (let t = 0; t < ms; t += 300) {
    const tickets = (await call('GET', `${CLIN}/pet-visits/${visitId}/service-invoices`, { token: T.rec })).data?.tickets || [];
    if (predicate(tickets)) return tickets;
    await sleep(300);
  }
  return [];
}
async function payExam(v) {
  const res = await payWhere(v, (i) => i.category === 'EXAMINATION');
  if (res.status === 200) await waitTickets(v, (ts) => ts.some((t) => t.queueType === 'DOCTOR'));
  return res;
}
async function payCls(v) {
  const types = [...new Set((await call('GET', `${CLIN}/pet-visits/${v}/service-invoices`, { token: T.rec })).data.invoices
    .filter((i) => i.paymentStatus === 'PENDING' && (i.category === 'LABORATORY' || i.category === 'IMAGING')).map((i) => i.category))];
  const res = await payWhere(v, (i) => i.category === 'LABORATORY' || i.category === 'IMAGING');
  if (res.status === 200) await waitTickets(v, (ts) => types.every((q) => ts.some((t) => t.queueType === q)));
  return res;
}
async function roomRows(kind, v) {   // kind: 'lab' | 'imaging'
  const items = (await call('GET', `${CLIN}/${kind}-queue`, { token: kind === 'lab' ? T.lab : T.img })).data;
  return items.filter((i) => i.visitId === v);
}
async function startRoom(kind, v) {   // nộp tiền CLS (nếu chưa) rồi KTV bắt đầu phiếu; trả về các dòng dịch vụ của phiếu
  const rows = await roomRows(kind, v);
  if (rows[0]) await startTicket(kind === 'lab' ? T.lab : T.img, rows[0].ticketId);
  return rows;
}
const startExam = (token, id, vitals = true) => call('POST', `${CLIN}/pet-visits/${id}/start-exam`, { token, body: vitals ? { vitalSigns: { weightKg: 6, temperatureC: 38.6 } } : {} });
const closeVisit = (token, id, diagnosis = 'Chẩn đoán e2e') => call('POST', `${CLIN}/pet-visits/${id}/close-visit`, { token, body: { finalDiagnosis: diagnosis, treatmentPlan: 'Điều trị', doctorAdvice: 'Theo dõi' } });
// Hóa đơn thuốc NHÁP do billing tự tạo từ PrescriptionCreatedEvent sau khi chốt ca (outbox + RabbitMQ, thường dưới 1 giây)
async function draftOf(token, visitId) {
  for (let i = 0; i < 40; i += 1) {
    const res = await call('GET', `${BILL}/medicine-invoices?visitId=${visitId}`, { token });
    if (res.status === 200 && res.data.length) return res.data[0];
    await sleep(500);
  }
  return null;
}
async function inProgress(petName, doctorId = ID.user.bs_mai) {
  const pet = await newPet(petName);
  const v = (await checkIn(pet, doctorId)).data.id;
  await payExam(v);
  await callNext(docToken(doctorId));
  const st = await startExam(docToken(doctorId), v);
  if (st.status !== 200) throw new Error(`startExam: ${brief(st)}`);
  return { pet, v, token: docToken(doctorId) };
}
const statementOf = async (v) => (await call('GET', `${CLIN}/pet-visits/${v}/service-invoices`, { token: T.rec })).data;
const imgForm = (bytes = PNG, type = 'image/png', name = 'a.png') => { const f = new FormData(); f.append('description', 'mô tả'); f.append('conclusion', 'kết luận'); f.append('files', new Blob([bytes], { type }), name); return f; };

// ---------- STOMP tối thiểu ----------
function stomp(token, { subscribe = [], send = false } = {}) {
  const NUL = '\u0000';
  const frames = []; let ws; const waiters = [];
  const parse = (raw) => String(raw).split(NUL).map((p) => p.replace(/^\n+/, '')).filter((p) => p.trim()).map((p) => { const i = p.indexOf('\n\n'); const head = (i === -1 ? p : p.slice(0, i)).split('\n'); const headers = {}; head.slice(1).forEach((l) => { const c = l.indexOf(':'); if (c > 0) headers[l.slice(0, c)] = l.slice(c + 1); }); return { command: head[0].trim(), headers, body: i === -1 ? '' : p.slice(i + 2) }; });
  const fr = (cmd, headers = {}, body = '') => `${cmd}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}${NUL}`;
  return new Promise((resolve) => {
    ws = new WebSocket(WS_URL);
    const api = {
      frames, close: () => { try { ws.close(); } catch { /* đã đóng */ } },
      waitFor: async (pred, ms = 12000) => { const end = Date.now() + ms; while (Date.now() < end) { const f = frames.find(pred); if (f) return f; await sleep(150); } return null; },
    };
    ws.on('open', () => ws.send(fr('CONNECT', { 'accept-version': '1.2', host: 'localhost', 'heart-beat': '0,0', ...(token ? { Authorization: `Bearer ${token}` } : {}) })));
    ws.on('message', (raw) => parse(raw).forEach((f) => {
      frames.push(f);
      if (f.command === 'CONNECTED') { subscribe.forEach((dest, i) => ws.send(fr('SUBSCRIBE', { id: `s${i}`, destination: dest }))); if (send) ws.send(fr('SEND', { destination: '/topic/role/ADMIN' }, '{"x":1}')); resolve(api); }
    }));
    ws.on('error', () => resolve(api));
    ws.on('close', () => resolve(api));
    setTimeout(() => resolve(api), 6000);
  });
}

async function main() {
  const who = { admin: 'admin', doc2: 'bs_tien', doc3: 'bs_an', doc4: 'bs_mai', rec: 'letan_hoa', lab: 'ktv_lab_minh', img: 'ktv_cdha_dung', owner: 'khach_lan', owner2: 'khach_nam' };
  for (const [k, u] of Object.entries(who)) T[k] = (await login(u)).accessToken;
  ID = await seedIds();
  const DOC3 = ID.user.bs_an; const DOC4 = ID.user.bs_mai; const OWNER = ID.user.khach_lan; const OWNER2 = ID.user.khach_nam;
  const S = ID.service; const M = ID.medicine;
  // Chạy lại cần dọn đợt khám dở dang của bác sĩ bs_tien / bs_an / bs_mai. Việc này HỦY ca thật đang có trong CSDL nên chỉ làm khi đặt E2E_CLEAN=1
  // (chỉ dùng trên CSDL thử; trên CSDL đang demo thủ công thì reset bằng `docker compose down -v` thay vì chạy lại).
  if (process.env.E2E_CLEAN === '1') {
    sql(`UPDATE pet_clinic_clinical_db.pet_visits SET status='CANCELLED' WHERE doctor_id IN (${[ID.user.bs_tien, DOC3, DOC4].map(uuidSql).join(',')}) AND status IN ('WAITING','IN_PROGRESS','RESULTS_READY','WAITING_RESULTS')`);
  } else {
    console.log('  (không dọn ca dở dang; nếu bác sĩ bs_tien / bs_an / bs_mai còn ca đang khám, chạy lại với E2E_CLEAN=1 hoặc reset CSDL)');
  }

  // =====================================================================================================================
  step('A. Tài khoản: tự đăng ký, hồ sơ, đổi mật khẩu, tạo / khóa / đổi vai trò / đặt lại mật khẩu, chặn dò mật khẩu');
  const regPhone = phone(1);
  const reg = await call('POST', `${AUTH}/register`, { body: { password: 'Owner@1234', fullName: `Chu E2E ${RUN}`, phoneNumber: regPhone, email: `e2e${RUN}@example.com` } });
  check('tự đăng ký chủ nuôi (luôn PET_OWNER, username = SĐT)', [200, 201].includes(reg.status) && (reg.data?.user?.role || reg.data?.role) !== 'ADMIN', brief(reg));
  check('đăng ký trùng SĐT → 409 AUTH_1102', (await call('POST', `${AUTH}/register`, { body: { password: 'Owner@1234', fullName: 'Trùng', phoneNumber: regPhone } })).code === 'AUTH_1102');
  check('mật khẩu yếu bị từ chối (400 SYS_1001)', (await call('POST', `${AUTH}/register`, { body: { password: 'abc', fullName: 'Yếu', phoneNumber: phone(2) } })).code === 'SYS_1001');
  check('đăng nhập sai mật khẩu → AUTH_1103', (await loginRes(regPhone, 'Sai@12345')).code === 'AUTH_1103');
  let me = await login(regPhone, 'Owner@1234');
  const ownerTok = me.accessToken;
  check('GET /me trả đúng hồ sơ', (await call('GET', `${AUTH}/me`, { token: ownerTok })).data?.fullName === `Chu E2E ${RUN}`);
  check('PUT /me đổi họ tên + địa chỉ', (await call('PUT', `${AUTH}/me`, { token: ownerTok, body: { fullName: `Chu E2E đổi ${RUN}`, address: 'Hà Nội' } })).data?.address === 'Hà Nội');
  check('đổi mật khẩu: sai mật khẩu hiện tại → AUTH_1111', (await call('POST', `${AUTH}/change-password`, { token: ownerTok, body: { currentPassword: 'Sai@12345', newPassword: 'Owner@5678' } })).code === 'AUTH_1111');
  check('đổi mật khẩu thành công', (await call('POST', `${AUTH}/change-password`, { token: ownerTok, body: { currentPassword: 'Owner@1234', newPassword: 'Owner@5678' } })).status === 200);
  check('refresh token cũ bị thu hồi sau khi đổi mật khẩu', (await call('POST', `${AUTH}/refresh`, { body: { refreshToken: me.refreshToken } })).status === 401);
  check('đăng nhập bằng mật khẩu mới', (await loginRes(regPhone, 'Owner@5678')).status === 200);

  const STAFF = '/api/v1/staff'; const USERS = '/api/v1/users';
  const vetProfile = (license) => ({ licenseNumber: license, licenseIssuedDate: '2020-01-01', licenseIssuedBy: 'Cục Thú y', licenseExpiryDate: '2032-01-01', academicDegree: 'Bác sĩ thú y', specialty: 'Nội khoa', careerStartYear: 2018 });
  const docPhone = phone(3); const docRoom = `E${RUN.slice(-3)}`;
  const staffBody = (extra) => ({ roleCodes: ['VETERINARIAN'], fullName: `BS E2E ${RUN}`, phoneNumber: docPhone, password: 'Doctor@123', roomNumber: docRoom, ...extra });
  const noProfile = await call('POST', STAFF, { token: T.admin, body: staffBody({ vetProfile: undefined }) });
  check('Bác sĩ thiếu hồ sơ chuyên môn → 400 AUTH_1108', noProfile.status === 400 && noProfile.code === 'AUTH_1108', brief(noProfile));
  const createdDoc = await call('POST', STAFF, { token: T.admin, body: staffBody({ vetProfile: vetProfile(`CC-${RUN}`) }) });
  check('Admin tạo bác sĩ có số phòng + hồ sơ chuyên môn (1 giao dịch)', createdDoc.status === 201 && createdDoc.data?.vetProfile?.licenseNumber === `CC-${RUN}` && /^NV\d+$/.test(createdDoc.data?.employeeCode), brief(createdDoc));
  const docId = createdDoc.data?.id;
  check('trùng số phòng → 409', (await call('POST', STAFF, { token: T.admin, body: staffBody({ phoneNumber: phone(4), roomNumber: docRoom, vetProfile: vetProfile(`CC2-${RUN}`) }) })).status === 409);
  check('trùng số chứng chỉ hành nghề → 400', (await call('POST', STAFF, { token: T.admin, body: staffBody({ phoneNumber: phone(4), roomNumber: `F${RUN.slice(-3)}`, vetProfile: vetProfile(`CC-${RUN}`) }) })).status === 400);
  check('Lễ tân tạo chủ nuôi được', [200, 201].includes((await call('POST', `${AUTH}/users`, { token: T.rec, body: { roleCode: 'PET_OWNER', fullName: `Khách quầy ${RUN}`, phoneNumber: phone(5), password: 'Owner@1234' } })).status));
  check('Lễ tân KHÔNG tạo được nhân sự (403)', (await call('POST', STAFF, { token: T.rec, body: staffBody({ phoneNumber: phone(6), roomNumber: 'Z99', vetProfile: vetProfile(`CC3-${RUN}`) }) })).status === 403);
  check('đường tạo khách hàng từ chối vai trò nhân sự', (await call('POST', `${AUTH}/users`, { token: T.admin, body: { roleCode: 'VETERINARIAN', fullName: 'X', phoneNumber: phone(6), password: 'Doctor@123' } })).status === 400);
  check('chủ nuôi không xem được danh sách người dùng (403)', (await call('GET', `${AUTH}/users`, { token: T.owner })).status === 403);
  check('chủ nuôi không xem được danh sách nhân sự (403)', (await call('GET', STAFF, { token: T.owner })).status === 403);
  const found = await call('GET', `${STAFF}?keyword=${docPhone}`, { token: T.admin });
  check('tìm nhân sự theo SĐT', found.data?.items?.some((u) => u.id === docId));
  check('lọc nhân sự theo vai trò VETERINARIAN', (await call('GET', `${STAFF}?role=VETERINARIAN&size=50`, { token: T.admin })).data?.items?.every((u) => u.roles.includes('VETERINARIAN')));
  check('GET /staff/{id} trả hồ sơ nhân sự đầy đủ', (await call('GET', `${STAFF}/${docId}`, { token: T.admin })).data?.vetProfile?.specialty === 'Nội khoa');
  check('GET /roles trả 6 vai trò', (await call('GET', '/api/v1/roles', { token: T.admin })).data?.length === 6);
  check('GET /staff/doctors có bác sĩ mới', (await call('GET', `${STAFF}/doctors`, { token: T.rec })).data?.some((u) => u.id === docId));
  check('Admin sửa hồ sơ chuyên môn', (await call('PUT', `${STAFF}/${docId}/vet-profile`, { token: T.admin, body: { ...vetProfile(`CC-${RUN}`), specialty: 'Ngoại khoa' } })).data?.vetProfile?.specialty === 'Ngoại khoa');
  check('Admin sửa hồ sơ nhân sự (chức danh)', (await call('PUT', `${STAFF}/${docId}`, { token: T.admin, body: { positionTitle: 'Trưởng khoa E2E' } })).data?.positionTitle === 'Trưởng khoa E2E');
  check('Admin sửa hồ sơ cá nhân bác sĩ', (await call('PUT', `${USERS}/${docId}/profile`, { token: T.admin, body: { fullName: `BS E2E sửa ${RUN}` } })).data?.fullName === `BS E2E sửa ${RUN}`);
  check('Lễ tân KHÔNG sửa được hồ sơ nhân sự (403)', (await call('PUT', `${USERS}/${docId}/profile`, { token: T.rec, body: { address: 'Hà Nội' } })).status === 403);
  const docSession = await login(docPhone, 'Doctor@123');
  check('khóa tài khoản → đăng nhập bị từ chối AUTH_1107', (await call('POST', `${USERS}/${docId}/lock`, { token: T.admin })).status === 200 && (await loginRes(docPhone, 'Doctor@123')).code === 'AUTH_1107');
  check('khóa tài khoản thu hồi mọi refresh token', (await call('POST', `${AUTH}/refresh`, { body: { refreshToken: docSession.refreshToken } })).status === 401);
  check('bác sĩ bị khóa không còn trong danh sách bác sĩ hoạt động', !(await call('GET', `${STAFF}/doctors`, { token: T.rec })).data?.some((u) => u.id === docId));
  check('mở khóa → đăng nhập lại được', (await call('POST', `${USERS}/${docId}/unlock`, { token: T.admin })).status === 200 && (await loginRes(docPhone, 'Doctor@123')).status === 200);
  check('Admin không tự khóa chính mình', (await call('POST', `${USERS}/${ID.user.admin}/lock`, { token: T.admin })).status >= 400);
  check('ngừng hoạt động là xóa mềm: dòng còn, đăng nhập bị từ chối', (await call('POST', `${USERS}/${docId}/deactivate`, { token: T.admin })).data?.status === 'INACTIVE' && (await loginRes(docPhone, 'Doctor@123')).code === 'AUTH_1107' && Number(sql(`SELECT COUNT(*) FROM pet_clinic_auth_db.users WHERE id = ${uuidSql(docId)}`)) === 1);
  check('kích hoạt lại', (await call('POST', `${USERS}/${docId}/unlock`, { token: T.admin })).data?.status === 'ACTIVE');
  check('bác sĩ kiêm quản trị viên (nhiều vai trò) đăng nhập mang cả hai', (await call('PUT', `${USERS}/${docId}/roles`, { token: T.admin, body: { roleCodes: ['VETERINARIAN', 'ADMIN'] } })).data?.roles?.length === 2 && JSON.stringify(await loginRes(docPhone, 'Doctor@123')).includes('ADMIN'));
  check('đổi vai trò bác sĩ → lễ tân (phòng bị xóa)', (await call('PUT', `${USERS}/${docId}/roles`, { token: T.admin, body: { roleCodes: ['RECEPTIONIST'] } })).data?.role === 'RECEPTIONIST');
  check('lễ tân → bác sĩ khi đã có hồ sơ chuyên môn', (await call('PUT', `${USERS}/${docId}/roles`, { token: T.admin, body: { roleCodes: ['VETERINARIAN'] } })).data?.role === 'VETERINARIAN');
  check('trả tài khoản về Lễ tân (như trạng thái cũ, không tính vào sức chứa đặt lịch)', (await call('PUT', `${USERS}/${docId}/roles`, { token: T.admin, body: { roleCodes: ['RECEPTIONIST'] } })).data?.role === 'RECEPTIONIST');
  const eventCount = (type) => Number(sql(`SELECT COUNT(*) FROM pet_clinic_auth_db.outbox_events WHERE aggregate_id = UNHEX(REPLACE('${docId}','-','')) AND event_type = '${type}'`));
  check('outbox: UserStatusChangedEvent khi khóa / mở khóa / ngừng / kích hoạt (4 dòng)', eventCount('UserStatusChangedEvent') === 4, String(eventCount('UserStatusChangedEvent')));
  check('outbox: UserProfileUpdatedEvent khi tạo, đổi họ tên, xóa phòng (3 dòng)', eventCount('UserProfileUpdatedEvent') === 3, String(eventCount('UserProfileUpdatedEvent')));
  check('Admin đặt lại mật khẩu nhân sự', (await call('POST', `${AUTH}/users/${docId}/reset-password`, { token: T.admin, body: { newPassword: 'Reset@1234' } })).status === 200 && (await loginRes(docPhone, 'Reset@1234')).status === 200);
  check('Lễ tân đặt lại mật khẩu cho chủ nuôi (UC tại quầy)', (await call('POST', `${AUTH}/users/${(await call('GET', `${AUTH}/users?keyword=${regPhone}`, { token: T.rec })).data.items[0].id}/reset-password`, { token: T.rec, body: { newPassword: 'Owner@9999' } })).status === 200);
  check('Lễ tân KHÔNG đặt lại mật khẩu nhân sự (403)', (await call('POST', `${AUTH}/users/${docId}/reset-password`, { token: T.rec, body: { newPassword: 'Reset@1234' } })).status === 403);
  let lock = null; for (let i = 1; i <= 6; i += 1) lock = await loginRes(`khong-co-${RUN}`, 'Sai@12345');
  check('sai 5 lần liên tiếp → lần thứ 6 bị chặn 429 AUTH_1112', lock.status === 429 && lock.code === 'AUTH_1112', brief(lock));

  // =====================================================================================================================
  step('B. Danh mục: loài, dịch vụ (VAT), chỉ số xét nghiệm + khoảng tham chiếu + gói, đánh giá LOW/NORMAL/HIGH');
  const sp = await call('POST', '/api/v1/species', { token: T.admin, body: { code: `SP${RUN}`, name: `Loài ${RUN}` } });
  check('Admin thêm loài', [200, 201].includes(sp.status), brief(sp));
  check('trùng mã loài → 409', (await call('POST', '/api/v1/species', { token: T.admin, body: { code: `SP${RUN}`, name: 'Trùng' } })).status === 409);
  check('sửa loài', (await call('PUT', `/api/v1/species/${sp.data.id}`, { token: T.admin, body: { code: `SP${RUN}`, name: `Loài sửa ${RUN}` } })).data?.name === `Loài sửa ${RUN}`);
  check('lễ tân không thêm loài (403)', (await call('POST', '/api/v1/species', { token: T.rec, body: { code: `X${RUN}`, name: 'x' } })).status === 403);
  const CAT = '/api/v1/catalog';
  const svc = await call('POST', `${CAT}/services`, { token: T.admin, body: { code: `SV${RUN}`, name: `Thủ thuật ${RUN}`, categoryCode: 'PROCEDURE', price: 123000, vatRate: 0.08, description: 'e2e' } });
  check('Admin thêm dịch vụ có giá + VAT 8% (service_prices)', svc.status === 201 && Number(svc.data.vatRate) === 0.08 && Number(svc.data.price) === 123000 && svc.data.category === 'PROCEDURE' && svc.data.queueType === null, brief(svc));
  await sleep(1100);   // mốc hiệu lực tính theo giây: giá mới phải sau giây bắt đầu của giá đang mở
  check('đổi giá dịch vụ (đóng dòng cũ + thêm dòng mới)', (await call('POST', `${CAT}/services/${svc.data.id}/prices`, { token: T.admin, body: { price: 150000, vatRate: 0.08, reason: 'e2e' } })).status === 201 && Number((await call('GET', `${CAT}/services/${svc.data.id}`, { token: T.admin })).data?.price) === 150000 && (await call('GET', `${CAT}/services/${svc.data.id}/prices`, { token: T.admin })).data?.length === 2);
  check('giá hiệu lực trước dòng đang mở → 400 BILL_1410', (await call('POST', `${CAT}/services/${svc.data.id}/prices`, { token: T.admin, body: { price: 1, vatRate: 0, effectiveFrom: '2020-01-01T00:00:00Z' } })).code === 'BILL_1410');
  check('lễ tân không sửa dịch vụ / đổi giá (403)', (await call('PUT', `${CAT}/services/${svc.data.id}`, { token: T.rec, body: { name: 'x' } })).status === 403 && (await call('POST', `${CAT}/services/${svc.data.id}/prices`, { token: T.rec, body: { price: 1, vatRate: 0 } })).status === 403);
  const catCode = `NH${RUN}`;
  const newCat = await call('POST', `${CAT}/service-categories`, { token: T.admin, body: { code: catCode, name: `Nhóm e2e ${RUN}`, queueType: '', requiresPrepayment: false } });
  check('Admin thêm nhóm dịch vụ làm tại phòng; sửa chính sách thu trước', newCat.status === 201 && newCat.data.queueType === null && (await call('PUT', `${CAT}/service-categories/${newCat.data.id}`, { token: T.admin, body: { code: catCode, name: `Nhóm e2e ${RUN}`, requiresPrepayment: true } })).data?.requiresPrepayment === true);
  // chỉ số xét nghiệm + gói riêng
  const mkItem = (code, name) => call('POST', `${CLIN}/lab-test-items`, { token: T.admin, body: { code: `${code}${RUN}`, name, unit: 'u', ranges: [{ speciesId: ID.species.DOG, minValue: 10, maxValue: 20 }] } });
  const itemLow = await mkItem('LOW', 'Chỉ số thấp'); const itemNorm = await mkItem('NRM', 'Chỉ số bình thường'); const itemHigh = await mkItem('HGH', 'Chỉ số cao');
  check('thêm 3 chỉ số xét nghiệm kèm khoảng tham chiếu loài Chó', [itemLow, itemNorm, itemHigh].every((r) => [200, 201].includes(r.status)), brief(itemLow));
  check('sửa chỉ số + lưu lại khoảng tham chiếu', (await call('PUT', `${CLIN}/lab-test-items/${itemLow.data.id}`, { token: T.admin, body: { name: 'Chỉ số thấp (sửa)', unit: 'u' } })).status === 200 && (await call('PUT', `${CLIN}/lab-test-items/${itemLow.data.id}/ranges/${ID.species.DOG}`, { token: T.admin, body: { minValue: 10, maxValue: 20 } })).status === 200);
  const labSvc = await call('POST', `${CAT}/services`, { token: T.admin, body: { code: `LB${RUN}`, name: `Gói XN e2e ${RUN}`, categoryCode: 'LABORATORY', price: 90000, vatRate: 0 } });
  check('gói xét nghiệm gồm 3 chỉ số', (await call('PUT', `${CLIN}/services/${labSvc.data.id}/lab-test-items`, { token: T.admin, body: { labTestItemIds: [itemLow.data.id, itemNorm.data.id, itemHigh.data.id] } })).status === 200 && (await call('GET', `${CLIN}/services/${labSvc.data.id}/lab-test-items`, { token: T.admin })).data?.length === 3);
  const lv = await inProgress('XN', DOC4);
  check('chỉ định gói xét nghiệm mới (CLS) thành công', (await call('POST', `${CLIN}/pet-visits/${lv.v}/paraclinical-orders`, { token: lv.token, body: { serviceIds: [labSvc.data.id] } })).data?.status === 'WAITING_RESULTS');
  // EX-20 (phần dịch vụ): đổi giá SAU khi chỉ định → hóa đơn đã lập giữ giá cũ, chỉ định mới lấy giá mới
  const labInvoice = async () => ((await call('GET', `${CLIN}/pet-visits/${lv.v}/service-invoices`, { token: T.rec })).data.invoices || []).find((i) => i.category === 'LABORATORY');
  check('hóa đơn gói XN chụp giá lúc chỉ định (90.000)', Number((await labInvoice())?.unitPrice) === 90000);
  await sleep(1100);
  await call('POST', `${CAT}/services/${labSvc.data.id}/prices`, { token: T.admin, body: { price: 95000, vatRate: 0, reason: 'e2e EX-20' } });
  check('EX-20: đổi giá sau khi chỉ định → hóa đơn đã lập vẫn 90.000, danh mục báo 95.000', Number((await labInvoice())?.unitPrice) === 90000 && Number((await call('GET', `${CAT}/services/${labSvc.data.id}`, { token: T.admin })).data?.price) === 95000);
  await payCls(lv.v);
  const labDetail = ((await startRoom('lab', lv.v))[0] || {}).serviceDetailId;
  const form = (await call('GET', `${CLIN}/service-details/${labDetail}/lab-form`, { token: T.lab })).data;
  check('lab-form trả đủ 3 chỉ số kèm khoảng tham chiếu của loài Chó', form?.length === 3 && form.every((f) => f.ranges?.some((r) => Number(r.minValue) === 10 && Number(r.maxValue) === 20)), JSON.stringify(form).slice(0, 200));
  const partial = await call('POST', `${CLIN}/lab-results/${labDetail}`, { token: T.lab, body: { results: [{ labTestItemId: itemLow.data.id, resultValue: 5 }] } });
  check('nhập thiếu chỉ số của gói → 400 SYS_1001', partial.status === 400 && partial.code === 'SYS_1001', brief(partial));
  const full = await call('POST', `${CLIN}/lab-results/${labDetail}`, { token: T.lab, body: { results: [{ labTestItemId: itemLow.data.id, resultValue: 5 }, { labTestItemId: itemNorm.data.id, resultValue: 15 }, { labTestItemId: itemHigh.data.id, resultValue: 25 }] } });
  check('nhập đủ chỉ số → 200', full.status === 200, brief(full));
  const results = (await call('GET', `${CLIN}/lab-results/${labDetail}`, { token: T.doc4 })).data || [];
  const evalOf = (id) => results.find((r) => r.labTestItemId === id)?.evaluation;
  check('đánh giá tính khi hiển thị: 5 → LOW, 15 → NORMAL, 25 → HIGH', evalOf(itemLow.data.id) === 'LOW' && evalOf(itemNorm.data.id) === 'NORMAL' && evalOf(itemHigh.data.id) === 'HIGH', JSON.stringify(results).slice(0, 300));
  check('khoảng tham chiếu được chụp (snapshot) vào kết quả', results.every((r) => Number(r.minValue) === 10 && Number(r.maxValue) === 20));
  await call('POST', `${CLIN}/pet-visits/${lv.v}/abandon`, { token: T.rec });
  check('dịch vụ tắt hoạt động không còn trong danh mục thường', (await call('PUT', `${CAT}/services/${svc.data.id}`, { token: T.admin, body: { active: false } })).status === 200 && !(await call('GET', `${CAT}/services`, { token: T.doc4 })).data.some((s) => s.id === svc.data.id));
  check('dịch vụ tắt vẫn xem được với includeInactive', (await call('GET', `${CAT}/services?includeInactive=true`, { token: T.admin })).data.some((s) => s.id === svc.data.id));

  // =====================================================================================================================
  step('C. Thú cưng và quyền xem hồ sơ');
  const freshOwnerToken = (await login(regPhone, 'Owner@9999')).accessToken;
  const ownerPet = await call('POST', '/api/v1/pets', { token: freshOwnerToken, body: { speciesId: ID.species.CAT, name: `Mèo tự thêm ${RUN}`, gender: 'FEMALE' } });
  check('chủ nuôi tự thêm thú cưng của mình', ownerPet.status === 201, brief(ownerPet));
  const petId = await newPet('Quầy', OWNER);
  check('lễ tân thêm hộ thú cưng cho chủ nuôi khach_lan', !!petId);
  check('lễ tân sửa thông tin thú cưng', (await call('PUT', `/api/v1/pets/${petId}`, { token: T.rec, body: { color: 'Vàng', neutered: true } })).data?.color === 'Vàng');
  check('lọc thú cưng theo chủ nuôi', (await call('GET', `/api/v1/pets?ownerId=${OWNER}&size=100`, { token: T.rec })).data?.items?.some((p) => p.id === petId));
  check('chủ nuôi chỉ thấy thú cưng của mình', (await call('GET', '/api/v1/pets?size=100', { token: T.owner2 })).data?.items?.every((p) => p.ownerId === OWNER2));
  check('chủ nuôi KHÔNG xem được thú cưng của người khác', [403, 404].includes((await call('GET', `/api/v1/pets/${petId}`, { token: T.owner2 })).status));
  check('chủ nuôi không xem bệnh án thú cưng người khác', [403, 404].includes((await call('GET', `/api/v1/pets/${petId}/medical-history`, { token: T.owner2 })).status));
  check('bác sĩ xem được bệnh án', (await call('GET', `/api/v1/pets/${petId}/medical-history`, { token: T.doc4 })).status === 200);
  // v4.0 (4.1): UUID, vi mạch + dị ứng, snapshot chủ nuôi, PetUpdatedEvent, xóa mềm
  const chip = `9001${RUN}`;
  check('id thú cưng là UUID; thêm vi mạch + dị ứng', petId.length === 36 && (await call('PUT', `/api/v1/pets/${petId}`, { token: T.rec, body: { microchipNumber: chip, allergies: 'Dị ứng penicillin' } })).data?.allergies === 'Dị ứng penicillin');
  check('số vi mạch đã dùng cho thú cưng khác → 409', (await call('PUT', `/api/v1/pets/${ID.pet.lu}`, { token: T.rec, body: { microchipNumber: chip } })).status === 409);
  check('tìm thú cưng theo SĐT chủ (snapshot) và theo số vi mạch', (await call('GET', '/api/v1/pets?ownerPhone=0912000008&size=100', { token: T.rec })).data?.items?.some((p) => p.id === petId) && (await call('GET', `/api/v1/pets?keyword=${chip}`, { token: T.rec })).data?.items?.some((p) => p.id === petId));
  const renamed = await call('PUT', `/api/v1/pets/${petId}`, { token: T.rec, body: { name: `Quầy-đổi-${RUN}` } });
  check('đổi tên thú cưng ghi PetUpdatedEvent vào outbox cùng giao dịch', renamed.status === 200 && Number(sql(`SELECT COUNT(*) FROM pet_clinic_clinical_db.outbox_events WHERE event_type='PetUpdatedEvent' AND aggregate_id = UNHEX(REPLACE('${petId}','-',''))`)) >= 1);
  const doomed = await newPet('XoaMem');
  check('xóa mềm thú cưng: dòng còn (deleted_at), GET → 404, biến mất khỏi danh sách', (await call('DELETE', `/api/v1/pets/${doomed}`, { token: T.rec })).status === 200
    && sql(`SELECT deleted_at IS NOT NULL FROM pet_clinic_clinical_db.pets WHERE id=${uuidSql(doomed)}`) === '1'
    && (await call('GET', `/api/v1/pets/${doomed}`, { token: T.rec })).status === 404
    && !(await call('GET', `/api/v1/pets?ownerId=${OWNER}&size=100`, { token: T.rec })).data?.items?.some((p) => p.id === doomed));
  check('thú cưng đã xóa mềm không tiếp đón được (404)', (await checkIn(doomed, DOC4)).status === 404);

  // =====================================================================================================================
  step('D. Lịch hẹn: đặt, duyệt, đổi giờ, hủy (quy tắc 2 giờ), vắng mặt, tiếp nhận theo lịch, báo cáo');
  const pA = await newPet('Lich-A'); const pB = await newPet('Lich-B'); const pC = await newPet('Lich-C');
  const day1 = dayOffset(2); const day2 = dayOffset(3);
  const byRec = await call('POST', APPT, { token: T.rec, body: { petId: pA, ownerId: OWNER, appointmentDate: day1, startTime: '08:30', reason: 'Đặt qua điện thoại', bookingChannel: 'PHONE' } });
  check('lễ tân đặt lịch qua điện thoại → CONFIRMED ngay', byRec.status === 201 && byRec.data.status === 'CONFIRMED' && byRec.data.bookingChannel === 'PHONE', brief(byRec));
  const byOwner = await call('POST', APPT, { token: T.owner, body: { petId: pB, ownerId: OWNER2, appointmentDate: day1, startTime: '09:00', reason: 'Đặt online', bookingChannel: 'PHONE' } });
  check('chủ nuôi đặt online → PENDING, kênh ONLINE (bỏ qua ownerId/kênh trong body)', byOwner.status === 201 && byOwner.data.status === 'PENDING' && byOwner.data.bookingChannel === 'ONLINE', brief(byOwner));
  check('cùng thú cưng cùng ngày: đặt lần hai bị từ chối (409)', (await call('POST', APPT, { token: T.rec, body: { petId: pA, ownerId: OWNER, appointmentDate: day1, startTime: '10:00', reason: 'Trùng ngày', bookingChannel: 'PHONE' } })).status === 409);
  check('đặt lịch ngày đã qua bị từ chối (400)', (await call('POST', APPT, { token: T.rec, body: { petId: pC, ownerId: OWNER, appointmentDate: dayOffset(-1), startTime: '09:00', reason: 'Quá khứ' } })).status === 400);
  check('lịch hẹn có snapshot tên thú cưng + tên / SĐT chủ nuôi', byRec.data?.petName && byRec.data?.ownerName && byRec.data?.ownerPhone === '0912000008', brief(byRec));
  const renamedA = `Lịch-đổi-${RUN}`;
  await call('PUT', `/api/v1/pets/${pA}`, { token: T.rec, body: { name: renamedA } });
  let petNameSynced = false; for (let i = 0; i < 30 && !petNameSynced; i += 1) { petNameSynced = (await call('GET', `${APPT}/${byRec.data.id}`, { token: T.rec })).data?.petName === renamedA; if (!petNameSynced) await sleep(500); }
  check('PetUpdatedEvent (outbox → RabbitMQ) cập nhật tên thú cưng trên lịch hẹn', petNameSynced);
  check('chủ nuôi không đặt cho thú cưng của người khác (403/404)', [403, 404].includes((await call('POST', APPT, { token: T.owner2, body: { petId: pC, appointmentDate: day2, startTime: '09:00', reason: 'Của người khác' } })).status));
  check('chủ nuôi không duyệt lịch (403)', (await call('POST', `${APPT}/${byOwner.data.id}/confirm`, { token: T.owner })).status === 403);
  check('lễ tân duyệt lịch PENDING → CONFIRMED', (await call('POST', `${APPT}/${byOwner.data.id}/confirm`, { token: T.rec })).data?.status === 'CONFIRMED');
  check('duyệt lần hai bị từ chối (trạng thái nguồn không hợp lệ)', (await call('POST', `${APPT}/${byOwner.data.id}/confirm`, { token: T.rec })).status === 400);
  const rs = await call('POST', `${APPT}/${byOwner.data.id}/reschedule`, { token: T.owner, body: { newDate: day2, newStartTime: '10:30', reason: 'Bận việc' } });
  check('chủ nuôi đổi sang khung khác (≥ 2 giờ trước giờ hẹn)', rs.status === 200 && rs.data.appointmentDate === day2 && String(rs.data.startTime).startsWith('10:30'), brief(rs));
  check('lịch sử trạng thái ghi đủ (tạo, duyệt, đổi giờ)', (await call('GET', `${APPT}/${byOwner.data.id}/history`, { token: T.rec })).data?.length >= 2);
  check('hủy bắt buộc có lý do (400)', (await call('POST', `${APPT}/${byOwner.data.id}/cancel`, { token: T.owner, body: {} })).status === 400);
  const before = (await call('GET', `${APPT}/slots?date=${day2}`)).data.find((s) => s.startTime.startsWith('10:30')).remaining;
  check('chủ nuôi hủy lịch ≥ 2 giờ → CANCELLED, trả lại chỗ', (await call('POST', `${APPT}/${byOwner.data.id}/cancel`, { token: T.owner, body: { reason: 'Đổi ý' } })).data?.status === 'CANCELLED' && (await call('GET', `${APPT}/slots?date=${day2}`)).data.find((s) => s.startTime.startsWith('10:30')).remaining === before + 1);
  check('hủy lịch đã hủy → từ chối', (await call('POST', `${APPT}/${byOwner.data.id}/cancel`, { token: T.rec, body: { reason: 'Lại' } })).status === 400);
  check('chủ nuôi chỉ thấy lịch của mình trong danh sách', (await call('GET', `${APPT}?size=100`, { token: T.owner2 })).data?.items?.every((a) => a.ownerId === OWNER2 || a.ownerId === undefined));
  check('lọc lịch theo mã / trạng thái / ngày', (await call('GET', `${APPT}?code=${byRec.data.appointmentCode}`, { token: T.rec })).data?.items?.length === 1 && (await call('GET', `${APPT}?status=CANCELLED&fromDate=${day1}&toDate=${day2}`, { token: T.rec })).data?.items?.some((a) => a.id === byOwner.data.id));
  // quy tắc 2 giờ: lịch hôm nay còn 1 giờ nữa
  const now = clinicNow(); const soon = new Date(now.getTime() + 3600000);
  const soonId = insertAppointment(`APT-E2E-${RUN}-A`, pC, iso(soon), hhmm(soon), 'CONFIRMED');
  const tooLate = await call('POST', `${APPT}/${soonId}/cancel`, { token: T.owner, body: { reason: 'Muộn' } });
  check('chủ nuôi hủy trong vòng 2 giờ trước giờ hẹn → APPT_1204', tooLate.code === 'APPT_1204', brief(tooLate));
  check('lễ tân hủy được dù sát giờ (không bị quy tắc 2 giờ)', (await call('POST', `${APPT}/${soonId}/cancel`, { token: T.rec, body: { reason: 'Phòng khám chủ động hủy' } })).data?.status === 'CANCELLED');
  // NO_SHOW + tiếp nhận muộn như vãng lai
  const pD = await newPet('NoShow'); const yesterday = dayOffset(-1);
  const nsId = insertAppointment(`APT-E2E-${RUN}-B`, pD, yesterday, '08:00:00', 'CONFIRMED');
  check('lễ tân đánh dấu vắng mặt lịch đã quá giờ → NO_SHOW', (await call('POST', `${APPT}/${nsId}/no-show`, { token: T.rec })).data?.status === 'NO_SHOW');
  check('đánh dấu vắng mặt lịch tương lai bị từ chối (APPT_1206)', (await call('POST', `${APPT}/${byRec.data.id}/no-show`, { token: T.rec })).code === 'APPT_1206');
  const lateWalkin = await checkIn(pD, DOC4, { appointmentId: nsId });
  check('tiếp nhận theo lịch đã NO_SHOW bị từ chối (APPT_1205)', lateWalkin.code === 'APPT_1205', brief(lateWalkin));
  const asWalkIn = await checkIn(pD, DOC4);
  check('khách đến trễ được tiếp nhận như vãng lai, lịch giữ NO_SHOW (FR-18)', asWalkIn.status === 201 && (await call('GET', `${APPT}/${nsId}`, { token: T.rec })).data?.status === 'NO_SHOW');
  await call('POST', `${CLIN}/pet-visits/${asWalkIn.data.id}/cancel`, { token: T.rec, body: { reason: 'dọn' } });
  // tiếp nhận theo lịch hôm nay → lịch CHECKED_IN qua sự kiện
  const pE = await newPet('CheckInAppt'); const today = iso(clinicNow());
  const ciId = insertAppointment(`APT-E2E-${RUN}-C`, pE, today, '16:30:00', 'CONFIRMED');
  const ci = await checkIn(pE, DOC4, { appointmentId: ciId, petId: undefined });
  check('tiếp nhận theo lịch hẹn hôm nay thành công (petId lấy từ lịch)', ci.status === 201 && ci.data.petId === pE, brief(ci));
  let checkedIn = false; for (let i = 0; i < 20 && !checkedIn; i += 1) { checkedIn = (await call('GET', `${APPT}/${ciId}`, { token: T.rec })).data?.status === 'CHECKED_IN'; if (!checkedIn) await sleep(500); }
  check('sự kiện visit.checked_in chuyển lịch hẹn sang CHECKED_IN', checkedIn);
  check('check-in lại thú cưng đang có đợt khám mở → CLIN_1306', (await checkIn(pE, DOC4)).code === 'CLIN_1306');
  await call('POST', `${CLIN}/pet-visits/${ci.data.id}/cancel`, { token: T.rec, body: { reason: 'dọn' } });
  const rep = await call('GET', `${APPT}/reports/summary?fromDate=${dayOffset(-3)}&toDate=${dayOffset(10)}`, { token: T.admin });
  check('báo cáo lịch hẹn: có tổng, tỉ lệ vắng mặt/hủy, theo kênh', rep.status === 200 && rep.data.totalAppointments >= 4 && rep.data.byChannel && 'noShowRate' in rep.data && 'cancellationRate' in rep.data, brief(rep));

  // =====================================================================================================================
  step('E. Quản lý đợt khám: phân công, hủy, dịch vụ tại phòng, mở lại hồ sơ, bỏ về, cấp cứu, quyền, giảm giá');
  const qa = await newPet('Q-A'); const qb = await newPet('Q-B');
  const va = (await checkIn(qa, undefined)).data; const vb = (await checkIn(qb, undefined, { isEmergency: true })).data;
  await waitTickets(vb.id, (ts) => ts.some((t) => t.priority === 2));   // ca cấp cứu: phiếu phát từ VisitCheckedInEvent
  const board0 = (await call('GET', `${CLIN}/pet-visits/queue`, { token: T.rec })).data;
  check('ca thường chưa nộp phí khám nằm ở mục "chờ thu phí", chưa vào danh sách chờ phân công', board0.awaitingPayment.some((v) => v.id === va.id) && !board0.unassigned.some((v) => v.id === va.id));
  check('ca cấp cứu có phiếu ngay (vào danh sách chờ phân công dù chưa nộp phí)', board0.unassigned.some((v) => v.id === vb.id && v.ticket?.priority === 2));
  await payExam(va.id);
  const board = (await call('GET', `${CLIN}/pet-visits/queue`, { token: T.rec })).data;
  check('nộp phí khám → có phiếu, vào danh sách chờ phân công; có gợi ý bác sĩ', board.unassigned.some((v) => v.id === va.id && v.ticket?.ticketNo > 0) && Boolean(board.suggestedDoctorId));   // id là UUID (chuỗi): không so sánh với số
  check('lễ tân xếp ca cho bác sĩ bất kỳ, kể cả đang khám ca khác (Q2)', (await call('POST', `${CLIN}/pet-visits/${va.id}/assign-doctor`, { token: T.rec, body: { doctorId: DOC3 } })).data?.doctorId === DOC3);
  const ticketBefore = (await myQueue(T.doc3)).find((v) => v.id === va.id)?.ticket;
  await call('POST', `${CLIN}/pet-visits/${va.id}/assign-doctor`, { token: T.rec, body: { doctorId: DOC4 } });
  const ticketAfter = (await myQueue(T.doc4)).find((v) => v.id === va.id)?.ticket;
  check('đổi bác sĩ: phiếu đi theo, giữ nguyên số và giờ phát', ticketBefore && ticketAfter && ticketAfter.id === ticketBefore.id && ticketAfter.ticketNo === ticketBefore.ticketNo && ticketAfter.issuedAt === ticketBefore.issuedAt);
  check('gỡ phân công → về danh sách chờ', (await call('POST', `${CLIN}/pet-visits/${va.id}/unassign-doctor`, { token: T.rec })).data?.doctorId == null);
  check('bác sĩ không được phân công ca (chỉ lễ tân): 403', (await call('POST', `${CLIN}/pet-visits/${va.id}/assign-doctor`, { token: T.doc4, body: { doctorId: DOC4 } })).status === 403);
  check('phân công cho tài khoản không phải bác sĩ hoạt động bị từ chối', (await call('POST', `${CLIN}/pet-visits/${va.id}/assign-doctor`, { token: T.rec, body: { doctorId: ID.user.letan_hoa } })).status >= 400);
  await call('POST', `${CLIN}/pet-visits/${va.id}/assign-doctor`, { token: T.rec, body: { doctorId: DOC4 } });
  await call('POST', `${CLIN}/pet-visits/${vb.id}/assign-doctor`, { token: T.rec, body: { doctorId: DOC4 } });
  const q4 = await myQueue(T.doc4);
  const queue4 = q4.map((v) => v.id);
  const ticketOfVisit = Object.fromEntries(q4.map((v) => [v.id, v.ticket?.id]));
  check('ca cấp cứu (đến sau) đứng trước ca thường trong hàng đợi bác sĩ', queue4.indexOf(vb.id) >= 0 && queue4.indexOf(vb.id) < queue4.indexOf(va.id), JSON.stringify(queue4));
  check('bác sĩ khác không gọi được phiếu của người khác (403)', (await callTicket(T.doc3, ticketOfVisit[vb.id])).status === 403);
  const called = await callNext(T.doc4);
  check('call-next chọn phiếu cấp cứu trước', called.data?.visitId === vb.id, brief(called));
  const callOther = await callTicket(T.doc4, ticketOfVisit[va.id]);
  const queueAfter = (await myQueue(T.doc4)).map((v) => v.id);
  check('cấp cứu vắng: gọi ca khác, ca cấp cứu vẫn là cấp cứu và giữ vị trí đầu hàng', callOther.status === 200 && queueAfter[0] === vb.id && (await visitOf(vb.id)).emergency === true, JSON.stringify(queueAfter));
  check('hủy tiếp nhận ca WAITING bắt buộc lý do (400)', (await call('POST', `${CLIN}/pet-visits/${vb.id}/cancel`, { token: T.rec, body: {} })).status === 400);
  check('hủy tiếp nhận ca WAITING (T03) → CANCELLED', (await call('POST', `${CLIN}/pet-visits/${vb.id}/cancel`, { token: T.rec, body: { reason: 'Khách bỏ về' } })).data?.status === 'CANCELLED');
  check('bác sĩ khác bắt đầu khám ca không thuộc mình → CLIN_1311', (await startExam(T.doc3, va.id)).code === 'CLIN_1311');
  check('bắt đầu khám đúng bác sĩ (T02)', (await startExam(T.doc4, va.id, false)).status === 200);
  check('đang có ca IN_PROGRESS thì không gọi số tiếp → CLIN_1302', (await callNext(T.doc4)).code === 'CLIN_1302');
  check('hủy tiếp nhận ca đang khám bị từ chối (chỉ WAITING)', (await call('POST', `${CLIN}/pet-visits/${va.id}/cancel`, { token: T.rec, body: { reason: 'x' } })).status === 400);
  check('thiếu sinh hiệu → chỉ định CLS bị từ chối CLIN_1308', (await call('POST', `${CLIN}/pet-visits/${va.id}/paraclinical-orders`, { token: T.doc4, body: { serviceIds: [S.LAB_CBC] } })).code === 'CLIN_1308');
  check('chốt ca thiếu chẩn đoán → CLIN_1307', (await call('POST', `${CLIN}/pet-visits/${va.id}/close-visit`, { token: T.doc4, body: { treatmentPlan: 'x' } })).code === 'CLIN_1307');
  check('ghi lần đo sinh hiệu (POST vital-signs, một dòng mỗi lần đo)', (await call('POST', `${CLIN}/pet-visits/${va.id}/vital-signs`, { token: T.doc4, body: { weightKg: 7.5, temperatureC: 38.9 } })).status === 201
    && (await call('GET', `${CLIN}/pet-visits/${va.id}/vital-signs`, { token: T.doc4 })).data?.length === 1);
  check('sửa hồ sơ khám (PUT medical-record) giữ nguyên trường không gửi', (await call('PUT', `${CLIN}/pet-visits/${va.id}/medical-record`, { token: T.doc4, body: { clinicalFindings: 'Ghi chú', preliminaryDiagnosis: 'Nghi viêm da' } })).data?.clinicalFindings === 'Ghi chú');
  const st = (await call('GET', `${CLIN}/doctors/status`, { token: T.rec })).data;
  check('doctors/status: bs_mai BUSY, bs_an AVAILABLE', st.find((d) => d.doctorId === DOC4)?.status === 'BUSY' && st.find((d) => d.doctorId === DOC3)?.status === 'AVAILABLE');
  // dịch vụ tại phòng
  const ord = await call('POST', `${CLIN}/pet-visits/${va.id}/in-room-services`, { token: T.doc4, body: { services: [{ serviceId: S.VAC_7B, quantity: 1 }, { serviceId: S.DEWORM, quantity: 2, notes: 'e2e' }, { serviceId: S.VAC_4B, quantity: 1 }] } });
  check('chỉ định 3 dịch vụ tại phòng, mỗi dịch vụ một hóa đơn PENDING (thu khi chốt ca)', ord.status === 200 && (await statementOf(va.id)).invoices.filter((i) => i.paymentStatus === 'PENDING' && !i.requiresPrepayment).length === 3, brief(ord));
  check('dịch vụ CLS không chỉ định qua đường dịch vụ tại phòng (400)', (await call('POST', `${CLIN}/pet-visits/${va.id}/in-room-services`, { token: T.doc4, body: { services: [{ serviceId: S.LAB_CBC, quantity: 1 }] } })).status >= 400);
  const details = (await visitOf(va.id)).services;
  const vac = details.find((d) => d.serviceId === S.VAC_7B); const dew = details.find((d) => d.serviceId === S.DEWORM); const cat = details.find((d) => d.serviceId === S.VAC_4B);
  await call('POST', `${CLIN}/service-details/${cat.id}/perform`, { token: T.doc4 });
  check('"Đã thực hiện" → COMPLETED', (await call('POST', `${CLIN}/service-details/${vac.id}/perform`, { token: T.doc4 })).data?.status === 'COMPLETED');
  check('hủy dịch vụ chưa làm → dịch vụ và hóa đơn CANCELLED', (await call('POST', `${CLIN}/service-details/${dew.id}/cancel`, { token: T.doc4, body: { reason: 'Khách không đồng ý' } })).data?.status === 'CANCELLED' && (await statementOf(va.id)).invoices.find((i) => i.visitServiceDetailId === dew.id).paymentStatus === 'CANCELLED');
  check('chốt chuyên môn (T05) → WAITING_PAYMENT', (await closeVisit(T.doc4, va.id)).data?.status === 'WAITING_PAYMENT');
  check('bác sĩ không sửa đơn thuốc sau khi chốt (PHARM_1411)', (await call('PUT', `${CLIN}/pet-visits/${va.id}/prescription`, { token: T.doc4, body: { items: [{ medicineId: M.MED_AMOX, quantity: 1, dosage: 'a', frequency: 'b', durationDays: 1 }] } })).code === 'PHARM_1411');
  const stmt = await statementOf(va.id);
  const vacInv = stmt.invoices.find((i) => i.visitServiceDetailId === vac.id);
  const gross = Number(vacInv.payableAmount) + Number(vacInv.discountAmount);
  check('giảm giá vượt giá trị hóa đơn → BILL_1402', (await call('POST', `${CLIN}/service-invoices/${vacInv.id}/discount`, { token: T.rec, body: { discountAmount: gross + 1 } })).code === 'BILL_1402');
  check('giảm giá hợp lệ làm giảm số phải trả', Number((await call('POST', `${CLIN}/service-invoices/${vacInv.id}/discount`, { token: T.rec, body: { discountAmount: 10000 } })).data?.payableAmount ?? (await statementOf(va.id)).invoices.find((i) => i.id === vacInv.id).payableAmount) === gross - 10000);
  check('thu hóa đơn không thuộc ca → BILL_1401', (await call('POST', `${CLIN}/pet-visits/${va.id}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: ['019a9442-d860-7000-8000-999999999999'] } })).code === 'BILL_1401');
  check('thu một phần: chỉ hóa đơn tiêm phòng', (await call('POST', `${CLIN}/pet-visits/${va.id}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: [vacInv.id] } })).status === 200 && (await visitOf(va.id)).status === 'WAITING_PAYMENT');
  check('thu lại hóa đơn đã thu → BILL_1403', (await call('POST', `${CLIN}/pet-visits/${va.id}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: [vacInv.id] } })).code === 'BILL_1403');
  check('thu hóa đơn đã hủy → BILL_1408', (await call('POST', `${CLIN}/pet-visits/${va.id}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: [stmt.invoices.find((i) => i.paymentStatus === 'CANCELLED').id] } })).code === 'BILL_1408');
  // mở lại hồ sơ T14
  check('mở lại hồ sơ bắt buộc lý do (400)', (await call('POST', `${CLIN}/pet-visits/${va.id}/reopen`, { token: T.doc4, body: {} })).status === 400);
  check('bác sĩ khác không mở lại ca của người khác', (await call('POST', `${CLIN}/pet-visits/${va.id}/reopen`, { token: T.doc3, body: { reason: 'x' } })).status >= 400);
  check('bác sĩ phụ trách mở lại hồ sơ (T14) → IN_PROGRESS', (await call('POST', `${CLIN}/pet-visits/${va.id}/reopen`, { token: T.doc4, body: { reason: 'Bổ sung dịch vụ' } })).data?.status === 'IN_PROGRESS');
  check('dịch vụ tại phòng đã làm và đã thu không hủy được', (await call('POST', `${CLIN}/service-details/${vac.id}/cancel`, { token: T.doc4, body: { reason: 'Muốn hủy' } })).status >= 400);
  const add = await call('POST', `${CLIN}/pet-visits/${va.id}/in-room-services`, { token: T.doc4, body: { services: [{ serviceId: S.VAC_4B, quantity: 1 }] } });
  check('thêm dịch vụ sau khi mở lại → hóa đơn mới PENDING, hóa đơn PAID giữ nguyên', add.status === 200 && (await statementOf(va.id)).invoices.find((i) => i.id === vacInv.id).paymentStatus === 'PAID');
  const newSvc = (await visitOf(va.id)).services.find((d) => d.serviceId === S.VAC_4B && d.status === 'PENDING');
  await call('POST', `${CLIN}/service-details/${newSvc.id}/perform`, { token: T.doc4 });
  check('chốt lại ca (T09) sau khi mở lại', (await closeVisit(T.doc4, va.id)).data?.status === 'WAITING_PAYMENT');
  check('Admin cũng mở lại được hồ sơ (T14)', (await call('POST', `${CLIN}/pet-visits/${va.id}/reopen`, { token: T.admin, body: { reason: 'Admin kiểm tra' } })).data?.status === 'IN_PROGRESS');
  check('hủy ca đã có dịch vụ tại phòng hoàn thành bị từ chối (T11 chỉ khi chưa có dịch vụ COMPLETED)', (await call('POST', `${CLIN}/pet-visits/${va.id}/cancel-exam`, { token: T.doc4, body: { reason: 'Chuyển viện' } })).status >= 400);
  await closeVisit(T.doc4, va.id);
  const rest = (await statementOf(va.id)).invoices.filter((i) => i.paymentStatus === 'PENDING').map((i) => i.id);
  check('thu nốt các hóa đơn còn lại → ca COMPLETED', (await call('POST', `${CLIN}/pet-visits/${va.id}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: rest } })).status === 200 && (await visitOf(va.id)).status === 'COMPLETED');
  const hist = await call('GET', `${CLIN}/pet-visits/${va.id}/history`, { token: T.doc4 });
  check('lịch sử trạng thái ghi đủ mọi lần chuyển (T01…T14) kèm người thực hiện', hist.data?.length >= 8 && hist.data.some((h) => h.toStatus === 'COMPLETED'), `${hist.data?.length}`);
  // T11 hủy ca không thu phí
  const ex = await inProgress('T11', DOC4);
  await call('POST', `${CLIN}/pet-visits/${ex.v}/in-room-services`, { token: ex.token, body: { services: [{ serviceId: S.DEWORM, quantity: 1 }] } });
  const t11 = await call('POST', `${CLIN}/pet-visits/${ex.v}/cancel-exam`, { token: ex.token, body: { reason: 'Chuyển viện' } });
  const t11Inv = (await statementOf(ex.v)).invoices;
  check('hủy ca / chuyển viện (T11) → CANCELLED; hóa đơn chưa thu CANCELLED, phí khám đã thu giữ PAID (không hoàn)', t11.data?.status === 'CANCELLED' && t11Inv.filter((i) => i.category !== 'EXAMINATION').every((i) => i.paymentStatus === 'CANCELLED') && t11Inv.find((i) => i.category === 'EXAMINATION').paymentStatus === 'PAID', brief(t11));
  // T12 bỏ về khi chờ kết quả
  const ab = await inProgress('T12', DOC4);
  await call('POST', `${CLIN}/pet-visits/${ab.v}/paraclinical-orders`, { token: ab.token, body: { serviceIds: [S.LAB_CBC, S.IMG_XRAY] } });
  await payCls(ab.v);
  const abLab = ((await startRoom('lab', ab.v))[0] || {}).serviceDetailId;
  check('bỏ về khi KTV đang thực hiện dở → CLIN_1304', (await call('POST', `${CLIN}/pet-visits/${ab.v}/abandon`, { token: T.rec })).code === 'CLIN_1304');
  await call('POST', `${CLIN}/service-details/${abLab}/cancel`, { token: T.doc4, body: { reason: 'Dừng xét nghiệm' } });
  const abandoned = await call('POST', `${CLIN}/pet-visits/${ab.v}/abandon`, { token: T.rec });
  const abInv = (await statementOf(ab.v)).invoices;
  check('bỏ về giữa chừng (T12) → COMPLETED; XN bác sĩ dừng (đã thu) được hoàn tiền; CĐHA đã thu chưa làm giữ PAID (khách bỏ về)', abandoned.data?.status === 'COMPLETED' && abInv.find((i) => i.category === 'LABORATORY').paymentStatus === 'REFUNDED' && abInv.find((i) => i.category === 'IMAGING').paymentStatus === 'PAID', brief(abandoned) + JSON.stringify(abInv.map((i) => i.paymentStatus)));
  const abCode = (await visitOf(ab.v)).visitCode;
  const found2 = await call('GET', `${CLIN}/pet-visits?status=COMPLETED&keyword=${encodeURIComponent(abCode)}`, { token: T.admin });
  check('tìm đợt khám theo trạng thái + từ khóa (mã đợt khám)', found2.data?.items?.some((v) => v.id === ab.v));
  check('lọc theo trạng thái khác không lẫn ca (WAITING không chứa ca đã COMPLETED)', !(await call('GET', `${CLIN}/pet-visits?status=WAITING&size=100`, { token: T.admin })).data.items.some((v) => v.id === ab.v));
  check('lọc đợt khám theo bác sĩ và ngày', (await call('GET', `${CLIN}/pet-visits?doctorId=${DOC4}&date=${iso(clinicNow())}&size=50`, { token: T.admin })).data?.items?.every((v) => v.doctorId === DOC4));
  // ảnh CĐHA: kiểm tra nội dung tệp + tải ảnh theo quyền
  const im = await inProgress('IMG', DOC4);
  await call('POST', `${CLIN}/pet-visits/${im.v}/paraclinical-orders`, { token: im.token, body: { serviceIds: [S.IMG_US_AB] } });
  await payCls(im.v);
  const imgDetail = ((await startRoom('imaging', im.v))[0] || {}).serviceDetailId;
  check('tệp giả ảnh (nội dung không phải PNG) bị từ chối', (await call('POST', `${CLIN}/imaging-results/${imgDetail}`, { token: T.img, form: imgForm(Buffer.from('không phải ảnh'), 'image/png', 'gia.png') })).status >= 400);
  check('định dạng không cho phép (pdf) bị từ chối', (await call('POST', `${CLIN}/imaging-results/${imgDetail}`, { token: T.img, form: imgForm(Buffer.from('%PDF-1.4'), 'application/pdf', 'a.pdf') })).status >= 400);
  const saved = await call('POST', `${CLIN}/imaging-results/${imgDetail}`, { token: T.img, form: imgForm() });
  check('ảnh hợp lệ được lưu', saved.status === 200, brief(saved));
  const attach = (await visitOf(im.v)).imagingResults?.[0]?.attachments?.[0];
  // Quy chuẩn 7.5: CSDL chỉ lưu file_key; URL ký tạm (HMAC, hạn 1 giờ) được cấp sau khi kiểm quyền, /files/** chỉ kiểm chữ ký
  const urlRes = attach && await call('GET', `${CLIN}/imaging-attachments/${attach.id}/url`, { token: T.doc4 });
  const fileKey = sql(`SELECT file_key FROM pet_clinic_clinical_db.imaging_attachments WHERE id=${uuidSql(attach.id)}`);
  check('CSDL lưu file_key dạng imaging/yyyy/MM/<uuid>.png (không lưu URL)', /^imaging\/\d{4}\/\d{2}\/[0-9a-f-]{36}\.png$/.test(fileKey), fileKey);
  check('bác sĩ xin được URL ký tạm /files/<file_key>?exp&sig', urlRes?.status === 200 && urlRes.data.url.startsWith(`/files/${fileKey}?exp=`) && urlRes.data.url.includes('&sig=') && Boolean(urlRes.data.expiresAt), brief(urlRes));
  const dl = urlRes?.status === 200 && await fetch(`${API}${urlRes.data.url}`);   // không gửi Authorization: chữ ký thay token (thẻ img)
  check('tải ảnh bằng URL ký không cần token (đúng loại, đúng dung lượng)', dl?.status === 200 && dl.headers.get('content-type') === 'image/png' && Buffer.from(await dl.arrayBuffer()).length === PNG.length);
  check('sửa chữ ký → bị từ chối (403)', urlRes?.status === 200 && (await fetch(`${API}${urlRes.data.url.replace(/sig=./, 'sig=X')}`)).status === 403);
  check('sửa hạn (exp) → bị từ chối (403)', urlRes?.status === 200 && (await fetch(`${API}${urlRes.data.url.replace(/exp=\d+/, 'exp=9999999999')}`)).status === 403);
  check('file_key lạ / đi ra ngoài thư mục → 404', (await fetch(`${API}/files/imaging/../../etc/passwd?exp=9999999999&sig=x`)).status >= 400
    && (await fetch(`${API}/files/imaging/2026/11/khong-ton-tai.png?exp=9999999999&sig=x`)).status === 404);
  const urlOther = attach && await call('GET', `${CLIN}/imaging-attachments/${attach.id}/url`, { token: T.owner2 });
  check('chủ nuôi khác KHÔNG xin được URL ảnh (403/404)', [403, 404].includes(urlOther?.status));
  check('không token không xin được URL (401)', attach && (await fetch(`${API}${CLIN}/imaging-attachments/${attach.id}/url`)).status === 401);
  await call('POST', `${CLIN}/pet-visits/${im.v}/abandon`, { token: T.rec });
  // Gộp ca: nhiều dịch vụ CĐHA của CÙNG một đợt khám bị đẩy lùi cùng nhau (một lần gọi / một lần đẩy lùi)
  const g1 = await inProgress('GRP1', DOC4);
  await call('POST', `${CLIN}/pet-visits/${g1.v}/paraclinical-orders`, { token: g1.token, body: { serviceIds: [S.IMG_US_AB, S.IMG_XRAY] } });
  check('chưa nộp tiền: hai dịch vụ CĐHA chưa vào hàng đợi phòng', (await roomRows('imaging', g1.v)).length === 0);
  await payCls(g1.v);
  const g2 = await inProgress('GRP2', DOC4);
  await call('POST', `${CLIN}/pet-visits/${g2.v}/paraclinical-orders`, { token: g2.token, body: { serviceIds: [S.IMG_US_AB] } });
  await payCls(g2.v);
  const q1 = (await call('GET', `${CLIN}/imaging-queue`, { token: T.img })).data;
  const g1rows = q1.filter((i) => i.visitId === g1.v);
  check('hai dịch vụ CĐHA cùng đợt khám chung MỘT phiếu, nằm liền nhau trong hàng đợi', g1rows.length === 2 && g1rows[0].ticketId === g1rows[1].ticketId && Math.abs(q1.indexOf(g1rows[0]) - q1.indexOf(g1rows[1])) === 1);
  // "Gọi" một ca bất kỳ (không nhất thiết đứng đầu): chỉ thông báo, không đổi trạng thái / thứ tự
  const g2row = q1.find((i) => i.visitId === g2.v);
  const callOne = await callTicket(T.img, g2row.ticketId);
  const q1b = (await call('GET', `${CLIN}/imaging-queue`, { token: T.img })).data;
  check('Gọi một ca bất kỳ trong hàng đợi CĐHA: 200 và không đổi thứ tự', callOne.status === 200 && callOne.data?.visitId === g2.v
    && q1b.map((i) => i.serviceDetailId).join() === q1.map((i) => i.serviceDetailId).join(), brief(callOne));
  check('KTV Lab không gọi được phiếu của phòng CĐHA (403)', (await callTicket(T.lab, g2row.ticketId)).status === 403);
  const gAgain = await callTicket(T.img, g1rows[0].ticketId);
  const q2 = (await call('GET', `${CLIN}/imaging-queue`, { token: T.img })).data;
  check('Gọi lại ca có hai dịch vụ: 200, thứ tự không đổi và ca vẫn đủ hai dịch vụ', gAgain.status === 200 && gAgain.data?.visitId === g1.v
    && q2.map((i) => i.serviceDetailId).join() === q1.map((i) => i.serviceDetailId).join() && q2.filter((i) => i.visitId === g1.v).length === 2);
  check('API "đẩy lùi" phòng CĐHA đã bị bỏ', (await call('POST', `${CLIN}/service-details/${g1rows[0].serviceDetailId}/skip`, { token: T.img })).status >= 400);
  await call('POST', `${CLIN}/pet-visits/${g1.v}/abandon`, { token: T.rec });
  await call('POST', `${CLIN}/pet-visits/${g2.v}/abandon`, { token: T.rec });

  // EX-17: Bác sĩ bị khóa khi còn ca đang chờ (BR-39): phân công mới hỏi auth-service ngay lúc ghi, ca chờ về danh sách chờ phân công
  const ex1 = (await checkIn(await newPet('Ex17a'), DOC4)).data.id; await payExam(ex1);
  const ex2 = (await checkIn(await newPet('Ex17b'), DOC4)).data.id; await payExam(ex2);
  // phiếu nằm trên Redis (bước 6.2): đọc Hash của phiếu DOCTOR đang mở của ca (tập queue:visit:{visitId})
  const ticketRow = (v) => {
    const id = redis('smembers', `queue:visit:${v}`).split('\n')[0];
    if (!id) return '';
    const [no, status, doctor] = redis('hmget', `ticket:${id}`, 'ticketNo', 'status', 'doctorId').split('\n');
    return `${no}|${status}|${doctor || 'NULL'}`;
  };
  const ex17Before = [ticketRow(ex1), ticketRow(ex2)];
  const exWorking = await inProgress('Ex17c', DOC4);
  check('EX-17: dựng hàng đợi Bác sĩ Mai: 2 phiếu chờ + 1 ca đang khám', ex17Before.every((r) => r.includes('|WAITING|')) && (await visitOf(exWorking.v)).status === 'IN_PROGRESS', ex17Before.join(' ; '));
  check('EX-17: Admin khóa Bác sĩ Mai', (await call('POST', `${USERS}/${DOC4}/lock`, { token: T.admin })).status === 200);
  check('EX-17: phân công cho Bác sĩ đã khóa bị từ chối CLIN_1316 ngay (hỏi auth-service lúc ghi, không dựa vào sự kiện)', (await call('POST', `${CLIN}/pet-visits/${ex1}/assign-doctor`, { token: T.rec, body: { doctorId: DOC4 } })).code === 'CLIN_1316');
  let released = false;
  for (let i = 0; i < 30 && !released; i += 1) { released = sql(`SELECT COUNT(*) FROM pet_clinic_clinical_db.pet_visits WHERE id IN (${uuidSql(ex1)}, ${uuidSql(ex2)}) AND doctor_id IS NULL`) === '2'; if (!released) await sleep(500); }
  check('EX-17: UserStatusChangedEvent (outbox → RabbitMQ) gỡ phân công 2 ca đang chờ của Bác sĩ Mai', released);
  const ex17After = [ticketRow(ex1), ticketRow(ex2)];
  check('EX-17: phiếu về danh sách chờ phân công, GIỮ NGUYÊN số phiếu', ex17After.every((r, i) => r.split('|')[0] === ex17Before[i].split('|')[0] && r.endsWith('|WAITING|NULL')), ex17After.join(' ; '));
  const ex17Board = (await call('GET', `${CLIN}/pet-visits/queue`, { token: T.rec })).data;
  check('EX-17: hai ca hiện ở "chờ phân công" và Bác sĩ Mai không còn trong danh sách bác sĩ hoạt động', [ex1, ex2].every((v) => ex17Board.unassigned.some((u) => u.id === v)) && !ex17Board.doctors.some((d) => d.doctorId === DOC4));
  const working = await visitOf(exWorking.v);
  check('EX-17: ca đang khám KHÔNG bị ngắt; vẫn hiện tên Bác sĩ (bản chụp) kèm doctorActive = false ("Đã khóa")', working.status === 'IN_PROGRESS' && working.doctorId === DOC4 && Boolean(working.doctorName) && working.doctorActive === false, brief({ status: 200, data: working }));
  check('EX-17: Lễ tân phân công lại cho Bác sĩ khác được, phiếu giữ số', (await call('POST', `${CLIN}/pet-visits/${ex1}/assign-doctor`, { token: T.rec, body: { doctorId: DOC3 } })).status === 200 && ticketRow(ex1).split('|')[0] === ex17Before[0].split('|')[0]);
  check('EX-17: Admin mở khóa → nhận ca được lại', (await call('POST', `${USERS}/${DOC4}/unlock`, { token: T.admin })).status === 200 && (await call('POST', `${CLIN}/pet-visits/${ex2}/assign-doctor`, { token: T.rec, body: { doctorId: DOC4 } })).status === 200);
  for (const v of [ex1, ex2]) await call('POST', `${CLIN}/pet-visits/${v}/cancel`, { token: T.rec, body: { reason: 'dọn dẹp EX-17' } });
  await call('POST', `${CLIN}/pet-visits/${exWorking.v}/cancel-exam`, { token: exWorking.token, body: { reason: 'dọn dẹp EX-17' } });

  // =====================================================================================================================
  step('F. Báo cáo');
  const rFrom = dayOffset(-1);
  const rTo = dayOffset(1);
  const r1 = await call('GET', `/api/v1/reports/visits/summary?from=${rFrom}&to=${rTo}`, { token: T.admin });
  check('báo cáo vận hành (reporting-service): tổng ca, hoàn tất, hủy, cấp cứu, theo bác sĩ', r1.status === 200 && r1.data.totalVisits > 5 && r1.data.completedVisits > 0 && r1.data.cancelledVisits > 0 && r1.data.emergencyVisits > 0 && r1.data.doctors.length > 0, JSON.stringify(r1.data).slice(0, 200));
  const r2 = await call('GET', `/api/v1/reports/revenue/by-category?from=${rFrom}&to=${rTo}`, { token: T.admin });
  check('doanh thu theo nhóm: có nhóm dịch vụ và nhóm thuốc, tổng thực thu dương', r2.status === 200 && Number(r2.data.totals.netAmount) > 0 && r2.data.items.some((i) => i.revenueType === 'SERVICE') && r2.data.items.some((i) => i.revenueType === 'MEDICINE'), JSON.stringify(r2.data).slice(0, 250));
  const r3 = await call('GET', `/api/v1/reports/revenue/daily?from=${rFrom}&to=${rTo}&type=SERVICE`, { token: T.admin });
  check('doanh thu theo ngày: lọc type=SERVICE, mỗi dòng một ngày', r3.status === 200 && r3.data.items.length >= 1 && r3.data.items.every((i) => i.revenueType === 'SERVICE'));
  const r4 = await call('GET', `/api/v1/reports/doctors/performance?from=${rFrom}&to=${rTo}`, { token: T.admin });
  check('hiệu suất bác sĩ: có ca hoàn tất và doanh thu dịch vụ', r4.status === 200 && r4.data.doctors.some((d) => d.completedVisits > 0 && Number(d.serviceNetRevenue) > 0), JSON.stringify(r4.data).slice(0, 200));
  check('khoảng báo cáo đảo ngược / quá dài bị từ chối', (await call('GET', `/api/v1/reports/revenue/daily?from=${dayOffset(5)}&to=${dayOffset(1)}`, { token: T.admin })).status === 400 && (await call('GET', `/api/v1/reports/revenue/daily?from=2020-01-01&to=2026-11-01`, { token: T.admin })).status === 400);
  check('lễ tân / bác sĩ không xem báo cáo doanh thu (403)', (await call('GET', `/api/v1/reports/revenue/daily`, { token: T.rec })).status === 403 && (await call('GET', `/api/v1/reports/doctors/performance`, { token: T.doc4 })).status === 403);

  // =====================================================================================================================
  step('G. Danh mục thuốc và kho');
  const med = await call('POST', `${PHARM}/medicines`, { token: T.admin, body: { code: `MED${RUN}`, name: `Thuốc e2e ${RUN}`, categoryCode: 'ANTIBIOTIC', dosageForm: 'Viên', baseUnit: 'Viên', packagingSpec: 'Hộp 10 viên', ingredients: [{ ingredientName: 'Hoạt chất e2e', strengthValue: 100, strengthUnit: 'mg' }], salePrice: 12000, vatRate: 0.1, initialStock: 30, minStockAlert: 5 } });
  check('Admin thêm thuốc (VAT 10%, tồn đầu 30, 1 thành phần, quy cách)', med.status === 201 && Number(med.data.vatRate) === 0.1 && med.data.stockQuantity === 30 && med.data.ingredients?.length === 1 && med.data.packagingSpec === 'Hộp 10 viên', brief(med));
  check('trùng mã thuốc → 409 PHARM_1414', (await call('POST', `${PHARM}/medicines`, { token: T.admin, body: { code: `MED${RUN}`, name: 'Trùng', categoryCode: 'ANTIBIOTIC', dosageForm: 'Viên', baseUnit: 'Viên', packagingSpec: 'Hộp 10 viên', salePrice: 1 } })).code === 'PHARM_1414');
  check('tồn đầu kỳ được ghi nhật ký IMPORT dương', (await call('GET', `${PHARM}/medicines/${med.data.id}/transactions`, { token: T.admin })).data.items.some((t) => t.transactionType === 'IMPORT' && t.quantity === 30));
  check('nhập kho số âm / bằng 0 bị từ chối (400)', (await call('POST', `${PHARM}/medicines/${med.data.id}/import`, { token: T.admin, body: { quantity: 0 } })).status === 400);
  check('cân chỉnh thiếu lý do bị từ chối (400)', (await call('POST', `${PHARM}/medicines/${med.data.id}/adjust`, { token: T.admin, body: { delta: -1 } })).status === 400);
  check('cân chỉnh −28 → tồn 2; lọc "sắp hết" thấy thuốc', (await call('POST', `${PHARM}/medicines/${med.data.id}/adjust`, { token: T.admin, body: { delta: -28, reason: 'Kiểm kê' } })).data?.stockQuantity === 2 && (await call('GET', `${PHARM}/medicines?lowStock=true&size=100`, { token: T.admin })).data.items.some((m) => m.id === med.data.id));
  check('đổi giá + VAT (bảng giá có lịch sử) không đụng tồn kho; dòng giá cũ được đóng, không bị sửa', (await call('POST', `${PHARM}/medicines/${med.data.id}/prices`, { token: T.admin, body: { salePrice: 15000, vatRate: 0.05, effectiveFrom: new Date(Date.now() + 86400000).toISOString(), reason: 'e2e' } })).status === 201
    && (await call('GET', `${PHARM}/medicines/${med.data.id}`, { token: T.admin })).data?.stockQuantity === 2
    && sql(`SELECT COUNT(*) FROM pet_clinic_billing_pharmacy_db.medicine_prices WHERE medicine_id=${uuidSql(med.data.id)}`) === '2'
    && sql(`SELECT sale_price FROM pet_clinic_billing_pharmacy_db.medicine_prices WHERE medicine_id=${uuidSql(med.data.id)} AND effective_to IS NOT NULL`) === '12000.00');
  check('ngừng kinh doanh: bác sĩ không còn thấy; Admin vẫn thấy', (await call('PUT', `${PHARM}/medicines/${med.data.id}`, { token: T.admin, body: { name: `Thuốc e2e ${RUN}`, dosageForm: 'Viên', baseUnit: 'Viên', packagingSpec: 'Hộp 10 viên', active: false } })).data?.active === false && !(await call('GET', `${PHARM}/medicines?size=100`, { token: T.doc4 })).data.items.some((m) => m.id === med.data.id) && (await call('GET', `${PHARM}/medicines?active=false&size=100`, { token: T.admin })).data.items.some((m) => m.id === med.data.id));
  const rv = await inProgress('KeDon', DOC4);
  check('kê thuốc đã ngừng kinh doanh → 404 PHARM_1405', (await call('PUT', `${CLIN}/pet-visits/${rv.v}/prescription`, { token: rv.token, body: { items: [{ medicineId: med.data.id, quantity: 1, dosage: 'a', frequency: 'b', durationDays: 1 }] } })).code === 'PHARM_1405');
  const rxOk = await call('PUT', `${CLIN}/pet-visits/${rv.v}/prescription`, { token: rv.token, body: { items: [{ medicineId: M.MED_AMOX, quantity: 2, dosage: '1', frequency: '2', durationDays: 3 }, { medicineId: M.MED_VANG_7B, quantity: 1, dosage: '1', frequency: '1', durationDays: 1 }] } });
  check('kê đơn nhiều thuốc; kê trùng thuốc trong một đơn bị từ chối', rxOk.status === 200 && (await call('PUT', `${CLIN}/pet-visits/${rv.v}/prescription`, { token: rv.token, body: { items: [{ medicineId: M.MED_AMOX, quantity: 1, dosage: 'a', frequency: 'b', durationDays: 1 }, { medicineId: M.MED_AMOX, quantity: 1, dosage: 'a', frequency: 'b', durationDays: 1 }] } })).status >= 400);
  check('mỗi đợt khám tối đa một đơn: lưu lại là cập nhật cùng đơn (prescriptions.visit_id UNIQUE)', (await call('PUT', `${CLIN}/pet-visits/${rv.v}/prescription`, { token: rv.token, body: { items: [{ medicineId: M.MED_AMOX, quantity: 1, dosage: 'a', frequency: 'b', durationDays: 1 }, { medicineId: M.MED_VANG_7B, quantity: 1, dosage: '1', frequency: '1', durationDays: 1 }] } })).data?.id === rxOk.data.id && sql(`SELECT COUNT(*) FROM pet_clinic_clinical_db.prescriptions WHERE visit_id=${uuidSql(rv.v)}`) === '1');
  check('sửa đơn: bỏ bớt thuốc, giữ id dòng còn lại', (await call('PUT', `${CLIN}/pet-visits/${rv.v}/prescription`, { token: rv.token, body: { items: [{ medicineId: M.MED_AMOX, quantity: 3, dosage: '1', frequency: '2', durationDays: 3 }] } })).data?.items?.length === 1);
  check('chủ nuôi xem đơn của thú cưng mình; chủ nuôi khác không (404)', (await call('GET', `${CLIN}/pet-visits/${rv.v}/prescription`, { token: T.owner })).status === 200 && (await call('GET', `${CLIN}/pet-visits/${rv.v}/prescription`, { token: T.owner2 })).status === 404);
  await closeVisit(rv.token, rv.v);
  const draft = await draftOf(T.rec, rv.v);
  check('chốt ca có đơn → hóa đơn thuốc nháp tự tạo, mua đúng số kê (Clavamox 3)', draft && draft.paymentStatus === 'PENDING' && draft.items.length === 1 && draft.items[0].quantity === 3, brief(draft));
  const mInv = await call('PUT', `${BILL}/medicine-invoices/${draft.id}/items`, { token: T.rec, body: { items: [{ medicineId: M.MED_AMOX, quantity: 2 }], discountAmount: 99999999 } });
  check('giảm giá hóa đơn thuốc vượt tổng → BILL_1402', mInv.code === 'BILL_1402', brief(mInv));
  check('Thu ngân hủy nháp (khách mua ngoài) → CANCELLED, kho không đổi', (await call('POST', `${BILL}/medicine-invoices/${draft.id}/cancel`, { token: T.rec })).data?.paymentStatus === 'CANCELLED');
  await call('POST', `${CLIN}/pet-visits/${rv.v}/reopen`, { token: T.admin, body: { reason: 'dọn' } });
  await call('POST', `${CLIN}/pet-visits/${rv.v}/cancel-exam`, { token: rv.token, body: { reason: 'dọn' } });

  // =====================================================================================================================
  step('H. WebSocket / STOMP: xác thực JWT, chỉ nhận kênh của mình, cấm gửi, nhận thông báo thời gian thực');
  check('CONNECT không token bị từ chối (ERROR)', !!(await (await stomp(null)).waitFor((f) => f.command === 'ERROR', 6000)));
  check('CONNECT token sai bị từ chối', !!(await (await stomp('abc.def.ghi')).waitFor((f) => f.command === 'ERROR', 6000)));
  const ownerWs = await stomp(T.owner, { subscribe: ['/user/queue/notifications'] });
  check('CONNECT với JWT hợp lệ → CONNECTED', !!(await ownerWs.waitFor((f) => f.command === 'CONNECTED', 6000)));
  const recWs = await stomp(T.rec, { subscribe: ['/topic/role/RECEPTIONIST'] });
  check('lễ tân đăng ký kênh vai trò của mình được', !!(await recWs.waitFor((f) => f.command === 'CONNECTED', 6000)) && !recWs.frames.some((f) => f.command === 'ERROR'));
  const evilWs = await stomp(T.rec, { subscribe: ['/topic/role/ADMIN'] });
  check('đăng ký kênh vai trò khác (/topic/role/ADMIN) bị từ chối', !!(await evilWs.waitFor((f) => f.command === 'ERROR', 6000)));
  const sendWs = await stomp(T.rec, { send: true });
  check('client gửi dữ liệu (SEND) bị từ chối', !!(await sendWs.waitFor((f) => f.command === 'ERROR', 6000)));
  const pw = await newPet('WS', OWNER);
  const wsVisit = (await checkIn(pw, DOC4)).data.id;
  const msg = await ownerWs.waitFor((f) => f.command === 'MESSAGE' && /VISIT_CHECKED_IN/.test(f.body), 12000);
  check('chủ nuôi nhận thông báo VISIT_CHECKED_IN theo thời gian thực qua kênh riêng', !!msg && JSON.parse(msg.body).data.visitId === wsVisit);
  check('lễ tân nhận thông báo qua kênh vai trò', !!(await recWs.waitFor((f) => f.command === 'MESSAGE' && /VISIT_CHECKED_IN/.test(f.body), 12000)));
  const other = await stomp(T.owner2, { subscribe: ['/user/queue/notifications'] });
  await sleep(1500);
  check('chủ nuôi khác KHÔNG nhận thông báo của người khác', !other.frames.some((f) => f.command === 'MESSAGE' && f.body.includes(String(wsVisit))));
  [ownerWs, recWs, evilWs, sendWs, other].forEach((c) => c.close());
  await call('POST', `${CLIN}/pet-visits/${wsVisit}/cancel`, { token: T.rec, body: { reason: 'dọn' } });

  finish();
}

run(main);
