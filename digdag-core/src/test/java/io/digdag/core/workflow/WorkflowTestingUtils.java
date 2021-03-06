package io.digdag.core.workflow;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import io.digdag.client.config.Config;
import io.digdag.core.DigdagEmbed;
import io.digdag.core.LocalSecretAccessPolicy;
import io.digdag.core.LocalSite;
import io.digdag.core.crypto.SecretCrypto;
import io.digdag.core.crypto.SecretCryptoProvider;
import io.digdag.core.archive.ArchiveMetadata;
import io.digdag.core.archive.WorkflowFile;
import io.digdag.core.database.DatabaseConfig;
import io.digdag.core.database.DatabaseSecretStoreManager;
import io.digdag.core.repository.ResourceConflictException;
import io.digdag.core.repository.ResourceNotFoundException;
import io.digdag.core.repository.StoredWorkflowDefinition;
import io.digdag.core.repository.WorkflowDefinitionList;
import io.digdag.core.session.StoredSessionAttemptWithSession;
import io.digdag.spi.CommandExecutor;
import io.digdag.spi.OperatorFactory;
import io.digdag.spi.ScheduleTime;
import io.digdag.spi.SchedulerFactory;
import io.digdag.spi.SecretAccessPolicy;
import io.digdag.spi.SecretStoreManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.digdag.core.database.DatabaseTestingUtils.cleanDatabase;
import static io.digdag.core.database.DatabaseTestingUtils.getEnvironmentDatabaseConfig;

public class WorkflowTestingUtils
{
    private WorkflowTestingUtils() { }

    public static DigdagEmbed setupEmbed()
    {
        DigdagEmbed embed = new DigdagEmbed.Bootstrap()
            .withExtensionLoader(false)
            .addModules((binder) -> {
                binder.bind(CommandExecutor.class).to(SimpleCommandExecutor.class).in(Scopes.SINGLETON);

                binder.bind(SecretCrypto.class).toProvider(SecretCryptoProvider.class).in(Scopes.SINGLETON);
                binder.bind(SecretStoreManager.class).to(DatabaseSecretStoreManager.class).in(Scopes.SINGLETON);
                binder.bind(SecretAccessPolicy.class).to(LocalSecretAccessPolicy.class);

                Multibinder<SchedulerFactory> schedulerFactoryBinder = Multibinder.newSetBinder(binder, SchedulerFactory.class);

                Multibinder<OperatorFactory> operatorFactoryBinder = Multibinder.newSetBinder(binder, OperatorFactory.class);
                operatorFactoryBinder.addBinding().to(NoopOperatorFactory.class).in(Scopes.SINGLETON);
                operatorFactoryBinder.addBinding().to(EchoOperatorFactory.class).in(Scopes.SINGLETON);
                operatorFactoryBinder.addBinding().to(FailOperatorFactory.class).in(Scopes.SINGLETON);
            })
            .overrideModulesWith((binder) -> {
                binder.bind(DatabaseConfig.class).toInstance(getEnvironmentDatabaseConfig());
            })
            .initializeWithoutShutdownHook();
        cleanDatabase(embed);
        return embed;
    }

    public static void runWorkflow(LocalSite localSite, Path workdir, String workflowName, Config config)
        throws InterruptedException
    {
        try {
            ArchiveMetadata meta = ArchiveMetadata.of(
                    WorkflowDefinitionList.of(ImmutableList.of(
                            WorkflowFile.fromConfig(workflowName, config).toWorkflowDefinition()
                            )),
                    config.getFactory().create().set("_workdir", workdir.toString()));
            LocalSite.StoreWorkflowResult stored = localSite.storeLocalWorkflowsWithoutSchedule(
                    "default",
                    "revision-" + UUID.randomUUID(),
                    meta);
            StoredWorkflowDefinition def = findDefinition(stored.getWorkflowDefinitions(), workflowName);
            AttemptRequest ar = localSite.getAttemptBuilder()
                .buildFromStoredWorkflow(
                        stored.getRevision(),
                        def,
                        config.getFactory().create(),
                        ScheduleTime.runNow(Instant.ofEpochSecond(Instant.now().getEpochSecond())));
            StoredSessionAttemptWithSession attempt = localSite.submitWorkflow(ar, def);
            localSite.runUntilDone(attempt.getId());
        }
        catch (ResourceNotFoundException | ResourceConflictException ex) {
            throw Throwables.propagate(ex);
        }
    }

    private static StoredWorkflowDefinition findDefinition(List<StoredWorkflowDefinition> defs, String name)
    {
        for (StoredWorkflowDefinition def : defs) {
            if (def.getName().equals(name)) {
                return def;
            }
        }
        throw new RuntimeException("Workflow does not exist: " + name);
    }
}
