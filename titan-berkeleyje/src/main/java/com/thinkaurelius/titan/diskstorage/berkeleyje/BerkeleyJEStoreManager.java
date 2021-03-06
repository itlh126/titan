package com.thinkaurelius.titan.diskstorage.berkeleyje;


import com.google.common.base.Preconditions;
import com.sleepycat.je.*;
import com.thinkaurelius.titan.diskstorage.PermanentStorageException;
import com.thinkaurelius.titan.diskstorage.StorageException;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.ConsistencyLevel;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.StoreFeatures;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.keyvalue.KeyValueStoreManager;
import com.thinkaurelius.titan.diskstorage.util.DirectoryUtil;
import com.thinkaurelius.titan.diskstorage.util.FileStorageConfiguration;
import com.thinkaurelius.titan.util.system.IOUtils;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.*;

public class BerkeleyJEStoreManager implements KeyValueStoreManager {

    private static final Logger log = LoggerFactory.getLogger(BerkeleyJEStoreManager.class);

    public static final String CACHE_KEY = "cache-percentage";
    public static final int CACHE_DEFAULT = 65;

    private final Map<String, BerkeleyJEKeyValueStore> stores;

    private Environment environment;
    private final File directory;
    private final boolean transactional;
    private final boolean isReadOnly;
    private final boolean batchLoading;
    private final StoreFeatures features;
    private final FileStorageConfiguration storageConfig;

    public BerkeleyJEStoreManager(Configuration configuration) throws StorageException {
        stores = new HashMap<String, BerkeleyJEKeyValueStore>();
        String storageDir = configuration.getString(STORAGE_DIRECTORY_KEY);
        Preconditions.checkArgument(storageDir != null, "Need to specify storage directory");
        directory = DirectoryUtil.getOrCreateDataDirectory(storageDir);
        isReadOnly = configuration.getBoolean(STORAGE_READONLY_KEY, STORAGE_READONLY_DEFAULT);
        batchLoading = configuration.getBoolean(STORAGE_BATCH_KEY, STORAGE_BATCH_DEFAULT);
        boolean transactional = configuration.getBoolean(STORAGE_TRANSACTIONAL_KEY, STORAGE_TRANSACTIONAL_DEFAULT);
        if (batchLoading) {
            if (transactional) log.warn("Disabling transactional since batch loading is enabled!");
            transactional = false;
        }
        this.transactional = transactional;
        if (!transactional)
            log.warn("Transactions are disabled. Ensure that there is at most one Titan instance interacting with this BerkeleyDB instance, otherwise your database may corrupt.");
        int cachePercentage = configuration.getInt(CACHE_KEY, CACHE_DEFAULT);

        initialize(cachePercentage);

        features = new StoreFeatures();
        features.supportsScan = true;
        features.supportsBatchMutation = false;
        features.supportsTransactions = true;
        features.supportsConsistentKeyOperations = false;
        features.supportsLocking = true;
        features.isKeyOrdered = true;
        features.isDistributed = false;
        features.hasLocalKeyPartition = false;

        storageConfig=new FileStorageConfiguration(directory);
    }

    private void initialize(int cachePercent) throws StorageException {
        try {
            EnvironmentConfig envConfig = new EnvironmentConfig();
            envConfig.setAllowCreate(true);
            envConfig.setTransactional(transactional);
            envConfig.setCachePercent(cachePercent);

            if (batchLoading) {
                envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CHECKPOINTER, "false");
                envConfig.setConfigParam(EnvironmentConfig.ENV_RUN_CLEANER, "false");
            }

            //Open the environment
            environment = new Environment(directory, envConfig);


        } catch (DatabaseException e) {
            throw new PermanentStorageException("Error during BerkeleyJE initialization: ", e);
        }

    }

    @Override
    public StoreFeatures getFeatures() {
        return features;
    }

    @Override
    public BerkeleyJETx beginTransaction(ConsistencyLevel level) throws StorageException {
        try {
            Transaction tx = null;
            if (transactional) {
                tx = environment.beginTransaction(null, null);
            }
            return new BerkeleyJETx(tx, level);
        } catch (DatabaseException e) {
            throw new PermanentStorageException("Could not start BerkeleyJE transaction", e);
        }
    }


    @Override
    public BerkeleyJEKeyValueStore openDatabase(String name) throws StorageException {
        Preconditions.checkNotNull(name);
        if (stores.containsKey(name)) {
            BerkeleyJEKeyValueStore store = stores.get(name);
            return store;
        }
        try {
            DatabaseConfig dbConfig = new DatabaseConfig();
            dbConfig.setReadOnly(isReadOnly);
            dbConfig.setAllowCreate(true);
            dbConfig.setTransactional(transactional);

            dbConfig.setKeyPrefixing(true);

            if (batchLoading) {
                dbConfig.setDeferredWrite(true);
            }

            Database db = environment.openDatabase(null, name, dbConfig);
            BerkeleyJEKeyValueStore store = new BerkeleyJEKeyValueStore(name, db, this);
            stores.put(name, store);
            return store;
        } catch (DatabaseException e) {
            throw new PermanentStorageException("Could not open BerkeleyJE data store", e);
        }
    }

    void removeDatabase(BerkeleyJEKeyValueStore db) {
        if (!stores.containsKey(db.getName())) {
            throw new IllegalArgumentException("Tried to remove an unkown database from the storage manager");
        }
        stores.remove(db.getName());
    }


    @Override
    public void close() throws StorageException {
        if (environment != null) {
            if (!stores.isEmpty())
                throw new IllegalStateException("Cannot shutdown manager since some databases are still open");
            try {
                environment.close();
            } catch (DatabaseException e) {
                throw new PermanentStorageException("Could not close BerkeleyJE database", e);
            }
        }

    }

    @Override
    public void clearStorage() throws StorageException {
        if (!stores.isEmpty())
            throw new IllegalStateException("Cannot delete store, since database is open: " + stores.keySet().toString());

        Transaction tx = null;
        for (String db : environment.getDatabaseNames()) {
            environment.removeDatabase(tx, db);
        }
        close();
        IOUtils.deleteFromDirectory(directory);
    }

    @Override
    public String getConfigurationProperty(String key) throws StorageException {
        return storageConfig.getConfigurationProperty(key);
    }

    @Override
    public void setConfigurationProperty(String key, String value) throws StorageException {
        storageConfig.setConfigurationProperty(key,value);
    }


}
