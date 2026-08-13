# 曝光计算设计

## 1. 目标

本文档定义 Android 胶片测光 App V1 的曝光计算逻辑。

测光模块负责从实时预览中估算当前场景的 `EV100_metered`；曝光计算模块负责结合用户设置的胶片 `ISO`、曝光补偿、1/3 档光圈表和经典整档快门表，生成：

- 一组主推荐曝光组合。
- 多组等效曝光组合。

核心输入：

- `EV100_metered`：测光模块输出的 ISO 100 基准 EV。
- `ISO`：用户选择的胶片 ISO。
- `EC`：曝光补偿，单位 EV。
- `apertureStops`：可用光圈档位。
- `shutterStops`：可用快门档位。
- `frameFormat`：画幅，例如 135、6x4.5、6x6。
- `focalLength`：模拟焦段，例如 35mm、50mm、70mm、75mm。

核心输出：

- `EV_target`：用于匹配快门/光圈组合的目标 EV。
- `primary`：主推荐曝光组合。
- `equivalents`：多组等效曝光组合。

## 2. 核心定义

摄影常规定义：

```text
EV100 = log2(N^2 / t)
```

其中：

- `N`：光圈值，例如 `2.8`、`4`、`5.6`。
- `t`：快门时间，单位秒，例如 `1/125`。

在任意 ISO 下，曝光组合需要匹配的目标 EV 为：

```text
EV_target = EV100_metered + log2(ISO / 100) - EC
```

其中：

- `ISO`：用户选择的胶片 ISO。
- `EC`：曝光补偿，单位 EV。

曝光补偿使用减号：

- `+1EV` 表示用户希望照片更亮，需要增加曝光量，因此目标 EV 降低。
- `-1EV` 表示用户希望照片更暗，需要减少曝光量，因此目标 EV 提高。

示例：

```text
EV100_metered = 12
ISO = 400
EC = +1

EV_target = 12 + log2(400 / 100) - 1
EV_target = 12 + 2 - 1
EV_target = 13
```

因此需要寻找满足以下条件的快门/光圈组合：

```text
log2(N^2 / t) ~= 13
```

## 3. Av / Tv 分解

曝光组合可以拆成两个曝光级数：

```text
Av = log2(N^2)
Tv = log2(1 / t)
EV = Av + Tv
```

含义：

- `Av`：光圈值对应的曝光级数。
- `Tv`：快门值对应的曝光级数。
- `EV`：该组快门/光圈组合对应的曝光级数。

示例：

```text
f/8, 1/125s

Av = log2(8^2) = 6
Tv = log2(125) ~= 7
EV ~= 13
```

所以 `f/8 + 1/125s` 是 `EV13` 附近的曝光组合。

## 4. 曝光档位表

V1 不应在计算逻辑中散落硬编码档位，建议维护标准快门表和光圈表。

每个档位预先保存：

```text
displayName
numericValue
evValue
```

### 4.1 光圈 1/3 档表

V1 建议使用以下光圈档位：

```text
f/1.0
f/1.1
f/1.2
f/1.4
f/1.6
f/1.8
f/2.0
f/2.2
f/2.5
f/2.8
f/3.2
f/3.5
f/4
f/4.5
f/5
f/5.6
f/6.3
f/7.1
f/8
f/9
f/10
f/11
f/13
f/14
f/16
f/18
f/20
f/22
```

数据结构示例：

```kotlin
data class ApertureStop(
    val displayName: String,
    val value: Double,
    val av: Double
)
```

计算：

```text
av = log2(value * value)
```

### 4.2 快门候选表

V1 建议使用以下快门档位：

```text
1/2000
1/1000
1/500
1/250
1/125
1/60
1/30
1/15
1/8
1/4
1/2
1s
2s
4s
8s
```

快门只使用以上经典整档候选，不继续拆分 1/3 档。

数据结构示例：

```kotlin
data class ShutterStop(
    val displayName: String,
    val seconds: Double,
    val tv: Double
)
```

计算：

```text
tv = log2(1.0 / seconds)
```

## 5. 组合匹配算法

输入：

```text
EV100_metered
ISO
EC
availableApertures
availableShutters
```

先计算目标 EV：

```text
EV_target = EV100_metered + log2(ISO / 100) - EC
```

然后遍历所有光圈和快门组合：

```text
pairEV = aperture.av + shutter.tv
error = abs(pairEV - EV_target)
```

保留误差足够小的组合：

```text
error <= 1/6EV
```

使用 `1/6EV` 作为容差，是因为档位步进为 `1/3EV`，最近档位的最大理论误差约为半档步进。

如果没有组合满足容差，则选择误差最小的若干组作为近似组合，避免 UI 无结果。

## 6. 等效组合排序

等效曝光组合建议按快门从快到慢排序：

```text
1/1000
1/500
1/250
1/125
1/60
1/30
```

这样更符合拍摄决策路径：用户可以先判断是否满足手持安全快门，再决定是否接受更大光圈或更慢快门。

也可以在 UI 层提供另一种排序：

```text
光圈从大到小
```

但 V1 算法输出默认使用快门优先排序。

## 7. 主推荐组合选择

等效曝光组合可能有多组，需要从中选择一组主推荐。

V1 使用安全快门优先策略。

安全快门规则：

```text
20mm ~ 35mm：不慢于 1/30s
36mm ~ 50mm：不慢于 1/60s
51mm ~ 90mm：不慢于 1/125s
91mm ~ 120mm：不慢于 1/250s
```

