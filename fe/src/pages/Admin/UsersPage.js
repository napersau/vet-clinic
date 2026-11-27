import { useState } from 'react';
import StaffLayout from '../../layouts/StaffLayout';
import Modal from '../../components/common/Modal';
import { AsyncView, Badge, Button, Field, FormDialog, Input, Notice, PageHead, Pager, Panel, SearchBox, Select, Table } from '../../components/ui';
import { staffApi, usersApi } from '../../api/endpoints';
import { mapFieldErrors } from '../../api/client';
import { temporaryPassword } from '../../api/normalize';
import { ACCOUNT_STATUSES, ROLES, ROOM_ROLES, STAFF_ROLES } from '../../constants/accessPolicy';
import { useAuth } from '../../contexts/AuthContext';
import useAction from '../../hooks/useAction';
import useAsync from '../../hooks/useAsync';
import useDebounced from '../../hooks/useDebounced';
import { blankToNull, formatDate } from '../../lib/format';
import { sameId } from '../../lib/ids';
import { ACCOUNT_STATUS } from '../../lib/labels';

const PASSWORD_RULE = /^(?=.*[A-Za-z])(?=.*\d).{8,72}$/;
// Admin gán được cả vai trò Quản trị viên cho nhân sự (Bác sĩ kiêm Quản trị viên); chủ nuôi không nằm trong danh sách nhân sự
const ASSIGNABLE_ROLES = ['ADMIN', ...STAFF_ROLES];
const EMPTY_VET = { licenseNumber: '', licenseIssuedDate: '', licenseIssuedBy: '', licenseExpiryDate: '', academicDegree: '', specialty: '', graduatedFrom: '', careerStartYear: '', biography: '' };

// Lỗi của trường lồng có dạng `vetProfile.licenseNumber`: ô nhập chỉ dùng phần tên cuối
const flatErrors = (problem) => Object.fromEntries(Object.entries(mapFieldErrors(problem)).map(([key, message]) => [key.split('.').pop(), message]));

const roleLabels = (roles) => (roles || []).map((code) => ROLES[code]?.label || code).join(', ');

/** Ô chọn nhiều vai trò. */
function RolePicker({ value, onChange }) {
  const toggle = (code) => onChange(value.includes(code) ? value.filter((item) => item !== code) : [...value, code]);
  return <div className="ui-checks" role="group" aria-label="Vai trò">
    {ASSIGNABLE_ROLES.map((code) => <label key={code} className="ui-check"><input type="checkbox" checked={value.includes(code)} onChange={() => toggle(code)} /> {ROLES[code].label}</label>)}
  </div>;
}

/** Các trường hồ sơ chuyên môn thú y (bắt buộc với Bác sĩ). */
function VetFields({ form, errors, set }) {
  return <>
    <Field label="Số chứng chỉ hành nghề *" error={errors.licenseNumber}><Input value={form.licenseNumber} onChange={set('licenseNumber')} maxLength={50} /></Field>
    <Field label="Cơ quan cấp *" error={errors.licenseIssuedBy}><Input value={form.licenseIssuedBy} onChange={set('licenseIssuedBy')} maxLength={150} /></Field>
    <Field label="Ngày cấp *" error={errors.licenseIssuedDate}><Input type="date" value={form.licenseIssuedDate} onChange={set('licenseIssuedDate')} /></Field>
    <Field label="Ngày hết hạn" error={errors.licenseExpiryDate}><Input type="date" value={form.licenseExpiryDate} onChange={set('licenseExpiryDate')} /></Field>
    <Field label="Học vị *" error={errors.academicDegree}><Input value={form.academicDegree} onChange={set('academicDegree')} maxLength={50} placeholder="Bác sĩ thú y, Thạc sĩ..." /></Field>
    <Field label="Chuyên khoa" error={errors.specialty}><Input value={form.specialty} onChange={set('specialty')} maxLength={150} /></Field>
    <Field label="Nơi tốt nghiệp" error={errors.graduatedFrom}><Input value={form.graduatedFrom} onChange={set('graduatedFrom')} maxLength={150} /></Field>
    <Field label="Năm bắt đầu hành nghề" error={errors.careerStartYear}><Input type="number" min="1950" max="2100" value={form.careerStartYear} onChange={set('careerStartYear')} /></Field>
  </>;
}

