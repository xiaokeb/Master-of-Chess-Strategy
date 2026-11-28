package com.masterofchessstrategy.games.competition_mahjong.challenge

import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import java.util.Collections

enum class CompetitionMahjongChallengeTheme(val label: String) {
    PATTERN("牌型识别"), CONTEXT("竞赛上下文"), EXCLUSION("番种排斥"), THRESHOLD("八分门槛"),
}

enum class CompetitionMahjongChallengeLevel(val label: String) {
    EASY("简单"), MEDIUM("中等"), HARD("困难"), MASTER("大师"),
}

class CompetitionMahjongChallengeChoice(val id: String, val text: String) {
    init { require(id.matches(Regex("[a-d]")) && text.isNotBlank()) }
}

class CompetitionMahjongChallenge(
    val id: String,
    val title: String,
    val theme: CompetitionMahjongChallengeTheme,
    val level: CompetitionMahjongChallengeLevel,
    val prompt: String,
    choices: List<CompetitionMahjongChallengeChoice>,
    val correctChoiceId: String,
    val explanation: String,
    sourceFanIds: Set<Int>,
    val sourceRuleSection: String? = null,
) {
    val choices: List<CompetitionMahjongChallengeChoice> = Collections.unmodifiableList(choices.toList())
    val sourceFanIds: Set<Int> = Collections.unmodifiableSet(sourceFanIds.toSet())

    init {
        require(id.matches(Regex("[a-z0-9-]{1,48}")) && title.isNotBlank())
        require(prompt.isNotBlank() && explanation.isNotBlank())
        if (CompetitionMahjongPracticePosition.isDiscardTraining(id) || CompetitionMahjongPracticePosition.isResponseTraining(id) ||
            id == CompetitionMahjongPracticePosition.CONCEALED_KONG_DRAW_ID) {
            require(choices.isEmpty() && correctChoiceId.isEmpty())
        } else {
            require(choices.size in 2..4 && choices.map { it.id }.toSet().size == choices.size)
            require(choices.any { it.id == correctChoiceId })
        }
        require(sourceFanIds.all { it in 1..81 } && (sourceFanIds.isNotEmpty() || !sourceRuleSection.isNullOrBlank()))
    }
}

data class CompetitionMahjongChallengeState(
    val challenge: CompetitionMahjongChallenge,
    val attempts: Int = 0,
    val selectedChoiceId: String? = null,
    val completed: Boolean = false,
    val feedback: String = "请选择答案。",
    val visibleHand: List<Int> = emptyList(),
    val discardTraining: Boolean = false,
    val chosenDiscard: Int? = null,
    val winningTile: Int? = null,
    val discardFromSeat: Int? = null,
    val roundFan: Int? = null,
    val roundDeltas: List<Int> = emptyList(),
    val flowers: List<Int> = emptyList(),
    val qualifyingFan: Int? = null,
) {
    val robbingKong: Boolean get() = challenge.id == CompetitionMahjongPracticePosition.ROB_KONG_ID
    val responseTraining: Boolean get() = CompetitionMahjongPracticePosition.isResponseTraining(challenge.id)
    val kongTraining: Boolean get() = challenge.id == CompetitionMahjongPracticePosition.CONCEALED_KONG_DRAW_ID
}

