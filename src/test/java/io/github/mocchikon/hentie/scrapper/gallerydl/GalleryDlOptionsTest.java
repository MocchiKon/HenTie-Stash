package io.github.mocchikon.hentie.scrapper.gallerydl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every value here reaches gallery-dl's command line, so anything not on the whitelist must fall away. */
class GalleryDlOptionsTest
{
    @Test
    void shouldKeepOnlyBrowsersGalleryDlKnowsWhenStoringTheCookiesChoice()
    {
        // WHEN + THEN
        assertThat(GalleryDlOptions.storableBrowser(" Firefox ")).isEqualTo("firefox");
        assertThat(GalleryDlOptions.storableBrowser("edge")).isEqualTo("edge");
        assertThat(GalleryDlOptions.storableBrowser("")).isNull();
        assertThat(GalleryDlOptions.storableBrowser(null)).isNull();
        assertThat(GalleryDlOptions.storableBrowser("firefox --exec rm")).isNull();
        assertThat(GalleryDlOptions.storableBrowser("firefox:profile")).isNull();
        assertThat(new GalleryDlOptions("netscape", false, "1").cookiesBrowser()).isNull();
    }

    @Test
    void shouldNormalizeADelayGalleryDlCanRead()
    {
        // WHEN + THEN
        assertThat(GalleryDlOptions.normalizedDelay("0.4-0.65")).contains("0.4-0.65");
        assertThat(GalleryDlOptions.normalizedDelay(" 0.40 - 0.650 ")).contains("0.4-0.65");
        assertThat(GalleryDlOptions.normalizedDelay("2")).contains("2");
        assertThat(GalleryDlOptions.normalizedDelay("2.0")).contains("2");
        assertThat(GalleryDlOptions.normalizedDelay("0")).contains("0");
        assertThat(GalleryDlOptions.normalizedDelay("3-3")).contains("3-3");
    }

    @Test
    void shouldRefuseADelayGalleryDlCannotRead()
    {
        // WHEN + THEN
        assertThat(GalleryDlOptions.normalizedDelay("")).isEmpty();
        assertThat(GalleryDlOptions.normalizedDelay(null)).isEmpty();
        assertThat(GalleryDlOptions.normalizedDelay("fast")).isEmpty();
        assertThat(GalleryDlOptions.normalizedDelay("-1")).isEmpty();
        assertThat(GalleryDlOptions.normalizedDelay("0.65-0.4")).isEmpty();
        assertThat(GalleryDlOptions.normalizedDelay("1,5")).isEmpty();
        assertThat(GalleryDlOptions.normalizedDelay("601")).isEmpty();
        assertThat(GalleryDlOptions.normalizedDelay("1 --exec x")).isEmpty();
    }
}
