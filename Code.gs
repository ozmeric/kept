/**
 * Kept — Google Apps Script backend (BuildFlow stack) · v4
 * Create as a STANDALONE Apps Script project, paste this file, set SHEET_ID, run `setup` once,
 * then Deploy → New deployment → Web app (Execute as: Me, Who has access: Anyone).
 * After code changes: Deploy → Manage deployments → Edit → Version: New version (URL stays the same).
 *
 * Optional Script Properties (Project Settings → Script properties):
 *   GEMINI_API_KEY   — enables the AI "Today's Brief" (free key at aistudio.google.com)
 *   GEMINI_MODEL     — optional: one model or several separated by commas, tried in order. Without it Kept
 *                      tries Google's newest Flash-Lite models, then Flash models, then the "-latest" aliases.
 *   EIA_API_KEY      — enables live US fuel prices (free key at eia.gov/opendata)
 *   PIN              — set automatically when the user changes their PIN in the app (PIN version only)
 *   PIN_ACCESS       — set to "off" once every driver has moved their data into a Kept account (closes the PIN door)
 *   FIREBASE_API_KEY — v6 accounts: the Firebase Web API key. Signed-in drivers can then use fuel prices, VIN, MPG lookup
 *                      and the AI brief. Their records live in Firebase, not in this Sheet.
 * Run `testIntegrations` once from the editor to check every outside service.
 */
var SHEET_ID = "PASTE_GOOGLE_SHEET_ID_HERE";
var DEFAULT_PIN = "1234";   // change after first login from Settings → Change PIN
var PHOTO_FOLDER = "Kept Photos";

var SCHEMA = {
  vehicles: { tab: "Vehicles", cols: ["id", "name", "type", "purchase", "resale", "lifeKm", "insurance", "json", "updatedAt"] },
  shifts:   { tab: "Shifts",   cols: ["id", "date", "start", "end", "hours", "vehicleId", "km", "emptyKm", "personalKm", "platforms", "pay", "tips", "bonus", "fees", "notes", "photos", "json", "updatedAt"] },
  expenses: { tab: "Expenses", cols: ["id", "date", "category", "amount", "vehicleId", "qty", "note", "photos", "json", "updatedAt"] },
  trips:    { tab: "Trips",    cols: ["id", "date", "start", "end", "app", "kind", "km", "pay", "tips", "vehicleId", "source", "json", "updatedAt"] }
};

function getss() { return SpreadsheetApp.openById(SHEET_ID); }
function out(d) { return ContentService.createTextOutput(JSON.stringify(d)).setMimeType(ContentService.MimeType.JSON); }
function prop(k) { return PropertiesService.getScriptProperties().getProperty(k); }
function getPin() { return prop("PIN") || DEFAULT_PIN; }

/** Run once from the editor to create the tabs and the Drive photo folder (also grants permissions). */
function setup() {
  var ss = getss();
  Object.keys(SCHEMA).forEach(function (k) { tab(ss, k); });
  settingsTab(ss); photosTab(ss); photoFolder();
  UrlFetchApp.fetch("https://www.google.com", { muteHttpExceptions: true });   // triggers the external-request permission
  Logger.log("Kept sheet ready: " + ss.getName());
}

function doGet() { return out({ status: "ok", app: "Kept", v: 2 }); }

