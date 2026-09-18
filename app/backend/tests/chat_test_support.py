"""Deterministic model fixture; never imported by the production application."""
import asyncio
import json
import re

from pydantic_ai.messages import ToolReturnPart
from pydantic_ai.models.function import DeltaToolCall, FunctionModel

from business_chat import make_agent


def test_agent_factory(config, client, allowed_ids):
    calls = 0
    evidence = ""
    refusal = False

    async def respond(messages, info):
        nonlocal calls, evidence, refusal
        calls += 1
        if calls == 1:
            question = str(messages)
            refusal = "missing-fact" in question
            if "simulate-error" in question:
                raise RuntimeError("private-token-that-must-not-leak")
            if "simulate-timeout" in question:
                await asyncio.sleep(60)
            yield {0: DeltaToolCall(name="rag_search", json_args=json.dumps({"query": "learning rate"}), tool_call_id="search-1")}
        elif calls == 2:
            returns = [part for message in messages for part in message.parts
                       if isinstance(part, ToolReturnPart) and part.tool_name == "rag_search"]
            evidence = str(returns[-1].content)
            ids = re.findall(r"\[([0-9a-f]{8}-[0-9a-f-]{27})\]", evidence)
            if not ids and not refusal:
                raise AssertionError("Fixture expected scoped search results: " + evidence)
            yield {0: DeltaToolCall(name="rag_cite", json_args=json.dumps({"chunk_ids": [] if refusal else ids}), tool_call_id="cite-1")}
        else:
            text = "No evidence for this fact." if refusal else evidence
            yield text[:len(text) // 2]
            yield text[len(text) // 2:]

    return make_agent(config, client, allowed_ids, model=FunctionModel(stream_function=respond))


def parse_sse(text):
    events = []
    for frame in text.split("\n\n"):
        if not frame.strip():
            continue
        name = next(line[6:].strip() for line in frame.splitlines() if line.startswith("event:"))
        data = "\n".join(line[5:].strip() for line in frame.splitlines() if line.startswith("data:"))
        events.append((name, json.loads(data)))
    return events
