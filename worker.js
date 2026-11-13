// Cloudflare Worker — proxies the app to Google Apps Script (hides the script URL)
// Only the Kept site and the Kept Android app may call it (browsers enforce this through CORS).
const APPS_SCRIPT_URL = "https://script.google.com/macros/s/YOUR_DEPLOYMENT_ID/exec";
const ALLOWED_ORIGINS = ["https://kept.usebuildflow.io", "https://localhost", "capacitor://localhost"];
export default {
  async fetch(request) {
    const origin = request.headers.get("Origin") || "";
    const allowed = ALLOWED_ORIGINS.includes(origin);
    const cors = {
      "Access-Control-Allow-Origin": allowed ? origin : ALLOWED_ORIGINS[0],
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Vary": "Origin",
    };
    if (request.method === "OPTIONS") return new Response(null, { status: allowed ? 204 : 403, headers: cors });
    if (!allowed) return new Response(JSON.stringify({ error: "Not allowed" }), { status: 403, headers: { "Content-Type": "application/json", ...cors } });
    if (request.method !== "POST") return new Response(JSON.stringify({ error: "POST only" }), { status: 405, headers: { "Content-Type": "application/json", ...cors } });
    try {
      const body = await request.text();
      if (body.length > 6 * 1024 * 1024) return new Response(JSON.stringify({ error: "Too large" }), { status: 413, headers: { "Content-Type": "application/json", ...cors } });
      const res = await fetch(APPS_SCRIPT_URL, {
        method: "POST",
        headers: { "Content-Type": "application/json", "Accept": "application/json" },
        body,
        redirect: "follow",
      });
      return new Response(await res.text(), { status: 200, headers: { "Content-Type": "application/json", ...cors } });
    } catch (err) {
      return new Response(JSON.stringify({ error: err.message }), { status: 500, headers: { "Content-Type": "application/json", ...cors } });
    }
  }
};
