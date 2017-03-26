package org.nzbhydra.mapping;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nzbhydra.rssmapping.*;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@RunWith(SpringRunner.class)
public class RssMappingTest {
    @Before
    public void setUp() throws Exception {

    }

    @Test
    public void testMappingFromXml() throws Exception {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer mockServer = MockRestServiceServer.createServer(restTemplate);

        mockServer.expect(requestTo("/api")).andRespond(withSuccess(Resources.toString(Resources.getResource(RssMappingTest.class, "newznab_3results.xml"), Charsets.UTF_8), MediaType.APPLICATION_XML));

        RssRoot rssRoot = restTemplate.getForObject("/api", RssRoot.class);
        RssChannel channel = rssRoot.getRssChannel();
        assertThat(channel.getDescription(), is("indexerName(dot)com Feed"));
        assertThat(channel.getLink(), is("https://indexerName.com/"));
        assertThat(channel.getLanguage(), is("en-gb"));
        assertThat(channel.getWebMaster(), is("admin@indexerName.com (indexerName(dot)com)"));

        NewznabResponse newznabResponse = channel.getNewznabResponse();
        assertThat(newznabResponse.getOffset(), is(0));
        assertThat(newznabResponse.getTotal(), is(1000));

        List<RssItem> items = channel.getItems();
        assertThat(items.size(), is(3));

        RssItem item = items.get(0);
        assertThat(item.getLink(), is("https://indexerName.com/getnzb/eff551fbdb69d6777d5030c209ee5d4b.nzb&i=1692&r=apikey"));
        assertThat(item.getPubDate(), is(Instant.ofEpochSecond(1444584857)));
        assertThat(item.getDescription(), is("testtitle1"));
        assertThat(item.getComments(), is("https://indexerName.com/details/eff551fbdb69d6777d5030c209ee5d4b#comments"));

        RssGuid rssGuid = item.getRssGuid();
        assertThat(rssGuid.getGuid(), is("eff551fbdb69d6777d5030c209ee5d4b"));
        assertThat(rssGuid.getIsPermaLink(), is(false));

        Enclosure enclosure = item.getEnclosure();
        assertThat(enclosure.getUrl(), is("https://indexerName.com/getnzb/eff551fbdb69d6777d5030c209ee5d4b.nzb&i=1692&r=apikey"));
        assertThat(enclosure.getLength(), is(2893890900L));

        List<NewznabAttribute> attributes = item.getAttributes();
        assertThat(attributes.size(), is(6));
        assertThat(attributes.get(0).getName(), is("category"));
        assertThat(attributes.get(0).getValue(), is("7000"));
        assertThat(attributes.get(2).getName(), is("size"));
        assertThat(attributes.get(2).getValue(), is("2893890900"));
        assertThat(attributes.get(3).getName(), is("guid"));
        assertThat(attributes.get(3).getValue(), is("eff551fbdb69d6777d5030c209ee5d4b"));
        assertThat(attributes.get(4).getName(), is("poster"));
        assertThat(attributes.get(4).getValue(), is("chuck@norris.com"));
        assertThat(attributes.get(5).getName(), is("group"));
        assertThat(attributes.get(5).getValue(), is("alt.binaries.mom"));
    }


}