const vetFormOf = (profile) => (profile ? { ...EMPTY_VET, ...Object.fromEntries(Object.entries(profile).map(([key, value]) => [key, value ?? ''])) } : { ...EMPTY_VET });
const vetBodyOf = (form) => ({
  licenseNumber: form.licenseNumber.trim(), licenseIssuedDate: form.licenseIssuedDate || null, licenseIssuedBy: form.licenseIssuedBy.trim(),
  licenseExpiryDate: blankToNull(form.licenseExpiryDate), academicDegree: form.academicDegree.trim(), specialty: blankToNull(form.specialty),
  graduatedFrom: blankToNull(form.graduatedFrom), careerStartYear: form.careerStartYear === '' ? null : Number(form.careerStartYear), biography: blankToNull(form.biography),
});

function validateVet(form) {
  const found = {};
  if (!form.licenseNumber.trim()) found.licenseNumber = 'Nhập số chứng chỉ hành nghề.';
  if (!form.licenseIssuedBy.trim()) found.licenseIssuedBy = 'Nhập cơ quan cấp.';
  if (!form.licenseIssuedDate) found.licenseIssuedDate = 'Chọn ngày cấp.';
  if (!form.academicDegree.trim()) found.academicDegree = 'Nhập học vị.';
  if (form.licenseExpiryDate && form.licenseIssuedDate && form.licenseExpiryDate <= form.licenseIssuedDate) found.licenseExpiryDate = 'Ngày hết hạn phải sau ngày cấp.';
  return found;
}

