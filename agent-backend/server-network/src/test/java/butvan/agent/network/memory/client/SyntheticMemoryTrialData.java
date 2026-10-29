package butvan.agent.network.memory.client;

import java.util.List;

/** 合成试跑唯一文本白名单；不会随环境变量或用户输入变化。 */
public final class SyntheticMemoryTrialData {
    private SyntheticMemoryTrialData() { }
    public static final List<String> SAMPLES = List.of(
            "纯虚构测试：小舟尝试把每日手记翻成英文来练习英语，目前仍在摸索，并没有掌握英语。",
            "纯虚构测试：小舟周末练习摄影，发现光线不足时照片容易拍糊，准备下次再尝试。",
            "纯虚构测试：小舟原本打算周六阅读，后来改成周日；这是计划调整，不代表已经读完。"
    );
    public static final List<String> QUERIES = List.of(
            "虚构人物小舟尝试怎样练英语？", "虚构人物小舟拍照时遇到了什么问题？", "虚构人物小舟的阅读计划有什么调整？"
    );
}
