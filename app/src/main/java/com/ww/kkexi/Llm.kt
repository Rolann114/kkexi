package com.ww.kkexi

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * AI 客户端：多服务商 + 流式输出 + 思考过程 + 工具调用。
 *
 * ## 三条不可违反的规矩
 * 1. **不引入 HTTP 库**：用 JDK 自带的 [HttpURLConnection]（AGENTS.md 硬约束 7）。
 * 2. **只发往用户自己配置的地址**：key 不经任何中转，不做遥测。
 * 3. **失败必须降级**：任何错误都通过回调如实上报，绝不让 AI 故障影响日程主功能。
 *
 * ## 能力来源（全部为 2026-10-14 用本机 key 实测，不是凭文档猜的）
 * | 能力 | 结论 | 证据 |
 * |---|---|---|
 * | 流式 | 支持 | 收到 35 个 SSE 块，以 `data: [DONE]` 收尾 |
 * | 思考过程 | 支持 | `choices[].delta.reasoning_content` 有内容 |
 * | 思考强度 | 支持且**真生效** | 合法值 none/minimal/low/medium/high/xhigh/ultra/max；实测 none→0、low→385、medium→732 reasoning_tokens |
 * | 工具调用 | 支持 | `finish_reason=tool_calls`，正确发起 `cancel_occurrence` |
 * | 读图 | 支持但不可靠 | 程序生成的色块图全对；**真实课表 20 条错 2 条（串列）**；给空图会编造 |
 *
 * ## 关于流式与工具调用的关系
 * 流式下 `tool_calls` 也是分片到达的（`delta.tool_calls[i].function.arguments` 逐段拼接），
 * 所以这里按 index 累积；收完一轮再决定是「输出答案」还是「执行工具」。
 */
