"""Business document ingestion. Existing chat/agent capabilities are unchanged.

Run one ASGI process per local LanceDB writer. The lock serializes ingestion
requests; URI-based upserts and a completion marker make HTTP retries safe.
"""
import asyncio
import hashlib
import logging
import os
import re
import tempfile
from pathlib import Path

import httpx
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException
from starlette.formparsers import MultiPartException
from starlette.requests import Request
from starlette.responses import JSONResponse

from haiku.rag.converters import get_converter

logger = logging.getLogger(__name__)


class UploadTooLarge(Exception):
    pass


class IngestionEndpoint:
    def __init__(self, get_client, config, max_bytes=None):
        self.get_client = get_client
        self.config = config
        self.max_bytes = max_bytes or int(os.getenv("RAG_MAX_FILE_BYTES", "20971520"))
        self.lock = asyncio.Lock()

    async def handle(self, request: Request):
        received = 0

        async def limited_receive():
            nonlocal received
            message = await request.receive()
            received += len(message.get("body", b""))
            if received > self.max_bytes + 65536:
                raise MultiPartException("Upload too large")
            return message

        bounded = Request(request.scope, receive=limited_receive)
        try:
            async with bounded.form(max_files=1, max_fields=5, max_part_size=4096) as form:
                document_id = str(form.get("documentId", ""))
                library_id = str(form.get("libraryId", ""))
                namespace = str(form.get("namespace", ""))
                sha = str(form.get("sha256", ""))
                name = str(form.get("originalFilename", ""))
                file = form.get("file")
                if (not re.fullmatch(r"[1-9][0-9]{0,18}", document_id)
                        or not re.fullmatch(r"[1-9][0-9]{0,18}", library_id)
                        or not re.fullmatch(r"[a-zA-Z0-9_-]{1,64}", namespace)
                        or not re.fullmatch(r"[0-9a-f]{64}", sha)
                        or not name.strip() or len(name) > 255 or name in {".", ".."}
                        or any(ord(char) < 32 or 127 <= ord(char) < 160 for char in name)
                        or "/" in name or "\\" in name or not isinstance(file, UploadFile)):
                    return JSONResponse({"errorCode": "INVALID_REQUEST"}, status_code=400)
                identity = {"documentId": int(document_id), "libraryId": int(library_id), "sha256": sha}
                extension = Path(name).suffix.lower()
                if len(extension.encode("utf-8")) > 240:
                    return JSONResponse({"errorCode": "INVALID_REQUEST"}, status_code=400)
                with tempfile.TemporaryDirectory(prefix="rag-ingestion-") as directory:
                    path = Path(directory) / ("source" + extension)
                    digest = hashlib.sha256()
                    size = 0
                    with path.open("wb") as output:
                        while data := await file.read(65536):
                            size += len(data)
                            if size > self.max_bytes:
                                raise UploadTooLarge()
                            digest.update(data)
                            output.write(data)
                    if not size or digest.hexdigest() != sha:
                        return JSONResponse({**identity, "status": "FAILED", "errorCode": "HASH_MISMATCH", "retryable": False})
                    # Capability is determined by the configured converter, not a second whitelist.
                    if extension not in get_converter(self.config).supported_extensions:
                        return JSONResponse({**identity, "status": "UNSUPPORTED", "errorCode": "UNSUPPORTED_TYPE"})
                    return await self._ingest(path, name, namespace, identity)
        except UploadTooLarge:
            return JSONResponse({"errorCode": "FILE_TOO_LARGE"}, status_code=413)
        except HTTPException:
            if received > self.max_bytes + 65536:
                return JSONResponse({"errorCode": "FILE_TOO_LARGE"}, status_code=413)
            return JSONResponse({"errorCode": "INVALID_REQUEST"}, status_code=400)
        except Exception as error:
            # Do not return raw parser/model exceptions, which can include credentials or paths.
            logger.error("Ingestion request failed: %s", type(error).__name__)
            return JSONResponse({"errorCode": "INGESTION_UNAVAILABLE"}, status_code=503)

    async def _ingest(self, path, name, namespace, identity):
        uri = f"paper-assistant://{namespace}/libraries/{identity['libraryId']}/documents/{identity['documentId']}/{identity['sha256']}"
        metadata = {"business_namespace": namespace, "library_id": identity["libraryId"],
                    "business_document_id": identity["documentId"], "business_sha256": identity["sha256"],
                    "original_filename": name, "business_index_complete": False}
        async with self.lock:
            try:
                client = await self.get_client()
                existing = await client.get_document_by_uri(uri)
                if existing and (existing.metadata or {}).get("business_index_complete") is True:
                    chunks = await client.chunk_repository.get_by_document_id(existing.id)
                    if chunks and all((existing.metadata or {}).get(key) == value for key, value in metadata.items()
                                      if key != "business_index_complete"):
                        return JSONResponse({**identity, "status": "INDEXED", "ragDocumentId": existing.id})
                # Incomplete attempts must bypass URI/content freshness short circuits.
                document = await client.create_document_from_source(
                    path, uri=uri, title=name, metadata=metadata, force=existing is not None)
                chunks = await client.chunk_repository.get_by_document_id(document.id)
                if not chunks:
                    return JSONResponse({**identity, "status": "FAILED", "errorCode": "EMPTY_CONTENT", "retryable": False})
                await client.update_document(document.id, metadata={**(document.metadata or {}), **metadata,
                                                                    "business_index_complete": True})
                return JSONResponse({**identity, "status": "INDEXED", "ragDocumentId": document.id})
            except Exception as error:
                logger.error("Document ingestion failed: documentId=%s type=%s", identity["documentId"], type(error).__name__)
                retryable = self._is_retryable(error)
                code = "MODEL_UNAVAILABLE" if retryable else "PARSING_FAILED"
                return JSONResponse({**identity, "status": "FAILED", "errorCode": code, "retryable": retryable})

    @staticmethod
    def _is_retryable(error):
        # SDK exceptions often wrap the underlying httpx transport failure.
        current = error
        for _ in range(5):
            if isinstance(current, (httpx.TransportError, TimeoutError, ConnectionError, OSError)):
                return True
            status = getattr(current, "status_code", None)
            if isinstance(current, httpx.HTTPStatusError):
                status = current.response.status_code
            if isinstance(status, int):
                return status >= 500 or status == 429
            current = current.__cause__
            if current is None:
                break
        return False
