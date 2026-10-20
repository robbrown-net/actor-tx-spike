#!/usr/bin/env python3
"""Builds docs/report/engineering-report.html from the Markdown docs.

Usage: python3 docs/report/build.py
Mermaid diagrams are rendered in the browser (mermaid from jsdelivr, pinned).
"""
import html, os, re

ROOT = os.path.normpath(os.path.join(os.path.dirname(__file__), ".."))
SECTIONS = [
    ("Engineering story", "ENGINEERING_STORY.md"),
    ("Requirements", "REQUIREMENTS.md"),
    ("User guide", "USER_GUIDE.md"),
    ("Use cases", "architecture/01-use-cases.md"),
    ("Domain model", "architecture/02-domain-model.md"),
    ("System sequence diagrams", "architecture/03-system-sequence-diagrams.md"),
    ("Design sequences, activities, states", "architecture/04-design-sequences-and-activities.md"),
    ("System structure", "architecture/05-system-structure.md"),
    ("Data model", "architecture/06-data-model.md"),
    ("Observability & performance", "architecture/07-observability-and-performance.md"),
    ("Test lab", "architecture/08-test-lab.md"),
    ("Bulk send", "architecture/09-bulk-send.md"),
    ("Compliance evidence", "compliance/COMPLIANCE_EVIDENCE.md"),
    ("Developer guide", "DEVELOPER_GUIDE.md"),
    ("Testing guide", "TESTING.md"),
    ("Quality snapshot", "quality/QUALITY_SNAPSHOT.md"),
    ("Decisions (ADRs)", None),
    ("Roadmap", "ROADMAP.md"),
]
ADRS = [f"adr/{f}" for f in sorted(os.listdir(os.path.join(ROOT, "adr"))) if re.match(r"\d{4}-.*\.md", f)]
slug = lambda s: re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def inline(t, base):
    t = html.escape(t, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<![\w*])\*([^*\n]+)\*(?![\w*])", r"<em>\1</em>", t)

    def link(m):
        text, href = m.group(1), m.group(2)
        if href.startswith("http"):
            return f'<a href="{href}">{text}</a>'
        target = os.path.normpath(os.path.join(os.path.dirname(base), href.split("#")[0]))
        return f'<a href="#doc-{slug(target)}">{text}</a>'

    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, t)


def render(md, base):
    out, lines, i = [], md.split("\n"), 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("```"):
            lang = line[3:].strip()
            j = i + 1
            body = []
            while j < len(lines) and not lines[j].startswith("```"):
                body.append(lines[j]); j += 1
            code = "\n".join(body)
            if lang == "mermaid":
                out.append(f'<div class="diagram"><pre class="mermaid">{html.escape(code)}</pre></div>')
            else:
                out.append(f"<pre><code>{html.escape(code)}</code></pre>")
            i = j + 1
        elif re.match(r"^#{1,4} ", line):
            n = len(line) - len(line.lstrip("#"))
            out.append(f"<h{n+1}>{inline(line[n+1:], base)}</h{n+1}>"); i += 1
        elif line.startswith("|") and i + 1 < len(lines) and re.match(r"^\|[\s:|-]+\|$", lines[i + 1]):
            head = [c.strip() for c in line.strip("|").split("|")]
            rows, j = [], i + 2
            while j < len(lines) and lines[j].startswith("|"):
                rows.append([c.strip() for c in re.split(r"(?<!\\)\|", lines[j].strip().strip("|"))]); j += 1
            t = "<table><thead><tr>" + "".join(f"<th>{inline(c, base)}</th>" for c in head) + "</tr></thead><tbody>"
            for r in rows:
                cells = [inline(c.replace("\\|", "|"), base) for c in r]
                t += "<tr>" + "".join("<td>" + c + "</td>" for c in cells) + "</tr>"
            out.append(t + "</tbody></table>"); i = j
        elif re.match(r"^\s*([-*]|\d+\.) ", line):
            ordered = bool(re.match(r"^\s*\d+\. ", line))
            tag, items, j = ("ol" if ordered else "ul"), [], i
            while j < len(lines) and re.match(r"^\s*([-*]|\d+\.) ", lines[j]):
                items.append(re.sub(r"^\s*([-*]|\d+\.) ", "", lines[j])); j += 1
            out.append(f"<{tag}>" + "".join(f"<li>{inline(x, base)}</li>" for x in items) + f"</{tag}>"); i = j
        elif line.startswith(">"):
            body, j = [], i
            while j < len(lines) and lines[j].startswith(">"):
                body.append(lines[j].lstrip("> ")); j += 1
            out.append(f"<blockquote>{inline(' '.join(body), base)}</blockquote>"); i = j
        elif line.strip() == "---":
            out.append("<hr>"); i += 1
        elif not line.strip():
            i += 1
        else:
            body, j = [], i
            while j < len(lines) and lines[j].strip() and not re.match(r"^(#{1,4} |```|\||>|\s*([-*]|\d+\.) )", lines[j]):
                body.append(lines[j]); j += 1
            out.append(f"<p>{inline(' '.join(body), base)}</p>"); i = j
    return "\n".join(out)


def section(title, path):
    full = os.path.join(ROOT, path)
    md = open(full, encoding="utf-8").read()
    return f'<section class="doc" id="doc-{slug(os.path.normpath(os.path.join("docs", path)))}">{render(md, path)}</section>'


