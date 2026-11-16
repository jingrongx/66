package com.lvyoubao.app

import com.lvyoubao.app.data.PhoneticGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneticGuardTest {

    @Test
    fun testSemanticDriftRejected() {
        // 事故还原：问的是酸甜，被 AI 上下文联想改成了红白
        val draft = "为什么柚子有酸奶也有甜？"
        val polished = "为什么柚子有红的也有白的？"
        // 拼音发音严重偏离，必须一票否决
        assertFalse(
            "语义篡改应该被拼音护栏拦截",
            PhoneticGuard.isPhoneticallyPlausible(draft, polished, threshold = 0.75f),
        )
    }

    @Test
    fun testHomophoneFixAllowed() {
        // 同音字纠错：幼子 -> 柚子
        val draft = "为什么幼子是酸的"
        val polished = "为什么柚子是酸的"
        assertTrue(
            "同音错字纠正应该允许通过",
            PhoneticGuard.isPhoneticallyPlausible(draft, polished, threshold = 0.75f),
        )
    }

    @Test
    fun testNumberToWordFixAllowed() {
        // 数字混写纠错：90酸 -> 酒石酸
        val draft = "90酸伐尼克兰片怎么吃"
        val polished = "酒石酸伐尼克兰片怎么吃"
        assertTrue(
            "数字转同音汉字应该允许通过",
            PhoneticGuard.isPhoneticallyPlausible(draft, polished, threshold = 0.75f),
        )
    }

    @Test
    fun testDialectAccentFixAllowed() {
        // 方言平翘舌/口音纠错：资道 -> 知道
        val draft = "你资道这个景点的门票吗"
        val polished = "你知道这个景点的门票吗"
        assertTrue(
            "平翘舌方言口音纠正应该允许通过",
            PhoneticGuard.isPhoneticallyPlausible(draft, polished, threshold = 0.75f),
        )
    }

    @Test
    fun testDialectModeThresholdStillRejectsSemanticDrift() {
        // 方言模式下放宽到 0.70 阈值，但「酸奶也有甜」vs「红的也有白的」（相似度 68.75%）依然被坚决拦截！
        val draft = "为什么柚子有酸奶也有甜？"
        val polished = "为什么柚子有红的也有白的？"
        assertFalse(
            "方言模式放宽阈值后，依然必须拦截严重语义颠覆",
            PhoneticGuard.isPhoneticallyPlausible(draft, polished, threshold = 0.70f),
        )
    }

    @Test
    fun testIdenticalAndPunctuation() {
        val draft = "为什么柚子有红的，还有白的呢？"
        val polished = "为什么柚子有红的，还有白的呢"
        assertTrue(
            "标点微调或原样应通过",
            PhoneticGuard.isPhoneticallyPlausible(draft, polished),
        )
    }
}
