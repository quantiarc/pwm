/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2017 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.util.db;

import password.pwm.PwmAboutProperty;
import password.pwm.PwmApplication;
import password.pwm.PwmApplicationMode;
import password.pwm.PwmConstants;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.option.DataStorageMethod;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmException;
import password.pwm.health.HealthRecord;
import password.pwm.health.HealthStatus;
import password.pwm.health.HealthTopic;
import password.pwm.svc.PwmService;
import password.pwm.svc.stats.Statistic;
import password.pwm.svc.stats.StatisticsManager;
import password.pwm.util.java.ClosableIterator;
import password.pwm.util.java.JavaHelper;
import password.pwm.util.java.JsonUtil;
import password.pwm.util.java.TimeDuration;
import password.pwm.util.logging.PwmLogger;

import java.io.File;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Jason D. Rivard
 */
public class DatabaseAccessorImpl implements PwmService, DatabaseAccessor {
// ------------------------------ FIELDS ------------------------------

    private static final PwmLogger LOGGER = PwmLogger.forClass(DatabaseAccessorImpl.class, true);
    private static final String KEY_COLUMN = "id";
    private static final String VALUE_COLUMN = "value";

    private static final int KEY_COLUMN_LENGTH = PwmConstants.DATABASE_ACCESSOR_KEY_LENGTH;

    private static final String KEY_TEST = "write-test-key";
    private static final String KEY_ENGINE_START_PREFIX = "engine-start-";

    private DBConfiguration dbConfiguration;
    private Driver driver;
    private String instanceID;
    private boolean traceLogging;
    private volatile Connection connection;
    private volatile PwmService.STATUS status = PwmService.STATUS.NEW;
    private ErrorInformation lastError;
    private PwmApplication pwmApplication;

    private JDBCDriverLoader.DriverLoader jdbcDriverLoader;

    private ExecutorService masterStatusService;
    private final AtomicBoolean masterStatus = new AtomicBoolean(false);

// --------------------------- CONSTRUCTORS ---------------------------

    public DatabaseAccessorImpl()
    {
    }

// ------------------------ INTERFACE METHODS ------------------------


// --------------------- Interface PwmService ---------------------

    public STATUS status() {
        return status;
    }

    public void init(final PwmApplication pwmApplication) throws PwmException {
        this.pwmApplication = pwmApplication;
        final Configuration config = pwmApplication.getConfig();
        init(config);
    }

    public void close()
    {
        status = PwmService.STATUS.CLOSED;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                LOGGER.debug("error while closing DB: " + e.getMessage());
            }
        }

        try {
            driver = null;
        } catch (Exception e) {
            LOGGER.debug("error while de-registering driver: " + e.getMessage());
        }

        connection = null;

        if (jdbcDriverLoader != null) {
            jdbcDriverLoader.unloadDriver();
            jdbcDriverLoader = null;
        }
    }

    private void init(final Configuration config) throws PwmException {

        this.dbConfiguration = DBConfiguration.fromConfiguration(config);
        this.instanceID = pwmApplication == null ? null : pwmApplication.getInstanceID();
        this.traceLogging = config.readSettingAsBoolean(PwmSetting.DATABASE_DEBUG_TRACE);

        if (!dbConfiguration.isEnabled()) {
            status = PwmService.STATUS.CLOSED;
            LOGGER.debug("skipping database connection open, no connection parameters configured");
        }

        masterStatusService = JavaHelper.makeSingleThreadExecutorService(pwmApplication, DatabaseAccessorImpl.class);
    }

    public List<HealthRecord> healthCheck() {
        if (status == PwmService.STATUS.CLOSED) {
            return Collections.emptyList();
        }

        final List<HealthRecord> returnRecords = new ArrayList<>();

        try {
            preOperationCheck();
        } catch (DatabaseException e) {
            lastError = e.getErrorInformation();
            returnRecords.add(new HealthRecord(HealthStatus.WARN, HealthTopic.Database, "Database server is not available: " + e.getErrorInformation().toDebugStr()));
            return returnRecords;
        }

        try {
            final Map<String,String> tempMap = new HashMap<>();
            tempMap.put("instance",instanceID);
            tempMap.put("date",(new java.util.Date()).toString());
            this.put(DatabaseTable.PWM_META, DatabaseAccessorImpl.KEY_TEST, JsonUtil.serializeMap(tempMap));
        } catch (PwmException e) {
            returnRecords.add(new HealthRecord(HealthStatus.WARN, HealthTopic.Database, "Error writing to database: " + e.getErrorInformation().toDebugStr()));
            return returnRecords;
        }

        if (lastError != null) {
            final TimeDuration errorAge = TimeDuration.fromCurrent(lastError.getDate());

            if (errorAge.isShorterThan(TimeDuration.HOUR)) {
                final String msg = "Database server was recently unavailable ("
                        + errorAge.asLongString(PwmConstants.DEFAULT_LOCALE)
                        + " ago at " + lastError.getDate().toString()+ "): " + lastError.toDebugStr();
                returnRecords.add(new HealthRecord(HealthStatus.CAUTION, HealthTopic.Database, msg));
            }
        }

        if (returnRecords.isEmpty()) {
            returnRecords.add(new HealthRecord(HealthStatus.GOOD, HealthTopic.Database, "Database connection to " + this.dbConfiguration.getConnectionString() + " okay"));
        }

        return returnRecords;
    }

