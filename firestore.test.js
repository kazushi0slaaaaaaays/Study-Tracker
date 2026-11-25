const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_mech_eng";
const BOB_UID = "bob_other_user";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(msAgo = 2000) {
  return new Date(Date.now() - msAgo);
}

function futureDate(msAhead = 3600000) {
  return new Date(Date.now() + msAhead);
}

test("Unauthenticated users cannot read or write any Study Tracker collection", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(db.collection(`users/${ALICE_UID}/subjects`).get());
  await assertFails(db.collection(`users/${ALICE_UID}/sessions`).get());
  await assertFails(db.doc(`users/${ALICE_UID}/activeSession/current`).get());
  await assertFails(
    db.doc(`users/${ALICE_UID}/subjects/sub_thermo`).set({
      name: "Thermodynamics",
      colorHex: "#6C7BFF",
      emoji: "🔥",
      courseCode: "ME-201",
      order: 0,
      archived: false,
      createdAt: pastDate(),
      updatedAt: pastDate(),
    })
  );
});

test("Cross-user isolation: Bob cannot read or write Alice's data", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().doc(`users/${ALICE_UID}/subjects/sub_1`).set({
      name: "Fluid Mechanics",
      colorHex: "#3DDC97",
      emoji: "💧",
      courseCode: "ME-302",
      order: 0,
      archived: false,
      createdAt: pastDate(5000),
      updatedAt: pastDate(5000),
    });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection(`users/${ALICE_UID}/subjects`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/subjects/sub_1`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/subjects/sub_1`).update({
      name: "Hijacked",
      updatedAt: pastDate(),
    })
  );
  await assertFails(bobDb.doc(`users/${ALICE_UID}/subjects/sub_1`).delete());
});

test("Owner can create, update, list, and delete subjects, chapters, sessions, goals, exams, and files", async () => {
  const db = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = pastDate(5000);

  // Settings
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/settings/config`).set({
      theme: "dark",
      accentHex: "#6C7BFF",
      focusMin: 25,
      breakMin: 5,
      longBreakMin: 15,
      longEvery: 4,
      weekStartsOn: "sat",
      dailyGoalMin: 120,
      weeklyGoalMin: 840,
      streakMinMinutes: 120,
      blockDuringBreaks: false,
      strictLock: false,
      alertSound: true,
      alertVibration: true,
      dailyReminderHour: 20,
      dailyReminderMinute: 0,
      updatedAt: created,
    })
  );

  // Subject create + partial update
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/subjects/sub_thermo`).set({
      name: "Thermodynamics",
      colorHex: "#6C7BFF",
      emoji: "🔥",
      courseCode: "ME-201",
      order: 0,
      archived: false,
      createdAt: created,
      updatedAt: created,
    })
  );
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/subjects/sub_thermo`).update({
      name: "Thermodynamics II",
      updatedAt: pastDate(1000),
    })
  );

  // Reject ghost field & immutable createdAt overwrite
  await assertFails(
    db.doc(`users/${ALICE_UID}/subjects/sub_thermo`).update({
      createdAt: pastDate(500),
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    db.doc(`users/${ALICE_UID}/subjects/sub_bad`).set({
      id: "sub_bad",
      name: "Bad Subject",
      colorHex: "#6C7BFF",
      emoji: "⚙️",
      courseCode: "",
      order: 1,
      archived: false,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Chapter
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/chapters/ch_1`).set({
      subjectId: "sub_thermo",
      parentId: "",
      title: "First Law of Thermodynamics",
      order: 0,
      done: false,
      doneAtMs: 0,
      dueDate: "2026-12-10",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Session
  const startMs = Date.now() - 1800000;
  const endMs = Date.now() - 1000;
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/sessions/sess_1`).set({
      subjectId: "sub_thermo",
      startAtMs: startMs,
      endAtMs: endMs,
      durationMs: endMs - startMs,
      mode: "pomodoro",
      note: "Entropy problems",
      manual: false,
      deviceId: "galaxy_a56",
      createdAt: created,
      updatedAt: created,
    })
  );

  // ActiveSession
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/activeSession/current`).set({
      active: true,
      subjectId: "sub_thermo",
      mode: "pomodoro",
      status: "running",
      phase: "focus",
      phaseStartedAtMs: Date.now() - 5000,
      accumulatedMs: 0,
      targetMs: 1500000,
      cycleCount: 1,
      note: "Deep focus",
      deviceId: "galaxy_a56",
      deviceName: "Galaxy A56",
      updatedAt: pastDate(1000),
    })
  );

  // Goal
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/goals/goal_1`).set({
      scope: "sub_thermo",
      period: "daily",
      targetMin: 60,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Exam
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/exams/exam_1`).set({
      title: "Thermodynamics Final",
      subjectId: "sub_thermo",
      date: "2026-12-18",
      reminderDays: [7, 3, 1],
      createdAt: created,
      updatedAt: created,
    })
  );

  // Linked File (metadata only, never URI)
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/files/file_1`).set({
      subjectId: "sub_thermo",
      displayName: "Steam_Tables.pdf",
      mime: "application/pdf",
      order: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Session blocklist & device record
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/sessionBlocklist/current`).set({
      android: ["com.instagram.android"],
      windows: ["Discord.exe"],
      updatedAt: created,
    })
  );
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/devices/dev_android_1`).set({
      name: "Samsung Galaxy A56",
      platform: "android",
      lastSeen: created,
      createdAt: created,
    })
  );

  // List queries succeed for owner
  await assertSucceeds(db.collection(`users/${ALICE_UID}/subjects`).get());
  await assertSucceeds(db.collection(`users/${ALICE_UID}/chapters`).get());
  await assertSucceeds(db.collection(`users/${ALICE_UID}/sessions`).get());
  await assertSucceeds(db.collection(`users/${ALICE_UID}/goals`).get());
  await assertSucceeds(db.collection(`users/${ALICE_UID}/exams`).get());
  await assertSucceeds(db.collection(`users/${ALICE_UID}/files`).get());
  await assertSucceeds(db.collection(`users/${ALICE_UID}/blockRules`).get());
  await assertSucceeds(db.collection(`users/${ALICE_UID}/devices`).get());
});

test("BlockRule lock rule: lockedUntil in future prevents delete and loosening, allows tightening", async () => {
  const db = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = pastDate(10000);
  const lockEnd = futureDate(3600000); // Locked for 1 hour

  // Create unlocked rule
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).set({
      platform: "android",
      kind: "limit",
      targetId: "com.instagram.android",
      targetLabel: "Instagram",
      allowanceMin: 30,
      periodType: "day",
      periodCount: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Before lock, raising allowance is allowed
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).update({
      allowanceMin: 45,
      updatedAt: pastDate(2000),
    })
  );

  // Engage lock when limit is reached
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).update({
      lockedUntil: lockEnd,
      updatedAt: pastDate(1500),
    })
  );

  // While locked: DELETE must FAIL
  await assertFails(db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).delete());

  // While locked: LOOSENING allowance (45 -> 60) must FAIL
  await assertFails(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).update({
      allowanceMin: 60,
      updatedAt: pastDate(1000),
    })
  );

  // While locked: SHORTENING period (day -> hours) must FAIL
  await assertFails(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).update({
      periodType: "hours",
      periodCount: 2,
      updatedAt: pastDate(1000),
    })
  );

  // While locked: REDUCING lockedUntil must FAIL
  await assertFails(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).update({
      lockedUntil: pastDate(500),
      updatedAt: pastDate(1000),
    })
  );

  // While locked: TIGHTENING allowance (45 -> 15) must SUCCEED
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).update({
      allowanceMin: 15,
      updatedAt: pastDate(900),
    })
  );

  // While locked: LENGTHENING period (day -> week) must SUCCEED
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/blockRules/rule_ig`).update({
      periodType: "week",
      periodCount: 1,
      updatedAt: pastDate(800),
    })
  );
});

