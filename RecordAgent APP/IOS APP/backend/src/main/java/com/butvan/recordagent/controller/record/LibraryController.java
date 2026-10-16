package com.butvan.recordagent.controller.record;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.record.LibraryDtos;
import com.butvan.recordagent.security.AuthenticatedUser;
import com.butvan.recordagent.service.record.LibraryService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 资料列表与正文按不同入口读取，全部依赖已认证身份。 */
@RestController
@RequestMapping("/api/v1/library")
@RequiredArgsConstructor
public class LibraryController {
    private final LibraryService library;

    @ApiLog("分页读取本人资料")
    @GetMapping
    public Page<LibraryDtos.Item> page(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "") String type,
            @RequestParam(defaultValue = "false") boolean favorite,
            @RequestParam(defaultValue = "false") boolean trash,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit) {
        return library.page(user.userId(), type, favorite, trash, query, offset, limit);
    }

    @ApiLog("读取本人资料正文")
    @GetMapping("/{id}")
    public LibraryDtos.Document detail(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return library.document(user.userId(), id);
    }

    @ApiLog("创建资料")
    @PostMapping
    public LibraryDtos.Document create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody LibraryDtos.Save s) {
        return library.create(user.userId(), s);
    }

    @ApiLog("更新资料")
    @PutMapping("/{id}")
    public LibraryDtos.Document update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody LibraryDtos.Save s) {
        return library.update(user.userId(), id, s);
    }

    @ApiLog("更新资料收藏与回收状态")
    @PatchMapping("/{id}/flags")
    public LibraryDtos.Document flags(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id,
            @Valid @RequestBody LibraryDtos.Flags s) {
        return library.flags(user.userId(), id, s);
    }
}