// -------------------------- OTHER METHODS --------------------------

    private synchronized void init()
            throws DatabaseException
    {
        status = PwmService.STATUS.OPENING;
        final Instant startTime = Instant.now();
        LOGGER.debug("opening connection to database " + this.dbConfiguration.getConnectionString());

        connection = openDB(dbConfiguration);
        for (final DatabaseTable table : DatabaseTable.values()) {
            initTable(connection, table, dbConfiguration);
        }

        status = PwmService.STATUS.OPEN;

        try {
            put(DatabaseTable.PWM_META, KEY_ENGINE_START_PREFIX + instanceID, JavaHelper.toIsoDate(new java.util.Date()));
        } catch (DatabaseException e) {
            final String errorMsg = "error writing engine start time value: " + e.getMessage();
            throw new DatabaseException(new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,errorMsg));
        }

        LOGGER.debug("successfully connected to remote database (" + TimeDuration.fromCurrent(startTime).asCompactString() + ")");
    }

    private Connection openDB(final DBConfiguration dbConfiguration) throws DatabaseException {
        final String connectionURL = dbConfiguration.getConnectionString();

        final JDBCDriverLoader.DriverWrapper wrapper = JDBCDriverLoader.loadDriver(pwmApplication, dbConfiguration);
        driver = wrapper.getDriver();
        jdbcDriverLoader = wrapper.getDriverLoader();

        try {
            LOGGER.debug("initiating connecting to database " + connectionURL);
            final Properties connectionProperties = new Properties();
            if (dbConfiguration.getUsername() != null && !dbConfiguration.getUsername().isEmpty()) {
                connectionProperties.setProperty("user", dbConfiguration.getUsername());
            }
            if (dbConfiguration.getPassword() != null) {
                connectionProperties.setProperty("password", dbConfiguration.getPassword().getStringValue());
            }
            final Connection connection = driver.connect(connectionURL, connectionProperties);


            final Map<PwmAboutProperty,String> debugProps = getConnectionDebugProperties(connection);
            LOGGER.debug("connected to database " + connectionURL + ", properties: " + JsonUtil.serializeMap(debugProps));

            connection.setAutoCommit(true);
            return connection;
        } catch (Throwable e) {
            final String errorMsg = "error connecting to database: " + JavaHelper.readHostileExceptionMessage(e);
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,errorMsg);
            LOGGER.error(errorInformation);
            throw new DatabaseException(errorInformation);
        }
    }

    private static void initTable(final Connection connection, final DatabaseTable table, final DBConfiguration dbConfiguration) throws DatabaseException {
        boolean tableExists = false;
        try {
            checkIfTableExists(connection, table);
            LOGGER.trace("table " + table + " appears to exist");
            tableExists = true;
        } catch (SQLException e) { // assume error was due to table missing;
            LOGGER.trace("error while checking for table: " + e.getMessage() + ", assuming due to table non-existence");
        }

        if (!tableExists) {
            createTable(connection, table, dbConfiguration);
        }
    }

    private static void createTable(final Connection connection, final DatabaseTable table, final DBConfiguration dbConfiguration) throws DatabaseException {
        {
            final StringBuilder sqlString = new StringBuilder();
            sqlString.append("CREATE table ").append(table.toString()).append(" (").append("\n");
            sqlString.append("  " + KEY_COLUMN + " ").append(dbConfiguration.getColumnTypeKey()).append("(").append(
                    KEY_COLUMN_LENGTH).append(") NOT NULL PRIMARY KEY,").append("\n");
            sqlString.append("  " + VALUE_COLUMN + " ").append(dbConfiguration.getColumnTypeValue()).append(" ");
            sqlString.append("\n");
            sqlString.append(")").append("\n");

            LOGGER.trace("attempting to execute the following sql statement:\n " + sqlString.toString());

            Statement statement = null;
            try {
                statement = connection.createStatement();
                statement.execute(sqlString.toString());
                LOGGER.debug("created table " + table.toString());
            } catch (SQLException ex) {
                final String errorMsg = "error creating new table " + table.toString() + ": " + ex.getMessage();
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE, errorMsg);
                throw new DatabaseException(errorInformation);
            } finally {
                close(statement);
            }
        }

        {
            final String indexName = table.toString() + "_IDX";
            final StringBuilder sqlString = new StringBuilder();
            sqlString.append("CREATE index ").append(indexName);
            sqlString.append(" ON ").append(table.toString());
            sqlString.append(" (").append(KEY_COLUMN).append(")");
            Statement statement = null;

            LOGGER.trace("attempting to execute the following sql statement:\n " + sqlString.toString());

            try {
                statement = connection.createStatement();
                statement.execute(sqlString.toString());
                LOGGER.debug("created index " + indexName);
            } catch (SQLException ex) {
                final String errorMsg = "error creating new index " + indexName + ": " + ex.getMessage();
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE, errorMsg);
                throw new DatabaseException(errorInformation);
            } finally {
                close(statement);
            }
        }
    }

    private static void checkIfTableExists(final Connection connection, final DatabaseTable table) throws SQLException {
        final StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM  ").append(table.toString()).append(" WHERE " + KEY_COLUMN + " = '0'");
        Statement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.createStatement();
            resultSet = statement.executeQuery(sb.toString());
        } finally {
            close(statement);
            close(resultSet);
        }
    }

    @Override
    public boolean put(
            final DatabaseTable table,
            final String key,
            final String value
    )
            throws DatabaseException {

        preOperationCheck();
        if (traceLogging) {
            LOGGER.trace("attempting put operation for table=" + table + ", key=" + key);
        }
        if (!contains(table, key)) {
            final String sqlText = "INSERT INTO " + table.toString() + "(" + KEY_COLUMN + ", " + VALUE_COLUMN + ") VALUES(?,?)";
            PreparedStatement statement = null;

            try {
                statement = connection.prepareStatement(sqlText);
                statement.setString(1, key);
                statement.setString(2, value);
                statement.executeUpdate();
            } catch (SQLException e) {
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"put operation failed: " + e.getMessage());
                lastError = errorInformation;
                throw new DatabaseException(errorInformation);
            } finally {
                close(statement);
            }
            return false;
        }

        final String sqlText = "UPDATE " + table.toString() + " SET " + VALUE_COLUMN + "=? WHERE " + KEY_COLUMN + "=?";
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement(sqlText);
            statement.setString(1, value);
            statement.setString(2, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"put operation failed: " + e.getMessage());
            lastError = errorInformation;
            throw new DatabaseException(errorInformation);
        } finally {
            close(statement);
        }

        if (traceLogging) {
            final Map<String,Object> debugOutput = new LinkedHashMap<>();
            debugOutput.put("table",table);
            debugOutput.put("key",key);
            debugOutput.put("value",value);
            LOGGER.trace("put operation result: " + JsonUtil.serializeMap(debugOutput, JsonUtil.Flag.PrettyPrint));
        }

        updateStats(false,true);
        return true;
    }

    private synchronized void preOperationCheck() throws DatabaseException {
        if (status == PwmService.STATUS.CLOSED) {
            throw new DatabaseException(new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"database connection is not open"));
        }

        if (status == PwmService.STATUS.NEW) {
            init();
        }

        if (!isValid(connection)) {
            init();
        }
    }

    private boolean isValid(final Connection connection) {
        if (connection == null) {
            return false;
        }

        if (status != PwmService.STATUS.OPEN) {
            return false;
        }

        try {
            final Method getFreeSpaceMethod = File.class.getMethod("isValid");
            final Object rawResult = getFreeSpaceMethod.invoke(connection,10);
            return (Boolean) rawResult;
        } catch (NoSuchMethodException e) {
            /* no error, pre java 1.6 doesn't have this method */
        } catch (Exception e) {
            LOGGER.debug("error checking for isValid for " + connection.toString() + ",: " + e.getMessage());
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(DatabaseTable.PWM_META.toString()).append(" WHERE " + KEY_COLUMN + " = ?");
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(sb.toString());
            statement.setString(1, KEY_ENGINE_START_PREFIX + instanceID);
            statement.setMaxRows(1);
            resultSet = statement.executeQuery();
            if (resultSet.next()) {
                resultSet.getString(VALUE_COLUMN);
            }
        } catch (SQLException e) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"isValid operation failed: " + e.getMessage());
            lastError = errorInformation;
            LOGGER.error(errorInformation.toDebugStr());
            return false;
        } finally {
            close(statement);
            close(resultSet);
        }
        return true;
    }

    private static void close(final Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                LOGGER.error("unexpected error during close statement object " + e.getMessage(), e);
            }
        }
    }

    private static void close(final ResultSet resultSet) {
        if (resultSet != null) {
            try {
                resultSet.close();
            } catch (SQLException e) {
                LOGGER.error("unexpected error during close resultSet object " + e.getMessage(), e);
            }
        }
    }

    @Override
    public boolean contains(
            final DatabaseTable table,
            final String key
    )
            throws DatabaseException
    {
        final boolean result = get(table, key) != null;
        if (traceLogging) {
            final Map<String,Object> debugOutput = new LinkedHashMap<>();
            debugOutput.put("table",table);
            debugOutput.put("key",key);
            debugOutput.put("result",result);
            LOGGER.trace("contains operation result: " + JsonUtil.serializeMap(debugOutput, JsonUtil.Flag.PrettyPrint));
        }
        updateStats(true,false);
        return result;
    }

    @Override
    public String get(
            final DatabaseTable table,
            final String key
    )
            throws DatabaseException
    {
        if (traceLogging) {
            LOGGER.trace("attempting get operation for table=" + table + ", key=" + key);
        }
        preOperationCheck();
        final StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(table.toString()).append(" WHERE " + KEY_COLUMN + " = ?");

        PreparedStatement statement = null;
        ResultSet resultSet = null;
        String returnValue = null;
        try {
            statement = connection.prepareStatement(sb.toString());
            statement.setString(1, key);
            statement.setMaxRows(1);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                returnValue = resultSet.getString(VALUE_COLUMN);
            }
        } catch (SQLException e) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"get operation failed: " + e.getMessage());
            lastError = errorInformation;
            throw new DatabaseException(errorInformation);
        } finally {
            close(statement);
            close(resultSet);
        }

        if (traceLogging) {
            final LinkedHashMap<String,Object> debugOutput = new LinkedHashMap<>();
            debugOutput.put("table",table);
            debugOutput.put("key",key);
            debugOutput.put("result",returnValue);
            LOGGER.trace("get operation result: " + JsonUtil.serializeMap(debugOutput, JsonUtil.Flag.PrettyPrint));
        }

        updateStats(true,false);
        return returnValue;
    }

    @Override
    public ClosableIterator<String> iterator(final DatabaseTable table)
            throws DatabaseException
    {
        preOperationCheck();
        return new DBIterator(table);
    }

    @Override
    public boolean remove(
            final DatabaseTable table,
            final String key
    )
            throws DatabaseException
    {
        if (traceLogging) {
            LOGGER.trace("attempting remove operation for table=" + table + ", key=" + key);
        }

        final boolean result = contains(table, key);
        if (result) {
            final StringBuilder sqlText = new StringBuilder();
            sqlText.append("DELETE FROM ").append(table.toString()).append(" WHERE " + KEY_COLUMN + "=?");

            PreparedStatement statement = null;
            try {
                statement = connection.prepareStatement(sqlText.toString());
                statement.setString(1, key);
                statement.executeUpdate();
                LOGGER.trace("remove operation succeeded for table=" + table + ", key=" + key);
            } catch (SQLException e) {
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"remove operation failed: " + e.getMessage());
                lastError = errorInformation;
                throw new DatabaseException(errorInformation);
            } finally {
                close(statement);
            }
        }

        if (traceLogging) {
            final Map<String,Object> debugOutput = new LinkedHashMap<>();
            debugOutput.put("table",table);
            debugOutput.put("key",key);
            debugOutput.put("result",result);
            LOGGER.trace("remove operation result: " + JsonUtil.serializeMap(debugOutput, JsonUtil.Flag.PrettyPrint));
        }

        updateStats(true, false);
        return result;
    }

    @Override
    public int size(final DatabaseTable table) throws
            DatabaseException {
        preOperationCheck();

        final StringBuilder sb = new StringBuilder();
        sb.append("SELECT COUNT(" + KEY_COLUMN + ") FROM ").append(table.toString());

        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            statement = connection.prepareStatement(sb.toString());
            resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt(1);
            }
        } catch (SQLException e) {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"size operation failed: " + e.getMessage());
            lastError = errorInformation;
            throw new DatabaseException(errorInformation);
        } finally {
            close(statement);
            close(resultSet);
        }

        updateStats(true,false);
        return 0;
    }

