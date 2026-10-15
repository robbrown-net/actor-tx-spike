#!/usr/bin/env python3
"""A small, honest mutation tester for the pure core of the system.

Line coverage says a line *ran*; mutation testing asks whether the tests would
*notice* if the line were wrong. For each target file we apply one small change
at a time (flip a comparison, swap and/or, drop a negation, off-by-one) and
run the relevant tests. A mutant the tests fail on is KILLED; one they pass on
SURVIVED (a gap). Mutants that do not compile are discarded.

Usage: python3 scripts/mutate.py [--max-per-file N]
Requires the toolchain on PATH (see README). Restores every file afterwards.
"""
import re, subprocess, sys, os, json

TARGETS = {
    "lib/actor_tx_spike/policy.ex": ["test/unit/policy_test.exs", "test/unit/selector_test.exs"],
    "lib/actor_tx_spike/provider/health.ex": ["test/unit/provider/health_test.exs", "test/unit/provider/server_test.exs"],
    "lib/actor_tx_spike/selector.ex": ["test/unit/selector_test.exs", "test/unit/policy_test.exs"],
}
RULES = [
    (re.compile(r"(?<![<>=!])>=(?!=)"), ">"), (re.compile(r"(?<![<>=!])<=(?!=)"), "<"),
    (re.compile(r"(?<![<>=!|-])>(?![=>])"), ">="), (re.compile(r"(?<![<>=!|-])<(?![=<-])"), "<="),
    (re.compile(r"(?<![=!<>])==(?!=)"), "!="), (re.compile(r"!="), "=="),
    (re.compile(r"\band\b"), "or"), (re.compile(r"\bor\b"), "and"),
    (re.compile(r"\bnot\b "), ""), (re.compile(r"\+ 1\b"), "+ 0"), (re.compile(r"- 1\b"), "- 0"),
    (re.compile(r"\bdo: true\b"), "do: false"), (re.compile(r"\bdo: false\b"), "do: true"),
]
MAX = int(sys.argv[sys.argv.index("--max-per-file") + 1]) if "--max-per-file" in sys.argv else 30


def candidate_lines(lines):
    in_doc = False
    for i, line in enumerate(lines):
        t = line.strip()
        if t.count('"""') % 2 == 1:
            in_doc = not in_doc
            continue
        if in_doc or t.startswith("#") or t.startswith("@") or not t:
            continue
        yield i, line


def run_tests(tests):
    p = subprocess.run(["mix", "test", "--no-color", "--max-failures", "1"] + tests,
                       capture_output=True, text=True, env={**os.environ, "MIX_ENV": "test"})
    out = p.stdout + p.stderr
    if "CompileError" in out or "error:" in out and "tests," not in out:
        return "invalid"
    return "survived" if p.returncode == 0 else "killed"


results, survivors = {}, []
for path, tests in TARGETS.items():
    original = open(path).read()
    lines = original.split("\n")
    mutants = []
    for i, line in candidate_lines(lines):
        for rx, repl in RULES:
            m = rx.search(line)
            if m:
                mutants.append((i, line[:m.start()] + repl + line[m.end():], rx.pattern))
    step = max(1, len(mutants) // MAX)
    sample = mutants[::step][:MAX]
    tally = {"killed": 0, "survived": 0, "invalid": 0}
    try:
        for i, new_line, rule in sample:
            mutated = lines[:i] + [new_line] + lines[i + 1:]
            open(path, "w").write("\n".join(mutated))
            verdict = run_tests(tests)
            tally[verdict] += 1
            if verdict == "survived":
                survivors.append({"file": path, "line": i + 1, "was": lines[i].strip(), "now": new_line.strip()})
            print(f"{path}:{i+1} {verdict}", flush=True)
    finally:
        open(path, "w").write(original)
    valid = tally["killed"] + tally["survived"]
    results[path] = {**tally, "score": round(100 * tally["killed"] / valid, 1) if valid else None, "sampled": len(sample), "available": len(mutants)}

print(json.dumps({"results": results, "survivors": survivors}, indent=2))