function CreateDialog({ onClose, onSaved }) {
  const [form, setForm] = useState({ username: '', password: '', fullName: '', phoneNumber: '', email: '', address: '', roomNumber: '', positionTitle: '' });
  const [roles, setRoles] = useState(['VETERINARIAN']);
  const [vet, setVet] = useState({ ...EMPTY_VET });
  const [errors, setErrors] = useState({});
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const setVetField = (key) => (event) => setVet({ ...vet, [key]: event.target.value });
  const isVet = roles.includes('VETERINARIAN');
  const needsRoom = roles.some((code) => ROOM_ROLES.includes(code));

  const submit = async () => {
    const found = {};
    if (roles.length === 0) found.roleCodes = 'Chọn ít nhất một vai trò.';
    if (!form.fullName.trim()) found.fullName = 'Nhập họ tên.';
    if (!/^0\d{9}$/.test(form.phoneNumber.trim())) found.phoneNumber = 'Số điện thoại gồm đúng 10 số, bắt đầu bằng 0.';
    if (!PASSWORD_RULE.test(form.password)) found.password = 'Mật khẩu 8–72 ký tự, có cả chữ và số.';
    if (needsRoom && !form.roomNumber.trim()) found.roomNumber = 'Nhập số phòng làm việc.';
    if (isVet) Object.assign(found, validateVet(vet));
    setErrors(found);
    if (Object.keys(found).length) throw new Error('Vui lòng kiểm tra lại các trường được đánh dấu.');
    const body = {
      username: blankToNull(form.username), password: form.password, fullName: form.fullName.trim(), phoneNumber: form.phoneNumber.trim(),
      email: blankToNull(form.email), address: blankToNull(form.address), roleCodes: roles, roomNumber: needsRoom ? form.roomNumber.trim() : null,
      positionTitle: blankToNull(form.positionTitle), vetProfile: isVet ? vetBodyOf(vet) : null,
    };
    try { onSaved(await staffApi.create(body)); } catch (problem) { setErrors(flatErrors(problem)); throw problem; }
  };

  return <FormDialog title="Thêm nhân sự" id="user-form-title" onClose={onClose} onSubmit={submit} submitLabel="Tạo nhân sự" wide>
    <div className="ui-grid">
      <Field label="Vai trò *" error={errors.roleCodes}><RolePicker value={roles} onChange={setRoles} /></Field>
      <Field label="Tên đăng nhập" error={errors.username} hint="Để trống sẽ dùng số điện thoại."><Input value={form.username} onChange={set('username')} maxLength={100} autoComplete="off" /></Field>
      <Field label="Họ và tên *" error={errors.fullName}><Input value={form.fullName} onChange={set('fullName')} maxLength={150} /></Field>
      <Field label="Số điện thoại *" error={errors.phoneNumber}><Input inputMode="tel" value={form.phoneNumber} onChange={set('phoneNumber')} /></Field>
      <Field label="Email" error={errors.email}><Input type="email" value={form.email} onChange={set('email')} /></Field>
      <Field label="Địa chỉ" error={errors.address}><Input value={form.address} onChange={set('address')} maxLength={255} /></Field>
      <Field label="Chức danh" error={errors.positionTitle} hint="Để trống sẽ lấy theo vai trò chính."><Input value={form.positionTitle} onChange={set('positionTitle')} maxLength={100} /></Field>
      {needsRoom && <Field label="Số phòng làm việc *" error={errors.roomNumber} hint="Mỗi phòng chỉ có một người phụ trách."><Input value={form.roomNumber} onChange={set('roomNumber')} /></Field>}
      <Field label="Mật khẩu ban đầu *" error={errors.password} hint="Gợi ý: bấm “Tạo ngẫu nhiên” rồi chuyển cho nhân viên."><div style={{ display: 'flex', gap: 8 }}><Input value={form.password} onChange={set('password')} autoComplete="new-password" /><Button onClick={() => setForm({ ...form, password: temporaryPassword() })}>Tạo ngẫu nhiên</Button></div></Field>
      {isVet && <><h3 className="ui-subhead">Hồ sơ chuyên môn thú y</h3><VetFields form={vet} errors={errors} set={setVetField} /></>}
    </div>
  </FormDialog>;
}

function EditDialog({ user, onClose, onSaved }) {
  const [form, setForm] = useState({ fullName: user.fullName || '', phoneNumber: user.phoneNumber || '', email: user.email || '', address: user.address || '', roomNumber: user.roomNumber || '', positionTitle: user.positionTitle || '', department: user.department || '' });
  const [errors, setErrors] = useState({});
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const needsRoom = (user.roles || []).some((code) => ROOM_ROLES.includes(code));

  const submit = async () => {
    const found = {};
    if (!form.fullName.trim()) found.fullName = 'Nhập họ tên.';
    if (!/^0\d{9}$/.test(form.phoneNumber.trim())) found.phoneNumber = 'Số điện thoại gồm đúng 10 số, bắt đầu bằng 0.';
    if (needsRoom && !form.roomNumber.trim()) found.roomNumber = 'Nhập số phòng làm việc.';
    if (!form.positionTitle.trim()) found.positionTitle = 'Nhập chức danh.';
    setErrors(found);
    if (Object.keys(found).length) throw new Error('Vui lòng kiểm tra lại các trường được đánh dấu.');
    try {
      // Hồ sơ cá nhân và hồ sơ nhân sự là hai tài nguyên riêng (docs v4.0 Task 2.4)
      await usersApi.updateProfile(user.id, { fullName: form.fullName.trim(), phoneNumber: form.phoneNumber.trim(), email: blankToNull(form.email), address: blankToNull(form.address) });
      const saved = await staffApi.update(user.id, { roomNumber: needsRoom ? form.roomNumber.trim() : '', positionTitle: form.positionTitle.trim(), department: blankToNull(form.department) });
      onSaved(saved);
    } catch (problem) { setErrors(flatErrors(problem)); throw problem; }
  };

  return <FormDialog title={`Sửa nhân sự ${user.fullName}`} id="user-form-title" onClose={onClose} onSubmit={submit} submitLabel="Lưu thay đổi" wide>
    <div className="ui-grid">
      <Field label="Họ và tên *" error={errors.fullName}><Input value={form.fullName} onChange={set('fullName')} maxLength={150} /></Field>
      <Field label="Số điện thoại *" error={errors.phoneNumber}><Input inputMode="tel" value={form.phoneNumber} onChange={set('phoneNumber')} /></Field>
      <Field label="Email" error={errors.email}><Input type="email" value={form.email} onChange={set('email')} /></Field>
      <Field label="Địa chỉ" error={errors.address}><Input value={form.address} onChange={set('address')} maxLength={255} /></Field>
      <Field label="Chức danh *" error={errors.positionTitle}><Input value={form.positionTitle} onChange={set('positionTitle')} maxLength={100} /></Field>
      <Field label="Bộ phận" error={errors.department}><Input value={form.department} onChange={set('department')} maxLength={100} /></Field>
      {needsRoom && <Field label="Số phòng làm việc *" error={errors.roomNumber} hint="Mỗi phòng chỉ có một người phụ trách."><Input value={form.roomNumber} onChange={set('roomNumber')} /></Field>}
    </div>
  </FormDialog>;
}

