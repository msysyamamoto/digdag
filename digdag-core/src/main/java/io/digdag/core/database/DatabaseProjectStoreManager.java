package io.digdag.core.database;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.digdag.client.api.IdName;
import io.digdag.client.config.Config;
import io.digdag.core.repository.ArchiveType;
import io.digdag.core.repository.ImmutableStoredProject;
import io.digdag.core.repository.ImmutableStoredRevision;
import io.digdag.core.repository.ImmutableStoredWorkflowDefinition;
import io.digdag.core.repository.ImmutableStoredWorkflowDefinitionWithProject;
import io.digdag.core.repository.Project;
import io.digdag.core.repository.ProjectControlStore;
import io.digdag.core.repository.ProjectMap;
import io.digdag.core.repository.ProjectStore;
import io.digdag.core.repository.ProjectStoreManager;
import io.digdag.core.repository.ResourceConflictException;
import io.digdag.core.repository.ResourceNotFoundException;
import io.digdag.core.repository.Revision;
import io.digdag.core.repository.StoredProject;
import io.digdag.core.repository.StoredRevision;
import io.digdag.core.repository.StoredWorkflowDefinition;
import io.digdag.core.repository.StoredWorkflowDefinitionWithProject;
import io.digdag.core.repository.TimeZoneMap;
import io.digdag.core.repository.WorkflowDefinition;
import io.digdag.core.schedule.Schedule;
import org.immutables.value.Value;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.StatementContext;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.GetGeneratedKeys;
import org.skife.jdbi.v2.sqlobject.SqlQuery;
import org.skife.jdbi.v2.sqlobject.SqlUpdate;
import org.skife.jdbi.v2.tweak.ResultSetMapper;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;

