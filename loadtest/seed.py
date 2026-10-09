"""
压测数据准备：通过注册接口创建压测专用账号，再用 SQL 批量插入简历，让列表排序问题在数据量下显现。

用法（在项目根目录，docker compose 已启动）：
    python loadtest/seed.py
可通过环境变量覆盖：LOADTEST_USERS（默认 20）、LOADTEST_RESUMES_PER_USER（默认 5000）。

压测账号统一以 loadtest 开头，只用于本地容器库，结束后执行 loadtest/cleanup.py 删除。
"""
import os
import subprocess
import sys

import requests

BASE = os.environ.get("LOADTEST_BASE_URL", "http://localhost:8080/api")
USERS = int(os.environ.get("LOADTEST_USERS", "20"))
RESUMES_PER_USER = int(os.environ.get("LOADTEST_RESUMES_PER_USER", "5000"))
# 仅用于本地压测账号的测试密码，k6 脚本通过同名环境变量读取（见 loadtest/README.md）。
PASSWORD = os.environ.get("LOADTEST_PASSWORD", "LoadTest-Passw0rd")
ACCOUNT_PREFIX = "loadtest"


def psql(sql: str) -> str:
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "jobassistant",
         "-v", "ON_ERROR_STOP=1", "-tA", "-c", sql],
        capture_output=True, text=True, encoding="utf-8")
    if result.returncode != 0:
        sys.exit(f"psql failed: {result.stderr}")
    return result.stdout.strip()


def main() -> None:
    accounts = [f"{ACCOUNT_PREFIX}{i:02d}" for i in range(1, USERS + 1)]
    for account in accounts:
        body = {"userAccount": account, "userPassword": PASSWORD, "checkPassword": PASSWORD}
        # 已存在的账号会注册失败，直接复用即可。
        requests.post(f"{BASE}/user/register", json=body, timeout=10)

    quoted = ",".join(f"'{a}'" for a in accounts)
    user_ids = psql(f"SELECT string_agg(id::text, ',' ORDER BY id) FROM users WHERE user_account IN ({quoted})")
    if not user_ids or len(user_ids.split(",")) != USERS:
        sys.exit(f"expected {USERS} loadtest users, found: {user_ids!r}")

    existing = int(psql(f"SELECT count(*) FROM resume WHERE user_id IN ({user_ids})"))
    target = USERS * RESUMES_PER_USER
    if existing >= target:
        print(f"already seeded: {existing} resumes for {USERS} loadtest users")
    else:
        # 每个用户插入 RESUMES_PER_USER 份简历，update_time 随机分布在过去一年，约 5% 为已删除。
        psql(f"""
            INSERT INTO resume (user_id, resume_name, file_url, status, latest_version_number,
                                is_delete, delete_time, create_time, update_time)
            SELECT u.id,
                   'Load Test Resume ' || g,
                   '/resumes/0/file',
                   0, 1,
                   CASE WHEN random() < 0.05 THEN 1 ELSE 0 END,
                   NULL,
                   now() - interval '400 days',
                   now() - random() * interval '365 days'
            FROM unnest(string_to_array('{user_ids}', ',')::bigint[]) AS u(id)
            CROSS JOIN generate_series(1, {RESUMES_PER_USER}) AS g
        """)
        psql("ANALYZE resume")
        print(f"inserted {target} resumes for {USERS} loadtest users")

    total = psql("SELECT count(*) FROM resume")
    print(f"loadtest user ids: {user_ids}")
    print(f"resume table total rows: {total}")


if __name__ == "__main__":
    main()
