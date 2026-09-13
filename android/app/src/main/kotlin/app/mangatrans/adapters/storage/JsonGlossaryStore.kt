package app.mangatrans.adapters.storage

import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.GlossaryKind
import app.mangatrans.ports.GlossaryStatus
import app.mangatrans.ports.GlossaryStore
import app.mangatrans.ports.isUsableSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * AD-7 — MOI thay doi glossary di qua day. Khong ai duoc ghi thang xuong file.
 *
 * Dung JSON thay vi Room: AD-19 chot MVP chi co MOT glossary toan cuc,
 * vai chuc muc. Room la thua.
 *
 * Khoa: (seriesKey, surface). AD-19 co dinh seriesKey = "default", nhung giu
 * cot do trong schema de sau nay tach duoc ma khong phai migrate.
 */
class JsonGlossaryStore(private val file: File) : GlossaryStore {

    private val lock = Mutex()

    override suspend fun confirmed(seriesKey: String): List<GlossaryEntry> =
        all().filter { it.seriesKey == seriesKey && it.status == GlossaryStatus.Confirmed }

    override suspend fun proposed(seriesKey: String): List<GlossaryEntry> =
        all().filter { it.seriesKey == seriesKey && it.status == GlossaryStatus.Proposed }

    /**
     * AD-7 — muc tu tich luy vao trang thai Proposed va KHONG duoc dua vao prompt
     * cho toi khi nguoi dung xac nhan. Muc nguoi dung nhap luon thang muc tu de xuat.
     */
    override suspend fun propose(entry: GlossaryEntry) = mutate { list ->
        val exists = list.any { it.seriesKey == entry.seriesKey && it.surface == entry.surface }
        if (exists) list else list + entry.copy(status = GlossaryStatus.Proposed)
    }

    override suspend fun confirm(seriesKey: String, surface: String) = mutate { list ->
        list.map {
            if (it.seriesKey == seriesKey && it.surface == surface)
                it.copy(status = GlossaryStatus.Confirmed) else it
        }
    }

    override suspend fun upsertByUser(entry: GlossaryEntry) = mutate { list ->
        // Nguoi dung nhap tay luon thang.
        list.filterNot { it.seriesKey == entry.seriesKey && it.surface == entry.surface } +
            entry.copy(status = GlossaryStatus.Confirmed)
    }

    override suspend fun delete(seriesKey: String, surface: String) = mutate { list ->
        list.filterNot { it.seriesKey == seriesKey && it.surface == surface }
    }

    private suspend fun all(): List<GlossaryEntry> = withContext(Dispatchers.IO) {
        lock.withLock { read() }
    }

    private suspend fun mutate(f: (List<GlossaryEntry>) -> List<GlossaryEntry>) =
        withContext(Dispatchers.IO) {
            lock.withLock { write(f(read())) }
        }

    private fun read(): List<GlossaryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(GlossaryEntry(
                        seriesKey = o.optString("seriesKey", "default"),
                        surface = o.getString("surface"),
                        meaning = o.getString("meaning"),
                        kind = runCatching { GlossaryKind.valueOf(o.getString("kind")) }
                            .getOrDefault(GlossaryKind.ProperNoun),
                        status = runCatching { GlossaryStatus.valueOf(o.getString("status")) }
                            .getOrDefault(GlossaryStatus.Proposed),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<GlossaryEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("seriesKey", it.seriesKey); put("surface", it.surface)
                put("meaning", it.meaning); put("kind", it.kind.name); put("status", it.status.name)
            })
        }
        file.parentFile?.mkdirs()
        file.writeText(arr.toString(1))
    }
}

/**
 * AD-8 — tu tich luy KHONG dung model rieng.
 *
 * Khong co giai phap NER tieng Nhat nao chay nhe duoc on-device (da tra web),
 * nen chi dung hai nguon DA CO SAN, chi phi bang 0:
 *   (a) truong `speaker` ma LLM von da tra ve
 *   (b) cum katakana lap lai qua nhieu trang
 */
object GlossaryMiner {

    /** Katakana lien tiep >= 2 ky tu — tin hieu manh cua ten rieng trong tieng Nhat. */
    private val KATAKANA = Regex("[\\u30A0-\\u30FF]{2,}")

    /** Xuat hien tu bao nhieu trang tro len thi moi de xuat. */
    const val MIN_PAGES = 3

    /**
     * @param speakersPerPage moi phan tu la tap `speaker` LLM tra ve cho mot trang
     * @param jaPerPage       moi phan tu la tap nguyen ban tieng Nhat cua mot trang
     */
    fun candidates(
        speakersPerPage: List<Set<String>>,
        jaPerPage: List<String>,
        minPages: Int = MIN_PAGES,
    ): List<Pair<String, GlossaryKind>> {
        val out = mutableListOf<Pair<String, GlossaryKind>>()

        // (a) speaker lap lai — nguon tin cay nhat, LLM da suy ra giup.
        speakersPerPage.flatMap { it }
            .filter { isUsableSurface(it) }
            .groupingBy { it }.eachCount()
            .filterValues { it >= minPages }
            .keys.forEach { out += it to GlossaryKind.ProperNoun }

        // (b) cum katakana lap lai qua nhieu trang.
        val perPage = jaPerPage.map { page -> KATAKANA.findAll(page).map { it.value }.toSet() }
        perPage.flatMap { it }
            .groupingBy { it }.eachCount()
            .filterValues { it >= minPages }
            .keys
            .filterNot { k -> out.any { it.first == k } }
            .forEach { out += it to GlossaryKind.ProperNoun }

        return out
    }
}