class CompetitionMahjongChallengeSession(
    private val challenge: CompetitionMahjongChallenge,
    hand: List<Int> = emptyList(),
    flowers: List<Int> = emptyList(),
) {
    private val discardTraining = CompetitionMahjongPracticePosition.isDiscardTraining(challenge.id)
    private val robbingKong = challenge.id == CompetitionMahjongPracticePosition.ROB_KONG_ID
    private val lastWall = challenge.id == CompetitionMahjongPracticePosition.LAST_WALL_CLAIM_ID
    private val lastWallSelfDraw = challenge.id == CompetitionMahjongPracticePosition.LAST_WALL_SELF_DRAW_ID
    private val responseTraining = CompetitionMahjongPracticePosition.isResponseTraining(challenge.id)
    private val initial = CompetitionMahjongChallengeState(challenge, visibleHand = Collections.unmodifiableList(hand.toList()),
        discardTraining = discardTraining,
        winningTile = when {
            robbingKong -> CompetitionMahjongPracticePosition.ROB_KONG_TILE
            lastWall || lastWallSelfDraw -> CompetitionMahjongPracticePosition.LAST_WALL_TILE
            else -> null
        },
        discardFromSeat = when {
            robbingKong -> CompetitionMahjongPracticePosition.ROB_KONG_PAYER
            lastWall -> CompetitionMahjongPracticePosition.LAST_WALL_PAYER
            else -> null
        },
        flowers = Collections.unmodifiableList(flowers.toList()),
        feedback = when {
            robbingKong -> "请决定是否抢杠和。"
            lastWall -> "牌墙已空，请回应北家的弃牌。"
            lastWallSelfDraw -> "牌墙已空，请选择自摸或打出刚摸到的东风。"
            challenge.id == CompetitionMahjongPracticePosition.CONCEALED_KONG_DRAW_ID -> "请暗杠四张二万。"
            discardTraining -> "请选择要打出的手牌。"
            else -> "请选择答案。"
        })
    private var current = initial
    fun state(): CompetitionMahjongChallengeState = current
    fun reset(): CompetitionMahjongChallengeState {
        current = initial
        return current
    }
    fun answer(choiceId: String): CompetitionMahjongChallengeState {
        if (current.completed) return current
        require(!discardTraining && !responseTraining && !current.kongTraining)
        require(challenge.choices.any { it.id == choiceId }) { "答案不属于当前牌型题" }
        val correct = choiceId == challenge.correctChoiceId
        current = current.copy(
            attempts = current.attempts + 1,
            selectedChoiceId = choiceId,
            completed = correct,
            feedback = when {
                correct -> challenge.explanation
                challenge.sourceRuleSection != null -> "这项不符合题面条件，请先核对是否听牌，再判断应付罚分。"
                else -> "这项不符合题面条件，请结合番种定义和排斥关系再判断。"
            },
        )
        return current
    }

    internal fun discard(tile: Int): CompetitionMahjongChallengeState {
        require(discardTraining && !current.completed && current.chosenDiscard == null && tile in current.visibleHand)
        val next = CompetitionMahjongPracticePosition.playDiscard(tile, challenge.id)
        current = if (next == null) current.copy(attempts = current.attempts + 1,
            feedback = "按题目给定的摸打继续后无法和牌。请重新选择弃牌。")
        else current.copy(attempts = current.attempts + 1, visibleHand = Collections.unmodifiableList(next.hand),
            chosenDiscard = tile, winningTile = next.tile, discardFromSeat = next.payerSeat,
            flowers = Collections.unmodifiableList(next.flowers.toList()),
            feedback = if (next.payerSeat == null) "三家按题目走完一轮，请确认自摸。" else "请确认点和。")
        return current
    }

    internal fun declareKong(): CompetitionMahjongChallengeState {
        require(current.kongTraining && !current.completed && current.winningTile == null)
        val turn = CompetitionMahjongPracticePosition.concealedKongDraw(declare = true)
        current = current.copy(attempts = current.attempts + 1,
            visibleHand = Collections.unmodifiableList(turn.hand), winningTile = turn.tile,
            feedback = "暗杠后从牌墙尾补到东风。请选择自摸。")
        return current
    }

    internal fun confirmWin(): CompetitionMahjongChallengeState {
        require((discardTraining || responseTraining || current.kongTraining) && !current.completed)
        val turn = when {
            responseTraining -> applyResponse(CompetitionMahjongRoundActionKind.WIN)
            current.kongTraining -> {
                require(current.winningTile != null)
                CompetitionMahjongPracticePosition.concealedKongDraw(declare = true, settle = true)
            }
            else -> checkNotNull(CompetitionMahjongPracticePosition.playDiscard(
                checkNotNull(current.chosenDiscard), challenge.id, settle = true))
        }
        check(turn.hand == current.visibleHand && turn.tile == current.winningTile &&
            turn.payerSeat == current.discardFromSeat && turn.flowers == current.flowers)
        val showSettlement = current.kongTraining || turn.payerSeat != null || turn.flowers.isNotEmpty()
        current = current.copy(completed = true, feedback = challenge.explanation,
            attempts = current.attempts + if (responseTraining || current.kongTraining) 1 else 0,
            roundFan = turn.totalFan.takeIf { showSettlement },
            qualifyingFan = turn.qualifyingFan.takeIf { turn.flowers.isNotEmpty() },
            roundDeltas = if (showSettlement) Collections.unmodifiableList(turn.netScores.toList()) else emptyList())
        return current
    }

    internal fun passClaim(): CompetitionMahjongChallengeState {
        require(responseTraining && !current.completed)
        applyResponse(CompetitionMahjongRoundActionKind.PASS)
        current = current.copy(attempts = current.attempts + 1,
            feedback = when {
                robbingKong -> "过牌后，南家完成加杠并补牌。重新选择可练习抢杠和。"
                lastWallSelfDraw -> "打出东风后三家均过，本盘流局，四家均为 0 分。可以重新选择自摸。"
                else -> "过牌后本盘流局，四家均为 0 分。重新选择可练习末张点和。"
            })
        return current
    }

    private fun applyResponse(kind: CompetitionMahjongRoundActionKind) = when {
        robbingKong -> CompetitionMahjongPracticePosition.robKong(kind)
        lastWallSelfDraw -> CompetitionMahjongPracticePosition.lastWallSelfDraw(kind)
        else -> CompetitionMahjongPracticePosition.lastWallClaim(kind)
    }
}

