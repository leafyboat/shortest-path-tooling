"""Tests for ``scripts/maintenance.py``.

Every subprocess call goes through the module's single ``run`` seam, so
tests monkeypatch ``mm.run`` (or ``mm.subprocess.run`` for the seam
itself) and redirect ``mm.REPO``/``mm.SUBMODULE`` to ``tmp_path``
subdirs — no test touches the network, the real submodule, or the real
cache.  The script is loaded via importlib because ``scripts/`` has no
``__init__.py``.
"""

import importlib.util
import json
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
SCRIPT_PATH = ROOT / "scripts" / "maintenance.py"
FIXTURES = Path(__file__).resolve().parent / "fixtures"

spec = importlib.util.spec_from_file_location("maintenance", SCRIPT_PATH)
mm = importlib.util.module_from_spec(spec)
sys.modules["maintenance"] = mm
spec.loader.exec_module(mm)


def load_fixture(name):
    return json.loads((FIXTURES / name).read_text())


def cp(cmd, stdout="", stderr="", rc=0):
    return subprocess.CompletedProcess(cmd, rc, stdout, stderr)


def redirect_repo(tmp_path, monkeypatch):
    """Point the module's path constants at a scratch tree."""
    repo = tmp_path / "repo"
    submodule = repo / "shortest-path"
    submodule.mkdir(parents=True)
    monkeypatch.setattr(mm, "REPO", repo)
    monkeypatch.setattr(mm, "SUBMODULE", submodule)
    return repo, submodule


# ---------- run() seam ----------


def test_run_passes_list_argv_and_timeout(monkeypatch):
    calls = {}

    def fake_run(cmd, **kwargs):
        calls["cmd"] = cmd
        calls.update(kwargs)
        return cp(cmd)

    monkeypatch.setattr(mm.subprocess, "run", fake_run)
    mm.run(["git", "status"])
    assert isinstance(calls["cmd"], list)
    assert calls["cmd"] == ["git", "status"]
    assert isinstance(calls["timeout"], (int, float))
    assert calls["timeout"] > 0


def test_run_timeout_exits_cleanly(monkeypatch):
    def fake_run(cmd, **kwargs):
        raise mm.subprocess.TimeoutExpired(cmd, kwargs.get("timeout"))

    monkeypatch.setattr(mm.subprocess, "run", fake_run)
    with pytest.raises(SystemExit):
        mm.run(["git", "status"])


def test_run_missing_command_exits_cleanly(monkeypatch):
    def fake_run(cmd, **kwargs):
        raise FileNotFoundError(cmd[0])

    monkeypatch.setattr(mm.subprocess, "run", fake_run)
    with pytest.raises(SystemExit) as exc:
        mm.run(["definitely-not-a-real-binary"])
    assert "command not found" in str(exc.value)


def test_run_binary_mode(monkeypatch):
    calls = {}

    def fake_run(cmd, **kwargs):
        calls.update(kwargs)
        return subprocess.CompletedProcess(cmd, 0, b"bytes", b"")

    monkeypatch.setattr(mm.subprocess, "run", fake_run)
    proc = mm.run(["git", "show", "x:y"], binary=True)
    assert proc.stdout == b"bytes"
    # Binary mode must not decode — no text=True may reach the call.
    assert calls.get("text") is not True


# ---------- keys.json patching ----------


def test_patch_keys_json_renames_fields(tmp_path):
    path = tmp_path / "keys.json"
    path.write_text((FIXTURES / "keys_raw.json").read_text())
    renamed = mm.patch_keys_json(path)
    assert renamed >= 1
    entries = json.loads(path.read_text())
    assert entries == load_fixture("keys_patched.json")
    for entry in entries:
        assert "region" in entry and "keys" in entry
        assert "mapsquare" not in entry and "key" not in entry


def test_patch_keys_json_idempotent(tmp_path):
    path = tmp_path / "keys.json"
    path.write_text((FIXTURES / "keys_raw.json").read_text())
    assert mm.patch_keys_json(path) >= 1
    after_first = path.read_bytes()
    assert mm.patch_keys_json(path) == 0
    assert path.read_bytes() == after_first


def test_patch_keys_json_rejects_malformed(tmp_path):
    path = tmp_path / "keys.json"
    # Top-level object instead of a list.
    path.write_text('{"mapsquare": 1, "key": [1, 2, 3, 4]}')
    before = path.read_bytes()
    with pytest.raises(SystemExit):
        mm.patch_keys_json(path)
    assert path.read_bytes() == before
    # List containing a non-dict entry.
    path.write_text('[{"mapsquare": 1, "key": [1, 2, 3, 4]}, 42]')
    before = path.read_bytes()
    with pytest.raises(SystemExit):
        mm.patch_keys_json(path)
    assert path.read_bytes() == before


# ---------- cache subcommand ----------


def test_cache_invokes_download_with_repo_cwd(tmp_path, monkeypatch):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append((cmd, cwd, timeout))
        # The script drops ./cache and ./keys.json into its cwd.
        (repo / "cache").mkdir(exist_ok=True)
        (repo / "keys.json").write_text(
            (FIXTURES / "keys_raw.json").read_text())
        return cp(cmd)

    monkeypatch.setattr(mm, "run", fake_run)
    monkeypatch.setattr(mm, "check_tools", lambda names: None)
    rc = mm.main(["cache"])
    assert rc == 0
    assert len(calls) == 1
    cmd, cwd, timeout = calls[0]
    assert cmd == [
        str(repo / "collision-map-update" / "download-latest-cache.sh")]
    assert isinstance(cmd, list)
    assert cwd == repo
    assert timeout > 0
    # The raw fixture keys got patched in place by the subcommand.
    entries = json.loads((repo / "keys.json").read_text())
    assert entries == load_fixture("keys_patched.json")


def test_cache_missing_keys_json_fails(tmp_path, monkeypatch, capsys):
    repo, _ = redirect_repo(tmp_path, monkeypatch)

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        # Download "succeeds" but never produces keys.json.
        return cp(cmd)

    monkeypatch.setattr(mm, "run", fake_run)
    monkeypatch.setattr(mm, "check_tools", lambda names: None)
    rc = mm.main(["cache"])
    assert rc != 0
    assert "keys.json" in capsys.readouterr().err


def test_cache_download_failure_returns_1(tmp_path, monkeypatch, capsys):
    repo, _ = redirect_repo(tmp_path, monkeypatch)

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        return cp(cmd, stderr="curl: (6) could not resolve host", rc=22)

    monkeypatch.setattr(mm, "run", fake_run)
    monkeypatch.setattr(mm, "check_tools", lambda names: None)
    rc = mm.main(["cache"])
    assert rc == 1
    assert "could not resolve host" in capsys.readouterr().err


def test_cache_help_exits_zero():
    with pytest.raises(SystemExit) as exc:
        mm.main(["cache", "--help"])
    assert exc.value.code == 0


# ---------- collision-map (primary path) ----------


def collision_kind(cmd):
    """Bucket a recorded argv into the pipeline step it represents."""
    if cmd[:4] == ["git", "-C", "shortest-path", "status"]:
        return "status"
    if cmd[:4] == ["git", "-C", "shortest-path", "rev-parse"]:
        return "rev-parse-short" if "--short" in cmd else "rev-parse"
    if cmd[:4] == ["git", "-C", "shortest-path", "fetch"]:
        return "fetch"
    if cmd[:4] == ["git", "-C", "shortest-path", "merge"]:
        return "merge"
    if cmd[:4] == ["git", "-C", "shortest-path", "show"]:
        return "show"
    if cmd[:2] == ["git", "add"]:
        return "add"
    if cmd[:2] == ["git", "commit"]:
        return "commit"
    if cmd[0] == sys.executable:
        return "compare"
    return f"other:{cmd}"


def make_collision_run(calls, *, status_out="", heads=("oldsha", "newsha"),
                       fetch_rc=0, merge_rc=0, show_bytes=b"ZIPBYTES",
                       show_rc=0, show_stderr=b"",
                       diff_stdout="EDGE TOTALS\n",
                       diff_rc=0, short="newsha1",
                       add_rc=0, commit_rc=0):
    """fake mm.run for the primary submodule-bump path."""
    remaining = list(heads)

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append(list(cmd))
        kind = collision_kind(cmd)
        if kind == "status":
            return cp(cmd, status_out)
        if kind == "rev-parse":
            head = remaining.pop(0) if len(remaining) > 1 \
                else remaining[0]
            return cp(cmd, head)
        if kind == "rev-parse-short":
            return cp(cmd, short)
        if kind == "fetch":
            return cp(cmd, rc=fetch_rc)
        if kind == "merge":
            return cp(cmd, stderr="fatal: Not possible to "
                                  "fast-forward", rc=merge_rc)
        if kind == "show":
            assert binary is True, "git show of a zip must be binary"
            return subprocess.CompletedProcess(
                cmd, show_rc, show_bytes, show_stderr)
        if kind == "add":
            return cp(cmd, stderr="add failed", rc=add_rc)
        if kind == "commit":
            return cp(cmd, stderr="hook declined the commit",
                      rc=commit_rc)
        if kind == "compare":
            return cp(cmd, diff_stdout, rc=diff_rc)
        raise AssertionError(f"unexpected argv: {cmd}")

    return fake_run


def test_collision_map_bump_diff_sequence(tmp_path, monkeypatch, capsys):
    repo, submodule = redirect_repo(tmp_path, monkeypatch)
    calls = []
    monkeypatch.setattr(
        mm, "run",
        make_collision_run(calls, heads=["oldsha0000", "newsha1111"]))
    rc = mm.main(["collision-map"])
    assert rc == 0
    assert [collision_kind(c) for c in calls] == [
        "status", "rev-parse", "fetch", "merge", "rev-parse",
        "show", "compare"]
    show = calls[5]
    assert show[3] == "show"
    assert show[4] == "oldsha0000:src/main/resources/collision-map.zip"
    old_zip = repo / "build" / "old-collision-map.zip"
    assert old_zip.read_bytes() == b"ZIPBYTES"
    compare = calls[6]
    assert compare[1].endswith("compare_collision_maps.py")
    assert compare[2] == str(old_zip)
    assert compare[3] == str(
        submodule / "src" / "main" / "resources" / "collision-map.zip")
    assert "EDGE TOTALS" in capsys.readouterr().out


def test_collision_map_already_up_to_date(tmp_path, monkeypatch, capsys):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []
    monkeypatch.setattr(
        mm, "run",
        make_collision_run(calls, heads=["samesha", "samesha"]))
    rc = mm.main(["collision-map"])
    assert rc == 0
    kinds = [collision_kind(c) for c in calls]
    assert kinds == ["status", "rev-parse", "fetch", "merge",
                     "rev-parse"]
    assert "show" not in kinds and "compare" not in kinds
    assert "up to date" in capsys.readouterr().out


