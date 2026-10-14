// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
import { LessonSession, planLesson, validateExercise } from "./engine.mjs";
import { locale, t } from "./i18n.mjs";

const app = document.querySelector("#app");
const native = typeof globalThis.LearningBridge?.postMessage === "function";
const params = new URLSearchParams(location.search);
const SESSION_KEY = "learning.session";
const PENDING_KEY = "learning.pending";
const SESSION_MAX_AGE_MS = 7 * 24 * 3600 * 1000;

document.documentElement.lang = locale();
if (["dark", "light"].includes(params.get("theme"))) { document.documentElement.dataset.theme = params.get("theme"); }

/** Which screen is showing; drives the Android back button. */
let screen = "home";
let session;
let course;
let current;
let response;
let submitted = false;
let busy = false;
let lessonStartedAt = 0;
let demo;
let homeMessage;

// ---------------------------------------------------------------- utilities

function node(tag, text, attrs = {}) {
    const result = document.createElement(tag);
    if (text !== undefined && text !== null) { result.textContent = text; }
    for (const [key, value] of Object.entries(attrs)) {
        if (value === undefined || value === null || value === false) { continue; }
        if (key === "class") { result.className = value; }
        else { result.setAttribute(key, value === true ? "" : value); }
    }
    return result;
}
function button(text, action, className = "", attrs = {}) {
    const result = node("button", text, { class: className, type: "button", ...attrs });
    result.addEventListener("click", action);
    return result;
}
const ICONS = {
    close: "M6 6l12 12M18 6L6 18",
    mic: "M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3",
    sync: "M4 12a8 8 0 0 1 13.7-5.6L20 9M20 4v5h-5M20 12a8 8 0 0 1-13.7 5.6L4 15M4 20v-5h5",
    back: "M15 6l-6 6 6 6",
    search: "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4",
};
function icon(name) {
    const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    svg.setAttribute("viewBox", "0 0 24 24");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("class", "icon");
    const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
    path.setAttribute("d", ICONS[name]);
    svg.append(path);
    return svg;
}
function shuffle(items) {
    const result = [...items];
    for (let index = result.length - 1; index > 0; index--) {
        const other = Math.floor(Math.random() * (index + 1));
        [result[index], result[other]] = [result[other], result[index]];
    }
    return result;
}
function displayName(name) {
    return String(name).replaceAll("::", " · ");
}
/** Storage may be unavailable or full; the lesson must still work without it. */
const store = {
    get(key, fallback) {
        try {
            const raw = localStorage.getItem(key);
            return raw === null ? fallback : JSON.parse(raw);
        } catch {
            return fallback;
        }
    },
    set(key, value) {
        try {
            if (value === undefined) { localStorage.removeItem(key); }
            else { localStorage.setItem(key, JSON.stringify(value)); }
        } catch {
            // Progress is best effort; reviews themselves are written to the collection.
        }
    },
};

// ---------------------------------------------------------------- native bridge

const pendingRequests = new Map();
let sequence = 0;
globalThis.learningReply = ({ id, result, error }) => {
    const pending = pendingRequests.get(id);
    if (!pending) { return; }
    clearTimeout(pending.timer);
    pendingRequests.delete(id);
    if (error) { pending.reject(new Error(error)); }
    else { pending.resolve(result); }
};
function request(method, payload = {}, timeoutMs = 45000) {
    return new Promise((resolve, reject) => {
        const id = ++sequence;
        const timer = timeoutMs
            ? setTimeout(() => {
                pendingRequests.delete(id);
                reject(new Error(t("error.timeout")));
            }, timeoutMs)
            : undefined;
        pendingRequests.set(id, { resolve, reject, timer });
        globalThis.LearningBridge.postMessage(JSON.stringify({ id, method, params: payload }));
    });
}
const isStale = (error) => String(error?.message).startsWith("STALE:");
const cleanMessage = (error) => String(error?.message || error).replace(/^STALE:\s*/, "");

/** Android back button. Returns true when the page handled it. */
globalThis.learningBack = () => {
    const dialog = document.querySelector("dialog[open]");
    if (dialog) {
        dialog.close();
        return true;
    }
    if (screen === "lesson") {
        confirmExit();
        return true;
    }
    if (screen === "finish" || screen === "error") {
        home();
        return true;
    }
    // While a lesson is loading, ignore back instead of closing mid-request.
    return screen === "loading";
};

// ---------------------------------------------------------------- companion

