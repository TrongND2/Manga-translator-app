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
import com.google.ai.edge.litertlm.ConversationConfig
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

        /** Tran do dai bai lam. Xem cho goi `createConversation`. */
        const val MAX_OUTPUT_TOKENS = 2048
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
                e.createConversation(
                    ConversationConfig(
                        // ⚠️ Khong dat thi lay mac dinh cua thu vien, va mac
                        // dinh do CAT NGANG bai lam. Do tren may: mot trang 18
                        // bong, mo hinh dung o **dung 10 bong** — hai luot lien
                        // tiep deu dung 10, khong phai ngau nhien.
                        //
                        // Moi bong trong JSON tra ve ton khoang 35-45 token, nen
                        // 10 bong ~ 400 token. Con so tron nhu the la dau hieu
                        // cua mot tran, khong phai mo hinh "het y" (F58).
                        //
                        // 2048 du cho mot trang rat day chu; trang thuong chi
                        // dung het mot phan nho, va phan khong dung khong ton gi.
                        maxOutputToken = MAX_OUTPUT_TOKENS,
                    )
                ).use { conv ->
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

private const val SYSTEM = """Dịch thoại manga Nhật → Việt: sát nghĩa, đúng ngữ pháp, đọc lên như người Việt nói. Trả về DUY NHẤT một khối JSON.

XƯNG HÔ (quan trọng nhất):
Suy quan hệ người nói–người nghe từ ngữ cảnh CẢ TRANG, chọn xưng hô từng cặp, giữ nhất quán. ĐỪNG mặc định "mày/tao".
lịch sự/xa lạ → tôi-anh, tôi-ông, em-anh · thân → tớ-cậu · suồng sã → tao-mày · cổ trang bề trên → ta-ngươi
Ai dùng 小生 / ござる / ませぬ là giọng cổ trang khiêm nhường → "tiểu sinh", "tại hạ"; đã chọn giọng nào thì mọi câu của người đó giữ giọng đó.

GIỮ NHỊP NÓI:
Nói lắp (có dấu phẩy ngay sau âm đầu) → lặp phụ âm đầu của CHÍNH từ tiếng Việt mình vừa chọn rồi thêm gạch nối. Dấu 「…」 là câu bỏ lửng: giữ "..." và ĐỪNG viết nốt. Bubble chỉ có mẩu câu cụt thì dịch đúng mẩu đó, ĐỪNG bịa thêm.
Tiếng thở, tiếng rên (はぁ・ふぅ・んっ・あぁ) thì phiên âm ra tiếng thở, ĐỪNG dịch nghĩa — 「はぁ」 là thở dốc chứ không phải "Hả?". Giữ nguyên ♡ ♪ ★ ở đúng chỗ chúng đứng.

AI LÀM CHO AI:
「〜て貰う」「〜てくれる」 là NGƯỜI KIA làm cho mình; 「〜てあげる」 là mình làm cho người kia. Đừng đảo ngược chiều.

ĐÚNG MỨC ĐỘ:
Nguyên bản thô tục tới đâu thì dịch thô tới đó — không nói tránh, không bỏ bớt. Nguyên bản nhã thì dịch nhã.

DÙNG GLOSSARY ĐÚNG CHỖ:
Chỉ thay một mục glossary khi bubble chứa ĐÚNG chuỗi chữ Nhật của mục đó. Giống âm gần gần thì KHÔNG phải — 「りゅ」 trong 「溢れちゃってりゅ」 là cách nói nhịu của 「る」, không phải tên người. Tuyệt đối đừng chèn tên nhân vật vào bubble không hề có tên đó.

Không để sót chữ Nhật nào. Tên riêng thì phiên âm theo âm Nhật; danh từ thường thì dịch nghĩa. Bỏ -san/-kun/-chan, chuyển sắc thái vào xưng hô. Tra glossary trước khi đoán thành ngữ. Ngắn gọn cho vừa bóng thoại, không chú thích. Viết theo trật tự tiếng Việt, đừng bám trật tự tiếng Nhật."""
