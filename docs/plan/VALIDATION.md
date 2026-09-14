# 验证与验收规格

**本文给出规划中的验收门槛，不是实测成绩，也不是行业标准。** 本轮没有实现以下测试。开发时按任务卡生成样例、测试和报告；没有证据的项目保持未通过。CONTRACTS负责字段语义，本文负责检查与数值判定。

## 1. 验证层级与环境

| 层级 | 可验证内容 | 不能据此声称 |
|---|---|---|
| L0 文档/静态 | 链接、接口一致性、语法/静态编译问题 | 训练、APK、真机或精度通过 |
| L1 host测试 | 纯Python/Kotlin/C的协议、标签、坐标、状态、文件事务 | Android权限、GPU驱动、root截图可用 |
| L2 构建与模型探针 | NDK/Gradle构建、真实导出和runtime加载 | 检测精度达标、持续15fps |
| L3 K70离线/诊断 | 本地图片CPU/GPU、root低频截图、SDK恢复 | 实时性能或全距离精度达标 |
| L4 独立测试集/持续运行 | 固定精度门槛、REALTIME有效帧率与帧龄 | 未覆盖ROM/设备同样兼容 |

所有报告记录源码commit、设备/ROM、profile、模型id/hash、数据版本、依赖锁、后端、命令和时间。纯语法检查不伪装成测试套件；没有真实模型时，合成张量只能验证解析，不能替代模型与引擎验收。

## 2. 共同fixture规范

后续目录 `contracts/fixtures/` 仅存小型确定性样例与说明；真实游戏图像保存在版本化数据目录，fixture manifest引用hash。共同fixture由一份定义生成/读取，Native与Android不得各自造出互不比较的“正确样例”。

| fixture设计名 | 内容与预期 |
|---|---|
| `frame_v2_rg_2x1` | CONTRACTS C01的2×1红绿帧，64字节头+8字节像素；C/Kotlin逐字节一致 |
| `frame_v2_padded_source` | 原始帧每行有padding，规范化后仅保留有效RGBA；下一行首像素不偏移 |
| `frame_v2_chunks` | 在每个头边界及像素边界分段，接收结果与完整输入相同 |
| `frame_v2_invalid` | magic/version/长度/宽高/stride/format/rotation/时间/ID逐项破坏；分配前拒绝 |
| `model_nms_rows` | 有目标、score0 padding、class0、低分、非有限值、错class、纯padding、越界各记录 |
| `letterbox_3200x1440` | S960、left0/top264、sx/sy0.3；规范化0.45/0.55框中心1600/720 |
| `letterbox_odd_portrait` | 奇数宽高、portrait、右下多1px padding、sx≠sy；golden保存精确resize与坐标参数 |
| `lifecycle_sequence` | 有目标→真实空帧→断流→恢复→TTL→stop，配受控BOOTTIME时钟 |
| `model_update_failpoints` | 下载/校验/rename/指针提交/重载各阶段中断，active始终是完整可用版本 |

数值fixture覆盖左右边界和最后一个有效像素，不能只测屏幕中心。每个fixture保存版本、来源、期待错误code或数值、是否仅用于host解析测试。

## 3. 构建与数据测试

| ID | 输入/操作 | 通过条件 |
|---|---|---|
| ENV01 | 干净检出+锁定JDK/Gradle/SDK/NDK/Python环境 | 无隐藏本地文件依赖；记录全部版本和校验和 |
| ENV02 | NDK C11严格警告构建；独立编译两种已支持后端 | 无未声明函数/格式错误/漏链接；arm64/API28产物，错误明确非零 |
| ENV03 | Gradle test/lint/assemble；不提供模型、无root | APK构建/启动成功，UI明确缺模型/root；不自动崩溃或请求占位网络 |
| ENV04 | host模拟adb/远端文件系统，测试纯build与显式deploy参数/事务 | build不访问adb；缺serial或校验失败拒绝；事务回滚正确。真实root部署留在G4，不作为G1前置 |
| DATA01 | 合法标签、class非0、NaN/Inf、负宽高、出界、错列数 | 合法通过；非法失败并精确到文件/行/规则，不静默裁剪标签 |
| DATA02 | 有图缺标、批准空标、孤立标注、大小写/扩展名变化 | 明确区分缺标和负样本；配对不多出images层；孤立项被报告 |
| DATA03 | 同一对局/邻帧/重复图混入多个split | 精确重复和group泄漏阻断；近重复人工复核清单可追溯 |
| DATA04 | manifest中uncertain、腐坏图、非SDR或未审标签 | 隔离或拒绝；不当作空负样本进入训练/测试 |
| DATA05 | 相同seed/清单重复split；修改一张输入 | 同样输入划分完全一致；改动产生新hash/版本，不覆盖冻结test |
| DATA06 | train少量真实样例，MPS/CPU及可用CUDA选项 | 模型来源、转移参数覆盖、设备与run-id记录；有效loss/权重；不覆盖旧run |

