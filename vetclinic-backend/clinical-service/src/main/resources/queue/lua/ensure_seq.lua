-- Khởi tạo / nâng bộ đếm số phiếu (queue:seq:{yyyyMMdd}:{queueType}) lên ÍT NHẤT bằng số lớn nhất đã có trong queue_ticket_histories của ngày đó.
-- Redis mất bộ đếm (flush, mất dữ liệu) rồi đếm lại từ 1 sẽ trùng UNIQUE (queue_type, service_date, ticket_no) của lịch sử; script này chỉ TĂNG, không bao giờ giảm.
-- KEYS[1] = queue:seq:{yyyyMMdd}:{queueType}   ARGV[1] = số nhỏ nhất cần có   ARGV[2] = TTL (giây)
-- Trả về giá trị bộ đếm sau khi chạy.
local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
local min = tonumber(ARGV[1])
if cur < min then
  redis.call('SET', KEYS[1], min, 'EX', tonumber(ARGV[2]))
  return min
end
return cur
