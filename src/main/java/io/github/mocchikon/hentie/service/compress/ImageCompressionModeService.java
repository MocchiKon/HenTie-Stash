package io.github.mocchikon.hentie.service.compress;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.dto.BuiltInCompressionMode;
import io.github.mocchikon.hentie.dto.CompressionModeOption;
import io.github.mocchikon.hentie.dto.CompressionProfile;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
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
 */
@Service
@RequiredArgsConstructor
public class ImageCompressionModeService
{
    private static final Logger log = LoggerFactory.getLogger(ImageCompressionModeService.class);

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

    /** Empty for {@code NONE}, blank, the "Custom" sentinel and a custom mode that no longer exists. */
    @Transactional(readOnly = true)
    public Optional<CompressionProfile> resolve(String key)
    {
        String trimmed = StringUtils.trimToEmpty(key);
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase(BuiltInCompressionMode.CUSTOM_KEY))
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
                CompressionProfile.splitArgs(settings.getMagickArgs()),
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
        repository.findFirstByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(mode.getId()))
                .ifPresent(other ->
                {
                    throw new NameTaken("Another mode is already called \"" + other.getName() + "\".");
                });
        mode.setName(name);
        return keyOf(repository.save(mode).getId());
    }

    /** No references to fix: a Settings choice or queued download pointing here then resolves to "None". */
    @Transactional
    public void delete(int id)
    {
        repository.deleteById(id);
    }
}
