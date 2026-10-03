package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** No upload date: it is set on creation and never editable. */
@Data
public class ChapterForm
{
    // SQLite does not enforce VARCHAR(255), so @Size is the real guard.
    private static final String TOO_LONG = "Must be 255 characters or fewer.";

    private Integer id;

    @Size(max = 255, message = TOO_LONG)
    private String title;

    @NotBlank(message = "Full title is required.")
    @Size(max = 255, message = TOO_LONG)
    private String titleFull;

    @Size(max = 255, message = TOO_LONG)
    private String nativeTitle;

    private Status status;

    @Size(max = 255, message = TOO_LONG)
    private String galleryId;

    @NotBlank(message = "Language is required.")
    @Size(max = 255, message = TOO_LONG)
    private String language;

    /** 1-10, nullable; the half-star UI shows it as 0.5-5. */
    private Integer score;

    private List<Integer> tagIds = new ArrayList<>();
    private List<Integer> artistIds = new ArrayList<>();
    private List<Integer> characterIds = new ArrayList<>();
    private List<Integer> parodyIds = new ArrayList<>();
    private List<Integer> groupIds = new ArrayList<>();
    private List<Integer> categoryIds = new ArrayList<>();
}