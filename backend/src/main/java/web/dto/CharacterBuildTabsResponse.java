package web.dto;

import application.CharacterBuildTabsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import application.icons.ResourceIconUrls;
import model.BuildResourceType;
import parser.CharacterBuildTabsParser.Specialization;
import repo.BuildMetadataRepository.Metadata;
import repo.CharacterBuildTabsRepository.SkillsView;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** A numeric reference; name and app-relative image URL only when metadata is resolved. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IdRef(int id, boolean resolved, String name, String iconUrl) {
        public IdRef(int id, boolean resolved) { this(id, resolved, null, null); }
    }

    /**
     * A trait reference with the documented owning specialization, tier, order (0 is valid for both) and
     * slot when known. {@code placement}: ORDINARY (owner, slot and unique tier/order all consistent),
     * NONSTANDARD (tier/order stated but outside ordinary tiers 1..3 and order 0..2; raw tier 0 and order 0 values are still preserved),
     * CONFLICT (wrong owner or slot, not offered by
     * the specialization, or a duplicate tier/order choice) or UNRESOLVED (no metadata, or a required tier/order fact is missing). The ids stay
     * either way; clients place only ORDINARY rows by tier/order and never by array position.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TraitRef(int id, boolean resolved, String name, String iconUrl, Integer specializationId,
                           Integer tier, Integer order, String slot, String placement) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpecRef(int id, boolean resolved, String name, String iconUrl, String profession, Boolean elite) {}

    /**
     * A STRING legend id. Legends have no native name or icon; {@code swapSkill} is the explicitly related
     * skill (resolved or not), and {@code resolved} says whether the legend-to-skill relation is known.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegendRef(String id, boolean resolved, IdRef swapSkill) {}

    /**
     * {@code traits} are the selected (highlighted) choices (null positions explicit). {@code minorTraits}/{@code majorTraits}
     * are the specialization's offered choice ids as upstream lists them; their placement comes only from
     * each trait's documented specialization/tier/order/slot fields (see {@link TraitRef}), never from list
     * position. Present only when the specialization is resolved, each possibly unresolved.
     */
    public record SpecializationDto(SpecRef specialization, List<TraitRef> traits,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) List<TraitRef> minorTraits,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) List<TraitRef> majorTraits) {}

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
                r.coveredParentGeneration(), r.tabs() == null ? null : r.tabs().stream().map(t -> tab(t, r.metadata())).toList());
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

    private static TabDto tab(repo.CharacterBuildTabsRepository.TabView t, Metadata m) {
        return new TabDto(t.number(), t.name(), t.profession(), t.active(),
                t.specializations().stream().map(s -> specialization(s, m)).toList(),
                skills(t.skills(), m), skills(t.aquaticSkills(), m),
                legends(t.legends(), m), legends(t.aquaticLegends(), m),
                refs(t.pets(), BuildResourceType.PETS, m), refs(t.aquaticPets(), BuildResourceType.PETS, m));
    }

    private static SpecializationDto specialization(Specialization s, Metadata m) {
        var row = s.id() == null ? null : m.specializations().get(s.id());
        SpecRef spec = s.id() == null ? null : row == null ? new SpecRef(s.id(), false, null, null, null, null)
                : new SpecRef(s.id(), true, row.name(), url(BuildResourceType.SPECIALIZATIONS, s.id(), row.iconUrl()),
                        row.profession(), row.elite());
        Set<Integer> offeredMajor = row == null ? Set.of() : new HashSet<>(row.majorTraits());
        List<TraitRef> selected = s.traits() == null ? null
                : s.traits().stream().map(i -> i == null ? null
                        : trait(i, m, s.id(), "Major", row == null || offeredMajor.contains(i), null)).toList();
        return new SpecializationDto(spec, selected,
                row == null ? null : choices(row.minorTraits(), m, s.id(), "Minor"),
                row == null ? null : choices(row.majorTraits(), m, s.id(), "Major"));
    }

    private static List<TraitRef> choices(List<Integer> ids, Metadata m, Integer owner, String slot) {
        Map<String, Integer> keyCount = new HashMap<>();
        Map<Integer, Integer> idCount = new HashMap<>();
        for (int i : ids) {
            idCount.merge(i, 1, Integer::sum);
            var t = m.traits().get(i);
            if (t != null && t.tier() != null && t.order() != null) keyCount.merge(t.tier() + "/" + t.order(), 1, Integer::sum);
        }
        return ids.stream().map(i -> {
            var t = m.traits().get(i);
            boolean dup = idCount.get(i) > 1
                    || (t != null && t.tier() != null && t.order() != null && keyCount.get(t.tier() + "/" + t.order()) > 1);
            return trait(i, m, owner, slot, true, dup);
        }).toList();
    }

    /** {@code offered} false or {@code duplicate} true marks a conflict; the expected owner/slot are checked here. */
    private static TraitRef trait(int id, Metadata m, Integer owner, String slot, boolean offered, Boolean duplicate) {
        var t = m.traits().get(id);
        if (t == null) return new TraitRef(id, false, null, null, null, null, null, null, "UNRESOLVED");
        String placement;
        if (!offered || Boolean.TRUE.equals(duplicate) || owner == null || !owner.equals(t.specializationId())
                || !slot.equals(t.slot())) {
            placement = "CONFLICT";
        } else if (t.tier() == null || t.order() == null) {
            placement = "UNRESOLVED";
        } else if (t.tier() < 1 || t.tier() > 3 || t.order() < 0 || t.order() > 2) {
            placement = "NONSTANDARD";
        } else {
            placement = "ORDINARY";
        }
        return new TraitRef(id, true, t.name(), url(BuildResourceType.TRAITS, id, t.iconUrl()),
                t.specializationId(), t.tier(), t.order(), t.slot(), placement);
    }

    private static SkillsDto skills(SkillsView s, Metadata m) {
        return new SkillsDto(idRef(s.heal(), BuildResourceType.SKILLS, m), refs(s.utilities(), BuildResourceType.SKILLS, m),
                idRef(s.elite(), BuildResourceType.SKILLS, m));
    }

    private static IdRef idRef(Integer id, BuildResourceType type, Metadata m) {
        if (id == null) return null;
        var row = type == BuildResourceType.SKILLS ? m.skills().get(id) : m.pets().get(id);
        return row == null ? new IdRef(id, false) : new IdRef(id, true, row.name(), url(type, id, row.iconUrl()));
    }

    private static List<IdRef> refs(List<Integer> ids, BuildResourceType type, Metadata m) {
        return ids == null ? null : ids.stream().map(i -> idRef(i, type, m)).toList();
    }

    private static List<LegendRef> legends(List<String> ids, Metadata m) {
        return ids == null ? null : ids.stream().map(i -> {
            if (i == null) return null;
            var row = m.legends().get(i);
            return row == null ? new LegendRef(i, false, null)
                    : new LegendRef(i, true, idRef(row.swapSkillId(), BuildResourceType.SKILLS, m));
        }).toList();
    }

    private static String url(BuildResourceType type, int id, String rawSource) {
        return ResourceIconUrls.iconUrlFor(type, id, rawSource);
    }
}
