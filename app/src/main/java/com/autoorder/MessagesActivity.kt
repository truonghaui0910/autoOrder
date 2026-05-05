package com.autoorder

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MessagesActivity : AppCompatActivity() {

    private lateinit var db: MessagesDb
    private lateinit var adapter: MessagesAdapter
    private lateinit var emptyView: TextView
    private lateinit var headerCount: TextView
    private lateinit var counter: TextView
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_messages)

        db = MessagesDb(this)
        emptyView = findViewById(R.id.emptyView)
        headerCount = findViewById(R.id.headerCount)
        counter = findViewById(R.id.counter)

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = MessagesAdapter(emptyList())
        list.adapter = adapter

        // Bottom bar
        findViewById<View>(R.id.btnHome).setOnClickListener { finish() }
        findViewById<View>(R.id.btnReload).setOnClickListener { loadData() }
        findViewById<View>(R.id.btnDump).setOnClickListener {
            Toast.makeText(this, "Quét DOM phải làm ở màn Chat", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnInbox).setOnClickListener { /* đang ở đây rồi */ }
        findViewById<View>(R.id.btnDb).setOnClickListener {
            Toast.makeText(this, "Đang ở DB", Toast.LENGTH_SHORT).show()
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun loadData() {
        ioExecutor.execute {
            val rows = runCatching { db.queryAll(2000) }.getOrDefault(emptyList())
            mainHandler.post {
                adapter.submit(rows)
                emptyView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                headerCount.text = "${rows.size} tin"
                counter.text = "${rows.size} tin"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
        runCatching { db.close() }
    }
}