function VetProfileDialog({ user, onClose, onSaved }) {
  const [form, setForm] = useState(vetFormOf(user.vetProfile));
  const [errors, setErrors] = useState({});
  const set = (key) => (event) => setForm({ ...form, [key]: event.target.value });
  const submit = async () => {
    const found = validateVet(form);
    setErrors(found);
    if (Object.keys(found).length) throw new Error('Vui lòng kiểm tra lại các trường được đánh dấu.');
    try { await staffApi.saveVetProfile(user.id, vetBodyOf(form)); onSaved(`Đã lưu hồ sơ chuyên môn của ${user.fullName}.`); } catch (problem) { setErrors(flatErrors(problem)); throw problem; }
  };
  return <FormDialog title={`Hồ sơ chuyên môn — ${user.fullName}`} onClose={onClose} onSubmit={submit} submitLabel="Lưu hồ sơ" wide>
    <div className="ui-grid"><VetFields form={form} errors={errors} set={set} /></div>
  </FormDialog>;
}

function RoleDialog({ user, onClose, onSaved }) {
  const [roles, setRoles] = useState(user.roles || []);
  const same = roles.length === (user.roles || []).length && roles.every((code) => (user.roles || []).includes(code));
  return <FormDialog title={`Vai trò — ${user.fullName}`} onClose={onClose} submitLabel="Lưu vai trò"
    onSubmit={async () => {
      if (roles.length === 0) throw new Error('Chọn ít nhất một vai trò.');
      if (same) throw new Error('Danh sách vai trò chưa thay đổi.');
      await usersApi.setRoles(user.id, roles);
      onSaved(`Đã cập nhật vai trò của ${user.fullName}: ${roleLabels(roles)}.`);
    }}>
    <p className="ui-confirm-text">Một tài khoản có thể có nhiều vai trò (ví dụ Bác sĩ kiêm Quản trị viên). Mọi phiên đăng nhập của tài khoản này bị đăng xuất để áp dụng vai trò mới; số phòng bị xóa nếu không còn vai trò nào có phòng cố định. Gán vai trò Bác sĩ cần hồ sơ chuyên môn.</p>
    <Field label="Vai trò"><RolePicker value={roles} onChange={setRoles} /></Field>
  </FormDialog>;
}

const STATUS_ACTIONS = { LOCKED: usersApi.lock, ACTIVE: usersApi.unlock, INACTIVE: usersApi.deactivate };