function companion(message, state = "idle", compact = false) {
    const row = node("div", undefined, { class: compact ? "pet-row compact" : "pet-row" });
    const pet = node("div", undefined, { class: "pet", role: "img", "aria-label": t("pet.label"), "data-state": state });
    const words = node("p", message, { class: "pet-message", role: "status", "aria-live": "polite" });
    row.append(pet, words);
    return row;
}
function setPet(state, message) {
    const pet = app.querySelector(".pet");
    if (pet) { pet.dataset.state = state; }
    const words = app.querySelector(".pet-message");
    if (words && message) { words.textContent = message; }
}
setInterval(() => {
    const pet = app.querySelector(".pet[data-state=\"idle\"]");
    if (!pet) { return; }
    pet.dataset.state = "blink";
    setTimeout(() => {
        if (pet.dataset.state === "blink") { pet.dataset.state = "idle"; }
    }, 180);
}, 4800);

function showError(container, error, retry) {
    const box = node("div", undefined, { class: "notice error", role: "alert" });
    box.append(node("p", t("error.generic", { message: cleanMessage(error) })));
    if (retry) { box.append(button(t("error.retry"), retry, "secondary")); }
    container.append(box);
}

// ---------------------------------------------------------------- persistence

function pendingAnswers() {
    const queue = store.get(PENDING_KEY, []);
    return Array.isArray(queue) ? queue : [];
}
function dropPending(token) {
    store.set(PENDING_KEY, pendingAnswers().filter((item) => item.token !== token));
}
/** Saves one review. Kept in a local outbox first, so an interrupted save is retried later. */
async function persistAnswer(answer) {
    if (!pendingAnswers().some((item) => item.token === answer.token)) {
        store.set(PENDING_KEY, [...pendingAnswers(), answer]);
    }
    try {
        await request("answer", answer);
    } catch (error) {
        if (isStale(error)) { dropPending(answer.token); }
        throw error;
    }
    dropPending(answer.token);
}
/** Retries the outbox. Failures are left for the next attempt and never block the UI. */
async function flushPending() {
    for (const answer of pendingAnswers()) {
        try {
            await persistAnswer(answer);
        } catch {
            // Stale entries are dropped by persistAnswer; others stay queued.
        }
    }
    return pendingAnswers().length;
}
function saveProgress() {
    if (!session || !course) { return; }
    store.set(SESSION_KEY, { course, snapshot: session.snapshot(), startedAt: lessonStartedAt, savedAt: Date.now() });
}
function clearProgress() {
    store.set(SESSION_KEY, undefined);
}
function savedProgress() {
    const saved = store.get(SESSION_KEY, null);
    if (!saved?.course || !saved.snapshot || Date.now() - (saved.savedAt ?? 0) > SESSION_MAX_AGE_MS) { return null; }
    if (!native && !demo?.courses.some((item) => item.id === saved.course.id)) { return null; }
    return saved;
}

// ---------------------------------------------------------------- home

async function loadCourses() {
    if (native) {
        return request("courses");
    }
    demo ||= await fetch("examples.json").then((result) => result.json());
    return {
        fsrs: true,
        demo: true,
        courses: demo.courses.map((item) => ({
            id: item.id,
            name: item.name,
            total: item.cards.length,
            due: 0,
            new: item.cards.length,
        })),
    };
}

async function home() {
    screen = "home";
    session = undefined;
    current = undefined;
    busy = false;
    document.querySelector("dialog")?.remove();
    const message = homeMessage;
    homeMessage = undefined;
    const pet = companion(message ?? t("pet.greeting"), message ? "encourage" : "idle");
    app.replaceChildren(
        node("p", t("app.eyebrow"), { class: "eyebrow" }),
        node("h1", t("app.title"), { tabindex: "-1" }),
        pet,
        node("p", native ? t("home.native") : t("home.demo"), { class: "muted lead" }),
    );
    const body = node("div", undefined, { class: "home-body", "aria-busy": "true" });
    body.append(skeleton());
    app.append(body);
    window.scrollTo(0, 0);
    try {
        const pending = native ? await flushPending() : 0;
        const data = await loadCourses();
        if (screen !== "home") { return; }
        body.replaceChildren();
        body.removeAttribute("aria-busy");
        renderResume(body);
        if (pending) { body.append(node("p", t("home.pendingSaves", { count: pending }), { class: "notice" })); }
        if (!data.fsrs) {
            const banner = node("div", undefined, { class: "notice warn" });
            banner.append(
                node("strong", t("home.fsrsTitle")),
                node("p", t("home.fsrsBody")),
                button(t("home.fsrsButton"), () => request("reviewSettings").catch((error) => showError(body, error)), "secondary"),
            );
            body.append(banner);
        }
        const due = data.courses.reduce((sum, item) => sum + item.due + item.new, 0);
        if (!message) {
            setPet(
                data.courses.length && !due ? "complete" : "idle",
                !data.courses.length
                    ? t("pet.greeting")
                    : due
                    ? t("pet.greetingDue", { count: due })
                    : t("pet.greetingNone"),
            );
        }
        if (!data.courses.length) { renderEmpty(body); }
        else { renderCourses(body, data.courses, data.fsrs); }
        body.append(renderHomeActions(data));
    } catch (error) {
        body.replaceChildren();
        body.removeAttribute("aria-busy");
        showError(body, error, home);
        body.append(renderHomeActions({}));
    }
}

