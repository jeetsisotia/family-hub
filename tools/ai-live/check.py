"""
Live check of the Gemini API with the same request shape the app sends (core/ai/GeminiClient.buildBody).

It is an API-level check, not a test of the app's screens. It needs a key in the GEMINI_API_KEY environment
variable (a GitHub secret); the key is never printed. Each call has a 40-second limit.

Checks:
  1. a general question gets a non-empty answer,
  2. a question with web search (the google_search tool) gets an answer with real sources from Google's
     grounding data (the app shows only these as citations),
  3. a follow-up uses the earlier turn (conversation context),
  4. an invalid key gets the error the app turns into "Google says this API key is not valid".
Prints PASS/FAIL per check and exits non-zero if any check that could run failed.
"""
import json, os, sys, urllib.request, urllib.error

API = "https://generativelanguage.googleapis.com/v1beta"
MODELS = ["gemini-flash-lite-latest", "gemini-flash-latest"]  # GeminiClient.PREFERRED
SYSTEM = "You are FamilyHub's assistant. Answer clearly and helpfully."


def body(turns, web=False):
    contents = [{"role": "user" if u else "model", "parts": [{"text": t}]} for u, t in turns]
    b = {"systemInstruction": {"parts": [{"text": SYSTEM}]}, "contents": contents,
         "generationConfig": {"temperature": 0.4}}
    if web:
        b["tools"] = [{"google_search": {}}]
    return json.dumps(b).encode()


def call(key, model, data):
    req = urllib.request.Request(f"{API}/models/{model}:generateContent", data=data,
                                 headers={"Content-Type": "application/json", "x-goog-api-key": key})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {}
    except Exception as e:  # network or timeout
        return -1, {"error": {"message": type(e).__name__}}


def answer(resp):
    cand = (resp.get("candidates") or [{}])[0]
    text = "".join(p.get("text", "") for p in (cand.get("content") or {}).get("parts", []))
    chunks = (cand.get("groundingMetadata") or {}).get("groundingChunks") or []
    sources = [c.get("web", {}).get("uri") for c in chunks if c.get("web", {}).get("uri")]
    return text, sources


def ask(key, turns, web=False):
    last = None
    for m in MODELS:
        code, resp = call(key, m, body(turns, web))
        if code == 200:
            return m, code, resp
        last = (m, code, resp)
    return last


results = []
def check(name, ok, detail):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + " :: " + detail)


key = os.environ.get("GEMINI_API_KEY", "").strip()

# 4. Invalid key (needs no secret).
code, resp = call("AIzaInvalidKeyForFamilyHubCheck000000000", MODELS[0], body([(True, "Hi")]))
msg = (resp.get("error") or {}).get("message", "")
check("invalid key is refused with a clear error", code in (400, 401, 403) and "api key" in msg.lower(),
      f"HTTP {code}: {msg[:120]}")

if not key:
    print("NOT TESTED general, web search and follow-up: no GEMINI_API_KEY secret is set.")
else:
    m, code, resp = ask(key, [(True, "In two sentences, why is the sky blue?")])
    text, _ = answer(resp) if code == 200 else ("", [])
    check("general question answered", code == 200 and len(text.strip()) > 40,
          f"model {m}, HTTP {code}, {len(text)} chars: {text.strip()[:160]!r}")

    m, code, resp = ask(key, [(True, "What is the latest stable version of Kotlin? Search the web.")], web=True)
    text, sources = answer(resp) if code == 200 else ("", [])
    err = (resp.get("error") or {}).get("message", "") if code != 200 else ""
    check("web search answer has real sources", code == 200 and len(sources) > 0,
          f"model {m}, HTTP {code}, {len(sources)} sources {sources[:3]}, answer {text.strip()[:120]!r} {err[:120]}")

    turns = [(True, "My favourite number is 7. Remember it."), (False, "Got it: 7."), (True, "What is my favourite number times 6?")]
    m, code, resp = ask(key, turns)
    text, _ = answer(resp) if code == 200 else ("", [])
    check("follow-up uses the earlier turn", code == 200 and "42" in text, f"model {m}, HTTP {code}: {text.strip()[:120]!r}")

print("Checks failed:", results.count(False))
sys.exit(1 if False in results else 0)
