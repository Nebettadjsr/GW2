from __future__ import annotations

import json
import shutil
import subprocess
import unittest
from pathlib import Path

from local_bridge.stage_result_contract import NORMALIZER_JS, REQUIRED_FIELDS

WORKFLOW = Path(__file__).parent / "workflows/GW2-Development-Pipeline.json"
SHA = "23a6d90aefcb4235ab76f7138e7b257dc1d0b327"
STORY = "STORY-WEB-028"
QA_CONTRACT = "b" * 64

NODE_HARNESS = r'''const fs=require("fs");
const wf=JSON.parse(fs.readFileSync(0,"utf8"))[0];
const codes=Object.fromEntries(wf.nodes.filter(n=>n.name.startsWith("Normalize")).map(n=>[n.name,n.parameters.jsCode]));
const get=(name, upstream={})=>{
 const input={first:()=>({json:upstream.__input??{}})};
 const $=node=>{if(!(node in upstream))throw new Error("node not available: "+node);return {first:()=>({json:upstream[node]})};};
 return new Function("$input","$",codes[name])(input,$)[0].json;
};
const call=(x)=>{try{return get(x.node,x.upstream)}catch(e){return {__error:String(e)}}};
const requests=JSON.parse(process.argv[1]);
process.stdout.write(JSON.stringify(requests.map(call)));
'''


def invoke(requests):
    node = shutil.which("node")
    if not node:
        raise unittest.SkipTest("Node.js is required to exercise the exported n8n Code nodes")
    exported = json.loads(WORKFLOW.read_text(encoding="utf-8"))
    run = subprocess.run(
        [node, "-e", NODE_HARNESS, json.dumps(requests)],
        input=json.dumps(exported), text=True, capture_output=True, check=True,
    )
    result = json.loads(run.stdout)
    for item in result:
        if "__error" in item:
            raise AssertionError(item["__error"])
    return result


def qa_payload(**overrides):
    data = {
        "story_id": STORY, "qa_status": "READY", "verified_qa_status": "READY", "decision": "READY",
        "task_status": "completed", "exit_code": 0, "implementation_permitted": True,
        "story_contract_sha256": QA_CONTRACT, "verified_story_contract_sha256": QA_CONTRACT,
        "outstanding_requirements": [], "error": None,
        "submission_disposition": "cached_result", "record_source": "persisted_bridge_task",
        "operation_source": "local_bridge_http", "task_id": "qa-task",
    }
    data.update(overrides)
    return data


def prior(stage="selection"):
    return {"pipeline_summary": [{"stage": "selection", "story_id": STORY}]}


def current_parent(upstream, stage):
    upstream = dict(upstream)
    upstream.setdefault("Normalize Select and Activate Story", prior())
    upstream.setdefault("Normalize Existing Active Story", {
        "pipeline_summary": [{"stage": "selection", "story_id": STORY}]
    })
    return {"node": "Normalize Prepare QA", "upstream": upstream}


