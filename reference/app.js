/* app.js - Noter (webklient mod Radicale/VJOURNAL).
 *
 * Alle netværkskald går gennem én kø (enqueue), så en opdatering aldrig kan overskrive
 * en note midt i et gem. Noters tekst sættes kun via textContent/value, aldrig som HTML. */
(function () {
  "use strict";

  const CFG = Object.assign({ saveDelay: 1200, retryMs: 8000, pollMs: 30000 }, window.NOTEWEB_CONFIG || {});
  const Ical = window.Ical;
  const Dav = window.Dav;
  const dav = new Dav.DavClient(); // samme origin; browseren håndterer Basic-login
  const STORE_KEY = "noteweb.collection";

  const $ = (id) => document.getElementById(id);
  const els = {
    list: $("list"), empty: $("empty"), search: $("search"), collection: $("collection"),
    status: $("status"), banner: $("banner"), bannerText: $("banner-text"),
    bannerAction: $("banner-action"), bannerClose: $("banner-close"),
    title: $("title"), tags: $("tags"), body: $("body"), meta: $("meta"),
    btnNew: $("btn-new"), btnDelete: $("btn-delete"), btnBack: $("btn-back"),
  };

  const blank = () => ({ title: "", body: "", tags: [] });
  const state = {
    collection: null,
    notes: new Map(),      // uid -> note
    selected: null,        // uid for noten i editoren, null = ny, endnu ikke gemt note
    baseline: blank(),     // det, editoren sidst var i overensstemmelse med serveren om
    shownEtag: null,
    ctag: null,
    query: "",
    saveTimer: null,
    bannerTimer: null,
  };

  // ---- hjælpere -----------------------------------------------------------

  let tail = Promise.resolve();
  function enqueue(fn) {
    const run = tail.then(fn);
    tail = run.catch(() => {});
    return run;
  }

  function storeGet() { try { return localStorage.getItem(STORE_KEY); } catch (e) { return null; } }
  function storeSet(v) { try { localStorage.setItem(STORE_KEY, v); } catch (e) { /* privat vindue */ } }

  function withSlash(p) { return p.endsWith("/") ? p : p + "/"; }

  function parseTags(s) {
    const out = [];
    s.split(",").forEach((t) => { t = t.trim(); if (t && !out.includes(t)) out.push(t); });
    return out;
  }
  function sameForm(a, b) {
    return a.title === b.title && a.body === b.body &&
      a.tags.length === b.tags.length && a.tags.every((t, i) => t === b.tags[i]);
  }
  function readForm() { return { title: els.title.value, body: els.body.value, tags: parseTags(els.tags.value) }; }
  function writeForm(f) {
    els.title.value = f.title;
    els.body.value = f.body;
    els.tags.value = f.tags.join(", ");
  }
  function isDirty() { return !sameForm(readForm(), state.baseline); }

  function parseStamp(s) {
    const m = /^(\d{4})(\d\d)(\d\d)T(\d\d)(\d\d)(\d\d)(Z?)$/.exec(s || "");
    if (!m) return null;
    const [y, mo, d, h, mi, se] = m.slice(1, 7).map(Number);
    return m[7] ? new Date(Date.UTC(y, mo - 1, d, h, mi, se)) : new Date(y, mo - 1, d, h, mi, se);
  }
  function dayDiff(t, now) {
    const day = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
    return Math.round((day(now) - day(t)) / 86400000);
  }
  function groupLabel(t, now) {
    const diff = dayDiff(t, now);
    if (diff <= 0) return "I dag";
    if (diff === 1) return "I går";
    if (diff < 7) return "Seneste 7 dage";
    return t.toLocaleDateString("da-DK", { month: "long", year: "numeric" });
  }
  function shortTime(t, now) {
    const diff = dayDiff(t, now);
    if (diff <= 0) return t.toLocaleTimeString("da-DK", { hour: "2-digit", minute: "2-digit" });
    if (diff < 7) return t.toLocaleDateString("da-DK", { weekday: "short" });
    return t.toLocaleDateString("da-DK", { day: "numeric", month: "short" });
  }
  function fullTime(t) {
    return t ? t.toLocaleString("da-DK", { dateStyle: "medium", timeStyle: "short" }) : "";
  }

  // ---- statuslinje og meddelelser -----------------------------------------

  function setStatus(text) { els.status.textContent = text; }

  function hideBanner() {
    clearTimeout(state.bannerTimer);
    els.banner.hidden = true;
  }
  function showBanner(text, opts) {
    opts = opts || {};
    clearTimeout(state.bannerTimer);
    els.bannerText.textContent = text;
    els.banner.classList.toggle("error", !!opts.error);
    els.banner.setAttribute("role", opts.error ? "alert" : "status");
    if (opts.action) {
      els.bannerAction.textContent = opts.action.label;
      els.bannerAction.onclick = opts.action.run;
      els.bannerAction.hidden = false;
    } else {
      els.bannerAction.hidden = true;
      els.bannerAction.onclick = null;
    }
    els.banner.hidden = false;
    if (!opts.error && !opts.sticky) state.bannerTimer = setTimeout(hideBanner, 9000);
  }

  function handleError(e) {
    console.error(e);
    if (e instanceof Dav.AuthError) {
      showBanner("Du er ikke logget ind. Log ind igen for at se og gemme dine noter.", {
        error: true, action: { label: "Log ind", run: () => { hideBanner(); start(); } },
      });
    } else if (e instanceof Dav.NetworkError) {
      setStatus("Ikke gemt");
      showBanner("Kan ikke nå serveren. Dine ændringer gemmes igen, så snart forbindelsen er tilbage.", { error: true });
      if (isDirty()) scheduleSave(CFG.retryMs);
    } else {
      showBanner("Noget gik galt: " + e.message, { error: true });
    }
  }

  // ---- liste --------------------------------------------------------------

  function visibleNotes() {
    const q = state.query.trim().toLowerCase();
    const all = Array.from(state.notes.values());
    const hit = q ? all.filter((n) => (n.title + "\n" + n.body + "\n" + n.tags.join(" ")).toLowerCase().includes(q)) : all;
    return hit
      .map((n) => ({ n, t: parseStamp(n.modified) }))
      .sort((a, b) => (b.t ? b.t.getTime() : 0) - (a.t ? a.t.getTime() : 0) || a.n.title.localeCompare(b.n.title));
  }

  function renderItem(n, t, now) {
    const li = document.createElement("li");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "note";
    btn.dataset.uid = n.uid;
    if (n.uid === state.selected) btn.setAttribute("aria-current", "true");

    const title = document.createElement("span");
    title.className = "note-title" + (n.title ? "" : " untitled");
    const text = document.createElement("span");
    text.className = "title-text";
    text.textContent = n.title || "Uden titel";
    title.append(text);
    btn.append(title);

    if (t) {
      const time = document.createElement("span");
      time.className = "note-time";
      time.textContent = shortTime(t, now);
      btn.append(time);
    }
    const first = n.body.split("\n").map((l) => l.trim()).find(Boolean);
    if (first) {
      const snip = document.createElement("span");
      snip.className = "note-snippet";
      snip.textContent = first.slice(0, 140);
      btn.append(snip);
    }
    if (n.tags.length) {
      const tg = document.createElement("span");
      tg.className = "note-tags";
      tg.textContent = n.tags.join(", ");
      btn.append(tg);
    }
    li.append(btn);
    return li;
  }

  function render() {
    const items = visibleNotes();
    const now = new Date();
    els.list.textContent = "";
    let last = null, ul = null;
    for (const { n, t } of items) {
      const label = t ? groupLabel(t, now) : "Uden dato";
      if (label !== last) {
        const h = document.createElement("h2");
        h.className = "group";
        h.textContent = label;
        ul = document.createElement("ul");
        els.list.append(h, ul);
        last = label;
      }
      ul.append(renderItem(n, t, now));
    }
    els.empty.hidden = items.length > 0 || !state.collection;
    els.empty.textContent = state.notes.size === 0
      ? "Ingen noter endnu. Tryk på Ny note for at skrive den første."
      : "Ingen noter matcher søgningen.";
  }

  // ---- editor -------------------------------------------------------------

  function updateMeta(n) { els.meta.textContent = n ? "Ændret " + fullTime(parseStamp(n.modified)) : ""; }

  function openNote(uid, switchView) {
    const n = state.notes.get(uid);
    state.selected = uid;
    state.baseline = { title: n.title, body: n.body, tags: n.tags.slice() };
    state.shownEtag = n.etag;
    writeForm(state.baseline);
    updateMeta(n);
    els.btnDelete.disabled = false;
    if (switchView !== false) document.body.dataset.view = "editor";
    render();
  }

  function startNew() {
    state.selected = null;
    state.baseline = blank();
    state.shownEtag = null;
    writeForm(state.baseline);
    updateMeta(null);
    els.btnDelete.disabled = true;
    document.body.dataset.view = "editor";
    render();
    els.title.focus();
  }

  function closeEditor() {
    state.selected = null;
    state.baseline = blank();
    state.shownEtag = null;
    writeForm(state.baseline);
    updateMeta(null);
    els.btnDelete.disabled = true;
    document.body.dataset.view = "list";
    render();
  }

  // Gem det, der er ventende, før vi skifter note. Returnerer false, hvis det ikke lykkedes.
  async function leaveCurrent() {
    clearTimeout(state.saveTimer);
    try { await enqueue(saveTask); } catch (e) { handleError(e); }
    if (isDirty()) {
      showBanner("Noten er ikke gemt endnu. Vent et øjeblik, og prøv igen.", { error: true });
      return false;
    }
    return true;
  }

  // ---- gem ----------------------------------------------------------------

  function scheduleSave(delay) {
    clearTimeout(state.saveTimer);
    state.saveTimer = setTimeout(() => { enqueue(saveTask).catch(handleError); },
      delay === undefined ? CFG.saveDelay : delay);
  }

  async function saveTask() {
    let saved = false;
    for (let i = 0; i < 10 && isDirty(); i++) {
      setStatus("Gemmer");
      await doSave();
      saved = true;
    }
    if (saved) setStatus("Gemt");
  }

  async function doSave() {
    const form = readForm();
    if (state.selected === null) {
      const note = await dav.createNote(state.collection, form);
      state.notes.set(note.uid, note);
      state.selected = note.uid;
      state.baseline = form;
      state.shownEtag = note.etag;
      els.btnDelete.disabled = false;
      updateMeta(note);
      render();
      return;
    }
    const note = state.notes.get(state.selected);
    const r = await dav.saveNote(note, form);
    if (r.status === 412) return resolveConflict(note, form);
    state.notes.set(note.uid, r.note);
    state.baseline = form;
    state.shownEtag = r.note.etag;
    updateMeta(r.note);
    render();
  }

  // Noten er ændret (eller slettet) et andet sted, mens den var åben her.
  async function resolveConflict(note, form) {
    const server = await dav.getNote(note.href);

    if (!server) { // slettet et andet sted: opret den igen med vores tekst
      const raw = Ical.updateIcs(note.raw, form);
      const r = await dav.putNote(note.href, raw, null);
      if (r.status === 412) throw new Dav.DavError(412, "noten kunne ikke oprettes igen");
      const again = Dav.toNote(note.href, r.etag, raw);
      state.notes.set(again.uid, again);
      state.baseline = form;
      state.shownEtag = again.etag;
      updateMeta(again);
      render();
      showBanner("Noten var slettet et andet sted. Den er oprettet igen med dine ændringer.");
      return;
    }

    const serverNote = Dav.toNote(note.href, server.etag, server.raw);
    const serverForm = { title: serverNote.title, body: serverNote.body, tags: serverNote.tags };
    if (sameForm(form, serverForm)) { // samme indhold: ingen konflikt
      state.notes.set(serverNote.uid, serverNote);
      state.baseline = form;
      state.shownEtag = serverNote.etag;
      updateMeta(serverNote);
      render();
      return;
    }

    // Behold begge: serverens version i originalen, vores tekst som ny note.
    const copy = await dav.createNote(state.collection, {
      title: (form.title + " (konflikt)").trim(), body: form.body, tags: form.tags,
    });
    state.notes.set(serverNote.uid, serverNote);
    state.notes.set(copy.uid, copy);
    state.selected = copy.uid;
    // Editoren viser nu kopien, så den skal også vise kopiens titel (med "(konflikt)"),
    // ellers ville næste gem fjerne mærket igen.
    state.baseline = { title: copy.title, body: form.body, tags: form.tags };
    if (els.title.value === form.title) els.title.value = copy.title;
    state.shownEtag = copy.etag;
    els.btnDelete.disabled = false;
    updateMeta(copy);
    render();
    showBanner('Noten blev ændret et andet sted. Din version er gemt som en ny note med "(konflikt)" i titlen.',
      { sticky: true });
  }

  // ---- slet ---------------------------------------------------------------

  async function removeCurrent() {
    if (state.selected === null) return;
    const n = state.notes.get(state.selected);
    if (!window.confirm('Slet noten "' + (n.title || "uden titel") + '"?')) return;
    clearTimeout(state.saveTimer);
    await enqueue(async () => {
      const result = await dav.deleteNote(n.href, n.etag);
      if (result === "conflict") {
        showBanner("Noten blev ændret et andet sted og er ikke slettet. Se den nye version, og slet igen, hvis du stadig vil.",
          { error: true });
        await refreshTask(true);
        return;
      }
      state.notes.delete(n.uid);
      closeEditor();
      showBanner("Noten er slettet.");
    }).catch(handleError);
  }

  // ---- opdatering fra serveren --------------------------------------------

  async function refreshTask(force) {
    const ctag = await dav.getCtag(state.collection);
    if (!force && ctag && ctag === state.ctag) return;
    const list = await dav.listNotes(state.collection);
    state.ctag = ctag;
    merge(list);
    render();
  }

  function merge(list) {
    const incoming = new Map(list.map((n) => [n.uid, n]));
    const cur = state.selected;
    const editing = cur !== null && isDirty(); // rør ikke ved noten, mens der skrives i den
    for (const [uid, n] of incoming) {
      if (!(editing && uid === cur)) state.notes.set(uid, n);
    }
    for (const uid of Array.from(state.notes.keys())) {
      if (incoming.has(uid) || (editing && uid === cur)) continue;
      state.notes.delete(uid);
    }
    if (cur !== null && !editing) {
      if (!state.notes.has(cur)) {
        closeEditor();
        showBanner("Noten blev slettet et andet sted.");
      } else if (state.notes.get(cur).etag !== state.shownEtag) {
        openNote(cur, false);
      }
    }
  }

  function softError(e) {
    if (e instanceof Dav.NetworkError) setStatus("Offline");
    else handleError(e);
  }

  function pollNow() {
    if (document.hidden || !state.collection) return;
    enqueue(() => refreshTask(false)).catch(softError);
  }

  // ---- start --------------------------------------------------------------

  function fillCollections(cols) {
    els.collection.textContent = "";
    for (const c of cols) {
      const o = document.createElement("option");
      o.value = c.href;
      o.textContent = c.name;
      els.collection.append(o);
    }
    els.collection.hidden = cols.length < 2;
  }

  async function start() {
    try {
      setStatus("Henter");
      let cols;
      const override = new URLSearchParams(location.search).get("col");
      if (override) {
        cols = [{ href: withSlash(override), name: "Noter", journal: true, onlyJournal: true }];
      } else {
        cols = (await dav.discover()).filter((c) => c.journal)
          .sort((a, b) => Number(b.onlyJournal) - Number(a.onlyJournal) || a.name.localeCompare(b.name));
      }
      if (!cols.length) {
        setStatus("");
        showBanner("Der er ingen samling til noter på serveren. Opret en med \"notesync.py mkcol\", eller angiv stien i adresselinjen, fx ?col=/bruger/noter/.",
          { error: true });
        return;
      }
      fillCollections(cols);
      const stored = storeGet();
      state.collection = (cols.find((c) => c.href === stored) || cols[0]).href;
      els.collection.value = state.collection;
      await enqueue(() => refreshTask(true));
      if (els.banner.classList.contains("error")) hideBanner();
      setStatus("");
    } catch (e) {
      setStatus("");
      handleError(e);
    }
  }

  // ---- hændelser ----------------------------------------------------------

  function onEdit() {
    if (isDirty()) { setStatus("Ændret"); scheduleSave(); }
    else setStatus("");
  }
  els.title.addEventListener("input", onEdit);
  els.tags.addEventListener("input", onEdit);
  els.body.addEventListener("input", onEdit);

  els.search.addEventListener("input", () => { state.query = els.search.value; render(); });

  els.list.addEventListener("click", async (e) => {
    const btn = e.target.closest("button.note");
    if (!btn) return;
    const uid = btn.dataset.uid;
    if (uid === state.selected) { document.body.dataset.view = "editor"; return; }
    if (await leaveCurrent()) openNote(uid);
  });

  els.btnNew.addEventListener("click", async () => { if (await leaveCurrent()) startNew(); });
  els.btnBack.addEventListener("click", async () => {
    if (await leaveCurrent()) document.body.dataset.view = "list";
  });
  els.btnDelete.addEventListener("click", removeCurrent);
  els.bannerClose.addEventListener("click", hideBanner);

  els.collection.addEventListener("change", async () => {
    if (!(await leaveCurrent())) { els.collection.value = state.collection; return; }
    state.collection = els.collection.value;
    storeSet(state.collection);
    state.notes.clear();
    state.ctag = null;
    closeEditor();
    enqueue(() => refreshTask(true)).catch(handleError);
  });

  document.addEventListener("keydown", (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
      e.preventDefault();
      clearTimeout(state.saveTimer);
      enqueue(saveTask).catch(handleError);
    }
  });

  document.addEventListener("visibilitychange", () => {
    if (document.hidden) { clearTimeout(state.saveTimer); enqueue(saveTask).catch(() => {}); }
    else pollNow();
  });

  window.addEventListener("beforeunload", (e) => {
    if (isDirty()) { e.preventDefault(); e.returnValue = ""; }
  });

  setInterval(pollNow, CFG.pollMs);
  start();
})();
