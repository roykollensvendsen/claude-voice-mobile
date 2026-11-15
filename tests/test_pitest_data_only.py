"""Pitest leaves some classes alone; each must hold data only, or real logic goes unmeasured.

The classes are listed in config/pitest-data-only.txt. The guard is fail-closed: rather than
looking for kinds of logic, it allows one shape only. A listed data class has no body at all,
and its constructor parameters have no default but a literal. A listed enum class may have a
body, holding nothing but its entries, each with literal arguments. A class is found by its
package and its outer classes, so one of the same name elsewhere cannot stand in for it.
"""

import pathlib
import re

ROOT = pathlib.Path(__file__).parent.parent
LIST = ROOT / "config" / "pitest-data-only.txt"
SOURCES = [ROOT / "core" / "src" / "main", ROOT / "plugin-api" / "src" / "main"]
LITERAL = r'(?:"[^"\\]*"|-?\d+(?:\.\d+)?[fFL]?|true|false|null)'
PARAMETER = re.compile(rf"^(?:val|var)\s+\w+\s*:\s*[\w.<>?, ]+?(?:\s*=\s*{LITERAL})?$")
ENTRY = re.compile(rf"^[A-Z][A-Z0-9_]*(?:\(\s*{LITERAL}(?:\s*,\s*{LITERAL})*\s*\))?$")
SUPERTYPES = re.compile(r"^:\s*[\w.]+(?:\s*,\s*[\w.]+)*$")


def listed():
    lines = (line.strip() for line in LIST.read_text().splitlines())
    return [line for line in lines if line and not line.startswith("#")]


def _code(text):
    """The text with comments blanked out, the same length, so positions still match."""
    return re.sub(r"/\*.*?\*/|//[^\n]*", lambda m: re.sub(r"[^\n]", " ", m.group(0)), text, flags=re.DOTALL)


def _close(text, start, opening, closing):
    """The index just past the bracket that closes the one at `start`."""
    depth = 0
    for at in range(start, len(text)):
        depth += {opening: 1, closing: -1}.get(text[at], 0)
        if depth == 0:
            return at + 1
    return len(text)


def _split(text):
    """Comma-separated parts at the top level of `text`, ignoring commas inside brackets."""
    parts, depth, current = [], 0, ""
    for char in text:
        depth += {"(": 1, "<": 1, "[": 1, ")": -1, ">": -1, "]": -1}.get(char, 0)
        if char == "," and depth == 0:
            parts.append(current.strip())
            current = ""
        else:
            current += char
    return [p for p in [*parts, current.strip()] if p]


def _find(scope, name):
    """Where `name` is declared at the top level of `scope`, and what kind it is."""
    pattern = re.compile(rf"\b((?:data |enum |sealed |abstract |open )?(?:class|interface|object)) {name}\b")
    for found in pattern.finditer(scope):
        before = scope[: found.start()]
        if before.count("{") == before.count("}"):  # not nested in something else here
            return found
    return None


def declaration(name, sources=SOURCES):
    """The listed class's kind and its text, found by package and outer classes; or None."""
    package, _, path = name.rpartition(".")
    chain = path.split("$")
    for file in (p for folder in sources for p in folder.rglob("*.kt")):
        text = _code(file.read_text(encoding="utf-8"))
        if not re.search(rf"^package {re.escape(package)}\s*$", text, re.MULTILINE):
            continue
        scope = text
        for outer in chain[:-1]:
            found = _find(scope, outer)
            if not found or "{" not in scope[found.end() :]:
                scope = None
                break
            opening = scope.index("{", found.end())
            scope = scope[opening + 1 : _close(scope, opening, "{", "}") - 1]
        found = _find(scope, chain[-1]) if scope is not None else None
        if found:
            return found.group(1), scope[found.end() :]
    return None


def _shape(kind, rest):
    """What breaks the data-only shape in a class's text after its name, or None."""
    rest = rest.lstrip()
    if not rest.startswith("("):
        return "has no constructor of values"
    end = _close(rest, 0, "(", ")")
    bad = [p for p in _split(rest[1 : end - 1]) if not PARAMETER.match(" ".join(p.split()))]
    if bad:
        return f"has a parameter that is not a plain value: {bad[0]}"
    after = rest[end:].lstrip()
    header = after.split("{", 1)[0].split("\n", 1)[0].strip() if after.startswith(":") else ""
    if header and not SUPERTYPES.match(header):
        return f"has supertypes that are not plain names: {header}"
    after = after[len(header) :].lstrip()
    if not after.startswith("{"):
        return None
    if kind != "enum class":
        return "has a body"
    body = after[1 : _close(after, 0, "{", "}") - 1].strip().rstrip(";").strip()
    bad = [e for e in _split(body) if not ENTRY.match(" ".join(e.split()))]
    return f"has more than its entries: {bad[0]}" if bad else None


def offenders(names, sources=SOURCES):
    """What is wrong with the list: a class not found, not data or enum, or with logic."""
    found = []
    for name in names:
        located = declaration(name, sources)
        if located is None:
            found.append(f"{name} is not declared in :core or :plugin-api")
            continue
        kind, rest = located
        if kind not in {"data class", "enum class"}:
            found.append(f"{name} is not a data or enum class")
            continue
        problem = _shape(kind, rest)
        if problem:
            found.append(f"{name} {problem}; take it off {LIST.name}")
    return found


def declared_classes(folder):
    """Every class, object and interface declared under `folder`, by package and outer classes."""
    found = []
    for file in sorted(folder.rglob("*.kt")):
        text = _code(file.read_text(encoding="utf-8"))
        package = re.search(r"^package ([\w.]+)", text, re.MULTILINE).group(1)
        found += _walk(text, f"{package}.", "")
    return found


