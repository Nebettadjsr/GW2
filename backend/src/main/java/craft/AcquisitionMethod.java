package craft;

/**
 * A way a requirement's quantity was actually obtained (DOMAIN_SPEC.md section 12). Unlike
 * {@link AcquisitionMode}, which classifies a requirement as a whole, several of these may apply
 * to one requirement whose quantity was split across sources.
 */
public enum AcquisitionMethod {
    INVENTORY,
    CRAFT,
    BUY
}
