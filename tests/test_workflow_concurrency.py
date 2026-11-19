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
PULL_REQUEST_WORKFLOWS = [w for w in WORKFLOWS if re.search(r"^  pull_request:", w.read_text(), re.MULTILINE)]

GROUP = "group: ${{ github.workflow }}-${{ github.ref }}"
CANCEL = "cancel-in-progress: ${{ github.event_name == 'pull_request' }}"


def concurrency_block(workflow: str) -> str:
    """The lines of the top-level `concurrency:` block, or "" if there is none."""
    found = re.search(r"^concurrency:\n((?:  .*\n)+)", workflow, re.MULTILINE)
    return found.group(1) if found else ""


def test_the_walk_finds_the_pull_request_workflow():
    assert [w.name for w in PULL_REQUEST_WORKFLOWS] == ["checks.yml"]


@pytest.mark.parametrize("workflow", PULL_REQUEST_WORKFLOWS, ids=lambda w: w.name)
def test_a_pull_request_workflow_cancels_only_pull_request_runs(workflow):
    block = concurrency_block(workflow.read_text())
    assert GROUP in block
    assert CANCEL in block
