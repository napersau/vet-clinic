import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import StaffLayout from '../../layouts/StaffLayout';
import { ImagingResults, LabResultsTable, VitalSignsTable } from '../../components/shared/MedicalEntry';
import { AsyncView, Badge, Button, ConfirmDialog, Facts, Field, FormDialog, Input, Notice, PageHead, Panel, Select, Table, Tabs, Textarea } from '../../components/ui';
import { catalogApi, pharmacyApi, visitsApi, paraclinicApi } from '../../api/endpoints';
import { mapFieldErrors } from '../../api/client';
import { useAuth } from '../../contexts/AuthContext';
import useAction from '../../hooks/useAction';
import useAsync from '../../hooks/useAsync';
import usePolling from '../../hooks/usePolling';
import { blankToNull, formatDate, formatDateTime, formatMoney, formatNumber, toNumber } from '../../lib/format';
import { sameId } from '../../lib/ids';
import { serviceCategoryLabel, SERVICE_DETAIL_STATUS, VISIT_STATUS } from '../../lib/labels';

// Cách thực hiện một dịch vụ do NHÓM quyết định (service_categories.queue_type): vào hàng đợi phòng XN / CĐHA = cận lâm sàng; không có hàng đợi = làm tại phòng
const PARACLINICAL_QUEUES = ['LABORATORY', 'IMAGING'];
const isParaclinical = (item) => PARACLINICAL_QUEUES.includes(item.queueType);
const isInRoom = (item) => item.queueType === null || item.queueType === undefined;

/** T02: bắt đầu khám (giai đoạn 1): tạo hồ sơ khám. Cân nặng / thân nhiệt lần đầu và bệnh sử là TÙY CHỌN (ghi sau ở các tab Sinh hiệu / Hồ sơ khám),
 *  nhưng phải có ít nhất một lần đo đủ cả hai chỉ số trước khi chỉ định cận lâm sàng hoặc chốt ca. */
function StartExam({ visit, onDone }) {
  const [form, setForm] = useState({ weightKg: '', temperatureC: '', anamnesis: '' });
  const [errors, setErrors] = useState({});
  const action = useAction();
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const submit = async (event) => {
    event.preventDefault();
    const found = {};
    if (form.weightKg !== '' && !(Number(form.weightKg) > 0)) found.weightKg = 'Cân nặng phải lớn hơn 0.';
    if (form.temperatureC !== '' && Number.isNaN(Number(form.temperatureC))) found.temperatureC = 'Thân nhiệt không hợp lệ.';
    setErrors(found);
    if (Object.keys(found).length) return;
    const hasVitals = form.weightKg !== '' || form.temperatureC !== '';
    const body = { vitalSigns: hasVitals ? { weightKg: toNumber(form.weightKg), temperatureC: toNumber(form.temperatureC) } : null, anamnesis: blankToNull(form.anamnesis) };
    const result = await action.run(() => visitsApi.startExam(visit.id, body).catch((problem) => { setErrors(mapFieldErrors(problem)); throw problem; }));
    if (result) onDone();
  };
  return <Panel title="Bắt đầu khám (giai đoạn 1)" subtitle="Thú cưng đã vào phòng. Phí khám tổng quát đã được thu lúc tiếp đón. Có thể ghi cân nặng / thân nhiệt và bệnh sử ngay hoặc nhập sau.">
    <form onSubmit={submit} noValidate className="ui-stack" style={{ gap: 16 }}>
      <Notice notice={action.notice} onClose={action.clear} />
      <div className="ui-grid">
        <Field label="Cân nặng (kg)" error={errors.weightKg}><Input type="number" step="0.1" min="0" value={form.weightKg} onChange={set('weightKg')} autoFocus /></Field>
        <Field label="Thân nhiệt (°C)" error={errors.temperatureC}><Input type="number" step="0.1" value={form.temperatureC} onChange={set('temperatureC')} /></Field>
        <Field label="Bệnh sử (chủ nuôi kể)" wide error={errors.anamnesis} hint={visit.reasonForVisit ? `Lý do đến khám lễ tân ghi: ${visit.reasonForVisit}` : undefined}><Textarea value={form.anamnesis} onChange={set('anamnesis')} maxLength={4000} /></Field>
      </div>
      <div><Button type="submit" variant="primary" busy={action.busy}>Bắt đầu khám</Button></div>
    </form>
  </Panel>;
}

const BLANK_VITALS = { weightKg: '', temperatureC: '', heartRateBpm: '', respiratoryRateBpm: '', bodyConditionScore: '', mucousMembraneColor: '', capillaryRefillSec: '', notes: '' };

