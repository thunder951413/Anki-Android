// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

export const ABILITIES = ["Recognition", "Recall", "Application"];
export const KINDS = [
    "choice",
    "multiple",
    "typed",
    "cloze",
    "order",
    "match",
    "numeric",
    "listen",
    "speak",
    "hotspot",
    "self",
];

const finite = (value) => typeof value === "number" && Number.isFinite(value);
const requireValue = (valid, message) => {
    if (!valid) { throw new Error(message); }
};

export function validateExercise(exercise) {
    requireValue(exercise?.version === 1, "练习版本必须为 1");
    requireValue(
        Array.isArray(exercise.steps) && exercise.steps.length >= 1 && exercise.steps.length <= 20,
        "练习需要 1–20 个步骤",
    );
    const ids = new Set();
    for (const step of exercise.steps) {
        requireValue(typeof step.id === "string" && step.id.length > 0 && !ids.has(step.id), "步骤 ID 必须唯一且非空");
        ids.add(step.id);
        requireValue(KINDS.includes(step.kind), `不支持的题型：${step.kind}`);
        requireValue(typeof step.prompt === "string" && step.prompt.trim(), "每步需要题目");
        requireValue(typeof step.explanation === "string" && step.explanation.trim(), "每步需要答案解释");
        if (["choice", "multiple", "order"].includes(step.kind)) {
            requireValue(
                Array.isArray(step.options) && step.options.length >= 2
                    && step.options.every((item) => typeof item === "string"),
                "选项至少两项",
            );
            requireValue(new Set(step.options).size === step.options.length, "选项不能重复");
        }
        if (step.kind === "choice") {
            requireValue(
                Number.isInteger(step.answer) && step.answer >= 0 && step.answer < step.options.length,
                "正确选项越界",
            );
        }
        if (["multiple", "order"].includes(step.kind)) {
            requireValue(
                Array.isArray(step.answer) && step.answer.length > 0 && step.answer.every((item) =>
                    Number.isInteger(item) && item >= 0 && item < step.options.length
                ) && new Set(step.answer).size === step.answer.length,
                "答案选项无效",
            );
            if (step.kind === "order") {
                requireValue(step.answer.length === step.options.length, "排序答案需要包含所有选项");
            }
        }
        if (["typed", "cloze", "listen", "speak"].includes(step.kind)) {
            requireValue(
                Array.isArray(step.answers) && step.answers.length
                    && step.answers.every((item) => typeof item === "string" && item.trim()),
                "需要至少一个可接受答案",
            );
        }
        if (step.kind === "listen") {
            requireValue(typeof step.audio === "string" && step.audio.length > 0, "听写需要音频文件");
        }
        if (step.kind === "numeric") {
            requireValue(
                finite(step.answer) && finite(step.tolerance ?? 0) && (step.tolerance ?? 0) >= 0,
                "数值答案或容差无效",
            );
            requireValue(
                step.units === undefined
                    || (Array.isArray(step.units)
                        && step.units.every((item) => typeof item === "string" && item.trim())),
                "单位列表无效",
            );
        }
        if (step.kind === "match") {
            requireValue(
                Array.isArray(step.pairs) && step.pairs.length >= 2
                    && step.pairs.every((pair) =>
                        Array.isArray(pair) && pair.length === 2
                        && pair.every((item) => typeof item === "string" && item.trim())
                    ),
                "配对需要两组以上的文字对",
            );
            requireValue(
                [0, 1].every((side) => new Set(step.pairs.map((pair) => pair[side])).size === step.pairs.length),
                "配对两侧的文字不能重复",
            );
        }
        if (step.kind === "hotspot") {
            requireValue(
                typeof step.image === "string" && step.image.length > 0 && Array.isArray(step.target)
                    && step.target.length === 4 && step.target.every((item) => finite(item) && item >= 0 && item <= 1)
                    && step.target[0] < step.target[2] && step.target[1] < step.target[3],
                "图片区域需为归一化的 [左, 上, 右, 下]",
            );
        }
        for (const field of ["audio", "image"]) {
            if (step[field]) {
                requireValue(!/[\\/]|\.\.|^[a-z]+:/i.test(step[field]), "媒体必须是 collection.media 中的文件名");
            }
        }
    }
    return exercise;
}

export function normalizeAnswer(value, step = {}) {
    let result = String(value ?? "").normalize("NFKC").trim().replace(/\s+/gu, " ");
    if (!step.caseSensitive) { result = result.toLocaleLowerCase(step.locale || "en"); }
    if (step.ignorePunctuation) { result = result.replace(/[\p{P}]/gu, "").trim(); }
    return result;
}

