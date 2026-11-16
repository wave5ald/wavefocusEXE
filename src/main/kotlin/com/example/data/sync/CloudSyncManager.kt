package com.example.data.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.auth.GoogleAccount
import com.example.data.auth.GoogleAuthManager
import com.example.data.auth.GoogleAuthStatus
import com.example.data.model.BoardElement
import com.example.data.model.BoardItem
import com.example.data.model.FocusSession
import com.example.data.model.NoteItem
import com.example.data.model.RoutineTask
import com.example.data.model.ScheduledScript
import com.example.data.model.TaskCompletionLog
import com.example.data.repository.RoutineRepository
import com.example.desktop.platform.CloudConfig
import com.example.util.CategoryLocalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class SyncState {
    IDLE,
    SYNCING,
    SYNCED,
    OFFLINE,
    ERROR
}

/**
 * Cloud sync that is compatible with the Wave Focus PWA.
 *
 * Both apps mirror the complete app state into the same Firestore document
 * (`users/{uid}/apps/wavefocus`). The newest `updatedAt` wins, exactly like `pwa/js/sync.js`:
 * the local copy stays authoritative, changes are pushed shortly after they happen, and on
 * sign-in / manual sync the newest copy replaces the older one.
 */
class CloudSyncManager private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wave_focus_sync_prefs", Context.MODE_PRIVATE)

    private val appPrefs: SharedPreferences =
        context.getSharedPreferences("daily_routine_prefs", Context.MODE_PRIVATE)

    private val auth = GoogleAuthManager.getInstance(context)
    private val firestore = FirestoreSyncClient(auth)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pushJob: Job? = null
    private var repositoryProvider: (() -> RoutineRepository)? = null

    @Volatile
    private var localUpdatedAt: Long = prefs.getLong(KEY_LOCAL_UPDATED_AT, 0L)

    private val _account = MutableStateFlow(auth.account.value)
    val account: StateFlow<GoogleAccount?> = _account.asStateFlow()

    private val _authStatus = MutableStateFlow(auth.status.value)
    val authStatus: StateFlow<GoogleAuthStatus> = _authStatus.asStateFlow()

    private val _authMessage = MutableStateFlow(auth.message.value)
    val authMessage: StateFlow<String> = _authMessage.asStateFlow()

    /** Kept for the settings UI: the signed-in uid, or null when signed out. */
    private val _syncKey = MutableStateFlow(auth.account.value?.uid)
    val syncKey: StateFlow<String?> = _syncKey.asStateFlow()

    private val _username = MutableStateFlow(auth.account.value?.let { it.displayName.ifBlank { it.email } })
    val username: StateFlow<String?> = _username.asStateFlow()

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage.asStateFlow()

    private val _lastSyncTimestamp = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L))
    val lastSyncTimestamp: StateFlow<Long> = _lastSyncTimestamp.asStateFlow()

    init {
        scope.launch {
            auth.account.collectLatest { account ->
                _account.value = account
                _syncKey.value = account?.uid
                _username.value = account?.let { it.displayName.ifBlank { it.email } }
            }
        }
        scope.launch { auth.status.collectLatest { _authStatus.value = it } }
        scope.launch { auth.message.collectLatest { _authMessage.value = it } }
    }

    companion object {
        private const val TAG = "CloudSyncManager"
        private const val KEY_LAST_SYNC = "cloud_last_sync_timestamp"
        private const val KEY_LOCAL_UPDATED_AT = "pwa_state_updated_at"
        private const val KEY_CATEGORIES = "pwa_categories_json"
        private const val KEY_LAST_UID = "pwa_last_uid"
        private const val PUSH_DEBOUNCE_MS = 1200L
        private const val SCHEMA_VERSION = 2

        @Volatile
        private var instance: CloudSyncManager? = null

        fun getInstance(context: Context): CloudSyncManager {
            return instance ?: synchronized(this) {
                instance ?: CloudSyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // ------------------------------------------------------------------ auth

    fun isConnected(): Boolean = auth.isSignedIn

    /** Lets the debounced auto-push read from the currently active edition database. */
    fun attachRepository(provider: () -> RoutineRepository) {
        repositoryProvider = provider
    }

    suspend fun signIn(): Result<GoogleAccount> = withContext(Dispatchers.IO) {
        val previousUid = prefs.getString(KEY_LAST_UID, null)
        val result = auth.signIn()
        result.onSuccess { account ->
            if (previousUid != account.uid) {
                // Different Google account (or first sign-in): let the cloud copy win when it
                // exists, instead of overwriting the account's data with this device's copy.
                localUpdatedAt = 0L
                prefs.edit()
                    .putLong(KEY_LOCAL_UPDATED_AT, 0L)
                    .remove(KEY_CATEGORIES)
                    .putString(KEY_LAST_UID, account.uid)
                    .apply()
            }
            _syncState.value = SyncState.SYNCING
            _syncMessage.value = "Signed in as ${account.email}"
        }
        result.onFailure { error ->
            _syncState.value = SyncState.ERROR
            _syncMessage.value = error.message ?: "Google sign-in failed"
        }
        result
    }

    fun signOut() {
        pushJob?.cancel()
        auth.signOut()
        _account.value = null
        _syncKey.value = null
        _username.value = null
        _authStatus.value = GoogleAuthStatus.SIGNED_OUT
        _syncState.value = SyncState.IDLE
        _syncMessage.value = "Signed out of cloud sync"
        prefs.edit().remove(KEY_LAST_UID).apply()
    }

    /** Alias kept for the existing settings screen. */
    fun disconnect() = signOut()

    // --------------------------------------------------------------- changes

    /**
     * Called after every local mutation. Mirrors the PWA: bump the state timestamp and push to
     * Firestore after a short pause (so a burst of edits results in a single write).
     */
    fun onLocalChange() {
        localUpdatedAt = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LOCAL_UPDATED_AT, localUpdatedAt).apply()
        if (!auth.isSignedIn) return
        val provider = repositoryProvider ?: return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            runCatching { performSync(provider()) }
                .onFailure { Log.w(TAG, "Auto sync failed: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ sync

    suspend fun performSync(repository: RoutineRepository): Result<String> = withContext(Dispatchers.IO) {
        val account = auth.account.value
            ?: return@withContext Result.failure(IllegalStateException("Sign in with Google first"))

        _syncState.value = SyncState.SYNCING
        _syncMessage.value = "Syncing with your Google account…"

        try {
            val path = documentPath(account.uid)
            val document = firestore.read(path)
            val cloudUpdatedAt = asLong(document?.get("updatedAt")) ?: 0L
            val cloudState = document?.get("state")

            if (cloudState != null && cloudUpdatedAt > localUpdatedAt) {
                val count = applyState(cloudState, repository)
                localUpdatedAt = cloudUpdatedAt
                prefs.edit().putLong(KEY_LOCAL_UPDATED_AT, localUpdatedAt).apply()
                markSynced()
                _syncState.value = SyncState.SYNCED
                _syncMessage.value = "Restored $count items from your Google account"
                Result.success("Restored $count items from the cloud")
            } else {
                val now = maxOf(System.currentTimeMillis(), localUpdatedAt)
                val state = buildState(repository, now)
                localUpdatedAt = now
                firestore.write(
                    path,
                    mapOf(
                        "updatedAt" to now,
                        "schema" to SCHEMA_VERSION,
                        "device" to CloudConfig.DEVICE_LABEL,
                        "state" to state
                    )
                )
                prefs.edit().putLong(KEY_LOCAL_UPDATED_AT, localUpdatedAt).apply()
                markSynced()
                _syncState.value = SyncState.SYNCED
                _syncMessage.value = "Synced successfully with your Google account"
                Result.success("Sync completed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync error: ${e.message}", e)
            _syncState.value = SyncState.ERROR
            _syncMessage.value = "Sync failed: ${e.localizedMessage ?: "Network or permission error"}"
            Result.failure(e)
        }
    }

    private fun markSynced() {
        val now = System.currentTimeMillis()
        _lastSyncTimestamp.value = now
        prefs.edit().putLong(KEY_LAST_SYNC, now).apply()
    }

    private fun documentPath(uid: String): String = "users/$uid/apps/wavefocus"

    // -------------------------------------------------------- state -> cloud

    private suspend fun buildState(repository: RoutineRepository, updatedAt: Long): Map<String, Any?> {
        val tasks = repository.getAllTasksSync()
        val logs = repository.getAllLogsSync()
        val sessions = repository.getAllFocusSessionsSync()
        val scripts = repository.getAllScriptsSync()
        val notes = repository.getAllNotesSync()
        val boards = repository.getAllBoardsSync()
        val elements = repository.getAllBoardElementsSync()

        val completions = LinkedHashMap<String, Any?>()
        logs.filter { it.isCompleted }
            .groupBy { it.dateEpochDay }
            .forEach { (day, dayLogs) ->
                completions[isoFromEpochDay(day)] = dayLogs.map { "t_${it.taskId}" }
            }

        val elementsByBoard = elements.groupBy { it.boardId }
        val notesByBoard = notes.filter { it.boardId != null }.groupBy { it.boardId }

        val boardList = boards.map { board ->
            val cards = buildList {
                elementsByBoard[board.id].orEmpty().forEach { element ->
                    add(
                        mapOf(
                            "id" to "c_${element.id}",
                            "text" to element.text,
                            "done" to false,
                            "xd" to mapOf(
                                "type" to element.elementType,
                                "color" to element.colorHex,
                                "textColor" to element.textColorHex,
                                "linked" to element.linkedNoteIds,
                                "image" to element.imageUri,
                                "posX" to element.posX.toDouble(),
                                "posY" to element.posY.toDouble()
                            )
                        )
                    )
                }
                notesByBoard[board.id].orEmpty().forEach { note ->
                    add(
                        mapOf(
                            "id" to "c_${note.id}",
                            "text" to (if (note.title.isBlank()) note.content else "${note.title} — ${note.content}"),
                            "done" to false,
                            "xd" to mapOf(
                                "type" to "NOTE",
                                "color" to note.colorHex,
                                "textColor" to note.textColorHex,
                                "image" to note.imageUri,
                                "posX" to note.posX.toDouble(),
                                "posY" to note.posY.toDouble(),
                                "title" to note.title,
                                "content" to note.content,
                                "pinned" to note.pinned
                            )
                        )
                    )
                }
            }
            mapOf(
                "id" to "b_${board.id}",
                "title" to board.title,
                "emoji" to "🗂️",
                "color" to board.backgroundColorHex,
                "cards" to cards,
                "createdAt" to board.createdAt,
                "xd" to mapOf(
                    "description" to board.description,
                    "updatedAt" to board.updatedAt
                )
            )
        }

        val settings = mapOf(
            "theme" to (appPrefs.getString("theme_mode", "system") ?: "system"),
            "lang" to (appPrefs.getString("app_language", "en") ?: "en"),
            "edition" to if (appPrefs.getString("active_app_edition", "standard") == "islamic") "islamic" else "standard",
            "weekStart" to 1,
            "notifications" to false,
            "haptics" to true,
            "onboarded" to true
        )

        return mapOf(
            "v" to SCHEMA_VERSION,
            "updatedAt" to updatedAt,
            "settings" to settings,
            "categories" to buildCategories(tasks),
            "tasks" to tasks.map { taskToPwa(it) },
            "completions" to completions,
            "sessions" to sessions.map { sessionToPwa(it) },
            "quests" to scripts.map { scriptToPwa(it) },
            "notes" to notes.filter { it.boardId == null }.map { noteToPwa(it) },
            "boards" to boardList
        )
    }

    private fun buildCategories(tasks: List<RoutineTask>): List<Map<String, Any?>> {
        val stored = runCatching {
            val raw = prefs.getString(KEY_CATEGORIES, null) ?: return@runCatching null
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val name = item.optString("name")
                if (name.isBlank()) null else name to item.optString("color", "#38BDF8")
            }
        }.getOrNull()

        val categories = LinkedHashMap<String, String>()
        defaultCategories().forEach { (name, color) -> categories[name] = color }
        stored?.forEach { (name, color) -> categories[name] = color }
        tasks.map { it.category }.filter { it.isNotBlank() }.distinct()
            .forEach { name -> categories.putIfAbsent(name, CategoryLocalization.getMainCategoryColorHex(name)) }

        return categories.map { (name, color) -> mapOf("name" to name, "color" to color) }
    }

    private fun defaultCategories(): List<Pair<String, String>> = listOf(
        "Health" to "#10B981",
        "Focus" to "#6366F1",
        "Mind" to "#14B8A6",
        "Productivity" to "#F97316",
        "Habit" to "#F43F5E"
    )

    private fun taskToPwa(task: RoutineTask): Map<String, Any?> {
        val anchorDay = if (task.startEpochDay > 0L) task.startEpochDay else epochDayFromMillis(task.createdAt)
        val days = task.daysOfWeek.split(',').mapNotNull { it.trim().toIntOrNull() }
        return mapOf(
            "id" to "t_${task.id}",
            "title" to task.title,
            "category" to task.category,
            "timeOfDay" to task.timeOfDay,
            "targetMinutes" to task.targetMinutes,
            "repeatType" to when (task.repeatType.uppercase()) {
                "INTERVAL" -> "interval"
                "ONCE", "SPECIFIC_DAY" -> "once"
                "WEEKEND_ONCE" -> "weekend"
                else -> "weekly"
            },
            "days" to days.ifEmpty { listOf(1, 2, 3, 4, 5, 6, 7) },
            "intervalDays" to task.repeatIntervalDays,
            "anchor" to isoFromEpochDay(anchorDay),
            "note" to "",
            "active" to task.isActive,
            "createdAt" to isoFromEpochDay(epochDayFromMillis(task.createdAt)),
            "order" to task.displayOrder,
            "xd" to mapOf(
                "updatedAt" to task.updatedAt,
                "daysOfWeek" to task.daysOfWeek,
                "repeatType" to task.repeatType,
                "startEpochDay" to task.startEpochDay
            )
        )
    }

    private fun sessionToPwa(session: FocusSession): Map<String, Any?> = mapOf(
        "id" to "s_${session.id}",
        "date" to isoFromEpochDay(session.dateEpochDay),
        "seconds" to session.durationSeconds,
        "mode" to session.mode,
        "taskId" to session.taskId?.let { "t_$it" },
        "taskTitle" to session.taskTitle,
        "endedAt" to session.completedAt,
        "xd" to mapOf("dateEpochDay" to session.dateEpochDay)
    )

    private fun scriptToPwa(script: ScheduledScript): Map<String, Any?> = mapOf(
        "id" to "q_${script.id}",
        "date" to isoFromEpochDay(script.startDateEpochDay),
        "title" to script.title,
        "emoji" to script.emoji,
        "type" to when {
            script.questType.contains("Milestone", ignoreCase = true) -> "milestone"
            script.questType.contains("Epic", ignoreCase = true) -> "epic"
            script.questType.contains("Focus", ignoreCase = true) -> "focus"
            else -> "daily"
        },
        "target" to script.targetValue,
        "xp" to script.xpReward,
        "done" to script.isCompleted,
        "createdAt" to script.updatedAt,
        "xd" to mapOf(
            "endDate" to isoFromEpochDay(script.endDateEpochDay),
            "category" to script.category,
            "questType" to script.questType,
            "isEncrypted" to script.isEncrypted,
            "encryptedContent" to script.encryptedContent,
            "iv" to script.iv
        )
    )

    private fun noteToPwa(note: NoteItem): Map<String, Any?> = mapOf(
        "id" to "n_${note.id}",
        "title" to note.title,
        "content" to note.content,
        "color" to note.colorHex,
        "pinned" to note.pinned,
        "createdAt" to note.createdAt,
        "updatedAt" to note.updatedAt,
        "xd" to mapOf(
            "textColor" to note.textColorHex,
            "image" to note.imageUri,
            "posX" to note.posX.toDouble(),
            "posY" to note.posY.toDouble()
        )
    )

    // -------------------------------------------------------- cloud -> state

    private suspend fun applyState(cloudState: Any?, repository: RoutineRepository): Int {
        val state = asMap(cloudState)
        var count = 0

        // ---- tasks
        val taskObjects = asListOfMaps(state["tasks"])
        val taskIdsInOrder = mutableListOf<String>()
        val tasks = taskObjects.map { obj ->
            val pwaId = asString(obj["id"]) ?: ""
            taskIdsInOrder += pwaId
            val xd = asMap(obj["xd"])
            val repeatType = when ((asString(xd["repeatType"]) ?: asString(obj["repeatType"]) ?: "weekly").lowercase()) {
                "interval" -> "INTERVAL"
                "once" -> "ONCE"
                "weekend" -> "WEEKEND_ONCE"
                else -> "WEEKLY"
            }
            val anchor = asString(obj["anchor"]) ?: asString(obj["createdAt"]) ?: LocalDate.now().toString()
            val days = asList(obj["days"]).mapNotNull { (asLong(it))?.toInt() }
            RoutineTask(
                id = numericId(pwaId),
                title = asString(obj["title"]) ?: "Untitled",
                category = asString(obj["category"]) ?: "Health",
                timeOfDay = asString(obj["timeOfDay"]) ?: "Morning",
                targetMinutes = asInt(obj["targetMinutes"]) ?: 0,
                daysOfWeek = (asString(xd["daysOfWeek"]) ?: days.joinToString(",")).ifBlank { "1,2,3,4,5,6,7" },
                repeatType = repeatType,
                repeatIntervalDays = asInt(obj["intervalDays"]) ?: 1,
                startEpochDay = asLong(xd["startEpochDay"]) ?: epochDayFromIso(anchor),
                isActive = asBool(obj["active"]) ?: true,
                createdAt = asLong(xd["createdAtMillis"]) ?: epochMillisFromIso(anchor, startOfDay = true),
                updatedAt = asLong(xd["updatedAt"]) ?: System.currentTimeMillis(),
                displayOrder = asInt(obj["order"]) ?: 0
            )
        }

        repository.clearAllTasks()
        val insertedTaskIds = if (tasks.isEmpty()) emptyList() else repository.insertTasksReturningIds(tasks)
        val pwaTaskToRoom = mutableMapOf<String, Long>()
        taskIdsInOrder.forEachIndexed { index, pwaId ->
            val roomId = insertedTaskIds.getOrNull(index)
            if (roomId != null && pwaId.isNotBlank()) pwaTaskToRoom[pwaId] = roomId
        }
        count += tasks.size

        // ---- completions
        val completions = asMap(state["completions"])
        val logs = mutableListOf<TaskCompletionLog>()
        completions.forEach { (dateKey, idsValue) ->
            val epochDay = epochDayFromIso(dateKey)
            asList(idsValue).forEach { value ->
                val roomId = pwaTaskToRoom[asString(value)] ?: return@forEach
                logs += TaskCompletionLog(
                    taskId = roomId,
                    dateEpochDay = epochDay,
                    isCompleted = true,
                    completedAt = epochMillisFromIso(dateKey, startOfDay = false)
                )
            }
        }
        repository.clearAllLogs()
        if (logs.isNotEmpty()) repository.insertLogs(logs)
        count += logs.size

        // ---- focus sessions
        val sessions = asListOfMaps(state["sessions"]).map { obj ->
            val xd = asMap(obj["xd"])
            FocusSession(
                id = numericId(asString(obj["id"]) ?: ""),
                taskId = pwaTaskToRoom[asString(obj["taskId"])],
                taskTitle = asString(obj["taskTitle"]) ?: "",
                durationSeconds = asInt(obj["seconds"]) ?: 0,
                mode = asString(obj["mode"]) ?: "Pomodoro",
                completedAt = asLong(obj["endedAt"]) ?: System.currentTimeMillis(),
                dateEpochDay = asLong(xd["dateEpochDay"]) ?: epochDayFromIso(asString(obj["date"]) ?: LocalDate.now().toString())
            )
        }
        repository.clearAllFocusSessions()
        if (sessions.isNotEmpty()) repository.insertFocusSessions(sessions)
        count += sessions.size

        // ---- quests (scheduled scripts)
        val scripts = asListOfMaps(state["quests"]).map { obj ->
            val xd = asMap(obj["xd"])
            val date = asString(obj["date"]) ?: LocalDate.now().toString()
            ScheduledScript(
                id = numericId(asString(obj["id"]) ?: ""),
                title = asString(obj["title"]) ?: "Quest",
                encryptedContent = asString(xd["encryptedContent"]) ?: "",
                iv = asString(xd["iv"]) ?: "",
                startDateEpochDay = epochDayFromIso(date),
                endDateEpochDay = epochDayFromIso(asString(xd["endDate"]) ?: date),
                category = asString(xd["category"]) ?: "Quest",
                updatedAt = asLong(obj["createdAt"]) ?: System.currentTimeMillis(),
                isEncrypted = asBool(xd["isEncrypted"]) ?: false,
                isCompleted = asBool(obj["done"]) ?: false,
                targetValue = asString(obj["target"]) ?: "",
                xpReward = asInt(obj["xp"]) ?: 50,
                questType = asString(xd["questType"]) ?: when ((asString(obj["type"]) ?: "daily").lowercase()) {
                    "milestone" -> "Milestone Goal"
                    "epic" -> "Epic Challenge"
                    "focus" -> "Focus Target"
                    else -> "Daily Quest"
                },
                emoji = asString(obj["emoji"]) ?: "🎯"
            )
        }
        repository.clearAllScripts()
        if (scripts.isNotEmpty()) repository.insertScriptsReturningIds(scripts)
        count += scripts.size

        // ---- boards (before notes so board notes can be attached)
        val boardObjects = asListOfMaps(state["boards"])
        val boardIdsInOrder = mutableListOf<String>()
        val boards = boardObjects.map { obj ->
            val xd = asMap(obj["xd"])
            val id = asString(obj["id"]) ?: ""
            boardIdsInOrder += id
            BoardItem(
                id = numericId(id),
                title = asString(obj["title"]) ?: "Board",
                description = asString(xd["description"]) ?: "",
                backgroundColorHex = asString(obj["color"]) ?: "#0F172A",
                createdAt = asLong(obj["createdAt"]) ?: System.currentTimeMillis(),
                updatedAt = asLong(xd["updatedAt"]) ?: System.currentTimeMillis()
            )
        }
        repository.clearAllNotes()
        repository.clearAllBoards()
        repository.clearAllBoardElements()
        val insertedBoardIds = if (boards.isEmpty()) emptyList() else repository.insertBoardsReturningIds(boards)
        val pwaBoardToRoom = mutableMapOf<String, Long>()
        boardIdsInOrder.forEachIndexed { index, pwaId ->
            val roomId = insertedBoardIds.getOrNull(index)
            if (roomId != null && pwaId.isNotBlank()) pwaBoardToRoom[pwaId] = roomId
        }
        count += boards.size

        val elements = mutableListOf<BoardElement>()
        val boardNotes = mutableListOf<NoteItem>()
        boardObjects.forEachIndexed { boardIndex, obj ->
            val roomBoardId = insertedBoardIds.getOrNull(boardIndex) ?: return@forEachIndexed
            asListOfMaps(obj["cards"]).forEach { card ->
                val xd = asMap(card["xd"])
                val type = (asString(xd["type"]) ?: "TEXT").uppercase()
                val text = asString(card["text"]) ?: ""
                if (type == "NOTE") {
                    boardNotes += NoteItem(
                        id = numericId(asString(card["id"]) ?: ""),
                        boardId = roomBoardId,
                        title = asString(xd["title"]) ?: text.take(40),
                        content = asString(xd["content"]) ?: text,
                        imageUri = asString(xd["image"]),
                        colorHex = asString(xd["color"]) ?: "#1E293B",
                        textColorHex = asString(xd["textColor"]) ?: "#F8FAFC",
                        posX = (asDouble(xd["posX"]) ?: 40.0).toFloat(),
                        posY = (asDouble(xd["posY"]) ?: 40.0).toFloat(),
                        pinned = asBool(xd["pinned"]) ?: false,
                        createdAt = asLong(obj["createdAt"]) ?: System.currentTimeMillis(),
                        updatedAt = asLong(xd["updatedAt"]) ?: System.currentTimeMillis()
                    )
                } else {
                    elements += BoardElement(
                        id = numericId(asString(card["id"]) ?: ""),
                        boardId = roomBoardId,
                        elementType = if (type in setOf("BUTTON", "TEXT", "IMAGE")) type else "TEXT",
                        text = text,
                        colorHex = asString(xd["color"]) ?: "#3B82F6",
                        textColorHex = asString(xd["textColor"]) ?: "#FFFFFF",
                        linkedNoteIds = asString(xd["linked"]) ?: "",
                        imageUri = asString(xd["image"]),
                        posX = (asDouble(xd["posX"]) ?: 100.0).toFloat(),
                        posY = (asDouble(xd["posY"]) ?: 100.0).toFloat(),
                        createdAt = asLong(obj["createdAt"]) ?: System.currentTimeMillis()
                    )
                }
            }
        }

        // ---- standalone notes
        val notes = asListOfMaps(state["notes"]).map { obj ->
            val xd = asMap(obj["xd"])
            NoteItem(
                id = numericId(asString(obj["id"]) ?: ""),
                boardId = null,
                title = asString(obj["title"]) ?: "",
                content = asString(obj["content"]) ?: "",
                imageUri = asString(xd["image"]),
                colorHex = asString(obj["color"]) ?: "#1E293B",
                textColorHex = asString(xd["textColor"]) ?: "#F8FAFC",
                posX = (asDouble(xd["posX"]) ?: 40.0).toFloat(),
                posY = (asDouble(xd["posY"]) ?: 40.0).toFloat(),
                pinned = asBool(obj["pinned"]) ?: false,
                createdAt = asLong(obj["createdAt"]) ?: System.currentTimeMillis(),
                updatedAt = asLong(obj["updatedAt"]) ?: System.currentTimeMillis()
            )
        } + boardNotes

        if (notes.isNotEmpty()) repository.insertNotes(notes)
        if (elements.isNotEmpty()) repository.insertBoardElements(elements)
        count += notes.size + elements.size

        // ---- categories (kept for colour round-trips)
        runCatching {
            val categories = asListOfMaps(state["categories"])
            if (categories.isNotEmpty()) {
                val array = JSONArray()
                categories.forEach { item ->
                    val name = asString(item["name"]) ?: return@forEach
                    array.put(
                        JSONObject()
                            .put("name", name)
                            .put("color", asString(item["color"]) ?: "#38BDF8")
                    )
                }
                prefs.edit().putString(KEY_CATEGORIES, array.toString()).apply()
            }
        }

        return count
    }

    // -------------------------------------------------------------- backup

    /** Exports the same JSON shape the PWA produces, so backups are interchangeable. */
    suspend fun exportBackupJson(repository: RoutineRepository): String = withContext(Dispatchers.IO) {
        val state = buildState(repository, System.currentTimeMillis())
        JSONObject()
            .put("app", "wavefocus")
            .put("schema", SCHEMA_VERSION)
            .put("exportedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now()))
            .put("data", JSONObject(state))
            .toString(2)
    }

    /**
     * Imports a backup produced by the PWA or by this app. Both `{app, data:{...}}` (PWA) and the
     * legacy desktop `{tasks:[...]}` shapes are accepted.
     */
    suspend fun importBackupJson(jsonString: String, repository: RoutineRepository): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val root = JSONObject(jsonString)
                val data = root.optJSONObject("data") ?: root
                if (data.optJSONArray("tasks") == null) {
                    return@withContext Result.failure(IllegalArgumentException("This file does not contain any Wave Focus tasks"))
                }
                val count = applyState(data, repository)
                localUpdatedAt = Math.max(localUpdatedAt, System.currentTimeMillis())
                prefs.edit().putLong(KEY_LOCAL_UPDATED_AT, localUpdatedAt).apply()
                onLocalChange()
                Result.success(count)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // -------------------------------------------------------------- helpers

    private fun isoFromEpochDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).toString()

    private fun epochDayFromIso(value: String): Long = runCatching {
        LocalDate.parse(value.take(10)).toEpochDay()
    }.getOrElse { LocalDate.now().toEpochDay() }

    private fun epochDayFromMillis(millis: Long): Long =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun epochMillisFromIso(value: String, startOfDay: Boolean): Long = runCatching {
        val date = LocalDate.parse(value.take(10))
        val time = if (startOfDay) date.atStartOfDay() else date.atTime(12, 0)
        time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrElse { System.currentTimeMillis() }

    /** Room ids are embedded in PWA ids (`t_12`); anything else is inserted with a new id. */
    private fun numericId(pwaId: String): Long =
        pwaId.substringAfterLast('_').toLongOrNull()?.takeIf { it > 0L } ?: 0L

    private fun asMap(value: Any?): Map<String, Any?> {
        val plain = plain(value) as? Map<*, *> ?: return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        plain.forEach { (k, v) -> result[k.toString()] = v }
        return result
    }

    private fun asListOfMaps(value: Any?): List<Map<String, Any?>> {
        val plain = plain(value) as? List<*> ?: return emptyList()
        return plain.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val result = LinkedHashMap<String, Any?>()
            map.forEach { (k, v) -> result[k.toString()] = v }
            result
        }
    }

    private fun asList(value: Any?): List<Any?> = plain(value) as? List<Any?> ?: emptyList()

    private fun asString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        else -> value.toString()
    }

    private fun asLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun asInt(value: Any?): Int? = asLong(value)?.toInt()

    private fun asDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private fun asBool(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        else -> null
    }

    private fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = plain(value.opt(key))
            }
            map
        }
        is JSONArray -> (0 until value.length()).map { plain(value.opt(it)) }
        is Map<*, *> -> {
            val map = LinkedHashMap<String, Any?>()
            value.forEach { (k, v) -> map[k.toString()] = plain(v) }
            map
        }
        is List<*> -> value.map { plain(it) }
        else -> value
    }
}
