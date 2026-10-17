#!/usr/bin/env python3
"""Lists the accessibility events of a `cmd accessibility start-trace` file (a11y_trace.winscope, protobuf with the
AccessibilityEvent.toString() text embedded). Usage: a11y-trace-events.py FILE"""
import re, sys
data = open(sys.argv[1], 'rb').read().decode('utf-8', 'replace')
pat = re.compile(r'EventType: (TYPE_\w+); EventTime: (\d+); PackageName: ([\w.]+);.*?ContentChangeTypes: \[(.*?)\];.*?ClassName: ([\w.$]+); Text: \[(.*?)\]; ContentDescription: (.*?); ItemCount', re.S)
seen = set()
for m in pat.finditer(data):
    t, ts, pkg, cct, cls, text, cd = m.groups()
    key = (t, ts, text)
    if key in seen: continue
    seen.add(key)
    if t in ('TYPE_WINDOW_CONTENT_CHANGED',) and not text: continue
    print(f'{ts}\t{t}\t{pkg}\t{cls}\ttext=[{text}]\tdesc={cd}\tchange={cct}')