class LlmClient(
    private val config: AiConfigStore,
    /** 兼容旧字段：AI 总开关从 Settings 读。 */
    private val settings: Settings,
    private val toolExecutor: ToolExecutor? = null,
) {

    /** 一次工具调用的结果。 */
    data class ToolResult(val ok: Boolean, val message: String)

    /** 工具执行器。由 ViewModel 注入，内部直接操作 Repository。 */
    fun interface ToolExecutor {
        fun execute(name: String, arguments: JSONObject): ToolResult
    }

    /**
     * 流式回调。
     *
     * [onReasoning] 是「思考过程」——有些模型不给这个字段，那就不回调。
     * [onDelta] 是正文增量。两者都必须能在主线程外被调用（实现方自己切线程）。
     * [onUsage] 在一轮请求结束后回调这一次的 token 用量（用户反馈要能看到消耗）。
     */
    interface StreamSink {
        fun onReasoning(text: String)
        fun onDelta(text: String)
        fun onToolStarted(name: String)
        fun onUsage(promptTokens: Int, completionTokens: Int, reasoningTokens: Int) {}
    }

    /** 一次请求的 token 用量。 */
    data class Usage(val promptTokens: Int, val completionTokens: Int, val reasoningTokens: Int) {
        val total: Int get() = promptTokens + completionTokens

        fun plus(other: Usage) = Usage(
            promptTokens + other.promptTokens,
            completionTokens + other.completionTokens,
            reasoningTokens + other.reasoningTokens,
        )

        companion object {
            val ZERO = Usage(0, 0, 0)
        }
    }

    /**
     * 解析出来的一条候选行程。
     *
     * 真正的定义搬到了 `CandidateParser.kt`（解析逻辑要能脱离网络层单独测），
     * 这里留一个别名，`LlmClient.Candidate` 这种旧写法继续可用。
     */
    typealias Candidate = com.ww.kkexi.Candidate

    private fun activeProvider(): AiProvider? = config.active()

    fun available(): Boolean =
        settings.aiEnabled && (activeProvider()?.configured == true)

    /** 有哪些能力可用（界面据此禁用按钮并说明原因）。 */
    fun visionAvailable(): Boolean = available() && config.visionCapable() != null

    // ================================================================= 文本 → 行程

    /**
     * 把一段自然语言拆成候选行程。
     *
     * [today] 与 [timetable] 由调用方注入而不是内部取，这样单测可以固定时间、结果可复现。
     */
    fun parseSchedules(
        text: String,
        today: LocalDate,
        timetable: Timetable = Timetable.DEFAULT,
    ): Result<List<Candidate>> {
        val provider = activeProvider()
            ?: return Result.failure(IllegalStateException("AI 未启用，或没有配置好的服务商"))
        if (!settings.aiEnabled) return Result.failure(IllegalStateException("AI 开关是关的"))

        val system = """
            你是日程抽取器。把用户给的文本拆成一条条日程，只输出 JSON，不要任何解释文字。
            输出格式：{"items":[{"title":"","kind":"COURSE|ACTIVITY|DEADLINE|OFFICE_HOUR",
            "date":"YYYY-MM-DD","start":"HH:mm","end":"HH:mm","point":false,
            "weeks":"5-16","parity":"EVERY|ODD|EVEN","location":"","note":"","evidence":""}]}
            规则：
            - 今天是 $today（${DateTimeExt.weekName(today)}），当前处于${DateTimeExt.weekLabel(today)}。
            - date 必须落在 $today 到 ${today.plusDays(90)} 之间；不确定就整条不要输出。
            - **时间一律写 start/end 的 HH:mm**（例如 "14:30"），**绝对不要在任何字段里写「第几节」**。
              原文只给节次时（例如「第 5-6 节」），按下面这张对照表折算成时刻；
              折算之后也不要写「由第 N 节折算」——用户要求界面上完全不出现节次：
              ${timetable.describe()}
            - **课表类材料里的课程必须一次给出整学期的周次**：kind=COURSE，
              weeks 写教学周范围（例如 "5-16"、"1-16"），不连续就写 "5,7,9"，
              单双周再给 parity（单周 ODD、双周 EVEN，每周 EVERY）。
              **不要**把一门课拆成「第 5 周一节、第 6 周一节……」这样的一周一条。
            - 原文只给了开始时刻、没给结束时刻时：普通日程按合理时长估一个（一般 60 分钟）；
              **截止型（DEADLINE）只给一个时刻的**（如「23:59 前交」「18:00 截止」）只写 start，
              并设 "point":true，不要编造 end——用户明确要「一个具体时间点」，不是一段。
            - **note 就是「描述」字段，要尽量填满**：原文里关于这件事的补充信息
              （谁组织的、在哪集合、要带什么、面向谁、注意事项、教师、周次）都写进 note；
              但**不要写「第几节」或「由第 N 节折算」**。
              周次请同时写进结构化字段 weeks/parity，不要只塞进描述。
            - evidence 必须是原文里能支撑这条日程的**原话片段**，不许改写、不许编造。
            - 原文没写的信息一律留空字符串，**绝对不要猜测**（地点、教师都不要补），
              但「原文里写了、只是不适合放标题」的信息要写进 note。
            - kind 必须用英文枚举，**不要写中文**（写「活动」会被拒）。作业、报名这类
              "到点必须做完"的用 DEADLINE；课程用 COURSE；固定时段的答疑用 OFFICE_HOUR；其余用 ACTIVITY。
        """.trimIndent()

        val response = complete(provider, system, text)
        if (response.isFailure) {
            return Result.failure(response.exceptionOrNull() ?: IllegalStateException("未知错误"))
        }
        val content = response.getOrNull().orEmpty()
        return try {
            // 必须把**用户原文**传进去做核对，不能拿模型自己的输出当原文。
            Result.success(CandidateParser.parse(content, text, today, timetable))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    /**
     * 把模型通过 `propose_events` 工具给出的 JSON 数组转成候选。
     *
     * ## 为什么要传 [sourceText]
     * 旧版本这里传的是**这段 JSON 自己**当"原文"，于是「依据是否真在原文里」那道校验
     * 变成拿模型输出核对模型输出——**永远为真，等于没校**。
     * 现在由调用方传入用户真实发的那段内容（粘贴的文字、或读图时的空串），
     * 校验才有意义。
     */
    fun candidatesFromJson(
        itemsJson: String,
        sourceText: String,
        today: LocalDate,
        timetable: Timetable,
    ): List<Candidate> {
        val trimmed = itemsJson.trim()
        val objectForm = if (trimmed.startsWith("[")) """{"items":$trimmed}""" else trimmed
        return CandidateParser.parse(objectForm, sourceText, today, timetable)
    }


    // ================================================================= 智能体问答（流式 + 工具）

    /**
     * 带工具调用与流式输出的问答。
     *
     * ## 为什么要传 [history]（用户反馈：「我发现它没有上下文」）
     * 上一版每次请求只发**当前这一句**，模型看不到之前聊过什么，
     * 所以问「我上一个问的什么」它只能回答「我看不到之前的对话记录」。
     * 现在把会话历史按顺序拼进 messages，多轮对话才成立。
     *
     * [history] 里每条是 (是否用户说的, 文本)；思考过程不重复回灌
     * （那是模型的内部草稿，回灌只会浪费 token 并且可能干扰它）。
     *
     * 循环：把问题与日程上下文发给模型（流式收）→ 若它要求调用工具，就在本机执行、
     * 把结果回灌 → 直到它给出最终答复。
     */
    fun askStreaming(
        question: String,
        context: String,
        today: LocalDate,
        timetable: Timetable = Timetable.DEFAULT,
        history: List<Pair<Boolean, String>> = emptyList(),
        sink: StreamSink,
    ): Result<String> {
        val provider = activeProvider()
            ?: return Result.failure(IllegalStateException("AI 未启用，或没有配置好的服务商"))

        val system = """
            你是「课隙」App 里的日程助手。你既能回答关于日程的问题，也能**直接修改**用户的日程。

            今天的日期是 $today（${DateTimeExt.weekName(today)}，${DateTimeExt.weekLabel(today)}）。
            当前时间约 ${Timetable.format(Timetable.nowMinute())}。
            用户当前的作息表（只用于把原文的节次折算成时刻；**回答里不要出现「第几节」**）：
              ${timetable.describe()}
            用户说「今天 / 明天 / 后天 / 这周三」时，按上面的日期换算成绝对日期，不要反问今天是哪天。

            规则：
            - 回答只能依据下面提供的「日程数据」、**上面的对话历史**和工具返回的结果。
              对话历史里用户已经说过的信息（例如他刚发给你的图片内容、刚建过的日程），
              要当成已知事实来用，**不要**说「我看不到之前的对话」。
            - 数据里没有的明确说「数据里没有」。**不要编造**课程、时间、地点。
            - 不确定就先调用查询工具确认，不要猜。
            - 用户要求改动日程时，**直接调用相应工具**，不要只描述你打算怎么做。
            - **用户说「这一次 / 只改这次」时用 cancel_occurrence 或 move_occurrence**（它们只影响一天）；
              只有明确说「以后都这样 / 整条删掉」才用 delete_event 或改整条规则。
              不要为了「取消这一次」去反问用户要哪种——他说了「这一次」就是这一次。
            - 回答用中文，简短直接，先说结果。可以用 Markdown（表格、列表、粗体）。
            - **回答里一律用真实时刻（HH:mm），绝对不要写「第几节」**。用户已经三次投诉
              「第几节」这个概念，界面和回答里都不能出现；提到时间就直接说几点到几点，
              或者「14:30 截止」。创建重复课程时用 weeks 指定周次范围，不要只建一周。

            ——日程数据（已按日期排序）——
            $context
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", system))
            // 历史对话：只回灌纯文本，不回灌思考过程与工具细节。
            for ((fromUser, text) in history) {
                if (text.isBlank()) continue
                put(
                    JSONObject()
                        .put("role", if (fromUser) "user" else "assistant")
                        .put("content", text)
                )
            }
            put(JSONObject().put("role", "user").put("content", question))
        }

        var lastToolSummary = ""
        var lastError: Throwable? = null
        // 一轮问答里可能因为工具调用来回多次，token 要累加（用户要看到这条消息总共花了多少）。
        var totalUsage = Usage.ZERO

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val roundResult = streamOnce(provider, messages, wantTools = true, sink = sink)
            if (roundResult.isFailure) {
                lastError = roundResult.exceptionOrNull()
                break
            }
            val turn = roundResult.getOrNull()!!
            totalUsage = totalUsage.plus(turn.usage)

            if (turn.toolCalls.isEmpty()) {
                // 没有工具调用 → 这一轮的流式正文就是最终答复。
                sink.onUsage(totalUsage.promptTokens, totalUsage.completionTokens, totalUsage.reasoningTokens)
                return Result.success(turn.content.trim())
            }

            // 有工具调用：把 assistant 这轮原样回灌，再逐条执行并回结果。
            messages.put(turn.assistantMessage)
            for (call in turn.toolCalls) {
                sink.onToolStarted(call.name)
                val result = toolExecutor?.execute(call.name, call.arguments)
                    ?: ToolResult(false, "本机没有接上工具执行器，无法改动日程")
                lastToolSummary = result.message
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.id)
                        .put("content", if (result.ok) result.message else "失败：${result.message}")
                )
            }
        }

        if (lastToolSummary.isNotEmpty()) return Result.success("已执行：$lastToolSummary")
        return Result.failure(lastError ?: IllegalStateException("模型在 $MAX_TOOL_ROUNDS 轮内没有给出结果"))
    }

    // ================================================================= 读图 → 行程

    /**
     * 从**本地图片文件**解析日程（聊天页发图片走这条）。
     *
     * 与 [parseScheduleImage] 同一套逻辑，只是入参从字节变成路径——
     * 聊天里的图片会先被拷进 App 私有目录，这样会话历史里的缩略图才长期可用。
     */
    fun parseScheduleImageFile(
        imagePath: String,
        today: LocalDate,
        timetable: Timetable = Timetable.DEFAULT,
    ): Result<List<Candidate>> {
        val bytes = try {
            java.io.File(imagePath).readBytes()
        } catch (error: Exception) {
            return Result.failure(error)
        }
        if (bytes.isEmpty()) return Result.failure(IllegalStateException("图片文件是空的"))
        return parseScheduleImage(bytes, "image/jpeg", today, timetable)
    }

    /**
     * 从图片里解析日程。
     *
     * 实测口径（见 [parseImageWithAi] 的说明与 `_tools/probe-vision*.py`）：
     * 模型能读图，但会**串列**，且给它看不见的图时会**编造**。
     * 所以这里只负责「读」，是否入库由调用方与用户决定，本方法绝不写库。
     */
    fun parseScheduleImage(
        imageBytes: ByteArray,
        mimeType: String,
        today: LocalDate,
        timetable: Timetable = Timetable.DEFAULT,
    ): Result<List<Candidate>> {
        val provider = config.visionCapable()
            ?: return Result.failure(IllegalStateException("没有勾选「支持图片」的服务商"))
        if (!settings.aiEnabled) return Result.failure(IllegalStateException("AI 开关是关的"))

        val base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
        val system = """
            你是课表/日程抽取器。用户给你一张图片（可能是课程表截图、作息时间表、微信群通知）。
            请把图里的日程读出来，只输出 JSON，不要任何解释文字。
            输出格式：{"items":[{"title":"","kind":"COURSE|ACTIVITY|DEADLINE|OFFICE_HOUR",
            "date":"YYYY-MM-DD","start":"HH:mm","end":"HH:mm","point":false,
            "weeks":"5-16","parity":"EVERY|ODD|EVEN","location":"","note":"","evidence":""}]}
            规则：
            - 今天是 $today（${DateTimeExt.weekName(today)}），当前处于${DateTimeExt.weekLabel(today)}。
            - **时间一律写 start/end 的 HH:mm**（例如 "14:30"），**绝对不要在任何字段里写「第几节」**。
              图片里如果只有节次（例如「第 5-6 节」），请按下面这张对照表折算成时刻；
              折算之后也不要写「由第 N 节折算」——用户要求界面上完全不出现节次：
              ${timetable.describe()}
            - **课程表必须一次读成整学期课程**：kind=COURSE 的条目要给出 weeks（教学周范围，
              例如 "5-16"；单双周再给 parity=ODD/EVEN）。图里写着「周次：5-16 周」时，
              必须把它变成 weeks 字段，**不要**把同一门课拆成一周一条、也不要只给一个日期就完事。
            - 截止型（DEADLINE）如果图里只给了一个时刻（例如「23:59 前交」），只写 start 并设 point=true，
              不要编造 end。
            - 原文只给了开始时刻、没给结束时刻时，普通日程按合理时长估一个（一般 60 分钟），不要留空。
            - **逐列核对星期**：课程表是二维网格，很容易把某一列的课归到相邻的星期。
              每读一条，都要重新确认它属于哪一列（星期）。
            - **看不清就写 "看不清"，绝对不要推测、不要补全**。宁可漏一条，也不要编一条。
            - 如果整张图你无法辨认，输出 {"items":[]}。
            - note 是**描述**字段：图里关于这件事的补充信息（谁组织的、在哪集合、
              要带什么、注意事项、教师）都要写进去，不要偷懒留空；**但不要写「第几节」**。
            - evidence 填你从图里读到的原始文字片段；图里没有的文字不要写。
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", provider.model)
            put("temperature", provider.temperature)
            if (provider.reasoningEffort.isNotEmpty()) put("reasoning_effort", provider.reasoningEffort)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().apply {
                            put(
                                JSONObject().put("type", "text")
                                    .put("text", "请读出这张图里的日程。看不清的字段写「看不清」。")
                            )
                            put(
                                JSONObject().put("type", "image_url")
                                    .put(
                                        "image_url",
                                        JSONObject().put("url", "data:$mimeType;base64,$base64")
                                    )
                            )
                        }
                    )
                )
            })
        }

        val response = postJson(provider, "/chat/completions", body)
        if (response.isFailure) {
            return Result.failure(response.exceptionOrNull() ?: IllegalStateException("未知错误"))
        }
        val content = try {
            JSONObject(response.getOrNull().orEmpty())
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.textOrNull("content")
        } catch (_: Exception) {
            null
        }
        if (content.isNullOrEmpty()) {
            return Result.failure(IllegalStateException("响应里没有 choices[0].message.content"))
        }
        // 图片没有可比对的原文，所以 originalText 传空串 → 每条都会带上
        // 「依据无法核对，请对着截图确认」的提示。这是有意为之。
        return try {
            // 读图模式：没有可比对的原文，所以传空串——校验会如实标注「无法机器核对」。
            Result.success(CandidateParser.parse(content, originalText = "", today = today, timetable = timetable))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    // ================================================================= 连接自检

    /**
     * 连接自检：报出**用的哪个服务商、哪个模型、思考强度是否被接受**。
     *
     * 用户反馈第 6 条要「专业一点」——那就不能只有一个 key 输入框，
     * 得让用户当场知道「我这样配到底通不通、用的哪个模型」。
     */
    fun ping(): Result<String> {
        val provider = activeProvider()
            ?: return Result.failure(IllegalStateException("没有配置好的服务商（key 与模型名都要填）"))
        val body = JSONObject().apply {
            put("model", provider.model)
            put("temperature", provider.temperature)
            put("max_tokens", 16)
            if (provider.reasoningEffort.isNotEmpty()) put("reasoning_effort", provider.reasoningEffort)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "回复两个字：正常"))
            })
        }
        val response = postJson(provider, "/chat/completions", body)
        if (response.isFailure) {
            return Result.failure(response.exceptionOrNull() ?: IllegalStateException("未知错误"))
        }
        val root = try {
            JSONObject(response.getOrNull().orEmpty())
        } catch (error: Exception) {
            return Result.failure(error)
        }
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return Result.failure(IllegalStateException("响应里没有 choices[0].message"))
        val usage = root.optJSONObject("usage")
        val reasoningTokens = usage?.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens")
        val effortNote = if (provider.reasoningEffort.isEmpty()) {
            "未指定思考强度"
        } else {
            "思考强度 ${provider.reasoningEffort} 已被接受" +
                (reasoningTokens?.let { "（本次思考 $it tokens）" } ?: "")
        }
        return Result.success(
            "${provider.name} · ${provider.model} 连接正常；$effortNote；" +
                "它回复：${message.text("content").take(20)}"
        )
    }

    // ================================================================= HTTP 基础设施

    /** 一轮流式请求的结果。 */
    private data class Turn(
        val content: String,
        val assistantMessage: JSONObject,
        val toolCalls: List<ToolCall>,
        val usage: Usage,
    )

    private data class ToolCall(val id: String, val name: String, val arguments: JSONObject)

    /**
     * 发一轮流式请求，把增量喂给 [sink]，并在结束时给出这一轮的完整正文与工具调用。
     *
     * SSE 解析要点：DeepSeek 的每一行形如 `data: {...}`，结束标记是 `data: [DONE]`；
     * 非 data 行（心跳、空行）直接忽略。
     */
    private fun streamOnce(
        provider: AiProvider,
        messages: JSONArray,
        wantTools: Boolean,
        sink: StreamSink,
    ): Result<Turn> {
        val body = JSONObject().apply {
            put("model", provider.model)
            put("temperature", provider.temperature)
            put("stream", true)
            if (provider.reasoningEffort.isNotEmpty()) put("reasoning_effort", provider.reasoningEffort)
            put("messages", messages)
            if (wantTools) put("tools", buildTools())
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(provider, "/chat/completions").apply { doOutput = true }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errorText = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                return Result.failure(IllegalStateException("HTTP $code：${errorText.take(300)}"))
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            // token 用量：流式下只有最后一个 chunk 带 usage 字段。
            var usage = Usage.ZERO
            // 工具调用按 index 累积：流式下参数是分片来的。
            val toolAccumulator = LinkedHashMap<Int, Pair<String, StringBuilder>>()
            val toolNames = LinkedHashMap<Int, String>()
            val toolIds = LinkedHashMap<Int, String>()

            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = try {
                        JSONObject(payload)
                    } catch (_: Exception) {
                        continue
                    }
                    // 有些实现把 usage 放在每个 chunk 上，有些只在最后一个，取到就更新。
                    chunk.optJSONObject("usage")?.let { raw ->
                        usage = Usage(
                            promptTokens = raw.optInt("prompt_tokens"),
                            completionTokens = raw.optInt("completion_tokens"),
                            reasoningTokens = raw.optJSONObject("completion_tokens_details")
                                ?.optInt("reasoning_tokens") ?: 0,
                        )
                    }

                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?: continue

                    // 必须用 text()：值是 JSON null 时 optString 会返回字面量 "null"，
                    // 导致每个空 chunk 都往思考过程里追加一次 "null"（真机上见过一整屏）。
                    delta.textOrNull("reasoning_content")?.let {
                        reasoning.append(it)
                        sink.onReasoning(it)
                    }
                    delta.textOrNull("content")?.let {
                        content.append(it)
                        sink.onDelta(it)
                    }

                    val calls = delta.optJSONArray("tool_calls") ?: continue
                    for (index in 0 until calls.length()) {
                        val call = calls.optJSONObject(index) ?: continue
                        val slot = call.optInt("index", index)
                        call.textOrNull("id")?.let { toolIds[slot] = it }
                        val function = call.optJSONObject("function") ?: continue
                        function.textOrNull("name")?.let { toolNames[slot] = it }
                        val args = function.text("arguments")
                        if (args.isNotEmpty()) {
                            toolAccumulator.getOrPut(slot) { (toolNames[slot] ?: "") to StringBuilder() }
                                .second.append(args)
                        }
                    }
                }
            }

            val toolCalls = toolAccumulator.keys.sorted().mapNotNull { slot ->
                val name = toolNames[slot] ?: return@mapNotNull null
                val rawArgs = toolAccumulator[slot]?.second?.toString().orEmpty()
                val arguments = try {
                    JSONObject(rawArgs.ifEmpty { "{}" })
                } catch (_: Exception) {
                    JSONObject()
                }
                ToolCall(toolIds[slot] ?: "call_$slot", name, arguments)
            }

            // 回灌用的 assistant 消息要按 API 要求的形状重建。
            val assistantMessage = JSONObject().apply {
                put("role", "assistant")
                put("content", content.toString())
                if (toolCalls.isNotEmpty()) {
                    put("tool_calls", JSONArray().apply {
                        for (call in toolCalls) {
                            put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", call.name)
                                            .put("arguments", call.arguments.toString())
                                    )
                            )
                        }
                    })
                }
            }

            Result.success(Turn(content.toString(), assistantMessage, toolCalls, usage))
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }

    /** 非流式的一次完整请求，用于「解析文本」这种不需要逐字显示的场景。 */
    private fun complete(provider: AiProvider, system: String, user: String): Result<String> {
        val body = JSONObject().apply {
            put("model", provider.model)
            put("temperature", provider.temperature)
            if (provider.reasoningEffort.isNotEmpty()) put("reasoning_effort", provider.reasoningEffort)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }
        val response = postJson(provider, "/chat/completions", body)
        if (response.isFailure) {
            return Result.failure(response.exceptionOrNull() ?: IllegalStateException("未知错误"))
        }
        val content = try {
            JSONObject(response.getOrNull().orEmpty())
                .optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.textOrNull("content")
        } catch (_: Exception) {
            null
        }
        return if (content.isNullOrEmpty()) {
            Result.failure(IllegalStateException("响应里没有 choices[0].message.content"))
        } else {
            Result.success(content)
        }
    }

    private fun openConnection(provider: AiProvider, path: String): HttpURLConnection {
        val base = provider.baseUrl.trimEnd('/')
        val url = URL(base + path)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }
    }

    private fun postJson(provider: AiProvider, path: String, body: JSONObject): Result<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = openConnection(provider, path).apply { doOutput = true }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                Result.failure(IllegalStateException("HTTP $code：${text.take(300)}"))
            } else {
                Result.success(text)
            }
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            connection?.disconnect()
        }
    }

    // ================================================================= 工具定义

    /** 暴露给模型的工具清单（OpenAI function calling 格式，DeepSeek 兼容）。 */
    private fun buildTools(): JSONArray = JSONArray().apply {
        put(tool("query_schedule", "按日期区间查询日程。回答任何关于「有没有课/什么事」的问题前先用它确认。") {
            string("from", "起始日期 YYYY-MM-DD")
            string("to", "结束日期 YYYY-MM-DD")
        })
        put(tool("create_event", "新建一条行程。时间一律用 HH:mm；整学期课程/重复事项用 weeks 指定教学周范围。") {
            string("title", "标题")
            string("date", "首次发生的日期 YYYY-MM-DD")
            string("start", "开始时刻 HH:mm，例如 14:30")
            string("end", "结束时刻 HH:mm；为空表示这是一个具体时间点（例如截止时刻），不要编造")
            string("weeks", "教学周范围，例如 5-16；不连续写 5,7,9；为空表示只这一次")
            string("parity", "EVERY/ODD/EVEN（每周/单周/双周），默认 EVERY")
            string("location", "地点，可为空")
            string("note", "描述：把原文里关于这件事的补充信息写进来（谁组织的、要带什么、注意事项），可为空")
        })
        put(tool("move_occurrence", "把某一天的一次课程/行程改到别的时间地点（只改这一次，不动整条规则）。") {
            string("title", "要改的那条日程的标题（可以是标题的一部分，用于匹配）")
            string("date", "原本的日期 YYYY-MM-DD")
            string("to_date", "改到哪一天 YYYY-MM-DD")
            string("to_start", "改后的开始时刻 HH:mm，可为空表示不变")
            string("to_end", "改后的结束时刻 HH:mm，可为空表示不变")
            string("to_location", "改后的地点，可为空表示不变")
        })
        put(tool("cancel_occurrence", "取消某一天的某一次日程（只有这一次，其他周照常）。") {
            string("title", "日程标题或标题的一部分")
            string("date", "哪一天 YYYY-MM-DD")
        })
        put(tool("delete_event", "删除整条日程，含它所有的重复周次。只在一整门课/一整件事都要删掉时使用。") {
            string("title", "日程标题或标题的一部分")
        })
        put(tool("list_events", "列出库里所有日程主规则（含周次规则），用于了解全貌。") {})
        put(
            tool(
                "propose_events",
                "把用户粘贴的一段文字（课表、群公告、便签）或图片里的日程**提取出来给用户确认**。" +
                    "只在用户给的是「需要被记录成日程的材料」时使用；" +
                    "注意：调用它只是把候选摆出来，**不会直接写库**，用户要逐条确认后才入库。",
            ) {
                string(
                    "items_json",
                    "JSON 数组字符串。每项字段：" +
                        "title(标题)、" +
                        "kind(只能是 ACTIVITY/DEADLINE/COURSE/OFFICE_HOUR 之一，**必须用英文枚举，不要用中文**)；" +
                        "date(YYYY-MM-DD)；" +
                        "start(开始时刻 HH:mm，例如 \"14:30\")；" +
                        "end(结束时刻 HH:mm；截止型只给一个时刻时留空并设 point=true)；" +
                        "point(布尔值，true 表示只要一个具体时刻，例如「23:59 前交」)；" +
                        "weeks(课表类课程的教学周范围，例如 \"5-16\" 或 \"5,7,9\"；一次性事件留空)；" +
                        "parity(EVERY/ODD/EVEN，单周 ODD、双周 EVEN，默认 EVERY)；" +
                        "location(地点)；" +
                        "note(**描述**：原文里关于这件事的补充信息——谁组织的、在哪集合、" +
                        "要带什么、注意事项、教师、周次——尽量填满，**但不要写「第几节」或「由第 N 节折算」**)；" +
                        "evidence(原文里支撑这条的原话片段)。" +
                        "**时间一律用 start/end 的 HH:mm，绝对不要用「第几节」。**" +
                        "**课表里的课程必须一次给整学期 weeks，不要把一门课拆成一周一条。**",
                )
            }
        )
    }

    private fun tool(name: String, description: String, parameters: JSONObject.() -> Unit): JSONObject {
        val properties = JSONObject().apply { parameters() }
        val required = JSONArray()
        properties.keys().forEach { required.put(it) }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", required)
                    )
            )
    }

    private fun JSONObject.string(name: String, description: String) {
        put(name, JSONObject().put("type", "string").put("description", description))
    }

    companion object {
        /** 工具调用最多来回几轮，防止模型来回打转。 */
        const val MAX_TOOL_ROUNDS = 5
    }
}