public class DatabaseProjectStoreManager
        extends BasicDatabaseStoreManager<DatabaseProjectStoreManager.Dao>
        implements ProjectStoreManager
{
    private final ConfigMapper cfm;

    @Inject
    public DatabaseProjectStoreManager(DBI dbi, ConfigMapper cfm, DatabaseConfig config)
    {
        super(config.getType(), Dao.class, dbi);

        dbi.registerMapper(new StoredProjectMapper(cfm));
        dbi.registerMapper(new StoredRevisionMapper(cfm));
        dbi.registerMapper(new StoredWorkflowDefinitionMapper(cfm));
        dbi.registerMapper(new StoredWorkflowDefinitionWithProjectMapper(cfm));
        dbi.registerMapper(new WorkflowConfigMapper());
        dbi.registerMapper(new IdNameMapper());
        dbi.registerArgumentFactory(cfm.getArgumentFactory());

        this.cfm = cfm;
    }

    @Override
    public ProjectStore getProjectStore(int siteId)
    {
        return new DatabaseProjectStore(siteId);
    }

    @Override
    public StoredWorkflowDefinitionWithProject getWorkflowDetailsById(long wfId)
            throws ResourceNotFoundException
    {
        return requiredResource(
                (handle, dao) -> dao.getWorkflowDetailsByIdInternal(wfId),
                "workflow id=%s", wfId);
    }

    @Override
    public StoredProject getProjectByIdInternal(int projId)
        throws ResourceNotFoundException
    {
        return requiredResource(
                (handle, dao) -> dao.getProjectByIdInternal(projId),
                "project id=%s", projId);
    }

    @Override
    public StoredRevision getRevisionOfWorkflowDefinition(long wfId)
        throws ResourceNotFoundException
    {
        return requiredResource(
                (handle, dao) -> dao.getRevisionOfWorkflowDefinition(wfId),
                "revision of workflow definition id=%s", wfId);
    }

    private class DatabaseProjectStore
            implements ProjectStore
    {
        // TODO retry
        private final int siteId;

        public DatabaseProjectStore(int siteId)
        {
            this.siteId = siteId;
        }

        //public List<StoredProject> getAllProjects()
        //{
        //    return dao.getProjects(siteId, Integer.MAX_VALUE, 0);
        //}

        @Override
        public List<StoredProject> getProjects(int pageSize, Optional<Integer> lastId)
        {
            return autoCommit((handle, dao) -> dao.getProjects(siteId, pageSize, lastId.or(0)));
        }

        @Override
        public ProjectMap getProjectsByIdList(List<Integer> projIdList)
        {
            if (projIdList.isEmpty()) {
                return ProjectMap.empty();
            }

            List<StoredProject> projs = autoCommit((handle, dao) ->
                    handle.createQuery(
                        "select * from projects" +
                        " where site_id = :siteId" +
                        " and id " + inLargeIdListExpression(projIdList)
                    )
                    .bind("siteId", siteId)
                    .map(new StoredProjectMapper(cfm))
                    .list()
                );

            ImmutableMap.Builder<Integer, StoredProject> builder = ImmutableMap.builder();
            for (StoredProject proj : projs) {
                builder.put(proj.getId(), proj);
            }
            return new ProjectMap(builder.build());
        }

        @Override
        public StoredProject getProjectById(int projId)
                throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getProjectById(siteId, projId),
                    "project id=%d", projId);
        }

        @Override
        public StoredProject getProjectByName(String projName)
                throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getProjectByName(siteId, projName),
                    "project name=%s", projName);
        }

        @Override
        public <T> T putAndLockProject(Project project, ProjectLockAction<T> func)
                throws ResourceConflictException
        {
            return transaction((handle, dao, ts) -> {
                int projId;

                if (!ts.isRetried()) {
                    try {
                        projId = catchConflict(() ->
                                dao.insertProject(siteId, project.getName()),
                                "project name=%s", project.getName());
                    }
                    catch (ResourceConflictException ex) {
                        ts.retry(ex);
                        return null;
                    }
                }
                else {
                    StoredProject proj = dao.getProjectByName(siteId, project.getName());
                    if (proj == null) {
                        throw new IllegalStateException("Database state error", ts.getLastException());
                    }
                    projId = proj.getId();
                }

                StoredProject proj = dao.lockProject(projId);
                if (proj == null) {
                    throw new IllegalStateException("Database state error");
                }

                return func.call(new DatabaseProjectControlStore(handle, siteId), proj);
            }, ResourceConflictException.class);
        }

        @Override
        public <T> T deleteProject(int projId, ProjectObsoleteAction<T> func)
            throws ResourceNotFoundException
        {
            return transaction((handle, dao, ts) -> {
                StoredProject proj = requiredResource(
                        dao.getProjectByIdWithLockForDelete(siteId, projId),
                        "project id=%d", projId);

                T res = func.call(new DatabaseProjectControlStore(handle, siteId), proj);

                dao.deleteProject(proj.getId());

                return res;
            }, ResourceNotFoundException.class);
        }

        @Override
        public StoredRevision getRevisionById(int revId)
                throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getRevisionById(siteId, revId),
                    "revision id=%d", revId);
        }

        @Override
        public StoredRevision getRevisionByName(int projId, String revName)
                throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getRevisionByName(siteId, projId, revName),
                    "revision name=%s in project id=%d", revName, projId);
        }

        @Override
        public StoredRevision getLatestRevision(int projId)
                throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getLatestRevision(siteId, projId),
                    "project id=%d", projId);
        }

        @Override
        public List<StoredRevision> getRevisions(int projId, int pageSize, Optional<Integer> lastId)
        {
            return autoCommit((handle, dao) -> dao.getRevisions(siteId, projId, pageSize, lastId.or(Integer.MAX_VALUE)));
        }

        @Override
        public byte[] getRevisionArchiveData(int revId)
                throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.selectRevisionArchiveData(revId),
                    "revisin id=%d", revId);
        }

        @Override
        public StoredWorkflowDefinitionWithProject getLatestWorkflowDefinitionByName(int projId, String name)
            throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getLatestWorkflowDefinitionByName(siteId, projId, name),
                    "workflow name=%s in the latest revision of project id=%d", name, projId);
        }

        @Override
        public List<StoredWorkflowDefinition> getWorkflowDefinitions(int revId, int pageSize, Optional<Long> lastId)
        {
            return autoCommit((handle, dao) -> dao.getWorkflowDefinitions(siteId, revId, pageSize, lastId.or(0L)));
        }

        @Override
        public StoredWorkflowDefinitionWithProject getWorkflowDefinitionById(long wfId)
            throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getWorkflowDetailsById(siteId, wfId),
                    "workflow id=%d", wfId);
        }

        @Override
        public StoredWorkflowDefinition getWorkflowDefinitionByName(int revId, String name)
            throws ResourceNotFoundException
        {
            return requiredResource(
                    (handle, dao) -> dao.getWorkflowDefinitionByName(siteId, revId, name),
                    "workflow name=%s in revision id=%d", name, revId);
        }

        @Override
        public TimeZoneMap getWorkflowTimeZonesByIdList(List<Long> defIdList)
        {
            if (defIdList.isEmpty()) {
                return TimeZoneMap.empty();
            }

            List<IdTimeZone> list = autoCommit((handle, dao) ->
                    handle.createQuery(
                        "select wd.id, wc.timezone from workflow_definitions wd" +
                        " join revisions rev on rev.id = wd.revision_id" +
                        " join projects proj on proj.id = rev.project_id" +
                        " join workflow_configs wc on wc.id = wd.config_id" +
                        " where wd.id in (" + defIdList.stream()
                            .map(it -> Long.toString(it)).collect(Collectors.joining(", ")) + ")" +
                        " and site_id = :siteId"
                    )
                    .bind("siteId", siteId)
                    .map(new IdTimeZoneMapper())
                    .list()
            );

            Map<Long, ZoneId> map = IdTimeZone.listToMap(list);
            return new TimeZoneMap(map);
        }
    }

    private static class IdTimeZone
    {
        protected final long id;
        protected final ZoneId timeZone;

        public IdTimeZone(long id, ZoneId timeZone)
        {
            this.id = id;
            this.timeZone = timeZone;
        }

        public static Map<Long, ZoneId> listToMap(List<IdTimeZone> list)
        {
            ImmutableMap.Builder<Long, ZoneId> builder = ImmutableMap.builder();
            for (IdTimeZone pair : list) {
                builder.put(pair.id, pair.timeZone);
            }
            return builder.build();
        }
    }

    private static class IdTimeZoneMapper
            implements ResultSetMapper<IdTimeZone>
    {
        @Override
        public IdTimeZone map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return new IdTimeZone(r.getLong("id"), ZoneId.of(r.getString("timezone")));
        }
    }

    private class DatabaseProjectControlStore
            implements ProjectControlStore
    {
        private final Handle handle;
        private final int siteId;
        private final Dao dao;

        public DatabaseProjectControlStore(Handle handle, int siteId)
        {
            this.handle = handle;
            this.siteId = siteId;
            this.dao = handle.attach(Dao.class);
        }

        /**
         * Create or overwrite a revision.
         *
         * This method doesn't check site id because ProjectControl
         * interface is avaiable only if site is is valid.
         */
        @Override
        public StoredRevision insertRevision(int projId, Revision revision)
            throws ResourceConflictException
        {
            int revId = catchConflict(() ->
                dao.insertRevision(projId, revision.getName(), revision.getDefaultParams(), revision.getArchiveType().getName(), revision.getArchiveMd5().orNull(), revision.getArchivePath().orNull(), revision.getUserInfo()),
                "revision=%s in project id=%d", revision.getName(), projId);
            try {
                return requiredResource(
                        dao.getRevisionById(siteId, revId),
                        "revision id=%d", revId);
            }
            catch (ResourceNotFoundException ex) {
                throw new IllegalStateException("Database state error", ex);
            }
        }

        @Override
        public void insertRevisionArchiveData(int revId, byte[] data)
            throws ResourceConflictException
        {
            // TODO catch conflict and overwrite it
            catchConflict(() -> {
                    dao.insertRevisionArchiveData(revId, data);
                    return true;
                },
                "revision archive=%d", revId);
        }

        /**
         * Create a revision.
         *
         * This method doesn't check site id because ProjectControl
         * interface is available only if site is is valid.
         */
        @Override
        public StoredWorkflowDefinition insertWorkflowDefinition(int projId, int revId, WorkflowDefinition def, ZoneId workflowTimeZone)
            throws ResourceConflictException
        {
            String configText = cfm.toText(def.getConfig());
            String zoneId = workflowTimeZone.getId();
            long configDigest = WorkflowConfig.digest(configText, zoneId);

            int configId;

            WorkflowConfig found = dao.findWorkflowConfigByDigest(projId, configDigest);
            if (found != null && WorkflowConfig.isEquivalent(found, configText, zoneId)) {
                configId = found.getId();
            }
            else {
                configId = dao.insertWorkflowConfig(projId, configText, zoneId, configDigest);
            }

            long wfId = catchConflict(() ->
                dao.insertWorkflowDefinition(revId, def.getName(), configId),
                "workflow=%s in revision id=%d", def.getName(), revId);

            try {
                return requiredResource(
                        dao.getWorkflowDefinitionById(siteId, wfId),
                        "workflow id=%d", wfId);
            }
            catch (ResourceNotFoundException ex) {
                throw new IllegalStateException("Database state error", ex);
            }
        }

        @Override
        public void updateSchedules(int projId, List<Schedule> schedules)
            throws ResourceConflictException
        {
            Map<String, Integer> oldNames = idNameListToHashMap(dao.getScheduleNames(projId));

            for (Schedule schedule : schedules) {
                if (oldNames.containsKey(schedule.getWorkflowName())) {
                    // found the same name. overwriting workflow_definition_id
                    int n = handle.createStatement(
                            "update schedules" +
                            " set workflow_definition_id = :workflowDefinitionId, updated_at = now()" +
                            // TODO should here update next_run_time nad next_schedule_time?
                            " where id = :id"
                        )
                        .bind("workflowDefinitionId", schedule.getWorkflowDefinitionId())
                        .bind("id", oldNames.get(schedule.getWorkflowName()))
                        .execute();
                    if (n <= 0) {
                        // TODO exception?
                    }
                    oldNames.remove(schedule.getWorkflowName());
                }
                else {
                    // not found this name. inserting a new entry.
                    // TODO this INSERT can be optimized by using lazy multiple-value insert
                    catchConflict(() ->
                            handle.createStatement(
                                "insert into schedules" +
                                " (project_id, workflow_definition_id, next_run_time, next_schedule_time, last_session_time, created_at, updated_at)" +
                                " values (:projId, :workflowDefinitionId, :nextRunTime, :nextScheduleTime, NULL, now(), now())"
                            )
                            .bind("projId", projId)
                            .bind("workflowDefinitionId", schedule.getWorkflowDefinitionId())
                            .bind("nextRunTime", schedule.getNextRunTime().getEpochSecond())
                            .bind("nextScheduleTime", schedule.getNextScheduleTime().getEpochSecond())
                            .execute(),
                            "workflow_definition_id=%d", schedule.getWorkflowDefinitionId());
                }
            }
            if (!oldNames.isEmpty()) {
                // those names don exist any more.
                handle.createStatement(
                        "delete from schedules" +
                        " where id in (" +
                            oldNames.values().stream().map(it -> Integer.toString(it)).collect(Collectors.joining(", ")) + ")")
                    .execute();
            }
        }

        @Override
        public void deleteSchedules(int projId)
        {
            dao.deleteSchedules(projId);
        }
    }

    public interface Dao
    {
        @SqlQuery("select * from projects" +
                " where site_id = :siteId" +
                " and name is not null" +
                " and id > :lastId" +
                " order by id asc" +
                " limit :limit")
        List<StoredProject> getProjects(@Bind("siteId") int siteId, @Bind("limit") int limit, @Bind("lastId") int lastId);

        @SqlUpdate("update projects" +
                " set deleted_name = name, deleted_at = now(), name = NULL" +
                " where id = :projId "+
                " and name is not null")
        int deleteProject(@Bind("projId") int projId);

        @SqlQuery("select * from projects" +
                " where site_id = :siteId" +
                " and id = :id")
        StoredProject getProjectById(@Bind("siteId") int siteId, @Bind("id") int id);

        @SqlQuery("select * from projects" +
                " where site_id = :siteId" +
                " and id = :id" +
                " and name is not null" +
                " for update")
        StoredProject getProjectByIdWithLockForDelete(@Bind("siteId") int siteId, @Bind("id") int id);

        @SqlQuery("select * from projects" +
                " where id = :id")
        StoredProject getProjectByIdInternal(@Bind("id") int id);

        @SqlQuery("select rev.*" +
                " from workflow_definitions wd" +
                " join revisions rev on rev.id = wd.revision_id" +
                " where wd.id = :id")
        StoredRevision getRevisionOfWorkflowDefinition(@Bind("id") long wfId);

        @SqlQuery("select * from projects" +
                " where site_id = :siteId" +
                " and name = :name" +
                " limit 1")
        StoredProject getProjectByName(@Bind("siteId") int siteId, @Bind("name") String name);

        @SqlQuery("select * from projects where id = :id" +
                " for update")
        StoredProject lockProject(@Bind("id") int id);

        @SqlUpdate("insert into projects" +
                " (site_id, name, created_at)" +
                " values (:siteId, :name, now())")
        @GetGeneratedKeys
        int insertProject(@Bind("siteId") int siteId, @Bind("name") String name);

        @SqlQuery("select rev.* from revisions rev" +
                " join projects proj on proj.id = rev.project_id" +
                " where site_id = :siteId" +
                " and rev.id = :id")
        StoredRevision getRevisionById(@Bind("siteId") int siteId, @Bind("id") int id);

        @SqlQuery("select rev.* from revisions rev" +
                " join projects proj on proj.id = rev.project_id" +
                " where site_id = :siteId" +
                " and rev.project_id = :projId" +
                " and rev.name = :name" +
                " limit 1")
        StoredRevision getRevisionByName(@Bind("siteId") int siteId, @Bind("projId") int projId, @Bind("name") String name);

        @SqlQuery("select rev.* from revisions rev" +
                " join projects proj on proj.id = rev.project_id" +
                " where site_id = :siteId" +
                " and rev.project_id = :projId" +
                " order by rev.id desc" +
                " limit 1")
        StoredRevision getLatestRevision(@Bind("siteId") int siteId, @Bind("projId") int projId);

        @SqlQuery("select rev.* from revisions rev" +
                " join projects proj on proj.id = rev.project_id" +
                " where site_id = :siteId" +
                " and rev.project_id = :projId" +
                " and rev.id < :lastId" +
                " order by rev.id desc" +
                " limit :limit")
        List<StoredRevision> getRevisions(@Bind("siteId") int siteId, @Bind("projId") int projId, @Bind("limit") int limit, @Bind("lastId") int lastId);

        @SqlQuery("select archive_data from revision_archives" +
                " where id = :revId")
        byte[] selectRevisionArchiveData(@Bind("revId") int revId);

        @SqlQuery("select wd.*, wc.config, wc.timezone," +
                " proj.id as proj_id, proj.name as proj_name, proj.deleted_name as proj_deleted_name, proj.deleted_at as proj_deleted_at, proj.site_id, proj.created_at as proj_created_at," +
                " rev.name as rev_name, rev.default_params as rev_default_params" +
                " from workflow_definitions wd" +
                " join revisions rev on rev.id = wd.revision_id" +
                " join projects proj on proj.id = rev.project_id" +
                " join workflow_configs wc on wc.id = wd.config_id" +
                " where wd.revision_id = (" +
                    "select max(id) from revisions" +
                    " where project_id = :projId" +
                ")" +
                " and wd.name = :name" +
                " and proj.site_id = :siteId" +
                " limit 1")
        StoredWorkflowDefinitionWithProject getLatestWorkflowDefinitionByName(@Bind("siteId") int siteId, @Bind("projId") int projId, @Bind("name") String name);

        // getWorkflowDetailsById is same with getWorkflowDetailsByIdInternal
        // excepting site_id check

        @SqlQuery("select wd.*, wc.config, wc.timezone," +
                " proj.id as proj_id, proj.name as proj_name, proj.deleted_name as proj_deleted_name, proj.deleted_at as proj_deleted_at, proj.site_id, proj.created_at as proj_created_at," +
                " rev.name as rev_name, rev.default_params as rev_default_params" +
                " from workflow_definitions wd" +
                " join revisions rev on rev.id = wd.revision_id" +
                " join projects proj on proj.id = rev.project_id" +
                " join workflow_configs wc on wc.id = wd.config_id" +
                " where wd.id = :id")
        StoredWorkflowDefinitionWithProject getWorkflowDetailsByIdInternal(@Bind("id") long id);

        @SqlQuery("select wd.*, wc.config, wc.timezone," +
                " proj.id as proj_id, proj.name as proj_name, proj.deleted_name as proj_deleted_name, proj.deleted_at as proj_deleted_at, proj.site_id, proj.created_at as proj_created_at," +
                " rev.name as rev_name, rev.default_params as rev_default_params" +
                " from workflow_definitions wd" +
                " join revisions rev on rev.id = wd.revision_id" +
                " join projects proj on proj.id = rev.project_id" +
                " join workflow_configs wc on wc.id = wd.config_id" +
                " where wd.id = :id" +
                " and site_id = :siteId")
        StoredWorkflowDefinitionWithProject getWorkflowDetailsById(@Bind("siteId") int siteId, @Bind("id") long id);

        @SqlQuery("select wd.*, wc.config, wc.timezone from workflow_definitions wd" +
                " join revisions rev on rev.id = wd.revision_id" +
                " join projects proj on proj.id = rev.project_id" +
                " join workflow_configs wc on wc.id = wd.config_id" +
                " where wd.id = :id" +
                " and site_id = :siteId")
        StoredWorkflowDefinition getWorkflowDefinitionById(@Bind("siteId") int siteId, @Bind("id") long id);

        @SqlQuery("select wd.*, wc.config, wc.timezone from workflow_definitions wd" +
                " join revisions rev on rev.id = wd.revision_id" +
                " join projects proj on proj.id = rev.project_id" +
                " join workflow_configs wc on wc.id = wd.config_id" +
                " where revision_id = :revId" +
                " and wd.name = :name" +
                " and site_id = :siteId" +
                " limit 1")
        StoredWorkflowDefinition getWorkflowDefinitionByName(@Bind("siteId") int siteId, @Bind("revId") int revId, @Bind("name") String name);

        @SqlQuery("select id, config, timezone" +
                " from workflow_configs" +
                " where project_id = :projId and config_digest = :configDigest")
        WorkflowConfig findWorkflowConfigByDigest(@Bind("projId") int projId, @Bind("configDigest") long configDigest);

        @SqlUpdate("insert into workflow_configs" +
                " (project_id, config, timezone, config_digest)" +
                " values (:projId, :config, :timezone, :configDigest)")
        @GetGeneratedKeys
        int insertWorkflowConfig(@Bind("projId") int projId, @Bind("config") String config, @Bind("timezone") String timezone, @Bind("configDigest") long configDigest);

        @SqlUpdate("insert into revisions" +
                " (project_id, name, default_params, archive_type, archive_md5, archive_path, user_info, created_at)" +
                " values (:projId, :name, :defaultParams, :archiveType, :archiveMd5, :archivePath, :userInfo, now())")
        @GetGeneratedKeys
        int insertRevision(@Bind("projId") int projId, @Bind("name") String name, @Bind("defaultParams") Config defaultParams, @Bind("archiveType") String archiveType, @Bind("archiveMd5") byte[] archiveMd5, @Bind("archivePath") String archivePath, @Bind("userInfo") Config userInfo);

        @SqlQuery("select wd.*, wc.config, wc.timezone from workflow_definitions wd" +
                " join revisions rev on rev.id = wd.revision_id" +
                " join projects proj on proj.id = rev.project_id" +
                " join workflow_configs wc on wc.id = wd.config_id" +
                " where wd.revision_id = :revId" +
                " and wd.id > :lastId" +
                " and proj.site_id = :siteId" +
                " order by wd.id asc" +
                " limit :limit")
        List<StoredWorkflowDefinition> getWorkflowDefinitions(@Bind("siteId") int siteId, @Bind("revId") int revId, @Bind("limit") int limit, @Bind("lastId") long lastId);

        @SqlUpdate("insert into revision_archives" +
                " (id, archive_data)" +
                " values (:revId, :data)")
        void insertRevisionArchiveData(@Bind("revId") int revId, @Bind("data") byte[] data);

        @SqlUpdate("insert into workflow_definitions" +
                " (revision_id, name, config_id)" +
                " values (:revId, :name, :configId)")
        @GetGeneratedKeys
        long insertWorkflowDefinition(@Bind("revId") int revId, @Bind("name") String name, @Bind("configId") int configId);

        @SqlQuery("select wd.name, schedules.id from schedules" +
                " join workflow_definitions wd on wd.id = schedules.workflow_definition_id" +
                " where schedules.project_id = :projId")
        List<IdName> getScheduleNames(@Bind("projId") int projId);

        @SqlUpdate("delete from schedules" +
                " where project_id = :projId")
        int deleteSchedules(@Bind("projId") int projId);
    }

    @Value.Immutable
    public static abstract class WorkflowConfig
    {
        public abstract int getId();

        public abstract String getConfigText();

        public abstract String getTimeZone();

        private static final MessageDigest md5;

        static {
            try {
                md5 = MessageDigest.getInstance("MD5");
            }
            catch (NoSuchAlgorithmException ex) {
                throw new RuntimeException(ex);
            }
        }

        public static long digest(String configText, String zoneId)
        {
            try {
                String target = configText + " " + zoneId;
                byte[] digest = ((MessageDigest) md5.clone()).digest(target.getBytes(UTF_8));
                return ByteBuffer.wrap(digest).getLong(0);
            }
            catch (CloneNotSupportedException ex) {
                throw new RuntimeException(ex);
            }
        }

        public static boolean isEquivalent(WorkflowConfig c, String configText, String zoneId)
        {
            return configText.equals(c.getConfigText()) && zoneId.equals(c.getTimeZone());
        }
    }

    private static class StoredProjectMapper
            implements ResultSetMapper<StoredProject>
    {
        private final ConfigMapper cfm;

        public StoredProjectMapper(ConfigMapper cfm)
        {
            this.cfm = cfm;
        }

        @Override
        public StoredProject map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            String name = r.getString("name");
            Optional<Instant> deletedAt = Optional.absent();
            if (r.wasNull()) {
                name = r.getString("deleted_name");
                deletedAt = Optional.of(getTimestampInstant(r, "deleted_at"));
            }
            return ImmutableStoredProject.builder()
                .id(r.getInt("id"))
                .name(name)
                .siteId(r.getInt("site_id"))
                .createdAt(getTimestampInstant(r, "created_at"))
                .deletedAt(deletedAt)
                .build();
        }
    }

    private static class StoredRevisionMapper
            implements ResultSetMapper<StoredRevision>
    {
        private final ConfigMapper cfm;

        public StoredRevisionMapper(ConfigMapper cfm)
        {
            this.cfm = cfm;
        }

        @Override
        public StoredRevision map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableStoredRevision.builder()
                .id(r.getInt("id"))
                .projectId(r.getInt("project_id"))
                .createdAt(getTimestampInstant(r, "created_at"))
                .name(r.getString("name"))
                .defaultParams(cfm.fromResultSetOrEmpty(r, "default_params"))
                .archiveType(ArchiveType.of(r.getString("archive_type")))
                .archiveMd5(getOptionalBytes(r, "archive_md5"))
                .archivePath(getOptionalString(r, "archive_path"))
                .userInfo(cfm.fromResultSetOrEmpty(r, "user_info"))
                .build();
        }
    }

    private static class StoredWorkflowDefinitionMapper
            implements ResultSetMapper<StoredWorkflowDefinition>
    {
        private final ConfigMapper cfm;

        public StoredWorkflowDefinitionMapper(ConfigMapper cfm)
        {
            this.cfm = cfm;
        }

        @Override
        public StoredWorkflowDefinition map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableStoredWorkflowDefinition.builder()
                .id(r.getLong("id"))
                .revisionId(r.getInt("revision_id"))
                .timeZone(ZoneId.of(r.getString("timezone")))
                .name(r.getString("name"))
                .config(cfm.fromResultSetOrEmpty(r, "config"))
                .build();
        }
    }

    private static class StoredWorkflowDefinitionWithProjectMapper
            implements ResultSetMapper<StoredWorkflowDefinitionWithProject>
    {
        private final ConfigMapper cfm;

        public StoredWorkflowDefinitionWithProjectMapper(ConfigMapper cfm)
        {
            this.cfm = cfm;
        }

        @Override
        public StoredWorkflowDefinitionWithProject map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            String projName = r.getString("proj_name");
            Optional<Instant> projDeletedAt = Optional.absent();
            if (r.wasNull()) {
                projName = r.getString("proj_deleted_name");
                projDeletedAt = Optional.of(getTimestampInstant(r, "proj_deleted_at"));
            }
            return ImmutableStoredWorkflowDefinitionWithProject.builder()
                .id(r.getLong("id"))
                .revisionId(r.getInt("revision_id"))
                .timeZone(ZoneId.of(r.getString("timezone")))
                .name(r.getString("name"))
                .config(cfm.fromResultSetOrEmpty(r, "config"))
                .project(
                        ImmutableStoredProject.builder()
                            .id(r.getInt("proj_id"))
                            .name(projName)
                            .siteId(r.getInt("site_id"))
                            .createdAt(getTimestampInstant(r, "proj_created_at"))
                            .deletedAt(projDeletedAt)
                            .build())
                .revisionName(r.getString("rev_name"))
                .revisionDefaultParams(cfm.fromResultSetOrEmpty(r, "rev_default_params"))
                .build();
        }
    }

    private static class WorkflowConfigMapper
            implements ResultSetMapper<WorkflowConfig>
    {
        @Override
        public WorkflowConfig map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return ImmutableWorkflowConfig.builder()
                .id(r.getInt("id"))
                .configText(r.getString("config"))
                .timeZone(r.getString("timezone"))
                .build();
        }
    }

    private static class IdNameMapper
            implements ResultSetMapper<IdName>
    {
        @Override
        public IdName map(int index, ResultSet r, StatementContext ctx)
                throws SQLException
        {
            return IdName.of(r.getInt("id"), r.getString("name"));
        }
    }

    private HashMap<String, Integer> idNameListToHashMap(List<IdName> list)
    {
        HashMap<String, Integer> map = new HashMap<>();
        for (IdName idName : list) {
            map.put(idName.getName(), idName.getId());
        }
        return map;
    }
}
