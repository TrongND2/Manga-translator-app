package app.mangatrans.adapters.assets

import android.util.Log
import app.mangatrans.ports.DownloadException
import app.mangatrans.ports.DownloadFailure
import app.mangatrans.ports.DownloadProgress
import app.mangatrans.ports.ModelFile
import app.mangatrans.ports.PackageManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Story 4.2 — tai goi mo hinh, **tiep duoc cho do**.
 *
 * 2.7 GB tren mang di dong la mot lan tai co that se dut giua chung. Lam lai tu
 * dau la cach chac chan nhat de nguoi dung bo cuoc.
 *
 * ⚠️ Da probe truoc khi viet: ca 5 URL tren Hugging Face deu tra `200` va
 * `Accept-Ranges: bytes`, va `content-length` khop dung kich thuoc file that.
 * Neu may chu KHONG ho tro `Range` thi lop nay bao `ResumeNotSupported` chu
 * khong am tham tai lai tu dau.
 *
 * Khong dung `DownloadManager` cua he thong: no khong cho kiem sha256 giua
 * chung, khong bao tien do theo byte du dung, va tren nhieu may Samsung no bi
 * trinh tiet kiem pin giet.
 */
class PackageDownloader(private val store: ModelStore) {

    private companion object {
        const val TAG = "Download"
        const val BUF = 1 shl 16
        const val CONNECT_MS = 20_000
        const val READ_MS = 30_000

        /** Bao nhieu byte thi bao tien do mot lan — bao moi lan doc la qua day. */
        const val REPORT_EVERY = 1L shl 20   // 1 MB
    }

    /** Tai manifest. Day la file DAU TIEN, va no quyet dinh tai gi tiep. */
    suspend fun fetchManifest(url: String = ModelStore.MANIFEST_URL): PackageManifest {
        val text = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                try {
                    if (responseCode != 200) throw DownloadException(
                        DownloadFailure.ServerError(responseCode)
                    )
                    inputStream.bufferedReader().readText()
                } finally { disconnect() }
            }
        }.getOrElse { e ->
            if (e is DownloadException) throw e
            throw DownloadException(DownloadFailure.NoNetwork, e)
        }
        return ModelStore.parseManifest(text)
    }

    /**
     * Tai het cac file con thieu. Phat tien do theo dong.
     *
     * Huy: huy coroutine. File dang do duoc GIU LAI (`.part`), lan sau tiep tu
     * do — do chinh la "tam dung va tiep tuc duoc" ma Story 4.2 doi.
     */
    fun download(manifest: PackageManifest): Flow<DownloadProgress> = flow {
        // Hoi cho TRUOC khi tai, khong phai luc tai duoc nua chung roi moi bao.
        val need = manifest.files.filter { store.find(it.name) == null }
            .sumOf { it.sizeBytes - store.bytesOnDisk(it.name) }
        if (need > 0 && store.freeBytes() < need) {
            throw DownloadException(DownloadFailure.NotEnoughSpace)
        }

        manifest.files.forEachIndexed { i, f ->
            coroutineContext.ensureActive()
            if (store.find(f.name) != null) {
                emit(DownloadProgress(f.name, i, manifest.files.size, f.sizeBytes, f.sizeBytes))
                return@forEachIndexed
            }
            downloadOne(f, i, manifest.files.size)
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun kotlinx.coroutines.flow.FlowCollector<DownloadProgress>.downloadOne(
        f: ModelFile,
        index: Int,
        count: Int,
    ) {
        val part = store.partFile(f.name)
        var have = if (part.isFile) part.length() else 0L

        // Phan do to hon file that => hong, bo di lam lai. Nho hon thi tiep.
        if (have > f.sizeBytes) {
            part.delete(); have = 0L
        }

        val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }

        try {
            val code = runCatching { conn.responseCode }
                .getOrElse { throw DownloadException(DownloadFailure.NoNetwork, it) }

            when {
                have > 0 && code == 206 -> Unit          // tiep dung cho do
                have == 0L && code == 200 -> Unit        // tai moi
                // Xin tiep cho do ma may chu tra ca file => no khong ho tro Range.
                // Bao ro chu khong am tham tai lai 2.7 GB tu dau.
                have > 0 && code == 200 -> throw DownloadException(
                    DownloadFailure.ResumeNotSupported
                )
                else -> throw DownloadException(DownloadFailure.ServerError(code))
            }

            Log.i(TAG, "${f.name}: tai tu byte $have / ${f.sizeBytes}")

            part.parentFile?.mkdirs()
            java.io.FileOutputStream(part, /* append = */ have > 0).use { out ->
                conn.inputStream.use { ins ->
                    val buf = ByteArray(BUF)
                    var sinceReport = 0L
                    emit(DownloadProgress(f.name, index, count, have, f.sizeBytes))
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ins.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        have += n
                        sinceReport += n
                        if (sinceReport >= REPORT_EVERY) {
                            sinceReport = 0
                            emit(DownloadProgress(f.name, index, count, have, f.sizeBytes))
                        }
                    }
                    out.fd.sync()
                }
            }
        } catch (e: IOException) {
            // Dut mang giua chung: GIU nguyen phan da tai. Lan sau tiep tu day.
            throw DownloadException(DownloadFailure.NoNetwork, e)
        } finally {
            conn.disconnect()
        }

        // Chi doi ten khi VA CHI KHI checksum dung. Nho vay khong bao gio ton tai
        // mot file "xong" ma hong — `find()` tra ve no la coi nhu dung duoc.
        val got = sha256(part)
        if (got != f.sha256) {
            Log.e(TAG, "${f.name}: checksum sai")
            part.delete()
            throw DownloadException(DownloadFailure.ChecksumMismatch(f.name))
        }
        val done = File(store.modelsDir, f.name)
        if (!part.renameTo(done)) {
            part.copyTo(done, overwrite = true); part.delete()
        }
        emit(DownloadProgress(f.name, index, count, f.sizeBytes, f.sizeBytes))
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
