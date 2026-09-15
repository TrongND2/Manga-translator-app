package app.mangatrans.adapters.litertlm

import android.util.Log
import app.mangatrans.domain.PageJob
import app.mangatrans.pipeline.PipelineConfig
import app.mangatrans.ports.BubbleTranslation
import app.mangatrans.ports.GlossaryEntry
import app.mangatrans.ports.GlossaryKind
import app.mangatrans.ports.Translator
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Buoc 4 — NOI DUY NHAT trong ca app duoc import LiteRT-LM (AD-2).
 *
 * Cac quyet dinh da do duoc, khong phai chon bua:
 *   AD-2  : CPU la duong duy nhat. GPU khoi tao duoc va nhanh gap 4 lan nhung
 *           SINH RA RAC tren Adreno 642L (F18). Van giu duong GPU de may khac
 *           dung duoc, nhung mac dinh la CPU.
 *   AD-25 : 2 luong CPU. Doi 35% toc do lay 16 do C (F23).
 *   AD-24 : nha engine khi ranh. 3200 MB -> 84 MB.
 *   AD-6  : thu tu truong JSON BAT BUOC la id -> jaEcho -> vi.
 */
class LiteRtLmTranslator(
    private val modelPath: String,
    private val cfg: PipelineConfig = PipelineConfig(),
    private val useGpu: Boolean = false,
    /**
     * Thu muc LiteRT-LM duoc phep dung lam bo nho dem tren dia.
     *
     * Do tren M52 luc dang dich: `Native Heap` 1.854 MB **ban** (chi nen vao
     * swap duoc, khong vut di duoc) con `Other mmap` 944 MB **sach**. Tuc thu
     * vien chep phan lon trong so vao heap chu khong anh xa tu file — va chinh
     * 1,85 GB ban do lam Android chon giet app (F41).
     *
     * `null` = khong dat, de thu vien tu quyet.
     */
    private val cacheDir: String? = null,
) : Translator {

    private companion object {
        const val TAG = "LiteRtLmTranslator"
    }

    private val lock = Mutex()
    private var engine: Engine? = null

    override val isWarm: Boolean get() = engine != null

    override suspend fun warmUp(): Unit = withContext(Dispatchers.Default) {
        lock.withLock {
            if (engine != null) return@withLock
            val backend = if (useGpu) Backend.GPU() else Backend.CPU(cfg.cpuThreads, null)
            val t0 = System.currentTimeMillis()
            val e = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = cacheDir,
                )
            )
            e.initialize()
            engine = e
            // Da do tren M52: 15-29 s. Google canh bao "toi 10 giay" — thuc te gap 3x.
            Log.i(TAG, "engine san sang sau ${System.currentTimeMillis() - t0} ms")
        }
    }

    override suspend fun release(): Unit = withContext(Dispatchers.Default) {
        lock.withLock {
            engine?.let { runCatching { it.close() } }
            engine = null
            Log.i(TAG, "da nha engine")
        }
    }

    /**
     * AD-17 — phat TUNG bubble ngay khi no hoan chinh trong dong tra ve.
     *
     * KHONG dung `sendMessage()` (chan): no doi ca trang sinh xong moi tra ve,
     * lam bubble dau tien ve sau 169 giay tren M52 — vo NFR-005 (<= 8s), va
     * NFR-005 moi la chi so nguoi dung cam nhan (PRD 8.3).
     *
     * `sendMessageAsync` nhan `MessageCallback`, goi lai nhieu lan khi token
     * chay ve. Ghep voi StreamingJsonParser de doc tung bubble ngay khi du.
     */
    override fun translate(page: PageJob, glossary: List<GlossaryEntry>): Flow<BubbleTranslation> =
        channelFlow {
            warmUp()
            val e = engine ?: error("engine chua san sang")
            val parser = StreamingJsonParser()

            lock.withLock {
                e.createConversation().use { conv ->
                    val done = CompletableDeferred<Unit>()
                    var seen = 0
                    val prompt = buildPrompt(page, glossary)
                    val tStart = System.currentTimeMillis()
                    var tFirstToken = 0L
                    // Do de biet prefill chiem bao nhieu trong thoi gian toi
                    // bubble dau tien (NFR-005). Prompt dai => prefill lau =>
                    // khong the co bubble nao truoc khi prefill xong (AD-3).
                    Log.i(TAG, "prompt ${prompt.length} ky tu | ${page.translatable.size} bubble | ${glossary.size} muc glossary")

                    conv.sendMessageAsync(
                        prompt,
                        object : MessageCallback {
                            override fun onMessage(message: Message) {
                                if (tFirstToken == 0L) {
                                    tFirstToken = System.currentTimeMillis()
                                    Log.i(TAG, "token dau tien sau ${tFirstToken - tStart} ms (= prefill)")
                                }
                                // Message mang phan van ban moi nhat.
                                parser.feed(message.contents.toString()).forEach {
                                    seen++
                                    if (seen == 1) Log.i(TAG,
                                        "bubble dau tien sau ${System.currentTimeMillis() - tStart} ms")
                                    trySend(it)
                                }
                            }

                            override fun onDone() {
                                parser.drain().forEach { trySend(it) }
                                if (seen == 0) {
                                    Log.w(TAG, "khong doc duoc bubble nao; raw=" +
                                        parser.raw.take(200))
                                }
                                done.complete(Unit)
                            }

                            override fun onError(t: Throwable) {
                                Log.e(TAG, "loi khi sinh", t)
                                done.complete(Unit)
                            }
                        },
                    )
                    done.await()
                }
            }
        }.flowOn(Dispatchers.Default)

    /**
     * Prompt v1 — ban da do o Phase 0 (69% tren bo truyen kho nhat).
     *
     * KHONG dung prompt v2 (co buoc `literal`): da do (F9) no lam qwen3 nham ngoi
     * va lam gemma3 bi cat cut JSON, trong khi loi ich bang 0.
     */
    private fun buildPrompt(page: PageJob, glossary: List<GlossaryEntry>): String {
        val gl = if (glossary.isEmpty()) "- (trống)" else glossary.joinToString("\n") {
            val tag = when (it.kind) {
                GlossaryKind.ProperNoun -> "tên riêng"
                GlossaryKind.Idiom -> "thành ngữ"
                GlossaryKind.Address -> "xưng hô"
            }
            "- ${it.surface} [$tag]: ${it.meaning}"
        }
        val bubbles = page.translatable.joinToString("\n") { "[${it.id}] ${it.ja}" }
        val n = page.translatable.size

        return """$SYSTEM

## Nhân vật và thuật ngữ (glossary)
$gl

## Bong bóng thoại
Đã sắp theo thứ tự đọc manga (phải→trái, trên→dưới). Bubble liền nhau thường là một mạch hội thoại.

$bubbles

## Yêu cầu
Dịch đủ $n bubble. Trả về JSON, các trường theo ĐÚNG thứ tự này:
{"bubbles":[{"id":<số>,"jaEcho":"<2 ký tự đầu của nguyên bản>","vi":"<bản dịch>","speaker":"<tên hoặc ?>"}]}

`jaEcho` là mã đối chiếu: chép y nguyên 2 ký tự ĐẦU TIÊN của nguyên bản tiếng Nhật của chính bubble đó. Không dịch, không sửa."""
    }

    /**
     * Doc JSON. Model hay boc trong ```json ... ``` nen phai cat truoc.
     * Khong nem exception khi hong — tra danh sach rong de TranslateFilter
     * xu ly theo AD-9 (tro ve nguyen ban).
     */
    private fun parse(raw: String): List<BubbleTranslation> {
        val body = Regex("\\{[\\s\\S]*\\}").find(raw)?.value ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).getJSONArray("bubbles")
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(BubbleTranslation(
                        id = o.getInt("id"),
                        jaEcho = o.optString("jaEcho", ""),
                        vi = o.optString("vi", "").trim(),
                        speaker = o.optString("speaker", "").takeIf { it.isNotBlank() && it != "?" },
                    ))
                }
            }
        }.getOrElse {
            Log.w(TAG, "JSON hong: ${it.message}")
            emptyList()
        }
    }
}