/** Sinh hiệu: mỗi lần đo là MỘT dòng mới (đo lại thì thêm dòng, không ghi đè). Cần ít nhất một lần đo đủ cân nặng + thân nhiệt trước khi chỉ định CLS / chốt ca. */
function VitalsTab({ visit, onChanged, editable }) {
  const [form, setForm] = useState(BLANK_VITALS);
  const [errors, setErrors] = useState({});
  const action = useAction();
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const save = async (event) => {
    event.preventDefault();
    const body = {
      weightKg: toNumber(form.weightKg), temperatureC: toNumber(form.temperatureC), heartRateBpm: toNumber(form.heartRateBpm), respiratoryRateBpm: toNumber(form.respiratoryRateBpm),
      bodyConditionScore: toNumber(form.bodyConditionScore), mucousMembraneColor: blankToNull(form.mucousMembraneColor), capillaryRefillSec: toNumber(form.capillaryRefillSec), notes: blankToNull(form.notes),
    };
    if (Object.values(body).every((value) => value === null)) { setErrors({}); action.setNotice({ type: 'error', text: 'Nhập ít nhất một chỉ số.' }); return; }
    setErrors({});
    if (await action.run(() => visitsApi.addVitalSigns(visit.id, body).catch((problem) => { setErrors(mapFieldErrors(problem)); throw problem; }), 'Đã ghi lần đo.')) { setForm(BLANK_VITALS); onChanged(); }
  };
  const complete = (visit.vitalSigns || []).some((row) => row.weightKg != null && row.temperatureC != null);
  return <div className="ui-stack">
    <Panel title="Các lần đo sinh hiệu" subtitle="Theo thứ tự thời gian. Cần có ít nhất một lần đo đủ cả cân nặng và thân nhiệt trước khi chỉ định cận lâm sàng hoặc chốt ca." flush>
      {editable && !complete && <div className="ui-alert warning" role="status"><span>Chưa có lần đo nào đủ cả cân nặng và thân nhiệt.</span></div>}
      <VitalSignsTable rows={visit.vitalSigns || []} />
    </Panel>
    {editable && <Panel title="Ghi một lần đo mới" subtitle="Chỉ nhập các chỉ số đã đo; để trống chỉ số không đo.">
      <form onSubmit={save} noValidate className="ui-stack" style={{ gap: 14 }}>
        <Notice notice={action.notice} onClose={action.clear} />
        <div className="ui-grid">
          <Field label="Cân nặng (kg)" error={errors.weightKg}><Input type="number" step="0.1" min="0" value={form.weightKg} onChange={set('weightKg')} /></Field>
          <Field label="Thân nhiệt (°C)" error={errors.temperatureC}><Input type="number" step="0.1" value={form.temperatureC} onChange={set('temperatureC')} /></Field>
          <Field label="Nhịp tim (lần/phút)" error={errors.heartRateBpm}><Input type="number" min="0" value={form.heartRateBpm} onChange={set('heartRateBpm')} /></Field>
          <Field label="Nhịp thở (lần/phút)" error={errors.respiratoryRateBpm}><Input type="number" min="0" value={form.respiratoryRateBpm} onChange={set('respiratoryRateBpm')} /></Field>
          <Field label="Điểm thể trạng BCS (1–9)" error={errors.bodyConditionScore}><Input type="number" min="1" max="9" value={form.bodyConditionScore} onChange={set('bodyConditionScore')} /></Field>
          <Field label="Màu niêm mạc" error={errors.mucousMembraneColor}><Input value={form.mucousMembraneColor} onChange={set('mucousMembraneColor')} maxLength={30} placeholder="Hồng, nhợt, vàng…" /></Field>
          <Field label="Làm đầy mao mạch CRT (giây)" error={errors.capillaryRefillSec}><Input type="number" step="0.1" min="0" value={form.capillaryRefillSec} onChange={set('capillaryRefillSec')} /></Field>
          <Field label="Ghi chú" wide error={errors.notes}><Input value={form.notes} onChange={set('notes')} maxLength={255} /></Field>
        </div>
        <div><Button type="submit" variant="primary" busy={action.busy}>Ghi lần đo</Button></div>
      </form>
    </Panel>}
  </div>;
}

/** Hồ sơ khám (medical_records): bệnh sử, kết quả khám lâm sàng, chẩn đoán sơ bộ. Có khóa lạc quan (version); khóa hẳn từ lúc chốt ca. */
function RecordTab({ visit, onChanged }) {
  const record = visit.medicalRecord || {};
  const [form, setForm] = useState({ anamnesis: record.anamnesis || '', clinicalFindings: record.clinicalFindings || '', preliminaryDiagnosis: record.preliminaryDiagnosis || '' });
  const [errors, setErrors] = useState({});
  const action = useAction();
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const save = async (event) => {
    event.preventDefault();
    const body = { anamnesis: form.anamnesis, clinicalFindings: form.clinicalFindings, preliminaryDiagnosis: form.preliminaryDiagnosis, version: record.version ?? null };
    if (await action.run(() => visitsApi.updateMedicalRecord(visit.id, body).catch((problem) => { setErrors(mapFieldErrors(problem)); throw problem; }), 'Đã lưu hồ sơ khám.')) onChanged();
  };
  return <Panel title="Hồ sơ khám" subtitle="Bệnh sử chủ nuôi kể, kết quả khám lâm sàng và chẩn đoán sơ bộ. Chẩn đoán xác định, phác đồ và lời dặn nhập ở tab Kết luận.">
    <form onSubmit={save} noValidate className="ui-stack" style={{ gap: 14 }}>
      <Notice notice={action.notice} onClose={action.clear} />
      <Field label="Bệnh sử" error={errors.anamnesis}><Textarea value={form.anamnesis} onChange={set('anamnesis')} maxLength={4000} rows={3} /></Field>
      <Field label="Kết quả khám lâm sàng" error={errors.clinicalFindings} hint="Tim phổi, da, niêm mạc, hạch…"><Textarea value={form.clinicalFindings} onChange={set('clinicalFindings')} maxLength={4000} rows={3} /></Field>
      <Field label="Chẩn đoán sơ bộ" error={errors.preliminaryDiagnosis}><Textarea value={form.preliminaryDiagnosis} onChange={set('preliminaryDiagnosis')} maxLength={2000} rows={2} /></Field>
      <div><Button type="submit" variant="primary" busy={action.busy}>Lưu hồ sơ khám</Button></div>
    </form>
  </Panel>;
}

