import copy
import json
import unittest
from unittest.mock import patch

import httpx
from starlette.applications import Starlette
from starlette.routing import Route

from business_chat import BusinessChatEndpoint
from chat_test_support import parse_sse, test_agent_factory
import test_business_chat as fixtures


class ConversationChatTests(unittest.IsolatedAsyncioTestCase):
    upload = fixtures.BusinessChatTests.upload
    payload = fixtures.BusinessChatTests.payload

    async def asyncSetUp(self):
        await fixtures.BusinessChatTests.asyncSetUp(self)
        self.endpoint = BusinessChatEndpoint(self.chat.get_client, self.config, test_agent_factory, persistent=True)
        self.conversation_http = self.new_http(self.endpoint)

    def new_http(self, endpoint):
        return httpx.AsyncClient(transport=httpx.ASGITransport(app=Starlette(routes=[
            Route("/chat", endpoint.handle, methods=["POST"])])), base_url="http://test")

    async def asyncTearDown(self):
        await self.conversation_http.aclose()
        await fixtures.BusinessChatTests.asyncTearDown(self)

    async def ask(self, checkpoint=None, question="Remember MEMORY731; learning rate?", **overrides):
        body = {**self.payload(question=question), "conversationId": 15, "checkpoint": checkpoint, **overrides}
        response = await self.conversation_http.post("/chat", json=body)
        self.assertEqual(response.status_code, 200, response.text)
        events = parse_sse(response.text)
        self.assertEqual(events[-1][0], "done", events[-1])
        saved = next(value for event, value in events if event == "checkpoint")
        return saved, events

    async def test_restored_history_survives_fresh_endpoint_and_compaction_preserves_original_messages(self):
        first, _ = await self.ask()
        before = copy.deepcopy(first)
        # New endpoint and JSON roundtrip mimic a new Python process restoring PostgreSQL data.
        await self.conversation_http.aclose()
        self.conversation_http = self.new_http(BusinessChatEndpoint(self.chat.get_client, self.config, test_agent_factory, persistent=True))
        from haiku.rag.capabilities import compaction
        with patch.object(compaction, "compact_history", wraps=compaction.compact_history) as compact:
            second, events = await self.ask(json.loads(json.dumps(first)), "expect-remember: what is its learning rate?")
            self.assertTrue(compact.called)
            self.assertGreater(compact.call_args.kwargs["boundary"], 0)
        self.assertEqual(first, before)
        self.assertEqual(second["messages"][:len(first["messages"])], first["messages"])
        self.assertGreater(len(second["messages"]), len(first["messages"]))
        answer = next(v for e, v in events if e == "answer")
        self.assertEqual({c["documentId"] for c in answer["citations"]}, {1})
        self.assertNotIn("BETA942", answer["answer"])

    async def test_scope_conversation_version_and_evidence_mismatch_fail_closed(self):
        first, _ = await self.ask()
        bodies = [dict(conversationId=16), dict(documents=[self.three])]
        for overrides in bodies:
            response = await self.conversation_http.post("/chat", json={**self.payload(), "conversationId": 15, "checkpoint": first, **overrides})
            self.assertEqual(response.status_code, 400)
        for mutate in [lambda c: c.update(version=2), lambda c: c.update(state={}),
                       lambda c: c["state"]["rag"]["evidence"].update(in_progress=True),
                       lambda c: next(iter(c["state"]["rag"]["citation_index"].values())).update(document_id=self.two["ragDocumentId"])]:
            bad = copy.deepcopy(first)
            mutate(bad)
            response = await self.conversation_http.post("/chat", json={**self.payload(), "conversationId": 15, "checkpoint": bad})
            self.assertEqual(response.status_code, 400, response.text)

    async def test_failure_emits_no_checkpoint_and_original_checkpoint_can_continue(self):
        first, _ = await self.ask()
        response = await self.conversation_http.post("/chat", json={**self.payload(question="simulate-error"), "conversationId": 15, "checkpoint": first})
        events = parse_sse(response.text)
        self.assertEqual(events[-1][0], "error")
        self.assertFalse(any(e == "checkpoint" for e, _ in events))
        await self.ask(first, "expect-remember: continue after failed attempt")

    async def test_refusal_checkpoint_contains_user_visible_final_answer(self):
        checkpoint, events = await self.ask(question="missing-fact")
        answer = next(value for event, value in events if event == "answer")["answer"]
        self.assertEqual(checkpoint["messages"][-1]["parts"][0]["content"], answer)
        self.assertEqual(checkpoint["state"]["rag"]["evidence"]["in_progress"], False)