function skeleton() {
    const box = node("div", undefined, { class: "courses" });
    box.append(node("span", t("home.loading"), { class: "visually-hidden" }));
    for (let index = 0; index < 3; index++) { box.append(node("div", undefined, { class: "course skeleton" })); }
    return box;
}

function renderResume(container) {
    const saved = savedProgress();
    if (!saved) { return; }
    const restored = LessonSession.restore(saved.snapshot);
    if (!restored) {
        clearProgress();
        return;
    }
    const card = node("section", undefined, { class: "notice resume", "aria-label": t("home.resumeTitle") });
    card.append(
        node("strong", t("home.resumeTitle")),
        node("p", t("home.resumeBody", {
            course: displayName(saved.course.name),
            done: restored.completed,
            total: restored.total,
        })),
    );
    const actions = node("div", undefined, { class: "row" });
    actions.append(
        button(t("home.resume"), () => {
            course = saved.course;
            session = restored;
            lessonStartedAt = saved.startedAt || Date.now();
            renderNext();
        }, "primary"),
        button(t("home.discard"), async () => {
            if (await ask(t("home.discard"), t("home.discardConfirm"), t("home.discard"), t("lesson.exitStay"))) {
                clearProgress();
                card.remove();
            }
        }, "secondary"),
    );
    card.append(actions);
    container.append(card);
}

function renderEmpty(container) {
    const box = node("section", undefined, { class: "empty" });
    const steps = node("ol");
    for (const key of ["home.emptyStep1", "home.emptyStep2", "home.emptyStep3"]) { steps.append(node("li", t(key))); }
    box.append(node("h2", t("home.emptyTitle")), steps);
    container.append(box);
}

function courseButton(item, fsrs) {
    const [subject, ...rest] = String(item.name).split("::");
    const entry = button("", () => start(item), "course", { disabled: !fsrs });
    const meta = item.due || item.new
        ? [item.due ? t("home.dueCount", { count: item.due }) : null, item.new ? t("home.newCount", { count: item.new }) : null]
            .filter(Boolean).join(" · ")
        : t("home.allDone");
    entry.append(
        node("span", subject, { class: "badge" }),
        node("strong", rest.length ? rest.join(" · ") : subject),
        node("span", meta, { class: item.due || item.new ? "course-meta due" : "course-meta" }),
        node("span", t("home.total", { count: item.total }), { class: "muted small" }),
    );
    return entry;
}

function renderCourses(container, courses, fsrs) {
    const sorted = [...courses].sort((a, b) => (b.due - a.due) || (b.new - a.new) || a.name.localeCompare(b.name));
    const dueCourses = sorted.filter((item) => item.due || item.new);
    const otherCourses = sorted.filter((item) => !item.due && !item.new);
    const lists = [];
    let search;
    if (courses.length > 6) {
        const label = node("label", undefined, { class: "search" });
        label.append(icon("search"));
        search = node("input", undefined, { type: "search", placeholder: t("home.search"), "aria-label": t("home.search") });
        label.append(search);
        container.append(label);
    }
    for (const [title, items] of [[t("home.sectionDue"), dueCourses], [t("home.sectionOther"), otherCourses]]) {
        if (!items.length) { continue; }
        const section = node("section", undefined, { class: "course-section" });
        const grid = node("div", undefined, { class: "courses" });
        for (const item of items) {
            const entry = courseButton(item, fsrs);
            entry.dataset.name = item.name.toLowerCase();
            grid.append(entry);
        }
        section.append(node("h2", title, { class: "section-title" }), grid);
        container.append(section);
        lists.push(section);
    }
    const noMatch = node("p", t("home.noMatch"), { class: "muted", hidden: true });
    container.append(noMatch);
    search?.addEventListener("input", () => {
        const query = search.value.trim().toLowerCase();
        let visible = 0;
        for (const section of lists) {
            let shown = 0;
            for (const entry of section.querySelectorAll(".course")) {
                entry.hidden = !!query && !entry.dataset.name.includes(query);
                if (!entry.hidden) { shown++; }
            }
            section.hidden = !shown;
            visible += shown;
        }
        noMatch.hidden = visible > 0;
    });
}