function StatusDialog({ user, status, onClose, onSaved }) {
  const label = ACCOUNT_STATUSES[status].label;
  const text = {
    ACTIVE: `Tài khoản ${user.fullName} sẽ đăng nhập lại được.`,
    LOCKED: `Tài khoản ${user.fullName} sẽ không đăng nhập được và mọi phiên hiện tại bị thu hồi. Bác sĩ bị khóa làm giảm sức chứa đặt lịch ngay lập tức.`,
    INACTIVE: `Ngừng hoạt động là xóa mềm: tài khoản ${user.fullName} không đăng nhập được nhưng dữ liệu và lịch sử khám vẫn được giữ; có thể kích hoạt lại sau.`,
  }[status];
  return <FormDialog title={`Chuyển sang “${label}”`} onClose={onClose} danger={status !== 'ACTIVE'} submitLabel="Xác nhận"
    onSubmit={async () => { await STATUS_ACTIONS[status](user.id); onSaved(`Đã chuyển tài khoản ${user.fullName} sang “${label}”.`); }}>
    <p className="ui-confirm-text">{text}</p>
  </FormDialog>;
}

function ResetDialog({ user, onClose }) {
  const [password] = useState(temporaryPassword);
  const [done, setDone] = useState(false);
  const action = useAction();
  return <Modal title={`Đặt lại mật khẩu — ${user.fullName}`} id="admin-reset-title" onClose={onClose} busy={action.busy}>
    <div className="ui-form-body">
      {done ? <div className="ui-alert success"><span>Đã đặt mật khẩu tạm. Mọi phiên đăng nhập cũ đã bị đăng xuất.</span></div> : <p className="ui-confirm-text">Đặt mật khẩu tạm cho tài khoản này và chuyển cho người dùng; họ nên đổi lại ngay sau khi đăng nhập.</p>}
      <Notice notice={action.notice} />
      <Field label="Mật khẩu tạm"><Input readOnly value={password} className="ui-mono" data-testid="temporary-password" onFocus={(event) => event.target.select()} /></Field>
    </div>
    <div className="ui-form-actions"><Button onClick={onClose}>{done ? 'Đóng' : 'Hủy'}</Button>{!done && <Button variant="primary" busy={action.busy} onClick={async () => { if (await action.run(() => usersApi.resetPassword(user.id, password))) setDone(true); }}>Đặt mật khẩu tạm</Button>}</div>
  </Modal>;
}

