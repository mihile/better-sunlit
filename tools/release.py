from pathlib import Path
import argparse, datetime, json, shutil, subprocess, sys

ROOT = Path(__file__).resolve().parents[1]
INSTANCE = ROOT.parents[1]
SHARED = [
    ".gitignore",
    "AGENTS.md",
    "MAINTAINER.md",
    "README.md",
    "build.py",
    "releases",
    "scripts",
    "src",
    "tests",
    "tools",
]


def run(cmd):
    print(">>", " ".join(map(str, cmd)), flush=True)
    subprocess.run(list(map(str, cmd)), cwd=ROOT, check=True)


def out(cmd):
    return subprocess.check_output(list(map(str, cmd)), cwd=ROOT, text=True, encoding="utf8").strip()


def last_shared_ts(branch):
    t = out(["git", "log", "-1", "--format=%ct", branch, "--"] + SHARED)
    return int(t) if t else 0


def latest_release_meta():
    rel_dir = ROOT / "releases"
    rel_files = sorted(rel_dir.glob("*.json"), key=lambda f: int(json.loads(f.read_text(encoding="utf8"))["build"]))
    latest_path = rel_files[-1]
    latest_data = json.loads(latest_path.read_text(encoding="utf8"))
    return rel_dir, latest_path, latest_data


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--side", choices=["client", "server"], required=True)
    p.add_argument("--local-only", action="store_true")
    args = p.parse_args()
    side = args.side

    if args.local_only:
        _, _, latest_data = latest_release_meta()
        build_num = str(latest_data["build"])
        label = latest_data["label"]
        print(f"=== Better Sunlit [{side}] 로컬 빌드 및 적용: build={build_num}, label={label} ===", flush=True)
        run([sys.executable, "build.py", "--instance", str(INSTANCE), "--side", side, "--build", build_num, "--label", label])
        run([sys.executable, "tools/test.py", "--side", side])
        built_jar = ROOT / "build" / side / "better-sunlit.jar"
        target_jar = INSTANCE / "minecraft" / "mods" / "better-sunlit.jar"
        target_jar.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(built_jar, target_jar)
        print(f"=== 로컬 클라이언트 모드 적용 완료: {target_jar} ===", flush=True)
        return

    other = "server" if side == "client" else "client"
    cur = out(["git", "branch", "--show-current"])
    if out(["git", "status", "--porcelain"]):
        run(["git", "add", "-A"])
        run(["git", "commit", "-m", f"Save changes on {cur} before {side} release"])

    if cur != side:
        run(["git", "checkout", side])

    if cur == other or last_shared_ts(other) > last_shared_ts(side):
        run(["git", "checkout", other, "--"] + SHARED)

    (ROOT / "release-channel.properties").write_text(f"channel={side}\n", encoding="utf8")

    releases_api = json.loads(out(["gh", "api", "repos/mihile/better-sunlit/releases?per_page=100"]))
    max_side_build = 0
    max_any_build = 0
    for r in releases_api:
        if r.get("draft"):
            continue
        tag = r.get("tag_name", "")
        for prefix in ("client-build-", "server-build-"):
            if tag.startswith(prefix):
                parts = tag[len(prefix):].split(".")
                num = int("".join(parts[:3]) + parts[3].zfill(2))
                max_any_build = max(max_any_build, num)
                if prefix.startswith(side + "-"):
                    max_side_build = max(max_side_build, num)

    rel_dir, latest_path, latest_data = latest_release_meta()
    max_any_build = max(max_any_build, int(latest_data["build"]))

    if int(latest_data["build"]) <= max_side_build:
        today = datetime.date.today().strftime("%Y%m%d")
        today_base = int(today) * 100 + 1
        next_build = max(max_any_build + 1, today_base)
        s = str(next_build)
        ymd, seq = s[:8], int(s[8:])
        next_label = f"{ymd[:4]}.{ymd[4:6]}.{ymd[6:8]}.{seq}"
        latest_data["build"] = next_build
        latest_data["label"] = next_label
        latest_path = rel_dir / f"{ymd}.{seq}.json"
        latest_path.write_text(json.dumps(latest_data, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
        print(f"새 릴리스 메타데이터 자동 생성: {latest_path.name} (build {next_build}, label {next_label})", flush=True)

    build_num = str(latest_data["build"])
    label = latest_data["label"]
    print(f"=== Better Sunlit [{side}] 릴리스 준비: build={build_num}, label={label} ===", flush=True)

    if out(["git", "status", "--porcelain"]):
        run(["git", "add", "-A"])
        run(["git", "commit", "-m", f"Prepare {side} release {label}"])

    if out(["git", "rev-parse", "HEAD"]) != out(["git", "rev-parse", f"origin/{side}"]):
        run(["git", "push", "origin", side])

    run([sys.executable, "build.py", "--instance", str(INSTANCE), "--side", side, "--build", build_num, "--label", label])
    run([sys.executable, "tools/test.py", "--side", side])
    run([sys.executable, "tools/publish.py", "--side", side, "--changes", str(latest_path)])
    print(f"=== Better Sunlit [{side}] {label} 릴리스 배포 완료! ===", flush=True)


if __name__ == "__main__":
    main()
