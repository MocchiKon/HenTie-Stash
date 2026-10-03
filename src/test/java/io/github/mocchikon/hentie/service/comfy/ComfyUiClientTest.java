package io.github.mocchikon.hentie.service.comfy;

import org.junit.jupiter.api.Test;

import io.github.mocchikon.hentie.config.AppProperties;
import io.github.mocchikon.hentie.service.SettingsService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComfyUiClientTest
{
    private final AppProperties appProperties = new AppProperties();
    private final SettingsService settingsService = mock(SettingsService.class);

    /** Falling back to ComfyUI's usual address would reach whatever ComfyUI runs there, e.g. from a test. */
    @Test
    void shouldRefuseADefaultAddressThatIsNoAddress()
    {
        // GIVEN
        appProperties.getComfyui().setDefaultUrl(" ");

        // WHEN + THEN
        assertThatIllegalStateException().isThrownBy(() -> new ComfyUiClient(appProperties, settingsService))
                .withMessageContaining("app.comfyui.default-url");

        // GIVEN
        appProperties.getComfyui().setDefaultUrl("ftp://127.0.0.1:1");

        // WHEN + THEN
        assertThatIllegalStateException().isThrownBy(() -> new ComfyUiClient(appProperties, settingsService));
    }

    @Test
    void shouldAnswerWithTheNormalizedDefaultWhileSettingsHoldNoUsableAddress()
    {
        // GIVEN a default written without a scheme and with a trailing slash, and a Settings value that is none.
        appProperties.getComfyui().setDefaultUrl("127.0.0.1:1/");
        when(settingsService.getComfyUiUrl()).thenReturn("ftp://elsewhere");
        var client = new ComfyUiClient(appProperties, settingsService);

        // WHEN + THEN
        assertThat(client.baseUrl()).isEqualTo("http://127.0.0.1:1");
    }
}
