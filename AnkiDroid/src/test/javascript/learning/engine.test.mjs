// SPDX-License-Identifier: GPL-3.0-or-later
// Run with: node --test AnkiDroid/src/test/javascript/learning/
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const assets = new URL("../../../main/assets/learning/", import.meta.url);
const { grade, LessonSession, normalizeAnswer, planLesson, priority, retrievability, validateExercise } = await import(
    new URL("engine.mjs", assets)
);
const { setLocale, t, _tablesForTest } = await import(new URL("i18n.mjs", assets));
setLocale("zh-CN");

const step = (extra) => ({ id: "s", prompt: "p", explanation: "e", ...extra });
const card = (id, steps, extra = {}) => ({ id, noteId: id, title: id, exercise: { version: 1, steps }, ...extra });

test("every bundled example exercise is valid", () => {
    const examples = JSON.parse(readFileSync(fileURLToPath(new URL("examples.json", assets)), "utf8"));
    let count = 0;
    for (const course of examples.courses) {
        for (const item of course.cards) {
            validateExercise(item.exercise);
            count++;
        }
    }
    assert.ok(count > 10);
});

test("validation rejects malformed exercises with a readable message", () => {
    assert.throws(() => validateExercise({ version: 2, steps: [] }), /练习版本必须为 1/);
    assert.throws(() => validateExercise({ version: 1, steps: [step({ kind: "nope" })] }), /不支持的题型：nope/);
    assert.throws(
        () => validateExercise({ version: 1, steps: [step({ kind: "listen", answers: ["a"], audio: "../x.wav" })] }),
        /媒体必须是/,
    );
});

test("grading covers each answer kind", () => {
    assert.equal(grade(step({ kind: "choice", options: ["a", "b"], answer: 1 }), 1), true);
    assert.equal(grade(step({ kind: "multiple", options: ["a", "b", "c"], answer: [0, 2] }), [2, 0]), true);
    assert.equal(grade(step({ kind: "multiple", options: ["a", "b", "c"], answer: [0, 2] }), [0, 0]), false);
    assert.equal(grade(step({ kind: "order", options: ["a", "b"], answer: [1, 0] }), [1, 0]), true);
    assert.equal(grade(step({ kind: "match", pairs: [["a", "1"], ["b", "2"]] }), [0, 1]), true);
    assert.equal(grade(step({ kind: "numeric", answer: 2, units: ["m/s²"] }), "２ m/s²"), true);
    assert.equal(grade(step({ kind: "numeric", answer: 2, tolerance: 0.1 }), "2.05"), true);
    assert.equal(grade(step({ kind: "numeric", answer: 2 }), "2 kg"), false);
    assert.equal(grade(step({ kind: "hotspot", target: [0.1, 0.1, 0.5, 0.5] }), [0.2, 0.3]), true);
    assert.equal(grade(step({ kind: "typed", answers: ["An Apple"] }), "  an   apple "), true);
    assert.equal(grade(step({ kind: "cloze", answers: ["疑是地上霜"], ignorePunctuation: true }), "疑是地上霜。"), true);
    assert.equal(grade(step({ kind: "self" }), { correct: true, answer: "x" }), true);
    assert.equal(grade(step({ kind: "self" }), { correct: true, answer: " " }), false);
    assert.equal(normalizeAnswer("ＡＢＣ"), "abc");
});

test("retrievability is 0.9 after exactly one stability interval", () => {
    const now = 1_700_000_000_000;
    const r = retrievability({ stability: 10, lastReview: now / 1000 - 10 * 86400, decay: 0.5 }, now);
    assert.ok(Math.abs(r - 0.9) < 1e-9);
    assert.equal(retrievability({ stability: null, lastReview: 1 }), null);
});

test("priority exposes a translatable key and failed cards jump the queue", () => {
    const failed = priority({ lastRating: 1, failures: 2, isNew: false });
    assert.equal(failed.tier, 4);
    assert.equal(failed.key, "lesson.priority4");
    assert.equal(t(failed.key), "优先回练");
    assert.equal(priority({ isNew: true }).key, "lesson.priority1");
});

