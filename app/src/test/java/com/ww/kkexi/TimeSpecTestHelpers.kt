package com.ww.kkexi

/**
 * 测试专用扩展：从 [TimeSpec] 里取出节次。
 *
 * 为什么需要：用户提了两次「新建要能填时间点，不要只能选节次」之后，
 * [Event] / [Session] 的时间变成了 [TimeSpec]（可以是时刻，也可以是节次）。
 * 而既有的测试里有大量断言是围绕「第几节」写的——那些断言本身仍然有效
 * （课程确实是按节次排的），只是取值路径变了。
 *
 * 与其把几十条断言全改一遍（容易改错、也容易把测试改成"迎合实现"），
 * 不如在这里给测试补一层薄薄的取值适配：
 * - 按节次录入的：返回真实节次；
 * - 按时刻录入的：返回 0（它本来就没有节次概念）。
 *
 * 生产代码里**不用**这两个属性——界面会根据 [Session.periodLabel] 是否为空
 * 来决定要不要显示节次，这才是正确做法。
 */

val Event.specFirstPeriodForTest: Int
    get() = (time as? TimeSpec.ByPeriod)?.firstPeriod ?: 0

val Event.specPeriodCountForTest: Int
    get() = (time as? TimeSpec.ByPeriod)?.periodCount ?: 0

val Session.specFirstPeriodForTest: Int
    get() = (spec as? TimeSpec.ByPeriod)?.firstPeriod ?: 0

val Session.specPeriodCountForTest: Int
    get() = (spec as? TimeSpec.ByPeriod)?.periodCount ?: 0

val EventException.specMovedFirstPeriodForTest: Int?
    get() = (movedTime as? TimeSpec.ByPeriod)?.firstPeriod

val EventException.specMovedPeriodCountForTest: Int?
    get() = (movedTime as? TimeSpec.ByPeriod)?.periodCount