test("BlockRule lock rule: expired lockedUntil allows deletion", async () => {
  const db = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = pastDate(20000);
  await assertSucceeds(
    db.doc(`users/${ALICE_UID}/blockRules/rule_yt`).set({
      platform: "android",
      kind: "limit",
      targetId: "com.google.android.youtube",
      targetLabel: "YouTube",
      allowanceMin: 20,
      periodType: "day",
      periodCount: 1,
      lockedUntil: pastDate(5000), // Already expired
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(db.doc(`users/${ALICE_UID}/blockRules/rule_yt`).delete());
});

test("Tampered future updatedAt timestamp is rejected", async () => {
  const db = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    db.doc(`users/${ALICE_UID}/subjects/sub_future`).set({
      name: "Future Subject",
      colorHex: "#6C7BFF",
      emoji: "🚀",
      courseCode: "ME-999",
      order: 0,
      archived: false,
      createdAt: futureDate(60000),
      updatedAt: futureDate(60000),
    })
  );
});

test("DailyStreak: owner can create and increment streak; unauth, cross-user, negative, and delete denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();

  // Unauthenticated read/write denied
  await assertFails(unauthDb.doc(`users/${ALICE_UID}/streak/current`).get());
  await assertFails(
    unauthDb.doc(`users/${ALICE_UID}/streak/current`).set({
      currentStreak: 1,
      longestStreak: 1,
      lastCompletedDate: "2026-11-25",
      updatedAt: pastDate(1000),
    })
  );

  // Owner create & increment succeed
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/streak/current`).set({
      currentStreak: 1,
      longestStreak: 1,
      lastCompletedDate: "2026-11-24",
      updatedAt: pastDate(2000),
    })
  );
  await assertSucceeds(
    aliceDb.doc(`users/${ALICE_UID}/streak/current`).update({
      currentStreak: 2,
      longestStreak: 2,
      lastCompletedDate: "2026-11-25",
      updatedAt: pastDate(1000),
    })
  );

  // Reject invalid streak (negative or longest < current)
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/streak/current`).update({
      currentStreak: -1,
      updatedAt: pastDate(500),
    })
  );
  await assertFails(
    aliceDb.doc(`users/${ALICE_UID}/streak/current`).update({
      currentStreak: 5,
      longestStreak: 2,
      updatedAt: pastDate(500),
    })
  );

  // Delete denied & cross-user access denied
  await assertFails(aliceDb.doc(`users/${ALICE_UID}/streak/current`).delete());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/streak/current`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/streak/current`).update({
      currentStreak: 99,
      longestStreak: 99,
      updatedAt: pastDate(500),
    })
  );
});