function ReasonDialog({ title, label = 'Lý do *', submitLabel, danger, onClose, onSubmit, children }) {
  const [reason, setReason] = useState('');
  return <FormDialog title={title} onClose={onClose} submitLabel={submitLabel} danger={danger} onSubmit={async () => { if (!reason.trim()) throw new Error('Nhập lý do.'); await onSubmit(reason.trim()); }}>
    {children}<Field label={label}><Textarea value={reason} onChange={(event) => setReason(event.target.value)} maxLength={500} autoFocus /></Field>
  </FormDialog>;
}

/** Chọn nhiều dịch vụ trong danh mục để chỉ định. Dịch vụ tại phòng và cận lâm sàng đi hai đường khác nhau (T04 / dịch vụ tại phòng). */
function ServicePicker({ services, picked, setPicked, withQuantity }) {
  const toggle = (id) => setPicked({ ...picked, [id]: { quantity: 1, notes: '', ...picked[id], checked: !picked[id]?.checked } });
  const patch = (id, key, value) => setPicked({ ...picked, [id]: { quantity: 1, notes: '', checked: true, ...picked[id], [key]: value } });
  return <div className="ui-stack" style={{ gap: 10 }}>
    {services.map((service) => <div key={service.id} style={{ display: 'grid', gridTemplateColumns: withQuantity ? 'minmax(0, 1fr) 80px minmax(0, 1fr)' : 'minmax(0, 1fr)', gap: 12, alignItems: 'center' }}>
      <label className="ui-check"><input type="checkbox" checked={Boolean(picked[service.id]?.checked)} onChange={() => toggle(service.id)} /><span><strong>{service.name}</strong> <small className="ui-muted">{service.categoryName} · {service.price == null ? 'chưa có giá' : formatMoney(service.price)}</small></span></label>
      {withQuantity && <Input type="number" min="1" max="20" aria-label={`Số lượng ${service.name}`} disabled={!picked[service.id]?.checked} value={picked[service.id]?.quantity ?? 1} onChange={(event) => patch(service.id, 'quantity', event.target.value)} />}
      {withQuantity && <Input aria-label={`Ghi chú ${service.name}`} placeholder="Ghi chú" disabled={!picked[service.id]?.checked} value={picked[service.id]?.notes ?? ''} onChange={(event) => patch(service.id, 'notes', event.target.value)} maxLength={500} />}
    </div>)}
  </div>;
}