## 4. 模型合同与数值一致性

| ID | 输入/操作 | 通过条件 |
|---|---|---|
| MODEL01 | 候选模型包、缺sidecar、hash错、未知schema/dtype/layout | 仅合同完全匹配者加载；错误code明确，不猜格式 |
| MODEL02 | 实际导出文件读取输入/输出tensor和量化参数 | 单输入[1,S,S,3]float32、单输出[1,300,6]float32，与sidecar一致 |
| MODEL03 | C03黄金色块/边界/照片预处理，Python与Android输出tensor | 几何参数精确一致；最大通道绝对误差≤2/255、平均≤0.25/255；失败先统一resize实现，不放宽模型比较掩盖 |
| MODEL04 | 同一保存好的NHWC输入分别送PyTorch部署适配、桌面TFLite、Android CPU | 排除预处理差异后达到下列同图匹配门槛；NMS配置一致 |
| MODEL05 | 同一保存输入Android CPU↔GPU | 实际后端日志正确；同图容差通过；回退不能冒充GPU运行 |
| MODEL06 | NMS六字段合成输出class0、score0、纯padding、越界 | 正确读score/class、仅一次NMS、去padding、按实际sx/sy映射并裁剪 |
| MODEL07 | 非有限/错class输出、输入size错误、动态/量化I/O | 加载期shape/dtype不兼容立即拒绝；运行期坏输出清latestFrame，连续3次错误进入ERROR，不把错误当无目标 |
| MODEL08 | 不存在/不兼容GPU、初始化失败、invoke失败 | AUTO专用线程完整释放、CPU重建一次；GPU_REQUIRED直接失败，CPU模式不尝试GPU；无并行Interpreter使用 |
| MODEL09 | 20次加载→推理→释放与一次模型热切换 | 线程归属正确，句柄/堆占用无持续上升；旧实例不再被调用 |

一致性样本至少50张批准截图，覆盖横竖屏、各尺寸桶、空场景、边角；同输入每引擎重复3次。数值验证和语义精度验证分别报告。

**同图匹配规则**：同类框做IoU最大的一对一匹配；稳定候选定义为参考score距运行阈值≥0.05且不存在IoU接近NMS阈值±0.02的竞争框。稳定候选匹配率≥99%，CPU框边最大差≤0.5个模型输入像素、score绝对差≤0.02；GPU对应≤1输入像素、≤0.03。低于4px的框另核对中心差，不能因IoU不稳定把全部tiny结果排除。所有不匹配、阈值/NMS边界候选完整输出对照，不计入稳定候选门槛的数量及理由单列。

仅在数值门槛通过且误差不集中于tiny桶时，模型包晋升为可测试发布候选。用随机输入跑通只通过加载/算子smoke，不通过MODEL04/05或精度验收。

## 5. Native与协议测试

