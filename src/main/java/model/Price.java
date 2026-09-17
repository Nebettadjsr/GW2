package model;

public class Price {
    int buy;
    int sell;

    public Price(int buy, int sell) {
        this.buy = buy;
        this.sell = sell;
    }

    public int getBuy() {
        return buy;
    }

    public void setBuy(int buy) {
        this.buy = buy;
    }

    public int getSell() {
        return sell;
    }

    public void setSell(int sell) {
        this.sell = sell;
    }
}
