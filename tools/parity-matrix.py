#!/usr/bin/env python3
"""Regenerates the Status column and the coverage block of docs/parity-matrix.md from the tests.

Sources (nothing else is read):
  * src/test/java/**/*.java      @Tag("CUS-04") -> the test class carries the ID
  * target/surefire-reports and target/failsafe-reports TEST-*.xml -> did that class pass?
  * e2e/tests/*.spec.ts          "[CUS-04] …" in a title -> the spec carries the ID
  * e2e/test-results/results.json (Playwright json reporter) -> did that spec pass?

Rules (docs/parity-matrix.md header): an ID is green only when at least one real tagged test
passed in the last run; "in progress" when tagged tests exist but none passed (or no report
exists); "planned" otherwise. MIG-* stay "blocked (Q-55)". NFR-05, NFR-06 and NFR-07 reach
"ready-for-owner" at most: they are marked so when their deliverables exist (deploy/, runbooks)
and become green only with a dated rehearsal record under docs/rehearsals/.

Usage: tools/parity-matrix.py            (after ./mvnw verify and e2e/run.sh)
       tools/parity-matrix.py --check    (exit 1 when the file would change: CI guard)
"""

from __future__ import annotations

import json
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MATRIX = ROOT / "docs" / "parity-matrix.md"
ID_RE = re.compile(r"\b((?:PUB|TRK|API|CUS|ADM|EML|MIG|NFR|ENH)-\d{2})\b")
TAG_RE = re.compile(r'@Tag\("((?:PUB|TRK|API|CUS|ADM|EML|MIG|NFR|ENH)-\d{2})"\)')
TITLE_RE = re.compile(r"\[((?:PUB|TRK|API|CUS|ADM|EML|MIG|NFR|ENH)-\d{2})\]")
ROW_RE = re.compile(r"^\| ((?:PUB|TRK|API|CUS|ADM|EML|MIG|NFR|ENH)-\d{2}) \| (M[12]) \| ([^|]*) \| ([^|]*) \|")
START, END = "<!-- generated:start -->", "<!-- generated:end -->"

READY_FOR_OWNER = {
    # ID: deliverables that must exist for "ready-for-owner" (rehearsal records make them green)
    "NFR-05": ["deploy/backup.sh", "docs/runbooks/deploy.md"],
    "NFR-06": [".github/workflows/ci.yml", "Dockerfile", "deploy/compose.yml"],
    "NFR-07": ["docs/runbooks/local-setup.md", "docs/runbooks/deploy.md"],
}


def junit_tags() -> dict[str, set[str]]:
    """ID -> test classes (simple names) whose source carries @Tag(ID)."""
    out: dict[str, set[str]] = defaultdict(set)
    for path in (ROOT / "src" / "test" / "java").rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        for tag in TAG_RE.findall(text):
            out[tag].add(path.stem)
    return out


def junit_results() -> dict[str, bool]:
    """Test class simple name -> passed (ran, no failure, no error, not everything skipped)."""
    out: dict[str, bool] = {}
    for folder in ("surefire-reports", "failsafe-reports"):
        for report in (ROOT / "target" / folder).glob("TEST-*.xml"):
            try:
                suite = ET.parse(report).getroot()
            except ET.ParseError:
                continue
            name = suite.get("name", "").rsplit(".", 1)[-1]
            tests = int(suite.get("tests", "0"))
            failures = int(suite.get("failures", "0")) + int(suite.get("errors", "0"))
            skipped = int(suite.get("skipped", "0"))
            passed = tests > 0 and failures == 0 and skipped < tests
            out[name] = out.get(name, True) and passed if name in out else passed
    return out


def e2e_tags() -> dict[str, set[str]]:
    """ID -> spec files whose titles carry [ID]."""
    out: dict[str, set[str]] = defaultdict(set)
    for path in (ROOT / "e2e" / "tests").glob("*.spec.ts"):
        for tag in TITLE_RE.findall(path.read_text(encoding="utf-8")):
            out[tag].add(path.name)
    return out


def e2e_results() -> dict[str, tuple[int, int]]:
    """ID -> (passed titles, failed titles) from the last Playwright json report."""
    report = ROOT / "e2e" / "test-results" / "results.json"
    counts: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    if not report.exists():
        return {}

    def walk(suite: dict) -> None:
        for spec in suite.get("specs", []):
            ids = set(TITLE_RE.findall(spec.get("title", "")))
            ok = bool(spec.get("ok")) and all(
                r.get("status") in ("passed", "expected") for t in spec.get("tests", []) for r in t.get("results", [])
            )
            for tag in ids:
                counts[tag][0 if ok else 1] += 1
        for child in suite.get("suites", []):
            walk(child)

    for suite in json.loads(report.read_text(encoding="utf-8")).get("suites", []):
        walk(suite)
    return {k: (v[0], v[1]) for k, v in counts.items()}


