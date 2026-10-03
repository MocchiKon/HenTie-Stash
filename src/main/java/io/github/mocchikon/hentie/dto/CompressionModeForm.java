package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.ImageEncoder;
import jakarta.validation.constraints.*;
import lombok.Data;

/**
 * Mirrors {@code ImageCompressionMode} field for field; a field missing here would be a mode setting the app
 * could not configure.
 *
 * <p>Lengths are checked here because SQLite does not enforce {@code VARCHAR(n)}.
 */
@Data
public class CompressionModeForm
{
    private Integer id;

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name must be at most 255 characters")
    private String name;

    @NotNull(message = "Encoder is required")
    private ImageEncoder encoder = ImageEncoder.JXL;

    @Size(max = 1000, message = "Encoder arguments must be at most 1000 characters")
    private String encoderArgs = "";

    /** Blank = ImageMagick is not run. */
    @Size(max = 1000, message = "ImageMagick arguments must be at most 1000 characters")
    private String magickArgs = "";

    /** Percent; 0 turns the check off. */
    @Min(value = 0, message = "Minimum relative reduction cannot be negative")
    @Max(value = 100, message = "Minimum relative reduction is a percentage, so at most 100")
    private int minRelativeReduction;

    /** KB; 0 turns the check off. */
    @Min(value = 0, message = "Minimum absolute reduction cannot be negative")
    private int minAbsoluteReduction;

    /** E.g. {@code JPG,GIF,PNG}. Blank = every image. */
    @Size(max = 255, message = "Image formats must be at most 255 characters")
    private String formats = "";
}