private const val SYSTEM = """Dịch thoại manga Nhật sang tiếng Việt: SÁT NGHĨA, ĐÚNG NGỮ PHÁP, đọc lên nghe như người Việt nói.

DỊCH ĐÚNG NHƯ NGUYÊN BẢN, KHÔNG LÀM NHẸ ĐI:
Truyện người lớn thì thoại tục, thô, trần trụi — dịch đúng mức độ đó. Không nói tránh, không thay bằng từ lịch sự hơn, không bỏ bớt. Nguyên bản thô tới đâu thì bản dịch thô tới đó; nguyên bản nhã thì bản dịch nhã. Sai mức độ cũng là dịch sai.

XƯNG HÔ — quan trọng nhất:
Suy ra quan hệ giữa người nói và người nghe từ ngữ cảnh CẢ TRANG, rồi chọn xưng hô cho đúng từng cặp. ĐỪNG mặc định "mày/tao".
- lịch sự, xa lạ, kính trọng → tôi/anh · tôi/ông · em/anh
- thân mật, bạn bè → tớ/cậu · mình/bạn
- suồng sã, đùa cợt, thân lâu năm → tao/mày
- bề trên nói với bề dưới → ta/ngươi (truyện cổ trang)
Cùng một trang có thể có nhiều cặp xưng hô khác nhau. Giữ nhất quán cho từng cặp.

DỊCH HẾT:
KHÔNG được để sót bất kỳ chữ Nhật nào trong bản dịch — kể cả tên riêng, chữ Hán lẻ, hay từ tượng thanh. Tên riêng thì phiên âm La-tinh (瑠璃丸 → Rurimaru). Tượng thanh thì dịch sang tượng thanh tiếng Việt.

GIỮ NGUYÊN NHỊP NÓI — đo trên máy thấy đây là chỗ sai nhiều nhất:
- Nói lắp thì dịch ra nói lắp: 「そ、それは」 → "C-cái đó là", 「な、なんで」 → "S-sao lại". ĐỪNG làm câu phẳng lại.
- Dấu 「…」 nghĩa là câu BỎ LỬNG: giữ "..." ở đúng chỗ đó, ĐỪNG viết nốt phần người ta chưa nói.
- Bubble chỉ có một mẩu câu (ví dụ 「ませぬぅ!」 — không có động từ) thì dịch đúng mẩu đó, ĐỪNG bịa ra cả câu.

XƯNG HÔ PHẢI NHẤT QUÁN VỚI GIỌNG:
Nhân vật dùng 小生 / ござる / ませぬ là giọng cổ trang, khiêm nhường → "tiểu sinh", "tại hạ", "kẻ hèn này". Đã chọn giọng đó cho một người thì mọi bubble của người đó phải cùng giọng — không được lúc "tiểu sinh" lúc "em".

VIẾT NHƯ NGƯỜI VIỆT NÓI, ĐỪNG BÁM TRẬT TỰ CHỮ NHẬT:
Dịch xong đọc lại một lượt: câu đó người Việt có nói thế không? Không thì viết lại cho thuận.
- 帰りの駅で → "ở ga trên đường về", KHÔNG phải "ở nhà ga về"
- Bổ ngữ nơi chốn/thời gian đứng đâu cho xuôi tiếng Việt thì để đó, đừng giữ nguyên chỗ của tiếng Nhật.

Còn lại:
- Tra glossary trước khi đoán nghĩa thành ngữ.
- Bỏ hậu tố -san/-kun/-chan, chuyển sắc thái vào xưng hô.
- Danh từ thường thì DỊCH, chỉ tên riêng mới phiên âm: 赤ちゃん → "em bé", KHÔNG phải "aka-chan".
- Ngắn gọn cho vừa bong bóng. Không thêm chú thích.

Trả về DUY NHẤT một khối JSON."""
