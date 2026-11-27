-- Phát phiếu nguyên tử (Quy chuẩn v4.0 mục 9.3, mục 1).
-- KEYS[1] = queue:{yyyyMMdd}:{queueType}:{doctorId|UNASSIGNED|ALL}   (Sorted Set hàng đợi)
-- KEYS[2] = queue:seq:{yyyyMMdd}:{queueType}                         (INCR -> số thứ tự trên phiếu)
-- KEYS[3] = queue:dedupe:{khóa chống trùng}                          (nhận lại sự kiện không phát phiếu thứ hai)
-- KEYS[4] = queue:visit:{visitId}                                    (Set các phiếu đang mở của đợt khám)
-- ARGV[1] = ticketId  ARGV[2] = score  ARGV[3] = TTL của phiếu (giây)  ARGV[4] = TTL của seq / dedupe (giây)
-- ARGV[5..] = cặp field / value của Hash phiếu
-- Trả về { ticketId, ticketNo, daPhatTruocDo (0 | 1) } (mọi phần tử là chuỗi)
local existed = redis.call('GET', KEYS[3])
if existed then
  local no = redis.call('HGET', 'ticket:' .. existed, 'ticketNo')
  if not no then no = '' end
  return { existed, no, '1' }
end
local no = redis.call('INCR', KEYS[2])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4]))
local tkey = 'ticket:' .. ARGV[1]
redis.call('HSET', tkey, 'ticketNo', no, 'status', 'WAITING', 'calledCount', 0, unpack(ARGV, 5))
redis.call('EXPIRE', tkey, tonumber(ARGV[3]))
redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
redis.call('SADD', KEYS[4], ARGV[1])
redis.call('EXPIRE', KEYS[4], tonumber(ARGV[3]))
redis.call('SET', KEYS[3], ARGV[1], 'EX', tonumber(ARGV[4]))
return { ARGV[1], tostring(no), '0' }