| ID | 输入/操作 | 通过条件 |
|---|---|---|
| FRAME01 | C编码、Kotlin解码共同2×1fixture | 头+负载逐字节一致，红/绿不交换，无native-endian依赖 |
| FRAME02 | 宽/高0、4097、u32极大值，payload不符、未知版本 | 在分配前拒绝；无整数溢出、负数组、OOM；版本不符立即ERROR |
| FRAME03 | 逐字节读写、EINTR/EAGAIN、部分writev | 一帧完整重组；总deadline不会被碎片刷新 |
| FRAME04 | 头中断、半帧断开、对端关闭、慢客户端 | 无SIGPIPE进程死亡；帧不交付；超时/断连后新stream可恢复 |
| FRAME05 | 源stride大于width×4、RGBX/BGRA/RGB565及未知格式 | 支持格式色块正确、alpha规范化；未知格式明确拒绝；不发送padding |
| FRAME06 | 已锁定raw布局后截断4字节；损坏/PNG头冒充raw | 拒绝，不能改猜12/16字节头继续读；失败计数可观察 |
| FRAME07 | 生产30fps/消费1fps，或推理模拟300ms | 队列容量固定，旧槽被替换并计数；不截断在途帧；恢复后收到最新完整帧 |
| FRAME08 | 新连接仍送旧stream、frame倒退/重复、时间跳变 | 拒绝或按合同终止；身份/时钟错误明确；不发布旧结果 |
| FRAME09 | STARTING预检中、无客户端、正在capture、正在send时SIGTERM | 200ms内观察stop信号；普通情况2s内优雅退出，必要时总3s停止本实例；子进程全部回收 |
| FRAME10 | 主显示0/90/180/270、尺寸变更、capture中generation改变 | raw/PNG对照、方向正确；变化时弃在途、断连接，新stream首帧与控制一致 |
| FRAME11 | 固定校准页raw与PNG/实际屏幕边角对照 | RGB/行/尺度/方向正确；支持的数据空间转换为sRGB；触控坐标另做显式测试 |
| FRAME12 | 第二个daemon、无权客户端、进程退出后的stale文件 | 不误unlink活实例；身份/锁校验；只清理确认属于自身的资源 |

## 6. SDK、权限和更新测试

| ID | 输入/操作 | 通过条件 |
|---|---|---|
| SDK01 | 同配置连续start；不同配置start；start中stop | 同配置幂等，不同配置BUSY；stop取消旧任务，无延时bind复活 |
| SDK02 | 正常绑定与bind失败、Service死亡 | 合法IBinder、唯一snapshot来源；失败可见，消费者不永久等待无错误流 |
| SDK03 | 检测有目标→真实空帧→断流 | 空帧有新frameId且detections=[]；断流latest=null+typed状态，二者不混淆 |
| SDK04 | REALTIME结果到250ms、DIAGNOSTIC到5000ms | 到期清latest；兼容observe发空；定时任务不会清掉更新revision结果 |
| SDK05 | 推理中发生stop/重连/display改变/model切换 | 旧session/stream/generation结果丢弃，已回收buffer不被后续使用 |
| SDK06 | root拒绝、未安装daemon、权限/FGS不允许、版本错 | 首次启动按profile期限返回明确错误；无无限授权/重连循环 |
| SDK07 | daemon晚启动、退出、持续断流30s | 有界退避/一次受控重启，耗尽ERROR；stop立即中断等待 |
| SDK08 | 50轮start/运行/stop，含快速操作和锁屏 | 无资源持续增长、残留进程、过期通知或错误RUNNING；唤醒后用户显式重启 |
| SDK09 | 假解释器永久不返回 | stop不跨线程close，超时隔离worker/STOP_TIMEOUT、拒绝再次start；普通停止仍通过2s/3s规则 |
| SDK12 | 杀App进程后保留daemon，再显式start | 用C08旧owner身份精准清理并新建会话；旧App仍活着/身份不同则BUSY，不永久卡死也不接管其他实例 |
| SDK13 | 截图能力/配置失败与无结构化记录的突然崩溃 | 完整FAILED记录按C06准确分类；仅无记录崩溃才DAEMON_EXITED；stdout逐行有界且持续被读 |
| SDK10 | 未root K70选择图片、CPU/GPU离线推理 | 可返回该图坐标/后端/耗时；不调用su、不伪造live帧、不要求截图权限 |
| SDK11 | 显式tap/swipe，非法坐标/未root | 显式 tap/swipe 只响应调用方请求，域外坐标拒绝；`moveCenter` 需显式开启 |
| UPDATE01 | 无update配置、不可达服务器 | 本地正常启动；无占位请求，无阻塞检测 |
| UPDATE02 | 合法新模型+sidecar | 两文件完整校验，stage不改变active；activate核验/预热后原子提交 |
| UPDATE03 | HTTP错误、超时、超大小、hash错、错schema/modelVersion | 拒绝候选，旧active未改变，暂存可清理 |
| UPDATE04 | GPU/CPU新模型初始化或首帧失败 | 重载旧模型并返回RolledBack；旧模型也不可用才ERROR |
| UPDATE05 | 每个持久化步骤杀进程后重启 | 只加载完整已验证active；无半包、无损坏version文件，staging不自动激活 |
| UPDATE06 | 更新中stop、并发两次update、同版本不同hash | 更新互斥且可取消，版本不可变规则生效；不双实例并发激活 |
| RELEASE01 | 从交付清单重建APK/AAR并新安装 | 示例工程仅依赖AAR即能离线/在线使用；版本/hash/协议匹配，清晰安装步骤 |

