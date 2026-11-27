"""Kiểm tra THẬT bước 4.3 (MySQL thật + RabbitMQ thật): ảnh CĐHA lưu file_key, URL ký tạm (Quy chuẩn 7.5).
  python scripts/imaging_check.py
Cần: container MySQL 'vetclinic-check-mysql' (bash scripts/service_check.sh clinical-service --reset), Docker, JDK 21 và jar của
auth-service / clinical-service trong <module>/target (mvn -o -pl ... install -DskipTests). Tệp ảnh ghi vào thư mục tạm (STORAGE_ROOT)."""
import base64
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
JAVA = os.path.join(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6"), "bin", "java")
MYSQL, RABBIT, RABBIT_PORT = "vetclinic-check-mysql", "vetclinic-check-rabbit", "5673"
REDIS, REDIS_PORT = "vetclinic-check-redis", "6391"   # hàng đợi trên Redis (bước 6.2): phiếu phát sau khi thu phí khám
PORTS = {"auth-service": 18081, "clinical-service": 18083}
SUFFIX = str(int(time.time()))[-6:]
STORE = tempfile.mkdtemp(prefix="vetclinic-storage-")
FAILS, PROCS = [], []
TIEN = "019a9442-d810-7000-8000-000000000002"   # bs_tien
BASE = "/api/v1/clinical/pet-visits"
PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")


def wait_for(fn, seconds=25):
    end = time.time() + seconds
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(0.4)
    return None


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sh(*args):
    return (subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout or "").strip()


def sql(query):
    return sh("docker", "exec", MYSQL, "mysql", "-uroot", "-pcheck", "--default-character-set=utf8mb4", "-N", "-B", "-e", query)


def b(uid):
    return f"UNHEX(REPLACE('{uid}','-',''))"


def to_uuid(hexstr):
    h = hexstr.lower()
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def claims(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def raw(method, path, token=None, body=None, content_type="application/json", service="clinical-service"):
    """Trả (status, headers, bytes)."""
    req = urllib.request.Request(f"http://localhost:{PORTS[service]}{path}", data=body, method=method)
    if body is not None:
        req.add_header("Content-Type", content_type)
    if token:  # tự đóng vai Gateway: gắn header danh tính từ claim của JWT
        c = claims(token)
        req.add_header("X-User-Id", c["userId"])
        req.add_header("X-User-Roles", ",".join(c["roles"]))
    try:
        with urllib.request.urlopen(req, timeout=30) as res:
            return res.status, res.headers, res.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def call(method, path, token=None, body=None, service="clinical-service"):
    st, _, data = raw(method, path, token, json.dumps(body).encode("utf-8") if body is not None else None, service=service)
    try:
        return st, json.loads(data or b"{}")
    except ValueError:
        return st, {}


def multipart(fields, files):
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode("utf-8"))
    for name, filename, ctype, content in files:
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\nContent-Type: {ctype}\r\n\r\n'.encode("utf-8") + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def login(username):
    st, res = call("POST", "/api/v1/auth/login", body={"username": username, "password": "Admin@123"}, service="auth-service")
    return res["data"]["accessToken"]


def start_jar(module):
    jar = os.path.join(ROOT, "vetclinic-backend", module, "target", f"{module}.jar")
    if not os.path.exists(jar):
        sys.exit(f"KHÔNG THẤY {jar}: chạy 'cd vetclinic-backend && mvn -o -pl {module} install -DskipTests' trước")
    log = os.path.join(tempfile.gettempdir(), f"imaging-check-{module}.log")
    env = dict(os.environ, DB_HOST="localhost", DB_PORT="3399", DB_USERNAME="root", DB_PASSWORD="check", TZ="UTC", STORAGE_ROOT=STORE,
               JWT_SECRET="check-secret-check-secret-check-secret-0123456789", RABBITMQ_HOST="localhost", RABBITMQ_PORT=RABBIT_PORT, REDIS_HOST="localhost", REDIS_PORT=REDIS_PORT)
    args = [JAVA, "-Duser.timezone=UTC", "-jar", jar, f"--server.port={PORTS[module]}", "--eureka.client.enabled=false"]
    for name, port in PORTS.items():
        args.append(f"--spring.cloud.discovery.client.simple.instances.{name}[0].uri=http://localhost:{port}")
    proc = subprocess.Popen(args, stdout=open(log, "w"), stderr=subprocess.STDOUT, env=env)
    PROCS.append(proc)
    for _ in range(60):
        time.sleep(2)
        if "Started " in open(log, encoding="utf-8", errors="replace").read():
            return
        if proc.poll() is not None:
            break
    sys.exit(f"KHÔNG KHỞI ĐỘNG ĐƯỢC {module}; xem log {log}")


try:
    if MYSQL not in sh("docker", "ps", "--format", "{{.Names}}").split():
        sys.exit("Chưa có MySQL tạm: chạy 'bash scripts/service_check.sh clinical-service --reset' trước")
    sh("docker", "rm", "-f", RABBIT, REDIS)
    sh("docker", "run", "-d", "--name", REDIS, "-p", f"{REDIS_PORT}:6379", "redis:7-alpine")
    sh("docker", "run", "-d", "--name", RABBIT, "-p", f"{RABBIT_PORT}:5672", "-e", "RABBITMQ_DEFAULT_USER=vetclinic",
       "-e", "RABBITMQ_DEFAULT_PASS=vetclinic_dev", "rabbitmq:3.12-management")
    for _ in range(40):
        time.sleep(3)
        if "Server startup complete" in sh("docker", "logs", RABBIT):
            break
    time.sleep(3)
    for module in ("clinical-service", "auth-service"):
        start_jar(module)

    sql("DELETE FROM pet_clinic_clinical_db.queue_ticket_histories")   # Redis tạm đếm số phiếu lại từ 1: bỏ lịch sử của lần chạy trước (UNIQUE loại + ngày + số)
    sql(f"UPDATE pet_clinic_clinical_db.pet_visits SET status = 'CANCELLED' WHERE doctor_id = {b(TIEN)} AND status IN ('IN_PROGRESS', 'WAITING', 'RESULTS_READY')")
    rec, tien, img = login("letan_hoa"), login("bs_tien"), login("ktv_cdha_dung")
    lan, nam = login("khach_lan"), login("khach_nam")
    imaging_service = to_uuid(sql("SELECT HEX(s.id) FROM pet_clinic_clinical_db.services s JOIN pet_clinic_clinical_db.service_categories c ON c.id = s.category_id "
                                  "WHERE c.code = 'IMAGING' AND s.is_active = 1 ORDER BY s.code LIMIT 1"))

    # ================================================================== dựng ca: tiếp đón -> nộp phí khám -> khám -> chỉ định CĐHA -> nộp tiền -> bắt đầu ở phòng CĐHA
    st, res = call("POST", "/api/v1/pets", lan, {"speciesId": 1, "name": f"Ảnh-{SUFFIX}", "gender": "MALE"})
    pet = res["data"]["id"]
    st, res = call("POST", f"{BASE}/check-in", rec, {"petId": pet, "doctorId": TIEN, "reasonForVisit": "Chụp bụng"})
    visit = res["data"]["id"]

    def pay(vid):
        st, res = call("GET", f"{BASE}/{vid}/service-invoices", rec)
        ids = [i["id"] for i in res["data"]["invoices"] if i["paymentStatus"] == "PENDING" and i["id"]]
        res = call("POST", f"{BASE}/{vid}/service-invoices/confirm-payment", rec, {"invoiceIds": ids})
        if res[0] == 200:   # phiếu phát SAU khi thu tiền (ServiceInvoicePaidEvent -> Redis): chờ có thời hạn
            wait_for(lambda: call("GET", f"{BASE}/{vid}/service-invoices", rec)[1]["data"]["tickets"], 30)
        return res

    check("tiếp đón + thu phí khám", pay(visit)[0] == 200)
    check("bắt đầu khám (có cân nặng + thân nhiệt)", call("POST", f"{BASE}/{visit}/start-exam", tien, {"vitalSigns": {"weightKg": 5, "temperatureC": 38.5}})[0] == 200)
    st, res = call("POST", f"{BASE}/{visit}/paraclinical-orders", tien, {"serviceIds": [imaging_service]})
    check("chỉ định chẩn đoán hình ảnh -> 200", st == 200, (st, res))
    check("khách nộp tiền CĐHA -> phát phiếu phòng", pay(visit)[0] == 200)
    st, res = call("GET", "/api/v1/clinical/imaging-queue", img)
    item = next((i for i in res.get("data", []) if i["visitId"] == visit), None)
    check("ca có trong hàng đợi phòng CĐHA", item is not None, res)
    check("KTV bắt đầu phiếu phòng", call("POST", f"/api/v1/clinical/queue-tickets/{item['ticketId']}/start", img)[0] == 200)

    # ================================================================== tải ảnh lên: file_key theo Quy chuẩn 7.5
    detail = item["serviceDetailId"]
    body, ctype = multipart({"description": "Gan bình thường", "conclusion": "Không phát hiện bất thường"}, [("files", "sieu-am.png", "image/png", PNG)])
    st, headers, data = raw("POST", f"/api/v1/clinical/imaging-results/{detail}", img, body, ctype)
    check("hoàn tất CĐHA (multipart một request) -> 200", st == 200, (st, data[:300]))
    row = sql(f"SELECT HEX(a.id), a.file_key, a.original_file_name, a.content_type, a.file_size, HEX(a.uploaded_by) FROM pet_clinic_clinical_db.imaging_attachments a "
              f"JOIN pet_clinic_clinical_db.imaging_results r ON r.id = a.imaging_result_id WHERE r.visit_service_detail_id = {b(detail)}").split("\t")
    attachment_id = to_uuid(row[0]) if row and row[0] else None
    file_key = row[1] if len(row) > 1 else ""
    import re
    check("DB: imaging_attachments.id BINARY(16) + file_key imaging/yyyy/MM/<uuid>.png, tên gốc, loại, dung lượng, người tải lên",
          bool(re.fullmatch(r"imaging/\d{4}/\d{2}/[0-9a-f-]{36}\.png", file_key)) and row[2:5] == ["sieu-am.png", "image/png", str(len(PNG))]
          and row[5] == claims(img)["userId"].replace("-", "").upper(), row)
    check("tệp nằm đúng chỗ dưới STORAGE_ROOT/file_key", os.path.isfile(os.path.join(STORE, *file_key.split("/"))) and os.path.getsize(os.path.join(STORE, *file_key.split("/"))) == len(PNG))
    check("CSDL không chứa URL tuyệt đối", "http" not in "\t".join(row))
    st, res = call("GET", f"{BASE}/{visit}", tien)
    attachments = res["data"]["imagingResults"][0]["attachments"]
    check("chi tiết ca: attachments có id UUID, tên, dung lượng, loại và KHÔNG có URL", len(attachments) == 1 and attachments[0]["id"] == attachment_id
          and set(attachments[0]) == {"id", "fileName", "fileSize", "contentType"}, attachments)

    # ================================================================== URL ký tạm
    st, res = call("GET", f"/api/v1/clinical/imaging-attachments/{attachment_id}/url", tien)
    signed = res.get("data", {})
    url = signed.get("url", "")
    check("Bác sĩ xin URL -> /files/<file_key>?exp=..&sig=.. kèm expiresAt", st == 200 and url.startswith(f"/files/{file_key}?exp=") and "&sig=" in url and signed.get("expiresAt"), (st, res))
    exp = int(re.search(r"exp=(\d+)", url).group(1)) if url else 0
    check("hạn khoảng 1 giờ", abs(exp - (time.time() + 3600)) < 120, exp - time.time())
    st, headers, data = raw("GET", url)   # KHÔNG gửi header danh tính: chữ ký thay token
    check("tải ảnh bằng URL ký không cần đăng nhập: 200, image/png, đúng nội dung", st == 200 and headers.get("Content-Type") == "image/png" and data == PNG, (st, headers.get("Content-Type")))
    check("header an toàn: nosniff, hiển thị inline", headers.get("X-Content-Type-Options") == "nosniff" and "inline" in (headers.get("Content-Disposition") or ""))
    check("sửa chữ ký -> 403", raw("GET", url.replace("sig=", "sig=X"))[0] == 403)
    check("kéo dài hạn (exp) -> 403", raw("GET", url.replace(f"exp={exp}", f"exp={exp + 99999}"))[0] == 403)
    check("thiếu chữ ký -> 403", raw("GET", url.split("&sig=")[0])[0] == 403)
    other_key = file_key.replace(".png", ".jpg")
    check("dùng chữ ký của tệp này cho file_key khác (đổi đuôi) -> 403 hoặc 404", raw("GET", url.replace(file_key, other_key))[0] in (403, 404))
    check("file_key sai khuôn / đi ra ngoài thư mục -> 404", raw("GET", "/files/imaging/../../etc/passwd?exp=9999999999&sig=x")[0] in (400, 404)
          and raw("GET", "/files/khac/abc.png?exp=9999999999&sig=x")[0] == 404)
    # (hết hạn: kiểm tra ở unit test FileUrlSignerTest với đồng hồ giả, không chờ 1 giờ thật)
    check("đường dẫn cũ /imaging-attachments/{id}/file đã bỏ -> 404/405", raw("GET", f"/api/v1/clinical/imaging-attachments/{attachment_id}/file", tien)[0] in (404, 405))

    # ================================================================== quyền xin URL
    check("chủ nuôi của thú cưng xin được URL", call("GET", f"/api/v1/clinical/imaging-attachments/{attachment_id}/url", lan)[0] == 200)
    check("chủ nuôi khác KHÔNG xin được URL (404)", call("GET", f"/api/v1/clinical/imaging-attachments/{attachment_id}/url", nam)[0] == 404)
    check("không có danh tính -> 401/403", call("GET", f"/api/v1/clinical/imaging-attachments/{attachment_id}/url")[0] in (401, 403))
    check("id không tồn tại -> 404", call("GET", f"/api/v1/clinical/imaging-attachments/{uuid.uuid4()}/url", tien)[0] == 404)
    check("Lễ tân xin được URL", call("GET", f"/api/v1/clinical/imaging-attachments/{attachment_id}/url", rec)[0] == 200)

    # ================================================================== rollback: ghi CSDL lỗi thì xóa tệp đã ghi (gửi lại cho dịch vụ đã COMPLETED -> bị từ chối, không để tệp mồ côi)
    before = sum(len(f) for _, _, f in os.walk(STORE))
    body, ctype = multipart({"description": "Lần hai", "conclusion": "x"}, [("files", "lan2.png", "image/png", PNG)])
    st, headers, data = raw("POST", f"/api/v1/clinical/imaging-results/{detail}", img, body, ctype)
    check("nộp lại kết quả cho chỉ định đã hoàn tất -> bị từ chối", st >= 400, st)
    check("tệp vừa ghi bị xóa (không để lại tệp mồ côi)", sum(len(f) for _, _, f in os.walk(STORE)) == before)
    body, ctype = multipart({"description": "Giả", "conclusion": "x"}, [("files", "gia.png", "image/png", b"khong phai anh")])
    check("tệp giả ảnh (sai magic-byte) bị từ chối 400", raw("POST", f"/api/v1/clinical/imaging-results/{detail}", img, body, ctype)[0] == 400)
finally:
    for proc in PROCS:
        proc.terminate()
    sh("docker", "rm", "-f", RABBIT, REDIS)
    shutil.rmtree(STORE, ignore_errors=True)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: ảnh CĐHA lưu file_key, URL ký tạm, kiểm quyền, rollback chạy đúng trên MySQL + RabbitMQ thật")
