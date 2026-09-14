package app.mangatrans.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.mangatrans.service.CaptureService

/**
 * Story 3.2 — man hinh TRONG SUOT, chi song du lau de xin quyen chup.
 *
 * Vi sao phai la mot Activity: `MediaProjectionManager.createScreenCaptureIntent()`
 * bat buoc di qua `startActivityForResult`. Service khong tu xin duoc.
 *
 * Vi sao no dong ngay: nguoi dung dang doc truyen o app khac. Man hinh nay ma
 * nan lai mot nhip la da cat ngang mach doc.
 *
 * ⚠️ `ComponentActivity` chu KHONG phai `AppCompatActivity`: manifest gan theme
 * trong suot cua he thong, ma `AppCompatActivity` doi theme phai la hau due cua
 * `Theme.AppCompat`, neu khong thi nem
 * `IllegalStateException: You need to use a Theme.AppCompat theme`.
 * Da sap that tren may. `ComponentActivity` du cho `registerForActivityResult`
 * va khong rang buoc theme.
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val ask = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // Ket qua duoc chuyen cho service — AD-21: service la chu so huu
            // DUY NHAT cua MediaProjection. Activity nay khong duoc giu gi ca.
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            )
        } else {
            // Tu choi la lua chon hop le, khong phai loi. Noi mot cau roi thoi.
            Toast.makeText(
                this,
                "Chưa cấp quyền chụp màn hình nên chưa dịch được. Chạm icon để cấp lại.",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
        overridePendingTransitionCompat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        ask.launch(mgr.createScreenCaptureIntent())
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() = overridePendingTransition(0, 0)
}
