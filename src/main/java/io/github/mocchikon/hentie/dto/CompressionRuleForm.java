package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.ImageCompressionRule;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * An image compression rule as its form edits it. The conditions are two lists in step, one entry per row of the
 * form: a row is a kind and a name, and the form adds and removes rows without numbering them. The names are
 * checked by the service, which knows what each kind refuses.
 */
@Data
public class CompressionRuleForm
{
    private Integer id;

    /** 0 is the highest; of the rules a gallery matches, only those with the lowest number count. */
    @NotNull(message = "Enter a priority, 0 for the highest")
    @Min(value = 0, message = "Priority must be 0 (the highest) or more")
    private Integer priority = ImageCompressionRule.DEFAULT_PRIORITY;

    @NotBlank(message = "Choose a mode")
    @Size(max = 64, message = "Choose one of the modes offered")
    private String compressionMode;

    private boolean allowUpgrade;

    /** {@link MetadataType} keys. */
    private List<String> conditionTypes = new ArrayList<>();

    private List<String> conditionValues = new ArrayList<>();
}