function ServicesTab({ visit, onChanged }) {
  const catalog = useAsync(() => catalogApi.services(), []);
  const [inRoomPicked, setInRoomPicked] = useState({});
  const [labPicked, setLabPicked] = useState({});
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  const navigate = useNavigate();
  const all = catalog.data || [];
  const inRoomServices = all.filter(isInRoom);
  const paraclinicalServices = all.filter(isParaclinical);
  const chosen = (picked) => Object.entries(picked).filter(([, value]) => value.checked);

  const orderInRoom = async () => {
    const services = chosen(inRoomPicked).map(([id, value]) => ({ serviceId: id, quantity: Number(value.quantity) || 1, notes: blankToNull(value.notes) }));
    if (await action.run(() => visitsApi.inRoomServices(visit.id, services), 'Đã chỉ định dịch vụ tại phòng. Bấm “Đã thực hiện” khi làm xong.')) { setInRoomPicked({}); onChanged(); }
  };
  const orderParaclinical = async () => {
    const result = await action.run(() => visitsApi.paraclinicalOrders(visit.id, chosen(labPicked).map(([id]) => id), null));
    if (result) { setLabPicked({}); navigate('/vet/today', { replace: true }); }
  };
  const perform = async (row) => { if (await action.run(() => paraclinicApi.perform(row.id), `Đã ghi nhận thực hiện ${row.serviceName}.`)) onChanged(); };

  return <div className="ui-stack">
    <Notice notice={action.notice} onClose={action.clear} />
    <Panel title="Dịch vụ đã chỉ định" subtitle="Mỗi dịch vụ có một hóa đơn riêng. Hủy dịch vụ thì hóa đơn của nó tự hủy; dịch vụ đã thu tiền không hủy được." flush>
      <Table caption="Dịch vụ đã chỉ định" empty="Chưa có dịch vụ nào." rows={visit.services || []} columns={[
        { title: 'Dịch vụ', render: (row) => <><strong>{row.serviceName}</strong><small>{serviceCategoryLabel(row.category)}{row.notes ? ` · ${row.notes}` : ''}</small></> },
        { title: 'SL', key: 'quantity' }, { title: 'Thành tiền', align: 'right', render: (row) => formatMoney(row.subtotal) },
        { title: 'Trạng thái', render: (row) => <Badge map={SERVICE_DETAIL_STATUS} value={row.status} /> },
        { title: '', align: 'right', render: (row) => <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
          {isInRoom(row) && ['PENDING', 'IN_PROGRESS'].includes(row.status) && <Button className="small primary" onClick={() => perform(row)}>Đã thực hiện</Button>}
          {row.queueType !== 'DOCTOR' && ['PENDING', 'IN_PROGRESS'].includes(row.status) && <Button className="small danger" onClick={() => setDialog({ row })}>Hủy</Button>}</div> }]} />
    </Panel>
    <Panel title="Dịch vụ tại phòng" subtitle="Tiêm phòng, tẩy giun, thủ thuật, phẫu thuật… làm ngay tại phòng khám (không qua hàng đợi cận lâm sàng). Ca vẫn đang khám.">
      <AsyncView state={catalog} isEmpty={() => false}>
        {() => inRoomServices.length === 0 ? <p className="ui-muted">Chưa có dịch vụ tại phòng trong danh mục.</p> : <div className="ui-stack" style={{ gap: 12 }}>
          <ServicePicker services={inRoomServices} picked={inRoomPicked} setPicked={setInRoomPicked} withQuantity />
          <div><Button variant="primary" disabled={chosen(inRoomPicked).length === 0} busy={action.busy} onClick={orderInRoom}>Chỉ định dịch vụ tại phòng</Button></div></div>}
      </AsyncView>
    </Panel>
    <Panel title="Chỉ định cận lâm sàng" subtitle="Xét nghiệm / chẩn đoán hình ảnh: cần có cân nặng và thân nhiệt, dịch vụ tại phòng phải xong hoặc hủy. Ca chuyển sang chờ kết quả và bạn được giải phóng để gọi ca khác.">
      <AsyncView state={catalog} isEmpty={() => false}>
        {() => paraclinicalServices.length === 0 ? <p className="ui-muted">Chưa có dịch vụ cận lâm sàng trong danh mục.</p> : <div className="ui-stack" style={{ gap: 12 }}>
          <ServicePicker services={paraclinicalServices} picked={labPicked} setPicked={setLabPicked} />
          <div><Button variant="primary" disabled={chosen(labPicked).length === 0} busy={action.busy} onClick={orderParaclinical}>Chỉ định và chuyển sang chờ kết quả</Button></div></div>}
      </AsyncView>
    </Panel>
    {dialog && <ReasonDialog title={`Hủy dịch vụ — ${dialog.row.serviceName}`} submitLabel="Hủy dịch vụ" danger onClose={() => setDialog(null)} onSubmit={async (reason) => { await paraclinicApi.cancel(dialog.row.id, reason); setDialog(null); onChanged(); }} />}
  </div>;
}

const blankLine = () => ({ medicineId: '', quantity: 1, dosage: '', frequency: '', durationDays: 3, instructions: '' });