function renderHomeActions(data) {
    const actions = node("div", undefined, { class: "home-actions" });
    if (!native) { return actions; }
    const sync = button("", () => request("sshSettings").catch(() => {}), "secondary");
    sync.append(icon("sync"), node("span", data.sshConnected ? t("home.syncConnected") : t("home.sync")));
    if (data.sshConnected) { sync.classList.add("connected"); }
    const back = button("", () => request("close").catch(() => {}), "secondary");
    back.append(icon("back"), node("span", t("home.backToAnki")));
    actions.append(back, sync);
    return actions;
}

// ---------------------------------------------------------------- lesson

async function start(selected) {
    if (busy) { return; }
    busy = true;
    screen = "loading";
    app.replaceChildren(companion(t("lesson.loading"), "thinking"), node("div", undefined, { class: "spinner", role: "progressbar", "aria-label": t("lesson.loading") }));
    try {
        let cards;
        let skipped = 0;
        if (native) {
            const result = await request("lesson", { deckId: selected.id }, 90000);
            cards = result.cards;
            skipped = result.skipped ?? 0;
        } else {
            demo ||= await fetch("examples.json").then((result) => result.json());
            cards = demo.courses.find((item) => item.id === selected.id).cards.map((card) => ({
                ...card,
                noteId: card.id,
                expectedReps: 0,
                token: crypto.randomUUID(),
                isNew: true,
            }));
        }
        const valid = cards.filter((card) => {
            try {
                validateExercise(card.exercise);
                return true;
            } catch {
                skipped++;
                return false;
            }
        });
        const planned = planLesson(valid);
        busy = false;
        if (!planned.length) {
            homeMessage = skipped ? t("lesson.skipped", { count: skipped }) : t("pet.noDue");
            await home();
            return;
        }
        course = { id: selected.id, name: selected.name };
        session = new LessonSession(planned);
        lessonStartedAt = Date.now();
        clearProgress();
        renderNext(skipped ? t("lesson.skipped", { count: skipped }) : undefined);
    } catch (error) {
        busy = false;
        screen = "error";
        app.replaceChildren(companion(t("pet.retryNow"), "encourage"));
        showError(app, error, () => start(selected));
        app.append(button(t("finish.home"), home, "secondary"));
    }
}

function mediaUrl(filename) {
    return native
        ? `https://appassets.androidplatform.net/media/${encodeURIComponent(filename)}`
        : `media/${encodeURIComponent(filename)}`;
}

