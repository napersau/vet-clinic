import { useEffect, useState } from 'react';
import { paraclinicApi } from '../../api/endpoints';
import { API_URL } from '../../config';
import { Badge, Facts, Table } from '../ui';
import { LAB_EVALUATION, serviceCategoryLabel, SERVICE_DETAIL_STATUS, VISIT_STATUS } from '../../lib/labels';
import { formatDate, formatDateTime, formatMoney, formatNumber } from '../../lib/format';

/** Hết hạn còn bao lâu thì xin lại URL: trước hạn 1 phút để ảnh đang mở không bị đứt; tối thiểu 5 giây để không xin liên tục. */
const refreshDelay = (expiresAt) => Math.max(5000, new Date(expiresAt).getTime() - Date.now() - 60000);

/**
 * Ảnh CĐHA: xin URL ký tạm (quyền được kiểm một lần ở máy chủ, hạn 1 giờ) rồi để thẻ img tải thẳng. URL hết hạn thì tự xin lại (theo hẹn giờ,
 * hoặc khi ảnh báo lỗi); không lưu URL ở đâu cả. DICOM chỉ cho tải về.
 */
export function AttachmentView({ attachment }) {
  const [signed, setSigned] = useState(null);
  const [failed, setFailed] = useState(false);
  const previewable = /^image\/(jpeg|png)$/.test(attachment.contentType || '');
  useEffect(() => {
    let cancelled = false; let timer;
    const load = () => paraclinicApi.attachmentUrl(attachment.id).then((result) => {
      if (cancelled) return;
      setSigned(result); setFailed(false);
      timer = setTimeout(load, refreshDelay(result.expiresAt));
    }).catch(() => { if (!cancelled) setFailed(true); });
    load();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [attachment.id]);
  const reload = () => paraclinicApi.attachmentUrl(attachment.id).then(setSigned).catch(() => setFailed(true));
  if (failed) return <span className="ui-muted">Không tải được tệp {attachment.fileName}.</span>;
  if (!signed) return <span className="ui-muted">Đang tải {attachment.fileName}…</span>;
  const href = `${API_URL}${signed.url}`;
  return <figure style={{ margin: 0 }}>
    {previewable && <a href={href} target="_blank" rel="noreferrer"><img className="ui-image" src={href} alt={`Ảnh kết quả ${attachment.fileName}`} onError={reload} /></a>}
    <figcaption><a className="ui-link" href={href} download={attachment.fileName}>Tải {attachment.fileName}</a> <small className="ui-muted">({formatNumber((attachment.fileSize || 0) / 1024)} KB)</small></figcaption>
  </figure>;
}

export function LabResultsTable({ results }) {
  if (!results?.length) return null;
  return <Table caption="Kết quả xét nghiệm" rowKey="id" columns={[
    { title: 'Chỉ số', render: (row) => <><strong>{row.name}</strong><small>{row.code}</small></> },
    { title: 'Kết quả', render: (row) => <span className={row.evaluation === 'HIGH' ? 'ui-lab-high' : row.evaluation === 'LOW' ? 'ui-lab-low' : ''}>{formatNumber(row.value, 2)} {row.unit}</span> },
    { title: 'Khoảng tham chiếu', render: (row) => (row.minValue !== null && row.minValue !== undefined ? `${formatNumber(row.minValue, 2)} – ${formatNumber(row.maxValue, 2)} ${row.unit}` : '—') },
    { title: 'Đánh giá', render: (row) => <Badge map={LAB_EVALUATION} value={row.evaluation} /> },
  ]} rows={results} />;
}

export function ImagingResults({ results }) {
  if (!results?.length) return null;
  return <div className="ui-stack" style={{ gap: 14 }}>{results.map((result) => <div key={result.id}>
    <Facts items={[['Mô tả', result.description], ['Kết luận', result.conclusion], ['Thực hiện lúc', formatDateTime(result.performedAt)]]} />
    <div className="ui-actions" style={{ marginTop: 12, alignItems: 'flex-start' }}>{(result.attachments || []).map((file) => <AttachmentView key={file.id} attachment={file} />)}</div>
  </div>)}</div>;
}

export function PrescriptionView({ prescription }) {
  if (!prescription) return <p className="ui-muted">Không có đơn thuốc cho đợt khám này.</p>;
  return <div className="ui-stack" style={{ gap: 12 }}>
    {prescription.doctorNotes && <p style={{ margin: 0 }}><strong>Lời dặn của bác sĩ:</strong> {prescription.doctorNotes}</p>}
    <Table caption="Đơn thuốc" rowKey={(row) => row.medicineId} columns={[
      { title: 'Thuốc', render: (row) => <><strong>{row.medicineName}</strong></> },
      { title: 'Số lượng', render: (row) => `${row.quantity} ${row.unit}` },
      { title: 'Cách dùng', render: (row) => <>{row.dosage}, {row.frequency}, {row.durationDays} ngày{row.instructions && <small>{row.instructions}</small>}</> },
    ]} rows={prescription.items || []} />
  </div>;
}

/** Các lần đo sinh hiệu của một đợt khám (vital_signs), theo thứ tự thời gian. */
export function VitalSignsTable({ rows }) {
  const cell = (value, digits, unit) => (value === null || value === undefined ? '—' : `${formatNumber(value, digits)}${unit ? ` ${unit}` : ''}`);
  return <Table caption="Sinh hiệu" empty="Chưa đo lần nào." rows={rows} columns={[
    { title: 'Thời điểm', render: (row) => formatDateTime(row.recordedAt) },
    { title: 'Cân nặng', align: 'right', render: (row) => cell(row.weightKg, 2, 'kg') },
    { title: 'Thân nhiệt', align: 'right', render: (row) => cell(row.temperatureC, 1, '°C') },
    { title: 'Nhịp tim', align: 'right', render: (row) => cell(row.heartRateBpm, 0, 'lần/phút') },
    { title: 'Nhịp thở', align: 'right', render: (row) => cell(row.respiratoryRateBpm, 0, 'lần/phút') },
    { title: 'BCS', align: 'right', render: (row) => cell(row.bodyConditionScore, 0, '/9') },
    { title: 'Niêm mạc · CRT', render: (row) => [row.mucousMembraneColor, row.capillaryRefillSec != null ? `CRT ${formatNumber(row.capillaryRefillSec, 1)} s` : null].filter(Boolean).join(' · ') || '—' },
    { title: 'Ghi chú', render: (row) => row.notes || '—' }]} />;
}

/** Một đợt khám trong bệnh án (MedicalHistoryEntry của backend): chẩn đoán, dịch vụ, xét nghiệm, hình ảnh, đơn thuốc. */
export default function MedicalEntry({ entry }) {
  const { visit, doctorName, prescription } = entry;
  const services = (visit.services || []).filter((item) => item.status !== 'CANCELLED');
  const record = visit.medicalRecord;
  const vitals = visit.vitalSigns || [];
  return <article className="ui-panel">
    <header className="ui-panel-head"><div><h2>{visit.visitCode} · {formatDateTime(visit.checkedInAt)}</h2><p>Bác sĩ phụ trách: {doctorName || visit.doctorName || (visit.doctorId ? `#${visit.doctorId}` : 'Chưa ghi nhận')}{visit.doctorActive === false && <> <Badge tone="red">Đã khóa</Badge></>}{visit.emergency ? ' · Cấp cứu' : ''}</p></div><Badge map={VISIT_STATUS} value={visit.status} /></header>
    <div className="ui-panel-body ui-stack" style={{ gap: 20 }}>
      <Facts items={[['Lý do đến khám', visit.reasonForVisit], ['Bệnh sử', record?.anamnesis], ['Kết quả khám lâm sàng', record?.clinicalFindings], ['Chẩn đoán sơ bộ', record?.preliminaryDiagnosis],
        ['Chẩn đoán xác định', record?.finalDiagnosis], ['Phác đồ điều trị', record?.treatmentPlan], ['Lời dặn của bác sĩ', record?.doctorAdvice], ['Hẹn tái khám', record?.followUpDate ? formatDate(record.followUpDate) : null]]} />
      {vitals.length > 0 && <div><h3>Sinh hiệu</h3><VitalSignsTable rows={vitals} /></div>}
      {services.length > 0 && <div><h3>Dịch vụ đã thực hiện</h3><Table caption="Dịch vụ" columns={[
        { title: 'Dịch vụ', render: (row) => <><strong>{row.serviceName}</strong><small>{serviceCategoryLabel(row.category)}</small></> },
        { title: 'SL', key: 'quantity' }, { title: 'Thành tiền', align: 'right', render: (row) => formatMoney(row.subtotal) },
        { title: 'Trạng thái', render: (row) => <Badge map={SERVICE_DETAIL_STATUS} value={row.status} /> }]} rows={services} /></div>}
      {visit.labResults?.length > 0 && <div><h3>Kết quả xét nghiệm</h3><LabResultsTable results={visit.labResults} /></div>}
      {visit.imagingResults?.length > 0 && <div><h3>Chẩn đoán hình ảnh</h3><ImagingResults results={visit.imagingResults} /></div>}
      <div><h3>Đơn thuốc</h3><PrescriptionView prescription={prescription} /></div>
    </div>
  </article>;
}
