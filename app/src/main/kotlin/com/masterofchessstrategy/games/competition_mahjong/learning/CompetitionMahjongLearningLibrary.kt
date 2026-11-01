package com.masterofchessstrategy.games.competition_mahjong.learning

internal sealed interface MahjongLessonTask {
    data object Reading : MahjongLessonTask
    data class Choice(val options: List<String>, val correctIndex: Int) : MahjongLessonTask {
        init { require(options.size >= 2 && correctIndex in options.indices) }
    }
    /** Indices distinguish physical copies of an identical tile. */
    data class Tiles(
        val tiles: List<Int>,
        val acceptedSelections: Set<Set<Int>>,
        val prompt: String,
    ) : MahjongLessonTask {
        init {
            require(tiles.isNotEmpty() && tiles.all { it in 0..41 })
            require(tiles.groupingBy { it }.eachCount().all { (tile, count) ->
                count <= if (tile < 34) 4 else 1
            })
            require(acceptedSelections.isNotEmpty() && acceptedSelections.all { selection ->
                selection.isNotEmpty() && selection.all { it in tiles.indices }
            })
        }
    }
}

internal data class MahjongLessonCard(
    val id: String,
    val title: String,
    val explanation: String,
    val task: MahjongLessonTask,
    val answerExplanation: String,
)

internal data class MahjongLessonChapter(
    val id: String,
    val title: String,
    val cards: List<MahjongLessonCard>,
)

/** Project-authored teaching examples; references and scope are in the game learning design. */
internal object CompetitionMahjongLearningLibrary {
    const val CONTENT_VERSION = 1
    const val CHAPTER_COUNT = 4

    private fun reading(id: String, title: String, text: String) =
        MahjongLessonCard(id, title, text, MahjongLessonTask.Reading, "已阅读，可以继续练习。")

    private fun quiz(id: String, title: String, text: String, answer: Int,
        explanation: String, vararg options: String) =
        MahjongLessonCard(id, title, text, MahjongLessonTask.Choice(options.toList(), answer), explanation)

    private fun tiles(id: String, title: String, text: String, values: List<Int>,
        selections: Set<Set<Int>>, prompt: String, answer: String) =
        MahjongLessonCard(id, title, text, MahjongLessonTask.Tiles(values, selections, prompt), answer)

