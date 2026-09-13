package app.mangatrans.bench

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Story 1.1 + 1.2 — app tran do LiteRT-LM.
 *
 * KHONG phai code san pham. Muc dich duy nhat: tra loi cong chan AD-14.
 *   - nap duoc model Gemma 4 E2B khong
 *   - backend GPU co dung duoc tren Adreno 642L khong, va co TU lui ve CPU duoc khong
 *   - tok/s that tren tung backend
 *
 * AD-22: moi con so o day CHI co gia tri khi chay tren Galaxy M52 that.
 * Chay tren may ao x86 chi de xac nhan app khong sap.
 *
 * Dung ham benchmark() co san cua LiteRT-LM thay vi tu dem ky tu — no tra ve
 * BenchmarkInfo voi dung nhung so can: initTime, timeToFirstToken, decodeTokensPerSecond.
 */
class BenchActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MangaBench"

        /**
         * Repo HF litert-community/gemma-4-E2B-it-litert-lm co NHIEU bien the:
         *   gemma-4-E2B-it.litertlm            2.41 GB  <- ban chung, dung cho CPU
         *   gemma-4-E2B-it-gpu.litertlm        1.87 GB  <- ban RIENG cho GPU
         *   gemma-4-E2B-it_qualcomm_sm8750     2.81 GB  <- NPU, Snapdragon 8 Elite
         *
         * M52 dung Snapdragon 778G (sm7325) — KHONG co ban NPU nao cho chip nay,
         * nen chi do 2 backend. Thu Backend.GPU() voi file CPU se cho ket luan SAI.
         */
        const val MODEL_CPU = "/data/local/tmp/gemma-4-E2B-it.litertlm"
        const val MODEL_GPU = "/data/local/tmp/gemma-4-E2B-it-gpu.litertlm"
    }

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 12f }
        setContentView(ScrollView(this).apply { addView(out) })
        lifecycleScope.launch { runAll() }
    }

    private fun log(line: String = "") {
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    private suspend fun runAll() = withContext(Dispatchers.Default) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        log("=== MangaTrans bench — Story 1.1/1.2 ===")
        log("May    : ${Build.MANUFACTURER} ${Build.MODEL}")
        log("SoC    : ${Build.SOC_MODEL} / ${Build.HARDWARE}")
        log("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        log("ABI    : $abi")
        log()

        if (abi.startsWith("x86")) {
            log("!! MAY AO x86 — moi so tok/s duoi day phan anh CPU cua may dev,")
            log("!! KHONG phai Snapdragon 778G. Lan chay nay chi de xac nhan")
            log("!! app khong sap. Cam chep so nay vao tai lieu (AD-22).")
            log()
        }

        val cpu = File(MODEL_CPU)
        val gpu = File(MODEL_GPU)
        if (!cpu.exists() && !gpu.exists()) {
            log("LOI: khong thay model nao. Day file len bang:")
            log("  adb push gemma-4-E2B-it.litertlm $MODEL_CPU")
            log("  adb push gemma-4-E2B-it-gpu.litertlm $MODEL_GPU")
            return@withContext
        }
        if (cpu.exists()) log("model CPU: %.2f GB".format(cpu.length() / 1e9))
        if (gpu.exists()) log("model GPU: %.2f GB".format(gpu.length() / 1e9))
        log()

        // AD-2: thu GPU truoc, bat loi, TU lui ve CPU.
        // LiteRT-LM KHONG co fallback tu dong — GPU init that bai lam hong ca Engine.
        var gpuOk = false
        if (gpu.exists()) {
            gpuOk = bench("GPU", MODEL_GPU, Backend.GPU())
        } else {
            log("--- bo qua GPU: chua co $MODEL_GPU ---"); log()
        }

        if (gpu.exists() && !gpuOk) {
            log("=> GPU KHONG dung duoc.")
            if (abi.startsWith("x86")) {
                // May ao khong co OpenCL. Day KHONG phai bang chung ve Adreno 642L.
                log("   NHUNG day la may ao — may ao khong co OpenCL noi chung.")
                log("   Lan chay nay KHONG noi duoc gi ve Adreno 642L cua M52.")
                log("   Cau hoi GPU chi tra loi duoc tren may that (AD-22).")
            } else {
                log("   Day la ket qua HOP LE cua Story 1.2, khong phai that bai.")
                log("   => Cap nhat AD-2: CPU la duong mac dinh,")
                log("      GPU la toi uu co dieu kien. Ghi nguyen van loi o tren.")
            }
            log()
        }

        if (cpu.exists()) bench("CPU", MODEL_CPU, Backend.CPU())

        log()
        log("=== xong ===")
        if (!abi.startsWith("x86")) {
            log("Chep vao spike/FINDINGS.md, ghi ro: so do tren MAY THAT ${Build.MODEL}.")
        }

        // Sinh thu mot cau de xac nhan model that su dich duoc, khong chi chay duoc.
        if (cpu.exists()) sampleTranslate(MODEL_CPU, Backend.CPU())
    }

    /**
     * Do bang ham benchmark() chinh thuc cua LiteRT-LM.
     *
     * benchmark() duoc danh dau @ExperimentalApi — thu vien con o 0.x nen API
     * co the doi. Chap nhan o app do luong (khong phai code san pham), nhung
     * khi nang phien ban LiteRT-LM thi phai kiem lai chu ky ham.
     */
    @OptIn(ExperimentalApi::class)
    private fun bench(name: String, modelPath: String, backend: Backend): Boolean {
        log("--- backend $name ---")
        return try {
            val info = benchmark(modelPath, backend)
            val initS = info.initTimeInSecond
            log("initialize     : %.2f s".format(initS))
            if (initS > 8.0) {
                log("  ^ vuot ngan sach 8s cua NFR-005")
                log("    => BAT BUOC ham nong engine luc bat app (AD-20)")
            }
            log("token dau tien : %.2f s".format(info.timeToFirstTokenInSecond))
            log("prefill        : %.1f tok/s (%d token)"
                .format(info.lastPrefillTokensPerSecond, info.lastPrefillTokenCount))
            log("decode         : %.1f tok/s (%d token)   <-- so quan trong nhat"
                .format(info.lastDecodeTokensPerSecond, info.lastDecodeTokenCount))

            // Mot man manga ~250-400 token dau ra (do o Phase 0).
            val d = info.lastDecodeTokensPerSecond
            if (d > 0) {
                log("=> uoc tinh 1 trang manga (300 token): %.0f s".format(300 / d))
                log("   nguong NFR-005b la 90s")
            }
            true
        } catch (t: Throwable) {
            log("THAT BAI: ${t::class.java.simpleName}: ${t.message}")
            Log.e(TAG, "backend $name loi", t)
            false
        } finally {
            log()
        }
    }

    /** Chay thu mot cau dich that — de biet model khong chi chay ma con dung. */
    private fun sampleTranslate(modelPath: String, backend: Backend) {
        log("--- thu dich mot cau ---")
        try {
            Engine(EngineConfig(modelPath = modelPath, backend = backend)).use { engine ->
                engine.initialize()
                engine.createConversation().use { conv ->
                    val msg = conv.sendMessage(
                        "Dịch sang tiếng Việt, chỉ trả về bản dịch, không giải thích: " +
                            "「では俺は失礼してー」"
                    )
                    log("JA: 「では俺は失礼してー」")
                    log("VI: ${msg.contents}")
                    log("(Phase 0 tren PC cho: \"Vậy thì tao xin phép lui nha.\")")
                }
            }
        } catch (t: Throwable) {
            log("loi khi dich thu: ${t::class.java.simpleName}: ${t.message}")
            Log.e(TAG, "sampleTranslate loi", t)
        }
    }
}
