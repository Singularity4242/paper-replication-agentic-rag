"""Isolated E2E fixture: real parsing/LanceDB, deterministic test embeddings.

Only launched by verify_ingestion.py, never registered in the production app.
"""
import hashlib
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path
from unittest.mock import patch

from ingestion import IngestionEndpoint
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from haiku.rag.client import HaikuRAG
from haiku.rag.config.models import AppConfig
from haiku.rag.embeddings import EmbedderWrapper


class LocalEmbeddings(EmbedderWrapper):
    def __init__(self):
        super().__init__(None, 8)

    async def _embed_documents(self, texts):
        return [[(x + 1) / 256 for x in hashlib.sha256(text.encode()).digest()[:8]] for text in texts]

    async def embed_query(self, text):
        return (await self._embed_documents([text]))[0]


config = AppConfig()
config.processing.chunker_type = "hierarchical"
config.processing.pictures = "none"
config.storage.auto_vacuum = False
config.embeddings.model.vector_dim = 8
client = None


async def get_client():
    return client


@asynccontextmanager
async def lifespan(app):
    global client
    with tempfile.TemporaryDirectory(prefix="rag-e2e-") as directory:
        with patch("haiku.rag.store.engine.get_embedder", return_value=LocalEmbeddings()):
            async with HaikuRAG(Path(directory) / "test.lancedb", config=config, create=True) as rag:
                client = rag
                yield
    client = None


async def verify(request):
    library = int(request.path_params["library"])
    results = await client.search("learning", search_type="vector",
                                  filter=f"uri LIKE 'paper-assistant://ingestion-tests/libraries/{library}/%'")
    return JSONResponse({"count": await client.count_documents(),
                         "results": [{"ragDocumentId": result.document_id, "content": result.content} for result in results]})


async def health(request):
    return JSONResponse({"status": "UP"})


endpoint = IngestionEndpoint(get_client, config)
app = Starlette(routes=[Route("/internal/documents/ingest", endpoint.handle, methods=["POST"]),
                        Route("/verify/{library:int}", verify), Route("/health", health)], lifespan=lifespan)
