"""Run: python -m unittest discover -s tests -v (from app/backend).

Real LanceDB + Docling text parsing tests use deterministic local embeddings.
No production database, model keys or external model calls are used.
"""
import asyncio
import hashlib
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
from ingestion import IngestionEndpoint
from starlette.applications import Starlette
from starlette.routing import Route

from haiku.rag.config.models import AppConfig


class EndpointTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.document = SimpleNamespace(id="rag-1", metadata={})
        self.client = SimpleNamespace(
            get_document_by_uri=AsyncMock(return_value=None),
            create_document_from_source=AsyncMock(return_value=self.document),
            update_document=AsyncMock(),
            chunk_repository=SimpleNamespace(get_by_document_id=AsyncMock(return_value=[object()])))
        self.get_client = AsyncMock(return_value=self.client)
        self.endpoint = IngestionEndpoint(self.get_client, AppConfig(), max_bytes=1024)
        self.converter = patch("ingestion.get_converter", return_value=SimpleNamespace(supported_extensions={".md", ".yaml", ".pdf"}))
        self.converter.start()
        self.http = httpx.AsyncClient(transport=httpx.ASGITransport(app=Starlette(routes=[
            Route("/ingest", self.endpoint.handle, methods=["POST"])])), base_url="http://test")

    async def asyncTearDown(self):
        self.converter.stop()
        await self.http.aclose()

    async def upload(self, data=b"# notes", name="notes.md", **changes):
        fields = dict(documentId="1", libraryId="2", namespace="tests", originalFilename=name,
                      sha256=hashlib.sha256(data).hexdigest())
        fields.update(changes)
        return await self.http.post("/ingest", data=fields, files={"file": (name, data)})

    async def test_import_preserves_identity_and_cleans_temporary_file(self):
        seen = []
        async def convert(path, **kwargs):
            seen.append(path)
            self.assertEqual(path.read_bytes(), b"# notes")
            self.assertEqual(kwargs["metadata"]["library_id"], 2)
            self.assertFalse(kwargs["metadata"]["business_index_complete"])
            return self.document
        self.client.create_document_from_source.side_effect = convert
        response = await self.upload()
        self.assertEqual(response.json()["status"], "INDEXED")
        self.assertEqual(response.json()["documentId"], 1)
        self.assertFalse(seen[0].exists())
        self.assertTrue(self.client.update_document.call_args.kwargs["metadata"]["business_index_complete"])

    async def test_unsupported_is_not_a_parser_failure(self):
        response = await self.upload(name="weights.unknown", data=b"\x00\xff")
        self.assertEqual(response.json()["status"], "UNSUPPORTED")
        self.get_client.assert_not_awaited()

    async def test_rejects_malformed_request_hash_mismatch_and_oversize(self):
        self.assertEqual((await self.upload(name="../notes.md")).status_code, 400)
        self.assertEqual((await self.upload(documentId="0")).status_code, 400)
        self.assertEqual((await self.upload(sha256="a" * 64)).json()["errorCode"], "HASH_MISMATCH")
        self.assertEqual((await self.upload(data=b"a" * 1025)).status_code, 413)
        self.assertEqual((await self.upload(data=b"a" * 70000)).status_code, 413)
        self.get_client.assert_not_awaited()

    async def test_completed_retries_skip_conversion_but_incomplete_import_is_repaired(self):
        self.document.metadata = dict(business_namespace="tests", library_id=2, business_document_id=1,
                                     business_sha256=hashlib.sha256(b"# notes").hexdigest(),
                                     original_filename="notes.md", business_index_complete=True)
        self.client.get_document_by_uri.return_value = self.document
        self.assertEqual((await self.upload()).json()["status"], "INDEXED")
        self.client.create_document_from_source.assert_not_awaited()
        self.document.metadata["business_index_complete"] = False
        self.assertEqual((await self.upload()).json()["status"], "INDEXED")
        self.assertTrue(self.client.create_document_from_source.call_args.kwargs["force"])

    async def test_permanent_and_transient_errors_are_distinct_and_do_not_leak_details(self):
        for error, retry in [(ValueError("secret-path-or-key"), False),
                             (httpx.ConnectError("secret-path-or-key"), True)]:
            self.client.create_document_from_source.side_effect = error
            response = await self.upload()
            self.assertEqual(response.json()["status"], "FAILED")
            self.assertEqual(response.json()["retryable"], retry)
            self.assertNotIn("secret-path-or-key", response.text)

    async def test_empty_content_does_not_become_ready(self):
        self.client.chunk_repository.get_by_document_id.return_value = []
        response = await self.upload()
        self.assertEqual(response.json()["errorCode"], "EMPTY_CONTENT")
        self.client.update_document.assert_not_awaited()


class RealIndexTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from haiku.rag.client import HaikuRAG
        from haiku.rag.embeddings import EmbedderWrapper

        class LocalEmbeddings(EmbedderWrapper):
            def __init__(self):
                super().__init__(None, 8)
            async def _embed_documents(self, texts):
                return [[(x + 1) / 256 for x in hashlib.sha256(text.encode()).digest()[:8]] for text in texts]
            async def embed_query(self, text):
                return (await self._embed_documents([text]))[0]

        self.directory = tempfile.TemporaryDirectory(prefix="rag-index-test-")
        self.config = AppConfig()
        self.config.processing.chunker_type = "hierarchical"
        self.config.processing.pictures = "none"
        self.config.storage.auto_vacuum = False
        self.config.embeddings.model.vector_dim = 8
        self.patch = patch("haiku.rag.store.engine.get_embedder", return_value=LocalEmbeddings())
        self.patch.start()
        self.rag = HaikuRAG(Path(self.directory.name) / "test.lancedb", config=self.config, create=True)
        await self.rag.__aenter__()
        self.endpoint = IngestionEndpoint(AsyncMock(return_value=self.rag), self.config)
        self.http = httpx.AsyncClient(transport=httpx.ASGITransport(app=Starlette(routes=[
            Route("/ingest", self.endpoint.handle, methods=["POST"])])), base_url="http://test")

    async def asyncTearDown(self):
        await self.http.aclose()
        await self.rag.__aexit__(None, None, None)
        self.patch.stop()
        self.directory.cleanup()

    async def upload(self, library, doc, name, data):
        return await self.http.post("/ingest", data=dict(documentId=str(doc), libraryId=str(library), namespace="tests",
                                    originalFilename=name, sha256=hashlib.sha256(data).hexdigest()), files={"file": (name, data)})

    async def test_real_markdown_yaml_and_scoped_search_with_idempotent_retries(self):
        data = b"# Reproduction\nThe ALPHA731 experiment uses learning rate 0.001."
        one = (await self.upload(1, 1, "paper.md", data)).json()
        self.assertEqual(one["status"], "INDEXED", one)
        repeated = await asyncio.gather(self.upload(1, 1, "paper.md", data), self.upload(1, 1, "paper.md", data))
        self.assertTrue(all(item.json()["ragDocumentId"] == one["ragDocumentId"] for item in repeated))
        two = (await self.upload(2, 2, "config.yaml", b"experiment: BETA942\nlearning_rate: 0.1")).json()
        self.assertEqual(two["status"], "INDEXED", two)
        self.assertEqual(await self.rag.count_documents(), 2)
        result = await self.rag.search("learning", filter="uri LIKE 'paper-assistant://tests/libraries/1/%'", search_type="vector")
        self.assertTrue(result)
        self.assertTrue(all(item.document_id == one["ragDocumentId"] for item in result))
        self.assertNotEqual(one["ragDocumentId"], two["ragDocumentId"])

    async def test_incomplete_marker_is_reprocessed_without_duplicate_documents(self):
        data = b"# Incomplete import\nThe RECOVERY537 value is 31."
        first = (await self.upload(1, 1, "recovery.md", data)).json()
        self.assertEqual(first["status"], "INDEXED", first)
        stored = await self.rag.get_document_by_id(first["ragDocumentId"])
        await self.rag.update_document(stored.id, metadata={**stored.metadata, "business_index_complete": False})
        second = (await self.upload(1, 1, "recovery.md", data)).json()
        self.assertEqual(first["ragDocumentId"], second["ragDocumentId"])
        self.assertEqual(await self.rag.count_documents(), 1)
        self.assertTrue((await self.rag.get_document_by_id(stored.id)).metadata["business_index_complete"])


if __name__ == "__main__":
    unittest.main()
