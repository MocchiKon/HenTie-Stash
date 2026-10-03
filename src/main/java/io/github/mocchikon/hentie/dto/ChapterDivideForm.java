package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.entity.Status;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parts are named by the file name of their first page, never by position: a position points at another page
 * once one is added or deleted. Titles are a map on the same key, so field order does not matter and a single
 * title is not split at its commas (as a {@code List<String>} would be).
 */
@Data
public class ChapterDivideForm
{
    private static final String TOO_LONG = "A title must be 255 characters or fewer.";

    @Size(max = 255, message = TOO_LONG)
    private String keptTitle;

    /** For every new chapter; null means the chapter's own. */
    private Status partStatus;

    private List<String> starts = new ArrayList<>();

    /** A start with no title (no JavaScript) gets the chapter's own. */
    private Map<String, @Size(max = 255, message = TOO_LONG) String> titles = new HashMap<>();
}
