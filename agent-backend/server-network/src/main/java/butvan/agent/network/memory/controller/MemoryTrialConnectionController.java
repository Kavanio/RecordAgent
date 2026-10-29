package butvan.agent.network.memory.controller;

import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.memory.client.HindsightTrialService;
import butvan.agent.network.memory.service.MemoryService;
import butvan.agent.network.memory.dto.MemoryTrialDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 真实聊天试用连接入口；不接受用户身份；允许显式配置远端 Bank ID，不把配置保存当成用途授权。 */
@RestController
@RequestMapping("/agent/memory/trial-connection")
@RequiredArgsConstructor
public class MemoryTrialConnectionController {
    private final HindsightTrialService service;
    private final MemoryService memory;
    @GetMapping @ApiLog("读取真实聊天记忆试用连接摘要")
    public Result<Connection> connection() { return Result.success(present(service.connection())); }
    @PutMapping @ApiLog("显式检查并保存真实聊天记忆试用连接")
    public Result<Connection> connect(@RequestBody Connect input) {
        String automatic = "ra-" + memory.settings().spaceId();
        Connect normalized = input != null && input.bankId() != null && input.bankId().strip().equals(automatic)
                ? input.withBankId("") : input;
        return Result.success(present(service.connect(normalized)));
    }
    private Connection present(Connection value) {
        return value.bankId() == null || value.bankId().isEmpty()
                ? value.withBankId("ra-" + memory.settings().spaceId()) : value;
    }
}
