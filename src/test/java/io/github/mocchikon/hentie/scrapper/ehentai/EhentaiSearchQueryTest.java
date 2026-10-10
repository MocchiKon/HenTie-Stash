package io.github.mocchikon.hentie.scrapper.ehentai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EhentaiSearchQueryTest
{
    @Test
    void shouldKeepTheFiltersOfAPastedSearchAddress()
    {
        // WHEN
        String query = EhentaiSearchQuery.normalized(
                "https://exhentai.org/?f_cats=1021&f_search=female%3Ablowjob%24+language%3Aenglish%24");

        // THEN in their order, encoded afresh
        assertThat(query).isEqualTo("f_cats=1021&f_search=female%3Ablowjob%24+language%3Aenglish%24");
    }

    /** The walk pages itself: a pasted page 3 would start it in the middle, and inline_set changes the account. */
    @Test
    void shouldDropPagingAndDisplaySettings()
    {
        // WHEN
        String query = EhentaiSearchQuery.normalized("?f_search=artist%3Ax%24&next=4219226&prev=1&page=2&jump=1d"
                + "&seek=2024-01-01&range=3&inline_set=dm_l");

        // THEN
        assertThat(query).isEqualTo("f_search=artist%3Ax%24");
    }

    @Test
    void shouldTakeTheSearchTextAlone()
    {
        // WHEN
        String query = EhentaiSearchQuery.normalized("  female:blowjob$ language:english$ ");

        // THEN
        assertThat(query).isEqualTo("f_search=female%3Ablowjob%24+language%3Aenglish%24");
    }

    @Test
    void shouldSearchForTheTagOfATagPage()
    {
        // WHEN
        String spaced = EhentaiSearchQuery.normalized("https://e-hentai.org/tag/female:big+breasts");
        String single = EhentaiSearchQuery.normalized("https://e-hentai.org/tag/artist:someone/");

        // THEN as gallery-dl turns one into a search: an exact tag
        assertThat(spaced).isEqualTo("f_search=female%3A%22big+breasts%24%22");
        assertThat(single).isEqualTo("f_search=artist%3Asomeone%24");
    }

    @Test
    void shouldRefuseWhatIsNoSearch()
    {
        // WHEN + THEN
        assertThatThrownBy(() -> EhentaiSearchQuery.normalized("https://e-hentai.org/g/618395/0439fa3666/"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("address of a search");
        assertThatThrownBy(() -> EhentaiSearchQuery.normalized("f_search=x&evil=1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("\"evil\"");
        assertThatThrownBy(() -> EhentaiSearchQuery.normalized("next=4219226&page=2"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("something to look for");
        assertThatThrownBy(() -> EhentaiSearchQuery.normalized("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EhentaiSearchQuery.normalized("f_search=%zz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** It would be searched for as text, and the subscription would never find anything. */
    @Test
    void shouldRefuseTheAddressOfAnotherSite()
    {
        // WHEN + THEN
        for (String address : List.of("https://nhentai.net/parody/genshin-impact/", "nhentai.net/search/?q=x",
                "http://example.com", "www.example.com/?f_search=x"))
        {
            assertThatThrownBy(() -> EhentaiSearchQuery.normalized(address))
                    .as(address).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not an address on");
        }
        // ...while search text with dots and colons is still search text.
        assertThat(EhentaiSearchQuery.normalized("parody:\"vol.2$\"")).isEqualTo("f_search=parody%3A%22vol.2%24%22");
    }
}
