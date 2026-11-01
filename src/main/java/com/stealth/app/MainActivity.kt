package com.stealth.app

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HiddenCallManager {
    var activeCall: Call? = null
    var serviceInstance: StealthInCallService? = null
    var isSpeakerOn = false
    var onStateChangedListener: (() -> Unit)? = null

    private const val PREFS = "StealthPrefs"
    private const val KEY_NUMBERS = "hidden_numbers"
    private const val KEY_MISSED = "missed_calls"

    fun setHiddenNumbers(ctx: Context, numbers: String) {
        val cleanList = numbers.split(",", " ", "\n")
            .map { it.filter { digit -> digit.isDigit() }.takeLast(10) }
            .filter { it.length == 10 }
            .toSet()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_NUMBERS, cleanList).apply()
    }

    fun getHiddenNumbers(ctx: Context): String {
        val set = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_NUMBERS, emptySet()) ?: emptySet()
        return set.joinToString(", ")
    }

    fun isHidden(ctx: Context, incoming: String?): Boolean {
        if (incoming.isNullOrBlank()) return false
        val cleanIn = incoming.filter { it.isDigit() }.takeLast(10)
        val savedSet = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_NUMBERS, emptySet()) ?: emptySet()
        return savedSet.any { it.length == 10 && cleanIn == it }
    }

    fun answer() {
        activeCall?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun hangup() {
        activeCall?.disconnect()
    }

    fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        val route = if (isSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
        serviceInstance?.setAudioRoute(route)
        notifyUI()
    }

    fun addMissedCall(ctx: Context, number: String) {
        val time = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date())
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_MISSED, "") ?: ""
        val updated = "• Missed Call ($number) - $time\n$old"
        prefs.edit().putString(KEY_MISSED, updated).apply()
    }

    fun getMissedCalls(ctx: Context): String {
        val logs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MISSED, "") ?: ""
        return if (logs.isBlank()) "Koi Missed Call nahi hai." else logs
    }

    fun clearMissedCalls(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_MISSED).apply()
        notifyUI()
    }

    fun notifyUI() {
        Handler(Looper.getMainLooper()).post {
            onStateChangedListener?.invoke()
        }
    }
}

class StealthInCallService : InCallService() {
    private var wasAnswered = false
    private var ringtone: Ringtone? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_ACTIVE) {
                wasAnswered = true
                stopNormalRingtone()
            }
            if (state == Call.STATE_DISCONNECTED) {
                stopNormalRingtone()
                val num = call.details.handle?.schemeSpecificPart ?: ""
                if (HiddenCallManager.isHidden(applicationContext, num)) {
                    if (!wasAnswered) {
                        HiddenCallManager.addMissedCall(applicationContext, num)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        deleteFromSystemCallLog(num)
                    }, 1500)
                }
                HiddenCallManager.activeCall = null
                HiddenCallManager.isSpeakerOn = false
                call.unregisterCallback(this)
            }
            HiddenCallManager.notifyUI()
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        HiddenCallManager.serviceInstance = this
        HiddenCallManager.activeCall = call
        wasAnswered = (call.state == Call.STATE_ACTIVE || call.state == Call.STATE_DIALING)
        call.registerCallback(callback)

        val incomingNum = call.details.handle?.schemeSpecificPart
        if (HiddenCallManager.isHidden(applicationContext, incomingNum)) {
            try {
                val tm = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                tm.silenceRinger()
            } catch (_: Exception) {}
            HiddenCallManager.notifyUI()
        } else {
            if (call.state == Call.STATE_RINGING) {
                playNormalRingtone()
            }
            val intent = Intent(this, NormalCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        stopNormalRingtone()
        if (HiddenCallManager.activeCall == call) {
            HiddenCallManager.activeCall = null
        }
        HiddenCallManager.notifyUI()
    }

    private fun playNormalRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            ringtone?.play()
        } catch (_: Exception) {}
    }

    private fun stopNormalRingtone() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {}
    }

    private fun deleteFromSystemCallLog(number: String) {
        try {
            val last10 = number.filter { it.isDigit() }.takeLast(10)
            if (last10.length == 10) {
                contentResolver.delete(
                    CallLog.Calls.CONTENT_URI,
                    "${CallLog.Calls.NUMBER} LIKE ?",
                    arrayOf("%$last10")
                )
            }
        } catch (_: Exception) {}
    }
}

class NormalCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#1E1E2E"))
        }

        val callerText = TextView(this).apply {
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 60)
        }

        val answerBtn = Button(this).apply {
            text = "🟢 Receive Call"
            textSize = 18f
            setOnClickListener { HiddenCallManager.answer() }
        }

        val speakerBtn = Button(this).apply {
            text = "🔊 Speaker ON / OFF"
            textSize = 16f
            setOnClickListener { HiddenCallManager.toggleSpeaker() }
        }

        val endBtn = Button(this).apply {
            text = "🔴 Cut Call"
            textSize = 18f
            setOnClickListener {
                HiddenCallManager.hangup()
                finish()
            }
        }

        layout.addView(callerText)
        layout.addView(answerBtn)
        layout.addView(speakerBtn)
        layout.addView(endBtn)
        setContentView(layout)

        val updateUI = {
            val call = HiddenCallManager.activeCall
            if (call == null || call.state == Call.STATE_DISCONNECTED) {
                finish()
            } else {
                val num = call.details.handle?.schemeSpecificPart ?: "Unknown"
                val status = if (call.state == Call.STATE_RINGING) "Incoming Call..." else "Connected"
                callerText.text = "$status\n\n$num"
                answerBtn.visibility = if (call.state == Call.STATE_RINGING) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        HiddenCallManager.onStateChangedListener = updateUI
        updateUI()
    }
}

