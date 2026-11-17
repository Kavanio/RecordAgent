package butvan.agent.network.material.controller;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.network.annotation.ApiLog;
import butvan.agent.network.common.Result;
import butvan.agent.network.material.dto.ChatMaterialDtos;
import butvan.agent.network.material.dto.ChatMaterialDtos.ChatMaterialPageResponse;
import butvan.agent.network.material.dto.ChatMaterialDtos.ChatMaterialResponse;
import butvan.agent.network.material.dto.ChatMaterialDtos.ChatMaterialSummaryResponse;
import butvan.agent.network.material.service.ChatMaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** 对话“档案”模块 HTTP 协议适配层。 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/agent/materials")
@RequiredArgsConstructor
public class ChatMaterialController {

    private final ChatMaterialService materialService;
    private final CurrentUserProvider currentUserProvider;

    @ApiLog("分页查询对话档案列表")
    @GetMapping
    public Result<ChatMaterialPageResponse> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String subType,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String sessionKind,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "40") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return Result.success(ChatMaterialDtos.from(materialService.list(
                owner(), category, subType, channel, sessionKind, sessionId, from, to, query, limit, offset
        )));
    }

    @ApiLog("查询对话档案分类统计")
    @GetMapping("/summary")
    public Result<ChatMaterialSummaryResponse> summary() {
        return Result.success(ChatMaterialDtos.from(materialService.summary(owner())));
    }

    @ApiLog("查询单个对话档案详情")
    @GetMapping("/{fileId}")
    public Result<ChatMaterialResponse> get(@PathVariable String fileId) {
        return Result.success(ChatMaterialDtos.from(materialService.get(owner(), fileId)));
    }

    @ApiLog("读取对话档案文件内容")
    @GetMapping("/{fileId}/content")
    public ResponseEntity<Resource> content(
            @PathVariable String fileId,
            @RequestParam(defaultValue = "false") boolean download
    ) {
        var material = materialService.get(owner(), fileId);
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(material.originalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(material.mediaType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(materialService.openContent(owner(), fileId));
    }

    @ApiLog("删除对话档案文件")
    @DeleteMapping("/{fileId}")
    public Result<String> delete(@PathVariable String fileId) {
        materialService.delete(owner(), fileId);
        return Result.success("档案素材已删除");
    }

    private String owner() {
        return currentUserProvider.currentUserId();
    }
}
