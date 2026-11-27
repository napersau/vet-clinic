// Kiểm thử NGOẠI LỆ PHÂN TÁN và PHI CHỨC NĂNG của v4.0 trên stack Docker thật (bước 8.3): EX-17, EX-18, EX-19, EX-20, NFR-09, NFR-10.
// Chạy: node e2e/v4_resilience.mjs   (cần: stack `docker compose up -d --build` đang chạy, mọi container healthy, dữ liệu seed docs/seed_demo_data.sql)
// Script TỰ tạo dữ liệu của riêng nó (thú cưng, đợt khám, một dịch vụ và một thuốc thử), nên chạy được sau các bộ e2e khác và chạy lại được.
// CẢNH BÁO: DỪNG rồi BẬT LẠI các container thật (rabbitmq-broker, redis, auth-service, appointment-service, clinical-service) bằng `docker compose`;
// mất khoảng 8-12 phút. Chỉ chạy trên môi trường thử nghiệm.
import { execSync } from 'node:child_process';
import { BILL, CLIN, PHARM, REPORTS, ROOT, RUN, brief, call, check, finish, login, redis, run, sleep, sql, step } from './lib/api.mjs';
import { seedIds, uuidSql } from './lib/ids.mjs';

const USERS = '/api/v1/users';
const CAT = '/api/v1/catalog';
const T = {};
let ID;

