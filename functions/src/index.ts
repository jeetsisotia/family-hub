import {onCall, HttpsError} from "firebase-functions/v2/https";
import {defineSecret} from "firebase-functions/params";
import {initializeApp} from "firebase-admin/app";
import {getFirestore, FieldValue} from "firebase-admin/firestore";

initializeApp();
const db = getFirestore();
const geminiApiKey = defineSecret("GEMINI_API_KEY");
const REGION = "asia-south1";
const DAILY_REQUEST_LIMIT = 40;
const MAX_SYSTEM_CHARS = 12_000;
const MAX_TURN_CHARS = 6_000;

type Turn = {role: "user" | "model"; text: string};

async function reserveDailyQuota(uid: string): Promise<void> {
  const day = new Date().toISOString().slice(0, 10);
  const ref = db.collection("_aiUsage").doc(`${uid}_${day}`);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const count = snap.exists ? Number(snap.get("count") || 0) : 0;
    if (count >= DAILY_REQUEST_LIMIT) {
      throw new HttpsError("resource-exhausted", "Daily AI request limit reached.");
    }
    tx.set(ref, {uid, day, count: count + 1, updatedAt: FieldValue.serverTimestamp()}, {merge: true});
  });
}

async function chooseFlashModels(apiKey: string): Promise<string[]> {
  const response = await fetch("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", {
    headers: {"x-goog-api-key": apiKey},
  });
  if (!response.ok) throw new HttpsError("unavailable", "Could not contact the AI model service.");
  const data = await response.json() as {models?: Array<{name?: string; supportedGenerationMethods?: string[]}>};
  const models = (data.models || [])
    .filter((m) => m.name && m.supportedGenerationMethods?.includes("generateContent"))
    .map((m) => (m.name || "").replace(/^models\//, ""))
    .filter((name) => name.toLowerCase().includes("flash") &&
      !/(image|tts|audio|live|embedding)/i.test(name));
  return [...new Set(models)].sort((a, b) =>
    Number(/preview|exp/i.test(a)) - Number(/preview|exp/i.test(b)) || b.localeCompare(a));
}

async function generate(apiKey: string, model: string, system: string, turns: Turn[], json: boolean, temperature: number): Promise<string> {
  const response = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(model)}:generateContent`,
    {
      method: "POST",
      headers: {"x-goog-api-key": apiKey, "content-type": "application/json"},
      body: JSON.stringify({
        systemInstruction: {parts: [{text: system}]},
        contents: turns.map((turn) => ({role: turn.role, parts: [{text: turn.text}]})),
        generationConfig: {
          temperature,
          maxOutputTokens: 2048,
          ...(json ? {responseMimeType: "application/json"} : {}),
        },
      }),
    },
  );
  const body = await response.json() as {
    error?: {message?: string};
    candidates?: Array<{content?: {parts?: Array<{text?: string; thought?: boolean}>}}>;
  };
  if (!response.ok) {
    if (response.status === 429) throw new HttpsError("resource-exhausted", "The AI usage limit has been reached. Please try again later.");
    if (response.status >= 500) throw new HttpsError("unavailable", "Google's AI service is temporarily unavailable.");
    throw new HttpsError("failed-precondition", (body.error?.message || "Gemini request failed.").slice(0, 180));
  }
  const parts = body.candidates?.[0]?.content?.parts || [];
  const text = parts.filter((part) => !part.thought).map((part) => part.text || "").join("").trim();
  if (!text) throw new HttpsError("unavailable", "The AI returned no answer. Please try again.");
  return text;
}

export const generateFamilyHubAI = onCall(
  {region: REGION, secrets: [geminiApiKey], timeoutSeconds: 90, memory: "256MiB"},
  async (request) => {
    if (!request.auth) throw new HttpsError("unauthenticated", "Sign in to use Family Hub AI.");
    const data = request.data as {system?: unknown; turns?: unknown; json?: unknown; temperature?: unknown};
    if (typeof data.system !== "string" || !data.system.trim() || data.system.length > MAX_SYSTEM_CHARS) {
      throw new HttpsError("invalid-argument", "Invalid AI instructions.");
    }
    if (!Array.isArray(data.turns) || data.turns.length < 1 || data.turns.length > 14) {
      throw new HttpsError("invalid-argument", "Invalid conversation.");
    }
    const turns: Turn[] = data.turns.map((item: unknown) => {
      const turn = item as {role?: unknown; text?: unknown};
      if ((turn.role !== "user" && turn.role !== "model") ||
          typeof turn.text !== "string" || !turn.text.trim() || turn.text.length > MAX_TURN_CHARS) {
        throw new HttpsError("invalid-argument", "Invalid conversation message.");
      }
      return {role: turn.role, text: turn.text};
    });
    if (turns[0].role !== "user") throw new HttpsError("invalid-argument", "Conversation must start with a user message.");
    const temperature = typeof data.temperature === "number" ? Math.max(0, Math.min(1, data.temperature)) : 0.4;
    await reserveDailyQuota(request.auth.uid);
    const apiKey = geminiApiKey.value();
    if (!apiKey) throw new HttpsError("failed-precondition", "The AI service has not been configured by the app administrator.");
    const models = await chooseFlashModels(apiKey);
    if (models.length === 0) throw new HttpsError("unavailable", "No supported Gemini Flash model is available.");
    let lastError: unknown;
    for (const model of models.slice(0, 5)) {
      try {
        return {text: await generate(apiKey, model, data.system, turns, data.json === true, temperature)};
      } catch (error) {
        if (error instanceof HttpsError && error.code !== "failed-precondition" && error.code !== "unavailable") throw error;
        lastError = error;
      }
    }
    if (lastError instanceof HttpsError) throw lastError;
    throw new HttpsError("unavailable", "No Gemini Flash model could complete this request.");
  },
);