test("a lesson never shows two sibling cards of the same note", () => {
    const planned = planLesson([
        card("a", [], { noteId: "n1", isNew: true }),
        card("b", [], { noteId: "n1", isNew: true }),
        card("c", [], { noteId: "n2", isNew: true }),
    ]);
    assert.deepEqual(planned.map((item) => item.id).sort(), ["a", "c"]);
});

test("wrong answers are retried later and rated Again; hints rate Hard", () => {
    let clock = 0;
    const session = new LessonSession(
        [
            card("x", [step({ kind: "choice", options: ["a", "b"], answer: 0 })]),
            card("y", [step({ kind: "choice", options: ["a", "b"], answer: 0 })]),
            card("z", [step({ kind: "choice", options: ["a", "b"], answer: 0 })]),
        ],
        { now: () => (clock += 1000) },
    );
    assert.equal(session.next().card.id, "x");
    const wrong = session.submit(1);
    assert.equal(wrong.correct, false);
    assert.equal(wrong.finished, false);
    assert.equal(session.next().card.id, "y");
    session.hint();
    assert.equal(session.submit(0).rating, 2);
    assert.equal(session.next().card.id, "z");
    assert.equal(session.submit(0).rating, 3);
    assert.equal(session.next().card.id, "x");
    const retried = session.submit(0);
    assert.equal(retried.finished, true);
    assert.equal(retried.rating, 1);
    assert.equal(session.next(), null);
    assert.deepEqual(session.results.map((item) => [item.id, item.rating]), [["y", 2], ["z", 3], ["x", 1]]);
});

test("a step is given up after three wrong attempts", () => {
    const session = new LessonSession([card("x", [step({ kind: "choice", options: ["a", "b"], answer: 0 })])]);
    for (let attempt = 1; attempt <= 3; attempt++) {
        session.next();
        const result = session.submit(1);
        assert.equal(result.exhausted, attempt === 3);
    }
    assert.equal(session.completed, 1);
});

test("calling next twice without submitting is rejected", () => {
    const session = new LessonSession([card("x", [step({ kind: "choice", options: ["a", "b"], answer: 0 })])]);
    session.next();
    assert.throws(() => session.next(), /先提交当前步骤/);
});

test("an interrupted lesson can be restored from its snapshot", () => {
    const cards = [
        card("x", [step({ kind: "choice", options: ["a", "b"], answer: 0 }), step({ id: "s2", kind: "typed", answers: ["ok"] })]),
        card("y", [step({ kind: "choice", options: ["a", "b"], answer: 0 })]),
    ];
    const session = new LessonSession(cards);
    session.next();
    session.submit(0); // first step of x correct
    session.next(); // x step 2 shown, then the app is killed
    const snapshot = JSON.parse(JSON.stringify(session.snapshot()));
    const restored = LessonSession.restore(snapshot);
    const resumed = restored.next();
    assert.equal(resumed.card.id, "x");
    assert.equal(resumed.stepIndex, 1);
    assert.equal(restored.submit("ok").finished, true);
    assert.equal(restored.next().card.id, "y");
    restored.submit(0);
    assert.equal(restored.next(), null);
    assert.equal(restored.completed, 2);
    assert.equal(restored.attempts, 3);
});

test("restoring a corrupt snapshot fails cleanly", () => {
    assert.equal(LessonSession.restore(null), null);
    assert.equal(LessonSession.restore({ version: 99 }), null);
    assert.equal(LessonSession.restore({ version: 1, cards: "x" }), null);
});

test("both languages define the same keys", () => {
    assert.deepEqual(Object.keys(_tablesForTest.en).sort(), Object.keys(_tablesForTest.zh).sort());
    setLocale("en");
    assert.equal(t("home.dueCount", { count: 3 }), "3 due");
    setLocale("zh-CN");
    assert.equal(t("home.dueCount", { count: 3 }), "3 题到期");
});
