package app.mangatrans.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import app.mangatrans.service.CaptureService

/**
 * Man hinh TRONG SUOT, chi song du lau de xin quyen chup man hinh.
 *
 * Vi sao phai la mot Activity: `MediaProjectionManager.createScreenCaptureIntent()`
 * bat buoc di qua `startActivityForResult`. Service khong tu xin duoc.
 *
 * ⚠️ `ComponentActivity` chu KHONG phai `AppCompatActivity`: manifest gan theme
 * trong suot cua he thong, ma `AppCompatActivity` doi theme phai la hau due cua
 * `Theme.AppCompat`, neu khong thi nem
 * `IllegalStateException: You need to use a Theme.AppCompat theme`. Da sap that.
 *
 * ⚠️ Manifest dat `taskAffinity=""` cho Activity nay, nen no chay trong task
 * RIENG. Khong co dong do thi mo no tu service se **keo ca task cua app len
 * truoc** — man hinh nhay ve trang chu cua app roi moi hien hop thoai, nhin y
 * nhu app tu bat len de theo doi man hinh. Nguoi dung dang doc truyen thi phai
 * o nguyen do.
 */
class ProjectionRequestActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_START_SERVICE = "startService"

        /**
         * @param alsoStartService true khi goi tu man hinh chinh (bat icon lan
         *   dau): xin quyen xong thi bat luon service. false khi service DA chay
         *   va chi can cap lai quyen (vi du sau khi khoa man hinh).
         */
        fun intent(ctx: Context, alsoStartService: Boolean) =
            Intent(ctx, ProjectionRequestActivity::class.java)
                .putExtra(EXTRA_START_SERVICE, alsoStartService)
                // NEW_TASK + CLEAR_TASK, khong phai chi NEW_TASK.
                //
                // `taskAffinity=""` cho Activity nay mot task rieng — nhung task
                // do TON TAI GIUA CAC LAN GOI. Neu lan truoc bo do giua chung
                // (vi du hop thoai he thong con ket lai trong do), thi lan sau
                // `startActivity` nhap vao dung cai task hong ay va **khong co
                // gi xay ra ca**: khong log, khong loi, khong man hinh.
                //
                // Da mat mot vong do de tim: `ActivityRecord{... MediaProjection
                // PermissionActivity} t9448` van nam do tu lan chay truoc.
                //
                // CLEAR_TASK don sach task truoc khi bat dau, nen moi lan xin
                // quyen la mot lan sach.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    private var startService = false

    private val ask = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // AD-21: service la chu so huu DUY NHAT cua MediaProjection.
            // Activity nay khong duoc giu gi ca — chuyen thang ket qua di.
            val i = Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            startForegroundService(i)
        } else {
            // Tu choi la lua chon hop le, khong phai loi. Noi mot cau roi thoi.
            Toast.makeText(
                this,
                if (startService)
                    "Chưa cấp quyền chụp màn hình nên chưa bật được. Mở app bật lại lúc nào cũng được."
                else
                    "Chưa cấp quyền chụp màn hình nên chưa dịch được. Chạm icon để cấp lại.",
                Toast.LENGTH_LONG,
            ).show()
        }
        finish()
        overridePendingTransitionCompat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService = intent?.getBooleanExtra(EXTRA_START_SERVICE, false) == true

        // Bat service TRUOC khi xin quyen: service phai ton tai de nhan ket qua,
        // va no bat dau o loai `specialUse` nen chua can quyen chup (F34).
        if (startService && !CaptureService.isRunning) {
            startForegroundService(Intent(this, CaptureService::class.java))
        }

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        ask.launch(mgr.createScreenCaptureIntent())
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() = overridePendingTransition(0, 0)
}
