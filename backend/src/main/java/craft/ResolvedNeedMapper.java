package craft;

public class ResolvedNeedMapper {

    public Node toNode(ResolvedNeed need) {
        if (need == null) {
            return new Node(-1, 0, "null", java.util.List.of());
        }

        java.util.List<Node> children = new java.util.ArrayList<>();
        for (ResolvedNeed child : need.getChildren()) {
            children.add(toNode(child));
        }

        String action = toActionLabel(need);

        return new Node(
                need.getItemId(),
                need.getQtyRequested(),
                action,
                children
        );
    }

    private String toActionLabel(ResolvedNeed need) {
        return switch (need.getMode()) {
            case INVENTORY -> "inventory";
            case CRAFT -> "craft";
            case BUY -> "buy";
            case MIXED -> buildMixedLabel(need);
            case BLOCKED -> buildBlockedLabel(need);
        };
    }

    private String buildMixedLabel(ResolvedNeed need) {
        StringBuilder sb = new StringBuilder("mixed");

        boolean hasDetails = need.getQtyFromInventory() > 0
                || need.getQtyCrafted() > 0
                || need.getQtyBought() > 0
                || need.getQtyBlocked() > 0;

        if (hasDetails) {
            sb.append(" [");
            boolean first = true;

            if (need.getQtyFromInventory() > 0) {
                sb.append("inv=").append(need.getQtyFromInventory());
                first = false;
            }
            if (need.getQtyCrafted() > 0) {
                if (!first) sb.append(", ");
                sb.append("craft=").append(need.getQtyCrafted());
                first = false;
            }
            if (need.getQtyBought() > 0) {
                if (!first) sb.append(", ");
                sb.append("buy=").append(need.getQtyBought());
                first = false;
            }
            if (need.getQtyBlocked() > 0) {
                if (!first) sb.append(", ");
                sb.append("blocked=").append(need.getQtyBlocked());
            }

            sb.append("]");
        }

        return sb.toString();
    }

    private String buildBlockedLabel(ResolvedNeed need) {
        if (need.getBlockedReason() == null || need.getBlockedReason() == BlockedReason.NONE) {
            return "blocked";
        }
        return "blocked(" + need.getBlockedReason().name().toLowerCase() + ")";
    }
}