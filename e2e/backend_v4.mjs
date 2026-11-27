// Kiểm thử backend v4.0 qua Gateway (HTTP thật): luồng chuẩn Task 5.4 + các luồng dễ lỗi.
// Chạy: node e2e/backend_v4.mjs   (cần: stack đang chạy — `docker compose up -d --build` hoặc 6 service + hạ tầng ở dev — và dữ liệu seed
//   `docs/seed_demo_data.sql` mới nạp (lộ trình v4), id tra theo mã ổn định qua e2e/lib/ids.mjs; luồng chuẩn cần Clavamox tồn ≥ 10 và Lu chưa có đợt khám mở)
// CẢNH BÁO: tạo dữ liệu thật (đợt khám, hóa đơn, lịch hẹn). Chỉ chạy trên môi trường thử nghiệm; muốn chạy lại hãy `docker compose down -v`.
import { BILL, CLIN, PHARM, REPORTS, brief, call, check, finish, login, redis, run, sleep, sql, step } from './lib/api.mjs';
import { medicineIdSql, seedIds, uuidSql } from './lib/ids.mjs';

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
let ID;   // id seed tra theo mã ổn định (lib/ids.mjs)

const T = {};   // token theo vai trò
async function stockOf(medicineId) { return (await call('GET', `${PHARM}/medicines/${medicineId}`, { token: T.admin })).data.stockQuantity; }
async function newPet(name, speciesId = ID.species.DOG) {
  const res = await call('POST', '/api/v1/pets', { token: T.rec, body: { ownerId: ID.user.khach_lan, speciesId, name } });
  if (res.status !== 201) throw new Error(`Không tạo được thú cưng ${name}: ${res.status} ${res.message}`);
  return res.data.id;
}
const checkIn = (petId, doctorId, emergency = false) => call('POST', `${CLIN}/pet-visits/check-in`, { token: T.rec, body: { petId, doctorId, isEmergency: emergency, reasonForVisit: 'e2e' } });
const startExam = (token, id) => call('POST', `${CLIN}/pet-visits/${id}/start-exam`, { token, body: { vitalSigns: { weightKg: 5, temperatureC: 38.5 } } });
const visitOf = async (id) => (await call('GET', `${CLIN}/pet-visits/${id}`, { token: T.rec })).data;
const labQueue = async () => (await call('GET', `${CLIN}/lab-queue`, { token: T.lab })).data;
const imagingQueue = async () => (await call('GET', `${CLIN}/imaging-queue`, { token: T.img })).data;
const detailOf = (queue, visitId) => queue.find((item) => item.visitId === visitId)?.serviceDetailId;
const rxBody = (medicineId, quantity) => ({ doctorNotes: 'e2e', items: [{ medicineId, quantity, dosage: '1 viên/lần', frequency: '2 lần/ngày', durationDays: 5 }] });
// v4.0 (5.2): đơn thuốc thuộc clinical, lưu cả đơn bằng PUT
const savePrescription = (token, visitId, medicineId, quantity) => call('PUT', `${CLIN}/pet-visits/${visitId}/prescription`, { token, body: rxBody(medicineId, quantity) });
// Hóa đơn thuốc NHÁP do billing tự tạo từ PrescriptionCreatedEvent sau khi chốt ca (qua outbox + RabbitMQ, thường dưới 1 giây)
async function draftOf(visitId) {
  for (let i = 0; i < 40; i += 1) {
    const res = await call('GET', `${BILL}/medicine-invoices?visitId=${visitId}`, { token: T.rec });
    if (res.status === 200 && res.data.length) return res.data[0];
    await sleep(500);
  }
  return null;
}
const close = (token, id) => call('POST', `${CLIN}/pet-visits/${id}/close-visit`, { token, body: { finalDiagnosis: 'Viêm da', treatmentPlan: 'Dùng thuốc', doctorAdvice: 'Theo dõi' } });
async function labResults(detailId) {
  const form = (await call('GET', `${CLIN}/service-details/${detailId}/lab-form`, { token: T.lab })).data;
  return call('POST', `${CLIN}/lab-results/${detailId}`, { token: T.lab, body: { results: form.map((item) => ({ labTestItemId: item.id, resultValue: 10 })) } });
}
function imagingForm() { const form = new FormData(); form.append('description', 'Bình thường'); form.append('conclusion', 'Không bất thường'); form.append('files', new Blob([PNG], { type: 'image/png' }), 'sa.png'); return form; }
const statementOf = async (visitId) => (await call('GET', `${CLIN}/pet-visits/${visitId}/service-invoices`, { token: T.rec })).data;
const pay = (visitId, ids) => call('POST', `${CLIN}/pet-visits/${visitId}/service-invoices/confirm-payment`, { token: T.rec, body: { invoiceIds: ids } });
async function payWhere(visitId, filter) {
  const st = await statementOf(visitId);
  return pay(visitId, st.invoices.filter((i) => i.paymentStatus === 'PENDING' && filter(i)).map((i) => i.id));
}
const payAllServices = (visitId) => payWhere(visitId, () => true);
// Từ v4.0 phiếu xếp hàng phát SAU khi thu tiền / tiếp đón cấp cứu (sự kiện qua outbox + RabbitMQ + Redis, thường dưới 1 giây): chờ có thời hạn, không đọc ngay
async function waitTickets(visitId, predicate, ms = 20000) {
  for (let t = 0; t < ms; t += 300) {
    const tickets = (await call('GET', `${CLIN}/pet-visits/${visitId}/service-invoices`, { token: T.rec })).data?.tickets || [];
    if (predicate(tickets)) return tickets;
    await sleep(300);
  }
  return [];
}
// Thu trước (17/11/2026): phí khám thu xong mới có phiếu DOCTOR; tiền XN / CĐHA thu xong mới có phiếu phòng (phiếu phát sau thu tiền, nên chờ)
async function payExamFee(visitId) {
  const res = await payWhere(visitId, (i) => i.category === 'EXAMINATION');
  if (res.status === 200) await waitTickets(visitId, (ts) => ts.some((t) => t.queueType === 'DOCTOR'));
  return res;
}
async function payParaclinical(visitId) {
  const types = [...new Set((await statementOf(visitId)).invoices.filter((i) => i.paymentStatus === 'PENDING' && (i.category === 'LABORATORY' || i.category === 'IMAGING')).map((i) => i.category))];
  const res = await payWhere(visitId, (i) => i.category === 'LABORATORY' || i.category === 'IMAGING');
  if (res.status === 200) await waitTickets(visitId, (ts) => types.every((q) => ts.some((t) => t.queueType === q)));
  return res;
}
async function admit(petId, doctorId, emergency = false) {   // tiếp đón + nộp phí khám -> có phiếu, vào hàng đợi Bác sĩ
  const id = (await checkIn(petId, doctorId, emergency)).data.id;
  if (!emergency) await payExamFee(id);
  return id;
}
const doctorCallNext = (token) => call('POST', `${CLIN}/queue-tickets/call-next?queueType=DOCTOR`, { token });
const callTicket = (token, ticketId) => call('POST', `${CLIN}/queue-tickets/${ticketId}/call`, { token });
const startTicket = (token, ticketId) => call('POST', `${CLIN}/queue-tickets/${ticketId}/start`, { token });
const ticketOf = (queue, visitId) => queue.find((item) => item.visitId === visitId)?.ticketId;
const myQueue = async (token) => (await call('GET', `${CLIN}/pet-visits/my-queue`, { token })).data;

