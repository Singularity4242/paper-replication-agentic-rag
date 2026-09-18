"""Isolated cross-process chat fixture. Optional real providers, always temp data."""
import os
import tempfile
from contextlib import asynccontextmanager, nullcontext
from pathlib import Path
from unittest.mock import patch

from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from business_chat import BusinessChatEndpoint, make_agent
from chat_test_support import test_agent_factory
from ingestion import IngestionEndpoint
from ingestion_server import LocalEmbeddings
from haiku.rag.client import HaikuRAG
from haiku.rag.config import get_config
from haiku.rag.config.models import AppConfig

real = os.getenv("CHAT_TEST_REAL_MODELS") == "1"
config = get_config().model_copy(deep=True) if real else AppConfig()
config.lancedb.databases = {}
config.storage.auto_vacuum = False
config.processing.chunker_type = "hierarchical"
config.processing.pictures = "none"
config.processing.conversion_options.do_ocr = False
config.processing.conversion_options.do_table_structure = False
config.processing.auto_title = False
if not real:
    config.embeddings.model.vector_dim = 8
client = None


async def get_client():
    return client


@asynccontextmanager
async def lifespan(app):
    global client
    with tempfile.TemporaryDirectory(prefix="rag-chat-e2e-") as directory:
        embedder = nullcontext() if real else patch("haiku.rag.store.engine.get_embedder", return_value=LocalEmbeddings())
        with embedder:
            path = Path(os.getenv("CHAT_TEST_DATABASE_PATH", str(Path(directory) / "test.lancedb")))
            async with HaikuRAG(path, config=config, create=True) as rag:
                client = rag
                yield
    client = None


async def health(request):
    return JSONResponse({"status": "UP", "realModels": real})


ingestion = IngestionEndpoint(get_client, config)
chat = BusinessChatEndpoint(get_client, config, make_agent if real else test_agent_factory, timeout=120)
conversation_chat = BusinessChatEndpoint(get_client, config, make_agent if real else test_agent_factory, timeout=120, persistent=True)
app = Starlette(routes=[Route("/internal/documents/ingest", ingestion.handle, methods=["POST"]),
                        Route("/internal/conversations/chat/stream", conversation_chat.handle, methods=["POST"]),
                        Route("/internal/chat/stream", chat.handle, methods=["POST"]),
                        Route("/health", health)], lifespan=lifespan)
