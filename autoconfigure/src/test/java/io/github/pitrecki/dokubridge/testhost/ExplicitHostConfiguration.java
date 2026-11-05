package io.github.pitrecki.dokubridge.testhost;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
@EntityScan("io.github.pitrecki.dokubridge.testentities")
public class ExplicitHostConfiguration {
}