class MainActivity : Activity() {
    private lateinit var dialerDisplay: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showDisguiseLockScreen()
    }

    private fun showDisguiseLockScreen() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)
        }

        val title = TextView(this).apply {
            text = "📝 Quick Notes"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        }

        val noteBox = EditText(this).apply {
            hint = "Write a note or enter PIN (1234)..."
            minLines = 3
        }

        val unlockBtn = Button(this).apply {
            text = "Save Note"
            setOnClickListener {
                if (noteBox.text.toString().trim() == "1234") {
                    showSecretVault()
                } else {
                    Toast.makeText(this@MainActivity, "Note Saved!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(noteBox)
        layout.addView(unlockBtn)
        setContentView(layout)
    }

    private fun showSecretVault() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 40, 30, 40)
        }

        val header = TextView(this).apply {
            text = "🔒 Secret Vault & Dialer"
            textSize = 20f
            setPadding(0, 0, 0, 16)
        }

        val permBtn = Button(this).apply {
            text = "Step 1: Default Dialer Permission"
            setOnClickListener { requestPermissionsAndRole() }
        }

        val numberInput = EditText(this).apply {
            hint = "Hidden Numbers (jaise: 9876543210)"
            setText(HiddenCallManager.getHiddenNumbers(this@MainActivity))
        }

        val saveBtn = Button(this).apply {
            text = "Step 2: Save Hidden Numbers"
            setOnClickListener {
                HiddenCallManager.setHiddenNumbers(this@MainActivity, numberInput.text.toString())
                Toast.makeText(this@MainActivity, "Saved Successfully!", Toast.LENGTH_SHORT).show()
            }
        }

        val liveStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, 20, 0, 10)
        }

        val answerBtn = Button(this).apply {
            text = "🟢 Answer Silent Call"
            setOnClickListener { HiddenCallManager.answer() }
        }

        val speakerBtn = Button(this).apply {
            text = "🔊 Speaker ON / OFF"
            setOnClickListener { HiddenCallManager.toggleSpeaker() }
        }

        val endBtn = Button(this).apply {
            text = "🔴 Cut / Reject Call"
            setOnClickListener { HiddenCallManager.hangup() }
        }

        // --- GENERAL DIALER SECTION ---
        val dialerHeader = TextView(this).apply {
            text = "\n📞 Normal Call Dialer:"
            textSize = 18f
        }

        dialerDisplay = EditText(this).apply {
            hint = "Number daalein ya Contact chunein..."
            textSize = 20f
            isFocusable = false
            isClickable = false
        }

        val pickContactBtn = Button(this).apply {
            text = "👥 Choose from Contacts"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_PICK, ContactsContract.Common.PhoneNumbers.CONTENT_URI)
                startActivityForResult(intent, 201)
            }
        }

        // Keypad grid
        val grid = GridLayout(this).apply {
            columnCount = 3
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }

        val buttons = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        for (bText in buttons) {
            val btn = Button(this).apply {
                text = bText
                textSize = 18f
                setOnClickListener {
                    dialerDisplay.setText(dialerDisplay.text.toString() + bText)
                }
            }
            val param = GridLayout.LayoutParams().apply {
                width = 200
                height = 130
                setMargins(8, 8, 8, 8)
            }
            grid.addView(btn, param)
        }

        val dialActionLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 20)
        }

        val callBtn = Button(this).apply {
            text = "📞 Call"
            setBackgroundColor(Color.parseColor("#00AA00"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val num = dialerDisplay.text.toString()
                if (num.isNotEmpty()) {
                    startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
                } else {
                    Toast.makeText(this@MainActivity, "Pehle number daalein", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val clearDialBtn = Button(this).apply {
            text = "⬅ Del"
            setOnClickListener {
                val current = dialerDisplay.text.toString()
                if (current.isNotEmpty()) {
                    dialerDisplay.setText(current.dropLast(1))
                }
            }
        }

        dialActionLayout.addView(callBtn)
        dialActionLayout.addView(clearDialBtn)

        val missedTitle = TextView(this).apply {
            text = "\n📋 Private Missed Calls:"
            textSize = 18f
        }

        val missedList = TextView(this).apply {
            textSize = 14f
            setPadding(0, 8, 0, 20)
        }

        val clearBtn = Button(this).apply {
            text = "Clear Missed Call History"
            setOnClickListener { HiddenCallManager.clearMissedCalls(this@MainActivity) }
        }

        layout.addView(header)
        layout.addView(permBtn)
        layout.addView(numberInput)
        layout.addView(saveBtn)
        layout.addView(liveStatus)
        layout.addView(answerBtn)
        layout.addView(speakerBtn)
        layout.addView(endBtn)
        
        // Add Dialer views
        layout.addView(dialerHeader)
        layout.addView(pickContactBtn)
        layout.addView(dialerDisplay)
        layout.addView(grid)
        layout.addView(dialActionLayout)

        layout.addView(missedTitle)
        layout.addView(missedList)
        layout.addView(clearBtn)
        scroll.addView(layout)
        setContentView(scroll)

        val refreshUI = {
            val call = HiddenCallManager.activeCall
            when (call?.state) {
                Call.STATE_RINGING -> {
                    liveStatus.text = "🔴 SECRET CONTACT CALLING SILENTLY!"
                    liveStatus.setTextColor(Color.RED)
                    answerBtn.isEnabled = true
                    endBtn.isEnabled = true
                    speakerBtn.isEnabled = true
                }
                Call.STATE_ACTIVE, Call.STATE_DIALING -> {
                    liveStatus.text = "🟢 Call Connected (Speaker: ${if (HiddenCallManager.isSpeakerOn) "ON" else "OFF"})"
                    liveStatus.setTextColor(Color.parseColor("#007F00"))
                    answerBtn.isEnabled = false
                    endBtn.isEnabled = true
                    speakerBtn.isEnabled = true
                }
                else -> {
                    liveStatus.text = "Status: No Active Secret Call"
                    liveStatus.setTextColor(Color.DKGRAY)
                    answerBtn.isEnabled = false
                    endBtn.isEnabled = false
                    speakerBtn.isEnabled = false
                }
            }
            missedList.text = HiddenCallManager.getMissedCalls(this)
        }

        HiddenCallManager.onStateChangedListener = refreshUI
        refreshUI()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 201 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val cursor = contentResolver.query(uri, arrayOf(ContactsContract.Common.PhoneNumbers.NUMBER), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val number = it.getString(0)
                        dialerDisplay.setText(number)
                    }
                }
            }
        }
    }

    private fun requestPermissionsAndRole() {
        requestPermissions(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_CONTACTS
            ), 101
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER), 102)
            }
        } else {
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivityForResult(intent, 102)
        }
    }
}
