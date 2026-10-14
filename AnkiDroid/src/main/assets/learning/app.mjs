// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html
import { LessonSession, planLesson, validateExercise } from "./engine.mjs";

const app = document.querySelector("#app");
const native = typeof globalThis.LearningBridge?.postMessage === "function";
const pendingRequests = new Map();
let sequence = 0;
let session;
let current;
let courseName;
let response;
let submitted = false;
let courses = [];
let demo;

function node(tag, text, attrs = {}) {
    const result = document.createElement(tag);
    if (text !== undefined) { result.textContent = text; }
    for (const [key, value] of Object.entries(attrs)) {
        if (key === "class") { result.className = value; }
        else { result.setAttribute(key, value); }
    }
    return result;
}
function button(text, action, className = "") {
    const result = node("button", text, { class: className, type: "button" });
    result.addEventListener("click", action);
    return result;
}
function shuffle(items) {
    const result = [...items];
    for (let index = result.length - 1; index > 0; index--) {
        const other = Math.floor(Math.random() * (index + 1));
        [result[index], result[other]] = [result[other], result[index]];
    }
    return result;
}

globalThis.learningReply = ({ id, result, error }) => {
    const pending = pendingRequests.get(id);
    if (!pending) { return; }
    clearTimeout(pending.timer);
    pendingRequests.delete(id);
    if (error) { pending.reject(new Error(error)); }
    else { pending.resolve(result); }
};
function request(method, params = {}) {
    return new Promise((resolve, reject) => {
        const id = ++sequence;
        const timer = setTimeout(() => {
            pendingRequests.delete(id);
            reject(new Error("操作超时，请重试；已提交的复习不会重复记账。"));
        }, 45000);
        pendingRequests.set(id, { resolve, reject, timer });
        globalThis.LearningBridge.postMessage(JSON.stringify({ id, method, params }));
    });
}

function companion(message = "今天也一起学一点吧。", state = "idle") {
    const row = node("div", undefined, { class: "pet-row" });
    const pet = node("div", undefined, {
        class: "pet",
        role: "img",
        "aria-label": "陪伴学习的小海豹",
        "data-state": state,
    });
    const words = node("div", message, { class: "pet-message", role: "status", "aria-live": "polite" });
    row.append(pet, words);
    return row;
}
function setPet(state, message) {
    const pet = app.querySelector(".pet");
    if (pet) { pet.dataset.state = state; }
    const words = app.querySelector(".pet-message");
    if (words) { words.textContent = message; }
}
setInterval(() => {
    const pet = app.querySelector(".pet[data-state=\"idle\"]");
    if (!pet) { return; }
    pet.dataset.state = "blink";
    setTimeout(() => {
        if (pet.dataset.state === "blink") { pet.dataset.state = "idle"; }
    }, 180);
}, 4800);

function errorMessage(error) {
    const message = node("p", error.message || String(error), { class: "error", role: "alert" });
    app.append(message);
}

async function home() {
    app.replaceChildren(
        node("div", "小海豹 · 通用学习", { class: "eyebrow" }),
        node("h1", "每次一点，慢慢掌握。"),
        companion(),
    );
    const description = native
        ? "从你的牌组开始一节短课。到期复习和薄弱题会优先安排。"
        : "示例课程 · 这里可以体验题型、错题回练和宠物反馈。";
    app.append(node("p", description, { class: "muted" }));
    try {
        if (native) {
            const pending = JSON.parse(localStorage.getItem("learning.pending") || "[]");
            for (const answer of pending) { await persistAnswer(answer); }
            courses = await request("courses");
            const settings = await request("settings");
            if (!settings.fsrs) {
                app.append(
                    node("p", "先开启智能间隔复习：进入牌组选项，启用 FSRS。该选项对整个配置文件生效。", {
                        class: "muted",
                    }),
                    button("打开复习设置", () => request("reviewSettings").catch(errorMessage)),
                );
            }
        } else {
            demo ||= await fetch("examples.json").then((result) => result.json());
            courses = demo.courses.map((course) => ({ id: course.id, name: course.name, count: course.cards.length }));
        }
        const grid = node("div", undefined, { class: "courses" });
        for (const course of courses) {
            const entry = button("", () => start(course), "course");
            entry.append(
                node("span", course.name.split("::")[0], { class: "badge" }),
                node("strong", course.name.replaceAll("::", " · ")),
                node("span", `${course.count} 个练习目标`, { class: "muted" }),
            );
            grid.append(entry);
        }
        if (!courses.length) {
            app.append(node("p", "还没有互动卡片。先在电脑端制作或导入「小海豹通用学习」卡片，再同步到手机。"));
        }
        app.append(grid);
        if (native) { app.append(button("SSH 同步连接", () => request("sshSettings").catch(errorMessage))); }
    } catch (error) {
        errorMessage(error);
        app.append(button("重试", home));
    }
}