function doPost(e) {
  try {
    var raw = (e.postData && e.postData.contents) ? e.postData.contents : (e.parameter && e.parameter.body ? e.parameter.body : "{}");
    var body = JSON.parse(raw);
    // v6 accounts: a signed-in Kept driver may use the shared services only (no Sheet data)
    if (body.idToken) {
      var uid = verifyIdToken(body.idToken);
      if (!uid) return out({ error: "Please sign in again" });
      if (!rateOk("u_" + uid, 300)) return out({ error: "Too many requests — try again in an hour" });
      var sa = body.action;
      if (sa === "fuelPrice") return out(fuelPrice(body.country, body.state));
      if (sa === "mpg")       return out(mpg(body));
      if (sa === "vin")       return out(vin(body));
      if (sa === "recalls")   return out(recalls(body));
      if (sa === "brief")     return out(brief(body.stats || {}));
      return out({ error: "Unknown action" });
    }
    // After everyone has moved to accounts, set Script property PIN_ACCESS = off: the Sheet can then no longer be read with a PIN
    if (String(prop("PIN_ACCESS") || "").toLowerCase() === "off") return out({ error: "The PIN version is closed. Sign in with your Kept account." });
    // PIN version: 10 wrong PINs within 15 minutes lock it for 15 minutes (stops guessing)
    var cache = CacheService.getScriptCache(), fails = Number(cache.get("pin_fails") || 0);
    if (fails >= 10) return out({ error: "Too many wrong PINs. Wait 15 minutes and try again." });
    if (String(body.pin) !== String(getPin())) { cache.put("pin_fails", String(fails + 1), 900); return out({ error: "Unauthorized" }); }
    var a = body.action;
    if (a === "ping")      return out({ ok: true });
    if (a === "getAll")    return out(getAll(getss()));
    if (a === "batch")     return out(batch(getss(), body.ops || []));
    if (a === "setPin") {
      var np = String(body.newPin || "");
      if (!/^\d{4,8}$/.test(np)) return out({ error: "PIN must be 4–8 digits" });
      if (np === DEFAULT_PIN || /^(\d)\1+$/.test(np) || "0123456789".indexOf(np) >= 0) return out({ error: "Choose a PIN that isn't easy to guess" });
      PropertiesService.getScriptProperties().setProperty("PIN", np);
      return out({ ok: true });
    }
    if (a === "savePhoto") return out(savePhoto(getss(), body.id, body.data, body.mime));
    if (a === "getPhoto")  return out(getPhoto(getss(), body.id));
    if (a === "brief")     return out(brief(body.stats || {}));
    if (a === "fuelPrice") return out(fuelPrice(body.country, body.state));
    if (a === "mpg")       return out(mpg(body));
    if (a === "vin")       return out(vin(body));
    if (a === "recalls")   return out(recalls(body));
    if (a === "deleteAll") { if (body.confirm !== "DELETE") return out({ error: "Confirmation missing" }); return out(deleteAll(getss())); }
    return out({ error: "Unknown action" });
  } catch (err) {
    return out({ error: err.toString() });
  }
}

// ── tabs ──
function tab(ss, coll) {
  var sc = SCHEMA[coll]; if (!sc) throw new Error("Unknown collection " + coll);
  var s = ss.getSheetByName(sc.tab);
  if (!s) {
    s = ss.insertSheet(sc.tab);
    s.getRange(1, 1, 1, sc.cols.length).setValues([sc.cols]).setFontWeight("bold");
    s.setFrozenRows(1);
  } else {
    // upgrade older v1 headers in place (adds new columns like "photos")
    var hdr = s.getRange(1, 1, 1, Math.max(1, s.getLastColumn())).getValues()[0];
    if (hdr.join("|") !== sc.cols.join("|")) migrate(s, hdr, sc.cols);
  }
  return s;
}
function migrate(s, hdr, cols) {
  var last = s.getLastRow(), jcOld = hdr.indexOf("json");
  var recs = [];
  if (last >= 2 && jcOld >= 0) recs = s.getRange(2, jcOld + 1, last - 1, 1).getValues().map(function (r) { return r[0]; });
  s.clear();
  s.getRange(1, 1, 1, cols.length).setValues([cols]).setFontWeight("bold");
  s.setFrozenRows(1);
  var coll = Object.keys(SCHEMA).filter(function (k) { return SCHEMA[k].tab === s.getName(); })[0];
  var rows = [];
  recs.forEach(function (j) { try { rows.push(rowFor(coll, JSON.parse(j))); } catch (x) {} });
  if (rows.length) s.getRange(2, 1, rows.length, rows[0].length).setValues(rows);
}
function settingsTab(ss) {
  var s = ss.getSheetByName("Settings");
  if (!s) { s = ss.insertSheet("Settings"); s.getRange(1, 1, 1, 2).setValues([["key", "value"]]).setFontWeight("bold"); }
  return s;
}
function photosTab(ss) {
  var s = ss.getSheetByName("Photos");
  if (!s) { s = ss.insertSheet("Photos"); s.getRange(1, 1, 1, 4).setValues([["id", "fileId", "url", "createdAt"]]).setFontWeight("bold"); }
  return s;
}
function photoFolder() {
  var it = DriveApp.getFoldersByName(PHOTO_FOLDER);
  return it.hasNext() ? it.next() : DriveApp.createFolder(PHOTO_FOLDER);
}

