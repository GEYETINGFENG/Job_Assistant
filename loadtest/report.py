"""
把 k6 summary JSON 整理成逐级「拐点表」，并可对比修复前后。

用法：
    python loadtest/report.py loadtest/results/list-before.json
    python loadtest/report.py loadtest/results/list-before.json loadtest/results/list-after.json
"""
import json
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")


def load_steps(path: str) -> tuple[str, dict[int, dict]]:
    with open(path, encoding="utf-8") as f:
        summary = json.load(f)
    metrics = summary["metrics"]
    step_seconds = summary.get("stepSeconds", 30)
    steps: dict[int, dict] = {}
    endpoint = None
    for name, metric in metrics.items():
        match = re.fullmatch(r"(http_req_duration|http_req_failed|http_reqs)\{scenario:vu_(\d+),ep:(\w+)\}", name)
        if not match:
            continue
        kind, vus, endpoint = match.group(1), int(match.group(2)), match.group(3)
        values = metric["values"]
        row = steps.setdefault(vus, {})
        if kind == "http_req_duration":
            row.update(p50=values["med"], p95=values["p(95)"], p99=values["p(99)"])
        elif kind == "http_req_failed":
            row["error"] = values["rate"]
        else:
            row["qps"] = values["count"] / step_seconds
    return endpoint, dict(sorted(steps.items()))


def print_table(title: str, steps: dict[int, dict]) -> None:
    print(f"\n### {title}\n")
    print("| 并发 VU | QPS | p50 (ms) | p95 (ms) | p99 (ms) | 错误率 |")
    print("|---:|---:|---:|---:|---:|---:|")
    for vus, row in steps.items():
        print(f"| {vus} | {row.get('qps', 0):.0f} | {row.get('p50', 0):.1f} | {row.get('p95', 0):.1f} "
              f"| {row.get('p99', 0):.1f} | {row.get('error', 0):.2%} |")


def main() -> None:
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    endpoint, before = load_steps(sys.argv[1])
    print_table(f"{endpoint}: {sys.argv[1]}", before)
    if len(sys.argv) == 3:
        _, after = load_steps(sys.argv[2])
        print_table(f"{endpoint}: {sys.argv[2]}", after)
        print("\n### 对比\n")
        print("| 并发 VU | QPS 前→后 | p95 前→后 (ms) | p95 变化 |")
        print("|---:|---:|---:|---:|")
        for vus in before:
            if vus in after:
                b, a = before[vus], after[vus]
                change = (a["p95"] - b["p95"]) / b["p95"] if b.get("p95") else 0
                print(f"| {vus} | {b['qps']:.0f} → {a['qps']:.0f} | {b['p95']:.1f} → {a['p95']:.1f} | {change:+.0%} |")


if __name__ == "__main__":
    main()
