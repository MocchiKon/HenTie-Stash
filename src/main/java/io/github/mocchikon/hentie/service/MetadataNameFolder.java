package io.github.mocchikon.hentie.service;

import io.github.mocchikon.hentie.config.WriteGate;
import io.github.mocchikon.hentie.dto.MetadataType;
import io.github.mocchikon.hentie.dto.OptionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Rewrites every existing metadata name in its canonical spelling ({@link MetadataService#canonical}): lower
 * case, and for tags without e-hentai's namespaces. The backfill for names written before either rule.
 *
 * <p><b>Two capitalisations of one name are two rows</b>, so folding them is a <i>merge</i>, not a rename.
 * It drives {@link MetadataService#mergeSpellings} rather than its own SQL, because SQLite's {@code lower()}
 * folds ASCII only. Row by row: a tag's {@code ♀}/{@code ♂} versions have spellings of their own, and a rename
 * taking them along with the plain tag would be refused while a version still has a second spelling, which
 * only the version's own group merges.
 *
 * <p>It records no <b>rule</b>: folding is not a decision about a name, and a case-insensitive rule for
 * the mixed-case spelling would rule out the survivor's own name.
 */
@Service
public class MetadataNameFolder
{
    private static final Logger log = LoggerFactory.getLogger(MetadataNameFolder.class);

    private final MetadataService metadataService;

    private final MetadataCatalog catalog;

    private final WriteGate writeGate;

    public MetadataNameFolder(MetadataService metadataService, MetadataCatalog catalog, WriteGate writeGate)
    {
        this.metadataService = metadataService;
        this.catalog = catalog;
        this.writeGate = writeGate;
    }

    /**
     * {@code blocked} counts rows a rule would not let be folded. They are left alone and reported, because
     * the fix (remove the rule, or delete the row) is the user's decision.
     */
    public record FoldResult(int renamed, int merged, int blocked)
    {
        public int total()
        {
            return renamed + merged;
        }
    }

    public FoldResult foldAll()
    {
        return writeGate.background("rewriting metadata names", this::foldEveryType);
    }

    private FoldResult foldEveryType()
    {
        int renamed = 0;
        int merged = 0;
        int blocked = 0;
        for (MetadataType type : MetadataType.values())
        {
            FoldResult result = fold(type);
            renamed += result.renamed();
            merged += result.merged();
            blocked += result.blocked();
        }
        if (renamed + merged + blocked > 0)
        {
            log.info("Metadata names rewritten in their canonical spelling: {} renamed, {} merged into an existing row, "
                    + "{} left alone because a rule rules the folded name out", renamed, merged, blocked);
        }
        return new FoldResult(renamed, merged, blocked);
    }

    /**
     * Grouping by canonical name first makes several spellings ({@code Halo ♀}, {@code female:halo}) collapse
     * onto <b>one</b> survivor.
     * <p>Deliberately <b>not</b> transactional: each merge and rename has its own, so the run never holds
     * SQLite's single write lock for long. The list is read uncached ({@link MetadataCatalog#allFresh})
     * because it decides what gets written.
     */
    public FoldResult fold(MetadataType type)
    {
        var byFoldedName = new LinkedHashMap<String, List<OptionDto>>();
        for (OptionDto option : catalog.allFresh(type))
        {
            byFoldedName.computeIfAbsent(MetadataService.canonical(type, option.getLabel()), k -> new ArrayList<>())
                    .add(option);
        }

        int renamed = 0;
        int merged = 0;
        int blocked = 0;
        for (var group : byFoldedName.entrySet())
        {
            String folded = group.getKey();
            List<OptionDto> rows = group.getValue();
            // Lowest id survives: the row other references are most likely to point at already.
            OptionDto survivor = rows.stream().min(Comparator.comparing(OptionDto::getId)).orElseThrow();
            for (OptionDto duplicate : rows)
            {
                if (!duplicate.getId().equals(survivor.getId()))
                {
                    metadataService.mergeSpellings(type, duplicate.getId(), survivor.getId());
                    merged++;
                }
            }
            if (!folded.equals(survivor.getLabel()))
            {
                if (metadataService.respell(type, survivor.getId(), folded).isPresent())
                {
                    log.warn("Left {} \"{}\" as it is: a rule rules \"{}\" out", type.getKey(),
                            survivor.getLabel(), folded);
                    blocked++;
                }
                else
                {
                    renamed++;
                }
            }
        }
        return new FoldResult(renamed, merged, blocked);
    }
}