async function start(course) {
    try {
        const cards = native
            ? await request("lesson", { deckId: course.id })
            : demo.courses.find((item) => item.id === course.id).cards.map((card) => ({
                ...card,
                noteId: card.id,
                expectedReps: 0,
                token: crypto.randomUUID(),
                isNew: true,
            }));
        cards.forEach((card) => validateExercise(card.exercise));
        const planned = planLesson(cards);
        if (!planned.length) {
            setPet("complete", "这个牌组当前没有到期题目。可以稍后再来，或选择另一个学科。休息也很重要。");
            return;
        }
        courseName = course.name;
        session = new LessonSession(planned);
        renderNext();
    } catch (error) {
        errorMessage(error);
    }
}

function mediaUrl(filename) {
    return native
        ? `https://appassets.androidplatform.net/media/${encodeURIComponent(filename)}`
        : `media/${encodeURIComponent(filename)}`;
}

function renderNext() {
    current = session.next();
    if (!current) {
        finish();
        return;
    }
    response = undefined;
    submitted = false;
    const { card, step, stepIndex } = current;
    app.replaceChildren();
    const toolbar = node("div", undefined, { class: "toolbar" });
    toolbar.append(
        button("退出短课", () => {
            if (confirm("退出后已完成题目的复习记录会保留，未完成题目下次重新安排。")) { home(); }
        }),
        node("span", `${session.completed} / ${session.cards.size}`, { class: "muted" }),
    );
    const progress = node("progress", undefined, {
        max: session.cards.size,
        value: session.completed,
        "aria-label": "短课进度",
    });
    app.append(
        toolbar,
        progress,
        companion("先自己想一想。需要帮助时，我在这里。"),
        node("div", `${courseName.replaceAll("::", " · ")}  /  ${card.ability || "练习"}`, { class: "eyebrow" }),
    );
    const question = node("section", undefined, { class: "card" });
    question.append(
        node("span", `${card.priority.label} · 权重 ${card.priority.weight}`, { class: "badge" }),
        node("p", `${card.title} · 第 ${stepIndex + 1} / ${card.exercise.steps.length} 步`, { class: "muted" }),
        node("h2", step.prompt),
    );
    if (step.passage) { question.append(node("div", step.passage, { class: "passage" })); }
    if (step.audio) {
        question.append(node("audio", undefined, { src: mediaUrl(step.audio), controls: "", preload: "none" }));
    }
    if (step.image && step.kind !== "hotspot") {
        question.append(
            node("img", undefined, {
                src: mediaUrl(step.image),
                alt: step.imageAlt || "题目配图",
                class: "question-image",
            }),
        );
    }
    renderAnswer(question, step);
    app.append(question);
    const footer = node("div", undefined, { class: "footer" });
    footer.append(
        button("提示", () => {
            session.hint();
            setPet("thinking", step.hint || "回想题目涉及的概念，也可以先写出已知条件。使用提示后会安排更多巩固练习。");
        }),
        button("检查答案", check, "primary"),
    );
    app.append(footer);
}