class ParentResumeTests(unittest.TestCase):
    def test_cached_ready_and_fresh_ready_reuse_or_run(self):
        cached, fresh, no_tests = invoke([
            current_parent({"__input": qa_payload()}, "qa_preparation"),
            current_parent({"__input": qa_payload(submission_disposition="created", record_source="persisted_bridge_task")}, "qa_preparation"),
            current_parent({"__input": qa_payload(qa_status="NO_TESTS_NEEDED", verified_qa_status="NO_TESTS_NEEDED", decision="NO_TESTS_NEEDED", submission_disposition="created", implementation_permitted=True)}, "qa_preparation"),
        ])
        self.assertTrue(cached["pipeline_continue"])
        self.assertEqual("REUSED", cached["pipeline_summary"][-1]["execution"])
        self.assertTrue(fresh["pipeline_continue"])
        self.assertEqual("RUN", fresh["pipeline_summary"][-1]["execution"])
        self.assertTrue(no_tests["pipeline_continue"])
        self.assertEqual("SKIPPED", no_tests["pipeline_summary"][-1]["execution"])

    def test_web020_fresh_qa_ready_child_result_continues(self):
        # Mirrors GW2 - Prepare QA's emitted result after a created task completes.
        child_output = {
            "story_id": "STORY-WEB-020", "task_id": "de8d6c79-ae16-4ce4-99bd-cc606c200e79",
            "task_status": "completed", "exit_code": 0, "qa_status": "READY", "decision": "READY",
            "verified_qa_status": "READY", "implementation_permitted": True,
            "story_contract_sha256": QA_CONTRACT, "verified_story_contract_sha256": QA_CONTRACT,
            "outstanding_requirements": [], "error": "", "submission_disposition": "created",
            "record_source": "persisted_bridge_task", "operation_source": "local_bridge_http",
        }
        request = {"node": "Normalize Prepare QA", "upstream": {
            "__input": child_output,
            "Normalize Select and Activate Story": {"pipeline_summary": [{"stage": "selection", "story_id": "STORY-WEB-020"}]},
        }}
        result = invoke([request])[0]
        self.assertTrue(result["pipeline_continue"], result)
        self.assertEqual("RUN", result["pipeline_summary"][-1]["execution"])
        self.assertEqual("READY", result["pipeline_summary"][-1]["status"])
        self.assertEqual("de8d6c79-ae16-4ce4-99bd-cc606c200e79", result["pipeline_summary"][-1]["task_id"])

    def test_qa_requires_matching_current_contract_and_success_fields(self):
        valid = qa_payload(submission_disposition="created")
        stale_fingerprint = {**valid, "verified_story_contract_sha256": "c" * 64}
        missing_fingerprint = {k: v for k, v in valid.items() if k != "story_contract_sha256"}
        missing_exit = {k: v for k, v in valid.items() if k != "exit_code"}
        results = invoke([
            current_parent({"__input": stale_fingerprint}, "qa_preparation"),
            current_parent({"__input": missing_fingerprint}, "qa_preparation"),
            current_parent({"__input": missing_exit}, "qa_preparation"),
            current_parent({"__input": {**valid, "decision": "NEEDS_USER", "outstanding_requirements": ["clarification"]}}, "qa_preparation"),
        ])
        for result in results:
            self.assertFalse(result["pipeline_continue"])
            self.assertEqual("STOPPED", result["pipeline_summary"][-1]["execution"])

    def test_no_tests_needed_is_skipped_and_stale_or_blocked_qa_stops(self):
        skipped, stale, retry, needs_user, failed = invoke([
            current_parent({"__input": qa_payload(qa_status="NO_TESTS_NEEDED", verified_qa_status="NO_TESTS_NEEDED", decision="NO_TESTS_NEEDED")}, "qa_preparation"),
            current_parent({"__input": qa_payload(story_id="STORY-WEB-999")}, "qa_preparation"),
            current_parent({"__input": qa_payload(qa_status="RETRY", verified_qa_status="RETRY", submission_disposition="created", implementation_permitted=True)}, "qa_preparation"),
            current_parent({"__input": qa_payload(qa_status="NEEDS_USER", verified_qa_status="NEEDS_USER", submission_disposition="created", implementation_permitted=True)}, "qa_preparation"),
            current_parent({"__input": qa_payload(task_status="failed", qa_status="TECHNICAL_FAILURE", verified_qa_status="TECHNICAL_FAILURE")}, "qa_preparation"),
        ])
        self.assertTrue(skipped["pipeline_continue"])
        self.assertEqual("SKIPPED", skipped["pipeline_summary"][-1]["execution"])
        for result in (stale, retry, needs_user, failed):
            self.assertFalse(result["pipeline_continue"])
            self.assertEqual("STOPPED", result["pipeline_summary"][-1]["execution"])

    def test_completed_implementation_is_reused_and_wrong_story_stops(self):
        reused, stale, stale_contract, fresh = invoke([
            {"node": "Normalize Implement Active Story", "upstream": {"__input": {"status": "blocked", "preparation_status": "implementation_already_completed", "story_id": STORY, "task_id": "impl-task", "implementation_task_id": "impl-task", "implementation_task_contract_sha256": "c"*64, "implementation_contract_sha256": "c"*64, "operation_source": "local_bridge_http"}, "Normalize Prepare QA": prior()}},
            {"node": "Normalize Implement Active Story", "upstream": {"__input": {"status": "blocked", "preparation_status": "implementation_already_completed", "story_id": "STORY-WEB-999", "task_id": "impl-task", "implementation_task_id": "impl-task", "implementation_task_contract_sha256": "c"*64, "implementation_contract_sha256": "c"*64, "operation_source": "local_bridge_http"}, "Normalize Prepare QA": prior()}},
            {"node": "Normalize Implement Active Story", "upstream": {"__input": {"status": "blocked", "preparation_status": "implementation_already_completed", "story_id": STORY, "task_id": "impl-task", "implementation_task_id": "impl-task", "implementation_task_contract_sha256": "c"*64, "implementation_contract_sha256": "d"*64, "operation_source": "local_bridge_http"}, "Normalize Prepare QA": prior()}},
            {"node": "Normalize Implement Active Story", "upstream": {"__input": {"status": "completed", "story_id": STORY, "task_id": "new-impl-task", "exit_code": "0", "record_source": "persisted_bridge_task"}, "Normalize Prepare QA": prior()}},
        ])
        self.assertTrue(reused["pipeline_continue"])
        self.assertEqual("REUSED", reused["pipeline_summary"][-1]["execution"])
        self.assertEqual("COMPLETED", reused["pipeline_summary"][-1]["status"])
        self.assertEqual("impl-task", reused["pipeline_summary"][-1]["task_id"])
        self.assertIsNone(reused["pipeline_summary"][-1]["error"])
        self.assertEqual("", reused["pipeline_summary"][-1]["reason"])
        self.assertFalse(stale["pipeline_continue"])
        self.assertFalse(stale_contract["pipeline_continue"])
        self.assertEqual("STOPPED", stale_contract["pipeline_summary"][-1]["execution"])
        self.assertTrue(fresh["pipeline_continue"])
        self.assertEqual("RUN", fresh["pipeline_summary"][-1]["execution"])

    def test_qa_approve_and_evaluator_complete_are_contract_bound(self):
        review = {"story_id": STORY, "task_status": "completed", "decision": "APPROVE", "exit_code": 0, "outstanding_requirements": [], "operation_source": "local_bridge_http", "record_source": "persisted_bridge_task", "submission_disposition": "idempotent_replay"}
        evaluator = {"story_id": STORY, "task_status": "completed", "decision": "COMPLETE", "exit_code": 0, "evaluator_exit_code": 0, "evaluation_contract_sha256": "f"*64, "status": "COMPLETE", "operation_source": "local_bridge_http", "record_source": "persisted_bridge_task", "submission_disposition": "idempotent_replay", "qa_review_required": True, "unmet_intent": [], "unmet_acceptance_criteria": [], "unmet_definition_of_done": [], "actionable_items": []}
        approve, retry, complete, stale = invoke([
            {"node": "Normalize Review Implementation", "upstream": {"__input": review, "Normalize Implement Active Story": prior()}},
            {"node": "Normalize Review Implementation", "upstream": {"__input": {**review, "decision": "RETRY"}, "Normalize Implement Active Story": prior()}},
            {"node": "Normalize Evaluate Implementation", "upstream": {"__input": evaluator, "Normalize Review Implementation": prior()}},
            {"node": "Normalize Evaluate Implementation", "upstream": {"__input": {**evaluator, "evaluation_contract_sha256": ""}, "Normalize Review Implementation": prior()}},
        ])
        self.assertTrue(approve["pipeline_continue"])
        self.assertEqual("REUSED", approve["pipeline_summary"][-1]["execution"])
        self.assertFalse(retry["pipeline_continue"])
        self.assertTrue(complete["pipeline_continue"])
        self.assertEqual("REUSED", complete["pipeline_summary"][-1]["execution"])
        self.assertEqual("COMPLETE", complete["pipeline_summary"][-1]["status"])
        self.assertEqual("COMPLETE", complete["pipeline_summary"][-1]["decision"])
        self.assertEqual("", complete["pipeline_summary"][-1]["reason"])
        self.assertIsNone(complete["pipeline_summary"][-1]["error"])
        self.assertFalse(stale["pipeline_continue"])

    def test_published_commit_is_reused_and_final_ci_sha_must_match(self):
        published = {"story_id": STORY, "status": "ALREADY_PUBLISHED", "decision": "ALREADY_PUBLISHED", "commit_sha": SHA, "push_status": "verified", "error": None, "authorized_paths": ["frontend/src/account/BankScreen.vue", "frontend/src/crafting/CraftingResolution.vue"]}
        fresh_publication = {"story_id": STORY, "status": "PUBLISHED", "decision": "PUBLISHED", "commit_sha": SHA, "push_status": "verified", "error": None, "authorized_paths": ["src/Fixture.java"], "pipeline_continue": True}
        ci = {"story_id": STORY, "ci_status": "PASSED", "task_status": "completed", "commit_sha": SHA, "exit_code": 0, "failures": [], "final_ci_contract_sha256": "a"*64, "operation_source": "local_bridge_http", "record_source": "persisted_bridge_task", "submission_disposition": "idempotent_replay", "result_source": "github_actions_for_pushed_commit"}
        pub, fresh, passed, wrong_sha, failed, unverified, mismatched_alias, wrong_story = invoke([
            {"node": "Normalize Publication", "upstream": {"__input": published, "Normalize Evaluate Implementation": prior()}},
            {"node": "Normalize Publication", "upstream": {"__input": fresh_publication, "Normalize Evaluate Implementation": prior()}},
            {"node": "Normalize Run Final CI", "upstream": {"__input": ci, "Normalize Publication": {"commit_sha": SHA, "pipeline_summary": [{"stage": "selection", "story_id": STORY}]}}},
            {"node": "Normalize Run Final CI", "upstream": {"__input": {**ci, "commit_sha": "0"*40}, "Normalize Publication": {"commit_sha": SHA, "pipeline_summary": [{"stage": "selection", "story_id": STORY}]}}},
            {"node": "Normalize Run Final CI", "upstream": {"__input": {**ci, "ci_status": "FAILED"}, "Normalize Publication": {"commit_sha": SHA, "pipeline_summary": [{"stage": "selection", "story_id": STORY}]}}},
            {"node": "Normalize Run Final CI", "upstream": {"__input": {**ci, "ci_status": "UNVERIFIED"}, "Normalize Publication": {"commit_sha": SHA, "pipeline_summary": [{"stage": "selection", "story_id": STORY}]}}},
            {"node": "Normalize Publication", "upstream": {"__input": {**published, "sha": "0"*40}, "Normalize Evaluate Implementation": prior()}},
            {"node": "Normalize Publication", "upstream": {"__input": {**published, "story_id": "STORY-WEB-999"}, "Normalize Evaluate Implementation": prior()}},
        ])
        self.assertTrue(pub["pipeline_continue"])
        self.assertEqual("REUSED", pub["pipeline_summary"][-1]["execution"])
        self.assertEqual("", pub["pipeline_summary"][-1]["reason"])
        self.assertIsNone(pub["pipeline_summary"][-1]["error"])
        self.assertEqual(SHA, pub["commit_sha"])
        self.assertTrue(fresh["pipeline_continue"])
        self.assertEqual("RUN", fresh["pipeline_summary"][-1]["execution"])
        self.assertTrue(passed["pipeline_continue"])
        self.assertEqual("REUSED", passed["pipeline_summary"][-1]["execution"])
        self.assertEqual(SHA, passed["commit_sha"])
        for result in (wrong_sha, failed, unverified, mismatched_alias, wrong_story):
            self.assertFalse(result["pipeline_continue"])

    def test_publication_failure_statuses_stop(self):
        base = {"story_id": STORY, "commit_sha": SHA, "push_status": "verified", "authorized_paths": ["src/Fixture.java"]}
        results = invoke([
            {"node": "Normalize Publication", "upstream": {"__input": {**base, "status": status}, "Normalize Evaluate Implementation": prior()}}
            for status in ("FAILED", "BLOCKED", "TECHNICAL_FAILURE", "INTERRUPTED", "UNVERIFIED")
        ] + [
            {"node": "Normalize Publication", "upstream": {"__input": {**base, "status": "ALREADY_PUBLISHED", "error": {"message": "publication error"}}, "Normalize Evaluate Implementation": prior()}},
            {"node": "Normalize Publication", "upstream": {"__input": {**base, "status": "ALREADY_PUBLISHED", "push_status": "unverified"}, "Normalize Evaluate Implementation": prior()}},
        ])
        for result in results:
            self.assertFalse(result["pipeline_continue"])
            self.assertEqual("STOPPED", result["pipeline_summary"][-1]["execution"])

    def test_finalization_existing_result_is_reused(self):
        result = invoke([{"node": "Normalize Finalize Story", "upstream": {"__input": {"story_id": STORY, "finalization_status": "already_finalized", "commit_sha": "b"*40, "operation_source": "local_bridge_http"}, "Normalize Run Final CI": prior()}}])[0]
        self.assertTrue(result["pipeline_continue"])
        self.assertEqual("REUSED", result["pipeline_summary"][-1]["execution"])

    def test_parent_resume_reuses_all_completed_stages_and_passes_exact_sha(self):
        active = {"story": {"id": STORY, "title": "Browser workflow contracts", "filename": "story.md"}, "operation_source": "local_bridge_http"}
        activate = {"story_id": STORY, "activation_status": "already_active", "operation_source": "local_bridge_http"}
        qa = qa_payload()
        implementation = {"status": "blocked", "preparation_status": "implementation_already_completed", "story_id": STORY, "task_id": "impl-task", "implementation_task_id": "impl-task", "implementation_task_contract_sha256": "c"*64, "implementation_contract_sha256": "c"*64, "operation_source": "local_bridge_http"}
        review = {"story_id": STORY, "task_status": "completed", "decision": "APPROVE", "exit_code": 0, "outstanding_requirements": [], "operation_source": "local_bridge_http", "record_source": "persisted_bridge_task", "submission_disposition": "idempotent_replay"}
        evaluator = {"story_id": STORY, "task_status": "completed", "decision": "COMPLETE", "exit_code": 0, "evaluator_exit_code": 0, "evaluation_contract_sha256": "f"*64, "status": "COMPLETE", "operation_source": "local_bridge_http", "record_source": "persisted_bridge_task", "submission_disposition": "idempotent_replay", "qa_review_required": True, "unmet_intent": [], "unmet_acceptance_criteria": [], "unmet_definition_of_done": [], "actionable_items": []}
        published = {"story_id": STORY, "status": "ALREADY_PUBLISHED", "decision": "ALREADY_PUBLISHED", "commit_sha": SHA, "push_status": "verified", "error": None, "authorized_paths": ["frontend/src/account/BankScreen.vue", "frontend/src/crafting/CraftingResolution.vue"]}
        ci = {"story_id": STORY, "ci_status": "PASSED", "task_status": "completed", "commit_sha": SHA, "exit_code": 0, "failures": [], "final_ci_contract_sha256": "a"*64, "operation_source": "local_bridge_http", "record_source": "persisted_bridge_task", "submission_disposition": "idempotent_replay", "result_source": "github_actions_for_pushed_commit"}
        finalized = {"story_id": STORY, "finalization_status": "finalized", "commit_sha": "b"*40, "operation_source": "local_bridge_http"}
        requests = [
            {"node": "Normalize Existing Active Story", "upstream": {"__input": active}},
            {"node": "Normalize Select and Activate Story", "upstream": {"__input": activate, "Normalize Select Next Story": prior()}},
        {"node": "Normalize Prepare QA", "upstream": {"__input": qa, "Normalize Existing Active Story": prior()}},
            {"node": "Normalize Implement Active Story", "upstream": {"__input": implementation, "Normalize Prepare QA": prior()}},
            {"node": "Normalize Review Implementation", "upstream": {"__input": review, "Normalize Implement Active Story": prior()}},
            {"node": "Normalize Evaluate Implementation", "upstream": {"__input": evaluator, "Normalize Review Implementation": prior()}},
            {"node": "Normalize Publication", "upstream": {"__input": published, "Normalize Evaluate Implementation": prior()}},
            {"node": "Normalize Run Final CI", "upstream": {"__input": ci, "Normalize Publication": {"commit_sha": SHA, "pipeline_summary": [{"stage": "selection", "story_id": STORY}]}}},
            {"node": "Normalize Finalize Story", "upstream": {"__input": finalized, "Normalize Run Final CI": prior()}},
        ]
        results = invoke(requests)
        for result in results:
            self.assertTrue(result["pipeline_continue"], result)
        self.assertEqual("REUSED", results[0]["pipeline_summary"][0]["execution"])
        self.assertEqual("REUSED", results[0]["pipeline_summary"][1]["execution"])
        self.assertEqual("REUSED", results[2]["pipeline_summary"][-1]["execution"])
        self.assertEqual("REUSED", results[3]["pipeline_summary"][-1]["execution"])
        self.assertEqual("REUSED", results[4]["pipeline_summary"][-1]["execution"])
        self.assertEqual("REUSED", results[5]["pipeline_summary"][-1]["execution"])
        self.assertEqual("REUSED", results[6]["pipeline_summary"][-1]["execution"])
        self.assertEqual("REUSED", results[7]["pipeline_summary"][-1]["execution"])
        self.assertEqual(SHA, results[7]["commit_sha"])
        self.assertEqual("RUN", results[8]["pipeline_summary"][-1]["execution"])
        wf=json.loads(WORKFLOW.read_text(encoding="utf-8"))[0]
        node={n["name"]:n for n in wf["nodes"]}["Run Final Application CI"]
        self.assertIn("Normalize Publication", node["parameters"]["workflowInputs"]["value"]["published_commit_sha"])
        by_name={n["name"]:n for n in wf["nodes"]}
        self.assertEqual("Qf8h1s7K2m4B9c6D", by_name["Publish Implementation"]["parameters"]["workflowId"]["value"])
        self.assertEqual("REUSED", results[6]["pipeline_summary"][-1]["execution"])
        self.assertEqual(SHA, results[6]["commit_sha"])
        group=next(g for g in wf["nodeGroups"] if g["name"]=="Publication")
        self.assertEqual({by_name[x]["id"] for x in ["Publish Implementation","Normalize Publication","Publication Succeeded?"]}, set(group["nodeIds"]))

    def test_normalized_contract_new_story_happy_path_and_restart_table(self):
        story = "STORY-WEB-099"
        sha = "d" * 40
        contract = "e" * 64
        implementation_id = "implementation-new-story"
        publication_metadata = {
            "schema_version": 1, "task_id": implementation_id, "story_id": story,
            "baseline_head_sha": "f" * 40, "baseline_status_sha256": "1" * 64,
            "baseline_index_diff_sha256": "2" * 64, "baseline_worktree_diff_sha256": "3" * 64,
            "changed_paths": ["src/Fixture.java"], "changed_path_sha256": {"src/Fixture.java": "4" * 64},
            "ambiguous_baseline_paths": [], "protected_paths_restored": [], "lifecycle_paths_restored": [],
            "authorized_scope": {"story_path": "agent/stories/STORY-WEB-099.md", "qa_plan_path": "agent/qa-plans/QA-STORY-WEB-099.json"},
        }
        requests = [
            {"node": "Normalize Select Next Story", "upstream": {"__input": {"queue_empty": False, "story": {"id": story, "title": "New contract fixture"}, "operation_source": "local_bridge_http"}}},
            {"node": "Normalize Select and Activate Story", "upstream": {"__input": {"story_id": story, "activation_status": "activated", "operation_source": "local_bridge_http"}, "Normalize Select Next Story": None}},
            {"node": "Normalize Prepare QA", "upstream": {"__input": qa_payload(story_id=story, task_id="qa-new-story", submission_disposition="created", story_contract_sha256=contract, verified_story_contract_sha256=contract), "Normalize Select and Activate Story": None}},
            {"node": "Normalize Implement Active Story", "upstream": {"__input": {"status": "completed", "story_id": story, "task_id": implementation_id, "exit_code": 0, "publication_metadata": publication_metadata, "record_source": "persisted_bridge_task", "submission_disposition": "created"}, "Normalize Prepare QA": None}},
            {"node": "Normalize Review Implementation", "upstream": {"__input": {"story_id": story, "task_id": "review-new-story", "task_status": "completed", "decision": "APPROVE", "exit_code": 0, "outstanding_requirements": [], "operation_source": "local_bridge_http", "submission_disposition": "created"}, "Normalize Implement Active Story": None}},
            {"node": "Normalize Evaluate Implementation", "upstream": {"__input": {"story_id": story, "task_id": "evaluator-new-story", "task_status": "completed", "status": "COMPLETE", "decision": "COMPLETE", "exit_code": 0, "evaluation_contract_sha256": "5" * 64, "operation_source": "local_bridge_http", "submission_disposition": "created", "unmet_intent": [], "unmet_acceptance_criteria": [], "unmet_definition_of_done": [], "actionable_items": []}, "Normalize Review Implementation": None}},
            {"node": "Normalize Publication", "upstream": {"__input": {"story_id": story, "publication_status": "PUBLISHED", "commit_sha": sha, "push_status": "verified", "authorized_paths": ["src/Fixture.java", "agent/qa-plans/QA-STORY-WEB-099.json"], "publication_permitted": True, "pipeline_continue": True, "error": None}, "Normalize Evaluate Implementation": None}},
            {"node": "Normalize Run Final CI", "upstream": {"__input": {"story_id": story, "ci_status": "PASSED", "task_status": "completed", "task_id": "ci-new-story", "commit_sha": sha, "exit_code": 0, "failures": [], "final_ci_contract_sha256": "6" * 64, "operation_source": "local_bridge_http", "submission_disposition": "created"}, "Normalize Publication": {"story_id": story, "commit_sha": sha, "pipeline_summary": [{"stage": "selection", "story_id": story}]}}},
            {"node": "Normalize Finalize Story", "upstream": {"__input": {"story_id": story, "finalization_status": "finalized", "operation_source": "local_bridge_http"}, "Normalize Run Final CI": None}},
        ]
        results = []
        pipeline = []
        for request in requests:
            missing_prior = [k for k, value in request["upstream"].items() if k.startswith("Normalize ") and value is None]
            if missing_prior:
                request["upstream"][missing_prior[0]] = {"pipeline_summary": pipeline}
            result = invoke([request])[0]
            self.assertTrue(result["pipeline_continue"], result)
            results.append(result)
            pipeline = result["pipeline_summary"]
        self.assertEqual(["RUN"] * 9, [r["pipeline_summary"][-1]["execution"] for r in results])
        self.assertEqual(sha, results[7]["commit_sha"])
        self.assertEqual(publication_metadata["changed_paths"], ["src/Fixture.java"])
        for result in results:
            self.assertTrue(set(REQUIRED_FIELDS) <= set(result["pipeline_summary"][-1]))

        resumed = [
            ("selection_activation", {"node": "Normalize Existing Active Story", "upstream": {"__input": {"story": {"id": story}, "operation_source": "local_bridge_http"}}}, ("REUSED", "REUSED")),
            ("qa_preparation", {"node": "Normalize Prepare QA", "upstream": {"__input": qa_payload(story_id=story, task_id="qa-persisted", submission_disposition="cached_result"), "Normalize Existing Active Story": {"pipeline_summary": [{"stage": "selection", "story_id": story}]} }}, ("REUSED",)),
            ("implementation", {"node": "Normalize Implement Active Story", "upstream": {"__input": {"status": "blocked", "preparation_status": "implementation_already_completed", "story_id": story, "task_id": implementation_id, "implementation_task_id": implementation_id, "implementation_task_contract_sha256": contract, "implementation_contract_sha256": contract, "operation_source": "local_bridge_http"}, "Normalize Prepare QA": {"pipeline_summary": [{"stage": "selection", "story_id": story}]} }}, ("REUSED",)),
            ("qa_review", {"node": "Normalize Review Implementation", "upstream": {"__input": {"story_id": story, "task_status": "completed", "decision": "APPROVE", "exit_code": 0, "outstanding_requirements": [], "operation_source": "local_bridge_http", "submission_disposition": "idempotent_replay"}, "Normalize Implement Active Story": {"pipeline_summary": [{"stage": "selection", "story_id": story}]} }}, ("REUSED",)),
            ("evaluator", {"node": "Normalize Evaluate Implementation", "upstream": {"__input": {"story_id": story, "task_status": "completed", "status": "COMPLETE", "decision": "COMPLETE", "exit_code": 0, "evaluation_contract_sha256": "5" * 64, "operation_source": "local_bridge_http", "submission_disposition": "idempotent_replay", "unmet_intent": [], "unmet_acceptance_criteria": [], "unmet_definition_of_done": [], "actionable_items": []}, "Normalize Review Implementation": {"pipeline_summary": [{"stage": "selection", "story_id": story}]} }}, ("REUSED",)),
            ("publication", {"node": "Normalize Publication", "upstream": {"__input": {"story_id": story, "status": "ALREADY_PUBLISHED", "commit_sha": sha, "push_status": "verified", "authorized_paths": ["src/Fixture.java"], "publication_permitted": True}, "Normalize Evaluate Implementation": {"pipeline_summary": [{"stage": "selection", "story_id": story}]} }}, ("REUSED",)),
            ("final_ci", {"node": "Normalize Run Final CI", "upstream": {"__input": {"story_id": story, "ci_status": "PASSED", "task_status": "completed", "commit_sha": sha, "exit_code": 0, "failures": [], "final_ci_contract_sha256": "6" * 64, "operation_source": "local_bridge_http", "submission_disposition": "idempotent_replay"}, "Normalize Publication": {"commit_sha": sha, "pipeline_summary": [{"stage": "selection", "story_id": story}]} }}, ("REUSED",)),
            ("finalization", {"node": "Normalize Finalize Story", "upstream": {"__input": {"story_id": story, "finalization_status": "already_finalized", "operation_source": "local_bridge_http"}, "Normalize Run Final CI": {"pipeline_summary": [{"stage": "selection", "story_id": story}]} }}, ("REUSED",)),
        ]
        workflow = json.loads(WORKFLOW.read_text(encoding="utf-8"))[0]
        code_nodes = {n["name"]: n for n in workflow["nodes"] if n["name"].startswith("Normalize")}
        self.assertTrue(all(code_nodes[name]["parameters"]["jsCode"].startswith(NORMALIZER_JS) for name in code_nodes))
        for label, request, expected in resumed:
            with self.subTest(stage=label):
                result = invoke([request])[0]
                observed = tuple(entry["execution"] for entry in result["pipeline_summary"][-len(expected):])
                self.assertEqual(expected, observed)
                for entry in result["pipeline_summary"][-len(expected):]:
                    self.assertTrue(set(REQUIRED_FIELDS) <= set(entry))

    def test_stopped_result_keeps_recovery_fields_and_stage_execution_markers(self):
        wf = json.loads(WORKFLOW.read_text(encoding="utf-8"))[0]
        nodes = {n["name"]: n for n in wf["nodes"]}
        for name in ["Normalize Prepare QA", "Normalize Implement Active Story", "Normalize Review Implementation", "Normalize Evaluate Implementation", "Normalize Publication", "Normalize Run Final CI", "Normalize Finalize Story"]:
            self.assertIn('execution', nodes[name]["parameters"]["jsCode"])
        self.assertIn("stopped_stage", nodes["Return Stopped Pipeline Result"]["parameters"]["jsCode"])
        self.assertIn("raw.story_contract_sha256===raw.verified_story_contract_sha256", nodes["Normalize Prepare QA"]["parameters"]["jsCode"])
        # A completed WEB-028 path keeps the published SHA intact between publication and Final CI.
        self.assertIn("raw.commit_sha===publishedSha", nodes["Normalize Run Final CI"]["parameters"]["jsCode"])


if __name__ == "__main__":
    unittest.main()
