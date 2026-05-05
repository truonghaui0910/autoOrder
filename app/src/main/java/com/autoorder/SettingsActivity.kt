package com.autoorder

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val group = findViewById<RadioGroup>(R.id.groupViewMode)
        val initial = AppPrefs.getViewMode(this)
        group.check(if (initial == AppPrefs.MODE_MOBILE) R.id.rbMobile else R.id.rbWeb)

        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rbMobile) AppPrefs.MODE_MOBILE else AppPrefs.MODE_WEB
            if (mode != AppPrefs.getViewMode(this)) {
                AppPrefs.setViewMode(this, mode)
                Toast.makeText(this, "Đã lưu cài đặt", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, ChatWebActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<View>(R.id.btnReload).setOnClickListener { /* no-op */ }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            Toast.makeText(this, "Quét DOM phải làm ở màn Chat", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener {
            startActivity(Intent(this, MessagesActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { /* đang ở đây */ }
    }
}
