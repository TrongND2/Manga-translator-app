package app.mangatrans.ports

/**
 * Epic 4 / AD-15 — goi mo hinh KHONG nam trong APK, va co phien ban.
 *
 * APK khong chua trong so model (NFR-008: APK <= 100 MB; rieng Gemma da 2.59 GB).
 * Nen phai tai ve, va phai tai duoc **tiep cho do**.
 */
data class ModelFile(
    /** Ten file tren may, cung ten ma `Composition` di tim. */
    val name: String,
    val sizeBytes: Long,
    /** SHA-256 chu thuong. Day la thu phan biet "tai xong" voi "tai dung". */
    val sha256: String,
    val url: String,
) {
    /** Bao nhieu MB — de noi voi nguoi dung, khong dung de tinh toan. */
    val megabytes get() = sizeBytes / 1_000_000
}

/**
 * AD-15 — manifest khai bao phien ban, checksum tung file, va **khoang phien
 * ban app tuong thich**.
 *
 * Khoang tuong thich la thu chan tinh trang "app moi chay voi model cu" (va
 * nguoc lai). No chi co nghia khi manifest den TU XA: manifest dong goi san
 * trong APK thi phep kiem nay luon dung, tuc la vo dung.
 */
data class PackageManifest(
    val packageVersion: String,
    /** `versionCode` nho nhat cua app doc duoc goi nay. */
    val minAppVersion: Int,
    /** `versionCode` lon nhat. */
    val maxAppVersion: Int,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
    val totalGigabytes: Double get() = totalBytes / 1e9

    fun supports(appVersion: Int) = appVersion in minAppVersion..maxAppVersion
}

/**
 * Goi mo hinh tren may dang o trang thai nao.
 *
 * `sealed` chu khong phai mot cap co: moi nhanh doi mot cach xu ly khac nhau o
 * giao dien, va trinh bien dich se bao neu quen nhanh nao.
 */
sealed interface PackageState {

    /** Chua co gi. Moi cai app xong la day. */
    data object Absent : PackageState

    /** Dang tai do, hoac tai dut. `Downloader` noi tiep tu day. */
    data class Partial(val bytesHave: Long, val bytesTotal: Long) : PackageState {
        val percent get() = if (bytesTotal == 0L) 0 else (bytesHave * 100 / bytesTotal).toInt()
    }

    /** Du file, checksum dung het. San sang dich. */
    data class Ready(val manifest: PackageManifest) : PackageState

    /**
     * Du file nhung **checksum sai**. AD-15 doi tai lai PHAN HONG, khong phai
     * ca goi — nen phai biet file nao hong.
     */
    data class Corrupt(val badFiles: List<String>) : PackageState

    /**
     * AD-15 — manifest nam ngoai khoang phien ban app. **Tu choi nap.**
     * Thong bao phai noi ro phai lam gi tiep, khong chi bao "khong tuong thich".
     */
    data class Incompatible(val manifest: PackageManifest, val appVersion: Int) : PackageState
}

/** Tien do tai, phat ra theo dong de giao dien ve thanh tien trinh. */
data class DownloadProgress(
    val fileName: String,
    val fileIndex: Int,
    val fileCount: Int,
    val bytesHave: Long,
    val bytesTotal: Long,
) {
    val percent get() = if (bytesTotal == 0L) 0 else (bytesHave * 100 / bytesTotal).toInt()
}

/**
 * Vi sao mot lan tai that bai — de `ui` noi bang tieng nguoi.
 * Cung ly le nhu `CaptureFailure`: moi nhanh doi mot cach xu ly khac nhau.
 */
sealed interface DownloadFailure {
    data object NoNetwork : DownloadFailure
    data object NotEnoughSpace : DownloadFailure
    /** May chu khong ho tro tai tiep cho do (khong co `Accept-Ranges`). */
    data object ResumeNotSupported : DownloadFailure
    data class ChecksumMismatch(val fileName: String) : DownloadFailure
    data class ServerError(val code: Int) : DownloadFailure
    data object Cancelled : DownloadFailure
}

class DownloadException(val failure: DownloadFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)
