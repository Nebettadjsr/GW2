package sync;

import api.TaskDeadline;

/**
 * Thin instantiable seam over the owner full-catalog operations (STORY-STATUS-004), mirroring
 * {@link GlobalDataRefreshGateway}: lets the application service be tested with a fake gateway.
 */
public class FullCatalogGateway {

    public void syncAllItems(TaskDeadline deadline) throws Exception {
        ItemSync.syncAllItemsFull(deadline);
    }

    public void syncAllRecipes(TaskDeadline deadline) throws Exception {
        RecipeSync.syncAllRecipesFull(deadline);
    }

    public void syncAllTradingPost(TaskDeadline deadline) throws Exception {
        TpSync.syncAllTradeablePrices(deadline);
    }
}