body, toc = [], []
for title, path in SECTIONS:
    toc.append(title)
    if path:
        body.append(f'<div class="part" id="p-{slug(title)}"><span>{title}</span></div>' + section(title, path))
    else:
        body.append(f'<div class="part" id="p-{slug(title)}"><span>{title}</span></div>' + "".join(section(a, a) for a in ADRS))

nav = "".join(f'<a href="#p-{slug(t)}">{t}</a>' for t in toc)
page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Actor TX: Engineering Report</title>
<style>
:root {{ --bg:#fff; --fg:#1f2328; --dim:#59636e; --line:#d1d9e0; --panel:#f6f8fa; --accent:#0969da; --good:#1a7f37; --warn:#9a6700; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg:#0d1117; --fg:#e6edf3; --dim:#9198a1; --line:#30363d; --panel:#161b22; --accent:#4493f8; --good:#3fb950; --warn:#d29922; }} }}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--fg); font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif; }}
header.hero {{ padding:40px 24px 24px; max-width:1100px; margin:0 auto; }}
header.hero h1 {{ margin:0 0 6px; font-size:30px; }}
header.hero p {{ margin:0; color:var(--dim); }}
.cards {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(150px,1fr)); gap:12px; max-width:1100px; margin:16px auto; padding:0 24px; }}
.card {{ background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:12px 14px; }}
.card b {{ display:block; font-size:24px; }} .card span {{ color:var(--dim); font-size:13px; }}
.card.good b {{ color:var(--good); }} .card.warn b {{ color:var(--warn); }}
nav {{ position:sticky; top:0; z-index:5; background:var(--bg); border-bottom:1px solid var(--line); }}
nav div {{ max-width:1100px; margin:0 auto; padding:8px 24px; display:flex; flex-wrap:wrap; gap:6px 16px; font-size:14px; }}
nav a {{ color:var(--accent); text-decoration:none; }} nav a:hover {{ text-decoration:underline; }}
main {{ max-width:1100px; margin:0 auto; padding:0 24px 80px; }}
.part {{ margin:56px 0 8px; border-bottom:2px solid var(--accent); }}
.part span {{ display:inline-block; font-size:12px; letter-spacing:.1em; text-transform:uppercase; color:var(--accent); font-weight:600; padding:0 0 4px; }}
section.doc {{ margin-bottom:32px; }}
h2 {{ font-size:26px; margin:28px 0 8px; }} h3 {{ font-size:20px; margin:28px 0 6px; }} h4 {{ font-size:16px; margin:20px 0 4px; }}
table {{ border-collapse:collapse; width:100%; margin:12px 0; font-size:14px; display:block; overflow-x:auto; }}
th,td {{ border:1px solid var(--line); padding:6px 10px; text-align:left; vertical-align:top; }} th {{ background:var(--panel); }}
code {{ background:var(--panel); padding:1px 5px; border-radius:4px; font-size:.9em; }}
pre {{ background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:12px; overflow-x:auto; }} pre code {{ background:none; padding:0; }}
blockquote {{ margin:12px 0; padding:8px 14px; border-left:4px solid var(--warn); background:var(--panel); }}
.diagram {{ background:#fff; border:1px solid var(--line); border-radius:10px; padding:12px; margin:14px 0; overflow-x:auto; }}
.diagram pre {{ background:none; border:none; margin:0; }}
hr {{ border:none; border-top:1px solid var(--line); margin:24px 0; }}
a {{ color:var(--accent); }}
@media print {{ nav {{ display:none; }} .diagram {{ break-inside:avoid; }} }}
</style></head>
<body>
<header class="hero"><h1>Actor TX: Engineering Report</h1>
<p>Orchestrated FX swap + payment across unreliable providers · Elixir/OTP · snapshot 2026-10-21 · generated by <code>docs/report/build.py</code></p></header>
<div class="cards">
<div class="card good"><b>100%</b><span>unit coverage (line)</span></div>
<div class="card good"><b>87%</b><span>integration coverage (line)</span></div>
<div class="card good"><b>12</b><span>system scenarios</span></div>
<div class="card good"><b>17</b><span>Test lab scenarios</span></div>
<div class="card good"><b>23</b><span>regression tests</span></div>
<div class="card"><b>392</b><span>tests, 0 failures</span></div>
<div class="card"><b>6,806</b><span>lib SLOC</span></div>
<div class="card good"><b>4</b><span>max cyclomatic complexity (limit 4)</span></div>
<div class="card good"><b>0</b><span>duplicated blocks (mass 16)</span></div>
<div class="card good"><b>0</b><span>Credo issues (strict)</span></div>
<div class="card good"><b>88/90</b><span>mutants killed (2 equivalent)</span></div>
<div class="card good"><b>0</b><span>runtime dependencies</span></div>
</div>
<nav><div>{nav}</div></nav>
<main>{''.join(body)}</main>
<script src="https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"></script>
<script>
  mermaid.initialize({{ startOnLoad: true, theme: 'default', securityLevel: 'strict', flowchart: {{ useMaxWidth: true }} }});
</script>
<noscript>Diagrams need JavaScript; their Mermaid source is in the Markdown files under docs/.</noscript>
</body></html>"""
out = os.path.join(ROOT, "report", "engineering-report.html")
open(out, "w", encoding="utf-8").write(page)
print("wrote", out, len(page), "bytes")
