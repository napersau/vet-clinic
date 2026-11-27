-- Chuyển phiếu DOCTOR đang chờ sang hàng đợi Bác sĩ khác / về danh sách chờ phân công, GIỮ NGUYÊN score
-- (số phiếu, giờ phát, mức ưu tiên không đổi: BR-39, EX-17). Hàng đợi nguồn tính từ chính Hash phiếu để không lệch khi phiếu vừa được chuyển.
-- KEYS[1] = ticket:{ticketId}
-- ARGV[1] = ticketId  ARGV[2] = doctorId mới ('' = UNASSIGNED)
-- Trả về: OK | NOT_FOUND | NOT_DOCTOR_QUEUE | NOT_WAITING
local f = redis.call('HMGET', KEYS[1], 'status', 'queueType', 'serviceDate', 'doctorId')
if not f[1] then return 'NOT_FOUND' end
if f[2] ~= 'DOCTOR' then return 'NOT_DOCTOR_QUEUE' end
if f[1] ~= 'WAITING' then return 'NOT_WAITING' end
local current = f[4]
if not current or current == '' then current = 'UNASSIGNED' end
local target = ARGV[2]
if target == '' then target = 'UNASSIGNED' end
local from = 'queue:' .. f[3] .. ':DOCTOR:' .. current
local to = 'queue:' .. f[3] .. ':DOCTOR:' .. target
local score = redis.call('ZSCORE', from, ARGV[1])
if not score then return 'NOT_FOUND' end
redis.call('ZREM', from, ARGV[1])
redis.call('ZADD', to, score, ARGV[1])
redis.call('HSET', KEYS[1], 'doctorId', ARGV[2])
return 'OK'
