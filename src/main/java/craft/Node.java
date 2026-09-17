package craft;

import java.util.List;

public class Node {
    public final int itemId;
    public final int qty;
    public final String action;
    public final List<Node> children;

    public Node(int itemId, int qty, String action, List<Node> children) {
        this.itemId = itemId;
        this.qty = qty;
        this.action = action;
        this.children = children;
    }
}
