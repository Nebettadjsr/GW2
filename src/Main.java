public class Main {
    static double DUST_PER_ECTO = 1.0;
    static int LUCK_PER_ECTO = 20;
    static final double TOTAL_FEE =  0.15; // 5% Listing, 10% exchange fee

    static void main(String[] args) {
        Price ecto = Gw2PriceFetch.getPrices(19721);   // Ecto
        Price dust = Gw2PriceFetch.getPrices(24277);   // Crystalline Dust

        int ectoBuy = ecto.buy;
        int ectoSell = ecto.sell;
        int dustBuy = dust.buy;
        int dustSell = dust.sell;

//        System.out.println("Ecto Buy: " + ectoBuy);
//        System.out.println("Ecto Sell: " + ectoSell);
//        System.out.println("Dust Buy: " + dustBuy);
//        System.out.println("Dust Sell: " + dustSell);

        // Costs
        int ectoInstantCost = ecto.sell;
        int ectoListingCost = ecto.buy;

        // Dust prices
        int dustInstantPrice = dust.buy;
        int dustListingPrice = dust.sell;

        // Profits
        int profit_EI_DI = profitPerEcto(ectoInstantCost, dustInstantPrice);
        int profit_EI_DL = profitPerEcto(ectoInstantCost, dustListingPrice);

        int profit_EL_DI = profitPerEcto(ectoListingCost, dustInstantPrice);
        int profit_EL_DL = profitPerEcto(ectoListingCost, dustListingPrice);

        System.out.println("\n========================= ECTO SALVAGE PROFIT ==========================");

        System.out.printf("%-31s | %15s | %15s\n",
                          "",
                          "Dust Instant Sell",
                          "Dust Listing Sell");

        System.out.println("------------------------------------------------------------------------");

        System.out.printf("%-28s | %17s | %15s\n",
                          "Ecto Instant Buy (" + formatMoney(ectoInstantCost) + ")",
                          formatMoney(profit_EI_DI),
                          formatMoney(profit_EI_DL));

        System.out.printf("%-28s | %17s | %15s\n",
                          "Ecto Listing Buy (" + formatMoney(ectoListingCost) + ")",
                          formatMoney(profit_EL_DI),
                          formatMoney(profit_EL_DL));


        int cost_EI_DI = costPer1000Luck(ectoInstantCost, dustInstantPrice);
        int cost_EI_DL = costPer1000Luck(ectoInstantCost, dustListingPrice);

        int cost_EL_DI = costPer1000Luck(ectoListingCost, dustInstantPrice);
        int cost_EL_DL = costPer1000Luck(ectoListingCost, dustListingPrice);


        System.out.println("\n============================== LUCK VALUE ==============================");
        System.out.println("1 ecto ≈ " + LUCK_PER_ECTO + " Luck");
        System.out.println("Cost per 1000 Luck (~ 50 ectos):");
        System.out.println();
        System.out.printf("%-31s | %15s | %15s\n",
                          "",
                          "Dust Instant Sell",
                          "Dust Listing Sell");

        System.out.println("------------------------------------------------------------------------");

        System.out.printf("Ecto Instant Buy (%s) | %17s | %17s\n",
                          formatMoney(ectoInstantCost),
                          formatMoney(cost_EI_DI),
                          formatMoney(cost_EI_DL));

        System.out.printf("Ecto Listing Buy (%s) | %17s | %17s\n",
                          formatMoney(ectoListingCost),
                          formatMoney(cost_EL_DI),
                          formatMoney(cost_EL_DL));

        System.out.println("========================================================================");



    }

    public static int applySellFees(int sellPrice) {
        return (int) Math.floor(sellPrice * (1 - TOTAL_FEE));
    }

    public static String formatMoney(int copper) {
        int gold = copper / 10000;
        int silver = (copper % 10000) / 100;
        int copperRest = copper % 100;

        return String.format("%3dg %2ds %2dc", gold, silver, copperRest);
    }

    public static int profitPerEcto(int ectoCost, int dustPrice) {
        int netDustPrice = applySellFees(dustPrice);
        double value = DUST_PER_ECTO * netDustPrice;
        return (int) Math.floor(value - ectoCost);
    }

    public static int costPer1000Luck(int ectoCost, int dustPrice) {

        // Net dust value after TP fee
        int netDustPrice = applySellFees(dustPrice);

        double dustValuePerEcto = DUST_PER_ECTO * netDustPrice;

        // Net cost of salvaging one ecto
        double netCostPerEcto = ectoCost - dustValuePerEcto;

        // How many ectos for 1000 luck
        double ectosNeeded = 1000.0 / LUCK_PER_ECTO;

        return (int) Math.ceil(netCostPerEcto * ectosNeeded);
    }


}