export default function UsersPage() {
  const { user: me } = useAuth();
  const [role, setRole] = useState('');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [dialog, setDialog] = useState(null);
  const [notice, setNotice] = useState(null);
  const search = useDebounced(keyword);
  const list = useAsync(() => staffApi.list({ role, status, keyword: search.trim() || undefined, page, size: 15 }), [role, status, search, page]);
  const done = (text) => { setDialog(null); setNotice({ type: 'success', text }); list.refresh(); };
  const reset = (setter) => (event) => { setter(event.target.value); setPage(0); };

  return <StaffLayout area="admin" title="Tài khoản & nhân sự">
    <PageHead eyebrow="QUẢN TRỊ" title="Tài khoản & nhân sự" actions={<Button variant="primary" onClick={() => setDialog({ type: 'create' })}>+ Thêm nhân sự</Button>}>
      Tạo nhân sự (hồ sơ nhân sự và hồ sơ chuyên môn của bác sĩ), gán số phòng và vai trò, khóa/mở khóa/ngừng hoạt động và đặt lại mật khẩu. Chủ nuôi tự đăng ký hoặc do lễ tân tạo.
    </PageHead>
    <Notice notice={notice} onClose={() => setNotice(null)} />
    <div className="ui-toolbar">
      <SearchBox value={keyword} onChange={(value) => { setKeyword(value); setPage(0); }} placeholder="Tên, SĐT, tên đăng nhập, mã NV hoặc phòng" label="Tìm nhân sự" />
      <Select aria-label="Vai trò" value={role} onChange={reset(setRole)}><option value="">Mọi vai trò</option>{ASSIGNABLE_ROLES.map((key) => <option key={key} value={key}>{ROLES[key].label}</option>)}</Select>
      <Select aria-label="Trạng thái" value={status} onChange={reset(setStatus)}><option value="">Mọi trạng thái</option>{Object.entries(ACCOUNT_STATUSES).map(([key, value]) => <option key={key} value={key}>{value.label}</option>)}</Select>
    </div>
    <Panel flush>
      <AsyncView state={list} isEmpty={(data) => data.items.length === 0} empty="Không có nhân sự phù hợp.">
        {(data) => <>
          <Table caption="Nhân sự" rows={data.items} columns={[
            { title: 'Nhân sự', render: (row) => <><strong>{row.fullName}</strong><small>{row.employeeCode} · {row.username}</small></> },
            { title: 'Liên hệ', render: (row) => <>{row.phoneNumber}<small>{row.email || '—'}</small></> },
            { title: 'Vai trò', render: (row) => <>{roleLabels(row.roles)}{row.roomNumber && <small>Phòng {row.roomNumber}</small>}</> },
            { title: 'Chức danh', render: (row) => <>{row.positionTitle}<small>{row.vetProfile ? `CCHN ${row.vetProfile.licenseNumber}` : (row.department || '—')}</small></> },
            { title: 'Vào làm', render: (row) => formatDate(row.hireDate) },
            { title: 'Trạng thái', render: (row) => <Badge map={ACCOUNT_STATUS} value={row.status} /> },
            { title: '', align: 'right', render: (row) => {
              const self = sameId(row.id, me.id);
              return <div className="ui-actions" style={{ justifyContent: 'flex-end' }}>
                <Button className="small" onClick={() => setDialog({ type: 'edit', user: row })}>Sửa</Button>
                {(row.roles || []).includes('VETERINARIAN') && <Button className="small" onClick={() => setDialog({ type: 'vet', user: row })}>Chuyên môn</Button>}
                {!self && <Button className="small" onClick={() => setDialog({ type: 'role', user: row })}>Vai trò</Button>}
                {!self && row.status === 'ACTIVE' && <Button className="small danger" onClick={() => setDialog({ type: 'status', user: row, status: 'LOCKED' })}>Khóa</Button>}
                {!self && row.status !== 'ACTIVE' && <Button className="small" onClick={() => setDialog({ type: 'status', user: row, status: 'ACTIVE' })}>Kích hoạt</Button>}
                {!self && row.status !== 'INACTIVE' && <Button className="small danger" onClick={() => setDialog({ type: 'status', user: row, status: 'INACTIVE' })}>Ngừng</Button>}
                {!self && <Button className="small" onClick={() => setDialog({ type: 'reset', user: row })}>Đặt lại MK</Button>}
              </div>; } }]} />
          <Pager page={data} onChange={setPage} />
        </>}
      </AsyncView>
    </Panel>
    {dialog?.type === 'create' && <CreateDialog onClose={() => setDialog(null)} onSaved={(saved) => done(`Đã tạo nhân sự ${saved.fullName} (${saved.employeeCode}).`)} />}
    {dialog?.type === 'edit' && <EditDialog user={dialog.user} onClose={() => setDialog(null)} onSaved={(saved) => done(`Đã cập nhật ${saved.fullName}.`)} />}
    {dialog?.type === 'vet' && <VetProfileDialog user={dialog.user} onClose={() => setDialog(null)} onSaved={done} />}
    {dialog?.type === 'role' && <RoleDialog user={dialog.user} onClose={() => setDialog(null)} onSaved={done} />}
    {dialog?.type === 'status' && <StatusDialog user={dialog.user} status={dialog.status} onClose={() => setDialog(null)} onSaved={done} />}
    {dialog?.type === 'reset' && <ResetDialog user={dialog.user} onClose={() => setDialog(null)} />}
  </StaffLayout>;
}