// ── read ──
function readColl(ss, coll) {
  var s = tab(ss, coll), sc = SCHEMA[coll], last = s.getLastRow();
  if (last < 2) return [];
  var jc = sc.cols.indexOf("json");
  return s.getRange(2, 1, last - 1, sc.cols.length).getValues()
    .filter(function (r) { return r[0] !== ""; })
    .map(function (r) { try { return JSON.parse(r[jc]); } catch (x) { return null; } })
    .filter(function (x) { return x; });
}
function getAll(ss) {
  var st = settingsTab(ss), settings = {};
  var last = st.getLastRow();
  if (last >= 2) st.getRange(2, 1, last - 1, 2).getValues().forEach(function (r) { if (r[0] === "settings") { try { settings = JSON.parse(r[1]); } catch (x) {} } });
  return { settings: settings, vehicles: readColl(ss, "vehicles"), shifts: readColl(ss, "shifts"), expenses: readColl(ss, "expenses"), trips: readColl(ss, "trips") };
}

// ── write ──
function n(v) { var x = parseFloat(v); return isFinite(x) ? x : 0; }
function rowFor(coll, r) {
  var now = new Date().toISOString(), json = JSON.stringify(r), ph = (r.photos || []).length;
  if (coll === "vehicles") return [r.id, r.name || r.type, r.type, n(r.purchase), n(r.resale), n(r.lifeKm), n(r.insurance), json, now];
  if (coll === "expenses") return [r.id, r.date, r.category, n(r.amount), r.vehicleId || "", r.qty || "", r.note || "", ph, json, now];
  if (coll === "trips") return [r.id, r.date, r.start || "", r.end || "", r.app || "", r.kind || "", n(r.km), r.pay === "" || r.pay == null ? "" : n(r.pay), r.tips === "" || r.tips == null ? "" : n(r.tips), r.vehicleId || "", r.source || "", json, now];
  var ps = r.platforms || [], sumf = function (k) { return ps.reduce(function (a, p) { return a + n(p[k]); }, 0); };
  var hrs = (new Date(r.end) - new Date(r.start)) / 3600000;
  return [r.id, r.date, r.start, r.end, Math.round(hrs * 100) / 100, r.vehicleId || "", n(r.km), n(r.emptyKm), n(r.personalKm),
          ps.map(function (p) { return p.name; }).join(", "), sumf("base"), sumf("tips"), sumf("bonus"), sumf("fees"), r.notes || "", ph, json, now];
}
function findRow(s, id) {
  var last = s.getLastRow(); if (last < 2) return -1;
  var ids = s.getRange(2, 1, last - 1, 1).getValues();
  for (var i = 0; i < ids.length; i++) if (String(ids[i][0]) === String(id)) return i + 2;
  return -1;
}
function upsert(ss, coll, rec) {
  if (!rec || !rec.id) throw new Error("Record needs an id");
  var s = tab(ss, coll), row = rowFor(coll, rec), at = findRow(s, rec.id);
  if (at > 0) s.getRange(at, 1, 1, row.length).setValues([row]);
  else s.appendRow(row);
}
function removeRec(ss, coll, id) {
  var s = tab(ss, coll), at = findRow(s, id);
  if (at < 0) return;
  try { var rec = JSON.parse(s.getRange(at, SCHEMA[coll].cols.indexOf("json") + 1).getValue()); (rec.photos || []).forEach(function (p) { deletePhoto(ss, p); }); } catch (x) {}
  s.deleteRow(at);
}
function saveSettings(ss, settings) {
  var s = settingsTab(ss), last = s.getLastRow();
  if (last >= 2) {
    var keys = s.getRange(2, 1, last - 1, 1).getValues();
    for (var i = 0; i < keys.length; i++) if (keys[i][0] === "settings") { s.getRange(i + 2, 2).setValue(JSON.stringify(settings)); return; }
  }
  s.appendRow(["settings", JSON.stringify(settings)]);
}
function replaceAll(ss, d) {
  Object.keys(SCHEMA).forEach(function (coll) {
    var s = tab(ss, coll), last = s.getLastRow();
    if (last >= 2) s.deleteRows(2, last - 1);
    var rows = (d[coll] || []).map(function (r) { return rowFor(coll, r); });
    if (rows.length) s.getRange(2, 1, rows.length, rows[0].length).setValues(rows);
  });
  saveSettings(ss, d.settings || {});
}
function batch(ss, ops) {
  var lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    ops.forEach(function (o) {
      if (o.op === "upsert")          upsert(ss, o.collection, o.record);
      else if (o.op === "remove")     removeRec(ss, o.collection, o.id);
      else if (o.op === "settings")   saveSettings(ss, o.settings || {});
      else if (o.op === "replaceAll") replaceAll(ss, o.data || {});
    });
  } finally { lock.releaseLock(); }
  return { ok: true, applied: ops.length };
}

