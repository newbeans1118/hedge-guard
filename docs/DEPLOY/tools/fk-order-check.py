#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Static FK total-order check for docs/DEPLOY/schema/*.sql  (T-179)

MODEL (matches the schema scripts' own convention, DATA_MODEL.md sec 0.5 /
m1_plan.sql self-check "文件内顺序无关，建表按依赖执行"):
  * within one file, CREATE TABLE statements may be executed in dependency
    order (a parent defined later in the same file is resolved by reordering);
  * across files the order is FIXED by README sec 7.1, except that deferred
    cross-module FK segments run at their scheduled replay step;
  * a module-internal deferred ALTER (parent defined later in the SAME file) runs
    at the end of that file's create section; a cross-module deferred ALTER runs
    at its scheduled replay step (README sec 7.1).  This file's deferred ALTER
    segments are executed when its own create phase finishes; only files with a
    dedicated replay step in PLAN are handled at that step.
  * therefore a violation = an FK whose parent does not exist at the moment the
    child is created (or the deferred ALTER runs) under that order.  A cycle
    spanning files is exactly this: no order can satisfy it, which is why the
    two-phase replay exists.
  * each directed (child, parent) edge is counted once (unique edges), regardless
    of how many constraints / columns realise it.

Usage:  python3 fk-order-check.py [schema_dir] [-v]
Exit 0 = PASS (total order realisable); 1 = FAIL.
"""
import re, sys, os
from collections import defaultdict, deque

argv = [a for a in sys.argv[1:] if a != '-v']
VERBOSE = '-v' in sys.argv
SCHEMA = argv[0] if argv else 'docs/DEPLOY/schema'

def read(f):
    with open(os.path.join(SCHEMA, f), encoding='utf-8') as fh:
        return fh.read()

def parse(text):
    creates, alters = [], []
    i, n = 0, len(text)
    while i < n:
        m = re.search(r'\bCREATE\s+TABLE\s+([A-Za-z_][A-Za-z0-9_]*)', text[i:], re.IGNORECASE)
        m2 = re.search(r'\bALTER\s+TABLE\s+([A-Za-z_][A-Za-z0-9_]*)\s+ADD\s+CONSTRAINT\s+([A-Za-z_][A-Za-z0-9_]*)', text[i:], re.IGNORECASE)
        cands = [(m.start(), 'c', m)] if m else []
        if m2: cands.append((m2.start(), 'a', m2))
        if not cands: break
        cands.sort()
        _, kind, mo = cands[0]
        if kind == 'c':
            j = i + mo.end(); k = text.find('(', j); depth, end = 0, k
            while end < n:
                c = text[end]
                if c == '(': depth += 1
                elif c == ')':
                    depth -= 1
                    if depth == 0: break
                end += 1
            parents = re.findall(r'FOREIGN\s+KEY\s*\([^)]*\)\s*REFERENCES\s+([A-Za-z_][A-Za-z0-9_]*)',
                                 text[k:end+1], re.IGNORECASE)
            creates.append((mo.group(1), parents)); i = end + 1
        else:
            seg = text[i + mo.end(): i + mo.end() + 400]
            mm = re.search(r'FOREIGN\s+KEY\s*\([^)]*\)\s*REFERENCES\s+([A-Za-z_][A-Za-z0-9_]*)', seg, re.IGNORECASE)
            alters.append((mo.group(1), [mm.group(1)] if mm else [])); i = i + mo.end()
    return creates, alters

FILES = ['m1_config_auth.sql', 'm1_product.sql', 'm1_spot.sql', 'm1_plan.sql',
         'm2_trade.sql', 'm2_settle.sql', 'm2_fund.sql', 'm3_risk.sql']
PARSED = {}
for f in FILES:
    PARSED[f] = parse(read(f))

# README sec 7.1 execution plan (two-phase replay for deferred segments)
PLAN = [
    ('m1_config_auth.sql', 'create'), ('m1_product.sql', 'create'),
    ('m1_config_auth.sql', 'alter'),  ('m1_spot.sql', 'create'),
    ('m1_plan.sql', 'create'),        ('m2_trade.sql', 'create'),
    ('m1_plan.sql', 'alter'),         ('m2_settle.sql', 'create'),
    ('m2_fund.sql', 'create'),        ('m2_settle.sql', 'alter'),
    ('m3_risk.sql', 'create'),
]
# Files whose deferred ALTER segment has a dedicated replay step in PLAN; every
# other file runs its deferred ALTERs at the end of its own create section.
SCHEDULED_ALTER = {f for f, p in PLAN if p == 'alter'}

def in_file_topo(creates):
    """Return tables in dependency order; raise on in-file cycle."""
    names = [t for t, _ in creates]
    pmap = {t: [p for p in ps if p in names and p != t] for t, ps in creates}
    children = defaultdict(list); indeg = {t: 0 for t in names}
    for t, ps in pmap.items():
        for p in set(ps):
            children[p].append(t); indeg[t] += 1
    q = deque([t for t in names if indeg[t] == 0]); order = []
    while q:
        t = q.popleft(); order.append(t)
        for c in children[t]:
            indeg[c] -= 1
            if indeg[c] == 0: q.append(c)
    if len(order) != len(names):
        raise RuntimeError('in-file cycle among: %s' % sorted(set(names) - set(order)))
    return order, pmap

created, violations, reordered = set(), [], []
seen_edges = set()

def check(fname, kind, tname, parent, when):
    key = (tname, parent)
    if key in seen_edges:            # same directed edge -> count once
        return
    seen_edges.add(key)
    if parent in created:
        if VERBOSE: print('OK   %-16s %-6s  %-30s -> %s' % (fname, kind, tname, parent))
    else:
        violations.append((fname, kind, tname, parent))
        print('FAIL %-16s %-6s  %-30s -> %s (%s)' % (fname, kind, tname, parent, when))

for fname, phase in PLAN:
    creates, alters = PARSED[fname]
    if phase == 'create':
        allparents = {t: ps for t, ps in creates}
        order, pmap = in_file_topo(creates)
        if order != [t for t, _ in creates]:
            reordered.append((fname, [t for t, _ in creates], order))
        for tname in order:
            for p in allparents[tname]:
                if p == tname:  # self-reference
                    continue
                check(fname, 'create', tname, p, 'parent absent at create time')
            created.add(tname)
        if fname not in SCHEDULED_ALTER:
            # module-internal deferred ALTER: runs after this file's tables exist
            for tname, parents in alters:
                for p in parents:
                    check(fname, 'alter', tname, p,
                          'parent absent at module-internal deferred ALTER time')
    else:
        for tname, parents in alters:
            for p in parents:
                check(fname, 'alter', tname, p, 'parent absent at replay step')

for fname, orig, new in reordered:
    if VERBOSE:
        print('NOTE %s: %.14s... executed in dependency order (same-file forward ref resolved)' % (fname, ' -> '.join(new)))
print('schema files            : %d' % len(FILES))
print('tables created in order : %d' % len(created))
print('FK edges checked        : %d unique (incl. module-internal deferred ALTER)' % len(seen_edges))
if violations:
    print('VIOLATIONS (%d)' % len(violations)); print('RESULT: FAIL'); sys.exit(1)
print('RESULT: PASS (total order realisable under README sec 7.1)')
sys.exit(0)

