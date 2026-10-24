package it.logisticasanchez.web.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * ADM-01 / chapter 10 §2: the session cookie of the deployed profiles is
 * {@code __Host-LS_SESSION} with {@code HttpOnly}, {@code Secure}, {@code SameSite=Lax} and
 * {@code Path=/}; staging and production do not override it. (The local and test profiles use a
 * plain name without {@code Secure} because browsers refuse {@code __Host-} cookies over
 * {@code http://}; the real-server test checks the flags that apply there.)
 */
@Tag("ADM-01")
class SessionCookieConfigurationTest {

    @Test
    void theBaseConfigurationDeclaresTheHostPrefixedCookieWithEveryFlag() {
        Properties base = load("application.yml");
        assertThat(base.getProperty("server.servlet.session.cookie.name")).isEqualTo("__Host-LS_SESSION");
        assertThat(base.getProperty("server.servlet.session.cookie.http-only")).isEqualTo("true");
        assertThat(base.getProperty("server.servlet.session.cookie.secure")).isEqualTo("true");
        assertThat(base.getProperty("server.servlet.session.cookie.same-site")).isEqualTo("lax");
        assertThat(base.getProperty("server.servlet.session.cookie.path")).isEqualTo("/");
        assertThat(base.getProperty("server.servlet.session.cookie.domain")).isNull();
    }

    @Test
    void stagingAndProductionKeepIt() {
        for (String profile : new String[] {"application-staging.yml", "application-prod.yml"}) {
            Properties overrides = load(profile);
            assertThat(overrides.stringPropertyNames())
                    .as("%s overrides the session cookie", profile)
                    .noneMatch(key -> key.startsWith("server.servlet.session.cookie"));
        }
    }

    private static Properties load(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }
}
