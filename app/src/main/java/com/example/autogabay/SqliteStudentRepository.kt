package com.example.autogabay.assignments

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class SqliteStudentRepository(context: Context) : StudentRepository {
    private val helper = Database(context.applicationContext)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val studentState = MutableStateFlow<List<Student>>(emptyList())
    private val pointsState = MutableStateFlow<Map<AssignmentType, Int>>(emptyMap())

    override val students: Flow<List<Student>> = studentState
    override val pointValues: Flow<Map<AssignmentType, Int>> = pointsState

    init {
        repositoryScope.launch { reload() }
    }

    override suspend fun addStudent(student: Student) = withContext(Dispatchers.IO) {
        require(student.id.isNotBlank() && student.name.isNotBlank())
        helper.writableDatabase.insertOrThrow("students", null, ContentValues().apply {
            put("id", student.id)
            put("name", student.name.trim())
        })
        reload()
    }

    override suspend fun updateStudent(student: Student) = withContext(Dispatchers.IO) {
        require(student.id.isNotBlank() && student.name.isNotBlank())
        val updated = helper.writableDatabase.update(
            "students",
            ContentValues().apply { put("name", student.name.trim()) },
            "id = ?",
            arrayOf(student.id),
        )
        check(updated == 1) { "Student '${student.id}' does not exist." }
        reload()
    }

    override suspend fun deleteStudent(studentId: String) = withContext(Dispatchers.IO) {
        require(studentId.isNotBlank())
        val deleted = helper.writableDatabase.delete("students", "id = ?", arrayOf(studentId))
        check(deleted == 1) { "התלמיד לא נמצא." }
        reload()
    }

    override suspend fun addCustomPrayer(name: String, points: Int) = withContext(Dispatchers.IO) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "יש להזין שם תפילה." }
        require(points >= 0) { "הניקוד לא יכול להיות שלילי." }
        helper.writableDatabase.insertOrThrow("custom_prayers", null, ContentValues().apply {
            put("id", "CUSTOM_${UUID.randomUUID()}")
            put("name", cleanName)
            put("points", points)
            put("is_deleted", 0)
        })
        reload()
    }

    /** Hides a duty from future use while retaining its historical records and points. */
    override suspend fun removeAssignmentType(type: AssignmentType) = withContext(Dispatchers.IO) {
        if (type.isCustom) {
            val changed = helper.writableDatabase.update(
                "custom_prayers",
                ContentValues().apply { put("is_deleted", 1) },
                "id = ? AND is_deleted = 0",
                arrayOf(type.id),
            )
            check(changed == 1) { "התורנות לא נמצאה." }
        } else {
            helper.writableDatabase.insertWithOnConflict(
                "hidden_assignment_types",
                null,
                ContentValues().apply { put("type", type.id) },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
        reload()
    }

    override suspend fun addAssignment(studentId: String, assignment: AssignmentRecord) =
        withContext(Dispatchers.IO) {
            require(assignment.id.isNotBlank())
            require(assignment.pointsEarned >= 0)
            val db = helper.writableDatabase
            db.beginTransaction()
            try {
                checkStudentExists(db, studentId)
                db.insertOrThrow("assignments", null, ContentValues().apply {
                    put("id", assignment.id)
                    put("student_id", studentId)
                    put("type", assignment.assignmentType.id)
                    put("points", assignment.pointsEarned)
                    put("timestamp", assignment.timestamp.toEpochMilli())
                    put("notes", assignment.notes)
                })
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            reload()
        }

    override suspend fun updateAssignment(assignment: AssignmentRecord) = withContext(Dispatchers.IO) {
        require(assignment.id.isNotBlank() && assignment.pointsEarned >= 0)
        val changed = helper.writableDatabase.update(
            "assignments",
            ContentValues().apply {
                put("type", assignment.assignmentType.id)
                put("points", assignment.pointsEarned)
                put("timestamp", assignment.timestamp.toEpochMilli())
                put("notes", assignment.notes)
            },
            "id = ?",
            arrayOf(assignment.id),
        )
        check(changed == 1) { "הרישום לא נמצא." }
        reload()
    }

    override suspend fun deleteAssignment(assignmentId: String) = withContext(Dispatchers.IO) {
        require(assignmentId.isNotBlank())
        val deleted = helper.writableDatabase.delete("assignments", "id = ?", arrayOf(assignmentId))
        check(deleted == 1) { "הרישום לא נמצא." }
        reload()
    }

    override suspend fun updateDefaultPoints(type: AssignmentType, points: Int) =
        withContext(Dispatchers.IO) {
            require(points >= 0)
            if (type.isCustom) {
                val updated = helper.writableDatabase.update(
                    "custom_prayers",
                    ContentValues().apply { put("points", points) },
                    "id = ? AND is_deleted = 0",
                    arrayOf(type.id),
                )
                check(updated == 1) { "התפילה לא נמצאה." }
            } else {
                helper.writableDatabase.insertWithOnConflict(
                    "assignment_points",
                    null,
                    ContentValues().apply {
                        put("type", type.id)
                        put("points", points)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            reload()
        }

    override suspend fun restoreBackup(backup: StudentCsvBackup) = withContext(Dispatchers.IO) {
        require(backup.students.isNotEmpty())
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            backup.assignmentTypes.forEach { type ->
                val isActive = type.id in backup.activeTypeIds
                if (type.isCustom) {
                    val values = ContentValues().apply {
                        put("id", type.id)
                        put("name", type.displayName)
                        put("points", type.defaultPoints)
                        put("is_deleted", if (isActive) 0 else 1)
                    }
                    val updated = db.update("custom_prayers", values, "id = ?", arrayOf(type.id))
                    if (updated == 0) db.insertOrThrow("custom_prayers", null, values)
                } else {
                    db.insertWithOnConflict(
                        "assignment_points",
                        null,
                        ContentValues().apply {
                            put("type", type.id)
                            put("points", type.defaultPoints)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    if (isActive) {
                        db.delete("hidden_assignment_types", "type = ?", arrayOf(type.id))
                    } else {
                        db.insertWithOnConflict(
                            "hidden_assignment_types",
                            null,
                            ContentValues().apply { put("type", type.id) },
                            SQLiteDatabase.CONFLICT_IGNORE,
                        )
                    }
                }
            }
            backup.students.forEach { student ->
                val values = ContentValues().apply {
                    put("id", student.id)
                    put("name", student.name)
                }
                val existing = db.update("students", ContentValues().apply {
                    put("name", student.name)
                }, "id = ?", arrayOf(student.id))
                if (existing == 0) db.insertOrThrow("students", null, values)
                student.assignments.forEach { assignment ->
                    db.insertWithOnConflict(
                        "assignments",
                        null,
                        ContentValues().apply {
                            put("id", assignment.id)
                            put("student_id", student.id)
                            put("type", assignment.assignmentType.id)
                            put("points", assignment.pointsEarned)
                            put("timestamp", assignment.timestamp.toEpochMilli())
                            put("notes", assignment.notes)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        reload()
    }

    private fun checkStudentExists(db: SQLiteDatabase, studentId: String) {
        db.query("students", arrayOf("id"), "id = ?", arrayOf(studentId), null, null, null).use {
            check(it.moveToFirst()) { "Student '$studentId' does not exist." }
        }
    }

    private fun reload() {
        val db = helper.readableDatabase
        val availableTypes = AssignmentType.predefined.associateBy { it.id }.toMutableMap()
        val pointMap = mutableMapOf<AssignmentType, Int>()
        val hiddenTypeIds = mutableSetOf<String>()
        db.query("hidden_assignment_types", arrayOf("type"), null, null, null, null, null).use {
            while (it.moveToNext()) hiddenTypeIds += it.getString(0)
        }

        db.query("assignment_points", arrayOf("type", "points"), null, null, null, null, null).use {
            while (it.moveToNext()) {
                AssignmentType.fromId(it.getString(0))?.let { type ->
                    if (type.id !in hiddenTypeIds) pointMap[type] = it.getInt(1)
                }
            }
        }
        db.query(
            "custom_prayers",
            arrayOf("id", "name", "points", "is_deleted"),
            null, null, null, null,
            "name COLLATE NOCASE ASC",
        ).use {
            while (it.moveToNext()) {
                val type = AssignmentType.customPrayer(it.getString(0), it.getString(1), it.getInt(2))
                availableTypes[type.id] = type
                if (it.getInt(3) == 0) pointMap[type] = type.defaultPoints
            }
        }

        val assignmentsByStudent = mutableMapOf<String, MutableList<AssignmentRecord>>()
        db.query(
            "assignments",
            arrayOf("id", "student_id", "type", "points", "timestamp", "notes"),
            null, null, null, null, "timestamp ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val type = availableTypes[cursor.getString(2)] ?: continue
                assignmentsByStudent.getOrPut(cursor.getString(1)) { mutableListOf() }.add(
                    AssignmentRecord(
                        id = cursor.getString(0),
                        assignmentType = type,
                        pointsEarned = cursor.getInt(3),
                        timestamp = Instant.ofEpochMilli(cursor.getLong(4)),
                        notes = if (cursor.isNull(5)) null else cursor.getString(5),
                    ),
                )
            }
        }

        val result = mutableListOf<Student>()
        db.query("students", arrayOf("id", "name"), null, null, null, null, "name COLLATE NOCASE ASC").use {
            while (it.moveToNext()) {
                val id = it.getString(0)
                result += Student(id, it.getString(1), assignmentsByStudent[id].orEmpty())
            }
        }
        studentState.value = result
        pointsState.value = pointMap
    }

    private class Database(context: Context) :
        SQLiteOpenHelper(context, "autogabay.db", null, 3) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE students (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL)")
            createAssignmentTables(db)
        }

        private fun createAssignmentTables(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE assignments (
                    id TEXT PRIMARY KEY NOT NULL,
                    student_id TEXT NOT NULL REFERENCES students(id) ON DELETE CASCADE,
                    type TEXT NOT NULL,
                    points INTEGER NOT NULL CHECK(points >= 0),
                    timestamp INTEGER NOT NULL,
                    notes TEXT
                )""".trimIndent(),
            )
            db.execSQL("CREATE INDEX assignments_student_id_idx ON assignments(student_id)")
            db.execSQL("CREATE TABLE assignment_points (type TEXT PRIMARY KEY NOT NULL, points INTEGER NOT NULL CHECK(points >= 0))")
            db.execSQL(
                """CREATE TABLE custom_prayers (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    points INTEGER NOT NULL CHECK(points >= 0),
                    is_deleted INTEGER NOT NULL DEFAULT 0
                )""".trimIndent(),
            )
            db.execSQL("CREATE TABLE hidden_assignment_types (type TEXT PRIMARY KEY NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    """CREATE TABLE custom_prayers (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        points INTEGER NOT NULL CHECK(points >= 0),
                        is_deleted INTEGER NOT NULL DEFAULT 0
                    )""".trimIndent(),
                )
                db.execSQL("UPDATE assignments SET type = 'SHABBAT_SHACHARIT' WHERE type = 'SHABBAT'")
                db.execSQL("UPDATE assignment_points SET type = 'SHABBAT_SHACHARIT' WHERE type = 'SHABBAT'")
                db.execSQL("UPDATE assignments SET type = 'TORAH_READING_LONG' WHERE type = 'TORAH_READING'")
                db.execSQL("UPDATE assignment_points SET type = 'TORAH_READING_LONG' WHERE type = 'TORAH_READING'")
            }
            if (oldVersion < 3) {
                db.execSQL("CREATE TABLE hidden_assignment_types (type TEXT PRIMARY KEY NOT NULL)")
            }
        }

        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
        }
    }
}
