import asyncio
import hashlib
import tempfile
import unittest
from dataclasses import fields
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx
from pydantic_ai import ModelRetry
from starlette.applications import Starlette
from starlette.routing import Route

from business_chat import BusinessChatEndpoint, REFUSAL, ScopedRAGCapability
from chat_test_support import parse_sse, test_agent_factory
from ingestion import IngestionEndpoint
from ingestion_server import LocalEmbeddings
from haiku.rag.capabilities.rag import RAGState, create_capability
from haiku.rag.client import HaikuRAG
from haiku.rag.config.models import AppConfig


class BusinessChatTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="rag-chat-test-")
        self.config = AppConfig()
        self.config.processing.chunker_type = "hierarchical"
        self.config.processing.pictures = "none"
        self.config.storage.auto_vacuum = False
        self.config.embeddings.model.vector_dim = 8
        self.patch = patch("haiku.rag.store.engine.get_embedder", return_value=LocalEmbeddings())
        self.patch.start()
        self.rag = HaikuRAG(Path(self.directory.name) / "test.lancedb", config=self.config, create=True)
        await self.rag.__aenter__()
        get_client = AsyncMock(return_value=self.rag)
        self.chat = BusinessChatEndpoint(get_client, self.config, test_agent_factory, timeout=10)
        self.ingestion = IngestionEndpoint(get_client, self.config)
        self.http = httpx.AsyncClient(transport=httpx.ASGITransport(app=Starlette(routes=[
            Route("/ingest", self.ingestion.handle, methods=["POST"]),
            Route("/chat", self.chat.handle, methods=["POST"])])), base_url="http://test")
        self.one = await self.upload(1, 1, b"# ALPHA731\nThe learning rate for ALPHA731 is 0.001.")
        self.two = await self.upload(2, 2, b"# BETA942\nThe learning rate for BETA942 is 0.9.")
        self.three = await self.upload(1, 3, b"# GAMMA537\nThe learning rate for GAMMA537 is 0.04.")

    async def asyncTearDown(self):
        await self.http.aclose()
        await self.rag.__aexit__(None, None, None)
        self.patch.stop()
        self.directory.cleanup()

    async def upload(self, library, doc_id, data):
        sha = hashlib.sha256(data).hexdigest()
        result = await self.http.post("/ingest", data={"namespace": "tests", "libraryId": str(library),
            "documentId": str(doc_id), "sha256": sha, "originalFilename": f"paper{doc_id}.md"},
            files={"file": ("paper.md", data)})
        self.assertEqual(result.json()["status"], "INDEXED", result.text)
        return {"documentId": doc_id, "ragDocumentId": result.json()["ragDocumentId"], "sha256": sha}

    def payload(self, documents=None, library=1, question="learning rate"):
        return {"namespace": "tests", "libraryId": library, "question": question,
                "documents": documents if documents is not None else [self.one]}

    async def answer(self, payload):
        response = await self.http.post("/chat", json=payload)
        self.assertEqual(response.status_code, 200, response.text)
        events = parse_sse(response.text)
        self.assertEqual(events[-1], ("done", {"status": "COMPLETED"}), events)
        return next(data for name, data in events if name == "answer"), events

    async def test_single_document_scope_and_citations_exclude_same_library_and_foreign_docs(self):
        answer, events = await self.answer(self.payload())
        self.assertIn("ALPHA731", answer["answer"])
        self.assertNotIn("BETA942", str(events))
        self.assertNotIn("GAMMA537", str(events))
        self.assertEqual({c["documentId"] for c in answer["citations"]}, {1})
        self.assertTrue(any(name == "delta" for name, _ in events))
        self.assertTrue(any(data.get("phase") == "SEARCHING" for _, data in events))

    async def test_parallel_requests_keep_different_scopes_and_citation_indexes(self):
        answers = await asyncio.gather(self.answer(self.payload([self.one, self.three])),
                                       self.answer(self.payload([self.two], library=2)))
        self.assertEqual({c["documentId"] for c in answers[0][0]["citations"]}, {1, 3})
        self.assertEqual({c["documentId"] for c in answers[1][0]["citations"]}, {2})
        self.assertNotIn("BETA942", answers[0][0]["answer"])
        self.assertNotIn("ALPHA731", answers[1][0]["answer"])

    async def test_scope_identity_mismatch_and_untrusted_state_are_rejected(self):
        for body in [self.payload([self.two]), self.payload([]), {**self.payload(), "state": {"rag": {}}},
                     {**self.payload(), "question": " "}, self.payload([self.one, self.one])]:
            response = await self.http.post("/chat", json=body)
            self.assertIn(response.status_code, {400, 409}, response.text)
            self.assertNotIn("event: delta", response.text)
        await self.rag.update_document(self.one["ragDocumentId"], metadata={"business_index_complete": False})
        self.assertEqual((await self.http.post("/chat", json=self.payload())).status_code, 409)

    async def test_unknown_citation_cannot_trigger_global_chunk_lookup(self):
        base = create_capability(config=self.config, rag=self.rag, defer_loading=False)
        cap = ScopedRAGCapability(**{f.name: getattr(base, f.name) for f in fields(base)},
                                  allowed_ids=frozenset({self.one["ragDocumentId"]}))
        cap.state = RAGState()
        foreign = (await self.rag.chunk_repository.get_by_document_id(self.two["ragDocumentId"]))[0]
        with patch.object(cap, "_ensure_rag", new_callable=AsyncMock) as lookup:
            with self.assertRaises(ModelRetry):
                await cap._cite([foreign.id])
            lookup.assert_not_awaited()

    async def test_missing_evidence_has_explicit_refusal_and_no_citations(self):
        answer, _ = await self.answer(self.payload(question="missing-fact"))
        self.assertEqual(answer, {"answer": REFUSAL, "citations": [], "outcome": "INSUFFICIENT_EVIDENCE"})

    async def test_model_failure_and_timeout_end_with_safe_error(self):
        response = await self.http.post("/chat", json=self.payload(question="simulate-error"))
        events = parse_sse(response.text)
        self.assertEqual(events[-1][1]["code"], "RAG_CHAT_FAILED")
        self.assertNotIn("private-token", response.text)
        self.assertNotIn("event: done", response.text)
        self.chat.timeout = 0.1
        response = await self.http.post("/chat", json=self.payload(question="simulate-timeout"))
        self.assertEqual(parse_sse(response.text)[-1][1]["code"], "RAG_TIMEOUT")


if __name__ == "__main__":
    unittest.main()
