import json
import urllib.request

from agent.runtime.support.config import EVALUATOR_REQUEST_TIMEOUT_SECONDS, MODEL, OLLAMA_URL


# ============================================================
# Ollama
# ============================================================

def call_ollama(
    messages: list[dict],
    schema: dict
) -> dict:

    payload = {
        "model": MODEL,
        "messages": messages,
        "stream": False,
        "format": schema,
        "options": {
            "temperature": 0
        }
    }

    request = urllib.request.Request(
        OLLAMA_URL,
        data=json.dumps(
            payload
        ).encode("utf-8"),
        headers={
            "Content-Type": "application/json"
        },
        method="POST",
    )

    with urllib.request.urlopen(
        request,
        timeout=EVALUATOR_REQUEST_TIMEOUT_SECONDS,
    ) as response:

        result = json.loads(
            response.read().decode(
                "utf-8"
            )
        )

    content = result[
        "message"
    ]["content"]

    return json.loads(
        content
    )