// ── photos (Google Drive, private to the owner) ──
function photoRow(ss, id) {
  var s = photosTab(ss), last = s.getLastRow(); if (last < 2) return null;
  var v = s.getRange(2, 1, last - 1, 2).getValues();
  for (var i = 0; i < v.length; i++) if (String(v[i][0]) === String(id)) return { row: i + 2, fileId: v[i][1] };
  return null;
}
function savePhoto(ss, id, b64, mime) {
  if (!id || !b64) throw new Error("Missing photo");
  if (photoRow(ss, id)) return { ok: true, existing: true };
  var blob = Utilities.newBlob(Utilities.base64Decode(b64), mime || "image/jpeg", id + ".jpg");
  var f = photoFolder().createFile(blob);
  photosTab(ss).appendRow([id, f.getId(), f.getUrl(), new Date().toISOString()]);
  return { ok: true, fileId: f.getId() };
}
function getPhoto(ss, id) {
  var r = photoRow(ss, id); if (!r) return { data: null };
  var b = DriveApp.getFileById(r.fileId).getBlob();
  return { data: Utilities.base64Encode(b.getBytes()), mime: b.getContentType() };
}
function deletePhoto(ss, id) {
  var r = photoRow(ss, id); if (!r) return;
  try { DriveApp.getFileById(r.fileId).setTrashed(true); } catch (x) {}
  photosTab(ss).deleteRow(r.row);
}
function deleteAll(ss) {
  replaceAll(ss, { settings: {} });
  var s = photosTab(ss), last = s.getLastRow();
  if (last >= 2) {
    s.getRange(2, 2, last - 1, 1).getValues().forEach(function (r) { try { DriveApp.getFileById(r[0]).setTrashed(true); } catch (x) {} });
    s.deleteRows(2, last - 1);
  }
  return { ok: true };
}

