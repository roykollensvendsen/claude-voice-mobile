"""A newer push to a pull request stops that pull request's older runs; main always finishes.

The owner's rule: every workflow a pull request starts shares one group per workflow and ref,
and cancels a run in progress only when the run belongs to a pull request. A run on main, or
any other event, is never cut short. This reads every workflow in the tree.
"""

import pathlib
import re

import pytest

ROOT = pathlib.Path(__file__).parent.parent
WORKFLOWS = sorted((ROOT / ".github" / "workflows").glob("*.yml"))


def started_by_pull_request(workflow: str) -> bool:
    """Whether the workflow's `on:` names pull_request or pull_request_target, in any form.

    The `on:` section runs from its own line to the next line that starts at the margin, and a
    comment in it names nothing.
    """
    found = re.search(r"""^["']?on["']?:(.*\n(?:[ \t-].*\n|\s*\n)*)""", workflow, re.MULTILINE)
    if not found:
        return False
    section = re.sub(r"#.*", "", found.group(1))
    return bool(re.search(r"\bpull_request(?:_target)?\b", section))


PULL_REQUEST_WORKFLOWS = [w for w in WORKFLOWS if started_by_pull_request(w.read_text())]

GROUP = "group: ${{ github.workflow }}-${{ github.ref }}"
CANCEL = "cancel-in-progress: ${{ github.event_name == 'pull_request' }}"


def concurrency_block(workflow: str) -> str:
    """The lines of the top-level `concurrency:` block, or "" if there is none."""
    found = re.search(r"^concurrency:\n((?:  .*\n)+)", workflow, re.MULTILINE)
    return found.group(1) if found else ""


@pytest.mark.parametrize(
    "on",
    [
        "on:\n  pull_request:\n  push:\n",
        "on:\n  push:\n  pull_request:\n    branches: [main]\n",
        "on: pull_request\n",
        "on: [push, pull_request]\n",
        "on: [ pull_request ]\n",
        "on:\n  - push\n  - pull_request\n",
        "on:\n  pull_request_target:\n",
        '"on":\n  pull_request:\n',
    ],
    ids=["block", "block, second", "a word", "a list", "a spaced list", "a dash list", "target", "quoted on"],
)
def test_every_way_of_naming_pull_request_counts(on):
    assert started_by_pull_request(f"name: x\n{on}\njobs:\n")


@pytest.mark.parametrize(
    "on",
    [
        "on:\n  schedule:\n    - cron: '17 4 * * 1'\n  workflow_dispatch:\n",
        "on: [push]\n",
        "on:\n  push:\n    branches: [main]\n# pull_request: left out on purpose\n",
    ],
    ids=["schedule", "push only", "a comment"],
)
def test_a_workflow_without_pull_request_does_not_count(on):
    assert not started_by_pull_request(f"name: x\n{on}\njobs:\n")


def test_the_walk_finds_the_pull_request_workflow():
    assert [w.name for w in PULL_REQUEST_WORKFLOWS] == ["checks.yml"]


@pytest.mark.parametrize("workflow", PULL_REQUEST_WORKFLOWS, ids=lambda w: w.name)
def test_a_pull_request_workflow_cancels_only_pull_request_runs(workflow):
    block = concurrency_block(workflow.read_text())
    assert GROUP in block
    assert CANCEL in block