/** Đơn thuốc v3.0: chỉ định chuyên môn, không giá. Kê vượt tồn kho vẫn lưu, chỉ cảnh báo (khách có thể mua ngoài). */
function PrescriptionTab({ visit }) {
  const medicines = useAsync(() => pharmacyApi.medicines({ active: true, size: 100 }), []);
  const existing = useAsync(() => visitsApi.prescription(visit.id).catch((problem) => { if (problem.status === 404) return null; throw problem; }), [visit.id]);
  const [lines, setLines] = useState([blankLine()]);
  const [notes, setNotes] = useState('');
  const [warnings, setWarnings] = useState([]);
  const [allergyWarning, setAllergyWarning] = useState('');
  const [loaded, setLoaded] = useState(false);
  const [focusLast, setFocusLast] = useState(false);
  const action = useAction();
  const current = existing.data || null;

  useEffect(() => {
    if (existing.loading || loaded || existing.data === undefined) return;
    if (current) {
      setLines(current.items.map((item) => ({ medicineId: String(item.medicineId), quantity: item.quantity, dosage: item.dosage, frequency: item.frequency, durationDays: item.durationDays, instructions: item.instructions || '' })));
      setNotes(current.doctorNotes || '');
    }
    setLoaded(true);
  }, [existing.data, existing.loading, current, loaded]);

  const meds = medicines.data?.items || [];
  const setLine = (index, key, value) => setLines(lines.map((line, position) => (position === index ? { ...line, [key]: value } : line)));
  const save = async () => {
    const items = lines.filter((line) => line.medicineId);
    if (!items.length) { action.setNotice({ type: 'error', text: 'Chọn ít nhất một loại thuốc.' }); return; }
    if (items.some((line) => !line.dosage.trim() || !line.frequency.trim())) { action.setNotice({ type: 'error', text: 'Mỗi thuốc cần có liều dùng và tần suất.' }); return; }
    if (new Set(items.map((line) => line.medicineId)).size !== items.length) { action.setNotice({ type: 'error', text: 'Mỗi thuốc chỉ một dòng — hãy gộp các dòng trùng.' }); return; }
    const payload = { doctorNotes: blankToNull(notes), items: items.map((line) => ({ medicineId: line.medicineId, quantity: Number(line.quantity), dosage: line.dosage.trim(), frequency: line.frequency.trim(), durationDays: Number(line.durationDays), instructions: blankToNull(line.instructions) })) };
    const saved = await action.run(() => visitsApi.savePrescription(visit.id, payload), 'Đã lưu đơn thuốc. Hóa đơn thuốc nháp tự tạo khi bạn chốt ca; kho chỉ bị trừ khi khách mua tại phòng khám.');
    if (saved && saved !== true) { setWarnings(saved.stockWarnings || []); setAllergyWarning(saved.allergyWarning || ''); existing.refresh(); }
  };

  const addLine = () => { setLines([...lines, blankLine()]); setFocusLast(true); };
  useEffect(() => {
    if (!focusLast) return;
    setFocusLast(false);
    const target = document.getElementById(`rx-medicine-${lines.length - 1}`);
    if (target) target.focus();
  }, [focusLast, lines.length]);
  const chosenElsewhere = (index) => new Set(lines.filter((_, position) => position !== index).map((line) => line.medicineId).filter(Boolean));

  return <Panel title="Đơn thuốc" subtitle="Đơn không có giá. Kê vượt tồn kho hoặc trùng tiền sử dị ứng vẫn lưu được (chỉ cảnh báo). Sửa được khi ca còn đang khám và hóa đơn thuốc chưa được thanh toán.">
    <Notice notice={action.notice} onClose={action.clear} />
    {allergyWarning && <div className="ui-alert warning" role="alert"><span><strong>Cảnh báo dị ứng:</strong> {allergyWarning}</span></div>}
    {warnings.length > 0 && <div className="ui-alert warning" role="status"><span><strong>Cảnh báo tồn kho:</strong> {warnings.join('; ')}. Khách có thể mua ngoài phòng khám.</span></div>}
    <AsyncView state={medicines}>{() => <div className="ui-stack" style={{ gap: 14 }}>
      <div className="ui-table-scroll"><table className="ui-table rx-table">
        <caption className="ui-sr-only">Đơn thuốc</caption>
        <thead><tr>
          <th className="rx-stt">STT</th><th>Thuốc</th><th>Số lượng</th><th>Số ngày dùng</th><th>Liều dùng *</th><th>Tần suất *</th><th>Hướng dẫn</th><th aria-label="Thao tác" />
        </tr></thead>
        <tbody>{lines.map((line, index) => {
          const taken = chosenElsewhere(index);
          const row = `dòng ${index + 1}`;
          return <tr key={index}>
            <td className="rx-stt"><strong>{index + 1}</strong></td>
            <td className="rx-medicine"><Select id={`rx-medicine-${index}`} aria-label={`Thuốc ${row}`} value={line.medicineId} onChange={(event) => setLine(index, 'medicineId', event.target.value)}>
              <option value="">Chọn thuốc</option>
              {meds.map((medicine) => <option key={medicine.id} value={medicine.id} disabled={taken.has(String(medicine.id))}>{medicine.name} — {medicine.baseUnit} — tồn {medicine.stockQuantity}</option>)}</Select></td>
            <td className="rx-number"><Input type="number" min="1" aria-label={`Số lượng ${row}`} value={line.quantity} onChange={(event) => setLine(index, 'quantity', event.target.value)} /></td>
            <td className="rx-number"><Input type="number" min="1" max="365" aria-label={`Số ngày dùng ${row}`} value={line.durationDays} onChange={(event) => setLine(index, 'durationDays', event.target.value)} /></td>
            <td className="rx-text"><Input aria-label={`Liều dùng ${row}`} value={line.dosage} placeholder="1 viên/lần" onChange={(event) => setLine(index, 'dosage', event.target.value)} maxLength={100} /></td>
            <td className="rx-text"><Input aria-label={`Tần suất ${row}`} value={line.frequency} placeholder="2 lần/ngày" onChange={(event) => setLine(index, 'frequency', event.target.value)} maxLength={100} /></td>
            <td className="rx-note"><Input aria-label={`Hướng dẫn ${row}`} value={line.instructions} placeholder="Sau ăn, uống nhiều nước…" onChange={(event) => setLine(index, 'instructions', event.target.value)} maxLength={2000} /></td>
            <td className="rx-remove">{lines.length > 1 && <Button className="small danger" aria-label={`Bỏ ${row}`} title="Bỏ dòng này" onClick={() => setLines(lines.filter((_, position) => position !== index))}>×</Button>}</td>
          </tr>;
        })}</tbody>
      </table></div>
      <div><Button onClick={addLine}>+ Thêm thuốc</Button></div>
      <Field label="Lời dặn chung"><Textarea value={notes} onChange={(event) => setNotes(event.target.value)} maxLength={4000} rows={2} /></Field>
      <div className="ui-actions"><Button variant="primary" busy={action.busy} onClick={save}>{current ? 'Cập nhật đơn thuốc' : 'Lưu đơn thuốc'}</Button></div>
    </div>}</AsyncView>
  </Panel>;
}