function renderNext(notice) {
    current = session.next();
    if (!current) {
        finish();
        return;
    }
    screen = "lesson";
    saveProgress();
    response = undefined;
    submitted = false;
    const { card, step, stepIndex } = current;
    app.replaceChildren();
    const bar = node("header", undefined, { class: "lesson-bar" });
    const close = button("", confirmExit, "icon-button", { "aria-label": t("lesson.exitLabel"), title: t("lesson.exitLabel") });
    close.append(icon("close"));
    const progress = node("div", undefined, {
        class: "progress",
        role: "progressbar",
        "aria-label": t("lesson.progress"),
        "aria-valuemin": "0",
        "aria-valuemax": String(session.total),
        "aria-valuenow": String(session.completed),
    });
    const fill = node("span");
    fill.style.width = `${(100 * session.completed) / Math.max(1, session.total)}%`;
    progress.append(fill);
    bar.append(close, progress, node("span", t("lesson.progressText", { done: session.completed, total: session.total }), { class: "muted count" }));
    app.append(bar, companion(notice ?? t("pet.think"), notice ? "encourage" : "idle", true));

    const question = node("section", undefined, { class: "card question" });
    const tags = node("div", undefined, { class: "tags" });
    tags.append(node("span", t(card.priority?.key ?? "lesson.priority2"), { class: "badge" }));
    tags.append(node("span", card.ability || t("lesson.defaultAbility"), { class: "badge quiet" }));
    if (card.isNew) { tags.append(node("span", t("lesson.new"), { class: "badge quiet" })); }
    const meta = card.exercise.steps.length > 1
        ? `${card.title} · ${t("lesson.step", { index: stepIndex + 1, total: card.exercise.steps.length })}`
        : card.title;
    const heading = node("h2", step.prompt, { class: "prompt", tabindex: "-1" });
    question.append(tags, node("p", meta, { class: "muted meta" }), heading);
    if (step.passage) { question.append(node("div", step.passage, { class: "passage" })); }
    if (step.audio) {
        const audio = node("audio", undefined, { src: mediaUrl(step.audio), controls: true, preload: "metadata" });
        audio.addEventListener("error", () => audio.replaceWith(node("p", t("lesson.audioUnavailable"), { class: "muted small" })), { once: true });
        question.append(audio);
    }
    if (step.image && step.kind !== "hotspot") {
        const image = node("img", undefined, { src: mediaUrl(step.image), alt: step.imageAlt || step.prompt, class: "question-image" });
        image.addEventListener("error", () => image.replaceWith(node("p", t("lesson.imageUnavailable"), { class: "muted small" })), { once: true });
        question.append(image);
    }
    renderAnswer(question, step);
    app.append(question);

    const footer = node("footer", undefined, { class: "footer" });
    const actions = node("div", undefined, { class: "row" });
    actions.append(
        button(t("lesson.hint"), () => {
            if (submitted) { return; }
            session.hint();
            setPet("thinking", step.hint || t("pet.hintDefault"));
        }, "secondary"),
        button(t("lesson.check"), check, "primary"),
    );
    footer.append(actions);
    app.append(footer);
    fitFooter();
    window.scrollTo(0, 0);
    heading.focus({ preventScroll: true });
}

/** Keeps the fixed footer from covering the end of the question. */
function fitFooter() {
    const footer = app.querySelector(".footer");
    if (!footer) {
        app.style.paddingBottom = "";
        return;
    }
    app.style.paddingBottom = `${footer.offsetHeight + 24}px`;
}
new ResizeObserver(fitFooter).observe(document.body);