export function grade(step, response) {
    switch (step.kind) {
        case "self":
            return response?.correct === true && typeof response.answer === "string"
                && response.answer.trim().length > 0;
        case "choice":
            return response === step.answer;
        case "multiple":
            return Array.isArray(response) && new Set(response).size === response.length
                && response.length === step.answer.length && response.every((item) => step.answer.includes(item));
        case "order":
            return Array.isArray(response) && response.length === step.answer.length
                && response.every((item, index) => item === step.answer[index]);
        case "match":
            return Array.isArray(response) && response.length === step.pairs.length
                && response.every((item, index) => item === index);
        case "numeric": {
            const value = String(response ?? "").normalize("NFKC").trim();
            const parsed = value.match(/^([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)\s*(.*?)$/u);
            if (!parsed) { return false; }
            const number = Number(parsed[1]);
            const unit = parsed[2].trim();
            const units = step.units ?? [];
            return finite(number)
                && Math.abs(number - step.answer)
                    <= (step.tolerance ?? 0)
                        + Number.EPSILON * Math.max(Number.MIN_VALUE, Math.abs(step.answer), Math.abs(number)) * 4
                && (units.length ? units.some((expected) => expected.normalize("NFKC").trim() === unit) : unit === "");
        }
        case "hotspot":
            return Array.isArray(response) && response.length === 2 && response.every(finite)
                && response[0] >= step.target[0] && response[0] <= step.target[2] && response[1] >= step.target[1]
                && response[1] <= step.target[3];
        default:
            return step.answers.some((answer) => normalizeAnswer(answer, step) === normalizeAnswer(response, step));
    }
}

// FSRS stability is the elapsed days at which retrievability equals 0.9.
// Use the backend's card-specific decay (FSRS-5 and FSRS-6 differ).
export function retrievability(card, now = Date.now()) {
    if (!finite(card.stability) || card.stability <= 0 || !finite(card.lastReview)) { return null; }
    const decay = finite(card.decay) && card.decay > 0 ? card.decay : 0.5;
    const elapsed = Math.max(0, (now / 1000 - card.lastReview) / 86400);
    return Math.pow(1 + (Math.pow(0.9, -1 / decay) - 1) * elapsed / card.stability, -decay);
}

export function priority(card, now = Date.now()) {
    const retention = Math.min(0.99, Math.max(0.7, card.retention ?? 0.9));
    const recall = retrievability(card, now);
    let weight = recall === null ? (card.isNew ? 35 : 55) : Math.min(85, 50 * (1 - recall) / (1 - retention));
    if (card.lastRating === 1) { weight = Math.max(85 + Math.min(15, (card.failures ?? 0) * 3), weight); }
    if (card.lastRating === 2) { weight = Math.max(60, weight); }
    weight = Math.round(Math.min(100, Math.max(0, weight)));
    const tier = weight >= 80 ? 4 : weight >= 60 ? 3 : weight >= 40 ? 2 : weight >= 20 ? 1 : 0;
    return { weight, tier, label: ["稳定", "巩固", "到期复习", "重点复习", "优先回练"][tier], retrievability: recall };
}

export function planLesson(cards, { now = Date.now(), limit = 8 } = {}) {
    const ranked = cards.map((card) => ({ ...card, priority: priority(card, now) })).sort((a, b) =>
        b.priority.weight - a.priority.weight || String(a.id).localeCompare(String(b.id))
    );
    // Sibling cards test distinct abilities, but showing them together leaks answers.
    const notes = new Set();
    return ranked.filter((card) => {
        if (notes.has(card.noteId ?? card.id)) { return false; }
        notes.add(card.noteId ?? card.id);
        return true;
    }).slice(0, limit);
}

export class LessonSession {
    constructor(cards, { now = () => Date.now() } = {}) {
        this.now = now;
        this.cards = new Map(
            cards.map((
                card,
            ) => [card.id, {
                card,
                stepIndex: 0,
                attempts: [],
                failed: false,
                hinted: false,
                finished: false,
                durationMs: 0,
            }]),
        );
        this.queue = cards.map((card) => card.id);
        this.current = null;
        this.completed = 0;
        this.correct = 0;
        this.attempts = 0;
    }
    next() {
        requireValue(this.current === null, "先提交当前步骤再继续");
        const id = this.queue.shift();
        if (id === undefined) { return null; }
        const state = this.cards.get(id);
        this.current = state;
        state.startedAt = this.now();
        return { card: state.card, step: state.card.exercise.steps[state.stepIndex], stepIndex: state.stepIndex };
    }
    hint() {
        requireValue(this.current, "没有当前题目");
        this.current.hinted = true;
    }
    submit(response) {
        requireValue(this.current, "没有当前题目");
        const state = this.current;
        const step = state.card.exercise.steps[state.stepIndex];
        const correct = grade(step, response);
        state.durationMs += Math.max(0, this.now() - state.startedAt);
        state.attempts[state.stepIndex] = (state.attempts[state.stepIndex] ?? 0) + 1;
        this.attempts += 1;
        if (correct) { this.correct += 1; }
        else { state.failed = true; }
        const exhausted = state.attempts[state.stepIndex] >= 3;
        if (correct || exhausted) { state.stepIndex += 1; }
        state.finished = state.stepIndex === state.card.exercise.steps.length;
        this.current = null;
        const retryGap = Math.min(2, this.queue.length);
        if (state.finished) { this.completed += 1; }
        else if (!correct && !exhausted) { this.queue.splice(Math.min(2, this.queue.length), 0, state.card.id); }
        else { this.queue.unshift(state.card.id); }
        return {
            correct,
            exhausted,
            explanation: step.explanation,
            finished: state.finished,
            card: state.card,
            rating: state.failed ? 1 : state.hinted ? 2 : 3,
            attempts: state.attempts.reduce((sum, item) => sum + item, 0),
            hinted: state.hinted,
            durationMs: state.durationMs,
            retryGap,
        };
    }
}