主推荐选择流程：

```text
1. 从等效组合中筛选快门不慢于安全快门的组合。
2. 在筛选结果中优先选择常用光圈。
3. 如果多组都满足，选择误差最小的组合。
4. 如果误差也相同，选择更接近中间光圈的组合。
5. 如果没有组合满足安全快门，则选择误差最小且快门最快的组合。
```

V1 常用光圈优先级：

```text
f/5.6
f/8
f/4
f/11
f/2.8
f/16
```

该优先级偏向胶片日常拍摄中更常用、成像更稳定的中间光圈。

## 8. 输出数据结构

推荐 Kotlin 数据结构：

```kotlin
data class ExposureRecommendation(
    val ev100Metered: Double,
    val evTarget: Double,
    val iso: Int,
    val exposureCompensation: Double,
    val primary: ExposurePair,
    val equivalents: List<ExposurePair>
)

data class ExposurePair(
    val apertureLabel: String,
    val aperture: Double,
    val shutterLabel: String,
    val shutterSeconds: Double,
    val ev: Double,
    val error: Double
)
```

如果后续需要支持光圈优先或快门优先模式，可以扩展：

```kotlin
enum class ExposureMode {
    PROGRAM,
    APERTURE_PRIORITY,
    SHUTTER_PRIORITY
}
```

V1 先实现 `PROGRAM`，即自动推荐多组等效组合。

## 9. UI 展示建议

UI 可以展示：

```text
EV100: 12.0
ISO: 400
曝光补偿: +1.0EV
目标 EV: 13.0

主推荐:
f/8  1/125

等效曝光:
f/4    1/500
f/5.6  1/250
f/8    1/125
f/11   1/60
f/16   1/30
```

其中：

- `EV100` 来自测光模块。
- `目标 EV` 用于解释 ISO 与曝光补偿对结果的影响。
- `主推荐` 用大字号展示。
- `等效曝光` 用列表展示。

## 10. 示例计算

输入：

```text
EV100_metered = 12
ISO = 400
EC = +1
frameFormat = 135
focalLength = 50mm
```

计算：

```text
EV_target = 12 + log2(400 / 100) - 1
EV_target = 12 + 2 - 1
EV_target = 13
```

可能的等效组合：

```text
f/4    1/500
f/5.6  1/250
f/8    1/125
f/11   1/60
f/16   1/30
```

当前选择 `135` 画幅且焦距为 `50mm`，安全快门不慢于 `1/60s`。

候选组合：

```text
f/4    1/500
f/5.6  1/250
f/8    1/125
f/11   1/60
```

根据常用光圈优先级，主推荐：

```text
f/5.6  1/250
```

如果希望更偏保守，也可以选择 `f/8 1/125`。V1 需要在实现时固定一种策略，建议使用上文的常用光圈优先级，保持行为可预测。

## 11. 边界处理

### 11.1 EV 超出档位范围

如果场景过亮或过暗，当前快门/光圈表可能无法覆盖目标 EV。

处理方式：

```text
1. 返回最接近目标 EV 的组合。
2. 标记 outOfRange = true。
3. UI 提示用户：
   - 场景过亮：需要 ND、缩小光圈、提高快门。
   - 场景过暗：需要三脚架、更大光圈、更高 ISO。
```

### 11.2 ISO 非标准值

V1 推荐 ISO 使用标准列表：

```text
25, 50, 64, 100, 125, 160, 200, 250, 320, 400, 500, 800, 1000, 1600, 3200
```

如果后续允许用户输入任意 ISO，计算公式仍然成立：

```text
EV_target = EV100_metered + log2(ISO / 100) - EC
```

但 UI 展示应保留一位或两位小数，避免出现过多噪声。

### 11.3 曝光补偿边界

V1 曝光补偿范围：

```text
-3EV ~ +3EV
```

步进：

```text
1/3EV
```

内部使用 `Double`，UI 展示使用：

```text
+0.3EV
+0.7EV
+1.0EV
-0.3EV
```

也可以展示为传统分数：

```text
+1/3EV
+2/3EV
+1EV
```

V1 建议先用小数展示，实现更简单。

## 12. 单元测试建议

曝光计算模块应尽量保持纯函数，便于单元测试。

建议覆盖：

- `EV100_metered = 12, ISO = 100, EC = 0` 时，目标 EV 为 `12`。
- `EV100_metered = 12, ISO = 400, EC = 0` 时，目标 EV 为 `14`。
- `EV100_metered = 12, ISO = 400, EC = +1` 时，目标 EV 为 `13`。
- `EV_target = 13` 时，包含 `f/8 1/125`、`f/5.6 1/250`、`f/11 1/60` 等等效组合。
- 焦距为 `50mm` 时，主推荐快门不慢于 `1/60s`。
- EV 超出档位范围时，返回最接近组合并标记越界。

## 13. 实现模块建议

建议拆分为：

```text
ExposureStopTables
ExposureValueCalculator
ExposurePairGenerator
PrimaryRecommendationSelector
ExposureRecommendationFormatter
```

其中：

- `ExposureStopTables`：维护 1/3 档光圈表和经典整档快门表。
- `ExposureValueCalculator`：计算 `EV_target`、`Av`、`Tv`、`pairEV`。
- `ExposurePairGenerator`：遍历并生成等效曝光组合。
- `PrimaryRecommendationSelector`：根据画幅、焦段、安全快门和常用光圈选择主推荐。
- `ExposureRecommendationFormatter`：负责 UI 展示字符串格式化。
