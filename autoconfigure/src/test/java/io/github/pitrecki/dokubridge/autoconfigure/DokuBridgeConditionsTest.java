package io.github.pitrecki.dokubridge.autoconfigure;

import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.out.content.DocumentContentStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class DokuBridgeConditionsTest {
    @Test
    void backsOffWithoutJpaInfrastructure() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DokuBridgeAutoConfiguration.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DocumentBridge.class)
                        .doesNotHaveBean(DocumentContentStore.class));
    }

    @Test
    void defaultPropertiesDoNotInitializeSchemaOrChooseAStorageDirectory() {
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class).run(context -> {
            var properties = context.getBean(DokuBridgeProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.jpa().initializeSchema()).isFalse();
            assertThat(properties.storage().root()).isNull();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DokuBridgeProperties.class)
    static class PropertiesConfiguration {
    }
}
