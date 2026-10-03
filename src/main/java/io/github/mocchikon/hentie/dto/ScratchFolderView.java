package io.github.mocchikon.hentie.dto;

import io.github.mocchikon.hentie.service.scratch.ScratchArea;

import java.util.List;

/**
 * @param value blank when not set in Settings
 * @param inUse where the files go now, and why; several lines for download staging, whose automatic choice
 *              depends on whether the download is compressed
 */
public record ScratchFolderView(ScratchArea area, String value, List<String> inUse) {}
