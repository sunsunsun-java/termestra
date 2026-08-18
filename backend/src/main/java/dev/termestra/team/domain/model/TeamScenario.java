package dev.termestra.team.domain.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A built-in team composition used by one-click scenario assembly. */
public record TeamScenario(String id, List<MemberSpec> members) {
    public TeamScenario {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("scenario id must not be blank");
        members = List.copyOf(Objects.requireNonNull(members));
        if (members.isEmpty()) throw new IllegalArgumentException("scenario must contain members");
    }

    public record MemberSpec(String nameStem, AgentRole role, Map<String, String> descriptions) {
        public MemberSpec {
            if (nameStem == null || nameStem.isBlank()) {
                throw new IllegalArgumentException("scenario member name stem must not be blank");
            }
            Objects.requireNonNull(role);
            if (!role.isWorker()) throw new IllegalArgumentException("scenario member must be a worker");
            descriptions = Map.copyOf(Objects.requireNonNullElse(descriptions, Map.of()));
        }

        public String description(String locale) {
            String language = "zh".equals(locale) ? "zh" : "en";
            return descriptions.get(language);
        }
    }
}