def _walk(scope, prefix, outer):
    found = []
    pattern = re.compile(r"\b((?:data |enum |sealed |abstract |open )?(?:class|interface|object)) (\w+)")
    for match in pattern.finditer(scope):
        before = scope[: match.start()]
        if before.count("{") != before.count("}"):
            continue
        name = f"{outer}${match.group(2)}" if outer else match.group(2)
        found.append((match.group(1), prefix + name, scope[match.end() :]))
        rest = scope[match.end() :]
        header = rest.split("\n", 1)[0]
        if match.group(1).endswith("interface") and "{" in header:
            opening = match.end() + rest.index("{")
            found += _walk(scope[opening + 1 : _close(scope, opening, "{", "}") - 1], prefix, name)
    return found


def unlisted_with_code(declared, names=None):
    """Declared classes that may hold code Pitest would mutate: neither listed nor bare interfaces.

    A bare interface declares functions and properties without bodies, so it has nothing to mutate.
    """
    names = set(listed() if names is None else names)
    out = []
    for kind, name, rest in declared:
        if name in names:
            continue
        body = rest[: _close(rest, rest.index("{"), "{", "}")] if "{" in rest.split("\n", 1)[0] else ""
        if kind.endswith("interface") and not re.search(r"=|\bget\(|fun [^\n]*\)\s*(?::[^\n{]*)?\{", body):
            continue
        out.append(name)
    return out


def test_every_class_pitest_leaves_alone_holds_data_only():
    assert offenders(listed()) == []


def _source(tmp_path, text, package="x", file="A.kt"):
    folder = tmp_path / package.replace(".", "/")
    folder.mkdir(parents=True, exist_ok=True)
    (folder / file).write_text(f"package {package}\n{text}")
    return [tmp_path]


def test_plain_data_and_enum_classes_pass(tmp_path):
    sources = _source(
        tmp_path,
        "/** Doc that says fun. */\n"
        'data class Plain(val a: Int, val b: String = "b", val c: List<Int>)\n'
        "sealed interface State {\n    data class On(val at: Int) : State\n}\n"
        'enum class Mode(val w: String) {\n    /** on */\n    ON("on"),\n    OFF("off"),\n}\n',
    )
    assert offenders(["x.Plain", "x.State$On", "x.Mode"], sources) == []


def test_a_computed_property_is_refused(tmp_path):
    sources = _source(tmp_path, "data class C(val a: Int) {\n    val twice = a * 2\n}\n")
    assert offenders(["x.C"], sources) == ["x.C has a body; take it off pitest-data-only.txt"]


def test_a_lazy_property_is_refused(tmp_path):
    sources = _source(tmp_path, "data class C(val a: Int) {\n    val heavy by lazy { a * 2 }\n}\n")
    assert offenders(["x.C"], sources) == ["x.C has a body; take it off pitest-data-only.txt"]


def test_a_computed_default_is_refused(tmp_path):
    sources = _source(tmp_path, "data class C(val a: Int, val b: Int = a * 2)\n")
    assert offenders(["x.C"], sources) == [
        "x.C has a parameter that is not a plain value: val b: Int = a * 2; take it off pitest-data-only.txt"
    ]


def test_a_secondary_constructor_is_refused(tmp_path):
    sources = _source(
        tmp_path, "data class C(val a: Int) {\n    constructor(s: String) : this(s.length)\n}\n"
    )
    assert offenders(["x.C"], sources) == ["x.C has a body; take it off pitest-data-only.txt"]


def test_an_enum_with_more_than_entries_is_refused(tmp_path):
    sources = _source(
        tmp_path, 'enum class M(val w: String) {\n    ON("on");\n    val loud get() = w.uppercase()\n}\n'
    )
    (problem,) = offenders(["x.M"], sources)
    assert problem.startswith("x.M has more than its entries:")
    assert "val loud get()" in problem


def test_a_class_of_the_same_name_elsewhere_cannot_stand_in(tmp_path):
    _source(tmp_path, "data class Answer(val a: Int)\n", package="other")
    sources = _source(
        tmp_path,
        "sealed interface Result {\n    data class Answer(val a: Int) {\n        fun b() = a\n    }\n}\n",
    )
    # The clean Answer in another package is not the listed one, in whatever order files are read.
    assert offenders(["x.Result$Answer"], sources) == [
        "x.Result$Answer has a body; take it off pitest-data-only.txt"
    ]
    assert offenders(["x.Answer"], sources) == ["x.Answer is not declared in :core or :plugin-api"]


def test_a_listed_class_that_is_not_data_is_refused(tmp_path):
    sources = _source(tmp_path, "class Busy(val a: Int)\n")
    assert offenders(["x.Busy"], sources) == ["x.Busy is not a data or enum class"]


def test_a_module_with_a_class_not_listed_is_not_all_data_only(tmp_path):
    folder = tmp_path / "x"
    folder.mkdir()
    (folder / "A.kt").write_text(
        "package x\ninterface Port {\n    fun open(): Int\n}\n"
        "sealed interface R {\n    data class Ok(val a: Int) : R\n}\n"
        "interface Helper {\n    fun twice(a: Int) = a * 2\n}\n"
        "class Busy(val a: Int)\n"
    )
    declared = declared_classes(tmp_path)
    assert [name for _, name, _ in declared] == ["x.Port", "x.R", "x.R$Ok", "x.Helper", "x.Busy"]
    assert unlisted_with_code(declared, names=["x.R$Ok"]) == ["x.Helper", "x.Busy"]
