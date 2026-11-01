package com.example.autogabay

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.autogabay.assignments.AssignmentCategory
import com.example.autogabay.assignments.AssignmentType
import com.example.autogabay.assignments.AssignmentViewModelFactory
import com.example.autogabay.assignments.GabayAssignmentViewModel
import com.example.autogabay.assignments.Student
import com.example.autogabay.assignments.StudentCsvExporter
import com.example.autogabay.assignments.StudentCsvBackupFile
import com.example.autogabay.assignments.AssignmentStats
import com.example.autogabay.assignments.hebrewName
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.time.LocalDate

class MainActivity : AppCompatActivity() {
    private val csvFilePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importBackup)
        }

    private val assignmentViewModel: GabayAssignmentViewModel by lazy {
        ViewModelProvider(this, AssignmentViewModelFactory(applicationContext))[
            GabayAssignmentViewModel::class.java
        ]
    }

    private lateinit var pageContent: LinearLayout
    private lateinit var navigation: BottomNavigationView
    private var latestStudents: List<Student> = emptyList()
    private var latestPoints: Map<AssignmentType, Int> = emptyMap()
    private var selectedScreen = Screen.STUDENTS
    private val expandedStudentIds = mutableSetOf<String>()

    private enum class Screen(val id: Int, val label: String) {
        STUDENTS(1, "תלמידים"),
        DUTIES(2, "תורנויות");

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: STUDENTS
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val savedNightMode = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (AppCompatDelegate.getDefaultNightMode() != savedNightMode) {
            AppCompatDelegate.setDefaultNightMode(savedNightMode)
        }
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() != "he") {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("he"))
        }
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        supportActionBar?.hide()
        selectedScreen = savedInstanceState?.getInt(STATE_SELECTED_SCREEN)
            ?.let(Screen::fromId) ?: Screen.STUDENTS

        setContentView(createLayout())
        navigation.selectedItemId = selectedScreen.id
        lifecycleScope.launch {
            assignmentViewModel.uiState.collect { state ->
                latestStudents = state.studentsByPoints
                latestPoints = state.defaultPoints
                renderScreen(selectedScreen)
                state.errorMessage?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                    assignmentViewModel.clearError()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_SELECTED_SCREEN, selectedScreen.id)
        super.onSaveInstanceState(outState)
    }

    private fun createLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pageBackgroundColor())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(surfaceColor())
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.yeshiva_logo)
            contentDescription = "סמל הישיבה"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = "ניהול תורנויות בישיבה"
            textSize = 19f
            setTextColor(primaryTextColor())
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(12), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        header.addView(ImageButton(this).apply {
            setImageResource(if (isDarkMode()) R.drawable.ic_sun else R.drawable.ic_moon)
            imageTintList = android.content.res.ColorStateList.valueOf(primaryTextColor())
            contentDescription = if (isDarkMode()) "מעבר למצב בהיר" else "מעבר למצב כהה"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                setDarkModeEnabled(!isDarkMode())
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val scroll = ScrollView(this)
        pageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(pageBackgroundColor())
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        scroll.addView(pageContent)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        navigation = BottomNavigationView(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            labelVisibilityMode = BottomNavigationView.LABEL_VISIBILITY_LABELED
            menu.add(0, Screen.STUDENTS.id, 0, Screen.STUDENTS.label)
                .setIcon(android.R.drawable.ic_menu_myplaces)
            menu.add(0, Screen.DUTIES.id, 1, Screen.DUTIES.label)
                .setIcon(android.R.drawable.ic_menu_agenda)
            setOnItemSelectedListener { item ->
                selectedScreen = Screen.fromId(item.itemId)
                renderScreen(selectedScreen)
                true
            }
        }
        root.addView(navigation, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun renderScreen(screen: Screen) {
        pageContent.removeAllViews()
        addText(screen.label, 25f, bold = true)
        if (screen == Screen.STUDENTS) renderStudents() else renderDuties()
    }

    private fun renderStudents() {
        addText("התלמידים מסודרים לפי הניקוד המצטבר, מהנמוך לגבוה.")
        addButtonRow(
            button("הוסף תלמיד") { showStudentDialog() },
            button("יצוא סיכום") { exportData() },
        )
        addButtonRow(
            button("גיבוי CSV מלא") { exportBackupCsv() },
            button("שחזור מקובץ CSV") { restoreBackupCsv() },
        )
        if (latestStudents.isEmpty()) {
            addCard("עדיין אין תלמידים", "הוסיפו תלמיד כדי להתחיל לנהל את התורנויות.")
            return
        }

        latestStudents.forEach { student ->
            val card = createCard()
            card.addView(TextView(this).apply {
                text = student.name
                textSize = 19f
                setTextColor(primaryTextColor())
                textDirection = View.TEXT_DIRECTION_RTL
            })
            card.addView(TextView(this).apply {
                text = "${AssignmentStats.totalPoints(student)} נקודות · ${student.assignments.size} תורנויות"
                textSize = 14f
                setTextColor(secondaryTextColor())
                setPadding(0, dp(5), 0, dp(4))
                textDirection = View.TEXT_DIRECTION_RTL
            })
            card.addView(createButtonRow(
                button("עריכת תלמיד") { showStudentDialog(student) },
                button("הזנת מטלה") { showAssignmentTypeDialog(student) },
            ))
            card.addView(button(
                if (student.id in expandedStudentIds) "הסתר הזנות" else "הצג הזנות (${student.assignments.size})"
            ) {
                if (!expandedStudentIds.add(student.id)) expandedStudentIds.remove(student.id)
                renderScreen(Screen.STUDENTS)
            })
            if (student.id in expandedStudentIds) {
                student.assignments.sortedByDescending { it.timestamp }.forEach { record ->
                    val recordView = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                    }
                    recordView.addView(TextView(this).apply {
                        text = "${record.assignmentType.hebrewName()} · ${record.pointsEarned} נקודות"
                        textSize = 14f
                        setTextColor(secondaryTextColor())
                        textDirection = View.TEXT_DIRECTION_RTL
                    })
                    recordView.addView(createButtonRow(
                        button("עריכה") { showAssignmentEditDialog(record) },
                        button("הסרה") { confirmDeleteAssignment(record) },
                    ))
                    card.addView(recordView)
                }
            }
            pageContent.addView(card, cardLayoutParams())
        }
    }

    private fun renderDuties() {
        addText("תפילות וקריאות תורה מוצגות יחד. ניתן לערוך ניקוד ולהוסיף תפילות.")
        addButtonRow(
            button("הוסף תפילה") { showCustomPrayerDialog() },
            button("יצוא נתונים") { exportData() },
        )

        activeAssignmentTypes().forEach { type ->
            val card = createCard()
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(TextView(this).apply {
                text = type.hebrewName()
                textSize = 18f
                setTextColor(primaryTextColor())
                textDirection = View.TEXT_DIRECTION_RTL
            }, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_menu_delete)
                contentDescription = "הסרת תורנות ${type.hebrewName()}"
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { confirmRemoveDuty(type) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            card.addView(heading)
            card.addView(TextView(this).apply {
                text = "ניקוד ברירת מחדל: ${latestPoints[type] ?: type.defaultPoints}"
                textSize = 14f
                setTextColor(secondaryTextColor())
                setPadding(0, dp(6), 0, dp(4))
                textDirection = View.TEXT_DIRECTION_RTL
            })
            val actions = mutableListOf(
                button("עריכת ניקוד") { showPointsDialog(type) }
            )
            actions += button(
                if (type.category == AssignmentCategory.PRAYER) "הזנת תפילה" else "הזנת קריאה"
            ) {
                if (latestStudents.isEmpty()) showStudentDialog()
                else showStudentPicker(type)
            }
            card.addView(createButtonRow(*actions.toTypedArray()))
            pageContent.addView(card, cardLayoutParams())
        }
    }

    private fun activeAssignmentTypes(): List<AssignmentType> =
        latestPoints.keys.sortedBy { it.hebrewName() }

    private fun allAssignmentTypes(): List<AssignmentType> =
        (
            AssignmentType.predefined +
                latestPoints.keys +
                latestStudents.flatMap { student -> student.assignments.map { it.assignmentType } }
            ).distinctBy { it.id }

    private fun confirmRemoveDuty(type: AssignmentType) {
        AlertDialog.Builder(this)
            .setTitle("הסרת תורנות")
            .setMessage("להסיר את «${type.hebrewName()}» מהרשימה? היסטוריית הרישומים הקודמת תישמר.")
            .setNegativeButton("ביטול", null)
            .setPositiveButton("הסרה") { _, _ -> assignmentViewModel.removeAssignmentType(type) }
            .show()
    }

    private fun showStudentDialog(student: Student? = null) {
        val input = EditText(this).apply {
            hint = "שם התלמיד"
            setText(student?.name.orEmpty())
            textDirection = View.TEXT_DIRECTION_RTL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val content = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(if (student == null) "הוספת תלמיד" else "עריכת תלמיד")
            .setView(content)
            .setNegativeButton("ביטול", null)
            .setPositiveButton(if (student == null) "הוסף" else "שמור") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(this, "יש להזין שם תלמיד", Toast.LENGTH_SHORT).show()
                } else if (student == null) {
                    assignmentViewModel.addStudent(Student(UUID.randomUUID().toString(), name))
                } else {
                    assignmentViewModel.updateStudent(student.copy(name = name))
                }
            }
        if (student != null) {
            builder.setNeutralButton("הסרת תלמיד") { _, _ -> confirmDeleteStudent(student) }
        }
        builder.show()
    }

    private fun confirmDeleteStudent(student: Student) {
        AlertDialog.Builder(this)
            .setTitle("הסרת תלמיד")
            .setMessage("להסיר את ${student.name} ואת כל רישומי המטלות שלו?")
            .setNegativeButton("ביטול", null)
            .setPositiveButton("הסרה") { _, _ -> assignmentViewModel.deleteStudent(student.id) }
            .show()
    }

    private fun showPointsDialog(type: AssignmentType) {
        val input = EditText(this).apply {
            hint = "מספר נקודות"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((latestPoints[type] ?: type.defaultPoints).toString())
        }
        AlertDialog.Builder(this)
            .setTitle("עריכת ניקוד: ${type.hebrewName()}")
            .setView(input)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("שמירה") { _, _ ->
                val points = input.text.toString().toIntOrNull()
                if (points == null) {
                    Toast.makeText(this, "יש להזין מספר תקין", Toast.LENGTH_SHORT).show()
                } else {
                    assignmentViewModel.updateDefaultPoints(type, points)
                }
            }
            .show()
    }

    private fun showCustomPrayerDialog() {
        val nameInput = EditText(this).apply {
            hint = "שם התפילה"
            textDirection = View.TEXT_DIRECTION_RTL
        }
        val pointsInput = EditText(this).apply {
            hint = "ניקוד ברירת מחדל"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("1")
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(nameInput)
            addView(pointsInput)
        }
        AlertDialog.Builder(this)
            .setTitle("הוספת תפילה")
            .setView(fields)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("הוספה") { _, _ ->
                val name = nameInput.text.toString().trim()
                val points = pointsInput.text.toString().toIntOrNull()
                if (name.isBlank() || points == null) {
                    Toast.makeText(this, "יש למלא שם וניקוד תקין", Toast.LENGTH_SHORT).show()
                } else {
                    assignmentViewModel.addCustomPrayer(name, points)
                }
            }
            .show()
    }

    private fun showAssignmentTypeDialog(student: Student) {
        val types = activeAssignmentTypes()
        AlertDialog.Builder(this)
            .setTitle("בחירת תפילה או קריאה עבור ${student.name}")
            .setItems(types.map { it.hebrewName() }.toTypedArray()) { _, index ->
                val type = types[index]
                assignmentViewModel.recordAssignment(student.id, type)
                Toast.makeText(this, "המטלה נרשמה עבור ${student.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun showAssignmentEditDialog(record: com.example.autogabay.assignments.AssignmentRecord) {
        val types = (activeAssignmentTypes() + record.assignmentType).distinctBy { it.id }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                types.map { it.hebrewName() },
            )
            setSelection(types.indexOfFirst { it.id == record.assignmentType.id }.coerceAtLeast(0))
        }
        val points = EditText(this).apply {
            hint = "נקודות"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(record.pointsEarned.toString())
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(spinner)
            addView(points)
        }
        AlertDialog.Builder(this)
            .setTitle("עריכת רישום מטלה")
            .setView(fields)
            .setNegativeButton("ביטול", null)
            .setPositiveButton("שמירה") { _, _ ->
                val earned = points.text.toString().toIntOrNull()
                if (earned == null || earned < 0) {
                    Toast.makeText(this, "יש להזין ניקוד תקין", Toast.LENGTH_SHORT).show()
                } else {
                    assignmentViewModel.updateAssignment(
                        record.copy(assignmentType = types[spinner.selectedItemPosition], pointsEarned = earned)
                    )
                }
            }
            .show()
    }

    private fun confirmDeleteAssignment(record: com.example.autogabay.assignments.AssignmentRecord) {
        AlertDialog.Builder(this)
            .setTitle("הסרת רישום")
            .setMessage("להסיר את הרישום של ${record.assignmentType.hebrewName()}?")
            .setNegativeButton("ביטול", null)
            .setPositiveButton("הסרה") { _, _ -> assignmentViewModel.deleteAssignment(record.id) }
            .show()
    }

    private fun showStudentPicker(type: AssignmentType) {
        AlertDialog.Builder(this)
            .setTitle("בחירת תלמיד · ${type.hebrewName()}")
            .setItems(latestStudents.map { it.name }.toTypedArray()) { _, index ->
                val student = latestStudents[index]
                assignmentViewModel.recordAssignment(student.id, type)
                Toast.makeText(this, "התפילה נרשמה עבור ${student.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    private fun exportBackupCsv() {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    StudentCsvBackupFile.export(
                        context = this@MainActivity,
                        students = latestStudents,
                        activeTypes = activeAssignmentTypes(),
                        pointValues = latestPoints,
                    )
                }
            }.onSuccess { uri ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "גיבוי מלא של נתוני הישיבה",
                ))
            }.onFailure {
                Toast.makeText(this@MainActivity, "יצירת הגיבוי נכשלה", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreBackupCsv() {
        csvFilePicker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
    }

    private fun importBackup(uri: android.net.Uri) {
        lifecycleScope.launch {
            runCatching {
                val backup = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                        StudentCsvBackupFile.parse(it.readText())
                    } ?: error("לא ניתן לקרוא את הקובץ.")
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("שחזור מקובץ CSV")
                    .setMessage(
                        "נמצאו ${backup.students.size} תלמידים ו-${backup.students.sumOf { it.assignments.size }} רישומי מטלות. " +
                            "הנתונים ימוזגו עם הנתונים הקיימים; רישומים בעלי מזהה זהה לא יוכפלו."
                    )
                    .setNegativeButton("ביטול", null)
                    .setPositiveButton("שחזור") { _, _ ->
                        assignmentViewModel.restoreBackup(backup)
                        Toast.makeText(this@MainActivity, "השחזור התחיל", Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }.onFailure {
                Toast.makeText(this@MainActivity, it.message ?: "לא ניתן לשחזר את קובץ ה-CSV", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportData() {
        val today = LocalDate.now()
        DatePickerDialog(this, { _, startYear, startMonth, startDay ->
            val start = LocalDate.of(startYear, startMonth + 1, startDay)
            DatePickerDialog(this, { _, endYear, endMonth, endDay ->
                val end = LocalDate.of(endYear, endMonth + 1, endDay)
                if (end.isBefore(start)) {
                    Toast.makeText(this, "תאריך הסיום צריך להיות אחרי תאריך ההתחלה", Toast.LENGTH_LONG).show()
                } else {
                    exportDataForRange(start, end)
                }
            }, start.year, start.monthValue - 1, start.dayOfMonth).apply {
                setTitle("בחירת תאריך סיום")
                datePicker.minDate = start.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.show()
        }, today.year, today.monthValue - 1, today.dayOfMonth).apply {
            setTitle("בחירת תאריך התחלה")
        }.show()
    }

    private fun exportDataForRange(start: LocalDate, end: LocalDate) {
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    StudentCsvExporter.export(
                        context = this@MainActivity,
                        students = latestStudents,
                        assignmentTypes = allAssignmentTypes(),
                        startDate = start,
                        endDate = end,
                    )
                }
            }.onSuccess { uri ->
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "יצוא נתונים לתקופה ${start} עד ${end}",
                ))
            }.onFailure {
                Toast.makeText(this@MainActivity, "יצוא הנתונים נכשל", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun button(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            textDirection = View.TEXT_DIRECTION_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun addButtonRow(vararg buttons: MaterialButton) {
        pageContent.addView(createButtonRow(*buttons), LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })
    }

    private fun createButtonRow(vararg buttons: MaterialButton): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            buttons.forEach { button ->
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginStart = dp(3)
                    marginEnd = dp(3)
                })
            }
        }

    private fun createCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(12), dp(15), dp(10))
        setBackgroundColor(surfaceColor())
        elevation = dp(2).toFloat()
        layoutDirection = View.LAYOUT_DIRECTION_RTL
    }

    private fun addCard(title: String, subtitle: String) {
        val card = createCard()
        card.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(primaryTextColor())
            textDirection = View.TEXT_DIRECTION_RTL
        })
        card.addView(TextView(this).apply {
            text = subtitle
            textSize = 14f
            setTextColor(secondaryTextColor())
            setPadding(0, dp(6), 0, 0)
            textDirection = View.TEXT_DIRECTION_RTL
        })
        pageContent.addView(card, cardLayoutParams())
    }

    private fun addText(value: String, size: Float = 16f, bold: Boolean = false) {
        pageContent.addView(TextView(this).apply {
            text = value
            textSize = size
            setTextColor(primaryTextColor())
            textDirection = View.TEXT_DIRECTION_RTL
            gravity = Gravity.START
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(12))
        })
    }

    private fun cardLayoutParams() = LinearLayout.LayoutParams(-1, -2).apply {
        bottomMargin = dp(8)
    }

    private fun setDarkModeEnabled(enabled: Boolean) {
        val mode = if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun pageBackgroundColor(): Int =
        if (isDarkMode()) 0xFF121212.toInt() else 0xFFF8F7FC.toInt()

    private fun surfaceColor(): Int =
        if (isDarkMode()) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()

    private fun primaryTextColor(): Int =
        if (isDarkMode()) 0xFFF2F2F2.toInt() else 0xFF211A2B.toInt()

    private fun secondaryTextColor(): Int =
        if (isDarkMode()) 0xFFBDBDBD.toInt() else 0xFF625D6B.toInt()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val STATE_SELECTED_SCREEN = "selected_screen"
        private const val PREFERENCES = "app_preferences"
        private const val KEY_NIGHT_MODE = "night_mode"
    }
}