def status_of(
    tag: str, junit: dict[str, set[str]], passed: dict[str, bool], e2e: dict[str, set[str]], e2e_ok: dict[str, tuple[int, int]]
) -> tuple[str, str]:
    """(status, evidence) for one ID."""
    if tag.startswith("MIG-"):
        return "blocked (Q-55)", "dropped by the owner; no test written"
    green_classes = sorted(c for c in junit.get(tag, ()) if passed.get(c))
    pending_classes = sorted(c for c in junit.get(tag, ()) if not passed.get(c))
    e2e_passed, e2e_failed = e2e_ok.get(tag, (0, 0))
    e2e_files = sorted(e2e.get(tag, ()))
    parts = []
    if green_classes:
        parts.append("JUnit: " + ", ".join(f"`{c}`" for c in green_classes))
    if pending_classes:
        parts.append("not passing or no report: " + ", ".join(f"`{c}`" for c in pending_classes))
    if e2e_files:
        parts.append(f"E2E: {e2e_passed} passing title(s) in " + ", ".join(f"`{f}`" for f in e2e_files)
                     + (f", {e2e_failed} failing" if e2e_failed else ""))
    evidence = "; ".join(parts) if parts else "no tagged test yet"
    if tag in READY_FOR_OWNER:
        missing = [d for d in READY_FOR_OWNER[tag] if not (ROOT / d).exists()]
        if missing:
            return "planned", f"deliverables missing: {', '.join(missing)}"
        present = ", ".join(f"`{d}`" for d in READY_FOR_OWNER[tag])
        return "ready-for-owner", f"deliverables present ({present}); green only with a dated rehearsal record"
    if green_classes or e2e_passed:
        return "green", evidence
    if junit.get(tag) or e2e_files:
        return "in progress", evidence
    return "planned", evidence


def main() -> int:
    check = "--check" in sys.argv
    junit, passed, e2e, e2e_ok = junit_tags(), junit_results(), e2e_tags(), e2e_results()
    original = MATRIX.read_text(encoding="utf-8")
    lines = original.splitlines()
    rows: list[tuple[str, str, str, str]] = []  # id, milestone, status, evidence
    for i, line in enumerate(lines):
        m = ROW_RE.match(line)
        if not m:
            continue
        tag, milestone = m.group(1), m.group(2)
        status, evidence = status_of(tag, junit, passed, e2e, e2e_ok)
        rows.append((tag, milestone, status, evidence))
        cells = line.split("|")
        cells[4] = f" {status} "
        lines[i] = "|".join(cells)

    m1 = [r for r in rows if r[1] == "M1"]
    m1_deliverable = [r for r in m1 if not r[0].startswith("MIG-")]
    counts = defaultdict(int)
    for r in m1_deliverable:
        counts[r[2]] += 1
    green = counts["green"]
    block = [START,
             f"**Coverage on {date.today().isoformat()}** (generated by `tools/parity-matrix.py` from the tags and the last "
             f"local test reports; `./mvnw -B -ntp verify` and `e2e/run.sh` run first). Chapter 09 has {len(rows)} IDs: "
             f"{len(m1)} M1 and {len(rows) - len(m1)} M2 (ENH). MIG-01…03 are dropped (Q-55), so {len(m1_deliverable)} M1 IDs "
             f"are deliverable: **{green} green** ({100 * green // len(m1_deliverable)} %), {counts['ready-for-owner']} ready-for-owner, "
             f"{counts['in progress']} in progress, {counts['planned']} planned. "
             f"JUnit classes with tags: {len({c for s in junit.values() for c in s})}; E2E spec files with tags: "
             f"{len({f for s in e2e.values() for f in s})}.", "",
             "| ID | Status | Evidence (last run) |", "|---|---|---|"]
    for tag, _milestone, status, evidence in rows:
        if tag.startswith("ENH-"):
            continue
        block.append(f"| {tag} | {status} | {evidence} |")
    block.append("")
    block.append("ENH-01…22 (M2) are planned: no flag is implemented yet, so no tagged test exists.")
    block.append(END)

    text = "\n".join(lines) + "\n"
    if START in text and END in text:
        before, rest = text.split(START, 1)
        _, after = rest.split(END, 1)
        text = before + "\n".join(block) + after
    else:
        anchor = "\n## PUB — Public site"
        text = text.replace(anchor, "\n" + "\n".join(block) + "\n" + anchor, 1)

    if check:
        if text != original:
            print("docs/parity-matrix.md is out of date: run tools/parity-matrix.py", file=sys.stderr)
            return 1
        print("docs/parity-matrix.md is current")
        return 0
    MATRIX.write_text(text, encoding="utf-8")
    print(f"parity matrix: {green}/{len(m1_deliverable)} deliverable M1 IDs green; "
          f"{counts['ready-for-owner']} ready-for-owner; {counts['in progress']} in progress; {counts['planned']} planned")
    return 0


if __name__ == "__main__":
    sys.exit(main())
