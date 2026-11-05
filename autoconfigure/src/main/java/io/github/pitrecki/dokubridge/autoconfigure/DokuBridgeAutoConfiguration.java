package io.github.pitrecki.dokubridge.autoconfigure;

import io.github.pitrecki.dokubridge.adapter.filesystem.FilesystemDocumentContentStore;
import io.github.pitrecki.dokubridge.adapter.jpa.JpaBusinessDocumentRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.JpaDocumentAuditRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.JpaDocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.JpaDocumentUploadRepository;
import io.github.pitrecki.dokubridge.adapter.jpa.JpaDocumentVersionRepository;
import io.github.pitrecki.dokubridge.core.application.DefaultDocumentBridge;
import io.github.pitrecki.dokubridge.core.port.in.DocumentBridge;
import io.github.pitrecki.dokubridge.core.port.out.BusinessDocumentRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentAuditRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentContentStore;
import io.github.pitrecki.dokubridge.core.port.out.DocumentLifecycleRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentUploadRepository;
import io.github.pitrecki.dokubridge.core.port.out.DocumentVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(after = {HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
@ConditionalOnClass({EntityManagerFactory.class, JpaTransactionManager.class})
@ConditionalOnSingleCandidate(EntityManagerFactory.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "dokubridge", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DokuBridgeProperties.class)
@Import(DokuBridgeEntityScanRegistrar.class)
public class DokuBridgeAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DocumentContentStore.class)
    DocumentContentStore dokubridgeContentStore(DokuBridgeProperties properties) throws IOException {
        if (properties.storage().root() == null) {
            throw new IllegalArgumentException("dokubridge.storage.root is required unless a DocumentContentStore bean is provided");
        }
        return new FilesystemDocumentContentStore(properties.storage().root());
    }

    @Bean
    @ConditionalOnMissingBean(BusinessDocumentRepository.class)
    BusinessDocumentRepository dokubridgeDocuments(EntityManagerFactory factory, PlatformTransactionManager transactions) {
        return new JpaBusinessDocumentRepository(entities(factory, transactions), transactions);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentVersionRepository.class)
    DocumentVersionRepository dokubridgeVersions(EntityManagerFactory factory, PlatformTransactionManager transactions) {
        return new JpaDocumentVersionRepository(entities(factory, transactions), transactions);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentAuditRepository.class)
    DocumentAuditRepository dokubridgeAudits(EntityManagerFactory factory, PlatformTransactionManager transactions) {
        return new JpaDocumentAuditRepository(entities(factory, transactions), transactions);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentUploadRepository.class)
    DocumentUploadRepository dokubridgeUploads(EntityManagerFactory factory, PlatformTransactionManager transactions) {
        return new JpaDocumentUploadRepository(entities(factory, transactions), transactions);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentLifecycleRepository.class)
    DocumentLifecycleRepository dokubridgeLifecycle(EntityManagerFactory factory, PlatformTransactionManager transactions) {
        return new JpaDocumentLifecycleRepository(entities(factory, transactions), transactions);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentBridge.class)
    DocumentBridge dokubridgeBridge(BusinessDocumentRepository documents, DocumentVersionRepository versions,
                                   DocumentContentStore content, DocumentUploadRepository uploads, DocumentLifecycleRepository lifecycle) {
        return new DefaultDocumentBridge(documents, versions, content, uploads, lifecycle);
    }

    private EntityManager entities(EntityManagerFactory factory, PlatformTransactionManager transactions) {
        if (!(transactions instanceof JpaTransactionManager jpa) || jpa.getEntityManagerFactory() != factory) {
            throw new IllegalArgumentException("DokuBridge repositories require a JpaTransactionManager for the selected EntityManagerFactory");
        }
        return SharedEntityManagerCreator.createSharedEntityManager(factory);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dokubridge.jpa", name = "initialize-schema", havingValue = "true")
    static class SchemaConfiguration {
        @Bean
        @ConditionalOnMissingBean(name = "dokubridgeSchemaInitializer")
        InitializingBean dokubridgeSchemaInitializer(DataSource dataSource,
                                                    ObjectProvider<FlywayMigrationInitializer> hostInitializers) {
            return () -> {
                // Complete host migrations first so a new host history is created before library tables exist.
                hostInitializers.orderedStream().toList();
                Flyway.configure().dataSource(dataSource).locations("classpath:db/dokubridge")
                        .table("dokubridge_flyway_history").baselineOnMigrate(true).baselineVersion("0")
                        .load().migrate();
            };
        }

        @Bean
        static EntityManagerFactoryDependsOnPostProcessor dokubridgeSchemaOrdering() {
            return new EntityManagerFactoryDependsOnPostProcessor("dokubridgeSchemaInitializer");
        }
    }
}
