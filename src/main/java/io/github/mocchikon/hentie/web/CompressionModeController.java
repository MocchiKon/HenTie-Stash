package io.github.mocchikon.hentie.web;

import io.github.mocchikon.hentie.dto.CompressionModeForm;
import io.github.mocchikon.hentie.entity.ImageCompressionMode;
import io.github.mocchikon.hentie.entity.ImageEncoder;
import io.github.mocchikon.hentie.service.compress.ImageCompressionModeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * User-defined modes only; "Custom" in a dropdown navigates here. The built-in modes are not editable, so
 * they are the same on every install and a "Lossless" can never stop being lossless.
 */
@Controller
@RequestMapping("/compression-modes")
@RequiredArgsConstructor
public class CompressionModeController
{
    private static final String FORM_VIEW = "compression-mode-form";
    private static final String REDIRECT_LIST = "redirect:/compression-modes";

    private final ImageCompressionModeService modeService;

    @GetMapping
    public String list(Model model)
    {
        model.addAttribute("modes", modeService.customModes());
        return "compression-modes";
    }

    @GetMapping("/new")
    public String create(Model model)
    {
        model.addAttribute("form", new CompressionModeForm());
        addFormModel(model);
        return FORM_VIEW;
    }

    @GetMapping("/{id:\\d+}/edit")
    public String edit(@PathVariable int id, Model model)
    {
        ImageCompressionMode mode = modeService.find(id).orElse(null);
        if (mode == null)
        {
            return REDIRECT_LIST;
        }
        model.addAttribute("form", toForm(mode));
        addFormModel(model);
        return FORM_VIEW;
    }

    /** A duplicate name is a field error: the UNIQUE column would otherwise throw at commit, a 500 naming no field. */
    @PostMapping
    public String save(@Valid @ModelAttribute("form") CompressionModeForm form, BindingResult result,
                       Model model, RedirectAttributes redirectAttributes)
    {
        if (result.hasErrors())
        {
            addFormModel(model);
            return FORM_VIEW;
        }
        try
        {
            modeService.save(toEntity(form));
        }
        catch (ImageCompressionModeService.NameTaken e)
        {
            result.rejectValue("name", "duplicate", e.getMessage());
            addFormModel(model);
            return FORM_VIEW;
        }
        redirectAttributes.addFlashAttribute("savedMode", form.getName());
        return REDIRECT_LIST;
    }

    @PostMapping("/{id:\\d+}/delete")
    public String delete(@PathVariable int id, RedirectAttributes redirectAttributes)
    {
        modeService.find(id).ifPresent(mode -> redirectAttributes.addFlashAttribute("deletedMode", mode.getName()));
        modeService.delete(id);
        return REDIRECT_LIST;
    }

    private void addFormModel(Model model)
    {
        model.addAttribute("encoders", ImageEncoder.values());
    }

    private static CompressionModeForm toForm(ImageCompressionMode mode)
    {
        var form = new CompressionModeForm();
        form.setId(mode.getId());
        form.setName(mode.getName());
        form.setEncoder(mode.getEncoder());
        form.setEncoderArgs(mode.getEncoderArgs());
        form.setMagickArgs(mode.getMagickArgs());
        form.setMinRelativeReduction(mode.getMinRelativeReduction());
        form.setMinAbsoluteReduction(mode.getMinAbsoluteReduction());
        form.setFormats(mode.getFormats());
        return form;
    }

    private ImageCompressionMode toEntity(CompressionModeForm form)
    {
        ImageCompressionMode mode = form.getId() == null
                ? new ImageCompressionMode()
                : modeService.find(form.getId()).orElseGet(ImageCompressionMode::new);
        mode.setName(form.getName());
        mode.setEncoder(form.getEncoder());
        mode.setEncoderArgs(trim(form.getEncoderArgs()));
        mode.setMagickArgs(trim(form.getMagickArgs()));
        mode.setMinRelativeReduction(form.getMinRelativeReduction());
        mode.setMinAbsoluteReduction(form.getMinAbsoluteReduction());
        mode.setFormats(trim(form.getFormats()));
        return mode;
    }

    /** The columns are {@code NOT NULL}, and a blank argument string means "do not run this tool". */
    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }
}
