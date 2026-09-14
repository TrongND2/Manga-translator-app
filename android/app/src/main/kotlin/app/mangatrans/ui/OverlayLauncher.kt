package app.mangatrans.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import app.mangatrans.service.CaptureService

/**
 * Story 3.1 — bat icon noi.
 *
 * `SYSTEM_ALERT_WINDOW` KHONG xin duoc bang hop thoai thong thuong: Android bat
 * nguoi dung tu vao Cai dat bat cong tac. Nen o day chi giai thich roi mo dung
 * trang Cai dat — khong co duong tat nao ca.
 */
object OverlayLauncher {

    fun canDrawOverlay(a: Activity): Boolean = Settings.canDrawOverlays(a)

    /**
     * @return true neu da bat service; false neu con thieu quyen (da mo Cai dat).
     */
    fun start(a: Activity): Boolean {
        if (!canDrawOverlay(a)) { explainAndOpenSettings(a); return false }
        a.startForegroundService(Intent(a, CaptureService::class.java))
        return true
    }

    fun stop(a: Activity) = a.startService(CaptureService.stopIntent(a))

    private fun explainAndOpenSettings(a: Activity) {
        AlertDialog.Builder(a)
            .setTitle("Cần quyền hiển thị trên ứng dụng khác")
            .setMessage(
                "Icon dịch phải nổi lên trên app đọc truyện, nên Android bắt bạn tự bật " +
                    "công tắc này trong Cài đặt — không có hộp thoại xin nhanh.\n\n" +
                    "Bấm Mở Cài đặt, tìm \"Manga Translator\" rồi bật, sau đó quay lại đây."
            )
            .setNegativeButton("Để sau", null)
            .setPositiveButton("Mở Cài đặt") { _, _ ->
                a.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${a.packageName}"),
                    )
                )
            }
            .show()
    }
}
