"""Pitest leaves some classes alone; each must hold data only, or real logic goes unmeasured.

The classes are listed in config/pitest-data-only.txt. A class there that gains a function,
an init block or a hand-written getter would have its logic skipped by the mutants measure.
"""

import pathlib
import re

ROOT = pathlib.Path(__file__).parent.parent
LIST = ROOT / "config" / "pitest-data-only.txt"
SOURCES = [ROOT / "core" / "src" / "main", ROOT / "plugin-api" / "src" / "main"]
LOGIC = re.compile(r"\bfun\b|\binit\s*\{|\bget\(\)|\bset\(")


def listed():
    lines = (line.strip() for line in LIST.read_text().splitlines())
    return [line for line in lines if line and not line.startswith("#")]


def declaration(simple, sources=SOURCES):
    """The declaration of the class, and its body if it has one, from the Kotlin sources."""
    pattern = re.compile(rf"^\s*(?:data |enum )class {simple}\b.*$", re.MULTILINE)
    for path in (p for folder in sources for p in folder.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        found = pattern.search(text)
        if not found:
            continue
        line = found.group(0)
        if not line.rstrip().endswith("{"):
            return line
        depth = 0
        for at in range(found.start(), len(text)):
            depth += {"{": 1, "}": -1}.get(text[at], 0)
            if text[at] == "}" and depth == 0:
                return text[found.start() : at + 1]
        return text[found.start() :]
    return None


def offenders(names, sources=SOURCES):
    """What is wrong with the list: a class that is not data, or one that has logic."""
    found = []
    for name in names:
        simple = name.rsplit(".", 1)[-1].rsplit("$", 1)[-1]
        body = declaration(simple, sources)
        if body is None:
            found.append(f"{name} is not a data or enum class in :core or :plugin-api")
            continue
        # Doc comments may say "fun"; only code counts.
        code = re.sub(r"/\*.*?\*/|//[^\n]*", "", body, flags=re.DOTALL)
        if LOGIC.search(code):
            found.append(f"{name} has logic now; take it off {LIST.name}")
    return found


def test_every_class_pitest_leaves_alone_holds_data_only():
    assert offenders(listed()) == []


def test_a_listed_class_that_gains_logic_is_refused(tmp_path):
    (tmp_path / "A.kt").write_text(
        "package x\n"
        "data class Plain(val a: Int)\n"
        "data class Clever(val a: Int) {\n    fun twice() = a * 2\n}\n"
        'enum class Mode(val w: String) {\n    ON("on");\n    val loud get() = w.uppercase()\n}\n'
        "class Busy { fun work() = 1 }\n"
    )
    names = ["x.Plain", "x.Clever", "x.Mode", "x.Busy"]
    assert offenders(names, [tmp_path]) == [
        "x.Clever has logic now; take it off pitest-data-only.txt",
        "x.Mode has logic now; take it off pitest-data-only.txt",
        "x.Busy is not a data or enum class in :core or :plugin-api",
    ]