function renderAnswer(container, step) {
    if (["choice", "multiple"].includes(step.kind)) {
        response = step.kind === "multiple" ? [] : undefined;
        if (step.kind === "multiple") { container.append(node("p", t("answer.multipleHint"), { class: "muted small" })); }
        const options = node("div", undefined, { class: "options", role: step.kind === "choice" ? "radiogroup" : "group", "aria-label": step.prompt });
        shuffle(step.options.map((text, index) => ({ text, index }))).forEach((item, position) => {
            const choice = button("", () => {
                if (submitted) { return; }
                if (step.kind === "choice") {
                    response = item.index;
                    options.querySelectorAll(".option").forEach((entry) => {
                        entry.classList.remove("selected");
                        entry.setAttribute("aria-checked", "false");
                    });
                } else {
                    response = response.includes(item.index)
                        ? response.filter((index) => index !== item.index)
                        : [...response, item.index];
                }
                const selected = step.kind === "choice" || response.includes(item.index);
                choice.classList.toggle("selected", selected);
                choice.setAttribute("aria-checked", String(selected));
            }, "option", { role: step.kind === "choice" ? "radio" : "checkbox", "aria-checked": "false" });
            choice.dataset.index = item.index;
            choice.append(node("span", String.fromCharCode(65 + position), { class: "option-index", "aria-hidden": "true" }), node("span", item.text));
            options.append(choice);
        });
        container.append(options);
    } else if (step.kind === "order") {
        response = [];
        const chosen = node("div", undefined, { class: "chips chosen", "aria-label": t("answer.orderChosen") });
        const available = node("div", undefined, { class: "chips", "aria-label": t("answer.orderAvailable") });
        const shuffled = shuffle(step.options.map((_, index) => index));
        const redraw = () => {
            chosen.replaceChildren(...response.map((index, position) =>
                button(`${position + 1}. ${step.options[index]}`, () => {
                    if (submitted) { return; }
                    response = response.filter((item) => item !== index);
                    redraw();
                }, "chip selected")
            ));
            available.replaceChildren(
                ...shuffled.filter((index) => !response.includes(index)).map((index) =>
                    button(step.options[index], () => {
                        if (submitted) { return; }
                        response.push(index);
                        redraw();
                    }, "chip")
                ),
            );
        };
        redraw();
        container.append(node("p", t("answer.orderChosen"), { class: "muted small" }), chosen, node("p", t("answer.orderAvailable"), { class: "muted small" }), available);
    } else if (step.kind === "match") {
        response = Array(step.pairs.length).fill(-1);
        const right = shuffle(step.pairs.map((pair, index) => ({ text: pair[1], index })));
        step.pairs.forEach((pair, index) => {
            const row = node("label", undefined, { class: "match-row" });
            const select = node("select", undefined, { "aria-label": t("answer.matchLabel", { item: pair[0] }) });
            select.append(
                node("option", t("answer.matchPick"), { value: -1 }),
                ...right.map((item) => node("option", item.text, { value: item.index })),
            );
            select.addEventListener("change", () => {
                response[index] = Number(select.value);
            });
            row.append(node("span", pair[0], { class: "match-left" }), select);
            container.append(row);
        });
    } else if (step.kind === "hotspot") {
        const area = node("div", undefined, { class: "hotspot" });
        const picture = node("img", undefined, { src: mediaUrl(step.image), alt: step.imageAlt || step.prompt });
        const marker = node("div", undefined, { class: "marker", hidden: true });
        const place = (x, y) => {
            response = [x, y];
            marker.hidden = false;
            marker.style.left = `${x * 100}%`;
            marker.style.top = `${y * 100}%`;
        };
        picture.addEventListener("click", (event) => {
            if (submitted) { return; }
            const rect = picture.getBoundingClientRect();
            place((event.clientX - rect.left) / rect.width, (event.clientY - rect.top) / rect.height);
        });
        area.append(picture, marker);
        const fallback = node("details", undefined, { class: "fallback" });
        const x = node("input", undefined, { type: "number", min: 0, max: 100, inputmode: "decimal", "aria-label": t("answer.hotspotX"), placeholder: t("answer.hotspotX") });
        const y = node("input", undefined, { type: "number", min: 0, max: 100, inputmode: "decimal", "aria-label": t("answer.hotspotY"), placeholder: t("answer.hotspotY") });
        for (const input of [x, y]) {
            input.addEventListener("input", () => {
                if (x.value !== "" && y.value !== "") { place(Number(x.value) / 100, Number(y.value) / 100); }
            });
        }
        fallback.append(node("summary", t("answer.hotspotNumeric")), x, y);
        container.append(node("p", t("answer.hotspotHelp"), { class: "muted small" }), area, fallback);
    } else if (step.kind === "self") {
        const input = node("textarea", undefined, { class: "answer-input", rows: 5, placeholder: t("answer.selfPlaceholder"), "aria-label": t("answer.label") });
        let revealed = false;
        input.addEventListener("input", () => {
            response = undefined;
            container.querySelectorAll(".self-assess").forEach((entry) => {
                entry.classList.remove("selected");
                entry.setAttribute("aria-pressed", "false");
            });
            if (revealed) { session.hint(); }
        });
        const compare = button(t("answer.compare"), () => {
            if (!input.value.trim()) {
                setPet("thinking", t("pet.writeFirst"));
                input.focus();
                return;
            }
            if (revealed) { return; }
            revealed = true;
            compare.hidden = true;
            const assess = node("div", undefined, { class: "options" });
            for (const [label, correct] of [[t("answer.selfGood"), true], [t("answer.selfMiss"), false]]) {
                const option = button(label, () => {
                    if (submitted) { return; }
                    response = { answer: input.value, correct };
                    assess.querySelectorAll(".self-assess").forEach((entry) => {
                        entry.classList.remove("selected");
                        entry.setAttribute("aria-pressed", "false");
                    });
                    option.classList.add("selected");
                    option.setAttribute("aria-pressed", "true");
                }, "option self-assess", { "aria-pressed": "false" });
                assess.append(option);
            }
            container.append(node("div", step.explanation, { class: "passage" }), node("p", t("answer.selfNote"), { class: "muted small" }), assess);
        }, "secondary");
        container.append(input, compare);
    } else {
        const input = node("input", undefined, {
            class: "answer-input",
            type: "text",
            autocomplete: "off",
            autocapitalize: "off",
            spellcheck: "false",
            enterkeyhint: "done",
            lang: step.locale,
            placeholder: step.kind === "numeric" ? t("answer.numericPlaceholder") : t("answer.typePlaceholder"),
            "aria-label": t("answer.label"),
        });
        input.addEventListener("input", () => {
            response = input.value;
        });
        container.append(input);
        if (step.kind === "speak") {
            const speak = button("", async () => {
                if (submitted || speak.disabled) { return; }
                speak.disabled = true;
                setPet("thinking", t("pet.listening"));
                try {
                    if (!native) { throw new Error(t("answer.speechDemo")); }
                    const heard = await request("speech", { locale: step.locale || "en-US" }, 0);
                    if (!submitted) {
                        response = heard;
                        input.value = heard;
                        setPet("idle", t("pet.think"));
                    }
                } catch (error) {
                    setPet("encourage", cleanMessage(error));
                } finally {
                    speak.disabled = submitted;
                }
            }, "secondary speak", { "aria-label": t("answer.speak") });
            speak.append(icon("mic"), node("span", t("answer.speakShort")));
            container.append(speak);
        }
    }
}

