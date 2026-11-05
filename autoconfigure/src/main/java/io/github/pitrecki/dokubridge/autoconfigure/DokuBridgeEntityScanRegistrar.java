package io.github.pitrecki.dokubridge.autoconfigure;

import java.util.LinkedHashSet;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.domain.EntityScanPackages;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

final class DokuBridgeEntityScanRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware {
    private BeanFactory beans;

    @Override
    public void setBeanFactory(BeanFactory beans) {
        this.beans = beans;
    }

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        var packages = new LinkedHashSet<String>();
        // Reading EntityScanPackages.get() here would instantiate and freeze an explicit host scan.
        // Merge its bean definition instead, retaining implicit host packages only when no explicit scan exists.
        if (!registry.containsBeanDefinition(EntityScanPackages.class.getName()) && AutoConfigurationPackages.has(beans)) {
            packages.addAll(AutoConfigurationPackages.get(beans));
        }
        packages.add("io.github.pitrecki.dokubridge.adapter.jpa.document.persistence");
        packages.add("io.github.pitrecki.dokubridge.adapter.jpa.version.persistence");
        packages.add("io.github.pitrecki.dokubridge.adapter.jpa.upload.persistence");
        packages.add("io.github.pitrecki.dokubridge.adapter.jpa.audit.persistence");
        EntityScanPackages.register(registry, packages);
    }
}
