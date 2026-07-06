package com.autoorder

import android.app.Dialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object UpdateChecker {

    private const val TAG = "AutoOrder-Update"
    private val io = Executors.newSingleThreadExecutor()

    data class Release(
        val tagName: String,
        val versionName: String,
        val changelog: String,
        val assetId: Long,
        val assetName: String,
        val assetSize: Long
    )

    fun checkFromSettings(ctx: Context) {
        val token = BuildConfig.GITHUB_TOKEN
        val repo = BuildConfig.GITHUB_REPO
        if (token.isBlank() || repo.isBlank()) {
            Toast.makeText(ctx, "Chưa cấu hình GITHUB_TOKEN / GITHUB_REPO", Toast.LENGTH_LONG).show()
            return
        }

        val progress = showProgressDialog(ctx, "Đang kiểm tra cập nhật…")

        io.execute {
            val result = runCatching { fetchLatestRelease(repo, token) }
            mainHandler().post {
                runCatching { progress.dismiss() }
                result.onSuccess { rel ->
                    handleRelease(ctx, rel)
                }.onFailure { e ->
                    Log.w(TAG, "check failed", e)
                    showStatusDialog(
                        ctx,
                        icon = R.drawable.ic_close,
                        iconTint = 0xFFE53935.toInt(),
                        title = "Không kiểm tra được",
                        message = e.message ?: "Lỗi không rõ"
                    )
                }
            }
        }
    }

    private fun handleRelease(ctx: Context, rel: Release) {
        val current = BuildConfig.VERSION_NAME
        if (!isNewer(rel.versionName, current)) {
            showStatusDialog(
                ctx,
                icon = R.drawable.ic_check_circle,
                iconTint = 0xFF43A047.toInt(),
                title = "Đã ở bản mới nhất",
                message = "Phiên bản hiện tại: $current\nMới nhất trên GitHub: ${rel.versionName}"
            )
            return
        }
        showUpdateDialog(ctx, rel, current)
    }

    private fun showUpdateDialog(ctx: Context, rel: Release, current: String) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_update_available, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.92).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        view.findViewById<TextView>(R.id.txtCurrentVersion).text = current
        view.findViewById<TextView>(R.id.txtNewVersion).text = rel.versionName
        val sizeMb = "%.1f".format(rel.assetSize / 1_000_000.0)
        view.findViewById<TextView>(R.id.txtSize).text = "Kích thước: $sizeMb MB"
        val changelog = rel.changelog.trim().ifBlank { "(Không có ghi chú thay đổi)" }
        view.findViewById<TextView>(R.id.txtChangelog).text = changelog.take(4000)

        view.findViewById<ImageView>(R.id.btnDismiss).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnLater).setOnClickListener { dialog.dismiss() }
        view.findViewById<Button>(R.id.btnDownload).setOnClickListener {
            dialog.dismiss()
            downloadAndInstall(ctx, rel)
        }
        dialog.show()
    }

    private fun showStatusDialog(
        ctx: Context,
        icon: Int,
        iconTint: Int,
        title: String,
        message: String
    ) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_update_status, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val iconView = view.findViewById<ImageView>(R.id.iconStatus)
        iconView.setImageResource(icon)
        iconView.imageTintList = android.content.res.ColorStateList.valueOf(iconTint)
        view.findViewById<TextView>(R.id.txtStatusTitle).text = title
        view.findViewById<TextView>(R.id.txtStatusMessage).text = message
        view.findViewById<Button>(R.id.btnOk).setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showProgressDialog(ctx: Context, title: String): Dialog {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_update_progress, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.75).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(false)
        view.findViewById<TextView>(R.id.txtProgressTitle).text = title
        dialog.show()
        return dialog
    }

    private fun fetchLatestRelease(repo: String, token: String): Release {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connectTimeout = 15000
            readTimeout = 20000
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
        if (code !in 200..299) {
            throw RuntimeException("GitHub API HTTP $code: ${body.take(300)}")
        }

        val json = JSONObject(body)
        val tagName = json.optString("tag_name")
        val changelog = json.optString("body")
        val assets = json.optJSONArray("assets") ?: throw RuntimeException("Release không có asset")

        var assetId = -1L
        var assetName = ""
        var assetSize = 0L
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                assetId = a.optLong("id")
                assetName = name
                assetSize = a.optLong("size")
                break
            }
        }
        if (assetId < 0) throw RuntimeException("Release không có file .apk")

        return Release(
            tagName = tagName,
            versionName = tagName.removePrefix("v"),
            changelog = changelog,
            assetId = assetId,
            assetName = assetName,
            assetSize = assetSize
        )
    }

    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        val l = local.removePrefix("v").split(".", "-").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun downloadAndInstall(ctx: Context, rel: Release) {
        val token = BuildConfig.GITHUB_TOKEN
        val repo = BuildConfig.GITHUB_REPO

        val apkDir = File(ctx.getExternalFilesDir(null), "apk").apply { mkdirs() }
        apkDir.listFiles()?.forEach { runCatching { it.delete() } }
        val outFile = File(apkDir, "update_${rel.versionName}.apk")

        val downloadUri = Uri.parse("https://api.github.com/repos/$repo/releases/assets/${rel.assetId}")
        val req = DownloadManager.Request(downloadUri)
            .addRequestHeader("Accept", "application/octet-stream")
            .addRequestHeader("Authorization", "Bearer $token")
            .setTitle("autoOrder ${rel.versionName}")
            .setDescription("Đang tải bản cập nhật")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(outFile))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)

        val progress = showProgressDialog(ctx, "Đang tải APK…")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val gotId = i?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                if (gotId != id) return
                try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                runCatching { progress.dismiss() }

                val query = DownloadManager.Query().setFilterById(id)
                dm.query(query).use { cur: Cursor ->
                    if (cur.moveToFirst()) {
                        val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            installApk(ctx, outFile)
                        } else {
                            val reason = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            showStatusDialog(
                                ctx,
                                icon = R.drawable.ic_close,
                                iconTint = 0xFFE53935.toInt(),
                                title = "Tải thất bại",
                                message = "Status=$status, reason=$reason"
                            )
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(receiver, filter)
        }
    }

    private fun installApk(ctx: Context, file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pm = ctx.packageManager
            if (!pm.canRequestPackageInstalls()) {
                showStatusDialog(
                    ctx,
                    icon = R.drawable.ic_settings,
                    iconTint = 0xFFFB8C00.toInt(),
                    title = "Cần bật quyền cài đặt",
                    message = "Bật quyền 'Cài ứng dụng không rõ nguồn gốc' cho autoOrder rồi bấm Kiểm tra cập nhật lại."
                )
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                    .setData(Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(intent) }
                return
            }
        }

        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }

    private fun mainHandler() = android.os.Handler(android.os.Looper.getMainLooper())
}