## 7. 独立精度门槛

### 数据前提与匹配

正式test至少300帧、10个独立对局，其中至少100帧明确无敌人的负样本；各模型输入短边桶至少100个enemy框、来自至少3个test对局。分桶以发布模型的LetterBox实际sx/sy换算后 `min(boxWidth,boxHeight)`，同时报告原图尺寸。采集总量由覆盖反推，1000张本身不保证足够。

test在运行候选前冻结；模型/阈值比较使用val。若已根据test错误反复调参，必须新增封存对局建立下一版test，再作正式交付判定。样本不足的桶标“证据不足”，不能用合并桶隐藏。

检测质量主口径：同类预测与ground truth按置信度降序、一对一IoU≥0.5匹配；未匹配预测计FP、未匹配标签计FN。跨桶FP按预测框在模型输入的短边归桶，recall按GT桶；另报整体precision避免只看某桶。AP/mAP可附带，但不能替代固定操作阈值门槛。

运行阈值从val的0.05到0.95、步长0.01网格选取：先满足precision/空帧误检预算，再最大化各桶宏平均recall，平局选更高precision、再选更高阈值；验证集无可行阈值则返回训练迭代，不在test上挑阈值。正式配置记录阈值，NMS保持导出IoU0.5。

### v1建议交付标准（规划值，全部同时满足）

| 指标 | 门槛 |
|---|---:|
| 全量precision | ≥0.95 |
| 全量recall | ≥0.80 |
| 输入短边≥16px recall | ≥0.90 |
| 输入短边8–16px recall | ≥0.85 |
| 输入短边4–8px recall | ≥0.75 |
| 输入短边<4px recall | ≥0.60 |
| 明确空帧平均误检数 | ≤0.05框/帧 |
| 明确空帧中出现误检的帧占比 | ≤5% |
| 已匹配框中心误差p95，按模型输入像素计算 | ≤max(2px, GT框对角线×10%)；逐框归一化后判定，另报原屏幕像素误差 |

中心误差门槛解释：对每个匹配框计算 `e / max(2,0.1×diagonal)`，其p95≤1；未匹配框仍计FN，不能用只看匹配框中心误差掩盖漏检。tiny同时报告IoU失败但中心距离较近的数量，作为诊断，**不能替代主recall门槛**。

fp16候选还须相对同checkpoint的PyTorch部署参考：全量precision/recall各下降≤1个百分点，各尺寸桶recall下降≤2个百分点，并同时满足绝对门槛。INT8准入同样遵守TRAINING与TASKS X01的相对退化/性能收益限制，不因文件变小自动晋升。

这些门槛是为了给开发提供明确目标，不代表已证明K70/当前模型能达到。低于4px的目标仍是必测和必交范围；如果达不到，按TRAINING的复核/补样/分辨率/结构/ROI路线迭代，最终仍失败则v1精度不通过，不以“只能看到大目标”宣称全距离完成。

报告同时给每项分子/分母、按对局bootstrap置信区间（建议1000次重采样）及失败样例；判定使用上述点估计与最低样本覆盖，置信区间用于说明不确定性，不能只报百分比而不报样本量。

## 8. K70持续性能标准

### 固定测试方式

首机记录Android/ROM/root、显示/实际帧尺寸、SDR设置、游戏地图与图形/帧率、模型hash、后端、线程数、profile、供电、室温。正式测量固定REALTIME，warmup2分钟后连续30分钟，覆盖空场景、多目标、移动/旋转、近/中/远样例；完整原始指标保留，不能删去慢帧。

同时做同设置游戏未运行检测的10分钟基线，用于评估共存影响。外接电源状态在两次测试中保持一致；性能测试至少重复2次，均通过。实测温度可使用设备可读传感器，缺传感器注明缺失，但必须报告热状态与首尾吞吐。

