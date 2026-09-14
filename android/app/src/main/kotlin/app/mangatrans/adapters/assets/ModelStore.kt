package app.mangatrans.adapters.assets

import android.content.Context
import android.os.StatFs
import app.mangatrans.ports.ModelFile
import app.mangatrans.ports.PackageManifest
import app.mangatrans.ports.PackageState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Epic 4 / AD-15 — noi DUY NHAT biet goi mo hinh nam o dau tren may.
 *
 * Hai thu muc, khong phai mot:
 *   - `modelsDir` — app tu tai ve, app tu xoa duoc (Story 4.5)
 *   - `/data/local/tmp` — do dev day tay bang `adb push`
 *
 * Giu ca hai vi suot Epic 1-3 model deu nam o `/data/local/tmp`, va bo duong do
 * se lam moi lenh thu trong handoff hong het. `Composition` tim o `modelsDir`
 * TRUOC — de goi tai ve luon thang goi day tay.
 */
class ModelStore(ctx: Context, private val appVersion: Int) {

    /** Ban manifest dong goi trong APK — duong du phong khi khong lay duoc tu xa. */
    val assets: android.content.res.AssetManager = ctx.assets

    companion object {
        const val DEV_DIR = "/data/local/tmp"

        /**
         * AD-15 — manifest den TU XA, khong dong goi trong APK.
         *
         * Neu dong goi trong APK thi phep kiem "khoang phien ban tuong thich"
         * luon dung, tuc la vo dung: no ton tai de chan tinh trang app cu gap
         * goi moi.
         *
         * Host o repo GitHub cua chinh du an — D2 chot chi phi 0 dong, ma repo
         * thi von da co.
         */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/TrongND2/Manga-translator-app/main/package.json"

        /** Duoi cho file dang tai do. Doi ten khi va chi khi checksum dung. */
        const val PART = ".part"

        fun parseManifest(json: String): PackageManifest {
            val o = JSONObject(json)
            val arr = o.getJSONArray("files")
            val files = buildList {
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    add(
                        ModelFile(
                            name = f.getString("name"),
                            sizeBytes = f.getLong("sizeBytes"),
                            sha256 = f.getString("sha256").lowercase(),
                            url = f.getString("url"),
                        )
                    )
                }
            }
            return PackageManifest(
                packageVersion = o.getString("packageVersion"),
                minAppVersion = o.getInt("minAppVersion"),
                maxAppVersion = o.getInt("maxAppVersion"),
                files = files,
            )
        }
    }

    /** `noBackupFilesDir`: 2.7 GB mo hinh KHONG duoc chui vao ban sao luu dam may. */
    val modelsDir: File = File(ctx.noBackupFilesDir, "models").apply { mkdirs() }

    private val devDir = File(DEV_DIR)

    /** Duong dan thuc cua mot file mo hinh, hoac null neu chua co. */
    fun find(name: String): File? =
        File(modelsDir, name).takeIf { it.isFile }
            ?: File(devDir, name).takeIf { it.isFile }

    fun partFile(name: String) = File(modelsDir, name + PART)

    /** Bao nhieu byte da tai duoc cua file nay (ke ca phan do). */
    fun bytesOnDisk(name: String): Long {
        val done = File(modelsDir, name)
        if (done.isFile) return done.length()
        return partFile(name).takeIf { it.isFile }?.length() ?: 0L
    }

    /**
     * Story 4.3 — kiem tung file. Tra ve trang thai, khong nem loi.
     *
     * ⚠️ Bam sha256 ca goi 2.7 GB mat thoi gian THAT (doc het tung byte). Chi
     * goi sau khi tai xong, dung goi moi lan mo app — do la ly do co
     * `verifiedMarker`.
     */
    suspend fun verify(manifest: PackageManifest): PackageState = withContext(Dispatchers.IO) {
        if (!manifest.supports(appVersion)) {
            return@withContext PackageState.Incompatible(manifest, appVersion)
        }

        val have = manifest.files.sumOf { bytesOnDisk(it.name) }
        val missing = manifest.files.filter { find(it.name) == null }
        if (missing.isNotEmpty()) {
            return@withContext if (have == 0L) PackageState.Absent
            else PackageState.Partial(have, manifest.totalBytes)
        }

        val bad = manifest.files.filterNot { f ->
            val onDisk = find(f.name)!!
            // File day tay bang adb push khong co checksum trong manifest cua no
            // — nhung neu kich thuoc khop thi van bam de kiem.
            onDisk.length() == f.sizeBytes && sha256(onDisk) == f.sha256
        }.map { it.name }

        if (bad.isEmpty()) {
            markVerified(manifest.packageVersion)
            PackageState.Ready(manifest)
        } else {
            PackageState.Corrupt(bad)
        }
    }

    /**
     * Da kiem xong goi nao — de lan mo app sau khong phai bam lai 2.7 GB.
     * Mat file nay thi chi ton mot lan bam, khong mat du lieu.
     */
    private val verifiedMarker = File(modelsDir, ".verified")

    private fun markVerified(packageVersion: String) =
        runCatching { verifiedMarker.writeText(packageVersion) }

    fun verifiedVersion(): String? =
        runCatching { verifiedMarker.takeIf { it.isFile }?.readText() }.getOrNull()

    /** Du file (theo TEN va KICH THUOC) chua — phep kiem re, dung luc mo app. */
    fun looksComplete(manifest: PackageManifest): Boolean =
        manifest.files.all { f -> find(f.name)?.length() == f.sizeBytes }

    /**
     * Story 4.5 — xoa goi mo hinh, **khong** dung toi glossary hay cau hinh.
     *
     * Chi xoa trong `modelsDir`. File o `/data/local/tmp` la do dev day tay,
     * app khong xoa — vua khong co quyen, vua khong phai cua no.
     *
     * @return so byte da giai phong
     */
    suspend fun deletePackage(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        modelsDir.listFiles()?.forEach { f ->
            val n = f.length()
            if (f.delete()) freed += n
        }
        freed
    }

    /** Tong dung luong goi dang chiem — de noi "xoa se giai phong bao nhieu". */
    suspend fun sizeOnDisk(): Long = withContext(Dispatchers.IO) {
        modelsDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /** Con du cho khong. Hoi TRUOC khi tai, khong phai luc tai duoc nua chung. */
    fun freeBytes(): Long = runCatching {
        val s = StatFs(modelsDir.absolutePath)
        s.availableBlocksLong * s.blockSizeLong
    }.getOrDefault(0L)

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
