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
    private const val KEY_NUMBER = "hidden_number"
    private const val KEY_MISSED = "missed_calls"

    fun setHiddenNumber(ctx: Context, number: String) {
        val clean = number.filter { it.isDigit() }.takeLast(10)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NUMBER, clean).apply()
    }

    fun getHiddenNumber(ctx: Context): String {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NUMBER, "") ?: ""
    }

    fun isHidden(ctx: Context, incoming: String?): Boolean {
        if (incoming.isNullOrBlank()) return false
        val cleanIn = incoming.filter { it.isDigit() }.takeLast(10)
        val saved = getHiddenNumber(ctx)
        return saved.length == 10 && cleanIn == saved
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
            setPadding(40, 48, 40, 48)
        }

        val header = TextView(this).apply {
            text = "🔒 Secret Call Vault"
            textSize = 22f
            setPadding(0, 0, 0, 24)
        }

        val permBtn = Button(this).apply {
            text = "Step 1: Activate Permissions & Default Dialer"
            setOnClickListener { requestPermissionsAndRole() }
        }

        val numberInput = EditText(this).apply {
            hint = "10-Digit Mobile Number daalein"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(HiddenCallManager.getHiddenNumber(this@MainActivity))
        }

        val saveBtn = Button(this).apply {
            text = "Step 2: Save Hidden Number"
            setOnClickListener {
                HiddenCallManager.setHiddenNumber(this@MainActivity, numberInput.text.toString())
                Toast.makeText(this@MainActivity, "Secret Number Saved!", Toast.LENGTH_SHORT).show()
            }
        }

        val liveStatus = TextView(this).apply {
            textSize = 18f
            setPadding(0, 40, 0, 16)
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

        val callOutBtn = Button(this).apply {
            text = "📞 Call Hidden Contact Now"
            setOnClickListener {
                val num = HiddenCallManager.getHiddenNumber(this@MainActivity)
                if (num.length == 10) {
                    startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
                } else {
                    Toast.makeText(this@MainActivity, "Pehle 10-digit number save karein", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val missedTitle = TextView(this).apply {
            text = "\n📋 Private Missed Calls:"
            textSize = 18f
        }

        val missedList = TextView(this).apply {
            textSize = 15f
            setPadding(0, 12, 0, 20)
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
        layout.addView(callOutBtn)
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

    private fun requestPermissionsAndRole() {
        requestPermissions(
            arrayOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALL_LOG,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS
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
