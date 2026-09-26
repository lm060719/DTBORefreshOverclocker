package io.mo.dtbooverclocker.core

import io.mo.dtbooverclocker.model.CustomTimingParams
import io.mo.dtbooverclocker.model.PatchMode
import io.mo.dtbooverclocker.model.PatchStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtsTimingPatcherTest {
    @Test
    fun multiLabelTimingNodesKeepTheirFullPath() {
        val file = File.createTempFile("panel", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel_a: panel_b: panel {
                |        timings_1: timings_2: display-timings {
                |            timing_0_37: timing_0_146: timing@0 {
                |                qcom,mdss-dsi-panel-framerate = <0x78>;
                |            };
                |        };
                |    };
                |};
            """.trimMargin()
        )

        assertEquals("/panel/display-timings/timing@0", DtsTimingPatcher.analyzeEntry(0, file).single().nodePath)
    }

    @Test
    fun balancedPatchChangesRefreshClockAndVerticalPorches() {
        val file = File.createTempFile("panel", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <0x78>;
                |            qcom,mdss-dsi-panel-clockrate = <0x29b92700>;
                |            qcom,mdss-dsi-panel-width = <1440>;
                |            qcom,mdss-dsi-panel-height = <3200>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        assertEquals(120, candidate.currentHz)

        val patched = DtsTimingPatcher.patch(candidate, 144, PatchStrategy.BALANCED_BLANKING_TIME)
        assertTrue(patched.text.contains("qcom,mdss-dsi-panel-framerate = <0x90>;"))
        assertTrue(patched.text.contains("qcom,mdss-dsi-v-front-porch = <24>;"))
        assertTrue(patched.text.contains("qcom,mdss-dsi-v-back-porch = <24>;"))
        assertTrue(patched.changes.any { it.contains("clockrate") })
    }

    @Test
    fun detectsTwoCellClock() {
        val file = File.createTempFile("panel64", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    timing@0 {
                |        refresh-rate = <120>;
                |        pixel-clock = <0x0 0x2faf0800>;
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(1, file).single()
        assertEquals(800_000_000L, candidate.pixelClockHz)
    }

    @Test
    fun dtcStringClockIsParsedAndPatchedCorrectly() {
        // "aFX" in DTC decompiled output represents 0x61465800 = 1632000000 Hz
        val file = File.createTempFile("panel_str_clock", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <0x90>;
                |            qcom,mdss-dsi-panel-clockrate = "aFX";
                |            qcom,mdss-dsi-panel-width = <1200>;
                |            qcom,mdss-dsi-panel-height = <2670>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        assertEquals(144, candidate.currentHz)
        assertEquals(1_632_000_000L, candidate.pixelClockHz)

        val patched = DtsTimingPatcher.patch(candidate, 165, PatchStrategy.BALANCED_BLANKING_TIME)
        assertTrue(patched.text.contains("qcom,mdss-dsi-panel-framerate = <0xa5>;"))
        assertTrue(!patched.text.contains("\"aFX\""))
        assertTrue(patched.text.contains("qcom,mdss-dsi-panel-clockrate = <0x"))
    }

    @Test
    fun dtcByteStreamClockIsParsedAndPatchedCorrectly() {
        val file = File.createTempFile("panel_byte_clock", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <120>;
                |            qcom,mdss-dsi-panel-clockrate = [61 46 58 00];
                |            qcom,mdss-dsi-panel-width = <1200>;
                |            qcom,mdss-dsi-panel-height = <2670>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        assertEquals(1_632_000_000L, candidate.pixelClockHz)

        val patched = DtsTimingPatcher.patch(candidate, 144, PatchStrategy.BALANCED_BLANKING_TIME)
        assertTrue(!patched.text.contains("[61 46 58 00]"))
        assertTrue(patched.text.contains("qcom,mdss-dsi-panel-clockrate = <0x"))
    }

    @Test
    fun inheritsParentClockAndInsertsTimingClockrate() {
        val file = File.createTempFile("panel_parent_clock", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        qcom,mdss-dsi-panel-clockrate = <0x29b92700>;
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <120>;
                |            qcom,mdss-dsi-panel-width = <1440>;
                |            qcom,mdss-dsi-panel-height = <3200>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        assertEquals(700_000_000L, candidate.pixelClockHz)

        val patched = DtsTimingPatcher.patch(candidate, 144, PatchStrategy.BALANCED_BLANKING_TIME)
        val timingSnippet = patched.text.substringAfter("timing@0 {").substringBefore("};")
        assertTrue(timingSnippet.contains("qcom,mdss-dsi-panel-clockrate = <0x"))
    }

    @Test
    fun appendNewTimingGeneratesUniqueSiblingNodeAndKeepsOriginal() {
        val file = File.createTempFile("panel_append", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <0x78>;
                |            qcom,mdss-dsi-panel-clockrate = <0x29b92700>;
                |            qcom,mdss-dsi-panel-width = <1440>;
                |            qcom,mdss-dsi-panel-height = <3200>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        assertEquals(120, candidate.currentHz)

        val result = DtsTimingPatcher.patch(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.APPEND_NEW
        )

        // 验证原节点 timing@0 保持 120 Hz (<0x78>)
        val node0 = result.text.substringAfter("timing@0 {").substringBefore("};")
        assertTrue("原节点 framerate 必须保持 0x78", node0.contains("qcom,mdss-dsi-panel-framerate = <0x78>;"))

        // 验证新追加节点 timing@1 生成且为 144 Hz (<0x90>)
        assertTrue("必须包含新追加的 timing@1 节点", result.text.contains("timing@1 {"))
        val node1 = result.text.substringAfter("timing@1 {").substringBefore("};")
        assertTrue("新节点 framerate 必须为 0x90", node1.contains("qcom,mdss-dsi-panel-framerate = <0x90>;"))
        assertTrue("新节点消隐前肩应有调整", node1.contains("qcom,mdss-dsi-v-front-porch = <24>;"))

        // 重新反编译分析，验证能同时找到 2 个候选档位
        val newFile = File.createTempFile("panel_append_verified", ".dts")
        newFile.writeText(result.text)
        val verifiedCandidates = DtsTimingPatcher.analyzeEntry(0, newFile)
        assertEquals(2, verifiedCandidates.size)
        assertEquals(listOf(120, 144), verifiedCandidates.map { it.currentHz }.sorted())
    }

    @Test
    fun appendNewTimingCorrectlyHandlesSiblingNamingIncrement() {
        val file = File.createTempFile("panel_naming", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        qcom,mdss-dsi-panel-timing-0 {
                |            qcom,mdss-dsi-panel-framerate = <60>;
                |            qcom,mdss-dsi-panel-clockrate = <0x20000000>;
                |            qcom,mdss-dsi-panel-width = <1080>;
                |            qcom,mdss-dsi-panel-height = <2400>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |        qcom,mdss-dsi-panel-timing-1 {
                |            qcom,mdss-dsi-panel-framerate = <120>;
                |            qcom,mdss-dsi-panel-clockrate = <0x40000000>;
                |            qcom,mdss-dsi-panel-width = <1080>;
                |            qcom,mdss-dsi-panel-height = <2400>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(2, candidates.size)
        val cand120 = candidates.first { it.currentHz == 120 }

        val result = DtsTimingPatcher.patch(
            candidate = cand120,
            targetHz = 144,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.APPEND_NEW
        )

        // 已经存在 timing-0 和 timing-1，递增后应为 timing-2
        assertTrue(
            "存在 timing-0 与 timing-1 时应递增生成 qcom,mdss-dsi-panel-timing-2",
            result.text.contains("qcom,mdss-dsi-panel-timing-2 {")
        )
    }

    @Test
    fun deleteTimingNodeRemovesTargetNodeAndPreservesSiblings() {
        val file = File.createTempFile("panel_delete", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <60>;
                |            qcom,mdss-dsi-panel-clockrate = <0x20000000>;
                |            qcom,mdss-dsi-panel-width = <1080>;
                |            qcom,mdss-dsi-panel-height = <2400>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |        timing@1 {
                |            qcom,mdss-dsi-panel-framerate = <120>;
                |            qcom,mdss-dsi-panel-clockrate = <0x40000000>;
                |            qcom,mdss-dsi-panel-width = <1080>;
                |            qcom,mdss-dsi-panel-height = <2400>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(2, candidates.size)
        val cand120 = candidates.first { it.currentHz == 120 }

        val result = DtsTimingPatcher.patch(
            candidate = cand120,
            targetHz = 120,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.DELETE_EXISTING
        )

        assertTrue("timing@0 应完好保留", result.text.contains("timing@0 {"))
        assertTrue("timing@1 应被彻底移除", !result.text.contains("timing@1 {"))

        val newFile = File.createTempFile("panel_delete_verified", ".dts")
        newFile.writeText(result.text)
        val remaining = DtsTimingPatcher.analyzeEntry(0, newFile)
        assertEquals(1, remaining.size)
        assertEquals(60, remaining.first().currentHz)
    }

    @Test
    fun deleteTimingNodeRemapsNativeModeIfTargetWasNative() {
        val file = File.createTempFile("panel_native_remap", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        display-timings {
                |            native-mode = <&timing0>;
                |            timing0: timing@0 {
                |                qcom,mdss-dsi-panel-framerate = <60>;
                |                qcom,mdss-dsi-panel-clockrate = <0x20000000>;
                |            };
                |            timing1: timing@1 {
                |                qcom,mdss-dsi-panel-framerate = <120>;
                |                qcom,mdss-dsi-panel-clockrate = <0x40000000>;
                |            };
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidates = DtsTimingPatcher.analyzeEntry(0, file)
        val cand60 = candidates.first { it.currentHz == 60 }

        val result = DtsTimingPatcher.patch(
            candidate = cand60,
            targetHz = 60,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.DELETE_EXISTING
        )

        assertTrue("native-mode 应被重定向至 timing1", result.text.contains("native-mode = <&timing1>;"))
        assertTrue("timing0 节点已被移除", !result.text.contains("timing0: timing@0 {"))
        assertTrue("timing1 节点应保留", result.text.contains("timing1: timing@1 {"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun deleteSingleTimingThrowsException() {
        val file = File.createTempFile("panel_single_del", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <60>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        DtsTimingPatcher.patch(
            candidate = candidate,
            targetHz = 60,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.DELETE_EXISTING
        )
    }

    @Test
    fun customStrategyAppliesUserSpecifiedClockAndPorches() {
        val file = File.createTempFile("panel_custom", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <120>;
                |            qcom,mdss-dsi-panel-clockrate = <0x20000000>;
                |            qcom,mdss-dsi-panel-width = <1080>;
                |            qcom,mdss-dsi-panel-height = <2400>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        val candidate = DtsTimingPatcher.analyzeEntry(0, file).single()
        val custom = CustomTimingParams(
            pixelClockHz = 1_234_567_890L,
            vFrontPorch = 35,
            vBackPorch = 45,
            hFrontPorch = 25,
            hBackPorch = 35
        )

        val result = DtsTimingPatcher.patch(
            candidate = candidate,
            targetHz = 144,
            strategy = PatchStrategy.CUSTOM,
            mode = PatchMode.OVERWRITE_EXISTING,
            customParams = custom
        )

        assertTrue(result.text.contains("qcom,mdss-dsi-panel-framerate = <144>;"))
        assertTrue(result.text.contains("0x499602d2")) // 1234567890 in hex
        assertTrue(result.text.contains("qcom,mdss-dsi-v-front-porch = <35>;"))
        assertTrue(result.text.contains("qcom,mdss-dsi-v-back-porch = <45>;"))
        assertTrue(result.text.contains("qcom,mdss-dsi-h-front-porch = <25>;"))
        assertTrue(result.text.contains("qcom,mdss-dsi-h-back-porch = <35>;"))
    }

    @Test
    fun sequentialMultipleStagedOperationsOnSameDtsFile() {
        val file = File.createTempFile("panel_sequential", ".dts")
        file.writeText(
            """/dts-v1/;
                |/ {
                |    panel {
                |        native-mode = <&timing0>;
                |        timing0: timing@0 {
                |            qcom,mdss-dsi-panel-framerate = <60>;
                |            qcom,mdss-dsi-panel-clockrate = <0x20000000>;
                |            qcom,mdss-dsi-panel-width = <1080>;
                |            qcom,mdss-dsi-panel-height = <2400>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |        timing1: timing@1 {
                |            qcom,mdss-dsi-panel-framerate = <120>;
                |            qcom,mdss-dsi-panel-clockrate = <0x40000000>;
                |            qcom,mdss-dsi-panel-width = <1080>;
                |            qcom,mdss-dsi-panel-height = <2400>;
                |            qcom,mdss-dsi-h-front-porch = <20>;
                |            qcom,mdss-dsi-h-back-porch = <20>;
                |            qcom,mdss-dsi-h-pulse-width = <4>;
                |            qcom,mdss-dsi-v-front-porch = <20>;
                |            qcom,mdss-dsi-v-back-porch = <20>;
                |            qcom,mdss-dsi-v-pulse-width = <2>;
                |        };
                |    };
                |};
            """.trimMargin()
        )

        // 初始状态：包含 60Hz 和 120Hz 两个档位
        var candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(2, candidates.size)
        assertEquals(listOf(60, 120), candidates.map { it.currentHz })

        // 步骤 1：编辑修改 timing1 (120Hz -> 144Hz)
        val timing1Cand = candidates.first { it.currentHz == 120 }
        val editResult = DtsTimingPatcher.patch(
            candidate = timing1Cand,
            targetHz = 144,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.OVERWRITE_EXISTING
        )
        file.writeText(editResult.text)
        candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(2, candidates.size)
        assertEquals(listOf(60, 144), candidates.map { it.currentHz })

        // 步骤 2：基于 144Hz 档位克隆新增一个 165Hz 档位
        val timing144Cand = candidates.first { it.currentHz == 144 }
        val appendResult = DtsTimingPatcher.patch(
            candidate = timing144Cand,
            targetHz = 165,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.APPEND_NEW
        )
        file.writeText(appendResult.text)
        candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(3, candidates.size)
        assertEquals(listOf(60, 144, 165), candidates.map { it.currentHz })

        // 步骤 3：删除 60Hz 档位（原 native-mode 所指节点）
        val timing60Cand = candidates.first { it.currentHz == 60 }
        val deleteResult = DtsTimingPatcher.patch(
            candidate = timing60Cand,
            targetHz = 0,
            strategy = PatchStrategy.BALANCED_BLANKING_TIME,
            mode = PatchMode.DELETE_EXISTING
        )
        file.writeText(deleteResult.text)
        candidates = DtsTimingPatcher.analyzeEntry(0, file)
        assertEquals(2, candidates.size)
        assertEquals(listOf(144, 165), candidates.map { it.currentHz })
        // 验证 native-mode 不再指向被删除的 timing0，而是被自动重定向
        assertTrue(!deleteResult.text.contains("&timing0"))
        assertTrue(deleteResult.text.contains("native-mode = <&timing1>;"))
    }
}

