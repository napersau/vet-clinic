import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import CustomerLayout from '../../layouts/CustomerLayout';
import PetAvatar from '../../components/customer/PetAvatar';
import PetFormDialog from '../../components/shared/PetFormDialog';
import { AsyncView, Badge, Button, ConfirmDialog, Facts, PageHead, Panel, Table } from '../../components/ui';
import { petsApi } from '../../api/endpoints';
import useAsync from '../../hooks/useAsync';
import { formatAge, formatDate, formatDateTime, formatNumber } from '../../lib/format';
import { PET_GENDER, VISIT_STATUS } from '../../lib/labels';

export default function PetDetailPage() {
  const { petId } = useParams();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const navigate = useNavigate();
  const pet = useAsync(() => petsApi.get(petId), [petId]);
  const visits = useAsync(() => petsApi.visits(petId), [petId]);
  return <CustomerLayout>
    <AsyncView state={pet}>
      {(data) => <>
        <PageHead eyebrow={`MÃ ${data.petCode}`} title={data.name} actions={<><Button onClick={() => setEditing(true)}>Sửa hồ sơ</Button><Button variant="danger" onClick={() => setDeleting(true)}>Xóa hồ sơ</Button><Link className="ui-button primary" to={`/portal/booking?petId=${data.id}`}>Đặt lịch khám</Link></>}>
          {data.speciesName}{data.breed ? ` · ${data.breed}` : ''} · {formatAge(data.dateOfBirth)}
        </PageHead>
        <Panel title="Thông tin chung">
          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
            <PetAvatar pet={data} />
            <div style={{ flex: 1, minWidth: 260 }}>
              <Facts items={[['Giới tính', PET_GENDER[data.gender]], ['Ngày sinh', formatDate(data.dateOfBirth)], ['Cân nặng', data.weight ? `${formatNumber(data.weight, 2)} kg` : null], ['Màu lông', data.color],
                ['Triệt sản', data.neutered ? 'Đã triệt sản' : 'Chưa'], ['Số vi mạch', data.microchipNumber], ['Đặc điểm nhận dạng', data.identifyingCharacteristics], ['Tiền sử dị ứng', data.allergies], ['Ghi chú', data.notes]]} />
            </div>
          </div>
        </Panel>
        <Panel title="Các đợt khám" subtitle="Chi tiết kết quả và đơn thuốc nằm trong Lịch sử khám bệnh." actions={<Link className="ui-button" to={`/portal/medical-history?petId=${data.id}`}>Xem lịch sử khám</Link>} flush>
          <AsyncView state={visits} empty="Bé chưa có đợt khám nào.">
            {(items) => <Table caption="Các đợt khám" columns={[
              { title: 'Mã đợt khám', render: (row) => <strong>{row.visitCode}</strong> },
              { title: 'Tiếp nhận', render: (row) => formatDateTime(row.checkedInAt) },
              { title: 'Lý do đến khám', render: (row) => row.reasonForVisit || '—' },
              { title: 'Trạng thái', render: (row) => <Badge map={VISIT_STATUS} value={row.status} /> }]} rows={items} />}
          </AsyncView>
        </Panel>
        {editing && <PetFormDialog pet={data} onClose={() => setEditing(false)} onSaved={() => pet.refresh()} />}
        {deleting && <ConfirmDialog title={`Xóa hồ sơ ${data.name}?`} danger confirmLabel="Xóa hồ sơ" onClose={() => setDeleting(false)} onConfirm={async () => { await petsApi.remove(data.id); navigate('/portal/pets'); }}>Hồ sơ sẽ bị ẩn khỏi danh sách và không đặt lịch được nữa; bệnh án cũ vẫn được lưu. Không xóa được khi bé đang có đợt khám chưa kết thúc.</ConfirmDialog>}
      </>}
    </AsyncView>
  </CustomerLayout>;
}
