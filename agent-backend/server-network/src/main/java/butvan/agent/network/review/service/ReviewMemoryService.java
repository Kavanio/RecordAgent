package butvan.agent.network.review.service;

import butvan.agent.network.review.dto.ReviewMemoryDtos.*;
import butvan.agent.network.review.repository.ReviewMemoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.Set;

/** 用户决定建议的采用与结果；模型和定时任务不能代替用户提交主观反馈。 */
@Service
@RequiredArgsConstructor
public class ReviewMemoryService {
    private final ReviewMemoryRepository repository;
    private final ObjectMapper mapper;
    private final Clock automationClock;
    private static final Set<String> DECISIONS = Set.of("ACCEPTED","DECLINED","DEFERRED","COMPLETED","PARTIAL","NOT_DONE","UNKNOWN","ARCHIVED");
    private static final Set<String> RESULTS = Set.of("COMPLETED","PARTIAL","NOT_DONE","UNKNOWN");
    private static final Set<String> ADOPTED = Set.of("ACCEPTED","COMPLETED","PARTIAL","NOT_DONE","UNKNOWN");

    /** 只读记忆列表，不运行模型或自动更新状态。 */
    @Transactional(readOnly=true)
    public MemoryView list(String owner) {
        ReviewPolicy.requireOwner(owner);
        return new MemoryView(repository.actions(owner),repository.observations(owner,automationClock.instant()));
    }
    /** 明确用户操作且匹配 revision，重复请求幂等返回。 */
    @Transactional
    public ActionView feedback(String owner,String id,FeedbackRequest input) {
        ReviewPolicy.requireOwner(owner);
        if(input==null || input.expectedRevision()<1 || !DECISIONS.contains(input.decision()==null?"":input.decision()))
            throw new IllegalArgumentException("请选择有效的建议反馈与版本");
        requestId(input.requestId());
        String fingerprint=ReviewPolicy.fingerprint(json(input));
        var replay=repository.response(owner,input.requestId(),id,fingerprint);
        if(replay.isPresent()) return read(replay.get(),ActionView.class);
        var old=repository.action(owner,id);
        if(old.revision()!=input.expectedRevision() || "ARCHIVED".equals(old.state()))
            throw new IllegalArgumentException("建议已变化或已归档，请刷新后重试");
        if(RESULTS.contains(input.decision()) && !ADOPTED.contains(old.state()))
            throw new IllegalArgumentException("请先明确采纳建议，再报告行动结果");
        String title=input.title()==null?old.title():text(input.title(),200);
        String criterion=input.criterion()==null?old.criterion():text(input.criterion(),300);
        if(title.isBlank() || criterion.isBlank()) throw new IllegalArgumentException("行动与检查标准不能为空");
        LocalDate check=input.checkOn()==null?old.checkOn():input.checkOn();
        if(check!=null && (check.isBefore(LocalDate.of(2000,1,1)) || check.isAfter(LocalDate.now(automationClock).plusYears(1))))
            throw new IllegalArgumentException("检查日期不在有效范围内");
        Instant now=automationClock.instant();
        repository.feedback(owner,old,input.decision(),title,criterion,check,text(input.feedback(),500),now,
                "DECLINED".equals(input.decision())?now.plus(Duration.ofDays(30)):null);
        var result=repository.action(owner,id);
        repository.remember(owner,input.requestId(),id,fingerprint,json(result),now);
        return result;
    }
    /** 纠正和否定立即停止该观察的自动召回，不写入聊天画像。 */
    @Transactional
    public ObservationView correct(String owner,String id,CorrectionRequest input) {
        ReviewPolicy.requireOwner(owner);
        if(input==null || input.expectedRevision()<1 || !Set.of("CORRECTED","REJECTED").contains(input.decision()==null?"":input.decision()))
            throw new IllegalArgumentException("请选择有效的观察处置与版本");
        requestId(input.requestId()); String fingerprint=ReviewPolicy.fingerprint(json(input));
        var replay=repository.response(owner,input.requestId(),id,fingerprint);
        if(replay.isPresent()) return read(replay.get(),ObservationView.class);
        var old=repository.observation(owner,id,automationClock.instant());
        if(old.revision()!=input.expectedRevision()) throw new IllegalArgumentException("观察已变化，请刷新后重试");
        String correction=text(input.correction(),500);
        if("CORRECTED".equals(input.decision()) && correction.isBlank()) throw new IllegalArgumentException("请填写纠正说明");
        repository.correct(owner,old,input.decision(),correction);
        var result=repository.observation(owner,id,automationClock.instant());
        repository.remember(owner,input.requestId(),id,fingerprint,json(result),automationClock.instant());
        return result;
    }
    private void requestId(String value) {
        if(value==null || !value.matches("[a-zA-Z0-9_-]{8,128}")) throw new IllegalArgumentException("反馈请求标识无效");
    }
    private String text(String value,int max) {
        String result=value==null?"":value.strip();
        if(result.length()>max || result.indexOf('\0')>=0) throw new IllegalArgumentException("反馈内容过长或含无效字符");
        return result;
    }
    private String json(Object value) {
        try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalArgumentException("反馈格式无效");}
    }
    private <T> T read(String value,Class<T> type) {
        try{return mapper.readValue(value,type);}catch(JsonProcessingException e){throw new IllegalStateException("反馈记录格式无效");}
    }
}
