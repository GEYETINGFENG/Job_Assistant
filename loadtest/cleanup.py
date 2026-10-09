"""
删除压测产生的全部数据：S3 中压测账号的对象，以及数据库中压测账号及其简历、版本、上传任务、Refresh Token。

用法（在项目根目录）：
    python loadtest/cleanup.py
S3 删除通过官方 amazon/aws-cli 镜像执行，读取项目根目录 .env 中的 AWS 凭证、桶名和区域，本机无需安装 AWS CLI。
"""
import subprocess
import sys

from seed import ACCOUNT_PREFIX, psql

S3_PREFIXES = ("resume-uploads", "resume-processing", "resumes")


def read_env(name: str) -> str:
    with open(".env", encoding="utf-8") as f:
        for line in f:
            if line.startswith(f"{name}="):
                return line.split("=", 1)[1].strip()
    sys.exit(f"{name} not found in .env")


def main() -> None:
    user_ids = psql(f"SELECT string_agg(id::text, ',') FROM users WHERE user_account LIKE '{ACCOUNT_PREFIX}%'")
    if not user_ids:
        print("no loadtest users found, nothing to clean")
        return

    bucket = read_env("AWS_S3_BUCKET")
    for user_id in user_ids.split(","):
        for prefix in S3_PREFIXES:
            # 只删除 <prefix>/<压测用户ID>/ 下的对象，不会碰到真实用户的文件。
            subprocess.run(["docker", "run", "--rm", "--env-file", ".env", "amazon/aws-cli",
                            "s3", "rm", f"s3://{bucket}/{prefix}/{user_id}/", "--recursive", "--quiet"],
                           check=True)
    print(f"deleted S3 objects for loadtest users {user_ids}")

    # 按外键依赖从子表到父表删除。
    psql(f"""
        BEGIN;
        DELETE FROM application WHERE user_id IN ({user_ids});
        DELETE FROM resume_upload_session WHERE user_id IN ({user_ids});
        DELETE FROM resume_version WHERE resume_id IN (SELECT id FROM resume WHERE user_id IN ({user_ids}));
        DELETE FROM resume WHERE user_id IN ({user_ids});
        DELETE FROM refresh_tokens WHERE user_id IN ({user_ids});
        DELETE FROM users WHERE id IN ({user_ids});
        COMMIT;
    """)
    print(f"deleted database rows for loadtest users {user_ids}")


if __name__ == "__main__":
    main()
