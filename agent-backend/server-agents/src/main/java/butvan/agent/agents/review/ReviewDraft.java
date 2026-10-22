package butvan.agent.agents.review;

import java.util.List;

/** 模型结构化草案；所有引用仍需服务端按白名单校验后才能保存。 */
public record ReviewDraft(int schemaVersion, String coverageNote, List<Observation> observations,
                          List<FollowUp> followUps, List<Action> actions) {
    public ReviewDraft {
        observations = observations == null ? List.of() : List.copyOf(observations);
        followUps = followUps == null ? List.of() : List.copyOf(followUps);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public record Observation(String topicKey, String kind, String text, List<String> evidenceIds,
                              List<String> counterEvidenceIds, String uncertainty) {
        public Observation {
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            counterEvidenceIds = counterEvidenceIds == null ? List.of() : List.copyOf(counterEvidenceIds);
        }
    }

    public record FollowUp(String actionId, String text, List<String> evidenceIds) {
        public FollowUp { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds); }
    }

    public record Action(String topicKey, String goalId, String methodId, String text, String rationale,
                         List<String> evidenceIds, Integer checkAfterDays, String successCriterion) {
        public Action { evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds); }
    }
}