def test_collision_map_dirty_worktree_refused(tmp_path, monkeypatch):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []
    monkeypatch.setattr(
        mm, "run", make_collision_run(calls, status_out=" M file"))
    with pytest.raises(SystemExit):
        mm.main(["collision-map"])
    kinds = [collision_kind(c) for c in calls]
    assert kinds == ["status"]


def test_collision_map_diverged_fails(tmp_path, monkeypatch, capsys):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []
    monkeypatch.setattr(
        mm, "run", make_collision_run(calls, merge_rc=128))
    rc = mm.main(["collision-map"])
    assert rc == 1
    err = capsys.readouterr().err
    assert "merge or rebase" in err


def test_collision_map_show_failure_with_bytes_stderr(
        tmp_path, monkeypatch, capsys):
    # binary=True captures stderr as bytes — the diagnostic path must
    # decode it and return 1, not crash on a TypeError.
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []
    monkeypatch.setattr(
        mm, "run",
        make_collision_run(
            calls, show_rc=128,
            show_stderr=b"fatal: path does not exist"))
    rc = mm.main(["collision-map"])
    assert rc == 1
    err = capsys.readouterr().err
    assert "could not extract collision-map.zip" in err
    assert "fatal: path does not exist" in err
    kinds = [collision_kind(c) for c in calls]
    assert "compare" not in kinds


def test_collision_map_commit_flag(tmp_path, monkeypatch):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []
    monkeypatch.setattr(
        mm, "run", make_collision_run(calls, short="abc1234"))
    rc = mm.main(["collision-map", "--commit"])
    assert rc == 0
    kinds = [collision_kind(c) for c in calls]
    assert kinds[-3:] == ["rev-parse-short", "add", "commit"]
    add = calls[-2]
    commit = calls[-1]
    assert add == ["git", "add", "shortest-path"]
    assert commit == ["git", "commit", "-m",
                      "chore: update shortest-path submodule to abc1234"]


def test_collision_map_commit_failures_return_1(tmp_path, monkeypatch,
                                                capsys):
    # A failed `git add` or `git commit` (hook, git error) must
    # surface — an unchecked failure silently loses the gitlink bump.
    for i, kwargs in enumerate(({"add_rc": 1}, {"commit_rc": 1})):
        repo, _ = redirect_repo(tmp_path / str(i), monkeypatch)
        calls = []
        monkeypatch.setattr(
            mm, "run", make_collision_run(calls, **kwargs))
        rc = mm.main(["collision-map", "--commit"])
        assert rc == 1
        assert "failed" in capsys.readouterr().err


def test_collision_map_no_commit_prints_hint(tmp_path, monkeypatch,
                                             capsys):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []
    monkeypatch.setattr(mm, "run", make_collision_run(calls))
    rc = mm.main(["collision-map"])
    assert rc == 0
    kinds = [collision_kind(c) for c in calls]
    assert "add" not in kinds and "commit" not in kinds
    out = capsys.readouterr().out
    assert "git add shortest-path" in out
    assert "chore: update shortest-path submodule" in out


# ---------- collision-map --local (fallback pipeline) ----------


def local_kind(cmd):
    """Bucket a recorded argv into the local-pipeline step it
    represents."""
    if cmd[:4] == ["git", "-C", "shortest-path", "rev-parse"]:
        return "upstream" if "@{u}" in cmd else "branch"
    if cmd[:4] == ["git", "-C", "shortest-path", "status"]:
        return "status"
    if cmd[0].endswith("download-latest-cache.sh"):
        return "download"
    if cmd[:2] == ["git", "clone"]:
        return "clone"
    if cmd[:2] == ["git", "-C"] and "fetch" in cmd:
        return "fetch"
    if cmd[:2] == ["git", "-C"] and "reset" in cmd:
        return "reset"
    if cmd[:3] == ["git", "apply", "--check"]:
        return "apply-check"
    if cmd[:3] == ["git", "apply", "--reverse"]:
        return "apply-reverse-check"
    if cmd[:2] == ["git", "apply"]:
        return "apply"
    if cmd[0].endswith("gradlew"):
        return "shadowJar"
    if cmd[0] == "java":
        return "java"
    if cmd[0] == "zip":
        return "zip"
    if cmd[0] == sys.executable:
        return "compare"
    return f"other:{cmd}"


def make_local_run(repo, calls, *, branch="maint-x",
                   upstream="origin/maint-x", upstream_rc=0,
                   status_out="", download_rc=0,
                   fetch_rc=0, reset_rc=0, clone_rc=0,
                   apply_check_rc=0, reverse_check_rc=1, apply_rc=0,
                   shadow_rc=0, java_rc=0, zip_rc=0,
                   diff_stdout="EDGE TOTALS\n"):
    """fake mm.run for the --local pipeline; fabricates the filesystem
    effects each step would produce under the tmp repo."""
    runelite = repo / "build" / "runelite-work" / "runelite"
    cache_mod = runelite / "cache"
    download_script = str(
        repo / "collision-map-update" / "download-latest-cache.sh")

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append((list(cmd), cwd))
        kind = local_kind(cmd)
        if kind == "branch":
            return cp(cmd, branch)
        if kind == "upstream":
            return cp(cmd, upstream if upstream_rc == 0 else "",
                      "" if upstream_rc == 0 else "no upstream",
                      rc=upstream_rc)
        if kind == "status":
            return cp(cmd, status_out)
        if kind == "download":
            (repo / "cache").mkdir(exist_ok=True)
            (repo / "keys.json").write_text(
                (FIXTURES / "keys_raw.json").read_text())
            return cp(cmd, rc=download_rc)
        if kind == "clone":
            if clone_rc == 0:
                target = Path(cmd[-1])
                (target / "cache" / "src" / "main" / "java" / "net" /
                 "runelite" / "cache").mkdir(parents=True)
                (target / ".git").mkdir()
            return cp(cmd, stderr="clone failed", rc=clone_rc)
        if kind == "fetch":
            return cp(cmd, stderr="fetch failed", rc=fetch_rc)
        if kind == "reset":
            return cp(cmd, stderr="reset failed", rc=reset_rc)
        if kind == "apply-check":
            return cp(cmd, stderr="patch does not apply",
                      rc=apply_check_rc)
        if kind == "apply-reverse-check":
            return cp(cmd, rc=reverse_check_rc)
        if kind == "apply":
            return cp(cmd, stderr="apply failed", rc=apply_rc)
        if kind == "shadowJar":
            libs = cache_mod / "build" / "libs"
            libs.mkdir(parents=True, exist_ok=True)
            (libs / "cache-1.0-all.jar").write_bytes(b"JAR")
            return cp(cmd, rc=shadow_rc)
        if kind == "java":
            return cp(cmd, rc=java_rc)
        if kind == "zip":
            Path(cwd, "collision-map.zip").write_bytes(b"NEWZIP")
            return cp(cmd, rc=zip_rc)
        if kind == "compare":
            return cp(cmd, diff_stdout)
        raise AssertionError(f"unexpected argv: {cmd}")

    return fake_run


def prepare_local(tmp_path, monkeypatch, *, cache_ready=True,
                  existing_zip=True, **run_kwargs):
    """Common --local setup: redirected repo, stubbed tool check, fake
    run seam.  Returns (repo, submodule, calls)."""
    repo, submodule = redirect_repo(tmp_path, monkeypatch)
    monkeypatch.setattr(mm, "check_tools", lambda names: None)
    # The pipeline copies real inputs out of collision-map-update/ —
    # mirror the directory into the scratch repo.
    shutil.copytree(ROOT / "collision-map-update",
                    repo / "collision-map-update")
    resources = submodule / "src" / "main" / "resources"
    resources.mkdir(parents=True)
    if existing_zip:
        (resources / "collision-map.zip").write_bytes(b"OLDZIP")
    if cache_ready:
        (repo / "cache").mkdir()
        (repo / "keys.json").write_text(
            (FIXTURES / "keys_patched.json").read_text())
    calls = []
    monkeypatch.setattr(
        mm, "run", make_local_run(repo, calls, **run_kwargs))
    return repo, submodule, calls


def test_collision_map_local_sequence(tmp_path, monkeypatch, capsys):
    # Cache absent -> the pipeline downloads it first; existing
    # submodule zip -> snapshotted as the diff baseline.
    repo, submodule, calls = prepare_local(
        tmp_path, monkeypatch, cache_ready=False)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert kinds == [
        "branch", "upstream", "status", "download", "clone",
        "apply-check", "apply", "shadowJar", "java", "zip", "compare"]

    runelite = repo / "build" / "runelite-work" / "runelite"
    build = repo / "build"
    # Clone argv and every scratch path live under build/.
    clone = next(c for c, _ in calls if local_kind(c) == "clone")
    assert clone[:4] == ["git", "clone", "--depth", "1"]
    assert clone[4] == "https://github.com/runelite/runelite"
    assert clone[5] == str(runelite)

    gradle = next(c for c, _ in calls if local_kind(c) == "shadowJar")
    assert gradle == [str(runelite / "gradlew"), ":cache:shadowJar",
                      "-x", "test", "--dependency-verification=off"]
    gradle_cwd = next(w for c, w in calls
                      if local_kind(c) == "shadowJar")
    assert gradle_cwd == runelite

    java = next(c for c, _ in calls if local_kind(c) == "java")
    assert java[:3] == ["java", "-jar", str(build / "cache.jar")]
    assert str(build / "collision-output") in java

    # The new zip landed in the submodule; the old one was preserved.
    new_zip = (submodule / "src" / "main" / "resources" /
               "collision-map.zip")
    assert new_zip.read_bytes() == b"NEWZIP"
    assert (build / "old-collision-map.zip").read_bytes() == b"OLDZIP"
    compare = next(c for c, _ in calls if local_kind(c) == "compare")
    assert compare[2] == str(build / "old-collision-map.zip")
    assert compare[3] == str(new_zip)
    assert "EDGE TOTALS" in capsys.readouterr().out


def test_collision_map_local_cleans_stale_output(tmp_path, monkeypatch):
    # Leftovers in collision-output — including a stale zip, which
    # `zip -r` would update rather than recreate — must not survive
    # into the artifact moved into the submodule.
    repo, submodule, _ = prepare_local(tmp_path, monkeypatch)
    output_dir = repo / "build" / "collision-output"
    output_dir.mkdir(parents=True)
    (output_dir / "stale.bin").write_bytes(b"STALE")
    (output_dir / "collision-map.zip").write_bytes(b"STALEZIP")
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    assert not (output_dir / "stale.bin").exists()
    new_zip = (submodule / "src" / "main" / "resources" /
               "collision-map.zip")
    assert new_zip.read_bytes() == b"NEWZIP"


def test_collision_map_local_checks_tools_first(tmp_path, monkeypatch):
    # git/java/zip are required up front — a missing binary must abort
    # before the submodule gates, not crash mid-pipeline.
    repo, _, _ = prepare_local(tmp_path, monkeypatch)
    seen = []
    monkeypatch.setattr(mm, "check_tools", seen.append)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    assert seen == [["git", "java", "zip"]]


