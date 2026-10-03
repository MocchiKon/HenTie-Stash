package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Status;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Every metadata field is an optional override; left empty, it is derived from the chapters. */
@Data
public class SeriesForm
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

    /** Override, 1-10; null = derived. The half-star UI shows it as 0.5-5. */
    private Integer score;

    @NotNull(message = "Status is required.")
    private Status status;

    private List<Integer> tagIds = new ArrayList<>();
    private List<Integer> artistIds = new ArrayList<>();
    private List<Integer> characterIds = new ArrayList<>();
    private List<Integer> parodyIds = new ArrayList<>();
    private List<Integer> groupIds = new ArrayList<>();
    private List<Integer> categoryIds = new ArrayList<>();

    /** To attach. */
    private List<Integer> chapterIds = new ArrayList<>();

    /** Chapter id to chapter number. */
    private Map<Integer, Float> chapterNums = new HashMap<>();
}