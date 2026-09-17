"""Optional smoke test using configured model services and a temporary database.

Invokes the real embedding provider. Run explicitly; not part of offline tests.
"""
import asyncio
import hashlib
import json
import logging
import tempfile
from pathlib import Path
from unittest.mock import AsyncMock

import httpx
from ingestion import IngestionEndpoint
from starlette.applications import Starlette
from starlette.routing import Route

from haiku.rag.client import HaikuRAG
from haiku.rag.config import get_config


def pdf_fixture():
    lines = ["Reproduction protocol for experiment GAMMA853",
             "This document describes the training configuration used in our experiment.",
             "The initial learning rate is 0.003 and the batch size is 16.",
             "Training runs for 20 epochs using the Adam optimizer and a fixed seed.",
             "All evaluation samples are excluded from the training dataset.",
             "The validation score is recorded after each epoch for comparison.",
             "We preserve the configuration file and dependency versions for reuse.",
             "The model uses the same preprocessing in training and evaluation.",
             "Input samples are normalized before they are passed to the model.",
             "The experiment identifier GAMMA853 links this document to its results."]
    content = b"BT /F1 12 Tf 18 TL 60 640 Td\n" + b"\n".join(
        ("(" + line + ") Tj T*").encode() for line in lines) + b"\nET\n"
    objects = [b"<< /Type /Catalog /Pages 2 0 R >>",
               b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
               b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
               b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
               b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"endstream"]
    result = bytearray(b"%PDF-1.4\n")
    offsets = []
    for number, obj in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(str(number).encode() + b" 0 obj\n" + obj + b"\nendobj\n")
    start = len(result)
    result.extend(b"xref\n0 6\n0000000000 65535 f \n")
    for offset in offsets:
        result.extend(("%010d 00000 n \n" % offset).encode())
    result.extend(b"trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + str(start).encode() + b"\n%%EOF\n")
    return bytes(result)


async def main():
    config = get_config().model_copy(deep=True)
    config.lancedb.databases = {}
    config.storage.auto_vacuum = False
    # A hierarchical chunker keeps this smoke test independent of HF tokenizer downloads.
    config.processing.chunker_type = "hierarchical"
    config.processing.pictures = "none"
    config.processing.conversion_options.do_ocr = False
    config.processing.conversion_options.do_table_structure = False
    config.processing.auto_title = False
    with tempfile.TemporaryDirectory(prefix="rag-configured-test-") as directory:
        async with HaikuRAG(Path(directory) / "test.lancedb", config=config, create=True) as rag:
            endpoint = IngestionEndpoint(AsyncMock(return_value=rag), config)
            app = Starlette(routes=[Route("/ingest", endpoint.handle, methods=["POST"])])
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as http:
                for i, (name, data) in enumerate([("notes.md", b"# ALPHA731\nThe learning rate is 0.001."),
                                                 ("config.yaml", b"experiment: BETA942\nlearning_rate: 0.1"),
                                                 ("paper.pdf", pdf_fixture())], 1):
                    response = await http.post("/ingest", data=dict(documentId=str(i), libraryId="1", namespace="configured-test",
                                              originalFilename=name, sha256=hashlib.sha256(data).hexdigest()), files={"file": (name, data)})
                    result = response.json()
                    print(json.dumps({"file": name, "status": result.get("status"), "errorCode": result.get("errorCode")}), flush=True)
                    if result.get("status") != "INDEXED":
                        raise RuntimeError("Configured ingestion smoke test did not index " + name)
                assert await rag.count_documents() == 3
                results = await rag.search("learning rate", search_type="vector",
                                           filter="uri LIKE 'paper-assistant://configured-test/libraries/1/%'")
                assert results
                print("PASS: configured embeddings, PDF/Markdown/YAML parsing, LanceDB indexing and retrieval.", flush=True)


if __name__ == "__main__":
    logging.basicConfig(level=logging.ERROR)
    try:
        asyncio.run(main())
    except Exception as error:
        print("SMOKE_FAILED type=" + type(error).__name__, flush=True)
        raise SystemExit(1)