def test_collision_map_local_missing_tool_aborts(tmp_path, monkeypatch):
    repo, _, calls = prepare_local(tmp_path, monkeypatch)

    def missing(names):
        raise SystemExit(f"missing required tools: {names[0]}")

    monkeypatch.setattr(mm, "check_tools", missing)
    with pytest.raises(SystemExit):
        mm.main(["collision-map", "--local"])
    # The tool gate runs before any subprocess call.
    assert calls == []


def test_collision_map_local_refuses_master(tmp_path, monkeypatch):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, branch="master")
    with pytest.raises(SystemExit):
        mm.main(["collision-map", "--local"])
    kinds = [local_kind(c) for c, _ in calls]
    assert kinds == ["branch"]


def test_collision_map_local_refuses_detached(tmp_path, monkeypatch):
    repo, _, calls = prepare_local(tmp_path, monkeypatch, branch="HEAD")
    with pytest.raises(SystemExit):
        mm.main(["collision-map", "--local"])
    kinds = [local_kind(c) for c, _ in calls]
    assert kinds == ["branch"]


def test_collision_map_local_refuses_upstream_tracking(tmp_path,
                                                     monkeypatch):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, upstream="upstream/master")
    with pytest.raises(SystemExit):
        mm.main(["collision-map", "--local"])
    kinds = [local_kind(c) for c, _ in calls]
    assert kinds == ["branch", "upstream"]
    assert "clone" not in kinds and "download" not in kinds


def test_collision_map_local_no_upstream_warns_but_allows(
        tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, upstream_rc=1, existing_zip=False)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    assert "no upstream" in capsys.readouterr().err
    kinds = [local_kind(c) for c, _ in calls]
    assert "clone" in kinds


def test_collision_map_local_existing_clone_refreshed(tmp_path,
                                                      monkeypatch):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, existing_zip=False)
    runelite = repo / "build" / "runelite-work" / "runelite"
    cache_mod = runelite / "cache"
    (cache_mod / "src" / "main" / "java" / "net" / "runelite" /
     "cache").mkdir(parents=True)
    (runelite / ".git").mkdir()
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert "clone" not in kinds
    assert "fetch" in kinds and "reset" in kinds
    assert kinds.index("fetch") < kinds.index("reset") < \
        kinds.index("apply-check")


def test_collision_map_local_existing_clone_failures(
        tmp_path, monkeypatch, capsys):
    # An unchecked fetch/reset builds whatever the clone happens to
    # hold — each failure must abort the pipeline with a diagnostic.
    for i, kwargs in enumerate(({"fetch_rc": 128},
                                {"reset_rc": 128})):
        repo, _, calls = prepare_local(
            tmp_path / str(i), monkeypatch, existing_zip=False,
            **kwargs)
        runelite = repo / "build" / "runelite-work" / "runelite"
        (runelite / ".git").mkdir(parents=True)
        rc = mm.main(["collision-map", "--local"])
        assert rc == 1
        assert "failed" in capsys.readouterr().err
        kinds = [local_kind(c) for c, _ in calls]
        assert "shadowJar" not in kinds and "java" not in kinds


def test_collision_map_local_clone_failure(tmp_path, monkeypatch,
                                           capsys):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, existing_zip=False, clone_rc=128)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 1
    assert "git clone of runelite failed" in capsys.readouterr().err
    kinds = [local_kind(c) for c, _ in calls]
    # The pipeline must stop before copying the dumper into a
    # nonexistent tree.
    assert "apply-check" not in kinds and "shadowJar" not in kinds


def test_collision_map_local_apply_failure(tmp_path, monkeypatch,
                                           capsys):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, existing_zip=False, apply_rc=1)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 1
    assert "git apply" in capsys.readouterr().err
    kinds = [local_kind(c) for c, _ in calls]
    assert "shadowJar" not in kinds


def test_collision_map_local_patch_already_applied(tmp_path,
                                                   monkeypatch, capsys):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, existing_zip=False,
        apply_check_rc=1, reverse_check_rc=0)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert "apply-check" in kinds
    assert "apply-reverse-check" in kinds
    assert "apply" not in kinds
    assert "shadowJar" in kinds
    assert "already applied" in capsys.readouterr().out


def test_collision_map_local_old_zip_preserved(tmp_path, monkeypatch):
    repo, submodule, calls = prepare_local(tmp_path, monkeypatch)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    old_zip = repo / "build" / "old-collision-map.zip"
    assert old_zip.read_bytes() == b"OLDZIP"
    compare = next(c for c, _ in calls if local_kind(c) == "compare")
    assert compare[2] == str(old_zip)


def test_collision_map_local_no_baseline_no_diff_message(
        tmp_path, monkeypatch, capsys):
    # With no prior collision-map.zip there is no diff — the closing
    # message must not claim there is one.
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, existing_zip=False)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert "compare" not in kinds
    out = capsys.readouterr().out
    assert "no previous collision-map.zip" in out
    assert "review the diff" not in out


def test_collision_map_local_missing_cache_downloads_first(
        tmp_path, monkeypatch):
    repo, _, calls = prepare_local(
        tmp_path, monkeypatch, cache_ready=False, existing_zip=False)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert kinds.index("download") < kinds.index("clone")


