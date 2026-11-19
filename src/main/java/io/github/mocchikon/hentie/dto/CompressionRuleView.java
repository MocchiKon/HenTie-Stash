package io.github.mocchikon.hentie.dto;

import java.util.List;

/**
 * One image compression rule as its page shows it.
 *
 * @param modeName   null for a mode since deleted, which the rule then applies as None
 * @param quality    of the mode it applies
 * @param conditions what a gallery must carry, every one; empty for a rule that matches every gallery
 * @param broken     a value it was saved with was removed since, so it matches no gallery
 */
public record CompressionRuleView(int id, int priority, String modeName, int quality, boolean allowUpgrade,
                                  List<Condition> conditions, boolean broken)
{
    public record Condition(MetadataType type, String name)
    {
    }
}