| 指标 | v1门槛与计算 |
|---|---|
| 有效结果FPS | 每个非重连/用户主动暂停的连续60s窗口≥14.5；未到期真实推理帧数/墙钟时长；空检测帧计入，重放/过期/丢弃不计 |
| 运营帧龄 | resultNs-captureStartNs，p95≤150ms、p99≤250ms；所有结果在发布时≤250ms |
| 不隐藏中断 | 同时报告包含全部重连/自动恢复时间的30分钟总FPS；总有效FPS≥14.0，异常中断累计≤测试时长1% |
| 稳定性 | 0崩溃/ANR/协议损坏/无界积压；意外ERROR为0；所有丢帧有计数与原因 |
| 首尾退化 | 最后5分钟有效FPS相对首次稳定5分钟下降≤10%，且仍满足以上窗口门槛 |
| App内存 | K70上稳态PSS≤768MiB，峰值≤1GiB；30分钟后段相对warmup后稳定点净增长≤50MiB |
| Native内存 | K70常驻daemon稳态PSS≤192MiB；CLI子进程/适配helper单列并报告合计峰值；无残留子进程 |
| 游戏共存 | 游戏FPS相对独立基线下降≤10%；若游戏FPS不可读，记录可复核外部测量方案，缺该证据不宣称共存验收完成 |
| 停止 | 正常2s内资源回收；强制自有daemon流程总3s内给出成功/明确STOP_TIMEOUT；50次启停无累积泄漏 |

每阶段p50/p95/p99必须报告，**不把各阶段p95相加当作端到端p95**。建议优化预算为采集/转换p95≤25ms、Socket接收≤15ms、预处理≤10ms、推理≤50ms、后处理/发布≤5ms；这些是定位预算而非额外硬门槛，最终依据真实吞吐和帧龄。采集、I/O、推理可流水并行，因此周期和单帧全程时延分别验收。

高帧率backend独立验证要求持续交付≥15个成功帧/秒，采集/归一化p95≤25ms且功能fixture/方向/格式通过。达不到时继续Native适配或模型对照，不用CLI诊断数据充当实时截图证明。

### 失败后的固定决策顺序

1. 先按时间戳定位采集、I/O、预处理、推理或积压的主瓶颈；修复错误计时/无界队列后重测。
2. 采集不达标→T21版本匹配适配；传输/分配瓶颈→缓冲复用、减少无效复制，保留合同语义；GPU未委派或失败→记录CPU回退并评估转换/算子组合。
3. 模型推理瓶颈→同数据对比s-P2/n-P2与640/960/1280/1600中有理由的候选，先较小模型960和s-P2 640；更高分辨率只在tiny召回受限时增加。每次改模型都重跑精度门槛。
4. 只有fp16基线完整，才进入X01 INT8；只有全图缩放被证实为tiny主因，才进入X02 ROI。模型/裁切方案不得以局部区域15fps冒充全屏更新15fps。
5. 没有方案同时通过精度/性能时，记录各候选的精度—延迟表、瓶颈与下一实验；“集成原型”可保留，“v1可交付”保持失败，不未经说明降低门槛。

## 9. 阶段门禁与证据清单

| 门禁 | 对应阶段 | 必需证据 |
|---|---|---|
| G1 | M1 | ENV01–04；编译/打包日志、无模型启动截图、工具链锁 |
| G2 | M2 | DATA01–06、MODEL01/02桌面部分；数据版本与规则、加载/导出日志、候选模型包 |
| G3 | M3 | MODEL01/02的Android部分、MODEL03–09、SDK10；黄金对照报告、K70实际CPU/GPU、输出语义与线程证明 |
| G4 | M4 | FRAME01–12、SDK01–13；设备probe、raw/PNG校准、DIAGNOSTIC启停/恢复日志 |
| G5 | M5 | 独立精度全部门槛、两次REALTIME30分钟报告、游戏共存基线；无未解释错误 |
| G6 | M6 | UPDATE01–06、RELEASE01；AAR示例安装、版本清单、回滚/重启证据 |

训练/转换在独立环境执行的报告要和Android端模型SHA一致。每次只做文档修改无需重跑全部实现测试；实现变更按受影响合同选择回归，发布候选跑全部门禁。

计划中的证据位置：`artifacts/<release-id>/reports/{environment,data,conversion,parity,native,sdk,accuracy,performance,update,release}/`。每份报告都包含PASS/FAIL/NOT_RUN、原因与可复现入口。版本发布清单中NOT_RUN不等于PASS。
