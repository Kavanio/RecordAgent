package com.butvan.recordagent.service.task;

import com.butvan.recordagent.dto.query.Page;
import com.butvan.recordagent.dto.task.TaskDtos;
import java.util.*;

/** 手机通知的配置权威与观察回执。每个账户最多 32 个未来启用提醒。 */
public interface TaskService {
    /** 配置列表分页；关键词匹配由数据库完成。 */
    Page<TaskDtos.Task> page(UUID owner, String query, Boolean enabled, int offset, int limit);

    /** 读取当前本人配置，越权与不存在均返回 404。 */
    TaskDtos.Task detail(UUID owner, UUID id);

    /** 所有待注册的未来启用提醒，硬上限 32，供当前手机对账。 */
    List<TaskDtos.Task> enabled(UUID owner);

    /** 使用客户端 UUID 创建，相同草稿重试不会产生重复提醒。 */
    TaskDtos.Task create(UUID owner, TaskDtos.Save s);

    /** 修改/启停使用版本号，失效或越权资源不会更新。 */
    TaskDtos.Task update(UUID owner, UUID id, TaskDtos.Save s);

    /** 软删除保留已观察的历史。 */
    void delete(UUID owner, UUID id, long version);

    /** 只记录系统返回的已送达通知；事件 ID 幂等。不会因注册成功生成回执。 */
    void observe(UUID owner, UUID id, TaskDtos.Observation s);

    /** 分页读取设备观察回执，不承诺未打开应用期间的完整送达审计。 */
    Page<TaskDtos.Delivery> history(UUID owner, UUID id, int offset, int limit);
}