/** Bản nháp kết luận giữ ở trang cha (và sessionStorage) để không mất khi đổi tab hoặc tải lại; chỉ gửi lên máy chủ lúc chốt ca. */
const DRAFT_KEY = (visitId) => `vetclinic.conclusion.${visitId}`;
function loadDraft(visit) {
  const record = visit.medicalRecord || {};
  // Chẩn đoán chốt mặc định lấy chẩn đoán xác định đã có, nếu chưa thì chẩn đoán sơ bộ (ca không làm cận lâm sàng được dùng luôn)
  const base = { finalDiagnosis: record.finalDiagnosis || record.preliminaryDiagnosis || '', treatmentPlan: record.treatmentPlan || '', doctorAdvice: record.doctorAdvice || '', followUpDate: record.followUpDate || '' };
  try { const saved = JSON.parse(sessionStorage.getItem(DRAFT_KEY(visit.id)) || 'null'); if (saved) return { ...base, ...saved }; } catch (problem) { /* không có sessionStorage: dùng dữ liệu máy chủ */ }
  return base;
}

/** Kết luận chuyên môn: nhập SAU khi có kết quả khám / cận lâm sàng, TRƯỚC khi kê đơn thuốc. */
function ConclusionTab({ draft, setDraft, onNext }) {
  const set = (key) => (event) => setDraft({ ...draft, [key]: event.target.value });
  return <Panel title="Kết luận" subtitle="Ghi chẩn đoán, phác đồ và lời dặn dựa trên kết quả khám và cận lâm sàng. Bước tiếp theo là kê đơn thuốc, cuối cùng mới chốt ca. Bản nháp được giữ khi bạn đổi tab; chỉ được ghi vào hồ sơ lúc chốt ca.">
    <div className="ui-stack" style={{ gap: 14 }}>
      <Field label="Chẩn đoán xác định *"><Textarea value={draft.finalDiagnosis} onChange={set('finalDiagnosis')} maxLength={2000} rows={3} autoFocus /></Field>
      <Field label="Phác đồ điều trị"><Textarea value={draft.treatmentPlan} onChange={set('treatmentPlan')} maxLength={4000} rows={3} /></Field>
      <Field label="Lời dặn cho chủ nuôi"><Textarea value={draft.doctorAdvice} onChange={set('doctorAdvice')} maxLength={2000} rows={3} /></Field>
      <Field label="Hẹn tái khám"><Input type="date" value={draft.followUpDate} onChange={set('followUpDate')} /></Field>
      <div><Button variant="primary" onClick={onNext}>Tiếp tục: kê đơn thuốc</Button></div>
    </div>
  </Panel>;
}

/** Chốt ca: xem lại kết luận + dịch vụ + đơn thuốc rồi chốt và chuyển thanh toán. */
function CloseTab({ visit, draft, onClosed, goTo }) {
  const prescription = useAsync(() => visitsApi.prescription(visit.id).catch((problem) => { if (problem.status === 404) return null; throw problem; }), [visit.id]);
  const [confirming, setConfirming] = useState(false);
  const diagnosis = draft.finalDiagnosis.trim();
  const unfinished = (visit.services || []).filter((row) => ['PENDING', 'IN_PROGRESS'].includes(row.status) && isInRoom(row));
  const noVitals = !(visit.vitalSigns || []).some((row) => row.weightKg != null && row.temperatureC != null);
  const medicines = prescription.data?.items?.length || 0;
  return <Panel title="Chốt ca" subtitle="Bước cuối: sau khi chốt, hồ sơ bị khóa (không sửa kết luận, dịch vụ, đơn thuốc) và ca chuyển sang chờ thanh toán. Hóa đơn dịch vụ đã có sẵn cho thu ngân; thuốc bán theo đơn nếu khách chọn mua.">
    <div className="ui-stack" style={{ gap: 14 }}>
      {!diagnosis && <div className="ui-alert error" role="alert"><span>Chưa có chẩn đoán xác định — bắt buộc để chốt ca. Nhập ở tab Kết luận.</span><Button className="small" onClick={() => goTo('conclusion')}>Đến tab Kết luận</Button></div>}
      {noVitals && <div className="ui-alert error" role="alert"><span>Chưa có lần đo nào đủ cả cân nặng và thân nhiệt. Nhập ở tab Sinh hiệu.</span><Button className="small" onClick={() => goTo('vitals')}>Đến tab Sinh hiệu</Button></div>}
      {unfinished.length > 0 && <div className="ui-alert warning" role="status"><span>Còn dịch vụ tại phòng chưa xong: {unfinished.map((row) => row.serviceName).join(', ')}. Hãy bấm “Đã thực hiện” hoặc hủy trước khi chốt.</span></div>}
      <Facts items={[['Chẩn đoán', diagnosis || null], ['Phác đồ điều trị', draft.treatmentPlan], ['Lời dặn cho chủ nuôi', draft.doctorAdvice], ['Hẹn tái khám', draft.followUpDate ? formatDate(draft.followUpDate) : null], ['Đơn thuốc', prescription.loading ? 'Đang tải…' : (medicines ? `Đã kê ${medicines} loại thuốc` : 'Chưa kê đơn (không bán thuốc theo đơn)')]]} />
      <div className="ui-actions">
        <Button onClick={() => goTo('conclusion')}>Sửa kết luận</Button>
        <Button onClick={() => goTo('prescription')}>Sửa đơn thuốc</Button>
        <Button variant="primary" onClick={() => setConfirming(true)} disabled={!diagnosis}>Chốt ca và chuyển thanh toán</Button>
      </div>
    </div>
    {confirming && <ConfirmDialog title="Chốt ca khám" confirmLabel="Chốt ca" onClose={() => setConfirming(false)} onConfirm={async () => {
      await visitsApi.closeVisit(visit.id, { finalDiagnosis: diagnosis, treatmentPlan: blankToNull(draft.treatmentPlan), doctorAdvice: blankToNull(draft.doctorAdvice), followUpDate: draft.followUpDate || null });
      try { sessionStorage.removeItem(DRAFT_KEY(visit.id)); } catch (problem) { /* bỏ qua */ }
      onClosed();
    }}>Hồ sơ sẽ bị khóa. Hãy chắc chắn đã lưu đơn thuốc và hoàn tất / hủy các dịch vụ tại phòng. Cần có chẩn đoán xác định và một lần đo đủ cân nặng + thân nhiệt.</ConfirmDialog>}
  </Panel>;
}

