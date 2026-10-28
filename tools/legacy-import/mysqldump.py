"""Reads the tables of a mysqldump file (plain or .gz) without a MySQL server.

Understands what `mysqldump` 8.0 writes: one `CREATE TABLE` per table and extended
`INSERT INTO `t` VALUES (...),(...);` statements. Values are returned as Python values:
None for NULL, str for quoted strings (backslash escapes decoded), int or float for numbers.
"""

import gzip
import re

_CREATE = re.compile(r"^CREATE TABLE `(\w+)` \($")
_COLUMN = re.compile(r"^\s+`(\w+)` ")
_INSERT = re.compile(r"^INSERT INTO `(\w+)` VALUES ")
_ESCAPES = {"0": "\0", "b": "\b", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a"}


def _open(path):
    if path.endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8", newline="\n")
    return open(path, encoding="utf-8", newline="\n")


def _number(text):
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    return float(text)


def _rows(text, pos):
    """Parses `(...),(...);` starting at pos; yields one tuple per row."""
    n = len(text)
    while pos < n:
        if text[pos] != "(":
            raise ValueError(f"expected '(' at {pos}")
        pos += 1
        row = []
        while True:
            while text[pos] in " \t":
                pos += 1
            c = text[pos]
            if c == "'":
                pos += 1
                out = []
                while True:
                    c = text[pos]
                    if c == "\\":
                        nxt = text[pos + 1]
                        out.append(_ESCAPES.get(nxt, nxt))
                        pos += 2
                    elif c == "'":
                        if text[pos + 1] == "'":
                            out.append("'")
                            pos += 2
                        else:
                            pos += 1
                            break
                    else:
                        out.append(c)
                        pos += 1
                row.append("".join(out))
            else:
                end = pos
                while text[end] not in ",)":
                    end += 1
                token = text[pos:end]
                row.append(None if token == "NULL" else _number(token))
                pos = end
            if text[pos] == ",":
                pos += 1
                continue
            if text[pos] == ")":
                pos += 1
                break
            raise ValueError(f"unexpected {text[pos]!r} at {pos}")
        yield tuple(row)
        if text[pos] == ",":
            pos += 1
        elif text[pos] == ";":
            return
        else:
            raise ValueError(f"unexpected {text[pos]!r} at {pos}")


def read_dump(path):
    """Returns {table: {"columns": [...], "rows": [dict, ...]}} for every table of the dump."""
    tables = {}
    current = None
    with _open(path) as fh:
        for line in fh:
            m = _CREATE.match(line)
            if m:
                current = m.group(1)
                tables[current] = {"columns": [], "rows": []}
                continue
            if current and line.startswith(")"):
                current = None
                continue
            if current:
                c = _COLUMN.match(line)
                if c:
                    tables[current]["columns"].append(c.group(1))
                continue
            m = _INSERT.match(line)
            if m:
                table = tables[m.group(1)]
                cols = table["columns"]
                for values in _rows(line.rstrip("\n"), m.end()):
                    if len(values) != len(cols):
                        raise ValueError(f"{m.group(1)}: {len(values)} values for {len(cols)} columns")
                    table["rows"].append(dict(zip(cols, values)))
    return tables