def test_compare_only_skips_write_gates(tmp_path, monkeypatch):
    # On master with a dirty worktree plain --local refuses at the
    # first gate; --compare-only writes nothing into the submodule so
    # the write-branch/clean-worktree gates do not apply.
    repo, _, calls = prepare_local(
        tmp_path / "plain", monkeypatch, branch="master",
        status_out=" M dirty.tsv")
    with pytest.raises(SystemExit):
        mm.main(["collision-map", "--local"])
    assert [local_kind(c) for c, _ in calls] == ["branch"]

    repo, _, calls = prepare_local(
        tmp_path / "compare", monkeypatch, branch="master",
        status_out=" M dirty.tsv")
    rc = mm.main(["collision-map", "--local", "--compare-only"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert "branch" not in kinds and "status" not in kinds


def test_compare_only_diverts_output(tmp_path, monkeypatch):
    # The regenerated zip lands under build/ — the submodule artifact
    # stays byte-identical.
    repo, submodule, calls = prepare_local(tmp_path, monkeypatch)
    rc = mm.main(["collision-map", "--local", "--compare-only"])
    assert rc == 0
    new_zip = (submodule / "src" / "main" / "resources" /
               "collision-map.zip")
    assert new_zip.read_bytes() == b"OLDZIP"
    staged = repo / "build" / "validate-collision-map.zip"
    assert staged.read_bytes() == b"NEWZIP"


def test_compare_only_diffs(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_local(tmp_path, monkeypatch)
    rc = mm.main(["collision-map", "--local", "--compare-only"])
    assert rc == 0
    build = repo / "build"
    compare = next(c for c, _ in calls if local_kind(c) == "compare")
    assert compare[2] == str(build / "old-collision-map.zip")
    assert compare[3] == str(build / "validate-collision-map.zip")
    out = capsys.readouterr().out
    assert "EDGE TOTALS" in out
    assert "compare-only" in out
    # Advisory wording — never a commit/PR instruction.
    assert "commit on your origin" not in out


def test_compare_only_no_baseline(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_local(tmp_path, monkeypatch,
                                   existing_zip=False)
    rc = mm.main(["collision-map", "--local", "--compare-only"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert "compare" not in kinds
    out = capsys.readouterr().out
    assert "no committed baseline" in out
    assert "commit" not in out.split("baseline", 1)[-1]


def test_compare_only_requires_local(tmp_path, monkeypatch, capsys):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append(list(cmd))
        raise AssertionError(f"unexpected argv: {cmd}")

    monkeypatch.setattr(mm, "run", fake_run)
    rc = mm.main(["collision-map", "--compare-only"])
    assert rc == 1
    assert "requires --local" in capsys.readouterr().err
    assert calls == []


def test_local_unchanged(tmp_path, monkeypatch, capsys):
    # Plain --local is identical: gates enforced, the zip lands in the
    # submodule, and the closing wording points at the commit.
    repo, submodule, calls = prepare_local(tmp_path, monkeypatch)
    rc = mm.main(["collision-map", "--local"])
    assert rc == 0
    kinds = [local_kind(c) for c, _ in calls]
    assert kinds[:3] == ["branch", "upstream", "status"]
    new_zip = (submodule / "src" / "main" / "resources" /
               "collision-map.zip")
    assert new_zip.read_bytes() == b"NEWZIP"
    assert not (repo / "build" / "validate-collision-map.zip").exists()
    assert "commit on your origin" in capsys.readouterr().out


# ---------- regions + bank subcommands ----------


def write_gate_kind(cmd):
    """Bucket a recorded argv into the write-gate probe it answers."""
    if cmd[:4] == ["git", "-C", "shortest-path", "rev-parse"]:
        return "upstream" if "@{u}" in cmd else "branch"
    if cmd[:4] == ["git", "-C", "shortest-path", "status"]:
        return "status"
    return f"other:{cmd}"


def make_dump_run(repo, calls, *, branch="maint-x",
                  upstream="origin/maint-x", upstream_rc=0,
                  status_out="", league_rc=0, f2p_rc=0, bank_rc=0,
                  script_rc=0, script_stdout=""):
    """fake mm.run for the regions/bank subcommands: answers the
    write-gate git probes and fabricates each dumper's build/ output."""
    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append((list(cmd), cwd, timeout))
        kind = write_gate_kind(cmd)
        if kind == "branch":
            return cp(cmd, branch)
        if kind == "upstream":
            return cp(cmd, upstream if upstream_rc == 0 else "",
                      "" if upstream_rc == 0 else "no upstream",
                      rc=upstream_rc)
        if kind == "status":
            return cp(cmd, status_out)
        if cmd[:2] == ["./gradlew", "leagueRegionDump"]:
            out = repo / "build" / "league-regions"
            out.mkdir(parents=True, exist_ok=True)
            (out / "regions.tsv").write_text("1\tVARLAMORE\n")
            return cp(cmd, "league dump ok", rc=league_rc)
        if cmd[:2] == ["./gradlew", "f2pRegionDump"]:
            out = repo / "build" / "f2p-regions"
            out.mkdir(parents=True, exist_ok=True)
            (out / "regions.tsv").write_text("2\tF2P\n")
            return cp(cmd, "f2p dump ok", rc=f2p_rc)
        if cmd[:2] == ["./gradlew", "bankTileDump"]:
            out = repo / "build" / "bank-tiles"
            out.mkdir(parents=True, exist_ok=True)
            (out / "bank_tile_placements.tsv").write_text("P\tL\n")
            return cp(cmd, "bank dump ok", rc=bank_rc)
        if cmd[0] == sys.executable:
            return cp(cmd, script_stdout, rc=script_rc)
        raise AssertionError(f"unexpected argv: {cmd}")

    return fake_run


def prepare_dump_repo(tmp_path, monkeypatch, *, cache_ready=True,
                      f2p_resources=False, f2p_java=False,
                      **run_kwargs):
    """Common regions/bank setup: redirected repo with cache + keys and
    the submodule's existing leagues/ resource dir.  Returns
    (repo, submodule, calls)."""
    repo, submodule = redirect_repo(tmp_path, monkeypatch)
    if cache_ready:
        (repo / "cache").mkdir()
        (repo / "keys.json").write_text(
            (FIXTURES / "keys_patched.json").read_text())
    resources = submodule / "src" / "main" / "resources"
    (resources / "leagues").mkdir(parents=True)
    (resources / "leagues" / "regions.tsv").write_text("OLD\n")
    if f2p_resources:
        (resources / "f2p").mkdir(parents=True)
    if f2p_java:
        pkg = submodule / "src" / "main" / "java" / "shortestpath" / "f2p"
        pkg.mkdir(parents=True)
        (pkg / "F2pRegionChecker.java").write_text(
            "class F2pRegionChecker {}\n")
    calls = []
    monkeypatch.setattr(
        mm, "run", make_dump_run(repo, calls, **run_kwargs))
    return repo, submodule, calls


def gradle_calls(calls):
    return [c for c, _, _ in calls if c[0] == "./gradlew"]


def test_regions_invokes_both_dumps_sequentially(tmp_path, monkeypatch):
    repo, _, calls = prepare_dump_repo(tmp_path, monkeypatch)
    rc = mm.main(["regions"])
    assert rc == 0
    dumps = gradle_calls(calls)
    assert dumps == [
        ["./gradlew", "leagueRegionDump",
         f"-PleagueRegionsCacheDir={repo / 'cache'}",
         f"-PleagueRegionsXteaPath={repo / 'keys.json'}"],
        ["./gradlew", "f2pRegionDump",
         f"-Pf2pRegionsCacheDir={repo / 'cache'}",
         f"-Pf2pRegionsXteaPath={repo / 'keys.json'}"],
    ]
    for cmd, cwd, timeout in calls:
        if cmd[0] == "./gradlew":
            assert cwd == repo
            assert timeout == mm.GRADLE_TIMEOUT_SECONDS


def test_regions_copies_leagues_unconditionally(tmp_path, monkeypatch):
    repo, submodule, calls = prepare_dump_repo(tmp_path, monkeypatch)
    rc = mm.main(["regions"])
    assert rc == 0
    copied = (submodule / "src" / "main" / "resources" / "leagues" /
              "regions.tsv")
    assert copied.read_text() == "1\tVARLAMORE\n"


def test_regions_f2p_skipped_without_consumer(tmp_path, monkeypatch,
                                              capsys):
    repo, submodule, calls = prepare_dump_repo(tmp_path, monkeypatch)
    rc = mm.main(["regions"])
    assert rc == 0
    f2p_out = (submodule / "src" / "main" / "resources" / "f2p" /
               "regions.tsv")
    assert not f2p_out.exists()
    # The dump still ran — only the copy is gated.
    assert any(c[:2] == ["./gradlew", "f2pRegionDump"]
               for c in gradle_calls(calls))
    out = capsys.readouterr().out
    assert "build/f2p-regions/regions.tsv" in out
    assert "--f2p" in out


def test_regions_f2p_copied_when_consumer_present(tmp_path, monkeypatch):
    repo, submodule, calls = prepare_dump_repo(
        tmp_path, monkeypatch, f2p_resources=True)
    rc = mm.main(["regions"])
    assert rc == 0
    copied = (submodule / "src" / "main" / "resources" / "f2p" /
              "regions.tsv")
    assert copied.read_text() == "2\tF2P\n"


def test_regions_f2p_copied_when_java_consumer(tmp_path, monkeypatch):
    repo, submodule, calls = prepare_dump_repo(
        tmp_path, monkeypatch, f2p_java=True)
    rc = mm.main(["regions"])
    assert rc == 0
    copied = (submodule / "src" / "main" / "resources" / "f2p" /
              "regions.tsv")
    assert copied.read_text() == "2\tF2P\n"


def test_regions_f2p_forced_flag(tmp_path, monkeypatch):
    repo, submodule, calls = prepare_dump_repo(tmp_path, monkeypatch)
    rc = mm.main(["regions", "--f2p"])
    assert rc == 0
    copied = (submodule / "src" / "main" / "resources" / "f2p" /
              "regions.tsv")
    assert copied.read_text() == "2\tF2P\n"


def test_regions_refuses_on_master_and_detached(tmp_path, monkeypatch):
    for name, branch in (("a", "master"), ("b", "HEAD")):
        repo, _, calls = prepare_dump_repo(
            tmp_path / name, monkeypatch, branch=branch)
        rc = mm.main(["regions"])
        assert rc == 1
        assert gradle_calls(calls) == []


def test_regions_missing_cache_refuses(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_dump_repo(
        tmp_path, monkeypatch, cache_ready=False)
    rc = mm.main(["regions"])
    assert rc == 1
    assert "maintenance.py cache" in capsys.readouterr().err
    assert gradle_calls(calls) == []


def test_bank_sequence(tmp_path, monkeypatch):
    repo, _, calls = prepare_dump_repo(tmp_path, monkeypatch)
    rc = mm.main(["bank"])
    assert rc == 0
    dumps = gradle_calls(calls)
    assert dumps == [[
        "./gradlew", "bankTileDump",
        f"-PbankTileCacheDir={repo / 'cache'}",
        f"-PbankTileXteaPath={repo / 'keys.json'}"]]
    # The dump must use its default output path — the same hardcoded
    # PLACEMENTS_TSV rebuild_bank_tsv.py reads.
    assert not any("bankTileOutput" in arg for arg in dumps[0])
    script_calls = [c for c, _, _ in calls if c[0] == sys.executable]
    assert script_calls == [[
        sys.executable,
        str(repo / "scripts" / "rebuild_bank_tsv.py")]]
    kinds = [("dump" if c[0] == "./gradlew" else "script")
             for c, _, _ in calls if c[0] in ("./gradlew",
                                              sys.executable)]
    assert kinds == ["dump", "script"]


def test_bank_refuses_on_master(tmp_path, monkeypatch):
    repo, _, calls = prepare_dump_repo(
        tmp_path, monkeypatch, branch="master")
    rc = mm.main(["bank"])
    assert rc == 1
    assert gradle_calls(calls) == []


def test_bank_missing_cache_refuses(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_dump_repo(
        tmp_path, monkeypatch, cache_ready=False)
    rc = mm.main(["bank"])
    assert rc == 1
    assert "maintenance.py cache" in capsys.readouterr().err
    assert gradle_calls(calls) == []


# ---------- seasonal subcommand + transports path ----------


def load_vsr():
    """Load scripts/verify_seasonal_regions.py as a module."""
    import os  # noqa: F401  (kept local: vsr constants are os.path-based)
    spec = importlib.util.spec_from_file_location(
        "verify_seasonal_regions",
        ROOT / "scripts" / "verify_seasonal_regions.py")
    vsr = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vsr)
    return vsr


def test_transports_path_points_at_submodule():
    import os
    vsr = load_vsr()
    expected = os.path.join(
        vsr.REPO,
        "shortest-path/src/main/resources/transports/"
        "seasonal_transports.tsv")
    assert vsr.TRANSPORTS == expected
    assert ".." not in vsr.TRANSPORTS


def test_transports_path_resolves_inside_submodule():
    import os
    vsr = load_vsr()
    sub = os.path.realpath(os.path.join(vsr.REPO, "shortest-path"))
    assert os.path.realpath(vsr.TRANSPORTS).startswith(sub + os.sep)


def test_vsr_codes_match_only_as_whole_words():
    vsr = load_vsr()
    # Fairy-ring codes match only as whole words — a destination name
    # that merely contains the letters must not claim the code's
    # region.
    assert vsr.expected_region(
        "Fairy Mushroom: Morytania - CKS (Canifis)",
        vsr.MUSHROOM) == "MORYTANIA"
    assert vsr.expected_region(
        "Fairy Mushroom: Nowhere - Blocksdale", vsr.MUSHROOM) is None
    assert vsr.expected_region(
        "Fairy Mushroom: Nowhere - Distant Shore",
        vsr.MUSHROOM) is None
    # Named destinations still match as substrings.
    assert vsr.expected_region(
        "Fairy Mushroom: Asgarnia - Port Sarim (planted)",
        vsr.MUSHROOM) == "ASGARNIA"


def test_vsr_load_bboxes_missing_file_exits(tmp_path):
    vsr = load_vsr()
    with pytest.raises(SystemExit) as exc:
        vsr.load_bboxes(tmp_path / "missing.tsv")
    assert "cannot read" in str(exc.value)


def test_vsr_load_bboxes_malformed_line_exits(tmp_path):
    vsr = load_vsr()
    bad = tmp_path / "bboxes.tsv"
    bad.write_text("VARLAMORE\t1\t2\tx\t4\n")
    with pytest.raises(SystemExit) as exc:
        vsr.load_bboxes(bad)
    assert "malformed bbox line" in str(exc.value)


def test_seasonal_invokes_script(tmp_path, monkeypatch):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append((list(cmd), cwd, timeout))
        return cp(cmd, "Summary: 0 Alacrity\n", rc=2)

    monkeypatch.setattr(mm, "run", fake_run)
    rc = mm.main(["seasonal"])
    assert rc == 2
    assert calls == [([sys.executable,
                      str(repo / "scripts" /
                          "verify_seasonal_regions.py")],
                      repo, mm.SCRIPT_TIMEOUT_SECONDS)]


def test_seasonal_does_not_require_branch(tmp_path, monkeypatch):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    calls = []

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append(list(cmd))
        # Even if asked, the submodule is on master — a read-only check
        # must not consult branch state at all.
        if cmd[:3] == ["git", "-C", "shortest-path"]:
            return cp(cmd, "master")
        return cp(cmd, "Summary: 0 Alacrity\n")

    monkeypatch.setattr(mm, "run", fake_run)
    rc = mm.main(["seasonal"])
    assert rc == 0
    assert not any(c[0] == "git" for c in calls)


# ---------- refresh chain + probes ----------


def make_gate_run(calls, *, branch="maint-x", upstream="origin/maint-x",
                  upstream_rc=0, status_out=""):
    """fake mm.run answering only the write-gate git probes; any other
    argv fails the test."""
    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append(list(cmd))
        kind = write_gate_kind(cmd)
        if kind == "branch":
            return cp(cmd, branch)
        if kind == "upstream":
            return cp(cmd, upstream if upstream_rc == 0 else "",
                      "" if upstream_rc == 0 else "no upstream",
                      rc=upstream_rc)
        if kind == "status":
            return cp(cmd, status_out)
        raise AssertionError(f"unexpected argv: {cmd}")

    return fake_run


def install_refresh_spies(monkeypatch, results=None):
    """Replace every refresh step with a call-recording spy that
    mirrors the callee's real arity — do_cache takes zero parameters,
    the rest take the args namespace — so a call site with the wrong
    signature fails instead of being masked.  Returns (events,
    captured_args)."""
    events = []
    captured = {}
    results = results or {}

    def spy(name):
        def one_arg(a):
            events.append(name)
            captured[name] = a
            return results.get(name, 0)
        return one_arg

    monkeypatch.setattr(mm, "do_collision_map", spy("collision"))
    monkeypatch.setattr(mm, "do_collision_map_local",
                        spy("collision-local"))

    def cache_spy():
        events.append("cache")
        return results.get("cache", 0)

    monkeypatch.setattr(mm, "do_cache", cache_spy)
    monkeypatch.setattr(mm, "do_regions", spy("regions"))
    monkeypatch.setattr(mm, "do_bank", spy("bank"))
    monkeypatch.setattr(mm, "do_seasonal", spy("seasonal"))
    return events, captured


def prepare_refresh(tmp_path, monkeypatch, results=None, **gate_kwargs):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    events, captured = install_refresh_spies(monkeypatch, results)
    calls = []
    monkeypatch.setattr(mm, "run", make_gate_run(calls, **gate_kwargs))
    return events, captured, calls


def test_refresh_order(tmp_path, monkeypatch):
    events, _, _ = prepare_refresh(tmp_path, monkeypatch)
    rc = mm.main(["refresh"])
    assert rc == 0
    assert events == ["collision", "cache", "regions", "bank",
                      "seasonal"]


def test_refresh_args_cover_step_attrs(tmp_path, monkeypatch):
    _, captured, _ = prepare_refresh(tmp_path, monkeypatch)
    rc = mm.main(["refresh"])
    assert rc == 0
    a = captured["collision"]
    for attr in ("commit", "local", "f2p", "skip_collision"):
        assert hasattr(a, attr), f"refresh args missing '{attr}'"


def test_refresh_local_forwarded(tmp_path, monkeypatch):
    events, _, _ = prepare_refresh(tmp_path, monkeypatch)
    rc = mm.main(["refresh", "--local"])
    assert rc == 0
    assert events[0] == "collision-local"
    assert "collision" not in events


def test_refresh_aborts_on_first_failure(tmp_path, monkeypatch, capsys):
    events, _, _ = prepare_refresh(
        tmp_path, monkeypatch, {"regions": 1})
    rc = mm.main(["refresh"])
    assert rc == 1
    assert events == ["collision", "cache", "regions"]
    assert "regions" in capsys.readouterr().err


def test_refresh_skip_collision(tmp_path, monkeypatch):
    events, _, _ = prepare_refresh(tmp_path, monkeypatch)
    rc = mm.main(["refresh", "--skip-collision"])
    assert rc == 0
    assert events == ["cache", "regions", "bank", "seasonal"]


def test_refresh_requires_write_branch_up_front(tmp_path, monkeypatch):
    events, _, _ = prepare_refresh(
        tmp_path, monkeypatch, branch="master")
    rc = mm.main(["refresh"])
    assert rc == 1
    assert events == []


def make_probe_run(calls, *, fail=()):
    """fake mm.run for the probes subcommand — records each gradle
    argv; tasks named in ``fail`` exit 1."""
    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append((list(cmd), cwd, timeout))
        assert cmd[0] == "./gradlew", f"non-gradle argv: {cmd}"
        rc = 1 if cmd[1] in fail else 0
        return cp(cmd, f"{cmd[1]} output\n", rc=rc)

    return fake_run


def prepare_probes_repo(tmp_path, monkeypatch, *, cache_ready=True,
                        **run_kwargs):
    repo, _ = redirect_repo(tmp_path, monkeypatch)
    if cache_ready:
        (repo / "cache").mkdir()
        (repo / "keys.json").write_text(
            (FIXTURES / "keys_patched.json").read_text())
    calls = []
    monkeypatch.setattr(mm, "run", make_probe_run(calls, **run_kwargs))
    return repo, calls


def test_probes_default_sequence(tmp_path, monkeypatch):
    repo, calls = prepare_probes_repo(tmp_path, monkeypatch)
    rc = mm.main(["probes"])
    assert rc == 0
    expected = [
        ("leagueIdProbe", "leagueId", "leagueId"),
        ("leagueTeleportItemDump", "leagueTeleportItem",
         "leagueTeleportItem"),
        ("leagueAreaStructDump", "leagueAreaStruct", "leagueAreaStruct"),
        ("leagueScriptScan", "leagueScript", "leagueScript"),
        ("briefcaseEnumProbe", "briefcaseEnum", "briefcaseEnum"),
        ("briefcaseParamScriptScan", "briefcaseParam",
         "briefcaseParam"),
        ("briefcaseTeleportTables", "briefcaseTt", None),
        ("sailingAmenityVarbitDump", "sailingAmenity",
         "sailingAmenity"),
    ]
    built = []
    for task, cache_prop, xtea_prop in expected:
        argv = ["./gradlew", task,
                f"-P{cache_prop}CacheDir={repo / 'cache'}"]
        if xtea_prop:
            argv.append(f"-P{xtea_prop}XteaPath={repo / 'keys.json'}")
        built.append(argv)
    assert [c for c, _, _ in calls] == built
    for _, cwd, timeout in calls:
        assert cwd == repo
        assert timeout == mm.GRADLE_TIMEOUT_SECONDS


def test_probes_names_file_unlocks_scans(tmp_path, monkeypatch):
    repo, calls = prepare_probes_repo(tmp_path, monkeypatch)
    names = repo / "names.txt"
    names.write_text("Varrock\n")
    rc = mm.main(["probes", "--names-file", str(names)])
    assert rc == 0
    argv = [c for c, _, _ in calls]
    assert len(argv) == 11
    assert argv[8:] == [
        ["./gradlew", "briefcaseDestOverlapScan",
         f"-PbriefcaseDestCacheDir={repo / 'cache'}",
         f"-PbriefcaseDestXteaPath={repo / 'keys.json'}",
         f"-PbriefcaseDestNamesFile={names}"],
        ["./gradlew", "briefcaseStructHunt",
         f"-PbriefcaseStructCacheDir={repo / 'cache'}",
         f"-PbriefcaseStructNamesFile={names}"],
        ["./gradlew", "briefcaseDbRowScan",
         f"-PbriefcaseDbCacheDir={repo / 'cache'}",
         f"-PbriefcaseDbNamesFile={names}"],
    ]


def test_probes_names_file_resolved_for_gradle(tmp_path, monkeypatch):
    # A relative --names-file resolves against the caller's CWD for the
    # existence check; Gradle must get the absolute path, not a string
    # it would re-resolve against the project dir.
    repo, calls = prepare_probes_repo(tmp_path, monkeypatch)
    names = repo / "names.txt"
    names.write_text("Varrock\n")
    monkeypatch.chdir(repo)
    rc = mm.main(["probes", "--names-file", "names.txt"])
    assert rc == 0
    for c, _, _ in calls:
        for a in c:
            if "NamesFile=" in a:
                assert a.endswith(str(names))


def test_probes_names_file_must_exist(tmp_path, monkeypatch):
    repo, calls = prepare_probes_repo(tmp_path, monkeypatch)
    rc = mm.main(["probes", "--names-file",
                  str(repo / "missing-names.txt")])
    assert rc == 1
    assert calls == []


def test_probes_missing_cache_refuses(tmp_path, monkeypatch, capsys):
    repo, calls = prepare_probes_repo(
        tmp_path, monkeypatch, cache_ready=False)
    rc = mm.main(["probes"])
    assert rc == 1
    assert calls == []
    assert "maintenance.py cache" in capsys.readouterr().err


def test_probes_failure_continues_and_reports(tmp_path, monkeypatch,
                                              capsys):
    repo, calls = prepare_probes_repo(
        tmp_path, monkeypatch, fail={"leagueScriptScan"})
    rc = mm.main(["probes"])
    assert rc == 1
    # One failing probe must not mask the remaining output.
    assert len(calls) == 8
    result = capsys.readouterr()
    assert "sailingAmenityVarbitDump output" in result.out
    assert "leagueScriptScan" in result.err


# ---------- verify subcommand ----------


DASHBOARD_CSVS = [
    "clue_locations_full.csv",
    "collision-map-issues.csv",
    "f2p_routes.csv",
    "quetzal_whistle_routes.csv",
    "routes.csv",
    "seasonal_briefcase_routes.csv",
    "unit-tests.csv",
]


def make_run_record(name="alpha scenario", reached=True,
                    assertion_passed=True, assertion_message=None):
    """A report.json run record in the DashboardBundlePublisher
    shape."""
    return {"name": name, "reached": reached, "pathLength": 12,
            "assertionPassed": assertion_passed,
            "assertionMessage": assertion_message,
            "stats": {"elapsedNanos": 123456}}


def write_report(path, runs):
    """Write a report.json with the given run records."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"runs": runs}))
    return path


def test_scan_report_flags_unreachable_and_failed_assertion(tmp_path):
    report = write_report(tmp_path / "report.json", [
        make_run_record("gone", reached=False),
        make_run_record("bad", assertion_passed=False,
                        assertion_message="expected 10 got 12"),
        make_run_record("fine"),
    ])
    failures = mm.scan_report(report)
    assert "gone: unreachable" in failures
    assert "bad: expected 10 got 12" in failures
    assert len(failures) == 2


def test_scan_report_missing_or_malformed_fails_closed(tmp_path):
    missing = tmp_path / "nope" / "report.json"
    failures = mm.scan_report(missing)
    assert failures and str(missing) in failures[0]
    bad = tmp_path / "report.json"
    bad.write_text('{"runs": [')
    failures = mm.scan_report(bad)
    assert failures and str(bad) in failures[0]


def test_scan_report_wrong_shape_fails_closed(tmp_path):
    # Syntactically valid JSON of the wrong shape must not crash the
    # verify run — it fails the dashboard tier closed instead.
    bad = tmp_path / "report.json"
    for payload in ("[]", '"text"', "42", '{"runs": "oops"}',
                    '{"runs": [42]}'):
        bad.write_text(payload)
        failures = mm.scan_report(bad)
        assert failures and str(bad) in failures[0], payload


def make_verify_run(repo, calls, *, datasets=None, compile_rc=0,
                    lint_rc=0, runs_for=None, missing_reports=(),
                    ls_tree_rc=0,
                    gitlink="160000 commit deadbeefcafe\tshortest-path",
                    show_bytes=b"OLDZIP", show_rc=0,
                    diff_stdout="EDGE TOTALS\n", diff_rc=0):
    """fake mm.run for the verify subcommand — answers git ls-files /
    ls-tree / show, every gradle tier, and the compare script; writes a
    report.json bundle under build/ for each dashboard run."""
    if datasets is None:
        datasets = DASHBOARD_CSVS

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append((list(cmd), cwd))
        if cmd[:2] == ["git", "ls-files"]:
            return cp(cmd, "".join(
                f"src/test/resources/dashboard/{d}\n"
                for d in datasets))
        if cmd[:3] == ["git", "ls-tree", "HEAD"]:
            return cp(cmd, gitlink, rc=ls_tree_rc)
        if cmd[:4] == ["git", "-C", "shortest-path", "show"]:
            assert binary is True, "git show of a zip must be binary"
            return subprocess.CompletedProcess(
                cmd, show_rc, show_bytes, b"")
        if cmd[:2] == ["./gradlew", "dashboard"]:
            dataset = next(a.split("=", 1)[1] for a in cmd
                           if a.startswith("-PdashboardDataset="))
            csv = dataset.rsplit("/", 1)[-1]
            slug = Path(csv).stem.lower().replace("_", "-")
            runs = (runs_for(csv) if runs_for
                    else [make_run_record(csv)])
            if csv not in missing_reports:
                write_report(
                    repo / "build" / "reports" /
                    "pathfinder-dashboard" / "bundles" / slug /
                    "report.json", runs)
            return cp(cmd, f"dashboard {csv}\n")
        if cmd[:2] == ["./gradlew", "compileTestJava"]:
            return cp(cmd, rc=compile_rc)
        if cmd[:4] == ["./gradlew", "-p", "shortest-path", "test"]:
            return cp(cmd, rc=lint_rc)
        if cmd[0] == sys.executable:
            return cp(cmd, diff_stdout, rc=diff_rc)
        raise AssertionError(f"unexpected argv: {cmd}")

    return fake_run


def prepare_verify(tmp_path, monkeypatch, *, new_zip=b"OLDZIP",
                   **run_kwargs):
    """Common verify setup: redirected repo + the submodule's worktree
    collision-map.zip.  Returns (repo, submodule, calls)."""
    repo, submodule = redirect_repo(tmp_path, monkeypatch)
    resources = submodule / "src" / "main" / "resources"
    resources.mkdir(parents=True)
    if new_zip is not None:
        (resources / "collision-map.zip").write_bytes(new_zip)
    calls = []
    monkeypatch.setattr(
        mm, "run", make_verify_run(repo, calls, **run_kwargs))
    return repo, submodule, calls


def verify_kind(cmd):
    """Bucket a recorded argv into the verify tier it belongs to."""
    if cmd[:2] == ["./gradlew", "dashboard"]:
        return "dashboard"
    if cmd[:2] == ["./gradlew", "compileTestJava"]:
        return "compile"
    if cmd[:4] == ["./gradlew", "-p", "shortest-path", "test"]:
        return "lint"
    if cmd[0] == sys.executable:
        return "compare"
    return "git"


def test_verify_derives_datasets_from_git_ls_files(tmp_path,
                                                   monkeypatch):
    repo, _, calls = prepare_verify(tmp_path, monkeypatch)
    rc = mm.main(["verify"])
    assert rc == 0
    dashboards = [c for c, _ in calls
                  if c[:2] == ["./gradlew", "dashboard"]]
    assert len(dashboards) == 7
    for cmd in dashboards:
        assert "-PdashboardProfile=false" in cmd
    swept = sorted(
        next(a for a in c if a.startswith("-PdashboardDataset="))
        .split("=", 1)[1] for c in dashboards)
    assert swept == sorted(
        f"/dashboard/{name}" for name in DASHBOARD_CSVS)
    # The gitignored scratch dataset can never enter the sweep — the
    # list comes from git ls-files, not a directory glob.
    assert not any("debug.csv" in a for c in dashboards for a in c)


def test_verify_datasets_ignore_non_csv(tmp_path, monkeypatch):
    # A committed README/.gitignore under dashboard/ is not a dataset.
    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch,
        datasets=["routes.csv", "README.md", ".gitignore"])
    rc = mm.main(["verify"])
    assert rc == 0
    dashboards = [c for c, _ in calls
                  if c[:2] == ["./gradlew", "dashboard"]]
    assert len(dashboards) == 1
    assert "-PdashboardDataset=/dashboard/routes.csv" in dashboards[0]


def test_verify_overlay_flags(tmp_path, monkeypatch):
    repo, _, calls = prepare_verify(tmp_path, monkeypatch)
    rc = mm.main(["verify"])
    assert rc == 0
    by_dataset = {}
    for cmd, _ in calls:
        if cmd[:2] != ["./gradlew", "dashboard"]:
            continue
        name = next(a for a in cmd
                    if a.startswith("-PdashboardDataset=")).rsplit(
                        "/", 1)[-1]
        by_dataset[name] = cmd
    assert "-PdashboardSeasonal=true" in \
        by_dataset["seasonal_briefcase_routes.csv"]
    assert "-PdashboardF2p=true" in by_dataset["f2p_routes.csv"]
    assert not any("dashboardSeasonal" in a
                   for a in by_dataset["routes.csv"])
    assert not any("dashboardF2p" in a
                   for a in by_dataset["routes.csv"])


def test_verify_compile_failure_marks_tier_but_continues(
        tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_verify(tmp_path, monkeypatch,
                                    compile_rc=1)
    rc = mm.main(["verify"])
    assert rc == 1
    out = capsys.readouterr().out
    assert "FAIL compile" in out
    # A red tier must not hide the rest — every later tier still ran.
    assert any(c[:4] == ["./gradlew", "-p", "shortest-path", "test"]
               for c, _ in calls)
    assert any(c[:2] == ["./gradlew", "dashboard"] for c, _ in calls)
    assert any(c[:3] == ["git", "ls-tree", "HEAD"] for c, _ in calls)


def test_verify_missing_report_fails_tier(tmp_path, monkeypatch,
                                          capsys):
    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch, missing_reports=DASHBOARD_CSVS)
    rc = mm.main(["verify"])
    assert rc == 1
    assert "FAIL dashboard" in capsys.readouterr().out


def test_verify_dashboard_tier_reads_bundles(tmp_path, monkeypatch,
                                             capsys):
    # The publisher has always written
    # build/reports/pathfinder-dashboard/bundles/{slug}/report.json —
    # a clean report at the real location must pass the tier.
    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch, datasets=["routes.csv"])
    rc = mm.main(["verify"])
    assert rc == 0
    assert "PASS dashboard" in capsys.readouterr().out


def test_verify_dashboard_tier_still_fails_closed(tmp_path, monkeypatch,
                                                  capsys):
    # A reachable-looking path fix must not soften the tier: an
    # unreached run still names itself, and an absent report still
    # fails closed on the path.
    def runs_for(csv):
        return [make_run_record("no path", reached=False)]

    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch, datasets=["routes.csv"],
        runs_for=runs_for)
    rc = mm.main(["verify"])
    assert rc == 1
    out = capsys.readouterr().out
    assert "FAIL dashboard" in out
    assert "no path: unreachable" in out

    repo, _, calls = prepare_verify(
        tmp_path / "missing", monkeypatch, datasets=["routes.csv"],
        missing_reports=["routes.csv"])
    rc = mm.main(["verify"])
    assert rc == 1
    assert "missing or unreadable report" in capsys.readouterr().out


def test_verify_scan_path_contains_bundles(tmp_path, monkeypatch):
    # Regression: the path do_verify hands scan_report must keep the
    # bundles/ segment the publisher always writes — a future layout
    # edit cannot silently re-break the tier.
    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch, datasets=["routes.csv"])
    seen = []
    real = mm.scan_report

    def spy(path):
        seen.append(Path(path))
        return real(path)

    monkeypatch.setattr(mm, "scan_report", spy)
    mm.main(["verify"])
    assert len(seen) == 1
    assert "bundles" in seen[0].parts


def test_verify_edge_diff_identity_passes(tmp_path, monkeypatch,
                                          capsys):
    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch, show_bytes=b"OLDZIP", new_zip=b"OLDZIP")
    rc = mm.main(["verify"])
    assert rc == 0
    out = capsys.readouterr().out
    assert "no edge changes" in out
    # Identical zips pass without invoking the compare script.
    assert not any(c[0] == sys.executable for c, _ in calls)


def test_verify_edge_diff_override_args(tmp_path, monkeypatch):
    repo, _, calls = prepare_verify(tmp_path, monkeypatch)
    a = tmp_path / "a.zip"
    b = tmp_path / "b.zip"
    a.write_bytes(b"AAAA")
    b.write_bytes(b"BBBB")
    rc = mm.main(["verify", "--old-zip", str(a),
                  "--new-zip", str(b)])
    assert rc == 0
    # Overrides skip the gitlink baseline derivation entirely.
    assert not any(c[:3] == ["git", "ls-tree", "HEAD"]
                   for c, _ in calls)
    assert not any(c[:4] == ["git", "-C", "shortest-path", "show"]
                   for c, _ in calls)
    compare = next(c for c, _ in calls
                   if c[0] == sys.executable)
    assert compare[1].endswith("compare_collision_maps.py")
    assert compare[2] == str(a)
    assert compare[3] == str(b)


def test_verify_gitlink_must_be_a_commit(tmp_path, monkeypatch,
                                         capsys):
    # If shortest-path were ever tracked as a plain tree, the gitlink
    # SHA would be a tree — report the real problem instead of a
    # confusing "could not extract" from git show.
    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch,
        gitlink="040000 tree deadbeefcafe\tshortest-path")
    rc = mm.main(["verify"])
    assert rc == 1
    out = capsys.readouterr().out
    assert "FAIL diff" in out
    assert "gitlink" in out
    assert not any(c[:4] == ["git", "-C", "shortest-path", "show"]
                   for c, _ in calls)


def test_verify_zip_args_both_or_neither(tmp_path, monkeypatch):
    repo, _, calls = prepare_verify(tmp_path, monkeypatch)
    a = tmp_path / "a.zip"
    a.write_bytes(b"AAAA")
    with pytest.raises(SystemExit) as exc:
        mm.main(["verify", "--old-zip", str(a)])
    assert exc.value.code == 2
    # argparse rejected the half-pair before any tier ran.
    assert calls == []


def test_verify_skip_flags(tmp_path, monkeypatch):
    repo, _, calls = prepare_verify(tmp_path / "a", monkeypatch)
    assert mm.main(["verify", "--skip-dashboard"]) == 0
    assert not any(c[:2] == ["./gradlew", "dashboard"]
                   for c, _ in calls)

    repo, _, calls = prepare_verify(tmp_path / "b", monkeypatch)
    assert mm.main(["verify", "--skip-compile"]) == 0
    assert not any(c[:2] == ["./gradlew", "compileTestJava"]
                   for c, _ in calls)

    repo, _, calls = prepare_verify(tmp_path / "c", monkeypatch)
    assert mm.main(["verify", "--skip-lint"]) == 0
    assert not any(c[:4] == ["./gradlew", "-p", "shortest-path",
                             "test"] for c, _ in calls)

    repo, _, calls = prepare_verify(tmp_path / "d", monkeypatch)
    assert mm.main(["verify", "--skip-diff"]) == 0
    assert not any(c[:3] == ["git", "ls-tree", "HEAD"]
                   for c, _ in calls)
    assert not any(c[0] == sys.executable for c, _ in calls)


def test_verify_returns_nonzero_on_any_tier_failure(
        tmp_path, monkeypatch, capsys):
    def runs_for(csv):
        if csv == "routes.csv":
            return [make_run_record("broken route", reached=False)]
        return [make_run_record(csv)]

    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch, runs_for=runs_for)
    rc = mm.main(["verify"])
    assert rc == 1
    out = capsys.readouterr().out
    assert "FAIL dashboard" in out
    assert "broken route: unreachable" in out
    assert "tiers passed" in out


def test_verify_all_pass_summary(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_verify(
        tmp_path, monkeypatch, show_bytes=b"OLDZIP", new_zip=b"OLDZIP")
    rc = mm.main(["verify"])
    assert rc == 0
    assert "verify: 4/4 tiers passed" in capsys.readouterr().out


def test_verify_summary_counts_only_ran_tiers(tmp_path, monkeypatch,
                                              capsys):
    repo, _, calls = prepare_verify(tmp_path, monkeypatch)
    rc = mm.main(["verify", "--skip-dashboard"])
    assert rc == 0
    # A skipped tier shrinks the denominator — 3/3 on a green run.
    assert "verify: 3/3 tiers passed" in capsys.readouterr().out


# ---------- validate subcommand ----------


def load_vd():
    """Load scripts/validate_data.py as a module."""
    spec = importlib.util.spec_from_file_location(
        "validate_data", ROOT / "scripts" / "validate_data.py")
    vd = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(vd)
    return vd


def make_validate_run(repo, calls, *, check_rc=None, check_stdout=None,
                      fetch_rc=0, fetch_stderr="",
                      caches=None, curl_rc=0, curl_stdout=None,
                      auto_commit="2026-09-10T01:18:05Z",
                      auto_commit_rc=0,
                      ahead="", ahead_rc=0,
                      gitlink="160000 commit deadbeefcafe\tshortest-path",
                      ls_tree_rc=0,
                      drift_rc=0, drift_stdout="drift output\n",
                      probe_rc=0,
                      seasonal_stdout="Summary: 0 Alacrity\n",
                      seasonal_rc=0):
    """fake mm.run for the validate subcommand — answers the leaf
    checks, the freshness git/curl probes, the drift Gradle task, and
    the season leaf + league probes, all keyed by argv content.
    Defaults produce the happy path: a live-cache timestamp behind the
    last upstream auto-commit and no collision commits ahead of the
    pin."""
    check_rc = check_rc or {}
    check_stdout = check_stdout or {}
    if caches is None:
        caches = [{"game": "oldschool", "environment": "live",
                   "id": 2695, "timestamp": "2026-09-08T10:30:08Z"}]

    def fake_run(cmd, *, cwd=None, timeout=None, binary=False):
        calls.append((list(cmd), cwd))
        if (cmd[0] == sys.executable and len(cmd) >= 3
                and cmd[1].endswith("validate_data.py")):
            name = cmd[2]
            return cp(cmd, check_stdout.get(name, ""),
                      rc=check_rc.get(name, 0))
        if (cmd[0] == sys.executable and len(cmd) >= 2
                and cmd[1].endswith("verify_seasonal_regions.py")):
            return cp(cmd, seasonal_stdout, rc=seasonal_rc)
        if cmd[:4] == ["git", "-C", "shortest-path", "fetch"]:
            return cp(cmd, stderr=fetch_stderr, rc=fetch_rc)
        if cmd[:3] == ["git", "ls-tree", "HEAD"]:
            return cp(cmd, gitlink, rc=ls_tree_rc)
        if cmd[:4] == ["git", "-C", "shortest-path", "log"]:
            if any(a.endswith("..upstream/master") for a in cmd):
                return cp(cmd, ahead, rc=ahead_rc)
            return cp(cmd, auto_commit, rc=auto_commit_rc)
        if cmd[:2] == ["curl", "-s"]:
            out = (json.dumps(caches) if curl_stdout is None
                   else curl_stdout)
            return cp(cmd, out, rc=curl_rc)
        if cmd[:2] == ["./gradlew", "transportAnchorDrift"]:
            return cp(cmd, drift_stdout, rc=drift_rc)
        if cmd[:2] == ["./gradlew", "leagueAreaStructDump"]:
            return cp(cmd, "area struct dump\n", rc=probe_rc)
        if cmd[:2] == ["./gradlew", "leagueScriptScan"]:
            return cp(cmd, "script scan\n", rc=probe_rc)
        raise AssertionError(f"unexpected argv: {cmd}")

    return fake_run


def prepare_validate(tmp_path, monkeypatch, *, marker=False,
                     cache_ready=False, **run_kwargs):
    """Common validate setup: redirected repo + recording fake run.
    Returns (repo, submodule, calls)."""
    repo, submodule = redirect_repo(tmp_path, monkeypatch)
    # The marker constant binds at module load to the real repo —
    # repoint it at the scratch tree so the committed file can never
    # leak into tests.
    marker_path = repo / "src" / "test" / "resources" / "season_active"
    monkeypatch.setattr(mm, "SEASON_MARKER", marker_path,
                        raising=False)
    if marker:
        marker_path.parent.mkdir(parents=True, exist_ok=True)
        marker_path.write_text("# gates the season tier\n"
                               "Demonic Pacts\n")
    if cache_ready:
        (repo / "cache").mkdir()
        (repo / "keys.json").write_text(
            (FIXTURES / "keys_patched.json").read_text())
    calls = []
    monkeypatch.setattr(
        mm, "run", make_validate_run(repo, calls, **run_kwargs))
    return repo, submodule, calls


def validate_kind(cmd):
    """Bucket a recorded argv into the validate step it runs."""
    if (cmd[0] == sys.executable and len(cmd) >= 3
            and cmd[1].endswith("validate_data.py")):
        return cmd[2]
    if (cmd[0] == sys.executable and len(cmd) >= 2
            and cmd[1].endswith("verify_seasonal_regions.py")):
        return "seasonal-leaf"
    if cmd[:4] == ["git", "-C", "shortest-path", "fetch"]:
        return "fetch"
    if cmd[:3] == ["git", "ls-tree", "HEAD"]:
        return "ls-tree"
    if cmd[:4] == ["git", "-C", "shortest-path", "log"]:
        return "log-pin" if any(
            a.endswith("..upstream/master") for a in cmd) \
            else "log-auto"
    if cmd[:2] == ["curl", "-s"]:
        return "curl"
    if cmd[0] == "./gradlew":
        return f"gradle:{cmd[1]}"
    return "other"


def leaf_names(calls):
    """The validate_data.py leaf checks a recorded run invoked."""
    return [c[2] for c, _ in calls
            if c[0] == sys.executable and len(c) >= 3
            and c[1].endswith("validate_data.py")]


def hard_names():
    return [n for n, _ in mm.VALIDATE_HARD_CHECKS]


def advisory_names():
    return [n for n, _ in mm.VALIDATE_ADVISORY_CHECKS]


def hard_leaf_names():
    return [n for n, k in mm.VALIDATE_HARD_CHECKS if k == "leaf"]


def advisory_leaf_names():
    return [n for n, k in mm.VALIDATE_ADVISORY_CHECKS if k == "leaf"]


def test_validate_registered_and_dispatches(tmp_path, monkeypatch,
                                            capsys):
    prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate"])
    assert rc == 0
    assert "checks passed" in capsys.readouterr().out
    with pytest.raises(SystemExit) as exc:
        mm.main(["--help"])
    assert exc.value.code == 0
    assert "validate" in capsys.readouterr().out


def test_validate_leaf_invocation_per_check(tmp_path, monkeypatch):
    repo, _, calls = prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate"])
    assert rc == 0
    names = leaf_names(calls)
    expected = hard_leaf_names() + advisory_leaf_names()
    assert names == expected
    for cmd, cwd in calls:
        if (cmd[0] == sys.executable and len(cmd) >= 3
                and cmd[1].endswith("validate_data.py")):
            assert cwd == repo


def test_validate_hard_failure_gates_rc(tmp_path, monkeypatch, capsys):
    _, _, calls = prepare_validate(
        tmp_path, monkeypatch,
        check_rc={"tsv-structure": 1},
        check_stdout={"tsv-structure": "FAIL x.tsv:3: bad row\n"})
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 1
    assert "FAIL tsv-structure" in out
    # A failed leaf must not stop later checks — every registered
    # leaf check is still invoked exactly once.
    assert leaf_names(calls) == \
        hard_leaf_names() + advisory_leaf_names()


def test_validate_advisory_never_gates(tmp_path, monkeypatch, capsys):
    prepare_validate(
        tmp_path, monkeypatch,
        check_stdout={"destinations":
                      "=== Destination walkability (2 findings) ===\n"
                      "  a.tsv:1 1 2 3 Bank\n"})
    assert "destinations" in advisory_names()
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "ADVISORY destinations" in out
    # Advisory checks are excluded from both the numerator and the
    # denominator of the summary.
    n = len(mm.VALIDATE_HARD_CHECKS)
    assert f"validate: {n}/{n} checks passed" in out


def test_validate_destinations_skip_flag(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate", "--skip-destinations"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "SKIP destinations" in out
    names = leaf_names(calls)
    assert "destinations" not in names
    # Hard leaf checks are unaffected by the advisory skip flag.
    assert sorted(names) == sorted(hard_leaf_names())


def test_validate_destinations_advisory_rc_ignored(tmp_path,
                                                 monkeypatch, capsys):
    # Even a nonzero leaf rc on an advisory check cannot move the
    # overall exit code.
    prepare_validate(tmp_path, monkeypatch,
                     check_rc={"destinations": 1})
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "ADVISORY destinations" in out


def test_validate_skip_flags(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate", "--skip-tsv-structure"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "SKIP tsv-structure" in out
    # The skipped check's leaf call is the only one removed — every
    # other registered leaf check still ran exactly once.
    names = leaf_names(calls)
    assert "tsv-structure" not in names
    assert sorted(names) == sorted(
        n for n in (hard_leaf_names() + advisory_leaf_names())
        if n != "tsv-structure")
    # The denominator counts only ran hard checks.
    n = len(mm.VALIDATE_HARD_CHECKS) - 1
    assert f"validate: {n}/{n} checks passed" in out


def test_validate_summary_line(tmp_path, monkeypatch, capsys):
    prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "PASS tsv-structure" in out
    n = len(mm.VALIDATE_HARD_CHECKS)
    assert f"validate: {n}/{n} checks passed" in out


def test_validate_scenario_csv_skip_flag(tmp_path, monkeypatch,
                                         capsys):
    _, _, calls = prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate", "--skip-scenario-csv"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "SKIP scenario-csv" in out
    names = leaf_names(calls)
    assert "scenario-csv" not in names
    # The skipped check's leaf call is the only one removed.
    assert sorted(names) == sorted(
        n for n in (hard_leaf_names() + advisory_leaf_names())
        if n != "scenario-csv")
    n = len(mm.VALIDATE_HARD_CHECKS) - 1
    assert f"validate: {n}/{n} checks passed" in out


def test_validate_region_override_skip_flag(tmp_path, monkeypatch,
                                            capsys):
    _, _, calls = prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate", "--skip-region-override"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "SKIP region-override" in out
    names = leaf_names(calls)
    assert "region-override" not in names
    assert sorted(names) == sorted(
        n for n in (hard_leaf_names() + advisory_leaf_names())
        if n != "region-override")
    n = len(mm.VALIDATE_HARD_CHECKS) - 1
    assert f"validate: {n}/{n} checks passed" in out


def _write_tsv(root, rel, header_cells, rows):
    """Write a fixture TSV: ``#``-prefixed header + verbatim rows."""
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# " + "\t".join(header_cells)] + rows
    path.write_text("\n".join(lines) + "\n")
    return rel


def test_validate_data_structure_check(tmp_path, monkeypatch, capsys):
    vd = load_vd()
    plugin = tmp_path / "shortest-path"
    rel = "src/main/resources/transports/transports.tsv"
    _write_tsv(plugin, rel,
               ["Origin", "Destination",
                "menuOption menuTarget objectID", "Bogus"],
               ["1 2 0\t3 4 0\tOpen Door 1\tok",
                "1 2 0",
                "not-a-coord\t3 4 0\tX",
                "# a section comment",
                "\t3 4 0\tY"])
    monkeypatch.setattr(vd, "PLUGIN", plugin)
    monkeypatch.setattr(vd, "_git_ls_files", lambda *ps: [rel])
    rc = vd.main(["tsv-structure"])
    out = capsys.readouterr().out
    assert rc == 1
    # Unknown header cell named.
    assert "Bogus" in out
    # Short data row (line 3) and malformed coordinate (line 4) are
    # file:line findings, not silent skips.
    assert f"{rel}:3" in out
    assert f"{rel}:4" in out
    # The permutation pair check fires: row 6 has a blank Origin with a
    # concrete Destination but no row has a concrete Origin with a
    # blank Destination — a dead anchor set.
    assert "permutation" in out.lower()
    assert "Summary:" in out


def test_validate_data_structure_check_clean(tmp_path, monkeypatch,
                                           capsys):
    vd = load_vd()
    plugin = tmp_path / "shortest-path"
    rel = "src/main/resources/transports/transports.tsv"
    _write_tsv(plugin, rel,
               ["Origin", "Destination",
                "menuOption menuTarget objectID"],
               ["1 2 0\t\tOpen Door 1",
                "\t3 4 0\tOpen Door 1"])
    monkeypatch.setattr(vd, "PLUGIN", plugin)
    monkeypatch.setattr(vd, "_git_ls_files", lambda *ps: [rel])
    rc = vd.main(["tsv-structure"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "Summary:" in out


# ---------- validate: freshness hard check ----------


def test_freshness_happy_path(tmp_path, monkeypatch, capsys):
    _, _, calls = prepare_validate(tmp_path, monkeypatch)
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert "PASS freshness" in out
    # Both timestamps print either way — they are the diagnostic.
    assert "2026-09-08" in out and "2026-09-10" in out
    kinds = [validate_kind(c) for c, _ in calls]
    assert "fetch" in kinds and "curl" in kinds
    assert "log-auto" in kinds and "log-pin" in kinds


def test_freshness_dead_ci(tmp_path, monkeypatch, capsys):
    # No "Update collision map" commits on upstream/master — the weekly
    # regeneration CI is dead and that must be a hard failure.
    prepare_validate(tmp_path, monkeypatch, auto_commit="")
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 1
    assert "FAIL freshness" in out
    assert "auto-commit" in out


def test_freshness_missed_bump(tmp_path, monkeypatch, capsys):
    # Latest live cache is more than a weekly cadence newer than the
    # last auto-commit — upstream ran but skipped the bump.
    prepare_validate(
        tmp_path, monkeypatch,
        caches=[{"game": "oldschool", "environment": "live",
                 "id": 1, "timestamp": "2026-09-25T00:00:00Z"}])
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 1
    assert "FAIL freshness" in out
    assert "days" in out


def test_freshness_stale_pin(tmp_path, monkeypatch, capsys):
    # Collision-map commits on upstream/master that the submodule pin
    # lacks — the gitlink needs bumping.
    prepare_validate(tmp_path, monkeypatch,
                     ahead="deadbeefcafe\nbeefcafe1234\n")
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 1
    assert "FAIL freshness" in out
    assert "behind upstream/master" in out


def test_freshness_fails_closed(tmp_path, monkeypatch, capsys):
    # Every unresolvable input is a FAIL naming what could not be
    # determined — never a silent pass.
    cases = (
        ({"fetch_rc": 128, "fetch_stderr": "fatal: unreachable"},
         "upstream"),
        ({"curl_rc": 7}, "caches.json"),
        ({"curl_stdout": "{not json"}, "caches.json"),
        ({"caches": []}, "caches.json"),
        ({"ls_tree_rc": 128}, "gitlink"),
        ({"gitlink": "040000 tree deadbeefcafe\tshortest-path"},
         "gitlink"),
    )
    for i, (kwargs, needle) in enumerate(cases):
        prepare_validate(tmp_path / str(i), monkeypatch, **kwargs)
        rc = mm.main(["validate"])
        out = capsys.readouterr().out
        assert rc == 1, kwargs
        assert "FAIL freshness" in out, kwargs
        assert needle in out, (kwargs, needle)


def test_freshness_missing_tool_is_finding(tmp_path, monkeypatch,
                                           capsys):
    prepare_validate(tmp_path, monkeypatch)

    def missing(names):
        raise SystemExit(f"missing required tools: {names[0]}")

    monkeypatch.setattr(mm, "check_tools", missing)
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 1
    assert "FAIL freshness" in out
    assert "curl" in out


# ---------- validate: drift advisory tier ----------


def test_drift_skip_by_default(tmp_path, monkeypatch, capsys):
    _, _, calls = prepare_validate(tmp_path, monkeypatch,
                                   cache_ready=True)
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert "SKIP drift" in out
    assert "--drift" in out
    assert not any(c[0] == "./gradlew" for c, _ in calls)


def test_drift_advisory(tmp_path, monkeypatch, capsys):
    repo, _, calls = prepare_validate(tmp_path, monkeypatch,
                                      cache_ready=True)
    rc = mm.main(["validate", "--drift"])
    out = capsys.readouterr().out
    argv = next(c for c, _ in calls
                if c[:2] == ["./gradlew", "transportAnchorDrift"])
    assert argv == ["./gradlew", "transportAnchorDrift",
                    f"-PtransportDriftCacheDir={repo / 'cache'}",
                    f"-PtransportDriftXteaPath={repo / 'keys.json'}"]
    assert "ADVISORY drift" in out
    assert "build/transport-drift.txt" in out


def test_drift_advisory_detector_error_never_counts(
        tmp_path, monkeypatch, capsys):
    # A failing detector still reports ADVISORY — the exit code is
    # unreachable for the drift tier.
    repo, _, calls = prepare_validate(tmp_path, monkeypatch,
                                      cache_ready=True, drift_rc=1)
    rc = mm.main(["validate", "--drift"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "ADVISORY drift" in out
    assert "detector error" in out


def test_drift_no_cache_advises_preparation(tmp_path, monkeypatch,
                                            capsys):
    _, _, calls = prepare_validate(tmp_path, monkeypatch,
                                   cache_ready=False)
    rc = mm.main(["validate", "--drift"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "ADVISORY drift" in out
    assert "maintenance.py cache" in out
    assert not any(c[0] == "./gradlew" for c, _ in calls)


# ---------- validate: season advisory tier ----------


def test_season_skip_and_gate(tmp_path, monkeypatch, capsys):
    # Ungated: no flag, no marker -> SKIP with the enablement hint.
    _, _, calls = prepare_validate(tmp_path / "a", monkeypatch)
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert "SKIP season" in out
    assert "--season-active" in out or "season_active" in out
    kinds = [validate_kind(c) for c, _ in calls]
    assert "seasonal-leaf" not in kinds

    # Flag on + cache present: wiki leaf then both league probes,
    # everything advisory.
    repo, _, calls = prepare_validate(tmp_path / "b", monkeypatch,
                                      cache_ready=True)
    rc = mm.main(["validate", "--season-active"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "ADVISORY season" in out
    kinds = [validate_kind(c) for c, _ in calls]
    assert "seasonal-leaf" in kinds
    assert "gradle:leagueAreaStructDump" in kinds
    assert "gradle:leagueScriptScan" in kinds
    assert "leagues_regions.tsv" in out

    # The committed marker gates the tier on without the flag; with no
    # cache the probes are skipped but the tier stays advisory.
    _, _, calls = prepare_validate(tmp_path / "c", monkeypatch,
                                   marker=True)
    rc = mm.main(["validate"])
    out = capsys.readouterr().out
    assert rc == 0
    assert "ADVISORY season" in out
    # The marker's season label reaches the report line.
    assert "Demonic Pacts" in out
    kinds = [validate_kind(c) for c, _ in calls]
    assert "seasonal-leaf" in kinds
    assert "gradle:leagueAreaStructDump" not in kinds
    assert "maintenance.py cache" in out


def test_season_flagless_marker_absent_never_calls_gradle(
        tmp_path, monkeypatch):
    _, _, calls = prepare_validate(tmp_path, monkeypatch,
                                   cache_ready=True)
    mm.main(["validate"])
    assert not any(c[0] == "./gradlew" for c, _ in calls)


# ---------- maintenance runbook (docs/maintenance.md) ----------


RUNBOOK = ROOT / "docs" / "maintenance.md"


def test_runbook_exists_and_names_every_subcommand():
    assert RUNBOOK.exists()
    doc = RUNBOOK.read_text()
    for sub in ("cache", "collision-map", "regions", "bank",
                "seasonal", "refresh", "probes", "verify", "validate"):
        assert sub in doc, f"runbook never mentions '{sub}'"


def test_runbook_documents_three_tiers():
    doc = RUNBOOK.read_text()
    for heading in ("Weekly refresh", "New league season",
                    "Upstream-issue-driven"):
        assert heading in doc, f"runbook lacks the '{heading}' tier"


def test_runbook_no_planning_literal():
    assert ".planning" not in RUNBOOK.read_text()


def test_runbook_states_key_rules():
    doc = RUNBOOK.read_text()
    assert "workflow_dispatch" in doc, \
        "fork-Actions workflow_dispatch-only rule undocumented"
    assert "Region override" in doc, \
        "Region override column rule undocumented"