async function main() {
  const tokens = { admin: 'admin', doc2: 'bs_tien', doc3: 'bs_an', rec: 'letan_hoa', lab: 'ktv_lab_minh', img: 'ktv_cdha_dung', owner: 'khach_lan' };
  const sessions = {};
  for (const [key, username] of Object.entries(tokens)) { sessions[key] = await login(username); T[key] = sessions[key].accessToken; }
  ID = await seedIds();
  const DOC2 = ID.user.bs_tien; const DOC3 = ID.user.bs_an;
  const S = ID.service; const AMOX = ID.medicine.MED_AMOX; const DRON = ID.medicine.MED_DRON_DOG;

  step('A. Luồng chuẩn: thu phí khám → phiếu Bác sĩ; thu XN/CĐHA → phiếu phòng; tổng dịch vụ 530.000đ + thuốc 157.500đ');
  const v1 = (await checkIn(ID.pet.lu, DOC2)).data.id;
  const st0 = await statementOf(v1);
  check('check-in: lập phí khám 100.000đ PENDING, ca WAITING, CHƯA có phiếu', (await visitOf(v1)).status === 'WAITING' && st0.invoices.length === 1 && st0.invoices[0].category === 'EXAMINATION' && Number(st0.totalPending) === 100000 && st0.tickets.length === 0, JSON.stringify(st0).slice(0, 300));
  check('chưa nộp phí khám: không có trong hàng đợi bác sĩ', !(await myQueue(T.doc2)).some((v) => v.id === v1));
  const noTicket = await startExam(T.doc2, v1);
  check('chưa nộp phí khám: bắt đầu khám bị từ chối CLIN_1314', noTicket.code === 'CLIN_1314', brief(noTicket));
  check('ca nằm ở mục thu ngân "phí khám"', (await call('GET', `${CLIN}/cashier/pending?section=EXAM_FEE`, { token: T.rec })).data.some((v) => v.id === v1));
  const fee = await payExamFee(v1);
  const doctorTicket = (await statementOf(v1)).tickets.find((t) => t.queueType === 'DOCTOR');
  check('thu phí khám → ServiceInvoicePaidEvent → phát phiếu DOCTOR (sau ~1 giây), ca vẫn WAITING', fee.status === 200 && doctorTicket && doctorTicket.ticketNo >= 1 && fee.data.visitStatus === 'WAITING', brief(fee));
  check('phiếu nằm trên Redis (Hash ticket:{id}), không còn bảng queue_tickets trong MySQL', redis('hget', `ticket:${doctorTicket.id}`, 'status') === 'WAITING'
    && Number(sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='pet_clinic_clinical_db' AND table_name IN ('queue_tickets','queue_ticket_items')")) === 0);
  check('thu phí khám lần hai → 409 BILL_1403', (await pay(v1, [st0.invoices[0].id])).code === 'BILL_1403');
  check('ca vào hàng đợi bác sĩ bs_tien kèm số phiếu', (await myQueue(T.doc2)).find((v) => v.id === v1)?.ticket?.ticketNo === doctorTicket.ticketNo);
  const called = await doctorCallNext(T.doc2);
  check('bác sĩ gọi số (không đổi trạng thái)', called.data?.visitId === v1 && called.data.ticketNo === doctorTicket.ticketNo && (await visitOf(v1)).status === 'WAITING', brief(called));
  check('bắt đầu khám (T02): phiếu → IN_SERVICE, không lập hóa đơn mới', (await startExam(T.doc2, v1)).status === 200 && (await statementOf(v1)).invoices.length === 1);
  const ordered = await call('POST', `${CLIN}/pet-visits/${v1}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [S.LAB_CBC, S.IMG_XRAY] } });
  check('chỉ định CBC + X-quang (T04): WAITING_RESULTS', ordered.data?.status === 'WAITING_RESULTS', JSON.stringify(ordered).slice(0, 200));
  check('chưa nộp tiền XN/CĐHA: hàng đợi Lab và CĐHA chưa có ca này', !(await labQueue()).some((i) => i.visitId === v1) && !(await imagingQueue()).some((i) => i.visitId === v1));
  check('ca nằm ở mục thu ngân "XN / CĐHA"', (await call('GET', `${CLIN}/cashier/pending?section=PARACLINIC`, { token: T.rec })).data.some((v) => v.id === v1));
  const cls = await payParaclinical(v1);
  const statementTickets = (await statementOf(v1)).tickets;
  check('thu CBC + X-quang → phát phiếu Lab và phiếu CĐHA (qua sự kiện)', cls.status === 200 && ['LABORATORY', 'IMAGING'].every((q) => (statementTickets).some((t) => t.queueType === q)), brief(cls));
  const labT = ticketOf(await labQueue(), v1);
  check('Lab gọi số → đúng phiếu', (await call('POST', `${CLIN}/queue-tickets/call-next?queueType=LABORATORY`, { token: T.lab })).data?.id === labT);
  const starts = await Promise.all(Array.from({ length: 10 }, () => startTicket(T.lab, labT)));
  check('10 thao tác "Bắt đầu" đồng thời trên một phiếu: đúng 1 thành công (IN_SERVICE), 9 lần CLIN_1313, KHÔNG có SYS_1005',
    starts.filter((r) => r.status === 200 && r.data?.status === 'IN_SERVICE').length === 1 && starts.filter((r) => r.code === 'CLIN_1313').length === 9 && !starts.some((r) => r.code === 'SYS_1005'),
    starts.map((r) => `${r.status}/${r.code || ''}`).join(','));
  const labDetail = detailOf(await labQueue(), v1);
  await call('POST', `${CLIN}/service-details/${labDetail}/sample-collected`, { token: T.lab });
  check('nhập kết quả xét nghiệm', (await labResults(labDetail)).status === 200);
  const imgT = ticketOf(await imagingQueue(), v1);
  check('CĐHA gọi số', (await call('POST', `${CLIN}/queue-tickets/call-next?queueType=IMAGING`, { token: T.img })).data?.id === imgT);
  await startTicket(T.img, imgT);
  const imgDetail = detailOf(await imagingQueue(), v1);
  check('nhập kết quả CĐHA kèm ảnh', (await call('POST', `${CLIN}/imaging-results/${imgDetail}`, { token: T.img, form: imagingForm() })).status === 200);
  const resultsTicket = await waitTickets(v1, (ts) => ts.some((t) => t.queueType === 'DOCTOR' && t.priority === 1));
  const q2 = await myQueue(T.doc2);
  check('đủ kết quả → RESULTS_READY (T06) + phiếu ưu tiên 1 (qua ParaclinicalResultsReadyEvent) đứng đầu hàng đợi bác sĩ', (await visitOf(v1)).status === 'RESULTS_READY' && resultsTicket.length > 0 && q2[0]?.id === v1 && q2[0]?.ticket?.priority === 1);
  check('phiếu phòng Lab / CĐHA đã DONE (không còn trong hàng đợi phòng)', !(await labQueue()).some((i) => i.visitId === v1) && !(await imagingQueue()).some((i) => i.visitId === v1));
  await doctorCallNext(T.doc2);
  check('bắt đầu giai đoạn 2 (T07)', (await call('POST', `${CLIN}/pet-visits/${v1}/start-phase2`, { token: T.doc2 })).status === 200);
  const stock0 = await stockOf(AMOX);
  const prescription = await savePrescription(T.doc2, v1, AMOX, 10);
  check('kê đơn 10 Clavamox (không giá, không trạng thái)', prescription.status === 200 && prescription.data.stockWarnings.length === 0 && !('status' in prescription.data), brief(prescription));
  check('chưa chốt ca: billing chưa có hóa đơn thuốc', (await call('GET', `${BILL}/medicine-invoices?visitId=${v1}`, { token: T.rec })).data.length === 0);
  check('chốt ca (T09) → WAITING_PAYMENT', (await close(T.doc2, v1)).data?.status === 'WAITING_PAYMENT');
  const statement = await statementOf(v1);
  check('3 hóa đơn dịch vụ (khám, CBC, X-quang) đã thu trước đủ 530.000đ, không còn khoản chờ', statement.invoices.length === 3 && Number(statement.totalPaid) === 530000 && Number(statement.totalPending) === 0, JSON.stringify(statement).slice(0, 300));
  check('VIEW v_service_invoice_amounts = 530.000', Number(sql(`SELECT SUM(payable_amount) FROM pet_clinic_clinical_db.v_service_invoice_amounts WHERE visit_id=${uuidSql(v1)}`)) === 530000);
  const mi = await draftOf(v1);
  check('chốt ca → PrescriptionCreatedEvent → hóa đơn thuốc NHÁP PENDING 157.500đ (10 × 15.000 × 1,05)', mi && mi.paymentStatus === 'PENDING' && Number(mi.payableAmount) === 157500 && mi.items.length === 1 && mi.items[0].quantity === 10 && mi.items[0].prescribedQuantity === 10, brief(mi));
  check('VIEW v_medicine_invoice_amounts = 157.500', Number(sql(`SELECT payable_amount FROM pet_clinic_billing_pharmacy_db.v_medicine_invoice_amounts WHERE invoice_id=${uuidSql(mi.id)}`)) === 157500);
  check('nháp chưa đụng kho', (await stockOf(AMOX)) === stock0);
  check('thu tiền thuốc: 200 → 190, trạng thái ca không đổi', (await call('POST', `${BILL}/medicine-invoices/${mi.id}/confirm-payment`, { token: T.rec })).status === 200 && (await stockOf(AMOX)) === stock0 - 10 && (await visitOf(v1)).status === 'WAITING_PAYMENT');
  check('bất biến SUM(inventory_transactions.quantity) = medicine_stocks.quantity_on_hand', sql(`SELECT (SELECT SUM(quantity) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_id=${medicineIdSql('MED_AMOX')}) - (SELECT quantity_on_hand FROM pet_clinic_billing_pharmacy_db.medicine_stocks WHERE medicine_id=${medicineIdSql('MED_AMOX')})`) === '0');
  const finished = await pay(v1, []);
  check('không còn gì để thu: thu ngân xác nhận (danh sách rỗng) → COMPLETED (T10, AD-03)', finished.status === 200 && (await visitOf(v1)).status === 'COMPLETED', brief(finished));

  step('A2. Dịch vụ làm tại phòng thu khi chốt ca; cấp cứu khám trước thu sau; hủy XN do phòng khám → hoàn tiền');
  const pIn = await newPet('E2E-inroom');
  const vIn = await admit(pIn, DOC2);
  await doctorCallNext(T.doc2); await startExam(T.doc2, vIn);
  await call('POST', `${CLIN}/pet-visits/${vIn}/in-room-services`, { token: T.doc2, body: { services: [{ serviceId: S.VAC_4B, quantity: 1 }] } });
  const vac = (await statementOf(vIn)).invoices.find((i) => i.category !== 'EXAMINATION');
  const early = await pay(vIn, [vac.id]);
  check('dịch vụ tại phòng chưa chốt ca → 409 BILL_1409', vac && !vac.requiresPrepayment && early.code === 'BILL_1409', brief(early));
  const vacDetail = (await visitOf(vIn)).services.find((d) => d.id === vac.visitServiceDetailId).id;
  await call('POST', `${CLIN}/service-details/${vacDetail}/perform`, { token: T.doc2 });
  await close(T.doc2, vIn);
  check('chốt ca rồi thu dịch vụ tại phòng → COMPLETED', (await pay(vIn, [vac.id])).data?.visitStatus === 'COMPLETED');

  const pEm = await newPet('E2E-emergency');
  const vEm = (await checkIn(pEm, DOC3, true)).data.id;
  await waitTickets(vEm, (ts) => ts.some((t) => t.priority === 2));
  const emQ = await myQueue(T.doc3);
  check('ca cấp cứu: có phiếu ngay (priority 2, từ VisitCheckedInEvent) dù chưa nộp phí, đứng đầu hàng đợi', emQ[0]?.id === vEm && emQ[0]?.ticket?.priority === 2);
  check('ca cấp cứu bắt đầu khám được ngay', (await startExam(T.doc3, vEm)).status === 200);
  await call('POST', `${CLIN}/pet-visits/${vEm}/paraclinical-orders`, { token: T.doc3, body: { serviceIds: [S.LAB_CBC] } });
  await waitTickets(vEm, (ts) => ts.some((t) => t.queueType === 'LABORATORY'));
  const emLab = (await labQueue()).find((i) => i.visitId === vEm);
  check('ca cấp cứu: phiếu Lab phát ngay, priority 2, chưa thu tiền', emLab?.ticketPriority === 2);
  const cancelClinic = await call('POST', `${CLIN}/service-details/${emLab.serviceDetailId}/cancel`, { token: T.lab, body: { reason: 'Máy xét nghiệm hỏng', reasonType: 'CLINIC' } });
  check('hủy XN chưa thu (do phòng khám) → hóa đơn CANCELLED, ca RESULTS_READY', cancelClinic.status === 200 && (await statementOf(vEm)).invoices.find((i) => i.category === 'LABORATORY').paymentStatus === 'CANCELLED' && (await visitOf(vEm)).status === 'RESULTS_READY', brief(cancelClinic));
  await call('POST', `${CLIN}/pet-visits/${vEm}/abandon`, { token: T.rec });

  const pRf = await newPet('E2E-refund');
  const vRf = await admit(pRf, DOC3);
  await doctorCallNext(T.doc3); await startExam(T.doc3, vRf);
  await call('POST', `${CLIN}/pet-visits/${vRf}/paraclinical-orders`, { token: T.doc3, body: { serviceIds: [S.LAB_CBC, S.IMG_XRAY] } });
  await payParaclinical(vRf);
  const rfLab = detailOf(await labQueue(), vRf); const rfImg = detailOf(await imagingQueue(), vRf);
  await call('POST', `${CLIN}/service-details/${rfImg}/cancel`, { token: T.img, body: { reason: 'Máy X-quang hỏng', reasonType: 'CLINIC' } });
  await call('POST', `${CLIN}/service-details/${rfLab}/cancel`, { token: T.lab, body: { reason: 'Khách đổi ý', reasonType: 'CUSTOMER' } });
  const rfSt = await statementOf(vRf);
  check('đã thu: hủy do phòng khám → REFUNDED; khách tự hủy → giữ PAID', rfSt.invoices.find((i) => i.category === 'IMAGING').paymentStatus === 'REFUNDED' && rfSt.invoices.find((i) => i.category === 'LABORATORY').paymentStatus === 'PAID' && Number(rfSt.totalRefunded) === 180000, JSON.stringify(rfSt.invoices.map((i) => i.paymentStatus)));
  await call('POST', `${CLIN}/pet-visits/${vRf}/abandon`, { token: T.rec });

  step('B. EX-15 (thiếu kho → rollback, sửa hóa đơn, thu lại) và EX-16 (thu trùng, kể cả đồng thời)');
  const p2 = await newPet('E2E-ex15');
  const v2 = await admit(p2, DOC3);
  await doctorCallNext(T.doc3); await startExam(T.doc3, v2);
  await savePrescription(T.doc3, v2, DRON, 10);   // Drontal tồn 100
  await close(T.doc3, v2);
  const stock4 = await stockOf(DRON);
  const mi2 = await draftOf(v2);   // nháp mua đúng đơn (10 viên)
  // kho giảm giữa lúc lập nháp và lúc thu tiền (bán chỗ khác, kiểm kê...): lúc lập còn đủ, lúc thu mới thiếu
  await call('POST', `${PHARM}/medicines/${DRON}/adjust`, { token: T.admin, body: { delta: -(stock4 - 5), reason: 'e2e đưa tồn về 5' } });
  const lack = await call('POST', `${BILL}/medicine-invoices/${mi2.id}/confirm-payment`, { token: T.rec });
  check('EX-15: thiếu kho → 409 PHARM_1406', lack.status === 409 && lack.code === 'PHARM_1406');
  check('EX-15: tồn vẫn 5, không có dòng EXPORT_SALE, hóa đơn vẫn PENDING', (await stockOf(DRON)) === 5 && sql(`SELECT COUNT(*) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_invoice_id=${uuidSql(mi2.id)}`) === '0' && (await call('GET', `${BILL}/medicine-invoices/${mi2.id}`, { token: T.rec })).data.paymentStatus === 'PENDING');
  const tooMuch = await call('PUT', `${BILL}/medicine-invoices/${mi2.id}/items`, { token: T.rec, body: { items: [{ medicineId: DRON, quantity: 6 }] } });
  check('sửa hóa đơn: không bán quá tồn kho (kho còn 5, sửa lên 6 → 409 PHARM_1406)', tooMuch.status === 409 && tooMuch.code === 'PHARM_1406', brief(tooMuch));
  await call('PUT', `${BILL}/medicine-invoices/${mi2.id}/items`, { token: T.rec, body: { items: [{ medicineId: DRON, quantity: 5 }] } });
  check('EX-15: sửa về 5 viên rồi thu lại → PAID, tồn 0', (await call('POST', `${BILL}/medicine-invoices/${mi2.id}/confirm-payment`, { token: T.rec })).status === 200 && (await stockOf(DRON)) === 0);
  check('EX-16: thu lần hai → 409 BILL_1403', (await call('POST', `${BILL}/medicine-invoices/${mi2.id}/confirm-payment`, { token: T.rec })).code === 'BILL_1403');
  // thu đồng thời trên hóa đơn khác
  await call('POST', `${PHARM}/medicines/${DRON}/import`, { token: T.admin, body: { quantity: 50, notes: 'e2e' } });
  const p3 = await newPet('E2E-ex16');
  const v3 = await admit(p3, DOC3);
  await doctorCallNext(T.doc3); await startExam(T.doc3, v3);
  await savePrescription(T.doc3, v3, DRON, 10);
  await close(T.doc3, v3);
  const stockNow = await stockOf(DRON);
  const mi3 = await draftOf(v3);
  const overStock = await call('PUT', `${BILL}/medicine-invoices/${mi3.id}/items`, { token: T.rec, body: { items: [{ medicineId: DRON, quantity: stockNow + 1 }] } });
  check('sửa hóa đơn: không bán quá tồn kho (409 PHARM_1406)', overStock.status === 409 && overStock.code === 'PHARM_1406', brief(overStock));
  const mi3b = await call('PUT', `${BILL}/medicine-invoices/${mi3.id}/items`, { token: T.rec, body: { items: [{ medicineId: DRON, quantity: 12 }] } });
  check('số lượng bác sĩ kê chỉ để tham khảo: kê 10, khách mua 12 (≤ tồn) vẫn sửa được', mi3b.status === 200 && mi3b.data.items[0].quantity === 12 && mi3b.data.items[0].prescribedQuantity === 10, brief(mi3b));
  check('hóa đơn trả kèm tồn kho hiện tại để màn hình bán thuốc hiển thị', mi3b.data.items[0].stockQuantity === stockNow, brief(mi3b));
  // EX-19: sự kiện chốt ca giao lại không tạo hóa đơn thứ hai và không dựng lại nháp đã sửa
  const outboxRow = `routing_key='prescription.created' AND aggregate_id = UNHEX(REPLACE('${v3}','-',''))`;
  sql(`UPDATE pet_clinic_clinical_db.outbox_events SET status='PENDING', published_at=NULL, next_attempt_at=UTC_TIMESTAMP() WHERE ${outboxRow}`);
  for (let i = 0; i < 40 && sql(`SELECT status FROM pet_clinic_clinical_db.outbox_events WHERE ${outboxRow}`) !== 'PUBLISHED'; i += 1) await sleep(500);
  await sleep(2000);
  check('EX-19: relay gửi lại; vẫn đúng 1 hóa đơn thuốc cho đơn, nháp đã sửa (12 viên) không bị dựng lại', sql(`SELECT COUNT(*) FROM pet_clinic_billing_pharmacy_db.medicine_invoices WHERE visit_id=${uuidSql(v3)}`) === '1' && (await call('GET', `${BILL}/medicine-invoices/${mi3.id}`, { token: T.rec })).data.items[0].quantity === 12);
  check('EX-19: processed_events có đúng 1 dòng cho sự kiện này', sql(`SELECT COUNT(*) FROM pet_clinic_billing_pharmacy_db.processed_events WHERE consumer='billing.prescription-created' AND event_id=(SELECT event_id FROM pet_clinic_clinical_db.outbox_events WHERE ${outboxRow})`) === '1');
  const before = await stockOf(DRON);
  const race = await Promise.all([1, 2, 3, 4, 5].map(() => call('POST', `${BILL}/medicine-invoices/${mi3.id}/confirm-payment`, { token: T.rec })));
  check('EX-16 đồng thời: đúng 1/5 yêu cầu thu được, 4 bị từ chối 409', race.filter((r) => r.status === 200).length === 1 && race.filter((r) => r.status === 409).length === 4, race.map((r) => r.status).join(','));
  check('EX-16 đồng thời: kho chỉ trừ đúng một lần', (await stockOf(DRON)) === before - 12);
  check('bất biến kho giữ nguyên sau EX-15/16 (Drontal)', sql(`SELECT (SELECT SUM(quantity) FROM pet_clinic_billing_pharmacy_db.inventory_transactions WHERE medicine_id=${medicineIdSql('MED_DRON_DOG')}) - (SELECT quantity_on_hand FROM pet_clinic_billing_pharmacy_db.medicine_stocks WHERE medicine_id=${medicineIdSql('MED_DRON_DOG')})`) === '0');

  step('C. Đua RESULTS_READY: hai KTV hoàn tất cùng lúc (không kẹt WAITING_RESULTS); thu phí khám đồng thời không phát trùng số');
  let stuck = 0;
  for (let round = 1; round <= 3; round += 1) {
    const pet = await newPet(`E2E-race-${round}`);
    const v = await admit(pet, DOC2);
    await doctorCallNext(T.doc2); await startExam(T.doc2, v);
    await call('POST', `${CLIN}/pet-visits/${v}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [S.LAB_CBC, S.IMG_XRAY] } });
    await payParaclinical(v);
    await startTicket(T.lab, ticketOf(await labQueue(), v)); await startTicket(T.img, ticketOf(await imagingQueue(), v));
    const lab = detailOf(await labQueue(), v); const img = detailOf(await imagingQueue(), v);
    await Promise.all([labResults(lab), call('POST', `${CLIN}/imaging-results/${img}`, { token: T.img, form: imagingForm() })]);
    if ((await visitOf(v)).status !== 'RESULTS_READY') stuck += 1;
    await call('POST', `${CLIN}/pet-visits/${v}/abandon`, { token: T.rec });   // dọn: khách bỏ về → COMPLETED
  }
  check('3 vòng hoàn tất đồng thời: không ca nào kẹt ở WAITING_RESULTS', stuck === 0, `kẹt ${stuck}/3`);
  const racePets = []; for (let i = 1; i <= 6; i += 1) racePets.push(await newPet(`E2E-fee-${i}`));
  const raceVisits = []; for (const pet of racePets) raceVisits.push((await checkIn(pet, null)).data.id);
  const fees = await Promise.all(raceVisits.map((id) => payExamFee(id)));
  const numbers = [];
  for (const id of raceVisits) numbers.push((await statementOf(id)).tickets.find((t) => t.queueType === 'DOCTOR')?.ticketNo);
  check('6 quầy thu phí khám cùng lúc: đều thành công, 6 số phiếu khác nhau (Redis INCR, không tranh số)', fees.every((r) => r.status === 200) && new Set(numbers).size === 6 && numbers.every(Boolean), numbers.join(',') + ' ' + fees.map(brief).join(' | '));
  for (const id of raceVisits) await call('POST', `${CLIN}/pet-visits/${id}/cancel`, { token: T.rec, body: { reason: 'e2e dọn' } });

  step('D. Gọi theo từng phiếu (BR-34): phiếu vắng giữ nguyên vị trí, phiếu thứ 3, 4 vẫn gọi được');
  const pets = []; for (let i = 1; i <= 4; i += 1) pets.push(await newPet(`E2E-q${i}`));
  const queue = []; for (const pet of pets) { queue.push(await admit(pet, DOC3)); await sleep(5); }
  const order = async () => (await myQueue(T.doc3)).map((v) => v.id);
  const tickets = Object.fromEntries((await myQueue(T.doc3)).map((v) => [v.id, v.ticket.id]));
  const [A, B, C, D] = queue;
  check('hàng đợi ban đầu theo giờ phát phiếu [A,B,C,D]', JSON.stringify(await order()) === JSON.stringify([A, B, C, D]));
  const c1 = await doctorCallNext(T.doc3);
  check('"Gọi số tiếp theo" gọi phiếu đầu hàng (A)', c1.status === 200 && c1.data?.visitId === A, brief(c1));
  const cB = await callTicket(T.doc3, tickets[B]);
  const cC = await callTicket(T.doc3, tickets[C]);
  const cD = await callTicket(T.doc3, tickets[D]);
  check('A, B, C vắng: bác sĩ gọi tiếp B, C rồi D (phiếu thứ 4) đều được, không bị kẹt', [cB, cC, cD].every((r) => r.status === 200) && cD.data?.visitId === D, brief(cD));
  check('gọi không đổi thứ tự hàng đợi (vẫn [A,B,C,D]) và lưu giờ gọi trên phiếu', JSON.stringify(await order()) === JSON.stringify([A, B, C, D]) && (await myQueue(T.doc3)).every((v) => v.ticket.lastCalledAt));
  const callAgain = await callTicket(T.doc3, tickets[A]);
  check('khách A quay lại: "Gọi lại" phiếu A được, thứ tự vẫn không đổi', callAgain.status === 200 && JSON.stringify(await order()) === JSON.stringify([A, B, C, D]));
  check('bác sĩ khác không gọi được phiếu của bác sĩ này → CLIN_1311', (await callTicket(T.doc2, tickets[A])).code === 'CLIN_1311');
  check('API gọi số cũ theo đợt khám đã bỏ', (await call('POST', `${CLIN}/pet-visits/call-next`, { token: T.doc3 })).status >= 400);
  check('cột queue_order đã bị xóa khỏi lược đồ (hàng đợi ở Redis)', Number(sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='pet_clinic_clinical_db' AND column_name='queue_order'")) === 0);
  for (const id of queue) await call('POST', `${CLIN}/pet-visits/${id}/cancel`, { token: T.rec, body: { reason: 'e2e dọn' } });
  check('hủy tiếp nhận: phiếu bị hủy, hàng đợi trống', (await order()).length === 0);

  step('E. Công nợ: khách bỏ về → COMPLETED, thu bù sau (dịch vụ tại phòng chưa thu)');
  const p5 = await newPet('E2E-debt');
  const v5 = await admit(p5, DOC2);
  await doctorCallNext(T.doc2); await startExam(T.doc2, v5);
  await call('POST', `${CLIN}/pet-visits/${v5}/in-room-services`, { token: T.doc2, body: { services: [{ serviceId: S.VAC_4B, quantity: 1 }] } });
  const d5 = (await visitOf(v5)).services.find((d) => d.category !== 'EXAMINATION').id;
  await call('POST', `${CLIN}/service-details/${d5}/perform`, { token: T.doc2 });
  await call('POST', `${CLIN}/pet-visits/${v5}/paraclinical-orders`, { token: T.doc2, body: { serviceIds: [S.LAB_CBC] } });
  const abandoned = await call('POST', `${CLIN}/pet-visits/${v5}/abandon`, { token: T.rec });
  check('khách bỏ về khi chưa nộp tiền XN: ca COMPLETED, XN hủy kèm hóa đơn', abandoned.data?.status === 'COMPLETED' && (await statementOf(v5)).invoices.find((i) => i.category === 'LABORATORY').paymentStatus === 'CANCELLED', brief(abandoned));
  check('ca xuất hiện ở danh sách công nợ (dịch vụ tại phòng đã làm chưa thu)', (await call('GET', `${CLIN}/pet-visits/with-debt`, { token: T.rec })).data.some((v) => v.id === v5));
  check('thu công nợ về sau, ca giữ COMPLETED, hết công nợ', (await payAllServices(v5)).status === 200 && (await visitOf(v5)).status === 'COMPLETED' && !(await call('GET', `${CLIN}/pet-visits/with-debt`, { token: T.rec })).data.some((v) => v.id === v5));

  step('F. Đặt lịch đồng thời (sức chứa = bác sĩ ACTIVE × 2) và slots công khai');
  const day = new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10);
  const slotsBefore = await call('GET', `/api/v1/appointments/slots?date=${day}`);
  const slot = slotsBefore.data.find((s) => s.startTime.startsWith('10:00'));
  check('slots công khai không cần token', slotsBefore.status === 200 && slot.capacity === 6, JSON.stringify(slot));
  const bookPets = []; for (let i = 1; i <= 9; i += 1) bookPets.push(await newPet(`E2E-book-${i}`));
  const booking = await Promise.all(bookPets.map((pet) => call('POST', '/api/v1/appointments', { token: T.owner, body: { petId: pet, appointmentDate: day, startTime: '10:00', reason: 'e2e đặt đồng thời' } })));
  const ok = booking.filter((r) => r.status === 201).length;
  check('9 yêu cầu đồng thời vào khung còn 6 chỗ: đúng 6 thành công, 3 bị từ chối', ok === 6 && booking.filter((r) => r.code === 'APPT_1203').length === 3, booking.map((r) => `${r.status}${r.code ? `/${r.code}` : ''}`).join(' '));
  check('sau đó khung hết chỗ', (await call('GET', `/api/v1/appointments/slots?date=${day}`)).data.find((s) => s.startTime.startsWith('10:00')).available === false);

  step('G. Refresh token dùng lại bị coi là đánh cắp (Token Rotation)');
  const s0 = await login('khach_lan');
  const r1 = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: s0.refreshToken } });
  check('refresh lần 1 thành công và cấp token mới', r1.status === 200 && r1.data.refreshToken !== s0.refreshToken);
  const reuse = await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: s0.refreshToken } });
  check('dùng lại token cũ → 401', reuse.status === 401);
  check('token mới cũng bị thu hồi (đăng xuất mọi thiết bị)', (await call('POST', '/api/v1/auth/refresh', { body: { refreshToken: r1.data.refreshToken } })).status === 401);

  step('H. Phân quyền và /internal bị chặn ở Gateway');
  check('/internal/** từ ngoài bị chặn', [403, 404].includes((await call('GET', `/internal/pet-visits/${v1}/summary`)).status));
  check('lễ tân không kê đơn (403)', (await savePrescription(T.rec, v1, AMOX, 1)).status === 403);
  check('bác sĩ không thu tiền thuốc (403)', (await call('POST', `${BILL}/medicine-invoices/${mi.id}/confirm-payment`, { token: T.doc2 })).status === 403);
  check('không token → 401 SYS_1002', (await call('GET', `${CLIN}/pet-visits/queue`)).code === 'SYS_1002');

  step('I. Transactional Outbox (v4 Phase 1): sự kiện nghiệp vụ đi qua outbox_events và đều được OutboxRelay gửi đi');
  const outboxDbs = ['pet_clinic_auth_db', 'pet_clinic_appointment_db', 'pet_clinic_clinical_db', 'pet_clinic_billing_pharmacy_db'];
  const unpublished = () => outboxDbs.reduce((sum, db) => sum + Number(sql(`SELECT COUNT(*) FROM ${db}.outbox_events WHERE status <> 'PUBLISHED'`)), 0);
  let notPublished = unpublished();
  for (let i = 0; i < 20 && notPublished > 0; i += 1) { await sleep(1000); notPublished = unpublished(); }   // relay chạy mỗi 500 ms
  check('cả 4 CSDL của service phát sự kiện đều có bảng outbox_events',
    Number(sql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'outbox_events' AND table_schema IN (${outboxDbs.map((d) => `'${d}'`).join(',')})`)) === 4);
  check('mọi dòng outbox của luồng chuẩn đều PUBLISHED (không còn PENDING / FAILED)', notPublished === 0, `${notPublished} dòng chưa gửi`);
  const outboxTypes = sql(['pet_clinic_appointment_db', 'pet_clinic_clinical_db', 'pet_clinic_billing_pharmacy_db']
    .map((db) => `SELECT event_type FROM ${db}.outbox_events`).join(' UNION ')).split('\n').filter(Boolean).sort();
  const expectedTypes = ['ServiceInvoicePaidEvent', 'VisitCancelledEvent', 'AppointmentBookedEvent', 'MedicineInvoicePaidEvent', 'PrescriptionCreatedEvent', 'MedicineLowStockEvent', 'ParaclinicalResultsReadyEvent', 'VisitCheckedInEvent', 'VisitCompletedEvent'];
  check('9 loại sự kiện đã đi qua outbox: đặt lịch, tiếp nhận, thu tiền dịch vụ, hủy ca, có kết quả CLS, hoàn tất ca, đơn thuốc chốt ca, thu tiền thuốc, tồn kho thấp',
    expectedTypes.every((type) => outboxTypes.includes(type)), outboxTypes.join(','));
  check('phiếu xếp hàng và gọi số gửi thẳng, không qua outbox (D9)',
    Number(sql("SELECT COUNT(*) FROM pet_clinic_clinical_db.outbox_events WHERE event_type IN ('QueueTicketIssuedEvent', 'PatientCalledEvent')")) === 0);

  step('J. Báo cáo (v4 Phase 7, reporting-service, CQRS): doanh thu luồng chuẩn 530.000 + 157.500, hoàn tiền trừ đúng, nhận trùng sự kiện không cộng hai lần (EX-19)');
  const RDB = 'pet_clinic_reporting_db';
  const money = (query) => Number(sql(query) || 0);
  const servicePaid = () => money("SELECT IFNULL(SUM(payable_amount), 0) FROM pet_clinic_clinical_db.v_service_invoice_amounts WHERE payment_status = 'PAID'");
  const medicinePaid = () => money("SELECT IFNULL(SUM(payable_amount), 0) FROM pet_clinic_billing_pharmacy_db.v_medicine_invoice_amounts WHERE payment_status = 'PAID'");
  const reportedService = () => money(`SELECT IFNULL(SUM(net_amount), 0) FROM ${RDB}.revenue_facts WHERE revenue_type = 'SERVICE'`);
  const reportedMedicine = () => money(`SELECT IFNULL(SUM(net_amount), 0) FROM ${RDB}.revenue_facts WHERE revenue_type = 'MEDICINE'`);
  for (let i = 0; i < 40 && (reportedService() !== servicePaid() || reportedMedicine() !== medicinePaid()); i += 1) await sleep(1000);   // reporting nhận sự kiện bất đồng bộ
  check('reporting bắt kịp: doanh thu dịch vụ và thuốc trong báo cáo = tổng hóa đơn PAID của VIEW (dịch vụ trừ phần đã hoàn)',
    reportedService() === servicePaid() && reportedMedicine() === medicinePaid() && servicePaid() > 0, `${reportedService()}/${servicePaid()} ${reportedMedicine()}/${medicinePaid()}`);
  check('luồng chuẩn: dịch vụ của ca v1 trong báo cáo = 530.000đ (khám + CBC + X-quang), thuốc = 157.500đ',
    money(`SELECT SUM(net_amount) FROM ${RDB}.revenue_facts WHERE visit_id = ${uuidSql(v1)} AND revenue_type = 'SERVICE'`) === 530000
    && money(`SELECT SUM(net_amount) FROM ${RDB}.revenue_facts WHERE source_invoice_id = ${uuidSql(mi.id)}`) === 157500);
  check('hoàn tiền X-quang (ca vRf): có dòng REFUND âm 180.000, thu − hoàn của nhóm CĐHA = 0, dịch vụ còn lại của ca = 250.000 (CBC khách tự hủy vẫn giữ)',
    money(`SELECT SUM(net_amount) FROM ${RDB}.revenue_facts WHERE visit_id = ${uuidSql(vRf)} AND entry_type = 'REFUND'`) === -180000
    && money(`SELECT SUM(net_amount) FROM ${RDB}.revenue_facts WHERE visit_id = ${uuidSql(vRf)} AND category_code = 'IMAGING'`) === 0
    && money(`SELECT SUM(net_amount) FROM ${RDB}.revenue_facts WHERE visit_id = ${uuidSql(vRf)} AND revenue_type = 'SERVICE'`) >= 250000);
  const vnToday = new Date(Date.now() + 7 * 3600 * 1000).toISOString().slice(0, 10);
  const daily = await call('GET', `${REPORTS}/revenue/daily?from=${vnToday}&to=${vnToday}`, { token: T.admin });
  check('GET /reports/revenue/daily (qua Gateway): tổng dịch vụ / thuốc khớp hóa đơn PAID, hai loại SERVICE + MEDICINE',
    daily.status === 200 && Number(daily.data.totals.serviceNetAmount) === servicePaid() && Number(daily.data.totals.medicineNetAmount) === medicinePaid()
    && daily.data.items.some((i) => i.revenueType === 'SERVICE') && daily.data.items.some((i) => i.revenueType === 'MEDICINE'), brief(daily));
  const medOnly = await call('GET', `${REPORTS}/revenue/daily?from=${vnToday}&to=${vnToday}&type=MEDICINE`, { token: T.admin });
  check('type=MEDICINE chỉ trả doanh thu thuốc', medOnly.status === 200 && medOnly.data.items.every((i) => i.revenueType === 'MEDICINE') && Number(medOnly.data.totals.netAmount) === medicinePaid(), brief(medOnly));
  const byCategory = await call('GET', `${REPORTS}/revenue/by-category?month=${vnToday.slice(0, 7)}`, { token: T.admin });
  const examCat = byCategory.data?.items?.find((i) => i.revenueType === 'SERVICE' && i.categoryCode === 'EXAMINATION');
  check('GET /reports/revenue/by-category?month=: có nhóm Khám bệnh (tên hiển thị) và nhóm thuốc, tổng = dịch vụ + thuốc',
    byCategory.status === 200 && examCat && examCat.categoryName === 'Khám bệnh' && Number(examCat.netAmount) > 0 && byCategory.data.items.some((i) => i.revenueType === 'MEDICINE')
    && Number(byCategory.data.totals.netAmount) === servicePaid() + medicinePaid(), brief(byCategory));
  const perfApi = await call('GET', `${REPORTS}/doctors/performance?from=${vnToday}&to=${vnToday}`, { token: T.admin });
  check('GET /reports/doctors/performance: có Bác sĩ, tổng ca hoàn tất khớp visit_facts, tên từ bảng phẳng',
    perfApi.status === 200 && perfApi.data.doctors.length > 0 && perfApi.data.doctors.every((d) => d.doctorName)
    && perfApi.data.doctors.reduce((s, d) => s + d.completedVisits, 0) === Number(sql(`SELECT COUNT(*) FROM ${RDB}.visit_facts WHERE current_status = 'COMPLETED' AND doctor_id IS NOT NULL`)), brief(perfApi));
  const summaryApi = await call('GET', `${REPORTS}/visits/summary?date=${vnToday}`, { token: T.admin });
  check('GET /reports/visits/summary?date=: tổng ca = số dòng visit_facts hôm nay, có ca cấp cứu, hoàn tất và hủy',
    summaryApi.status === 200 && summaryApi.data.totalVisits === Number(sql(`SELECT COUNT(*) FROM ${RDB}.visit_facts WHERE business_date = '${vnToday}'`))
    && summaryApi.data.emergencyVisits > 0 && summaryApi.data.completedVisits > 0 && summaryApi.data.cancelledVisits > 0
    && summaryApi.data.totalVisits === summaryApi.data.routineVisits + summaryApi.data.paraclinicalVisits, brief(summaryApi));
  // EX-19: phát lại sự kiện thu tiền dịch vụ + thu tiền thuốc của luồng chuẩn -> báo cáo không đổi
  const beforeReplay = [reportedService(), reportedMedicine(), Number(sql(`SELECT COUNT(*) FROM ${RDB}.revenue_facts`)), Number(sql(`SELECT SUM(net_amount) FROM ${RDB}.daily_revenue_summaries`))];
  sql(`UPDATE pet_clinic_clinical_db.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP() WHERE routing_key = 'invoice.service_paid' AND aggregate_id = UNHEX(REPLACE('${v1}','-',''))`);
  sql(`UPDATE pet_clinic_billing_pharmacy_db.outbox_events SET status = 'PENDING', published_at = NULL, next_attempt_at = UTC_TIMESTAMP() WHERE routing_key = 'pharmacy.medicine_invoice_paid' AND aggregate_id = UNHEX(REPLACE('${mi.id}','-',''))`);
  for (let i = 0; i < 20 && Number(sql("SELECT COUNT(*) FROM pet_clinic_clinical_db.outbox_events WHERE status <> 'PUBLISHED'")) + Number(sql("SELECT COUNT(*) FROM pet_clinic_billing_pharmacy_db.outbox_events WHERE status <> 'PUBLISHED'")) > 0; i += 1) await sleep(1000);
  await sleep(4000);
  const afterReplay = [reportedService(), reportedMedicine(), Number(sql(`SELECT COUNT(*) FROM ${RDB}.revenue_facts`)), Number(sql(`SELECT SUM(net_amount) FROM ${RDB}.daily_revenue_summaries`))];
  check('EX-19: giao lại sự kiện thu tiền dịch vụ + thuốc của luồng chuẩn → tổng báo cáo, số dòng fact và bảng tổng hợp KHÔNG đổi', JSON.stringify(beforeReplay) === JSON.stringify(afterReplay), `${beforeReplay} → ${afterReplay}`);
  // Task 4.5.4: dựng lại báo cáo từ outbox (qua Gateway, chỉ ADMIN) -> báo cáo giống hệt trước đó
  const rebuilt = await call('POST', `${REPORTS}/rebuild?days=1`, { token: T.admin });
  check('POST /reports/rebuild?days=1 (Gateway, ADMIN): đọc outbox của 3 service, áp dụng đủ, không bỏ qua sự kiện nào', rebuilt.status === 200 && rebuilt.data.fetched > 0 && rebuilt.data.applied === rebuilt.data.fetched && rebuilt.data.skipped === 0, brief(rebuilt) + JSON.stringify(rebuilt.data).slice(0, 200));
  const afterRebuild = [reportedService(), reportedMedicine(), Number(sql(`SELECT COUNT(*) FROM ${RDB}.revenue_facts`)), Number(sql(`SELECT SUM(net_amount) FROM ${RDB}.daily_revenue_summaries`))];
  check('sau dựng lại: doanh thu dịch vụ / thuốc, số dòng fact và bảng tổng hợp giống HỆT trước đó', JSON.stringify(afterReplay) === JSON.stringify(afterRebuild), `${afterReplay} → ${afterRebuild}`);
  check('Lễ tân không được dựng lại báo cáo (403)', (await call('POST', `${REPORTS}/rebuild`, { token: T.rec })).status === 403);
  check('bảng tổng hợp theo ngày khớp tổng các dòng fact (cộng dồn nguyên tử)', Number(sql(`SELECT SUM(net_amount) FROM ${RDB}.daily_revenue_summaries`)) === reportedService() + reportedMedicine());
  check('khoảng ngày đảo ngược → 400 SYS_1001; type sai → 400', (await call('GET', `${REPORTS}/revenue/daily?from=${vnToday}&to=2020-01-01`, { token: T.admin })).code === 'SYS_1001'
    && (await call('GET', `${REPORTS}/revenue/daily?type=FOOD`, { token: T.admin })).status === 400);
  check('chỉ ADMIN xem báo cáo: Lễ tân, Bác sĩ → 403; không token → 401', (await call('GET', `${REPORTS}/revenue/daily`, { token: T.rec })).status === 403
    && (await call('GET', `${REPORTS}/visits/summary`, { token: T.doc3 })).status === 403 && (await call('GET', `${REPORTS}/revenue/daily`)).status === 401);
  check('báo cáo cũ ở clinical / billing đã bị xóa (404)', (await call('GET', `${CLIN}/reports/revenue`, { token: T.admin })).status === 404 && (await call('GET', `${BILL}/reports/revenue`, { token: T.admin })).status === 404);

  finish();
}

run(main);
