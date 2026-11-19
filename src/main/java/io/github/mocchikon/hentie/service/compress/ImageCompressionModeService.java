package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.CompressionModeOption;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.repository.ImageCompressionModeRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * <b>A mode is referred to by a key, never by its name or position</b>: the enum constant for a built-in
 * ({@code LOSSLESS}), {@code custom:<id>} for a user mode. So a rename, or a new built-in in the middle of
 * the list, cannot repoint a stored choice.
 *
 * <p>A key that no longer resolves (a deleted custom mode) means "None", the answer that cannot lose a page.
 *
 * <p>{@link BuiltInCompressionMode#RULES_KEY} is no mode: a queue row holding it is given one by the image
 * compression rules. Only a download may store it ({@link #storableDownloadKey}).
 */
@Service
@RequiredArgsConstructor
public class ImageCompressionModeService
{
    private static final Logger log = LoggerFactory.getLogger(ImageCompressionModeService.class);

    /** cjxl re-encodes a JPEG losslessly unless told otherwise, whatever quality the mode asks for. */
    private static final String LOSSLESS_JPEG = "--lossless_jpeg";

    /** Passed to cjxl as {@code --lossless_jpeg=1} or {@code =0}, depending on the mode's quality. */
    private static final String LOSSLESS_JPEG_QUALITY_100 = LOSSLESS_JPEG + "=1";
    private static final String LOSSLESS_JPEG_BELOW_100 = LOSSLESS_JPEG + "=0";

    /** Keeps cjxl from storing metadata that only rebuilds the source JPEG byte for byte. */
    private static final String ALLOW_JPEG_RECONSTRUCTION = "--allow_jpeg_reconstruction";

    private final AppProperties appProperties;
    private final ImageCompressionModeRepository repository;

    /** Refuses a name another mode already has, or one of the built-in names. */
    public static class NameTaken extends RuntimeException
    {
        public NameTaken(String message)
        {
            super(message);
        }
    }

    // ---- the dropdown ------------------------------------------------------

    /**
     * The Download page's dropdown: "By rules" first, then {@link #options}. Settings never offers it: the rules fall
     * back on Settings' mode.
     */
    @Transactional(readOnly = true)
    public List<CompressionModeOption> downloadOptions()
    {
        var options = new ArrayList<CompressionModeOption>();
        options.add(CompressionModeOption.of(BuiltInCompressionMode.RULES_KEY, BuiltInCompressionMode.RULES_LABEL));
        options.addAll(options());
        return options;
    }

    /** "Custom" is flagged because it is not a mode: it navigates to the page where one is created. */
    @Transactional(readOnly = true)
    public List<CompressionModeOption> options()
    {
        var options = new ArrayList<CompressionModeOption>();
        for (BuiltInCompressionMode mode : BuiltInCompressionMode.values())
        {
            options.add(CompressionModeOption.of(mode.getKey(), mode.getDisplayName()));
        }
        for (ImageCompressionMode mode : repository.findAllByOrderByIdAsc())
        {
            options.add(CompressionModeOption.of(keyOf(mode.getId()), mode.getName()));
        }
        options.add(new CompressionModeOption(BuiltInCompressionMode.CUSTOM_KEY,
                BuiltInCompressionMode.CUSTOM_LABEL, true));
        return options;
    }

    @Transactional(readOnly = true)
    public String displayName(String key)
    {
        return resolve(key).map(CompressionProfile::name).orElse(BuiltInCompressionMode.NONE.getDisplayName());
    }

    /**
     * Empty for a deleted mode. {@link #displayName} says "None" instead, which fits a choice about the future
     * but not a record of what was already done to a chapter.
     */
    public Optional<String> nameOf(String key)
    {
        return resolve(key).map(CompressionProfile::name);
    }

    /** One read for a page listing many rows. A deleted mode's key is absent, so it renders as nothing. */
    @Transactional(readOnly = true)
    public Map<String, String> namesByKey()
    {
        var names = new LinkedHashMap<String, String>();
        options().stream()
                .filter(option -> !option.custom())
                .forEach(option -> names.put(option.key(), option.label()));
        return names;
    }

    // ---- resolving a stored choice ----------------------------------------

    /**
     * Empty for {@code NONE}, blank, the "Custom" and "By rules" sentinels and a custom mode that no longer exists.
     * A download queued "By rules" asks the rules for a key before it gets here.
     */
    @Transactional(readOnly = true)
    public Optional<CompressionProfile> resolve(String key)
    {
        String trimmed = StringUtils.trimToEmpty(key);
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(BuiltInCompressionMode.CUSTOM_KEY) || isRules(trimmed))
        {
            return Optional.empty();
        }
        if (StringUtils.startsWithIgnoreCase(trimmed, BuiltInCompressionMode.CUSTOM_PREFIX))
        {
            return customProfile(trimmed);
        }
        return builtIn(trimmed);
    }

    private Optional<CompressionProfile> builtIn(String key)
    {
        BuiltInCompressionMode mode;
        try
        {
            mode = BuiltInCompressionMode.valueOf(key.toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException unknown)
        {
            log.warn("Unknown Image Compression mode {} - treating it as None", key);
            return Optional.empty();
        }
        AppProperties.ImageCompression config = appProperties.getImageCompression();
        AppProperties.Mode settings = switch (mode)
        {
            case NONE -> null;
            case LOSSLESS -> config.getLossless();
            case HIGH_REDUCTION -> config.getHighReduction();
            case VERY_HIGH_REDUCTION -> config.getVeryHighReduction();
        };
        if (settings == null)
        {
            return Optional.empty();
        }
        return Optional.of(new CompressionProfile(mode.getKey(), mode.getDisplayName(), settings.getEncoder(),
                CompressionProfile.splitArgs(settings.getEncoderArgs()),
                CompressionProfile.splitArgs(settings.getMagickArgs()), settings.getImageHeightLimit(),
                settings.getMinRelativeReduction(), settings.getMinAbsoluteReduction(),
                CompressionProfile.parseFormats(settings.getFormats())));
    }

    /**
     * The key to <b>store</b> for a form value: anything that is not an existing mode becomes {@code NONE}.
     * A whitelist, because only {@code app.js} keeps the {@code CUSTOM} sentinel out of a submission, and
     * SQLite would store a string of any length in the 64-character column.
     */
    @Transactional(readOnly = true)
    public String storableKey(String key)
    {
        String trimmed = StringUtils.trimToEmpty(key);
        Integer id = customId(trimmed);
        if (id != null)
        {
            // Canonical spelling, so "CUSTOM:7" and "custom: 7" are stored as one value.
            return repository.existsById(id) ? keyOf(id) : BuiltInCompressionMode.NONE.getKey();
        }
        try
        {
            // CUSTOM is not a constant of this enum, so the sentinel lands in the catch.
            return BuiltInCompressionMode.valueOf(trimmed.toUpperCase(Locale.ROOT)).getKey();
        }
        catch (IllegalArgumentException notAMode)
        {
            return BuiltInCompressionMode.NONE.getKey();
        }
    }

    /** {@link #storableKey}, or "By rules", which a queued download may hold until its metadata is known. */
    @Transactional(readOnly = true)
    public String storableDownloadKey(String key)
    {
        return isRules(key) ? BuiltInCompressionMode.RULES_KEY : storableKey(key);
    }

    public static boolean isRules(String key)
    {
        return BuiltInCompressionMode.RULES_KEY.equalsIgnoreCase(StringUtils.trimToEmpty(key));
    }

    // ---- quality -----------------------------------------------------------

    /**
     * The quality of every mode there is, by key, in one read, since a decision compares several. A key absent from
     * the map no longer resolves: see {@link #qualityOf} and {@link #recordedQualityOf} for what that means.
     */
    @Transactional(readOnly = true)
    public Map<String, Integer> qualities()
    {
        AppProperties.ImageCompression config = appProperties.getImageCompression();
        var qualities = new HashMap<String, Integer>();
        qualities.put(BuiltInCompressionMode.NONE.getKey(), BuiltInCompressionMode.NONE_QUALITY);
        qualities.put(BuiltInCompressionMode.LOSSLESS.getKey(), config.getLossless().getQuality());
        qualities.put(BuiltInCompressionMode.HIGH_REDUCTION.getKey(), config.getHighReduction().getQuality());
        qualities.put(BuiltInCompressionMode.VERY_HIGH_REDUCTION.getKey(), config.getVeryHighReduction().getQuality());
        repository.findAllByOrderByIdAsc().forEach(mode -> qualities.put(keyOf(mode.getId()), mode.getQuality()));
        return qualities;
    }

    /** Of a mode about to be used: one that no longer resolves runs as None, so it has None's quality. */
    public static int qualityOf(Map<String, Integer> qualities, String key)
    {
        return qualities.getOrDefault(canonicalKey(key), BuiltInCompressionMode.NONE_QUALITY);
    }

    /**
     * Of the pages a chapter's {@code compression_mode} says re-encoded them: null means original pages, as good as
     * None. A mode since deleted is of unknown quality, so any mode there is counts as better: an upgrade it allows
     * puts pages of a known quality back.
     */
    public static int recordedQualityOf(Map<String, Integer> qualities, String recordedKey)
    {
        return recordedKey == null ? BuiltInCompressionMode.NONE_QUALITY
                : qualities.getOrDefault(canonicalKey(recordedKey), Integer.MAX_VALUE);
    }

    /** Whether pages {@code recordedKey} compressed would be strictly better in {@code modeKey}. */
    @Transactional(readOnly = true)
    public boolean isBetter(String modeKey, String recordedKey)
    {
        Map<String, Integer> qualities = qualities();
        return qualityOf(qualities, modeKey) < recordedQualityOf(qualities, recordedKey);
    }

    /** The spelling {@link #qualities} keys by; anything that is no mode's key comes back as it was. */
    private static String canonicalKey(String key)
    {
        String trimmed = StringUtils.trimToEmpty(key);
        Integer id = customId(trimmed);
        if (id != null)
        {
            return keyOf(id);
        }
        return trimmed.toUpperCase(Locale.ROOT);
    }

    private Optional<CompressionProfile> customProfile(String key)
    {
        Integer id = customId(key);
        if (id == null)
        {
            return Optional.empty();
        }
        Optional<ImageCompressionMode> mode = repository.findById(id);
        if (mode.isEmpty())
        {
            log.warn("Image Compression mode {} no longer exists - treating it as None", key);
            return Optional.empty();
        }
        return mode.map(m -> CompressionProfile.of(key, m));
    }

    public static String keyOf(int id)
    {
        return BuiltInCompressionMode.CUSTOM_PREFIX + id;
    }

    /** Null when the key is not a (well-formed) {@code custom:<id>} key. */
    public static Integer customId(String key)
    {
        if (!StringUtils.startsWithIgnoreCase(key, BuiltInCompressionMode.CUSTOM_PREFIX))
        {
            return null;
        }
        try
        {
            return Integer.valueOf(key.substring(BuiltInCompressionMode.CUSTOM_PREFIX.length()).trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    // ---- managing user-defined modes ---------------------------------------

    @Transactional(readOnly = true)
    public List<ImageCompressionMode> customModes()
    {
        return repository.findAllByOrderByIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<ImageCompressionMode> find(int id)
    {
        return repository.findById(id);
    }

    /**
     * The name is checked <b>first</b>: the column is UNIQUE, and a duplicate reaching the database fails at
     * commit with nothing useful to say. Built-in names and "Custom" are refused too, or the dropdown would
     * show two identical entries.
     *
     * @return the mode's key
     */
    @Transactional
    public String save(ImageCompressionMode mode)
    {
        String name = StringUtils.trimToEmpty(mode.getName());
        for (BuiltInCompressionMode builtIn : BuiltInCompressionMode.values())
        {
            if (builtIn.getDisplayName().equalsIgnoreCase(name))
            {
                throw new NameTaken("\"" + name + "\" is a built-in mode - please choose another name.");
            }
        }
        if (BuiltInCompressionMode.CUSTOM_LABEL.equalsIgnoreCase(name))
        {
            throw new NameTaken("\"" + name + "\" is the dropdown entry for defining a new mode - please "
                    + "choose another name.");
        }
        if (BuiltInCompressionMode.RULES_LABEL.equalsIgnoreCase(name))
        {
            throw new NameTaken("\"" + name + "\" is the dropdown entry that leaves the mode to the image "
                    + "compression rules - please choose another name.");
        }
        repository.findFirstByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(mode.getId()))
                .ifPresent(other ->
                {
                    throw new NameTaken("Another mode is already called \"" + other.getName() + "\".");
                });
        mode.setName(name);
        mode.setEncoderArgs(withJxlDefaults(mode.getEncoder(), mode.getEncoderArgs()));
        return keyOf(repository.save(mode).getId());
    }

    /**
     * Adds the cjxl switches a JPEG source needs, so the stored mode says what is really run and a later edit
     * shows it. The switches are added only when the user has not named them: an explicit
     * {@code --lossless_jpeg} or {@code --allow_jpeg_reconstruction} always wins.
     */
    private static String withJxlDefaults(ImageEncoder encoder, String rawArgs)
    {
        String args = StringUtils.trimToEmpty(rawArgs);
        if (encoder != ImageEncoder.JXL)
        {
            return args;
        }
        List<String> tokens = CompressionProfile.splitArgs(args);
        var normalized = new StringBuilder(args);
        if (!namesOption(tokens, LOSSLESS_JPEG))
        {
            appendOption(normalized, Integer.valueOf(100).equals(qualityOf(tokens))
                    ? LOSSLESS_JPEG_QUALITY_100
                    : LOSSLESS_JPEG_BELOW_100);
        }
        if (!namesOption(tokens, ALLOW_JPEG_RECONSTRUCTION))
        {
            appendOption(normalized, ALLOW_JPEG_RECONSTRUCTION + "=0");
        }
        return normalized.toString();
    }

    /** True when the mode already names the option, so an explicit value wins and nothing is appended twice. */
    private static boolean namesOption(List<String> args, String option)
    {
        return args.stream().anyMatch(arg -> arg.equals(option) || arg.startsWith(option + "="));
    }

    private static void appendOption(StringBuilder args, String option)
    {
        if (args.length() > 0)
        {
            args.append(' ');
        }
        args.append(option);
    }

    /**
     * The last quality in the arguments, since cjxl lets a later option override an earlier one. Null when
     * none is given or the value is not a number; both are below 100, which is what the caller needs.
     */
    private static Integer qualityOf(List<String> args)
    {
        Integer quality = null;
        for (int i = 0; i < args.size(); i++)
        {
            String arg = args.get(i);
            String value = null;
            if (arg.equals("-q") || arg.equals("--quality"))
            {
                value = i + 1 < args.size() ? args.get(i + 1) : null;
            }
            else if (arg.startsWith("-q="))
            {
                value = arg.substring("-q=".length());
            }
            else if (arg.startsWith("--quality="))
            {
                value = arg.substring("--quality=".length());
            }
            else if (arg.startsWith("-q") && arg.length() > 2)
            {
                value = arg.substring(2);
            }
            if (value != null)
            {
                try
                {
                    quality = Integer.valueOf(value.trim());
                }
                catch (NumberFormatException notAQuality)
                {
                    quality = null;
                }
            }
        }
        return quality;
    }

    /** No references to fix: a Settings choice or queued download pointing here then resolves to "None". */
    @Transactional
    public void delete(int id)
    {
        repository.deleteById(id);
    }
}
