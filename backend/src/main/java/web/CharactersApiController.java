package web;

import application.CharacterRosterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.CharacterRepository.CharacterProfile;
import web.access.RequestAccountContext;
import web.dto.CharacterReadResponse;
import web.dto.CharacterReadResponse.CharacterDto;

import java.sql.SQLException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP boundary for the Characters read foundation (STORY-CHAR-001): the account roster and one
 * selected character's identity and crafting disciplines. Thin: it maps the
 * {@link CharacterRosterService} result to transport records and a status; it reads nothing itself.
 *
 * <p>Status contract (the body {@code state} is authoritative): AVAILABLE and STALE 200; PENDING 202;
 * GRANT_REQUIRED and ENDPOINT_RESTRICTED 403; NOT_FOUND 404; UNAVAILABLE 503.
 *
 * <p>Ordering: characters by name then persisted id (no in-game slot order is retained); each
 * character's disciplines by discipline name.
 */
@RestController
@RequestMapping("/api/account/characters")
public class CharactersApiController {

    private static final Logger LOG = Logger.getLogger(CharactersApiController.class.getName());

    private final CharacterRosterService service;
    private final application.CharacterEquipmentTabsService equipmentTabs;
    private final application.CharacterInventoryLayoutService inventory;
    private final application.CharacterBuildTabsService buildTabs;

    public CharactersApiController(CharacterRosterService service) {
        this(service, null, null);
    }

    public CharactersApiController(CharacterRosterService service,
                                   application.CharacterEquipmentTabsService equipmentTabs) {
        this(service, equipmentTabs, null);
    }

    public CharactersApiController(CharacterRosterService service,
                                   application.CharacterEquipmentTabsService equipmentTabs,
                                   application.CharacterInventoryLayoutService inventory) {
        this(service, equipmentTabs, inventory, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CharactersApiController(CharacterRosterService service,
                                   application.CharacterEquipmentTabsService equipmentTabs,
                                   application.CharacterInventoryLayoutService inventory,
                                   application.CharacterBuildTabsService buildTabs) {
        this.buildTabs = buildTabs;
        this.service = service;
        this.equipmentTabs = equipmentTabs;
        this.inventory = inventory;
    }

    /** One selected character's bag layout with empty slots (STORY-CHAR-006); the roster stays compact. */
    @GetMapping(path = "/{name}/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<web.dto.CharacterInventoryResponse> inventory(
            @PathVariable("name") String name, HttpServletRequest request) {
        var context = RequestAccountContext.required(request);
        var scope = RequestAccountContext.scopeOf(request);
        try {
            if (inventory == null) throw new IllegalStateException("inventory layout is not configured");
            return web.dto.CharacterInventoryResponse.respond(inventory.read(context, scope, name));
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Inventory layout read failed", e);
            return web.dto.CharacterInventoryResponse.unavailable(
                    e instanceof SQLException ? "DATA_STORE_UNAVAILABLE" : "INVENTORY_READ_FAILED");
        }
    }

    /** One selected character's saved equipment tabs (STORY-CHAR-005); the roster stays compact. */
    @GetMapping(path = "/{name}/equipment-tabs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<web.dto.CharacterEquipmentTabsResponse> equipmentTabs(
            @PathVariable("name") String name, HttpServletRequest request) {
        var context = RequestAccountContext.required(request);
        var scope = RequestAccountContext.scopeOf(request);
        try {
            if (equipmentTabs == null) throw new IllegalStateException("equipment tabs are not configured");
            return web.dto.CharacterEquipmentTabsResponse.respond(equipmentTabs.read(context, scope, name));
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Equipment tabs read failed", e);
            return web.dto.CharacterEquipmentTabsResponse.unavailable(
                    e instanceof SQLException ? "DATA_STORE_UNAVAILABLE" : "EQUIPMENT_READ_FAILED");
        }
    }

    /** One selected character's saved build tabs (STORY-CHAR-007); stored rows only. */
    @GetMapping(path = "/{name}/build-tabs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<web.dto.CharacterBuildTabsResponse> buildTabs(
            @PathVariable("name") String name, HttpServletRequest request) {
        var context = RequestAccountContext.required(request);
        var scope = RequestAccountContext.scopeOf(request);
        try {
            if (buildTabs == null) throw new IllegalStateException("build tabs are not configured");
            return web.dto.CharacterBuildTabsResponse.respond(buildTabs.read(context, scope, name));
        } catch (SQLException | RuntimeException e) {
            LOG.log(Level.SEVERE, "Build tabs read failed", e);
            return web.dto.CharacterBuildTabsResponse.unavailable(
                    e instanceof SQLException ? "DATA_STORE_UNAVAILABLE" : "BUILD_TABS_READ_FAILED");
        }
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CharacterReadResponse> roster(HttpServletRequest request) {
        var context = RequestAccountContext.required(request);
        var scope = RequestAccountContext.scopeOf(request);
        try {
            return respond(service.roster(context, scope), false);
        } catch (SQLException | RuntimeException e) {
            return unavailable(e);
        }
    }

    @GetMapping(path = "/{name}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CharacterReadResponse> character(@PathVariable("name") String name,
                                                           HttpServletRequest request) {
        var context = RequestAccountContext.required(request);
        var scope = RequestAccountContext.scopeOf(request);
        try {
            return respond(service.character(context, scope, name), true);
        } catch (SQLException | RuntimeException e) {
            return unavailable(e);
        }
    }

    private static ResponseEntity<CharacterReadResponse> respond(CharacterRosterService.Result r, boolean detail) {
        List<CharacterDto> roster = r.roster() == null ? null : r.roster().stream().map(CharactersApiController::toDto).toList();
        var body = new CharacterReadResponse(
                r.state().name(), r.reason(), r.message(),
                r.completedAt() == null && r.refresh() == null ? null
                        : new CharacterReadResponse.Freshness(r.completedAt(), r.refresh()),
                roster == null ? null : roster.size(), roster,
                r.character() == null ? null : toDto(r.character()),
                detail && r.character() != null ? CharacterReadResponse.Sections.deferred() : null);
        HttpStatus status = switch (r.state()) {
            case AVAILABLE, STALE -> HttpStatus.OK;
            case PENDING -> HttpStatus.ACCEPTED;
            case GRANT_REQUIRED, ENDPOINT_RESTRICTED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseEntity<CharacterReadResponse> unavailable(Exception e) {
        LOG.log(Level.SEVERE, "Character read failed", e);
        String reason = e instanceof SQLException ? "DATA_STORE_UNAVAILABLE" : "CHARACTER_READ_FAILED";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new CharacterReadResponse(
                "UNAVAILABLE", reason, "Characters cannot be read right now.", null, null, null, null, null));
    }

    private static CharacterDto toDto(CharacterProfile p) {
        return new CharacterDto(p.name(), p.profession(), p.race(), p.gender(), p.level(), p.createdAt(),
                p.disciplines().stream()
                        .map(d -> new CharacterReadResponse.DisciplineDto(d.discipline(), d.rating(), d.active()))
                        .toList(),
                p.ageSeconds(), p.deaths(), p.nextBirthdayAt());
    }
}