/** Project-authored questions tied to the already verified WMO fan example IDs. */
object CompetitionMahjongChallengeLibrary {
    const val CONTENT_VERSION = 1
    private fun choice(id: String, text: String) = CompetitionMahjongChallengeChoice(id, text)
    private fun challenge(id: String, title: String, theme: CompetitionMahjongChallengeTheme,
                          level: CompetitionMahjongChallengeLevel, prompt: String, correct: String,
                          explanation: String, source: Set<Int>, vararg choices: CompetitionMahjongChallengeChoice) =
        CompetitionMahjongChallenge(id, title, theme, level, prompt, choices.toList(), correct, explanation, source)

    private fun falseWinReview(id: String, title: String, prompt: String, correct: String, explanation: String) =
        CompetitionMahjongChallenge(id, title, CompetitionMahjongChallengeTheme.CONTEXT,
            CompetitionMahjongChallengeLevel.MASTER, prompt, listOf(
                choice("a", "停和，各付 10 分"),
                choice("b", "停和，各付 20 分"),
                choice("c", "允许和牌"),
            ), correct, explanation, emptySet(), sourceRuleSection = "3.11.6")

    val challenges: List<CompetitionMahjongChallenge> = Collections.unmodifiableList(listOf(
        challenge("seven-plus-flowers", "花牌不能补门槛", CompetitionMahjongChallengeTheme.THRESHOLD,
            CompetitionMahjongChallengeLevel.EASY,
            "手牌基本番为7番，另有8张花牌。按竞赛规则能否和牌？", "b",
            "不能。起和门槛只计算花牌以外的番数，7番仍未达到8番。", setOf(81),
            choice("a", "可以，共15番"), choice("b", "不可以，基本番不足8番"), choice("c", "只有自摸才可以")),
        challenge("last-wall-self-draw", "最后一张自摸", CompetitionMahjongChallengeTheme.CONTEXT,
            CompetitionMahjongChallengeLevel.EASY,
            "牌墙最后一张牌由本人摸入并和牌，对应哪项8番番种？", "a",
            "本人摸入牌墙最后一张和牌计妙手回春；海底捞月用于和他人打出的最后一张。", setOf(44, 45),
            choice("a", "妙手回春"), choice("b", "海底捞月"), choice("c", "杠上开花")),
        challenge("edge-wait", "一二等三", CompetitionMahjongChallengeTheme.PATTERN,
            CompetitionMahjongChallengeLevel.EASY,
            "手中一万、二万只等三万完成一二三，等待类型是什么？", "c",
            "一二等三或八九等七属于边张，边张、嵌张、单钓互相排斥。", setOf(77, 78, 79),
            choice("a", "单钓将"), choice("b", "嵌张"), choice("c", "边张")),

        challenge("four-identical-chows", "四副同顺", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.MEDIUM,
            "同一花色四副完全相同的顺子成立一色四同顺时，哪项较低番种不再另计？", "b",
            "一色四同顺按48番计，并排斥一色三同顺等被包含的低番组合。", setOf(14, 23),
            choice("a", "清龙"), choice("b", "一色三同顺"), choice("c", "三色三同顺")),
        challenge("exact-eight", "刚好八番", CompetitionMahjongChallengeTheme.THRESHOLD,
            CompetitionMahjongChallengeLevel.MEDIUM,
            "碰碰和6番、单钓将1番，再有一副幺九刻1番，且无花牌。是否达到起和门槛？", "a",
            "三项合计8个基本番，正好达到竞赛规则的起和门槛。", setOf(49, 73, 79),
            choice("a", "达到，正好8番"), choice("b", "未达到，必须超过8番"), choice("c", "只有庄家达到")),
        challenge("concealed-self-draw", "门清自摸", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.MEDIUM,
            "没有吃、碰、明杠，最终自摸成和。应计哪项4番番种，并不再分别计门前清和自摸？", "c",
            "门清自摸计不求人4番，包含门前清与自摸的含义，不重复叠加。", setOf(56, 62, 80),
            choice("a", "门前清"), choice("b", "自摸"), choice("c", "不求人")),

        challenge("seven-pairs-exclusion", "七对的排斥", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.HARD,
            "标准七对成立并以一张牌补成最后一对时，下列哪项不因等待形状再另计？", "a",
            "七对按24番计，排斥单钓将，也不另计门前清。", setOf(19, 62, 79),
            choice("a", "单钓将"), choice("b", "花牌"), choice("c", "和绝张")),
        challenge("seven-honors-unconnected", "七星不靠", CompetitionMahjongChallengeTheme.PATTERN,
            CompetitionMahjongChallengeLevel.HARD,
            "七种字牌齐全，并配合三色147、258、369中互不相连的序数牌，形成哪项24番特殊和型？", "b",
            "七种字牌齐全的特殊不靠结构为七星不靠；普通全不靠只有12番。", setOf(20, 34),
            choice("a", "十三幺"), choice("b", "七星不靠"), choice("c", "全不靠")),
        challenge("robbing-fourth", "抢第四张加杠", CompetitionMahjongChallengeTheme.CONTEXT,
            CompetitionMahjongChallengeLevel.HARD,
            "他家用全场可见的第四张牌加杠，你在抢杠窗口和牌。主要8番上下文番种是什么？", "c",
            "加杠牌被抢和计抢杠和；同一张虽是第四张，不再另计和绝张。", setOf(47, 58),
            choice("a", "杠上开花"), choice("b", "和绝张"), choice("c", "抢杠和")),

        challenge("four-wind-pungs", "四风齐刻", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.MASTER,
            "东南西北四组刻子同时成立大四喜时，哪组低番项目都不再另计？", "a",
            "大四喜88番包含三风刻、碰碰和及相关风刻，不重复累计这些低番。", setOf(1, 38, 49, 60, 61, 73),
            choice("a", "三风刻、碰碰和及风刻"), choice("b", "七对与门前清"), choice("c", "清一色与无字")),
        challenge("four-kongs", "四组杠", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.MASTER,
            "和牌结构中共有四组杠，计四杠88番后，下列哪组被包含番种不再另计？", "b",
            "四杠排斥三杠、双明杠和明杠等按杠数拆分的低番项目。", setOf(5, 17, 57, 74),
            choice("a", "全带幺与幺九刻"), choice("b", "三杠、双明杠与明杠"), choice("c", "自摸与不求人")),
        challenge("nine-gates", "九莲宝灯", CompetitionMahjongChallengeTheme.PATTERN,
            CompetitionMahjongChallengeLevel.MASTER,
            "门清同一花色具1112345678999，再以该花色任一序数牌成和，对应哪项88番和型？", "c",
            "这一门清同花色基础结构为九莲宝灯，按88番计。", setOf(4, 22),
            choice("a", "清龙"), choice("b", "连七对"), choice("c", "九莲宝灯")),
        challenge(CompetitionMahjongPracticePosition.PUZZLE_ID, "七对起手", CompetitionMahjongChallengeTheme.PATTERN,
            CompetitionMahjongChallengeLevel.MASTER,
            "你坐东家，摸完起手牌。根据当前手牌，选择能成立的和牌方式。", "a",
            "Native 裁定自摸成立，七对计 24 番，已达到八番起和门槛。", setOf(19),
            choice("a", "自摸，七对"), choice("b", "自摸，碰碰和"), choice("c", "不能和牌，继续出牌")),
        challenge(CompetitionMahjongPracticePosition.DISCARD_PUZZLE_ID, "留东听七对", CompetitionMahjongChallengeTheme.PATTERN,
            CompetitionMahjongChallengeLevel.MASTER,
            "打出一张牌，保留六对及一张东风。本题中三家依次摸切，过一轮后轮到你摸牌。", "",
            "七对自摸成立，七对计 24 番。", setOf(19)),
        challenge(CompetitionMahjongPracticePosition.NINE_GATES_PUZZLE_ID, "九莲宝灯怎样计番", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.MASTER,
            "东家起手自摸同色五，牌面已成九莲宝灯。根据新版计分，这手牌还可计不求人或幺九刻吗？", "b",
            "九莲宝灯计 88 番，可以加计不求人 4 番。幺九刻已被九莲宝灯包含。", setOf(4, 56, 73),
            choice("a", "可加计幺九刻"), choice("b", "可加计不求人"), choice("c", "两项均可加计")),
        challenge(CompetitionMahjongPracticePosition.ALL_GREEN_PUZZLE_ID, "绿一色还可计混一色", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.MASTER,
            "当前手牌由二三四条、六条刻、八条刻、发刻和三条将组成，起手自摸三条。计绿一色后，还能加计哪项花色番？", "a",
            "绿一色计 88 番。本题有发且其余都是条子，可以加计混一色 6 番，不能计清一色。", setOf(3, 22, 50),
            choice("a", "混一色 6 番"), choice("b", "清一色 24 番"), choice("c", "两项都不计")),
        challenge(CompetitionMahjongPracticePosition.TERMINALS_PUZZLE_ID, "清幺九的两组双同刻", CompetitionMahjongChallengeTheme.EXCLUSION,
            CompetitionMahjongChallengeLevel.MASTER,
            "当前手牌有一万、九万、一条、九条四副暗刻，以一筒作将起手自摸。清幺九成立后，两组独立的双同刻如何计？", "c",
            "清幺九计 64 番。一万与一条、九万与九条分别组成双同刻，各计 2 番，共 4 番。碰碰和、全带幺、幺九刻和无字不重复计。", setOf(8, 49, 55, 65, 73, 76),
            choice("a", "被清幺九包含，不计"), choice("b", "只能计一组，共 2 番"), choice("c", "两组分别计，共 4 番")),
        challenge(CompetitionMahjongPracticePosition.ORPHANS_DISCARD_ID, "留十三种幺九字", CompetitionMahjongChallengeTheme.PATTERN,
            CompetitionMahjongChallengeLevel.MASTER,
            "你摸完起手牌。请选择一张弃牌，保留十三种不同的幺九牌和字牌。其他三家按题目逐次摸切，过一轮后轮到你自摸。", "",
            "十三种幺九字牌齐全，自摸白板成对，原生裁定十三幺计 88 番。", setOf(7)),
        challenge(CompetitionMahjongPracticePosition.PURE_STRAIGHT_DISCARD_ID, "留万子成清龙",
            CompetitionMahjongChallengeTheme.PATTERN, CompetitionMahjongChallengeLevel.MASTER,
            "打一张牌，保留万子的一至九和一二三条。其他三家依次摸切，一轮后轮到你摸牌。", "",
            "万子的一二三、四五六、七八九各为一副顺子，自摸五筒成将，原生裁定清龙计 16 番。", setOf(28)),
        challenge(CompetitionMahjongPracticePosition.MIXED_TRIPLE_CHOW_DISCARD_ID, "留三色同顺",
            CompetitionMahjongChallengeTheme.PATTERN, CompetitionMahjongChallengeLevel.MASTER,
            "打一张牌，保留万、筒、条各一副一二三顺子。其他三家依次摸切，一轮后轮到你摸牌。", "",
            "万、筒、条各有一二三顺子，自摸东风成将，原生裁定三色三同顺计 8 番。", setOf(41)),
        challenge(CompetitionMahjongPracticePosition.FOUR_CONCEALED_CLAIM_ID, "四暗刻点和",
            CompetitionMahjongChallengeTheme.CONTEXT, CompetitionMahjongChallengeLevel.MASTER,
            "你是东家。打出一张牌，保留手中的四副暗刻。南家下一手摸切，观察南家的弃牌能否成将。", "",
            "四副暗刻已在手中，南家打出的东风组成将牌，四暗刻计 64 番。三家各付 8 分，南家另付本盘总番。", setOf(12)),
        falseWinReview(CompetitionMahjongPracticePosition.WRONG_TILE_ID, "听东误报南",
            "你坐东家，手中六对加一张东风。南家打出南风，你误报和牌。裁判如何处理？", "a",
            "手牌已听东，南风不能完成七对。本盘停和，三家各得 10 分，东家共付 30 分。"),
        falseWinReview(CompetitionMahjongPracticePosition.NOT_READY_ID, "未听却报和",
            "你坐东家，眼前的十三张手牌还未听牌。南家打出南风，你误报和牌。裁判如何处理？", "b",
            "报和时还未听牌。本盘停和，三家各得 20 分，东家共付 60 分。"),
        challenge(CompetitionMahjongPracticePosition.ROB_KONG_ID, "实战抢杠和",
            CompetitionMahjongChallengeTheme.CONTEXT, CompetitionMahjongChallengeLevel.MASTER,
            "你坐西家，十三幺听红中。南家已经碰过红中，摸到第四张后宣布加杠。请选择如何回应。", "",
            "红中补齐十三幺。抢杠和计 8 番，和绝张不叠加。南家原碰保留，本盘直接结算。", setOf(7, 47, 58)),
        challenge(CompetitionMahjongPracticePosition.TRIPLE_PUNG_DISCARD_ID, "保留三色五刻",
            CompetitionMahjongChallengeTheme.EXCLUSION, CompetitionMahjongChallengeLevel.MASTER,
            "你坐东家。请保留三种花色的五刻和一张南风，打出多余的一张。其他三家依次摸切后轮到你摸牌。", "",
            "南风作将，三同刻计 16 番。同组三副刻子已用于三同刻，双同刻不重复计。", setOf(32, 65)),
        challenge(CompetitionMahjongPracticePosition.FLOWER_REPLACEMENT_ID, "连补两花后自摸",
            CompetitionMahjongChallengeTheme.CONTEXT, CompetitionMahjongChallengeLevel.MASTER,
            "你坐东家。保留万、筒、条各一副一二三顺子和一张东风。打出多余的牌并观察补花后的摸牌。", "",
            "从牌墙尾连续补花后摸到东风，自摸成和。两张花牌在和牌后各加 1 分，不计入起和，也不算杠上开花。",
            setOf(41, 46, 81)),
        challenge(CompetitionMahjongPracticePosition.LAST_WALL_CLAIM_ID, "牌墙末张点和",
            CompetitionMahjongChallengeTheme.CONTEXT, CompetitionMahjongChallengeLevel.MASTER,
            "你坐东家。北家摸完牌墙后打出东风，你手中只缺一张东风作将。请选择点和或过牌。", "",
            "东风成将，海底捞月计 8 番。三家各付底分 8，北家另付本盘总番。", setOf(44, 45)),
        challenge(CompetitionMahjongPracticePosition.LAST_WALL_SELF_DRAW_ID, "牌墙末张自摸",
            CompetitionMahjongChallengeTheme.CONTEXT, CompetitionMahjongChallengeLevel.MASTER,
            "你坐北家，摸到牌墙最后一张东风，可以组成将牌。请选择自摸或打出东风。", "",
            "东风成将，妙手回春计 8 番。三家各付底分 8 和本盘总番。", setOf(44, 45)),
        challenge(CompetitionMahjongPracticePosition.CONCEALED_KONG_DRAW_ID, "暗杠后补牌自摸",
            CompetitionMahjongChallengeTheme.CONTEXT, CompetitionMahjongChallengeLevel.MASTER,
            "你坐东家，手中有四张二万。先暗杠二万，再看从牌墙尾补到的牌能否成和。", "",
            "暗杠后补到东风成将。杠上开花计 8 番，自摸 1 番不重复计。三家各付底分和本盘总番。", setOf(46, 80)),
    ))

    init {
        require(challenges.map { it.id }.toSet().size == challenges.size)
        require(CompetitionMahjongChallengeLevel.entries.all { level -> challenges.any { it.level == level } })
        require(CompetitionMahjongChallengeTheme.entries.all { theme -> challenges.any { it.theme == theme } })
    }

    fun challenge(id: String): CompetitionMahjongChallenge? = challenges.firstOrNull { it.id == id }
}
