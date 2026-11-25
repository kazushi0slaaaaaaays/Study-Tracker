package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

class StudyRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        createConfiguredFirestore(context.applicationContext)
    )

    companion object {
        @Volatile
        private var configuredDb: FirebaseFirestore? = null

        fun createConfiguredFirestore(appContext: Context): FirebaseFirestore {
            configuredDb?.let { return it }
            val databaseId = appContext.getString(R.string.firestore_database_id)
            val instance = FirebaseFirestore.getInstance(databaseId)
            try {
                val settings = FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(
                        PersistentCacheSettings.newBuilder()
                            .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                            .build()
                    )
                    .build()
                instance.firestoreSettings = settings
            } catch (_: IllegalStateException) {
                // Already started; safe to keep existing persistent cache settings
            }
            configuredDb = instance
            return instance
        }
    }

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    // --- REALTIME OBSERVATION STREAMS (Two-tier error handling + ServerTimestampBehavior.ESTIMATE) ---

    private fun <T> observeCollection(
        userId: String,
        collectionName: String,
        clazz: Class<T>,
    ): Flow<List<T>> = flow {
        val path = "users/$userId/$collectionName"
        emitAll(
            userDoc(userId).collection(collectionName)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(clazz, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    private fun <T> observeSingleDoc(
        userId: String,
        collectionName: String,
        docId: String,
        clazz: Class<T>,
        defaultValue: T,
    ): Flow<T> = flow {
        val path = "users/$userId/$collectionName/$docId"
        emitAll(
            userDoc(userId).collection(collectionName).document(docId)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) {
                        snapshot.toObject(clazz, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                            ?: defaultValue
                    } else {
                        defaultValue
                    }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeSettings(userId: String): Flow<UserSettings> =
        observeSingleDoc(userId, "settings", "config", UserSettings::class.java, UserSettings())

    fun observeSubjects(userId: String): Flow<List<Subject>> =
        observeCollection(userId, "subjects", Subject::class.java)
            .map { list -> list.sortedWith(compareBy<Subject> { it.order }.thenBy { it.name }) }

    fun observeChapters(userId: String): Flow<List<Chapter>> =
        observeCollection(userId, "chapters", Chapter::class.java)
            .map { list -> list.sortedBy { it.order } }

    fun observeSessions(userId: String): Flow<List<StudySession>> =
        observeCollection(userId, "sessions", StudySession::class.java)
            .map { list -> list.sortedByDescending { it.startAtMs } }

    fun observeActiveSession(userId: String): Flow<ActiveSession> =
        observeSingleDoc(userId, "activeSession", "current", ActiveSession::class.java, ActiveSession())

    fun observeGoals(userId: String): Flow<List<StudyGoal>> =
        observeCollection(userId, "goals", StudyGoal::class.java)

    fun observeExams(userId: String): Flow<List<Exam>> =
        observeCollection(userId, "exams", Exam::class.java)
            .map { list -> list.sortedBy { it.date } }

    fun observeFiles(userId: String): Flow<List<LinkedFileEntry>> =
        observeCollection(userId, "files", LinkedFileEntry::class.java)
            .map { list -> list.sortedBy { it.order } }

    fun observeBlockRules(userId: String): Flow<List<BlockRule>> =
        observeCollection(userId, "blockRules", BlockRule::class.java)
            .map { list -> list.sortedBy { it.targetLabel.lowercase() } }

    fun observeSessionBlocklist(userId: String): Flow<SessionBlocklist> =
        observeSingleDoc(userId, "sessionBlocklist", "current", SessionBlocklist::class.java, SessionBlocklist())

    fun observeDevices(userId: String): Flow<List<DeviceRecord>> =
        observeCollection(userId, "devices", DeviceRecord::class.java)
            .map { list -> list.sortedByDescending { it.lastSeen?.toDate()?.time ?: 0L } }

    fun observeDailyStreak(userId: String): Flow<DailyStreak> =
        observeSingleDoc(userId, "streak", "current", DailyStreak::class.java, DailyStreak())

    // --- HEARTBEAT & SERVER TIME OFFSET ---

    suspend fun registerDeviceAndMeasureOffset(deviceId: String, deviceName: String): Result<Long> {
        var path = "users/unauthenticated/devices/$deviceId"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid).collection("devices").document(deviceId)
            path = ref.path
            val existingSnap = try {
                ref.get().await()
            } catch (_: Exception) {
                null
            }
            val beforeMs = System.currentTimeMillis()
            if (existingSnap != null && existingSnap.exists()) {
                ref.update(
                    mapOf(
                        "name" to deviceName.take(100).ifBlank { "Android Phone" },
                        "lastSeen" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                ref.set(
                    mapOf(
                        "name" to deviceName.take(100).ifBlank { "Android Phone" },
                        "platform" to "android",
                        "lastSeen" to FieldValue.serverTimestamp(),
                        "createdAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }
            val afterMs = System.currentTimeMillis()
            val snap = ref.get().await()
            val serverMs = snap.getTimestamp("lastSeen")?.toDate()?.time
            val offsetMs = if (serverMs != null && !snap.metadata.hasPendingWrites()) {
                serverMs - ((beforeMs + afterMs) / 2L)
            } else {
                0L
            }
            Result.success(offsetMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- SETTINGS ---

    suspend fun saveSettings(settings: UserSettings): Result<Unit> = runMutation(OperationType.WRITE) { uid ->
        val ref = userDoc(uid).collection("settings").document("config")
        ref.path to { ref.set(settings.toWriteMap()).await() }
    }

    // --- DAILY STREAK (25+ MINUTE SESSION COUNTER) ---

    suspend fun recordQualifyingSessionForStreak(
        todayKey: String,
        yesterdayKey: String,
        cachedStreak: DailyStreak = DailyStreak(),
    ): Result<Pair<DailyStreak, Boolean>> {
        var path = "users/unauthenticated/streak/current"
        return try {
            val uid = requireUserId()
            val ref = userDoc(uid).collection("streak").document("current")
            path = ref.path

            val existing = try {
                val snap = ref.get().await()
                if (snap.exists()) {
                    snap.toObject(DailyStreak::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?: cachedStreak
                } else {
                    cachedStreak
                }
            } catch (_: Exception) {
                cachedStreak
            }

            if (existing.lastCompletedDate == todayKey && existing.currentStreak > 0L) {
                return Result.success(existing to false)
            }

            val nextCurrent = when (existing.lastCompletedDate) {
                yesterdayKey -> (existing.currentStreak + 1L).coerceIn(1L, 100000L)
                todayKey -> existing.currentStreak.coerceAtLeast(1L)
                else -> 1L
            }
            val nextLongest = maxOf(existing.longestStreak, nextCurrent)
            val updated = DailyStreak(
                currentStreak = nextCurrent,
                longestStreak = nextLongest,
                lastCompletedDate = todayKey,
            )

            ref.set(updated.toWriteMap()).await()
            Result.success(updated to true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    // --- SUBJECTS ---

    suspend fun createSubject(subject: Subject): Result<String> {
        var path = "users/unauthenticated/subjects"
        return try {
            val uid = requireUserId()
            require(subject.name.trim().isNotEmpty()) { "Subject name cannot be empty" }
            val ref = userDoc(uid).collection("subjects").document()
            path = ref.path
            ref.set(subject.toCreateMap()).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateSubject(subject: Subject): Result<Unit> = runMutation(OperationType.UPDATE) { uid ->
        require(subject.id.isNotBlank()) { "Subject ID required" }
        require(subject.name.trim().isNotEmpty()) { "Subject name cannot be empty" }
        val ref = userDoc(uid).collection("subjects").document(subject.id)
        ref.path to { ref.update(subject.toUpdateMap()).await() }
    }

    suspend fun deleteSubject(subjectId: String): Result<Unit> = runMutation(OperationType.DELETE) { uid ->
        require(subjectId.isNotBlank()) { "Subject ID required" }
        val ref = userDoc(uid).collection("subjects").document(subjectId)
        ref.path to { ref.delete().await() }
    }

    // --- CHAPTERS ---

    suspend fun createChapter(chapter: Chapter): Result<String> {
        var path = "users/unauthenticated/chapters"
        return try {
            val uid = requireUserId()
            require(chapter.subjectId.isNotBlank()) { "Subject is required" }
            require(chapter.title.trim().isNotEmpty()) { "Chapter title cannot be empty" }
            val ref = userDoc(uid).collection("chapters").document()
            path = ref.path
            ref.set(chapter.toCreateMap()).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateChapter(chapter: Chapter): Result<Unit> = runMutation(OperationType.UPDATE) { uid ->
        require(chapter.id.isNotBlank()) { "Chapter ID required" }
        val ref = userDoc(uid).collection("chapters").document(chapter.id)
        ref.path to { ref.update(chapter.toUpdateMap()).await() }
    }

    suspend fun reorderChapters(orderedIds: List<String>): Result<Unit> = runMutation(OperationType.UPDATE) { uid ->
        val col = userDoc(uid).collection("chapters")
        "users/$uid/chapters" to {
            val batch = db.batch()
            orderedIds.forEachIndexed { idx, chapterId ->
                if (chapterId.isNotBlank()) {
                    batch.update(
                        col.document(chapterId),
                        mapOf("order" to idx.toLong(), "updatedAt" to FieldValue.serverTimestamp())
                    )
                }
            }
            batch.commit().await()
        }
    }

    suspend fun deleteChapter(chapterId: String): Result<Unit> = runMutation(OperationType.DELETE) { uid ->
        require(chapterId.isNotBlank()) { "Chapter ID required" }
        val ref = userDoc(uid).collection("chapters").document(chapterId)
        ref.path to { ref.delete().await() }
    }

    // --- STUDY SESSIONS & ACTIVE SESSION ---

    suspend fun setActiveSession(session: ActiveSession): Result<Unit> = runMutation(OperationType.WRITE) { uid ->
        val ref = userDoc(uid).collection("activeSession").document("current")
        ref.path to { ref.set(session.toWriteMap()).await() }
    }

    suspend fun createSession(session: StudySession): Result<String> {
        var path = "users/unauthenticated/sessions"
        return try {
            val uid = requireUserId()
            require(session.subjectId.isNotBlank()) { "Subject is required" }
            val ref = userDoc(uid).collection("sessions").document()
            path = ref.path
            ref.set(session.toCreateMap()).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateSession(session: StudySession): Result<Unit> = runMutation(OperationType.UPDATE) { uid ->
        require(session.id.isNotBlank()) { "Session ID required" }
        val ref = userDoc(uid).collection("sessions").document(session.id)
        ref.path to { ref.update(session.toUpdateMap()).await() }
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = runMutation(OperationType.DELETE) { uid ->
        require(sessionId.isNotBlank()) { "Session ID required" }
        val ref = userDoc(uid).collection("sessions").document(sessionId)
        ref.path to { ref.delete().await() }
    }

    // --- GOALS ---

    suspend fun upsertGoal(goal: StudyGoal): Result<Unit> = runMutation(OperationType.WRITE) { uid ->
        val col = userDoc(uid).collection("goals")
        if (goal.id.isBlank()) {
            val ref = col.document()
            ref.path to { ref.set(goal.toCreateMap()).await() }
        } else {
            val ref = col.document(goal.id)
            ref.path to { ref.update(goal.toUpdateMap()).await() }
        }
    }

    suspend fun deleteGoal(goalId: String): Result<Unit> = runMutation(OperationType.DELETE) { uid ->
        require(goalId.isNotBlank())
        val ref = userDoc(uid).collection("goals").document(goalId)
        ref.path to { ref.delete().await() }
    }

    // --- EXAMS ---

    suspend fun createExam(exam: Exam): Result<String> {
        var path = "users/unauthenticated/exams"
        return try {
            val uid = requireUserId()
            require(exam.title.trim().isNotEmpty()) { "Exam title required" }
            val ref = userDoc(uid).collection("exams").document()
            path = ref.path
            ref.set(exam.toCreateMap()).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateExam(exam: Exam): Result<Unit> = runMutation(OperationType.UPDATE) { uid ->
        require(exam.id.isNotBlank())
        val ref = userDoc(uid).collection("exams").document(exam.id)
        ref.path to { ref.update(exam.toUpdateMap()).await() }
    }

    suspend fun deleteExam(examId: String): Result<Unit> = runMutation(OperationType.DELETE) { uid ->
        require(examId.isNotBlank())
        val ref = userDoc(uid).collection("exams").document(examId)
        ref.path to { ref.delete().await() }
    }

    // --- LINKED FILES (Metadata only; URIs stay local) ---

    suspend fun createFileEntry(entry: LinkedFileEntry): Result<String> {
        var path = "users/unauthenticated/files"
        return try {
            val uid = requireUserId()
            require(entry.subjectId.isNotBlank()) { "Subject required" }
            require(entry.displayName.trim().isNotEmpty()) { "File name required" }
            val ref = userDoc(uid).collection("files").document()
            path = ref.path
            ref.set(entry.toCreateMap()).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateFileEntry(entry: LinkedFileEntry): Result<Unit> = runMutation(OperationType.UPDATE) { uid ->
        require(entry.id.isNotBlank())
        val ref = userDoc(uid).collection("files").document(entry.id)
        ref.path to { ref.update(entry.toUpdateMap()).await() }
    }

    suspend fun deleteFileEntry(fileId: String): Result<Unit> = runMutation(OperationType.DELETE) { uid ->
        require(fileId.isNotBlank())
        val ref = userDoc(uid).collection("files").document(fileId)
        ref.path to { ref.delete().await() }
    }

    // --- BLOCKER: SESSION BLOCKLIST & USAGE LIMIT RULES ---

    suspend fun saveSessionBlocklist(blocklist: SessionBlocklist): Result<Unit> =
        runMutation(OperationType.WRITE) { uid ->
            val ref = userDoc(uid).collection("sessionBlocklist").document("current")
            ref.path to { ref.set(blocklist.toWriteMap()).await() }
        }

    suspend fun createBlockRule(rule: BlockRule): Result<String> {
        var path = "users/unauthenticated/blockRules"
        return try {
            val uid = requireUserId()
            require(rule.targetId.isNotBlank()) { "App package is required" }
            val ref = userDoc(uid).collection("blockRules").document()
            path = ref.path
            ref.set(rule.toCreateMap()).await()
            Result.success(ref.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateBlockRule(
        ruleId: String,
        targetLabel: String,
        allowanceMin: Long,
        periodType: String,
        periodCount: Long,
        lockedUntilMs: Long?,
    ): Result<Unit> = runMutation(OperationType.UPDATE) { uid ->
        require(ruleId.isNotBlank())
        val ref = userDoc(uid).collection("blockRules").document(ruleId)
        val payload = mutableMapOf<String, Any>(
            "targetLabel" to targetLabel.trim().take(120),
            "allowanceMin" to allowanceMin.coerceIn(1L, 1440L),
            "periodType" to if (periodType in listOf("hours", "day", "week", "month")) periodType else "day",
            "periodCount" to periodCount.coerceIn(1L, 24L),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (lockedUntilMs != null && lockedUntilMs > 0L) {
            payload["lockedUntil"] = Timestamp(Date(lockedUntilMs))
        }
        ref.path to { ref.update(payload).await() }
    }

    suspend fun lockBlockRuleUntil(ruleId: String, lockedUntilMs: Long): Result<Unit> =
        runMutation(OperationType.UPDATE) { uid ->
            require(ruleId.isNotBlank())
            val ref = userDoc(uid).collection("blockRules").document(ruleId)
            ref.path to {
                ref.update(
                    mapOf(
                        "lockedUntil" to Timestamp(Date(lockedUntilMs)),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }
        }

    suspend fun deleteBlockRule(ruleId: String): Result<Unit> = runMutation(OperationType.DELETE) { uid ->
        require(ruleId.isNotBlank())
        val ref = userDoc(uid).collection("blockRules").document(ruleId)
        ref.path to { ref.delete().await() }
    }

    // --- BACKUP & IMPORT (JSON & CSV, NEVER INCLUDES LOCAL FILE PATHS/URIS) ---

    fun exportAllToJson(
        settings: UserSettings,
        subjects: List<Subject>,
        chapters: List<Chapter>,
        sessions: List<StudySession>,
        goals: List<StudyGoal>,
        exams: List<Exam>,
        files: List<LinkedFileEntry>,
        blockRules: List<BlockRule>,
        sessionBlocklist: SessionBlocklist,
    ): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAtMs", System.currentTimeMillis())

        root.put("settings", JSONObject().apply {
            put("theme", settings.theme)
            put("accentHex", settings.accentHex)
            put("focusMin", settings.focusMin)
            put("breakMin", settings.breakMin)
            put("longBreakMin", settings.longBreakMin)
            put("longEvery", settings.longEvery)
            put("weekStartsOn", settings.weekStartsOn)
            put("dailyGoalMin", settings.dailyGoalMin)
            put("weeklyGoalMin", settings.weeklyGoalMin)
            put("streakMinMinutes", settings.streakMinMinutes)
            put("blockDuringBreaks", settings.blockDuringBreaks)
            put("strictLock", settings.strictLock)
            put("alertSound", settings.alertSound)
            put("alertVibration", settings.alertVibration)
            put("dailyReminderHour", settings.dailyReminderHour)
            put("dailyReminderMinute", settings.dailyReminderMinute)
        })

        root.put("subjects", JSONArray().apply {
            subjects.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("colorHex", s.colorHex)
                    put("emoji", s.emoji)
                    put("courseCode", s.courseCode)
                    put("order", s.order)
                    put("archived", s.archived)
                })
            }
        })

        root.put("chapters", JSONArray().apply {
            chapters.forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id)
                    put("subjectId", c.subjectId)
                    put("parentId", c.parentId)
                    put("title", c.title)
                    put("order", c.order)
                    put("done", c.done)
                    put("doneAtMs", c.doneAtMs)
                    put("dueDate", c.dueDate)
                })
            }
        })

        root.put("sessions", JSONArray().apply {
            sessions.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("subjectId", s.subjectId)
                    put("startAtMs", s.startAtMs)
                    put("endAtMs", s.endAtMs)
                    put("durationMs", s.durationMs)
                    put("mode", s.mode)
                    put("note", s.note)
                    put("manual", s.manual)
                    put("deviceId", s.deviceId)
                })
            }
        })

        root.put("goals", JSONArray().apply {
            goals.forEach { g ->
                put(JSONObject().apply {
                    put("id", g.id)
                    put("scope", g.scope)
                    put("period", g.period)
                    put("targetMin", g.targetMin)
                })
            }
        })

        root.put("exams", JSONArray().apply {
            exams.forEach { e ->
                put(JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    put("subjectId", e.subjectId)
                    put("date", e.date)
                    put("reminderDays", JSONArray(e.reminderDays))
                })
            }
        })

        // File metadata ONLY (never local URIs/paths)
        root.put("files", JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().apply {
                    put("id", f.id)
                    put("subjectId", f.subjectId)
                    put("displayName", f.displayName)
                    put("mime", f.mime)
                    put("order", f.order)
                })
            }
        })

        root.put("blockRules", JSONArray().apply {
            blockRules.forEach { r ->
                put(JSONObject().apply {
                    put("id", r.id)
                    put("platform", r.platform)
                    put("kind", r.kind)
                    put("targetId", r.targetId)
                    put("targetLabel", r.targetLabel)
                    put("allowanceMin", r.allowanceMin)
                    put("periodType", r.periodType)
                    put("periodCount", r.periodCount)
                })
            }
        })

        root.put("sessionBlocklist", JSONObject().apply {
            put("android", JSONArray(sessionBlocklist.android))
            put("windows", JSONArray(sessionBlocklist.windows))
        })

        return root.toString(2)
    }

    fun exportSessionsToCsv(sessions: List<StudySession>, subjects: List<Subject>): String {
        val subjectMap = subjects.associateBy { it.id }
        val sb = StringBuilder()
        sb.appendLine("session_id,subject,course_code,start_ms,end_ms,duration_min,mode,manual,note")
        sessions.forEach { s ->
            val sub = subjectMap[s.subjectId]
            val subName = csvEscape(sub?.name ?: "Unknown")
            val code = csvEscape(sub?.courseCode ?: "")
            val durMin = "%.1f".format(s.durationMs / 60000.0)
            val note = csvEscape(s.note)
            sb.appendLine("${s.id},$subName,$code,${s.startAtMs},${s.endAtMs},$durMin,${s.mode},${s.manual},$note")
        }
        return sb.toString()
    }

    suspend fun importFromJson(jsonText: String, replaceExisting: Boolean): Result<Int> {
        var path = "users/unauthenticated/import"
        return try {
            val uid = requireUserId()
            path = "users/$uid"
            val root = JSONObject(jsonText)
            var count = 0

            if (replaceExisting) {
                for (colName in listOf("subjects", "chapters", "sessions", "goals", "exams", "files")) {
                    val docs = userDoc(uid).collection(colName).get().await().documents
                    docs.forEach { it.reference.delete().await() }
                }
            }

            root.optJSONObject("settings")?.let { s ->
                val settings = UserSettings(
                    theme = s.optString("theme", "dark"),
                    accentHex = s.optString("accentHex", "#6C7BFF"),
                    focusMin = s.optLong("focusMin", 25L),
                    breakMin = s.optLong("breakMin", 5L),
                    longBreakMin = s.optLong("longBreakMin", 15L),
                    longEvery = s.optLong("longEvery", 4L),
                    weekStartsOn = s.optString("weekStartsOn", "sat"),
                    dailyGoalMin = s.optLong("dailyGoalMin", 120L),
                    weeklyGoalMin = s.optLong("weeklyGoalMin", 840L),
                    streakMinMinutes = s.optLong("streakMinMinutes", 120L),
                    blockDuringBreaks = s.optBoolean("blockDuringBreaks", false),
                    strictLock = s.optBoolean("strictLock", false),
                    alertSound = s.optBoolean("alertSound", true),
                    alertVibration = s.optBoolean("alertVibration", true),
                    dailyReminderHour = s.optLong("dailyReminderHour", -1L),
                    dailyReminderMinute = s.optLong("dailyReminderMinute", 0L),
                )
                userDoc(uid).collection("settings").document("config").set(settings.toWriteMap()).await()
                count++
            }

            fun safeDocId(raw: String): String = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "").take(128)

            root.optJSONArray("subjects")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = safeDocId(o.optString("id", ""))
                    val sub = Subject(
                        name = o.optString("name", "Subject"),
                        colorHex = o.optString("colorHex", "#6C7BFF"),
                        emoji = o.optString("emoji", "📐"),
                        courseCode = o.optString("courseCode", ""),
                        order = o.optLong("order", i.toLong()),
                        archived = o.optBoolean("archived", false),
                    )
                    val ref = if (id.isNotBlank()) userDoc(uid).collection("subjects").document(id)
                    else userDoc(uid).collection("subjects").document()
                    ref.set(sub.toCreateMap()).await()
                    count++
                }
            }

            root.optJSONArray("chapters")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = safeDocId(o.optString("id", ""))
                    val subjectId = safeDocId(o.optString("subjectId", ""))
                    if (subjectId.isBlank()) continue
                    val ch = Chapter(
                        subjectId = subjectId,
                        parentId = safeDocId(o.optString("parentId", "")),
                        title = o.optString("title", "Chapter"),
                        order = o.optLong("order", i.toLong()),
                        done = o.optBoolean("done", false),
                        doneAtMs = o.optLong("doneAtMs", 0L),
                        dueDate = o.optString("dueDate", ""),
                    )
                    val ref = if (id.isNotBlank()) userDoc(uid).collection("chapters").document(id)
                    else userDoc(uid).collection("chapters").document()
                    ref.set(ch.toCreateMap()).await()
                    count++
                }
            }

            root.optJSONArray("sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = safeDocId(o.optString("id", ""))
                    val subjectId = safeDocId(o.optString("subjectId", ""))
                    if (subjectId.isBlank()) continue
                    val sess = StudySession(
                        subjectId = subjectId,
                        startAtMs = o.optLong("startAtMs", System.currentTimeMillis() - 60000L),
                        endAtMs = o.optLong("endAtMs", System.currentTimeMillis()),
                        durationMs = o.optLong("durationMs", 60000L),
                        mode = o.optString("mode", "stopwatch"),
                        note = o.optString("note", ""),
                        manual = o.optBoolean("manual", false),
                        deviceId = o.optString("deviceId", "imported"),
                    )
                    val ref = if (id.isNotBlank()) userDoc(uid).collection("sessions").document(id)
                    else userDoc(uid).collection("sessions").document()
                    ref.set(sess.toCreateMap()).await()
                    count++
                }
            }

            root.optJSONArray("goals")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = safeDocId(o.optString("id", ""))
                    val g = StudyGoal(
                        scope = o.optString("scope", "overall"),
                        period = o.optString("period", "daily"),
                        targetMin = o.optLong("targetMin", 60L),
                    )
                    val ref = if (id.isNotBlank()) userDoc(uid).collection("goals").document(id)
                    else userDoc(uid).collection("goals").document()
                    ref.set(g.toCreateMap()).await()
                    count++
                }
            }

            root.optJSONArray("exams")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = safeDocId(o.optString("id", ""))
                    val remArr = o.optJSONArray("reminderDays")
                    val remDays = buildList {
                        if (remArr != null) {
                            for (j in 0 until remArr.length()) add(remArr.optLong(j, 1L))
                        } else {
                            addAll(listOf(7L, 3L, 1L))
                        }
                    }
                    val exam = Exam(
                        title = o.optString("title", "Exam"),
                        subjectId = safeDocId(o.optString("subjectId", "")),
                        date = o.optString("date", "2026-12-31"),
                        reminderDays = remDays,
                    )
                    val ref = if (id.isNotBlank()) userDoc(uid).collection("exams").document(id)
                    else userDoc(uid).collection("exams").document()
                    ref.set(exam.toCreateMap()).await()
                    count++
                }
            }

            root.optJSONArray("files")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = safeDocId(o.optString("id", ""))
                    val subjectId = safeDocId(o.optString("subjectId", ""))
                    if (subjectId.isBlank()) continue
                    val entry = LinkedFileEntry(
                        subjectId = subjectId,
                        displayName = o.optString("displayName", "File"),
                        mime = o.optString("mime", ""),
                        order = o.optLong("order", i.toLong()),
                    )
                    val ref = if (id.isNotBlank()) userDoc(uid).collection("files").document(id)
                    else userDoc(uid).collection("files").document()
                    ref.set(entry.toCreateMap()).await()
                    count++
                }
            }

            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    private fun csvEscape(raw: String): String {
        val cleaned = raw.replace("\"", "\"\"")
        return if (cleaned.contains(",") || cleaned.contains("\n") || cleaned.contains("\"")) {
            "\"$cleaned\""
        } else cleaned
    }

    private suspend fun runMutation(
        opType: OperationType,
        block: (uid: String) -> Pair<String, suspend () -> Unit>,
    ): Result<Unit> {
        var docPath = "users/unauthenticated"
        return try {
            val uid = requireUserId()
            val (path, action) = block(uid)
            docPath = path
            action()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, opType, docPath)
            Result.failure(e)
        }
    }
}