function answerText(step) {
    switch (step.kind) {
        case "choice":
            return step.options[step.answer];
        case "multiple":
            return step.answer.map((index) => step.options[index]).join("、");
        case "order":
            return step.answer.map((index) => step.options[index]).join(" → ");
        case "match":
            return step.pairs.map((pair) => `${pair[0]} — ${pair[1]}`).join("；");
        case "numeric":
            return `${step.answer}${step.units?.length ? ` ${step.units[0]}` : ""}`;
        case "hotspot":
        case "self":
            return null;
        default:
            return step.answers[0];
    }
}

function revealOptions(step) {
    if (!["choice", "multiple"].includes(step.kind)) { return; }
    const correct = step.kind === "choice" ? [step.answer] : step.answer;
    app.querySelectorAll(".question .option").forEach((entry) => {
        const index = Number(entry.dataset.index);
        if (correct.includes(index)) { entry.classList.add("is-correct"); }
        else if (entry.classList.contains("selected")) { entry.classList.add("is-wrong"); }
    });
}

async function check() {
    if (submitted || screen !== "lesson") { return; }
    if (
        response === undefined || response === ""
        || (typeof response === "string" && !response.trim())
        || (Array.isArray(response) && (!response.length || response.includes(-1)))
    ) {
        setPet("thinking", t("pet.incomplete"));
        return;
    }
    submitted = true;
    const step = current.step;
    const result = session.submit(response);
    saveProgress();
    app.querySelectorAll(".question button,.question input,.question select,.question textarea").forEach((entry) => {
        entry.disabled = true;
    });
    revealOptions(step);
    setPet(
        result.correct ? "correct" : "encourage",
        result.correct
            ? t("pet.correct")
            : result.exhausted
            ? t("pet.exhausted")
            : result.retryGap
            ? t("pet.retryLater")
            : t("pet.retryNow"),
    );
    const footer = app.querySelector(".footer");
    footer.classList.add("sheet", result.correct ? "right" : "wrong");
    const sheet = node("div", undefined, { class: "sheet-body", role: "alert" });
    sheet.append(node("strong", result.correct ? t("lesson.correct") : t("lesson.wrong"), { class: "verdict" }));
    const answer = !result.correct && answerText(step);
    if (answer) { sheet.append(node("p", t("lesson.correctAnswer", { answer }), { class: "answer" })); }
    sheet.append(node("p", result.explanation, { class: "explanation" }));
    const status = node("p", undefined, { class: "save-status", hidden: true });
    const actions = node("div", undefined, { class: "row" });
    const lastOne = result.finished && session.queue.length === 0;
    const advance = button(lastOne ? t("lesson.finish") : t("lesson.continue"), () => {
        if (advance.disabled) { return; }
        advance.disabled = true;
        renderNext();
    }, "primary");
    actions.append(advance);
    footer.replaceChildren(sheet, status, actions);
    fitFooter();
    footer.scrollTop = 0;

    if (result.finished && native) {
        const answerData = {
            cardId: result.card.id,
            token: result.card.token,
            expectedReps: result.card.expectedReps,
            contentHash: result.card.contentHash,
            rating: result.rating,
            attempts: result.attempts,
            hinted: result.hinted,
            durationMs: Math.min(3600000, result.durationMs),
        };
        advance.disabled = true;
        const later = button(t("lesson.saveLater"), () => {
            advance.hidden = false;
            advance.disabled = false;
            advance.click();
        }, "secondary");
        const retry = button(t("lesson.saveRetry"), () => save(), "secondary");
        const save = async () => {
            status.hidden = false;
            status.textContent = t("lesson.saving");
            retry.remove();
            later.remove();
            try {
                await persistAnswer(answerData);
                status.hidden = true;
                advance.hidden = false;
                advance.disabled = false;
                advance.focus({ preventScroll: true });
            } catch (error) {
                if (isStale(error)) {
                    status.textContent = t("lesson.stale");
                    advance.disabled = false;
                } else {
                    status.textContent = t("lesson.saveFailed", { message: cleanMessage(error) });
                    advance.hidden = true;
                    actions.prepend(retry, later);
                }
            }
            fitFooter();
        };
        await save();
    } else {
        advance.focus({ preventScroll: true });
    }
}