export default function VisitPage() {
  const { visitId } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const visit = useAsync(() => visitsApi.get(visitId), [visitId]);
  const [tab, setTab] = useState('vitals');
  const [draft, setDraftState] = useState(null);
  const [dialog, setDialog] = useState(null);
  const action = useAction();
  usePolling(visit.refresh, 15000);
  const backToQueue = () => navigate('/vet/today', { replace: true });
  const setDraft = (next) => { setDraftState(next); try { sessionStorage.setItem(DRAFT_KEY(visitId), JSON.stringify(next)); } catch (problem) { /* không lưu được thì giữ trong bộ nhớ */ } };

  return <StaffLayout area="vet" title="Hồ sơ khám">
    <AsyncView state={visit}>
      {(v) => {
        const mine = sameId(v.doctorId, user.id);
        const hasResults = v.labResults?.length > 0 || v.imagingResults?.length > 0;
        const conclusion = draft || loadDraft(v);
        const record = v.medicalRecord;
        const vitals = v.vitalSigns || [];
        const latest = (key) => [...vitals].reverse().find((row) => row[key] != null)?.[key];
        return <>
          <PageHead eyebrow={v.visitCode} title={`${v.petName} (${v.speciesName})`} actions={<><Link className="ui-button" to="/vet/today">← Hàng đợi</Link><Link className="ui-button" to={`/vet/history?petId=${v.petId}`}>Bệnh án trước đây</Link></>}>
            Tiếp nhận {formatDateTime(v.checkedInAt)}{v.emergency ? ' · CẤP CỨU' : ''} · Phòng {v.assignedRoom || '—'}
          </PageHead>
          <Notice notice={action.notice} onClose={action.clear} />
          <Panel title="Tóm tắt" actions={<Badge map={VISIT_STATUS} value={v.status} />}>
            <Facts items={[['Lý do đến khám', v.reasonForVisit], ['Cân nặng (lần đo gần nhất)', latest('weightKg') != null ? `${formatNumber(latest('weightKg'), 2)} kg` : null], ['Thân nhiệt (lần đo gần nhất)', latest('temperatureC') != null ? `${formatNumber(latest('temperatureC'), 1)} °C` : null], ['Chẩn đoán', record?.finalDiagnosis || record?.preliminaryDiagnosis]]} />
          </Panel>
          {!mine && v.doctorId && <div className="ui-alert warning"><span>Ca này thuộc bác sĩ khác — bạn chỉ xem được.</span></div>}

          {mine && v.status === 'WAITING' && (v.ticket ? <>
            <div className="ui-alert info"><span>Phiếu số {v.ticket.ticketNo} đang trong hàng đợi của bạn. Bấm “Gọi” ở trang hàng đợi; thú cưng vào phòng thì bắt đầu khám tại đây.</span></div>
            <StartExam visit={v} onDone={visit.refresh} />
          </> : <div className="ui-alert warning"><span>Khách chưa nộp phí khám nên ca chưa có phiếu và chưa vào hàng đợi. Nộp phí tại quầy thu ngân xong mới bắt đầu khám được.</span></div>)}

          {mine && v.status === 'IN_PROGRESS' && <>
            <Tabs value={tab} onChange={setTab} tabs={[{ key: 'vitals', label: 'Sinh hiệu' }, { key: 'record', label: 'Hồ sơ khám' }, { key: 'services', label: 'Dịch vụ & cận lâm sàng' }, { key: 'conclusion', label: 'Kết luận' }, { key: 'prescription', label: 'Đơn thuốc' }, { key: 'close', label: 'Chốt ca' }]} />
            {tab === 'vitals' && <VitalsTab visit={v} onChanged={visit.refresh} editable />}
            {tab === 'record' && <RecordTab visit={v} onChanged={visit.refresh} />}
            {tab === 'services' && <ServicesTab visit={v} onChanged={visit.refresh} />}
            {tab === 'conclusion' && <ConclusionTab draft={conclusion} setDraft={setDraft} onNext={() => setTab('prescription')} />}
            {tab === 'prescription' && <PrescriptionTab visit={v} />}
            {tab === 'close' && <CloseTab visit={v} draft={conclusion} onClosed={backToQueue} goTo={setTab} />}
            <div className="ui-actions" style={{ marginTop: 18 }}><Button variant="danger" onClick={() => setDialog('cancel-exam')}>Hủy ca / chuyển viện</Button></div>
          </>}

          {v.status === 'WAITING_RESULTS' && <div className="ui-alert info"><span>Đang chờ kỹ thuật viên hoàn tất xét nghiệm / chẩn đoán hình ảnh. Bạn được giải phóng để gọi ca khác và sẽ nhận thông báo khi đủ kết quả (ca tự lên đầu hàng đợi).</span></div>}

          {mine && v.status === 'RESULTS_READY' && <Panel title="Đã có đủ kết quả" subtitle="Ca đang đứng đầu hàng đợi của bạn. Gọi số ở trang hàng đợi, thú cưng vào phòng thì bắt đầu giai đoạn 2.">
            <div className="ui-actions">
              <Button variant="primary" busy={action.busy} onClick={async () => { if (await action.run(() => visitsApi.startPhase2(v.id), 'Đã bắt đầu giai đoạn 2.')) visit.refresh(); }}>Bắt đầu giai đoạn 2</Button>
              <Button variant="danger" onClick={() => setDialog('abandon')}>Khách bỏ về — đóng ca</Button>
            </div></Panel>}

          {['WAITING_PAYMENT', 'COMPLETED'].includes(v.status) && <div className="ui-alert info"><span>Hồ sơ đã chốt{v.status === 'COMPLETED' ? ' và hoàn tất' : ', đang chờ thanh toán'}; không chỉnh sửa được.{v.status === 'WAITING_PAYMENT' && mine ? ' Cần sửa thì mở lại hồ sơ (hóa đơn dịch vụ đã thu giữ nguyên; dịch vụ thêm mới có hóa đơn mới).' : ''}</span>
            {v.status === 'WAITING_PAYMENT' && (mine || user.role === 'ADMIN') && <Button className="small" onClick={() => setDialog('reopen')}>Mở lại hồ sơ</Button>}</div>}

          {v.status !== 'IN_PROGRESS' && vitals.length > 0 && <Panel title="Sinh hiệu" flush><VitalSignsTable rows={vitals} /></Panel>}
          {v.status !== 'IN_PROGRESS' && record && <Panel title={`Hồ sơ khám${record.locked ? ' (đã khóa)' : ''}`}>
            <Facts items={[['Bệnh sử', record.anamnesis], ['Kết quả khám lâm sàng', record.clinicalFindings], ['Chẩn đoán sơ bộ', record.preliminaryDiagnosis], ['Chẩn đoán xác định', record.finalDiagnosis],
              ['Phác đồ điều trị', record.treatmentPlan], ['Lời dặn cho chủ nuôi', record.doctorAdvice], ['Hẹn tái khám', record.followUpDate ? formatDate(record.followUpDate) : null]]} /></Panel>}
          {(v.services?.length > 0 && v.status !== 'IN_PROGRESS') && <Panel title="Dịch vụ đã chỉ định" flush><Table caption="Dịch vụ" rows={v.services} columns={[
            { title: 'Dịch vụ', render: (row) => <><strong>{row.serviceName}</strong><small>{serviceCategoryLabel(row.category)}</small></> }, { title: 'Trạng thái', render: (row) => <Badge map={SERVICE_DETAIL_STATUS} value={row.status} /> }]} /></Panel>}
          {hasResults && <Panel title="Kết quả cận lâm sàng">
            <div className="ui-stack">{v.labResults?.length > 0 && <LabResultsTable results={v.labResults} />}{v.imagingResults?.length > 0 && <ImagingResults results={v.imagingResults} />}</div>
          </Panel>}

          {dialog === 'abandon' && <ConfirmDialog title="Đóng ca — khách bỏ về" danger confirmLabel="Đóng ca" onClose={() => setDialog(null)} onConfirm={async () => { await visitsApi.abandon(v.id); backToQueue(); }}>
            Chỉ dùng khi chủ nuôi đã bỏ về và không quay lại đọc kết quả. Kết quả cận lâm sàng được lưu vào bệnh án; hóa đơn dịch vụ đã làm giữ chờ thanh toán (công nợ), không tạo hóa đơn mới.</ConfirmDialog>}
          {dialog === 'cancel-exam' && <ReasonDialog title={`Hủy ca ${v.visitCode}`} submitLabel="Hủy ca" danger onClose={() => setDialog(null)} onSubmit={async (reason) => { await visitsApi.cancelExam(v.id, reason); backToQueue(); }}>
            <p className="ui-confirm-text">Chỉ hủy được khi chưa có dịch vụ tại phòng nào hoàn thành. Mọi dịch vụ bị hủy kèm hóa đơn; khách không mất phí.</p></ReasonDialog>}
          {dialog === 'reopen' && <ReasonDialog title="Mở lại hồ sơ" submitLabel="Mở lại" onClose={() => setDialog(null)} onSubmit={async (reason) => { await visitsApi.reopen(v.id, reason); setDialog(null); visit.refresh(); }}>
            <p className="ui-confirm-text">Ca trở lại “Đang khám”. Đơn thuốc đã có hóa đơn thuốc chưa hủy thì không sửa được. Sau khi chỉnh, chốt lại ca.</p></ReasonDialog>}
        </>;
      }}
    </AsyncView>
  </StaffLayout>;
}
