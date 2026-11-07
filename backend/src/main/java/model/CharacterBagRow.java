package model;

/**
 * One authoritative bag position of a character (STORY-CHAR-006). An uninstalled position (upstream
 * {@code null}) carries neither a container nor a capacity.
 */
public record CharacterBagRow(int bagIndex, boolean installed, Integer containerItemId, Integer capacity) {}
