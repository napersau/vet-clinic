"""Kiểm tra THẬT bước 6.1: kho phiếu xếp hàng trên Redis (5 Lua script + QueueRedisRepository) chạy trên Redis 7 thật, không cần dựng cả hệ thống.
  python scripts/redis_queue_check.py
Việc làm: (1) dựng container redis:7-alpine giống docker-compose (AOF, appendfsync everysec) ở cổng 6390; (2) chạy QueueRedisRepositoryLiveTest
(mvn, bật bằng REDIS_TEST_PORT): phát phiếu (số theo ngày / loại hàng đợi, thứ tự priority rồi giờ đến, 50 luồng phát đồng thời), chống trùng EX-19,
gọi / gọi lại, 10 yêu cầu "Bắt đầu" đồng thời đúng 1 thành công, mỗi Bác sĩ một phiếu một lúc, chuyển phiếu giữ số + thứ tự (EX-17), kết thúc / hủy,
Redis không truy cập được -> CLIN_1318; (3) kiểm tra AOF đang bật và dữ liệu sống sót qua lần khởi động lại Redis.
Cần: Docker, JDK 21 (JAVA_HOME), Maven. Đặt QUEUE_CHECK_BACKEND_DIR nếu muốn build trên bản sao khác (mặc định vetclinic-backend)."""
import os
import subprocess
import sys
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
BACKEND = os.environ.get("QUEUE_CHECK_BACKEND_DIR", os.path.join(ROOT, "vetclinic-backend"))
CONTAINER, PORT = "vetclinic-check-redis", "6390"
FAILS = []


def check(name, ok, detail=""):
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok else f"  -> {detail}"))
    if not ok:
        FAILS.append(name)


def sh(*args, **kwargs):
    return subprocess.run(args, capture_output=True, text=True, encoding="utf-8", errors="replace", **kwargs)


def redis_cli(*args):
    return sh("docker", "exec", CONTAINER, "redis-cli", *args).stdout.strip()


try:
    sh("docker", "rm", "-f", CONTAINER)
    started = sh("docker", "run", "-d", "--name", CONTAINER, "-p", f"{PORT}:6379", "redis:7-alpine", "redis-server", "--appendonly", "yes", "--appendfsync", "everysec")
    if started.returncode != 0:
        sys.exit("Không dựng được Redis: " + started.stderr)
    for _ in range(30):
        if redis_cli("ping") == "PONG":
            break
        time.sleep(1)
    check("Redis 7 khởi động, trả lời PONG", redis_cli("ping") == "PONG")
    check("AOF đang bật (appendonly yes)", "yes" in redis_cli("config", "get", "appendonly"))
    check("appendfsync everysec", "everysec" in redis_cli("config", "get", "appendfsync"))

    env = dict(os.environ, REDIS_TEST_PORT=PORT)
    env.setdefault("JAVA_HOME", "C:/Program Files/Java/jdk-21.0.6")
    mvn = "mvn.cmd" if os.name == "nt" else "mvn"
    run = subprocess.run([mvn, "-o", "-pl", "clinical-service", "test", "-Dtest=QueueRedisRepositoryLiveTest", "-Dsurefire.failIfNoSpecifiedTests=false"],
                         cwd=BACKEND, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = run.stdout
    summary = [line for line in out.splitlines() if "Tests run:" in line and "-- in " in line and "QueueRedisRepositoryLiveTest" in line]
    print("  " + (summary[-1].strip() if summary else "(không thấy dòng tổng kết của test)"))
    skipped = any("Skipped: 0" not in line for line in summary)
    check("QueueRedisRepositoryLiveTest: 15 ca chạy thật (không bị bỏ qua), 0 lỗi", run.returncode == 0 and not skipped and any("Tests run: 15, Failures: 0, Errors: 0" in line for line in summary),
          "\n".join(out.splitlines()[-40:]))

    # AOF: dữ liệu sống sót qua khởi động lại
    redis_cli("set", "queue:check:marker", "alive")
    ticket_keys = redis_cli("--scan", "--pattern", "ticket:*").splitlines()
    check("Redis còn phiếu do test để lại (TTL 36 giờ)", len(ticket_keys) > 0)
    time.sleep(2.5)   # appendfsync everysec
    sh("docker", "restart", CONTAINER)
    for _ in range(30):
        if redis_cli("ping") == "PONG":
            break
        time.sleep(1)
    check("AOF: sau khi Redis khởi động lại, dữ liệu vẫn còn", redis_cli("get", "queue:check:marker") == "alive")
    check("AOF: phiếu phát trước đó vẫn còn", len(redis_cli("--scan", "--pattern", "ticket:*").splitlines()) == len(ticket_keys))
finally:
    sh("docker", "rm", "-f", CONTAINER)

if FAILS:
    print(f"\n{len(FAILS)} KIỂM TRA THẤT BẠI: {FAILS}")
    sys.exit(1)
print("\nOK: kho phiếu xếp hàng trên Redis (5 Lua script) chạy đúng trên Redis 7 thật")