// ── AI Today's Brief (Gemini) ──
function brief(stats) {
  var key = prop("GEMINI_API_KEY");
  if (!key) throw new Error("NO_AI_KEY");
  var langName = { en: "English", nl: "Dutch", tr: "Turkish" }[stats.lang] || "English";
  var prompt =
    "You are Kept, a coach for gig drivers and couriers. Write today's brief in " + langName + ".\n" +
    "Rules: 3 to 4 short sentences, plain text, no headings, no bullet points, no emojis. Use the numbers given; never invent figures. " +
    "Money is in " + stats.currency + ", distance in " + stats.unit + ". Mention one concrete thing to do today (e.g. which day/time pays best, " +
    "whether to take it easier if burnout is 'watch' or 'high', how far from the daily target they are). No tax or legal advice.\n" +
    "Data (JSON): " + JSON.stringify(stats);
  // several models, tried in order until one gives a usable brief (see geminiCandidates)
  var cache = CacheService.getScriptCache(), list = geminiCandidates(key), t0 = Date.now(), tried = [];
  for (var i = 0; i < list.length; i++) {
    if (Date.now() - t0 > 90000) { tried.push("stopped: 90 s used"); break; }   // leave time for the app's request
    var model = list[i], t1 = Date.now(), why = "";
    try {
      var res = UrlFetchApp.fetch("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + encodeURIComponent(key), {
        method: "post", contentType: "application/json", muteHttpExceptions: true,
        payload: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { temperature: 0.6, maxOutputTokens: 1024 } }) });
      var j = JSON.parse(res.getContentText());
      if (j.error) why = j.error.message || "error";
      var text = !why && j.candidates && j.candidates[0] && j.candidates[0].content && j.candidates[0].content.parts
        ? j.candidates[0].content.parts.map(function (p) { return p.text || ""; }).join("").trim() : "";
      // a usable brief is plain sentences: no code marks or JSON, ends like a sentence, at least ~60 characters
      if (!why && (!text || /[`{}]/.test(text) || text.length < 60 || !/[.!?…]["”')]?$/.test(text))) why = "unclear answer";
      var secs = Math.round((Date.now() - t1) / 1000);
      if (!why) {
        if (secs > 30) cache.put("gemini_slow_" + model, "1", 86400);  // works but slow: try it later next time
        else cache.put("gemini_ok", model, 86400);                     // remembered as the first choice for a day
        return { text: text, model: model, seconds: secs, tried: tried };
      }
      tried.push(model + ": " + String(why).slice(0, 80));
      if (secs > 30) cache.put("gemini_slow_" + model, "1", 86400);
    } catch (x) { tried.push(model + ": " + String(x).slice(0, 80)); }
  }
  throw new Error("Gemini: no model gave a usable brief (" + tried.join("; ") + ")");
}

// The models to try, in order:
//  1. Script property GEMINI_MODEL, if set: one model or several separated by commas,
//     e.g. "gemini-3.8-flash-lite, gemini-3.8-flash, gemini-flash-latest"
//  2. otherwise from Google's model list for this key: every stable "gemini-X.Y-flash-lite" (newest first),
//     then every "gemini-X.Y-flash" (newest first), then the "-latest" aliases as a last resort.
// The model that last worked comes first; models that were slow (over 30 s) move to the end for a day.
function geminiCandidates(key) {
  var cache = CacheService.getScriptCache(), list = [];
  var pinned = String(prop("GEMINI_MODEL") || "").split(",").map(function (x) { return x.trim().replace(/^models\//, ""); }).filter(String);
  if (pinned.length) list = pinned;
  else {
    var cached = cache.get("gemini_models");
    if (cached) list = JSON.parse(cached);
    else {
      try {
        var j = JSON.parse(UrlFetchApp.fetch("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000&key=" + encodeURIComponent(key), { muteHttpExceptions: true }).getContentText());
        var found = [];
        (j.models || []).forEach(function (m) {
          var name = String(m.name || "").replace(/^models\//, ""), mm = name.match(/^gemini-(\d+(?:\.\d+)?)-flash(-lite)?$/);
          if (mm && (m.supportedGenerationMethods || []).indexOf("generateContent") >= 0) found.push({ name: name, score: (mm[2] ? 1000 : 0) + parseFloat(mm[1]) });
        });
        list = found.sort(function (a, b) { return b.score - a.score; }).slice(0, 6).map(function (x) { return x.name; });
      } catch (x) {}
      ["gemini-flash-lite-latest", "gemini-flash-latest"].forEach(function (a) { if (list.indexOf(a) < 0) list.push(a); });
      cache.put("gemini_models", JSON.stringify(list), 86400);
    }
  }
  var ok = cache.get("gemini_ok");
  if (ok && list.indexOf(ok) > 0) { list.splice(list.indexOf(ok), 1); list.unshift(ok); }
  var fast = list.filter(function (m) { return !cache.get("gemini_slow_" + m); }), slow = list.filter(function (m) { return cache.get("gemini_slow_" + m); });
  return fast.concat(slow);
}

// ── live fuel prices (cached 6 h) ──
var PADD = { "1": "R10", "2": "R20", "3": "R30", "4": "R40", "5": "R50" };
var EIA_STATES = { CA: "SCA", CO: "SCO", FL: "SFL", MA: "SMA", MN: "SMN", NY: "SNY", OH: "SOH", TX: "STX", WA: "SWA" };
var EIA_NAMES = { R1X: "New England", R1Y: "Central Atlantic", R1Z: "Lower Atlantic", R10: "East Coast", R20: "Midwest", R30: "Gulf Coast", R40: "Rocky Mountain",
  R50: "West Coast", R5XCA: "West Coast without California", NUS: "U.S. average", SCA: "California", SCO: "Colorado", SFL: "Florida", SMA: "Massachusetts",
  SMN: "Minnesota", SNY: "New York", SOH: "Ohio", STX: "Texas", SWA: "Washington" };
var EIA_SUB = { CT:"R1X", ME:"R1X", MA:"R1X", NH:"R1X", RI:"R1X", VT:"R1X", DE:"R1Y", DC:"R1Y", MD:"R1Y", NJ:"R1Y", NY:"R1Y", PA:"R1Y",
  FL:"R1Z", GA:"R1Z", NC:"R1Z", SC:"R1Z", VA:"R1Z", WV:"R1Z", AK:"R5XCA", AZ:"R5XCA", HI:"R5XCA", NV:"R5XCA", OR:"R5XCA", WA:"R5XCA" };
var STATE_PADD = { AL:3,AK:5,AZ:5,AR:3,CA:5,CO:4,CT:1,DE:1,DC:1,FL:1,GA:1,HI:5,ID:4,IL:2,IN:2,IA:2,KS:2,KY:2,LA:3,ME:1,MD:1,MA:1,MI:2,MN:2,MS:3,MO:2,MT:4,NE:2,NV:5,NH:1,NJ:1,NM:3,NY:1,NC:1,ND:2,OH:2,OK:2,OR:5,PA:1,RI:1,SC:1,SD:2,TN:2,TX:3,UT:4,VT:1,VA:1,WA:5,WV:1,WI:2,WY:4 };
function fuelPrice(country, state) {
  var cache = CacheService.getScriptCache(), ck = "fuel_" + country + "_" + (state || "");
  var hit = cache.get(ck); if (hit) return JSON.parse(hit);
  var r = country === "NL" ? fuelNL() : country === "US" ? fuelUS(state) : null;
  if (!r) throw new Error("No live fuel source for this country");
  cache.put(ck, JSON.stringify(r), 6 * 3600);
  return r;
}
function fuelNL() {
  // CBS StatLine open data, table 80416NED "Pompprijzen motorbrandstoffen; brandstofsoort, per dag" (no key needed)
  // CBS ignores $orderby, so ask for the last 30 days and take the newest row
  var since = Utilities.formatDate(new Date(Date.now() - 30 * 864e5), "Europe/Amsterdam", "yyyyMMdd");
  var url = "https://opendata.cbs.nl/ODataApi/odata/80416NED/TypedDataSet?$filter=" + encodeURIComponent("Perioden ge '" + since + "'");
  var j = JSON.parse(UrlFetchApp.fetch(url, { muteHttpExceptions: true }).getContentText());
  var rows = (j.value || []).filter(function (r) { return /^\d{8}$/.test(String(r.Perioden)); })
    .sort(function (a, b) { return String(a.Perioden) < String(b.Perioden) ? -1 : 1; });
  var row = rows[rows.length - 1]; if (!row) throw new Error("CBS: no prices in the last 30 days");
  var pick = function (re) { for (var k in row) if (re.test(k) && typeof row[k] === "number") return row[k]; return null; };
  var p = String(row.Perioden || "");
  var date = /^\d{8}$/.test(p) ? p.slice(0, 4) + "-" + p.slice(4, 6) + "-" + p.slice(6, 8) : p;
  return { petrol: pick(/euro95|benzine/i), diesel: pick(/diesel/i), unit: "L", currency: "EUR", date: date, area: "NL", source: "CBS 80416NED" };
}
function fuelUS(state) {
  var key = prop("EIA_API_KEY"); if (!key) throw new Error("Add EIA_API_KEY in Script properties for US fuel prices");
  // smallest EIA area first: the state's own series (9 states), then its sub-region (New England, Central Atlantic,
  // Lower Atlantic, West Coast without California), then its PADD region, then the U.S. average
  var chain = [EIA_STATES[state], EIA_SUB[state], STATE_PADD[state] ? PADD[String(STATE_PADD[state])] : null, "NUS"].filter(function (x) { return x; });
  var get = function (product) {
    var url = "https://api.eia.gov/v2/petroleum/pri/gnd/data/?api_key=" + encodeURIComponent(key) +
      "&frequency=weekly&data%5B0%5D=value&facets%5Bduoarea%5D%5B%5D=" + area + "&facets%5Bproduct%5D%5B%5D=" + product +
      "&sort%5B0%5D%5Bcolumn%5D=period&sort%5B0%5D%5Bdirection%5D=desc&length=1";
    var j = JSON.parse(UrlFetchApp.fetch(url, { muteHttpExceptions: true }).getContentText());
    var d = j.response && j.response.data && j.response.data[0];
    return d ? { v: parseFloat(d.value), period: d.period, name: d["area-name"] } : null;
  };
  var g = null, d = null, area = "NUS";
  for (var i = 0; i < chain.length && !g && !d; i++) { area = chain[i]; g = get("EPMR"); d = get("EPD2D"); }
  if (!g && !d) throw new Error("EIA: no data for " + state);
  return { petrol: g ? g.v : null, diesel: d ? d.v : null, unit: "gal", currency: "USD", date: (g || d).period, area: EIA_NAMES[area] || (g || d).name || area, source: "EIA weekly retail" };
}

// ── vehicle MPG lookup (fueleconomy.gov, no key) ──
function feg(path) {
  var res = UrlFetchApp.fetch("https://www.fueleconomy.gov/ws/rest/" + path, { headers: { Accept: "application/json" }, muteHttpExceptions: true });
  if (res.getResponseCode() !== 200) throw new Error("fueleconomy.gov " + res.getResponseCode());
  return JSON.parse(res.getContentText());
}
function menuItems(j) {
  var m = j && j.menuItem; if (!m) return [];
  if (!Array.isArray(m)) m = [m];
  return m.map(function (x) { return { text: String(x.text), value: String(x.value) }; });
}
// VIN decode: NHTSA vPIC (free U.S. government database, no key). Returns the decoded fields unchanged;
// the app maps them and the driver confirms before saving.
function vin(b) {
  var v = String(b.vin || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (!/^[A-HJ-NPR-Z0-9]{17}$/.test(v)) throw new Error("VIN must be 17 letters/numbers (no I, O or Q)");
  var res = UrlFetchApp.fetch("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/" + v + "?format=json", { muteHttpExceptions: true });
  if (res.getResponseCode() !== 200) throw new Error("Vehicle database unavailable (" + res.getResponseCode() + ")");
  var r = (JSON.parse(res.getContentText()).Results || [])[0] || {};
  return { ModelYear: r.ModelYear, Make: r.Make, Model: r.Model, Trim: r.Trim, BodyClass: r.BodyClass, DisplacementL: r.DisplacementL,
           EngineCylinders: r.EngineCylinders, FuelTypePrimary: r.FuelTypePrimary, ElectrificationLevel: r.ElectrificationLevel, ErrorCode: r.ErrorCode, ErrorText: r.ErrorText };
}

// Firebase ID token → user id, checked by Google's Identity Toolkit (cached 30 min per token)
function verifyIdToken(tok) {
  var key = prop("FIREBASE_API_KEY"); if (!key || !tok) return null;
  var cache = CacheService.getScriptCache();
  var h = Utilities.base64EncodeWebSafe(Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, tok)).slice(0, 40);
  var hit = cache.get("tok_" + h); if (hit) return hit;
  var res = UrlFetchApp.fetch("https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=" + encodeURIComponent(key),
    { method: "post", contentType: "application/json", payload: JSON.stringify({ idToken: tok }), muteHttpExceptions: true });
  if (res.getResponseCode() !== 200) return null;
  var u = (JSON.parse(res.getContentText()).users || [])[0];
  if (!u || !u.localId) return null;
  cache.put("tok_" + h, u.localId, 1800);
  return u.localId;
}
function rateOk(k, perHour) {
  var cache = CacheService.getScriptCache(), n = Number(cache.get("rl_" + k) || 0);
  if (n >= perHour) return false;
  cache.put("rl_" + k, String(n + 1), 3600);
  return true;
}

// Safety recalls by model year, make and model: NHTSA recalls API (free, no key)
function recalls(b) {
  var make = String(b.make || "").trim(), model = String(b.model || "").trim(), year = parseInt(b.year, 10);
  if (!make || !model || !(year > 1980)) throw new Error("Year, make and model are needed");
  var url = "https://api.nhtsa.gov/recalls/recallsByVehicle?make=" + encodeURIComponent(make) + "&model=" + encodeURIComponent(model) + "&modelYear=" + year;
  var res = UrlFetchApp.fetch(url, { muteHttpExceptions: true });
  // any error is reported as an error, never as "no recalls" (a wrong "no recalls" would be unsafe)
  if (res.getResponseCode() !== 200) throw new Error("Recall database unavailable (" + res.getResponseCode() + ")");
  var j = JSON.parse(res.getContentText());
  return { results: (j.results || []).map(function (r) { return { NHTSACampaignNumber: r.NHTSACampaignNumber, Component: r.Component, Summary: r.Summary, ReportReceivedDate: r.ReportReceivedDate }; }) };
}

function mpg(b) {
  var q = function (s) { return encodeURIComponent(s); };
  if (b.step === "make")    return { items: menuItems(feg("vehicle/menu/make?year=" + q(b.year))) };
  if (b.step === "model")   return { items: menuItems(feg("vehicle/menu/model?year=" + q(b.year) + "&make=" + q(b.make))) };
  if (b.step === "options") return { items: menuItems(feg("vehicle/menu/options?year=" + q(b.year) + "&make=" + q(b.make) + "&model=" + q(b.model))) };
  if (b.step === "vehicle") {
    var v = feg("vehicle/" + q(b.id));
    var ev = v.atvType === "EV";
    return { mpg: ev ? null : parseFloat(v.comb08), ev: ev, hybrid: /hybrid/i.test(String(v.atvType || "")), kwhPer100mi: ev ? parseFloat(v.combE) : null,
             fuelType: v.fuelType1 || v.fuelType, vclass: v.VClass || "", year: v.year, make: v.make, model: v.model };
  }
  throw new Error("Unknown mpg step");
}

/** Run from the editor to check every outside service. Results appear in Execution log. */
function testIntegrations() {
  try { Logger.log("NL fuel: " + JSON.stringify(fuelNL())); } catch (x) { Logger.log("NL fuel FAILED: " + x); }
  try { Logger.log("US fuel (VA): " + JSON.stringify(fuelUS("VA"))); } catch (x) { Logger.log("US fuel FAILED: " + x); }
  try { Logger.log("VIN test: " + JSON.stringify(vin({ vin: "4T1B11HK4KU236014" }))); } catch (x) { Logger.log("VIN FAILED: " + x); }
  try { Logger.log("MPG makes 2022: " + mpg({ step: "make", year: 2022 }).items.length); } catch (x) { Logger.log("MPG FAILED: " + x); }
  try { Logger.log("MPG Toyota Prius 2022: " + JSON.stringify(mpg({ step: "options", year: 2022, make: "Toyota", model: "Prius" }))); } catch (x) { Logger.log("MPG options FAILED: " + x); }
  try { var ss = getss(); savePhoto(ss, "ph_test", Utilities.base64Encode("test"), "text/plain"); Logger.log("Photo save OK"); deletePhoto(ss, "ph_test"); } catch (x) { Logger.log("Photos FAILED: " + x); }
  try { Logger.log("Gemini models to try: " + geminiCandidates(prop("GEMINI_API_KEY") || "").join(", ")); } catch (x) { Logger.log("Gemini model list FAILED: " + x); }
  try { var t0 = Date.now(), b = brief({ lang: "en", currency: "USD", unit: "mi", today: { net: 120, hours: 6 } }); Logger.log("Gemini (" + b.model + ", " + b.seconds + " s" + (b.tried.length ? "; skipped " + b.tried.join("; ") : "") + "): " + b.text); } catch (x) { Logger.log("Gemini FAILED: " + x); }
}

// ── nightly backup of the Sheet (PIN version) ──
// Run installNightlyBackup() once from the editor. Every night a copy of the Sheet goes to the Drive folder
// "Kept Backups"; copies older than 30 days are moved to the trash.
function installNightlyBackup() {
  ScriptApp.getProjectTriggers().forEach(function (t) { if (t.getHandlerFunction() === "nightlyBackup") ScriptApp.deleteTrigger(t); });
  ScriptApp.newTrigger("nightlyBackup").timeBased().everyDays(1).atHour(3).create();
  nightlyBackup();
}
function nightlyBackup() {
  var it = DriveApp.getFoldersByName("Kept Backups"), folder = it.hasNext() ? it.next() : DriveApp.createFolder("Kept Backups");
  var stamp = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), "yyyy-MM-dd");
  DriveApp.getFileById(SHEET_ID).makeCopy("Kept backup " + stamp, folder);
  var cutoff = Date.now() - 30 * 864e5, files = folder.getFiles();
  while (files.hasNext()) { var f = files.next(); if (f.getDateCreated().getTime() < cutoff) f.setTrashed(true); }
}