// -------------------------- ENUMERATIONS --------------------------

    // -------------------------- INNER CLASSES --------------------------

    public class DBIterator implements ClosableIterator<String> {
        private final DatabaseTable table;
        private final ResultSet resultSet;
        private java.lang.String nextValue;
        private boolean finished;

        public DBIterator(final DatabaseTable table)
                throws DatabaseException
        {
            this.table = table;
            this.resultSet = init();
            getNextItem();
        }

        private ResultSet init() throws DatabaseException {
            final StringBuilder sb = new StringBuilder();
            sb.append("SELECT " + KEY_COLUMN + " FROM ").append(table.toString());

            try {
                final PreparedStatement statement = connection.prepareStatement(sb.toString());
                return statement.executeQuery();
            } catch (SQLException e) {
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DB_UNAVAILABLE,"get iterator failed: " + e.getMessage());
                lastError = errorInformation;
                throw new DatabaseException(errorInformation);
            }
        }

        public boolean hasNext() {
            return !finished;
        }

        public java.lang.String next() {
            if (finished) {
                throw new IllegalStateException("iterator completed");
            }
            final String returnValue = nextValue;
            getNextItem();
            return returnValue;
        }

        public void remove() {
            throw new UnsupportedOperationException("remove not supported");
        }

        private void getNextItem() {
            try {
                if (resultSet.next()) {
                    nextValue = resultSet.getString(KEY_COLUMN);
                } else {
                    close();
                }
            } catch (SQLException e) {
                finished = true;
                LOGGER.warn("unexpected error during result set iteration: " + e.getMessage());
            }
            updateStats(true,false);
        }

        public void close() {
            if (resultSet != null) {
                try {
                    resultSet.close();
                } catch (SQLException e) {
                    LOGGER.error("error closing inner resultset in iterator: " + e.getMessage());
                }
            }
            finished = true;
        }
    }

    public ServiceInfo serviceInfo()
    {
        if (status() == STATUS.OPEN) {
            return new ServiceInfo(Collections.singletonList(DataStorageMethod.DB));
        } else {
            return new ServiceInfo(Collections.emptyList());
        }
    }

    private void updateStats(final boolean readOperation, final boolean writeOperation) {
        if (pwmApplication != null && pwmApplication.getApplicationMode() == PwmApplicationMode.RUNNING) {
            final StatisticsManager statisticsManager = pwmApplication.getStatisticsManager();
            if (statisticsManager != null && statisticsManager.status() == STATUS.OPEN) {
                if (readOperation) {
                    statisticsManager.updateEps(Statistic.EpsType.DB_READS,1);
                }
                if (writeOperation) {
                    statisticsManager.updateEps(Statistic.EpsType.DB_WRITES,1);
                }
            }
        }
    }


    @Override
    public Map<PwmAboutProperty,String> getConnectionDebugProperties() {
        return getConnectionDebugProperties(connection);
    }

    private static Map<PwmAboutProperty,String> getConnectionDebugProperties(final Connection connection) {
        if (connection != null) {
            try {
                final Map<PwmAboutProperty,String> returnObj = new LinkedHashMap<>();
                final DatabaseMetaData databaseMetaData = connection.getMetaData();
                returnObj.put(PwmAboutProperty.database_driverName, databaseMetaData.getDriverName());
                returnObj.put(PwmAboutProperty.database_driverVersion, databaseMetaData.getDriverVersion());
                returnObj.put(PwmAboutProperty.database_databaseProductName, databaseMetaData.getDatabaseProductName());
                returnObj.put(PwmAboutProperty.database_databaseProductVersion, databaseMetaData.getDatabaseProductVersion());
                return Collections.unmodifiableMap(returnObj);
            } catch (SQLException e) {
                LOGGER.error("error reading jdbc meta data: " + e.getMessage());
            }
        }
        return Collections.emptyMap();
    }

    @Override
    public boolean isMasterServer()
    {
        return false;
    }

    private class MasterCheckTask implements Runnable {
        @Override
        public void run()
        {

        }
    }
}