function renderAnswer(container, step) {
    if (["choice", "multiple"].includes(step.kind)) {
        response = step.kind === "multiple" ? [] : undefined;
        const options = node("div", undefined, { class: "options" });
        shuffle(step.options.map((text, index) => ({ text, index }))).forEach((item, position) => {
            const choice = button("", () => {
                if (submitted) { return; }
                if (step.kind === "choice") {
                    response = item.index;
                    options.querySelectorAll("button").forEach((entry) => {
                        entry.classList.remove("selected");
                        entry.setAttribute("aria-pressed", "false");
                    });
                } else {response = response.includes(item.index)
                        ? response.filter((index) => index !== item.index)
                        : [...response, item.index];}
                const selected = step.kind === "choice" || response.includes(item.index);
                choice.classList.toggle("selected", selected);
                choice.setAttribute("aria-pressed", String(selected));
            }, "option");
            choice.setAttribute("aria-pressed", "false");
            choice.append(
                node("span", String.fromCharCode(65 + position), { class: "option-index" }),
                node("span", item.text),
            );
            options.append(choice);
        });
        container.append(options);
    } else if (step.kind === "order") {
        response = [];
        const chosen = node("div", undefined, { class: "chips", "aria-label": "已排顺序" });
        const available = node("div", undefined, { class: "chips", "aria-label": "待排内容" });
        const shuffled = shuffle(step.options.map((_, index) => index));
        const redraw = () => {
            chosen.replaceChildren(...response.map((index) =>
                button(step.options[index], () => {
                    if (submitted) { return; }
                    response = response.filter((item) => item !== index);
                    redraw();
                })
            ));
            available.replaceChildren(
                ...shuffled.filter((index) => !response.includes(index)).map((index) =>
                    button(step.options[index], () => {
                        if (submitted) { return; }
                        response.push(index);
                        redraw();
                    })
                ),
            );
        };
        redraw();
        container.append(chosen, available);
    } else if (step.kind === "match") {
        response = Array(step.pairs.length).fill(-1);
        const right = shuffle(step.pairs.map((pair, index) => ({ text: pair[1], index })));
        step.pairs.forEach((pair, index) => {
            const row = node("div", undefined, { class: "match-row" });
            const select = node("select", undefined, { "aria-label": `为 ${pair[0]} 选择配对` });
            select.append(
                node("option", "选择配对", { value: -1 }),
                ...right.map((item) => node("option", item.text, { value: item.index })),
            );
            select.addEventListener("change", () => {
                response[index] = Number(select.value);
            });
            row.append(node("span", pair[0]), select);
            container.append(row);
        });
    } else if (step.kind === "hotspot") {
        const area = node("div", undefined, { class: "hotspot" });
        const picture = node("img", undefined, { src: mediaUrl(step.image), alt: step.imageAlt || step.prompt });
        const marker = node("div", undefined, { class: "marker", hidden: "" });
        picture.addEventListener("click", (event) => {
            if (submitted) { return; }
            const rect = picture.getBoundingClientRect();
            response = [(event.clientX - rect.left) / rect.width, (event.clientY - rect.top) / rect.height];
            marker.removeAttribute("hidden");
            marker.style.left = `${response[0] * 100}%`;
            marker.style.top = `${response[1] * 100}%`;
        });
        const x = node("input", undefined, {
            type: "number",
            min: 0,
            max: 100,
            placeholder: "横向百分比",
            "aria-label": "位置横坐标百分比",
        });
        const y = node("input", undefined, {
            type: "number",
            min: 0,
            max: 100,
            placeholder: "纵向百分比",
            "aria-label": "位置纵坐标百分比",
        });
        for (const input of [x, y]) {
            input.addEventListener("input", () => {
                if (x.value !== "" && y.value !== "") { response = [Number(x.value) / 100, Number(y.value) / 100]; }
            });
        }
        area.append(picture, marker);
        container.append(area, x, y);
    } else if (step.kind === "self") {
        const input = node("textarea", undefined, {
            class: "answer-input",
            rows: 5,
            placeholder: "先写出你的理解，再对照参考要点。",
            "aria-label": "你的答案",
        });
        let revealed = false;
        input.addEventListener("input", () => {
            response = undefined;
            if (revealed) { session.hint(); }
        });
        const compare = button("对照参考要点", () => {
            if (!input.value.trim()) {
                setPet("thinking", "先写出自己的理解，再对照要点吧。");
                return;
            }
            if (revealed) { return; }
            revealed = true;
            container.append(
                node("div", step.explanation, { class: "passage" }),
                node("p", "这是一道开放问答，请根据参考要点评价自己的答案。", { class: "muted" }),
            );
            for (const [label, correct] of [["包含主要要点", true], ["还有遗漏，需要回练", false]]) {
                const assess = button(label, () => {
                    if (submitted) { return; }
                    response = { answer: input.value, correct };
                    container.querySelectorAll(".self-assess").forEach((entry) => {
                        entry.classList.remove("selected");
                        entry.setAttribute("aria-pressed", "false");
                    });
                    assess.classList.add("selected");
                    assess.setAttribute("aria-pressed", "true");
                }, "self-assess");
                assess.setAttribute("aria-pressed", "false");
                container.append(assess);
            }
        });
        container.append(input, compare);
    } else {
        const input = node("input", undefined, {
            class: "answer-input",
            type: "text",
            autocomplete: "off",
            spellcheck: "false",
            placeholder: step.kind === "numeric" ? "输入数值与单位，例如 2 m/s²" : "输入你的答案",
            "aria-label": "你的答案",
        });
        input.addEventListener("input", () => {
            response = input.value;
        });
        input.addEventListener("keydown", (event) => {
            if (event.key === "Enter") { check(); }
        });
        container.append(input);
        if (step.kind === "speak") {
            container.append(button("开始朗读", async () => {
                setPet("thinking", "我在听。慢慢说，表达清楚最重要。");
                try {
                    if (!native) { throw new Error("语音识别在安卓应用中使用。预览可以输入朗读内容体验反馈。"); }
                    response = await request("speech", { locale: step.locale || "en-US" });
                    input.value = response;
                } catch (error) {
                    setPet("encourage", error.message);
                }
            }));
        }
    }
}

