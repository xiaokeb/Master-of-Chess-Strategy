package com.masterofchessstrategy.games.competition_mahjong.challenge

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
) {
    val choices: List<CompetitionMahjongChallengeChoice> = Collections.unmodifiableList(choices.toList())
    val sourceFanIds: Set<Int> = Collections.unmodifiableSet(sourceFanIds.toSet())

    init {
        require(id.matches(Regex("[a-z0-9-]{1,48}")) && title.isNotBlank())
        require(prompt.isNotBlank() && explanation.isNotBlank())
        require(choices.size in 2..4 && choices.map { it.id }.toSet().size == choices.size)
        require(choices.any { it.id == correctChoiceId })
        require(sourceFanIds.isNotEmpty() && sourceFanIds.all { it in 1..81 })
    }
}

data class CompetitionMahjongChallengeState(
    val challenge: CompetitionMahjongChallenge,
    val attempts: Int = 0,
    val selectedChoiceId: String? = null,
    val completed: Boolean = false,
    val feedback: String = "请选择答案。",
)

class CompetitionMahjongChallengeSession(private val challenge: CompetitionMahjongChallenge) {
    private var current = CompetitionMahjongChallengeState(challenge)
    fun state(): CompetitionMahjongChallengeState = current
    fun reset(): CompetitionMahjongChallengeState {
        current = CompetitionMahjongChallengeState(challenge)
        return current
    }
    fun answer(choiceId: String): CompetitionMahjongChallengeState {
        if (current.completed) return current
        require(challenge.choices.any { it.id == choiceId }) { "答案不属于当前牌型题" }
        val correct = choiceId == challenge.correctChoiceId
        current = current.copy(
            attempts = current.attempts + 1,
            selectedChoiceId = choiceId,
            completed = correct,
            feedback = if (correct) challenge.explanation else "这项不符合题面条件，请结合番种定义和排斥关系再判断。",
        )
        return current
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
    ))

    init {
        require(challenges.map { it.id }.toSet().size == challenges.size)
        require(CompetitionMahjongChallengeLevel.entries.all { level -> challenges.count { it.level == level } == 3 })
        require(CompetitionMahjongChallengeTheme.entries.all { theme -> challenges.any { it.theme == theme } })
    }

    fun challenge(id: String): CompetitionMahjongChallenge? = challenges.firstOrNull { it.id == id }
}
