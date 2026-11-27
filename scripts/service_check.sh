#!/usr/bin/env bash
# Kiểm tra NHANH một service với MySQL thật, KHÔNG cần dựng cả hệ thống Docker (quy trình v4: test ở cấp service sau mỗi bước).
#   bash scripts/service_check.sh auth-service          # hoặc appointment-service | clinical-service | billing-pharmacy-service
#   bash scripts/service_check.sh auth-service --reset  # nạp lại schema + seed vào MySQL tạm
# Việc làm: (1) bật MySQL tạm 'vetclinic-check-mysql' (cổng 3399) nạp docs/schema_init.sql + docs/seed_demo_data.sql nếu chưa có;
# (2) chạy jar của service với Eureka tắt; Hibernate `ddl-auto: validate` kiểm tra mọi entity khớp lược đồ (việc mà unit test dùng mock
# KHÔNG kiểm tra được: kiểu cột BINARY(16), tên cột, quan hệ, câu JPQL); (3) chờ "Started", in lỗi nếu có; với auth-service còn đăng nhập
# thật bằng tài khoản seed và đọc claim JWT.
# Cần: Docker, JDK 21 (JAVA_HOME), jar trong <module>/target (cd vetclinic-backend && mvn -o -pl <module> install -DskipTests).
set -u
cd "$(dirname "$0")/.." || exit 1
MODULE=${1:?cần tên module, ví dụ auth-service}
RESET=${2:-}
CONTAINER=vetclinic-check-mysql
MYSQL_PORT=3399
case "$MODULE" in
  auth-service) PORT=18081;; appointment-service) PORT=18082;; clinical-service) PORT=18083;; billing-pharmacy-service) PORT=18084;; reporting-service) PORT=18086;; *) PORT=18090;;
esac
JAVA="${JAVA_HOME:-C:/Program Files/Java/jdk-21.0.6}/bin/java"
LOG="${TMPDIR:-/tmp}/service-check-$MODULE.log"

if [ "$RESET" = "--reset" ]; then docker rm -f "$CONTAINER" >/dev/null 2>&1; fi
if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  docker rm -f "$CONTAINER" >/dev/null 2>&1
  # docker create + cp + start (không bind-mount: tránh lỗi mount thư mục máy chủ của Docker Desktop trên Windows)
  docker create --name "$CONTAINER" -p "$MYSQL_PORT:3306" -e MYSQL_ROOT_PASSWORD=check -e TZ=Asia/Ho_Chi_Minh mysql:8.0 >/dev/null || exit 1
  docker cp docs/schema_init.sql "$CONTAINER:/docker-entrypoint-initdb.d/01-schema_init.sql" || exit 1
  docker cp docs/seed_demo_data.sql "$CONTAINER:/docker-entrypoint-initdb.d/02-seed_demo_data.sql" || exit 1
  docker start "$CONTAINER" >/dev/null || exit 1
  echo "đã tạo MySQL tạm, chờ nạp lược đồ + seed..."
  for _ in $(seq 1 60); do
    sleep 3
    docker exec "$CONTAINER" mysql -uroot -pcheck -N -e "SELECT COUNT(*) FROM pet_clinic_clinical_db.pets" 2>/dev/null | grep -q '^14$' && break
  done
fi

JAR="vetclinic-backend/$MODULE/target/$MODULE.jar"
[ -f "$JAR" ] || { echo "KHÔNG THẤY $JAR: chạy 'cd vetclinic-backend && mvn -o -pl $MODULE install -DskipTests' trước"; exit 2; }

export DB_HOST=localhost DB_PORT=$MYSQL_PORT DB_USERNAME=root DB_PASSWORD=check TZ=UTC
export JWT_SECRET=check-secret-check-secret-check-secret-0123456789
export RABBITMQ_HOST=localhost
"$JAVA" -Duser.timezone=UTC -jar "$JAR" --server.port=$PORT --eureka.client.enabled=false \
  --spring.cloud.discovery.enabled=false > "$LOG" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null' EXIT

STARTED=0
for _ in $(seq 1 60); do
  sleep 2
  if grep -q "Started .* in " "$LOG"; then STARTED=1; break; fi
  kill -0 $PID 2>/dev/null || break
done

if [ $STARTED -ne 1 ]; then
  echo "KHÔNG KHỞI ĐỘNG ĐƯỢC $MODULE. Lỗi chính:"
  grep -E "Caused by|APPLICATION FAILED|Schema-validation|Description:|Action:" "$LOG" | head -12 | cut -c1-300
  echo "(log đầy đủ: $LOG)"
  exit 1
fi
echo "OK: $MODULE khởi động với MySQL thật, Hibernate validate khớp lược đồ."

if [ "$MODULE" = "auth-service" ]; then
  RES=$(curl -s -X POST "http://localhost:$PORT/api/v1/auth/login" -H 'Content-Type: application/json' -d '{"username":"bs_tien","password":"Admin@123"}')
  TOKEN=$(echo "$RES" | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null)
  if [ -z "$TOKEN" ]; then echo "ĐĂNG NHẬP THẤT BẠI: $RES" | cut -c1-300; exit 1; fi
  echo "$TOKEN" | PYTHONIOENCODING=utf-8 python -c "
import sys,base64,json
t=sys.stdin.read().strip().split('.')[1]; t+='='*(-len(t)%4)
c=json.loads(base64.urlsafe_b64decode(t))
print('JWT claims:', {k:c[k] for k in ('userId','roles','fullName','roomNumber','username') if k in c})
assert c['roles']==['VETERINARIAN'], 'roles phải là danh sách'
assert len(c['userId'])==36, 'userId phải là UUID'
print('OK: đăng nhập seed thật, JWT mang userId UUID + roles danh sách')" || exit 1
  # Bước 2.2: nhân sự, hồ sơ, vai trò, khóa/mở khóa, outbox (chạy thật trên MySQL tạm)
  PYTHONIOENCODING=utf-8 python scripts/auth_staff_check.py "$PORT" "$CONTAINER" || exit 1
fi
if [ "$MODULE" = "clinical-service" ]; then
  # Bước 3.1: nhóm dịch vụ, dịch vụ UUID, bảng giá có lịch sử, xét nghiệm (chạy thật trên MySQL tạm)
  PYTHONIOENCODING=utf-8 python scripts/catalog_check.py "$PORT" "$CONTAINER" || exit 1
fi
if [ "$MODULE" = "billing-pharmacy-service" ]; then
  # Bước 5.1: danh mục thuốc v4.0, bảng giá có lịch sử, tồn kho tách bảng (chạy thật trên MySQL tạm)
  PYTHONIOENCODING=utf-8 python scripts/medicine_check.py "$PORT" "$CONTAINER" || exit 1
fi
exit 0