async function persistAnswer(answer) {
    let queue = JSON.parse(localStorage.getItem("learning.pending") || "[]");
    if (!queue.some((item) => item.token === answer.token)) {
        queue.push(answer);
        localStorage.setItem("learning.pending", JSON.stringify(queue));
    }
    try {
        await request("answer", answer);
    } catch (error) {
        if (error.message.startsWith("STALE:")) {
            queue = JSON.parse(localStorage.getItem("learning.pending") || "[]").filter((item) =>
                item.token !== answer.token
            );
            localStorage.setItem("learning.pending", JSON.stringify(queue));
        }
        throw error;
    }
    queue = JSON.parse(localStorage.getItem("learning.pending") || "[]").filter((item) => item.token !== answer.token);
    localStorage.setItem("learning.pending", JSON.stringify(queue));
}

async function check() {
    if (submitted) { return; }
    if (
        response === undefined || response === ""
        || (Array.isArray(response) && (!response.length || response.includes(-1)))
    ) {
        setPet("thinking", "先试着完成答案，再检查吧。");
        return;
    }
    submitted = true;
    const result = session.submit(response);
    app.querySelectorAll(".card button,.card input,.card select,.card textarea").forEach((entry) => {
        entry.disabled = true;
    });
    setPet(
        result.correct ? "correct" : "encourage",
        result.correct
            ? "做得好！又往前走了一小步。"
            : result.exhausted
            ? "这一步先记下，我们下次重点练习。"
            : result.retryGap
            ? "没关系，先看懂原因，过几题我们再试一次。"
            : "没关系，先看懂原因，我们再试一次。",
    );
    app.querySelector(".card").append(
        node("div", `${result.correct ? "答对了" : "再巩固一下"}\n${result.explanation}`, {
            class: `feedback ${result.correct ? "" : "wrong"}`,
            role: "status",
        }),
    );
    const footer = app.querySelector(".footer");
    footer.replaceChildren();
    const advance = button("继续", renderNext, "primary");
    footer.append(advance);
    if (result.finished && native) {
        advance.disabled = true;
        const answer = {
            cardId: result.card.id,
            token: result.card.token,
            expectedReps: result.card.expectedReps,
            contentHash: result.card.contentHash,
            rating: result.rating,
            attempts: result.attempts,
            hinted: result.hinted,
            durationMs: Math.min(3600000, result.durationMs),
        };
        const save = async () => {
            try {
                await persistAnswer(answer);
                advance.disabled = false;
                footer.querySelector(".retry")?.remove();
            } catch (error) {
                setPet(
                    "encourage",
                    error.message.startsWith("STALE:")
                        ? "这道题已在其他学习流程中更新，本次结果无法覆盖新的记录。请刷新课程后继续。"
                        : `复习记录待保存：${error.message}`,
                );
                if (!footer.querySelector(".retry")) {
                    footer.append(
                        button(
                            error.message.startsWith("STALE:") ? "刷新课程" : "重试保存",
                            error.message.startsWith("STALE:") ? home : save,
                            "retry",
                        ),
                    );
                }
            }
        };
        await save();
    }
}

function finish() {
    app.replaceChildren(
        node("div", courseName.replaceAll("::", " · "), { class: "eyebrow" }),
        node("h1", "今天又多掌握了一点。"),
        companion("短课完成！答错的题会得到更多练习，掌握的内容会在合适的时候再见面。", "complete"),
    );
    const stats = node("div", undefined, { class: "stats card" });
    for (
        const [value, label] of [[session.completed, "完成目标"], [session.attempts, "练习次数"], [
            `${Math.round(100 * session.correct / Math.max(1, session.attempts))}%`,
            "答题正确率",
        ]]
    ) {
        const item = node("div");
        item.append(node("strong", value), node("span", label, { class: "muted" }));
        stats.append(item);
    }
    app.append(stats, button("返回课程", home, "primary"));
}

await home();