// ---------------------------------------------------------------- điều khiển container
const compose = (args) => execSync(`docker compose ${args}`, { cwd: ROOT, stdio: ['ignore', 'pipe', 'pipe'] }).toString().trim();
function health(service) {
  const container = compose(`ps -q ${service}`);
  if (!container) return 'missing';
  try { return execSync(`docker inspect -f "{{.State.Health.Status}}" ${container}`, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim(); } catch { return 'unknown'; }
}
async function waitHealthy(services, seconds = 240) {
  for (let t = 0; t < seconds; t += 3) {
    if (services.every((s) => health(s) === 'healthy')) return true;
    await sleep(3000);
  }
  return false;
}
const stop = (...services) => compose(`stop -t 1 ${services.join(' ')}`);
async function start(...services) { compose(`start ${services.join(' ')}`); return waitHealthy(services); }

// ---------------------------------------------------------------- luồng nghiệp vụ dùng chung
const vnDate = (offsetDays = 0) => new Date(Date.now() + 7 * 3600 * 1000 + offsetDays * 86400000).toISOString().slice(0, 10);
async function newPet(name) {
  const res = await call('POST', '/api/v1/pets', { token: T.rec, body: { ownerId: ID.user.khach_lan, speciesId: ID.species.DOG, name: `${name}-${RUN}` } });
  if (res.status !== 201) throw new Error(`Không tạo được thú cưng ${name}: ${brief(res)}`);
  return res.data.id;
}
const checkIn = (petId, doctorId, emergency = false) => call('POST', `${CLIN}/pet-visits/check-in`, { token: T.rec, body: { petId, doctorId, isEmergency: emergency, reasonForVisit: 'e2e v4' } });
const statementOf = async (visitId) => (await call('GET', `${CLIN}/pet-visits/${visitId}/service-invoices`, { token: T.rec })).data;
const payWhere = async (visitId, filter) => {
  const st = await statementOf(visitId);
  return call('POST', `${CLIN}/pet-visits/${visitId}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: st.invoices.filter((i) => i.paymentStatus === 'PENDING' && filter(i)).map((i) => i.id) } });
};
async function waitTickets(visitId, ms = 30000) {
  for (let t = 0; t < ms; t += 400) {
    const tickets = (await statementOf(visitId))?.tickets || [];
    if (tickets.length) return tickets;
    await sleep(400);
  }
  return [];
}
async function admit(petId, doctorId) {   // tiếp đón + nộp phí khám -> chờ phiếu Bác sĩ
  const id = (await checkIn(petId, doctorId)).data.id;
  const paid = await payWhere(id, (i) => i.category === 'EXAMINATION');
  if (paid.status !== 200) throw new Error(`Không thu được phí khám: ${brief(paid)}`);
  if (!(await waitTickets(id)).length) throw new Error(`Không có phiếu cho ca ${id}`);
  return id;
}
const startExam = (token, id) => call('POST', `${CLIN}/pet-visits/${id}/start-exam`, { token, body: { vitalSigns: { weightKg: 5, temperatureC: 38.5 } } });
const savePrescription = (token, visitId, medicineId, quantity) => call('PUT', `${CLIN}/pet-visits/${visitId}/prescription`, { token, body: { doctorNotes: 'e2e v4', items: [{ medicineId, quantity, dosage: '1 viên/lần', frequency: '2 lần/ngày', durationDays: 5 }] } });
const closeVisit = (token, id) => call('POST', `${CLIN}/pet-visits/${id}/close-visit`, { token, body: { finalDiagnosis: 'Viêm da', treatmentPlan: 'Dùng thuốc', doctorAdvice: 'Theo dõi' } });
async function draftOf(visitId, ms = 40000) {
  for (let t = 0; t < ms; t += 500) {
    const res = await call('GET', `${BILL}/medicine-invoices?visitId=${visitId}`, { token: T.rec });
    if (res.status === 200 && res.data.length) return res.data[0];
    await sleep(500);
  }
  return null;
}
const ticketHash = (ticketId, field) => redis('hget', `ticket:${ticketId}`, field).replace('(nil)', '');
const cancelVisit = (id) => call('POST', `${CLIN}/pet-visits/${id}/cancel`, { token: T.rec, body: { reason: 'dọn dẹp e2e v4' } });
const poll = async (fn, ms = 30000, every = 500) => { for (let t = 0; t < ms; t += every) { const v = await fn(); if (v) return v; await sleep(every); } return null; };
const outbox = (db, where) => Number(sql(`SELECT COUNT(*) FROM ${db}.outbox_events WHERE ${where}`));
const CDB = 'pet_clinic_clinical_db'; const BDB = 'pet_clinic_billing_pharmacy_db'; const RDB = 'pet_clinic_reporting_db';

async function main() {
  for (const [key, username] of Object.entries({ admin: 'admin', rec: 'letan_hoa', mai: 'bs_mai', an: 'bs_an', owner: 'khach_lan' })) T[key] = (await login(username)).accessToken;
  ID = await seedIds();
  const MAI = ID.user.bs_mai; const AN = ID.user.bs_an;
  // Thuốc RIÊNG cho các luồng kê đơn (EX-18, EX-19): không phụ thuộc tồn kho của thuốc seed mà các bộ e2e khác đã làm cạn
  const rx = await call('POST', `${PHARM}/medicines`, { token: T.admin, body: { code: `RXV${RUN}`, name: `Thuốc kê đơn e2e v4 ${RUN}`, categoryCode: 'ANTIBIOTIC', dosageForm: 'Viên', baseUnit: 'Viên', packagingSpec: 'Hộp 10 viên', salePrice: 10000, vatRate: 0.05, initialStock: 200, minStockAlert: 5 } });
  if (rx.status !== 201) throw new Error(`Không tạo được thuốc kê đơn thử: ${brief(rx)}`);
  const AMOX = rx.data.id;
  // Dọn trạng thái dở dang của hai Bác sĩ dùng ở đây (các bộ e2e khác có thể để lại ca đang khám)
  sql(`UPDATE ${CDB}.pet_visits SET status = 'CANCELLED' WHERE doctor_id IN (${uuidSql(MAI)}, ${uuidSql(AN)}) AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY', 'WAITING_RESULTS')`);

  // =====================================================================================================================
  step('EX-17: Bác sĩ bị khóa khi còn ca đang chờ trong hàng đợi');
  const [pa, pb] = [await newPet('ex17a'), await newPet('ex17b')];
  const e1 = await admit(pa, MAI); const e2 = await admit(pb, MAI);
  const t1 = (await statementOf(e1)).tickets[0]; const t2 = (await statementOf(e2)).tickets[0];
  check('hai ca đã nộp phí có phiếu DOCTOR WAITING trong hàng đợi Bác sĩ Mai', t1.status === 'WAITING' && t2.status === 'WAITING' && ticketHash(t1.id, 'doctorId').length > 0, JSON.stringify([t1, t2]));
  check('Admin khóa Bác sĩ Mai → 200', (await call('POST', `${USERS}/${MAI}/lock`, { token: T.admin })).status === 200);
  const locked = await call('POST', `${CLIN}/pet-visits/check-in`, { token: T.rec, body: { petId: await newPet('ex17c'), doctorId: MAI, isEmergency: false, reasonForVisit: 'e2e' } });
  check('xếp ca mới cho Bác sĩ đã khóa bị từ chối NGAY, kể cả khi sự kiện chưa tới clinical (CLIN_1316)', locked.status >= 400 && locked.code === 'CLIN_1316', brief(locked));
  const released = await poll(async () => { const a = (await call('GET', `${CLIN}/pet-visits/${e1}`, { token: T.rec })).data; const b = (await call('GET', `${CLIN}/pet-visits/${e2}`, { token: T.rec })).data; return a?.doctorId == null && b?.doctorId == null; });
  check('auth → outbox → RabbitMQ → clinical: hai ca WAITING được gỡ phân công', Boolean(released));
  check('phiếu về danh sách chờ phân công nhưng GIỮ NGUYÊN số phiếu và thứ tự', ticketHash(t1.id, 'doctorId') === '' && ticketHash(t1.id, 'ticketNo') === String(t1.ticketNo) && ticketHash(t2.id, 'ticketNo') === String(t2.ticketNo) && t1.ticketNo < t2.ticketNo, `${ticketHash(t1.id, 'doctorId')} ${ticketHash(t1.id, 'ticketNo')}`);
  check('Admin mở khóa → Lễ tân phân công lại được, phiếu đi theo Bác sĩ và vẫn giữ số', (await call('POST', `${USERS}/${MAI}/unlock`, { token: T.admin })).status === 200
    && (await call('POST', `${CLIN}/pet-visits/${e1}/assign-doctor`, { token: T.rec, body: { doctorId: MAI } })).status === 200
    && Boolean(await poll(() => ticketHash(t1.id, 'doctorId') === MAI)) && ticketHash(t1.id, 'ticketNo') === String(t1.ticketNo));
  for (const v of [e1, e2]) await cancelVisit(v);

  // =====================================================================================================================
  step('EX-18 / NFR-09 (1): RabbitMQ dừng ngay sau khi thu tiền, sau khi chốt ca và khi tiếp đón; bật lại thì không mất gì');
  const [pA, pB, pC, pD] = [await newPet('ex18a'), await newPet('ex18b'), await newPet('ex18c'), await newPet('ex18d')];
  const vD = await admit(pD, AN);   // ca có đơn thuốc, đã vào khám, sẽ chốt khi RabbitMQ đang tắt
  check('chuẩn bị: Bác sĩ An bắt đầu khám và lưu đơn thuốc', (await startExam(T.an, vD)).status === 200 && (await savePrescription(T.an, vD, AMOX, 2)).status === 200);
  const vA = (await checkIn(pA, MAI)).data.id; const vB = (await checkIn(pB, MAI)).data.id;
  stop('rabbitmq-broker');
  await sleep(1500);
  const payA = await payWhere(vA, (i) => i.category === 'EXAMINATION');
  await sleep(1300);   // mốc thu tiền cách nhau để kiểm tra thứ tự phát phiếu theo giờ thu
  const payB = await payWhere(vB, (i) => i.category === 'EXAMINATION');
  check('RabbitMQ tắt: thu phí khám vẫn THÀNH CÔNG (200), hóa đơn PAID', payA.status === 200 && payB.status === 200 && (await statementOf(vA)).invoices.every((i) => i.paymentStatus === 'PAID'), `${brief(payA)} ${brief(payB)}`);
  const closed = await closeVisit(T.an, vD);
  check('RabbitMQ tắt: chốt ca có đơn thuốc vẫn thành công (200)', closed.status === 200, brief(closed));
  const checkedIn = await checkIn(pC, MAI);
  check('RabbitMQ tắt: tiếp đón khách mới vẫn thành công (201)', checkedIn.status === 201, brief(checkedIn));
  const vC = checkedIn.data?.id;
  await sleep(3000);
  check('sự kiện nằm chờ trong outbox (PENDING), chưa phát phiếu và chưa có hóa đơn thuốc nháp',
    outbox(CDB, `status = 'PENDING' AND routing_key = 'invoice.service_paid' AND aggregate_id = ${uuidSql(vA)}`) === 1
    && outbox(CDB, `status = 'PENDING' AND routing_key = 'prescription.created' AND aggregate_id = ${uuidSql(vD)}`) === 1
    && (await statementOf(vA)).tickets.length === 0
    && ((await call('GET', `${BILL}/medicine-invoices?visitId=${vD}`, { token: T.rec })).data || []).length === 0);
  // relay đang chờ lần kết nối đầu (connection-timeout 5 giây) rồi mới ghi nhận lần lỗi: chờ có thời hạn thay vì đọc ngay
  check('relay đã THỬ gửi và lùi dần (attempt_count > 0, chưa FAILED)', Boolean(await poll(() => Number(sql(`SELECT MAX(attempt_count) FROM ${CDB}.outbox_events WHERE status = 'PENDING' AND aggregate_id = ${uuidSql(vA)}`)) > 0, 45000, 1000)) && outbox(CDB, "status = 'FAILED'") === 0);
  check('RabbitMQ bật lại và healthy', await start('rabbitmq-broker'));
  const ticketsA = await waitTickets(vA, 120000); const ticketsB = await waitTickets(vB, 120000);
  check('sau khi hồi phục: phiếu phát đủ cho cả hai ca đã thu tiền lúc RabbitMQ tắt', ticketsA.length === 1 && ticketsB.length === 1, `${ticketsA.length} ${ticketsB.length}`);
  check('phiếu theo THỜI ĐIỂM THU TIỀN: ca thu trước có số phiếu nhỏ hơn (khách không bị tụt hạng)', ticketsA[0] && ticketsB[0] && ticketsA[0].ticketNo < ticketsB[0].ticketNo, `${ticketsA[0]?.ticketNo} ${ticketsB[0]?.ticketNo}`);
  const draft = await draftOf(vD, 120000);
  check('NFR-09: đã chốt ca có đơn thuốc thì CHẮC CHẮN có hóa đơn thuốc nháp (sau khi RabbitMQ hồi phục)', Boolean(draft) && draft.paymentStatus === 'PENDING', JSON.stringify(draft));
  const visitCFact = await poll(() => Number(sql(`SELECT COUNT(*) FROM ${RDB}.visit_facts WHERE visit_id = ${uuidSql(vC)}`)) === 1, 120000, 1000);
  check('tiếp đón lúc RabbitMQ tắt cũng đến được reporting (visit_facts) sau khi hồi phục', Boolean(visitCFact));
  check('mọi dòng outbox của clinical và billing về PUBLISHED, không dòng FAILED', Boolean(await poll(() => outbox(CDB, "status <> 'PUBLISHED'") + outbox(BDB, "status <> 'PUBLISHED'") === 0, 120000, 1000)));
  for (const v of [vA, vB, vC]) await cancelVisit(v);
  const payD = await call('POST', `${BILL}/medicine-invoices/${draft?.id}/confirm-payment`, { token: T.rec });
  await call('POST', `${CLIN}/pet-visits/${vD}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: [] } });
  check('hóa đơn thuốc nháp thu được bình thường sau hồi phục', payD.status === 200, brief(payD));

  step('EX-18 (2): Redis dừng ngay sau khi thu tiền');
  const vE = (await checkIn(await newPet('ex18e'), MAI)).data.id;
  stop('redis');
  await sleep(1200);
  const redisDown = await call('GET', `${CLIN}/pet-visits/my-queue`, { token: T.mai });
  check('Redis tắt: đọc hàng đợi trả lỗi tạm thời rõ ràng 503 CLIN_1318 (không treo, không 500)', redisDown.status === 503 && redisDown.code === 'CLIN_1318', brief(redisDown));
  const payE = await payWhere(vE, (i) => i.category === 'EXAMINATION');
  check('Redis tắt: thu phí khám vẫn THÀNH CÔNG (MySQL là nguồn sự thật), hóa đơn PAID', payE.status === 200 && (await statementOf(vE)).invoices.every((i) => i.paymentStatus === 'PAID'), brief(payE));
  check('Redis bật lại (AOF) và healthy', await start('redis'));
  const ticketsE = await waitTickets(vE, 200000);   // listener thử lại 3 lần; nếu hết lượt thì QueueReconcileJob (60 giây) bù phiếu thiếu
  check('sau khi hồi phục: phiếu được phát, ĐÚNG MỘT phiếu', ticketsE.length === 1 && ticketsE[0].status === 'WAITING', JSON.stringify(ticketsE));
  check('hàng đợi đọc lại bình thường (200)', (await call('GET', `${CLIN}/pet-visits/my-queue`, { token: T.mai })).status === 200);
  await cancelVisit(vE);

  // =====================================================================================================================
  step('EX-19 / NFR-09 (2): sự kiện giao hai lần (thu tiền dịch vụ, đơn thuốc, thu tiền thuốc) không tạo gì thêm');
  const vF = await admit(await newPet('ex19'), AN);
  await startExam(T.an, vF); await savePrescription(T.an, vF, AMOX, 3); await closeVisit(T.an, vF);
  const draftF = await draftOf(vF);
  check('chốt ca → hóa đơn thuốc nháp', Boolean(draftF));
  check('thu tiền thuốc → 200', (await call('POST', `${BILL}/medicine-invoices/${draftF.id}/confirm-payment`, { token: T.rec })).status === 200);
  check('thu nốt (T10) → ca COMPLETED', (await call('POST', `${CLIN}/pet-visits/${vF}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: [] } })).status === 200);
  await poll(() => Number(sql(`SELECT COUNT(*) FROM ${RDB}.revenue_facts WHERE visit_id = ${uuidSql(vF)}`)) >= 2, 60000, 1000);
  const snapshot = () => ({
    histories: Number(sql(`SELECT COUNT(*) FROM ${CDB}.queue_ticket_histories WHERE visit_id = ${uuidSql(vF)}`)),
    drafts: Number(sql(`SELECT COUNT(*) FROM ${BDB}.medicine_invoices WHERE visit_id = ${uuidSql(vF)}`)),
    revenueRows: Number(sql(`SELECT COUNT(*) FROM ${RDB}.revenue_facts WHERE visit_id = ${uuidSql(vF)}`)),
    revenueNet: sql(`SELECT SUM(net_amount) FROM ${RDB}.revenue_facts WHERE visit_id = ${uuidSql(vF)}`),
    seq: redis('get', `queue:seq:${vnDate().replaceAll('-', '')}:DOCTOR`),
    stock: sql(`SELECT quantity_on_hand FROM ${BDB}.medicine_stocks WHERE medicine_id = ${uuidSql(AMOX)}`),
  });
  const before = snapshot();
  const eventIds = sql(`SELECT HEX(event_id) FROM ${CDB}.outbox_events WHERE aggregate_id IN (${uuidSql(vF)}) AND routing_key IN ('invoice.service_paid', 'prescription.created', 'visit.completed', 'visit.checked_in') `
    + `UNION SELECT HEX(event_id) FROM ${BDB}.outbox_events WHERE aggregate_id = ${uuidSql(draftF.id)} AND routing_key = 'pharmacy.medicine_invoice_paid'`).split('\n').filter(Boolean);
  check('có đủ 5 sự kiện outbox của ca (tiếp đón, thu dịch vụ, đơn thuốc, thu thuốc, hoàn tất)', eventIds.length === 5, eventIds.length);
  const requeue = `SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP()`;
  sql(`UPDATE ${CDB}.outbox_events ${requeue} WHERE aggregate_id = ${uuidSql(vF)} AND routing_key IN ('invoice.service_paid', 'prescription.created', 'visit.completed', 'visit.checked_in')`);
  sql(`UPDATE ${BDB}.outbox_events ${requeue} WHERE aggregate_id = ${uuidSql(draftF.id)} AND routing_key = 'pharmacy.medicine_invoice_paid'`);
  await poll(() => outbox(CDB, "status <> 'PUBLISHED'") + outbox(BDB, "status <> 'PUBLISHED'") === 0, 60000, 1000);
  await sleep(5000);   // cho consumer xử lý xong lần giao thứ hai
  const after = snapshot();
  check('EX-19: giao lại cả 5 sự kiện → không phát phiếu thứ hai, không thêm hóa đơn thuốc, doanh thu không đếm hai lần, kho không trừ thêm', JSON.stringify(before) === JSON.stringify(after), `${JSON.stringify(before)} → ${JSON.stringify(after)}`);
  const dup = Number(sql(`SELECT COUNT(*) FROM (SELECT event_id, consumer FROM ${CDB}.processed_events GROUP BY event_id, consumer HAVING COUNT(*) > 1) x`))
    + Number(sql(`SELECT COUNT(*) FROM (SELECT event_id, consumer FROM ${BDB}.processed_events GROUP BY event_id, consumer HAVING COUNT(*) > 1) x`))
    + Number(sql(`SELECT COUNT(*) FROM (SELECT event_id, consumer FROM ${RDB}.processed_events GROUP BY event_id, consumer HAVING COUNT(*) > 1) x`));
  check('processed_events: mỗi (sự kiện, listener) đúng một dòng ở cả clinical, billing và reporting', dup === 0
    && Number(sql(`SELECT COUNT(*) FROM ${BDB}.processed_events WHERE consumer = 'billing.prescription-created' AND event_id = (SELECT event_id FROM ${CDB}.outbox_events WHERE aggregate_id = ${uuidSql(vF)} AND routing_key = 'prescription.created')`)) === 1);

  // =====================================================================================================================
  step('EX-20: đổi giá khi ca đang mở (dịch vụ chụp giá lúc chỉ định, thuốc chốt giá lúc thu)');
  const svc = await call('POST', `${CAT}/services`, { token: T.admin, body: { code: `RES${RUN}`, name: `Thủ thuật e2e ${RUN}`, categoryCode: 'PROCEDURE', price: 200000, vatRate: 0, description: 'e2e v4 EX-20' } });
  check('tạo dịch vụ thử (giá 200.000)', svc.status === 201, brief(svc));
  const vG = await admit(await newPet('ex20g'), AN);
  await startExam(T.an, vG);
  check('chỉ định dịch vụ thử cho ca G', (await call('POST', `${CLIN}/pet-visits/${vG}/in-room-services`, { token: T.an, body: { services: [{ serviceId: svc.data.id, quantity: 1 }] } })).status < 300);
  const invoiceOf = async (v) => (await statementOf(v)).invoices.find((i) => i.serviceName?.includes('Thủ thuật e2e'));
  check('hóa đơn dịch vụ chụp giá lúc chỉ định: 200.000', Number((await invoiceOf(vG))?.unitPrice) === 200000);
  await sleep(1200);
  const changed = await call('POST', `${CAT}/services/${svc.data.id}/prices`, { token: T.admin, body: { price: 220000, vatRate: 0, reason: 'e2e EX-20' } });
  check('Admin đổi giá thành 220.000 có hiệu lực ngay → 201', changed.status === 201, brief(changed));
  check('EX-20: hóa đơn đã lập của ca đang mở VẪN 200.000 (khách trả giá lúc chỉ định)', Number((await invoiceOf(vG))?.unitPrice) === 200000);
  await call('POST', `${CLIN}/pet-visits/${vG}/cancel-exam`, { token: T.an, body: { reason: 'dọn dẹp e2e v4' } });
  const vH = await admit(await newPet('ex20h'), AN);
  await startExam(T.an, vH);
  await call('POST', `${CLIN}/pet-visits/${vH}/in-room-services`, { token: T.an, body: { services: [{ serviceId: svc.data.id, quantity: 1 }] } });
  check('chỉ định MỚI sau khi đổi giá lấy giá mới 220.000', Number((await invoiceOf(vH))?.unitPrice) === 220000);
  check('lịch sử giá còn nguyên: 2 dòng, dòng cũ đã đóng và không bị sửa số tiền', Number(sql(`SELECT COUNT(*) FROM ${CDB}.service_prices WHERE service_id = ${uuidSql(svc.data.id)}`)) === 2
    && sql(`SELECT price FROM ${CDB}.service_prices WHERE service_id = ${uuidSql(svc.data.id)} AND effective_to IS NOT NULL`) === '200000.00');
  await call('POST', `${CLIN}/pet-visits/${vH}/cancel-exam`, { token: T.an, body: { reason: 'dọn dẹp e2e v4' } });
  await call('PUT', `${CAT}/services/${svc.data.id}`, { token: T.admin, body: { active: false } });

  const med = await call('POST', `${PHARM}/medicines`, { token: T.admin, body: { code: `MRS${RUN}`, name: `Thuốc e2e v4 ${RUN}`, categoryCode: 'ANTIBIOTIC', dosageForm: 'Viên', baseUnit: 'Viên', packagingSpec: 'Hộp 10 viên', salePrice: 12000, vatRate: 0.1, initialStock: 20, minStockAlert: 2 } });
  check('tạo thuốc thử (giá 12.000, VAT 10%)', med.status === 201, brief(med));
  const vI = await admit(await newPet('ex20i'), AN);
  await startExam(T.an, vI); await savePrescription(T.an, vI, med.data.id, 2); await closeVisit(T.an, vI);
  const draftI = await draftOf(vI);
  check('hóa đơn thuốc nháp theo giá lúc tạo: 2 × 12.000 × 1,1 = 26.400', Number(draftI?.payableAmount) === 26400, JSON.stringify(draftI));
  await sleep(1200);
  check('Admin đổi giá thuốc thành 15.000, VAT 5% có hiệu lực ngay → 201', (await call('POST', `${PHARM}/medicines/${med.data.id}/prices`, { token: T.admin, body: { salePrice: 15000, vatRate: 0.05, reason: 'e2e EX-20' } })).status === 201);
  const paidI = await call('POST', `${BILL}/medicine-invoices/${draftI.id}/confirm-payment`, { token: T.rec });
  check('EX-20: thu tiền thuốc → giá chốt theo giá HIỆN HÀNH lúc bán: 2 × 15.000 × 1,05 = 31.500', paidI.status === 200 && Number(paidI.data.payableAmount) === 31500 && Number(paidI.data.items[0].unitPrice) === 15000, JSON.stringify(paidI.data).slice(0, 300));
  check('lịch sử giá thuốc: 2 dòng, dòng cũ (12.000) đã đóng', Number(sql(`SELECT COUNT(*) FROM ${BDB}.medicine_prices WHERE medicine_id = ${uuidSql(med.data.id)}`)) === 2
    && sql(`SELECT sale_price FROM ${BDB}.medicine_prices WHERE medicine_id = ${uuidSql(med.data.id)} AND effective_to IS NOT NULL`) === '12000.00');
  await call('POST', `${CLIN}/pet-visits/${vI}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: [] } });

  // =====================================================================================================================
  step('NFR-10: hàng đợi trên Redis: 10 thao tác đồng thời, ≤ 50 ms, không xung đột; danh sách không gọi chéo service');
  const queuePets = []; for (let i = 0; i < 10; i += 1) queuePets.push(await newPet(`nfr10-${i}`));
  const queueVisits = await Promise.all(queuePets.map((p) => admit(p, MAI)));   // 10 quầy cùng thu phí khám: phát phiếu đồng thời
  const queueTickets = await Promise.all(queueVisits.map(async (v) => (await statementOf(v)).tickets[0]));
  const numbers = queueTickets.map((t) => t.ticketNo).sort((a, b) => a - b);
  check('10 ca thu phí đồng thời → 10 phiếu, số phiếu KHÔNG trùng', queueTickets.length === 10 && new Set(numbers).size === 10, numbers.join(','));
  redis('config', 'set', 'slowlog-log-slower-than', '50000'); redis('slowlog', 'reset'); redis('config', 'resetstat');
  const timed = await Promise.all(queueTickets.map(async (t) => { const t0 = performance.now(); const res = await call('POST', `${CLIN}/queue-tickets/${t.id}/call`, { token: T.mai }); return { res, ms: performance.now() - t0 }; }));
  const ms = timed.map((x) => x.ms).sort((a, b) => a - b);
  check('10 lượt "Gọi" ĐỒNG THỜI trên 10 phiếu của một Bác sĩ: tất cả 200, không có SYS_1005 (không xung đột phiên bản)', timed.every((x) => x.res.status === 200) && !timed.some((x) => x.res.code === 'SYS_1005'), timed.map((x) => brief(x.res)).join(' | '));
  console.log(`  (độ trễ API đầu-cuối của 10 lượt Gọi đồng thời: nhỏ nhất ${ms[0].toFixed(0)} ms, trung vị ${ms[5].toFixed(0)} ms, lớn nhất ${ms[9].toFixed(0)} ms)`);
  const evalStats = redis('info', 'commandstats').split('\n').filter((l) => /^cmdstat_(evalsha|eval):/.test(l.trim()));
  const perCall = evalStats.map((l) => Number(/usec_per_call=([\d.]+)/.exec(l)?.[1])).filter((x) => !Number.isNaN(x));
  check('Redis: mỗi lệnh Lua (EVALSHA) tốn ≤ 50 ms (đo bằng commandstats của chính Redis)', perCall.length > 0 && perCall.every((x) => x <= 50000), evalStats.join(' | '));
  check('Redis: SLOWLOG (ngưỡng 50 ms) trống sau các thao tác hàng đợi', redis('slowlog', 'len') === '0', redis('slowlog', 'get', '3'));
  redis('config', 'set', 'slowlog-log-slower-than', '10000');
  check('API "Gọi" không chậm bất thường (lớn nhất dưới 2 giây trên máy dev)', ms[9] < 2000, ms.join(','));
  const startRace = await Promise.all(Array.from({ length: 10 }, () => startExam(T.mai, queueVisits[0])));
  check('10 yêu cầu "Bắt đầu khám" đồng thời trên cùng một ca: đúng 1 thành công, không SYS_1005 / lỗi 500', startRace.filter((r) => r.status === 200).length === 1 && !startRace.some((r) => r.code === 'SYS_1005' || r.status >= 500), startRace.map((r) => brief(r)).join(' | '));
  await call('POST', `${CLIN}/pet-visits/${queueVisits[0]}/cancel-exam`, { token: T.mai, body: { reason: 'dọn dẹp e2e v4' } });
  for (const v of queueVisits.slice(1)) await cancelVisit(v);

  // danh sách: cột snapshot nên KHÔNG gọi sang auth / appointment / clinical (chứng minh bằng cách tắt các service đó)
  const day = vnDate(6);
  const apptPet = await newPet('nfr10-appt');
  const booked = await call('POST', '/api/v1/appointments', { token: T.owner, body: { petId: apptPet, appointmentDate: day, startTime: '11:00', reason: 'e2e v4 NFR-10' } });
  check('chuẩn bị: đặt một lịch hẹn để kiểm tra danh sách lịch hẹn', booked.status === 201, brief(booked));
  const waiting = await admit(await newPet('nfr10-wait'), MAI);
  stop('auth-service', 'appointment-service');
  await sleep(1500);
  const timeList = async (url, token) => { const t0 = performance.now(); const res = await call('GET', url, { token }); return { res, ms: performance.now() - t0 }; };
  const l1 = await timeList(`${CLIN}/pet-visits?status=WAITING&size=50`, T.rec);
  const l2 = await timeList(`${CLIN}/pet-visits/my-queue`, T.mai);
  const l3 = await timeList(`${CLIN}/pet-visits?size=50`, T.admin);
  check('auth + appointment TẮT: danh sách đợt khám (lọc trạng thái, tất cả) và hàng đợi Bác sĩ vẫn 200 (0 lời gọi chéo service)', [l1, l2, l3].every((x) => x.res.status === 200), [l1, l2, l3].map((x) => brief(x.res)).join(' | '));
  check('các danh sách đó có TÊN Bác sĩ và thú cưng (đọc từ cột snapshot) và trả nhanh (không chờ timeout Feign 2 giây)',
    l1.res.data.items.some((v) => v.doctorName && v.petName) && l2.res.data.some((v) => v.doctorName && v.petName) && [l1, l2, l3].every((x) => x.ms < 1500), [l1, l2, l3].map((x) => x.ms.toFixed(0)).join(','));
  const detail = await timeList(`${CLIN}/pet-visits/${waiting}`, T.rec);
  check('chi tiết MỘT ca vẫn trả được khi auth tắt (nhãn "Đã khóa" / số phòng chỉ là phần làm giàu, để trống)', detail.res.status === 200 && detail.res.data.doctorName, brief(detail.res));
  check('khởi động lại auth + appointment', await start('auth-service', 'appointment-service'));
  stop('clinical-service', 'auth-service');
  await sleep(1500);
  const a1 = await timeList('/api/v1/appointments?size=50', T.rec);
  check('clinical + auth TẮT: danh sách lịch hẹn vẫn 200, có tên thú cưng và chủ nuôi (snapshot trong lịch hẹn), trả nhanh',
    a1.res.status === 200 && a1.res.data.items.some((a) => a.petName && a.ownerName) && a1.ms < 1500, `${brief(a1.res)} ${a1.ms.toFixed(0)} ms`);
  check('khởi động lại clinical + auth', await start('clinical-service', 'auth-service'));
  await poll(async () => (await call('GET', `${CLIN}/pet-visits/queue`, { token: T.rec })).status === 200, 120000, 2000);
  await cancelVisit(waiting);
  await call('POST', `/api/v1/appointments/${booked.data?.id}/cancel`, { token: T.owner, body: { reason: 'dọn dẹp e2e v4' } });

  const t0 = performance.now();
  const report = await call('GET', `${REPORTS}/revenue/by-category?month=${vnDate().slice(0, 7)}`, { token: T.admin });
  const reportMs = performance.now() - t0;
  check('NFR-10: báo cáo doanh thu theo nhóm trong tháng ≤ 2 giây (một CSDL reporting, không gọi service khác)', report.status === 200 && reportMs < 2000, `${brief(report)} ${reportMs.toFixed(0)} ms`);

  // =====================================================================================================================
  step('Tổng kết sau mọi lần dừng / bật: hệ thống nhất quán');
  const unhealthy = ['mysql-server', 'rabbitmq-broker', 'redis', 'auth-service', 'appointment-service', 'clinical-service', 'billing-pharmacy-service', 'notification-service', 'reporting-service', 'api-gateway'].filter((s) => health(s) !== 'healthy');
  check('mọi container vẫn healthy sau các lần dừng / bật', unhealthy.length === 0, unhealthy.join(','));
  check('không còn dòng outbox PENDING / FAILED ở clinical, billing, appointment, auth', Boolean(await poll(() => outbox(CDB, "status <> 'PUBLISHED'") + outbox(BDB, "status <> 'PUBLISHED'") + outbox('pet_clinic_appointment_db', "status <> 'PUBLISHED'") + outbox('pet_clinic_auth_db', "status <> 'PUBLISHED'") === 0, 90000, 1000)));
  check('bất biến kho: tồn = tổng nhật ký kho của thuốc kê đơn thử', sql(`SELECT (SELECT quantity_on_hand FROM ${BDB}.medicine_stocks WHERE medicine_id = ${uuidSql(AMOX)}) - (SELECT SUM(quantity) FROM ${BDB}.inventory_transactions WHERE medicine_id = ${uuidSql(AMOX)})`) === '0');

  finish();
}

run(main);
