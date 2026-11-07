package web.dto;

import application.CharacterBuildTabsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import repo.CharacterBuildTabsRepository.SkillsView;

import java.time.Instant;
import java.util.List;

/**
 * Body of the selected-character saved-build-tabs read (STORY-CHAR-007). {@code tabs} is present only for
 * AVAILABLE or STALE, so missing, pending or incompatible coverage never reads as an empty list. Reference
 * ids are returned as {@code {id, resolved:false}}: names and icons are not available yet and are not
 * fabricated. Null positions stay explicit JSON nulls; absent Revenant/Ranger sections are omitted.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CharacterBuildTabsResponse(String state, String reason, String message, String character,
                                         Coverage parent, Coverage builds, Long coveredParentGeneration,
                                         List<TabDto> tabs) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Coverage(Long generation, Instant completedAt) {}

    /** An upstream numeric identifier with no resolved display metadata. */
    public record IdRef(int id, boolean resolved) {}

    /** An upstream STRING legend identifier with no resolved display metadata. */
    public record LegendRef(String id, boolean resolved) {}

    public record SpecializationDto(IdRef specialization, List<IdRef> traits) {}

    public record SkillsDto(IdRef heal, List<IdRef> utilities, IdRef elite) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TabDto(int tab, String name, String profession, boolean active,
                         List<SpecializationDto> specializations, SkillsDto skills, SkillsDto aquaticSkills,
                         List<LegendRef> legends, List<LegendRef> aquaticLegends,
                         List<IdRef> pets, List<IdRef> aquaticPets) {}

    public static ResponseEntity<CharacterBuildTabsResponse> respond(CharacterBuildTabsService.Result r) {
        var body = new CharacterBuildTabsResponse(r.state().name(), r.reason(), r.message(), r.character(),
                r.parentGeneration() == null ? null : new Coverage(r.parentGeneration(), r.parentCompletedAt()),
                r.buildsGeneration() == null ? null : new Coverage(r.buildsGeneration(), r.buildsCompletedAt()),
                r.coveredParentGeneration(), r.tabs() == null ? null : r.tabs().stream().map(CharacterBuildTabsResponse::tab).toList());
        HttpStatus status = switch (r.state()) {
            case AVAILABLE, STALE -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case GRANT_REQUIRED, ENDPOINT_RESTRICTED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(body);
    }

    public static ResponseEntity<CharacterBuildTabsResponse> unavailable(String reason) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new CharacterBuildTabsResponse(
                "UNAVAILABLE", reason, "Build tabs cannot be read right now.", null, null, null, null, null));
    }

    private static TabDto tab(repo.CharacterBuildTabsRepository.TabView t) {
        return new TabDto(t.number(), t.name(), t.profession(), t.active(),
                t.specializations().stream().map(s -> new SpecializationDto(ref(s.id()),
                        s.traits() == null ? null : s.traits().stream().map(CharacterBuildTabsResponse::ref).toList())).toList(),
                skills(t.skills()), skills(t.aquaticSkills()),
                legends(t.legends()), legends(t.aquaticLegends()), refs(t.pets()), refs(t.aquaticPets()));
    }

    private static SkillsDto skills(SkillsView s) {
        return new SkillsDto(ref(s.heal()), refs(s.utilities()), ref(s.elite()));
    }

    private static IdRef ref(Integer id) {
        return id == null ? null : new IdRef(id, false);
    }

    private static List<IdRef> refs(List<Integer> ids) {
        return ids == null ? null : ids.stream().map(CharacterBuildTabsResponse::ref).toList();
    }

    private static List<LegendRef> legends(List<String> ids) {
        return ids == null ? null : ids.stream().map(i -> i == null ? null : new LegendRef(i, false)).toList();
    }
}
