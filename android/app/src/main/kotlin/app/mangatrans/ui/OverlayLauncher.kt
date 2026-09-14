package app.mangatrans.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import app.mangatrans.service.CaptureService

/**
 * Bat icon noi — va xin **ca hai quyen ngay tai day**, mot lan, truoc khi icon
 * xuat hien.
 *
 * ⚠️ Truoc day app bat icon truoc roi de nguoi dung cham icon moi xin quyen
 * chup. Hai cai sai voi cach do:
 *
 *   1. Nguoi dung phai cap quyen o hai thoi diem khac nhau, cach nhau vai phut,
 *      va lan thu hai thi ho da o trong app doc truyen — dang ngo hon nhieu.
 *   2. Mo `ProjectionRequestActivity` tu service keo ca task cua app len truoc,
 *      nen man hinh **nhay ve trang chu cua app** roi moi hien hop thoai. Nhin
 *      y nhu app dang tu bat len de theo doi man hinh.
 *
 * Gio: bam Bat -> xin quyen hien tren app khac -> xin quyen chup -> icon hien ra
 * o trang thai san sang luon. Khong con icon 🔑.
 */
object OverlayLauncher {

    fun canDrawOverlay(a: Activity): Boolean = Settings.canDrawOverlays(a)

    /**
     * @return true neu da bat service; false neu con thieu quyen hien tren app
     *   khac (da mo Cai dat cho nguoi dung bat).
     */
    fun start(a: Activity): Boolean {
        if (!canDrawOverlay(a)) { explainAndOpenSettings(a); return false }
        // Xin quyen chup NGAY, khong doi den luc cham icon.
        a.startActivity(ProjectionRequestActivity.intent(a, alsoStartService = true))
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
