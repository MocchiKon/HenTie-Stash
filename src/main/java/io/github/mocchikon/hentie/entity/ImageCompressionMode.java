package io.github.mocchikon.hentie.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * A user-defined Image Compression mode. The built-in modes are not rows, so they cannot be edited into
 * something that no longer matches their name.
 */
@Entity
@Table(name = "image_compression_mode")
@Getter
@Setter
public class ImageCompressionMode
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** UNIQUE: two modes the user cannot tell apart are worse than a refusal. */
    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    /** ORDINAL: never reorder {@link ImageEncoder}. */
    @Enumerated(EnumType.ORDINAL)
    @Column(name = "encoder", nullable = false)
    private ImageEncoder encoder = ImageEncoder.JXL;

    /** Passed verbatim, e.g. {@code "-q 100 -e 10"}. */
    @Column(name = "encoder_args", nullable = false, length = 1000)
    private String encoderArgs = "";

    /** Passed to ImageMagick verbatim. Blank = ImageMagick is not run. */
    @Column(name = "magick_args", nullable = false, length = 1000)
    private String magickArgs = "";

    /** Percent that must be saved, or the original is kept. 0 = no relative check. */
    @Column(name = "min_relative_reduction", nullable = false)
    private int minRelativeReduction;

    /** KB that must be saved, or the original is kept. 0 = no absolute check. */
    @Column(name = "min_absolute_reduction", nullable = false)
    private int minAbsoluteReduction;

    /** Input extensions, e.g. {@code "JPG,PNG"}. Blank = every image. */
    @Column(name = "formats", nullable = false, length = 255)
    private String formats = "";
}