    val chapters: List<MahjongLessonChapter> = listOf(
        MahjongLessonChapter("tiles", "牌组与和牌结构", listOf(
            reading("tile-families", "认识144张牌",
                "万、条、筒各有1至9，每种四张；东南西北、中发白也各四张，共136张。另有八张不同的花牌，合计144张。花牌亮出后补牌，不用它组成顺子、刻子或将牌。"),
            tiles("choose-pair", "分步练习：将牌", "选出两张相同的五筒作为一对将牌。这里只展示局部牌。",
                listOf(22, 4, 22, 23), setOf(setOf(0, 2)), "点选两张五筒", "五筒和五筒是一对；五万不能代替五筒。"),
            tiles("choose-chow", "分步练习：顺子", "选出同花色且连续的三张牌，组成一副顺子。",
                listOf(1, 2, 3, 12, 31), setOf(setOf(0, 1, 2)), "选出完整顺子", "二万、三万、四万连续且同属万子。不同花色不能混成顺子。"),
            tiles("choose-pung", "分步练习：刻子", "选出三张相同的中，组成一副刻子。字牌不能组成顺子。",
                listOf(31, 27, 31, 32, 31), setOf(setOf(0, 2, 4)), "选出三个中", "三个中是一副刻子；东、中、发不是顺子。"),
            quiz("ordinary-shape", "知识测试：普通和牌", "普通和牌以哪种结构组成？这里不讨论七对等特殊牌型。", 1,
                "普通和牌为四副面子加一对将牌；一副杠仍按一副面子计算。结构成立后还须检查起和分数。",
                "三副面子加三张单牌", "四副面子加一对将牌", "只要14张牌就可以"),
            quiz("flower-role", "知识测试：花牌", "手中有春、一万、二万，可用春当三万组成顺子吗？", 0,
                "花牌不替代序数牌。补花取到的普通牌才可参与组牌。", "不可以", "可以，任意花都能代牌", "只有庄家可以"),
        )),
        MahjongLessonChapter("claims", "吃碰杠与认领顺序", listOf(
            reading("claim-rules", "从摸打到认领",
                "轮到自己通常摸一张再弃一张；庄家开局先弃牌。吃只能认领上家弃牌，碰和明杠可以认领任一其他席位弃牌。和牌优先，碰或明杠优先于吃。"),
            tiles("claim-chow", "分步练习：吃上家", "上家打出三万。请从自己的局部手牌中选两张，与三万组成顺子。",
                listOf(0, 1, 4, 5), setOf(setOf(0, 1)), "选出用于吃牌的两张暗牌", "一万、二万加上上家的三万组成123万；三万不需要再从手中选。"),
            tiles("claim-pung", "分步练习：碰白", "对家打出白。请选出自己用于碰牌的两张白。",
                listOf(33, 32, 33, 31), setOf(setOf(0, 2)), "选出两张白", "手里的两张白加对家的一张白组成碰；碰不限于上家。"),
            quiz("chow-source", "知识测试：吃牌来源", "下家打出你能组成顺子的牌，能声明吃吗？", 2,
                "只能吃上家的弃牌；牌形能组成顺子不意味着任意席位都能吃。", "能，任何人都能吃", "只要没有人碰就能吃", "不能，下家不是上家"),
            quiz("claim-priority", "知识测试：多人认领", "同一弃牌有合格和牌、碰牌和吃牌声明，先处理哪一个？", 0,
                "和牌优先；没有成立的和牌时才处理碰、杠和吃。", "合格和牌", "先点击按钮的人", "吃牌"),
            quiz("two-winners", "知识测试：同时报和", "东家弃牌，南家和西家都能合法和牌。同报时谁得到这张牌？", 1,
                "按从出牌者起的行牌顺序，最近的下一位合格和牌者获胜，此例为南家。", "两家都和", "南家", "西家"),
            reading("kong-types", "杠牌与补牌",
                "暗牌四张相同可暗杠；暗牌三张可认领第四张弃牌明杠；已有碰后，自己摸到第四张可加杠。杠后从墙尾补牌。吃或碰之后不能立即杠，必须先完成弃牌并等以后摸牌。加杠可被其他合格手牌抢杠和。"),
            quiz("kong-after-pung", "知识测试：碰后能否立即杠", "刚碰完牌，手里还有另一种四张相同的牌，此刻能直接暗杠吗？", 1,
                "不能在刚吃或碰的同一轮立即杠；应先弃牌，以后摸牌时再判断。", "能，四张相同就行", "不能，先弃牌", "只有庄家能"),
        )),
        MahjongLessonChapter("fans", "八分起和与番种基础", listOf(
            reading("fan-threshold", "先检查八分门槛",
                "能组成和牌结构还不够：不含花牌的番种分至少达到8分。满足门槛后，再把花牌每张1分加入基本分。番种按规则组合，不能把必然包含的小番重复相加。"),
            quiz("flowers-not-threshold", "知识测试：七分加花", "按规则组合得到7分，另有两张花牌，是否达到起和条件？", 2,
                "起和门槛不含花牌，7分仍不足8分，不能因加花变成9分而和牌。", "可以，总共9分", "只有自摸才可以", "不可以，花不补足起和分"),
            tiles("pure-straight", "分步练习：清龙", "选出123、456、789万共九张，观察同一花色完整一条龙。这里只辨认番种组成，不是一手完整和牌。",
                listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 18, 19),
                setOf((0..8).toSet()), "选出九张万子", "三副万子顺子连成1至9，清龙为16分；完整手牌仍须配齐另一副面子和将牌。"),
            quiz("seven-pairs", "知识测试：特殊牌型", "七对作为特殊和牌结构，需要怎样的牌组？", 0,
                "七对由七个对子组成，共14张，不能有已经吃、碰或杠出的副露。", "七个对子且没有副露", "七张不同的单牌", "四副顺子和一对将牌"),
            quiz("non-duplicate", "知识测试：番种叠加", "已按清一色计分，能再以它必然包含的缺一门来加分吗？", 1,
                "不能。番种成立必然包含的小番不得重复计分；不能把列表里的所有番值直接求和。", "可以，所有小番都累加", "不能，按不重复原则处理", "只有自摸才加"),
            quiz("flower-basic", "知识测试：合法和牌基本分", "不含花的有效番种共16分，有两张花，结算的基本分是多少？", 2,
                "16分已经达到起和门槛，加两张花后基本分为18分；底分另算。", "16分", "26分", "18分"),
            quiz("kong-flower-win", "知识测试：补花和牌", "杠后补来花牌，继续补花得到普通牌而和，应如何看待和牌方式？", 0,
                "由补花取得的牌成和按自摸处理，不能把这次补花成和算作杠上开花。", "自摸，不算杠上开花", "一定计杠上开花8分", "花牌本身就是所和牌"),
        )),
        MahjongLessonChapter("results", "结算、错和与轮庄", listOf(
            reading("score-basics", "四席结算",
                "和牌底分为8分。自摸时其余三家各付8加基本分；点和时其余三家各付底分，出牌者还付基本分。庄家不加倍。一盘结束，无论和牌或荒牌都轮换庄家。"),
            quiz("self-draw-score", "知识测试：自摸结算", "赢家基本分18分，自摸。其他每家应付多少分？", 1,
                "每家付8＋18＝26分，赢家共得78分；四席合计为零。", "18分", "26分", "只有庄家付52分"),
            quiz("discard-score", "知识测试：点和结算", "基本分18分，点和。出牌者与另外两家分别付多少？", 0,
                "出牌者付26分，另外两家各付8分，赢家共得42分。", "26、8、8", "18、0、0", "26、26、26"),
            reading("false-win", "主动报和前先核查",
                "不足8分或错认所和牌而报和，向其余每家付10分；尚未听牌却报和，向每家付20分。两类都会失去本盘和牌资格，仍继续陪打。应用的合格和牌提示与主动报和确认是不同入口，确认前应核查。"),
            quiz("below-eight-penalty", "知识测试：不足起和分", "手牌结构成立，但仅7分且报和，应向每家罚付多少分？", 2,
                "每家10分，报和者共损失30分，并在本盘停和。", "8分", "20分", "10分"),
            quiz("unready-penalty", "知识测试：未听报和", "尚未听牌却宣布和牌，应向每家罚付多少分？", 1,
                "每家20分，报和者共损失60分，并在本盘停和；不能把所有错和一律算成同一罚分。", "10分", "20分", "基本分加倍"),
            quiz("dealer-rotation", "知识测试：庄家轮换", "东家是本盘庄家并和牌，下盘怎么办？", 0,
                "仍由原南家接庄；国标竞赛规则不因庄家获胜而连庄。", "原南家接庄", "东家连庄", "比较本盘赢家的分数再决定"),
            quiz("exhaustive-draw", "知识测试：荒牌", "牌墙耗尽且无人和牌，哪一项正确？", 2,
                "荒牌没有和牌结算，已有犯规罚分仍有效；下盘依序轮庄。", "庄家连庄且三家各付8分", "清除所有已有罚分", "没有和牌结算，仍轮庄"),
        )),
    )

    init {
        require(chapters.size == CHAPTER_COUNT)
        require(chapters.map { it.id }.distinct().size == chapters.size)
        require(chapters.all { it.cards.isNotEmpty() })
        val cards = chapters.flatMap { it.cards }
        require(cards.map { it.id }.distinct().size == cards.size)
    }
}
