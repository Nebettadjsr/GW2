package web;

import application.BankContentsService;
import application.icons.ItemIconUrls;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import repo.BankRepository;
import web.dto.BankContentsResponse;

import java.sql.SQLException;
import java.util.List;

/**
 * HTTP boundary for the account-bank read (STORY-API-007, TARGET_ARCHITECTURE.md §9): the slots a
 * browser needs to render the bank grid the JavaFX Bank view already shows.
 *
 * <p>Thin by construction: it calls the existing {@link BankContentsService} read {@code BankView}
 * uses (§5.12/§6 of CURRENT_ARCHITECTURE.md) and copies the rows into transport records. It queries
 * no repository, synchronizes nothing, writes nothing, calls the GW2 API not at all and runs no
 * crafting calculation - it drops, adds, reorders and aggregates nothing either.
 *
 * <p>Like the selector-options route it holds a single shared service instance: the service keeps no
 * per-call state, so there is no read result for concurrent requests to observe.
 */
@RestController
@RequestMapping("/api/account")
public class BankContentsApiController {

    private final BankContentsService bankContentsService;

    public BankContentsApiController(BankContentsService bankContentsService) {
        this.bankContentsService = bankContentsService;
    }

    /**
     * Returns every account-bank slot in the service's slot order, empty slots included.
     *
     * <p>Status contract: 200 with the slots; 503 when the database is unavailable; 500 when the
     * read fails for any other reason. See {@link AccountReadApiExceptionHandler}. An account with
     * no bank rows is a 200 with {@code slotCount: 0} and an empty {@code slots} list - nothing is
     * substituted, and there is no 404 case.
     */
    @GetMapping(path = "/bank", produces = MediaType.APPLICATION_JSON_VALUE)
    public BankContentsResponse bankContents() throws SQLException {
        List<BankContentsResponse.BankSlotDto> slots = toSlots(bankContentsService.getBankContents());
        return new BankContentsResponse(slots.size(), slots);
    }

    /**
     * Field-for-field copy, preserving the service's order; no filtering, sorting or defaulting.
     *
     * <p>The one derived value is {@code iconUrl}: this application's image URL for the slot's item,
     * computed from the retained source the same batch read already carried
     * (TARGET_ARCHITECTURE.md §12.1). No lookup, no request and no calculation happens per slot, and
     * the backend's local {@code iconPath} stays out of the response.
     */
    private static List<BankContentsResponse.BankSlotDto> toSlots(List<BankRepository.BankSlotRow> rows) {
        return rows.stream()
                .map(row -> new BankContentsResponse.BankSlotDto(
                        row.slot(),
                        row.itemId(),
                        row.count(),
                        ItemIconUrls.iconUrlFor(row.itemId(), row.iconUrl()),
                        row.rarity()))
                .toList();
    }
}