/** In-page confirmation; resolves true for [confirmLabel]. The safe choice is the default focus. */
function ask(title, body, confirmLabel, cancelLabel) {
    document.querySelector("dialog[open]")?.close();
    return new Promise((resolve) => {
        let answer = false;
        const dialog = node("dialog", undefined, { class: "dialog", "aria-labelledby": "dialog-title" });
        const actions = node("div", undefined, { class: "row" });
        const cancel = button(cancelLabel, () => dialog.close(), "primary");
        actions.append(
            button(confirmLabel, () => {
                answer = true;
                dialog.close();
            }, "secondary"),
            cancel,
        );
        dialog.append(node("h2", title, { id: "dialog-title" }), node("p", body), actions);
        dialog.addEventListener("close", () => {
            dialog.remove();
            resolve(answer);
        });
        document.body.append(dialog);
        dialog.showModal();
        cancel.focus();
    });
}

async function confirmExit() {
    if (document.querySelector("dialog[open]")) { return; }
    if (await ask(t("lesson.exitTitle"), t("lesson.exitBody"), t("lesson.exitLeave"), t("lesson.exitStay"))) {
        if (screen !== "lesson") { return; }
        if (!submitted) { saveProgress(); }
        home();
    }
}

document.addEventListener("keydown", (event) => {
    if (event.key !== "Enter" || event.isComposing || screen !== "lesson") { return; }
    if (document.querySelector("dialog[open]")) { return; }
    const target = event.target;
    if (target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement || target instanceof HTMLButtonElement) { return; }
    event.preventDefault();
    if (submitted) {
        const advance = app.querySelector(".footer .primary");
        if (advance && !advance.disabled && !advance.hidden) { advance.click(); }
    } else {
        check();
    }
});

// ---------------------------------------------------------------- summary

async function finish() {
    screen = "finish";
    clearProgress();
    const finishedCourse = course;
    app.style.paddingBottom = "";
    const minutes = Math.max(1, Math.round((Date.now() - lessonStartedAt) / 60000));
    app.replaceChildren(
        node("p", displayName(finishedCourse.name), { class: "eyebrow" }),
        node("h1", t("finish.title"), { tabindex: "-1" }),
        companion(t("pet.complete"), "complete"),
    );
    const stats = node("div", undefined, { class: "stats card" });
    for (const [value, label] of [
        [session.completed, t("finish.completed")],
        [session.attempts, t("finish.attempts")],
        [`${Math.round((100 * session.correct) / Math.max(1, session.attempts))}%`, t("finish.accuracy")],
        [minutes, t("finish.minutes")],
    ]) {
        const item = node("div");
        item.append(node("strong", String(value)), node("span", label, { class: "muted" }));
        stats.append(item);
    }
    app.append(stats);
    const weak = session.results.filter((item) => item.rating < 3);
    const strong = session.results.filter((item) => item.rating === 3);
    if (weak.length) {
        const list = node("ul", undefined, { class: "result-list" });
        for (const item of weak) { list.append(node("li", item.title)); }
        const section = node("section", undefined, { class: "card" });
        section.append(node("h2", t("finish.weakTitle"), { class: "section-title" }), list);
        app.append(section);
    }
    if (strong.length) {
        const details = node("details", undefined, { class: "card" });
        const list = node("ul", undefined, { class: "result-list strong" });
        for (const item of strong) { list.append(node("li", item.title)); }
        details.append(node("summary", `${t("finish.strongTitle")} · ${strong.length}`), list);
        app.append(details);
    }
    const pending = native ? pendingAnswers().length : 0;
    if (pending) { app.append(node("p", t("finish.pending", { count: pending }), { class: "notice" })); }
    const actions = node("div", undefined, { class: "finish-actions" });
    actions.append(
        button(t("finish.again"), () => start(finishedCourse), "primary"),
        button(t("finish.home"), home, "secondary"),
    );
    if (native) { actions.append(button(t("finish.toAnki"), () => request("close").catch(() => {}), "secondary")); }
    app.append(actions);
    window.scrollTo(0, 0);
    app.querySelector("h1").focus({ preventScroll: true });
}

await home();
