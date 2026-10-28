package butvan.agent.network.memory.controller;

import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.memory.client.HindsightReadinessService;
import butvan.agent.network.memory.dto.MemoryDtos.ConnectionTestResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 显式连接检查的协议入口；地址和凭据只读取服务端受保护配置。 */
@RestController
@RequestMapping("/agent/memory")
@RequiredArgsConstructor
public class MemoryConnectionController {
    private final HindsightReadinessService service;

    /** 无请求字段，不探测 bank，不发送任何聊天或模型查询。 */
    @PostMapping("/connection-test") @ApiLog("只读检查经历记忆服务版本与就绪合约")
    public Result<ConnectionTestResponse> check() { return Result.success(service.check()); }
}
