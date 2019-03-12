package org.tarantool;

import java.util.concurrent.Executor;

/**
 * Configuration for the {@link TarantoolClusterClient}.
 */
public class TarantoolClusterClientConfig extends TarantoolClientConfig {

    public static final int DEFAULT_OPERATION_EXPIRY_TIME_MILLIS = 500;
    public static final int DEFAULT_CLUSTER_DISCOVERY_DELAY_MILLIS = 60_000;

    /**
     * Period for the operation is eligible for retry.
     */
    public int operationExpiryTimeMillis = DEFAULT_OPERATION_EXPIRY_TIME_MILLIS;

    /**
     * Executor that will be used as a thread of
     * execution to retry writes.
     */
    public Executor executor;

    /**
     * Gets an instance address which contain {@link TarantoolClusterClientConfig#clusterDiscoveryEntryFunction}
     * returning a pool of extra instances.
     */
    public String clusterDiscoveryEntryInstance;

    /**
     * Gets a name of the stored function to be used
     * to fetch list of instances.
     */
    public String clusterDiscoveryEntryFunction;

    /**
     * Scan period for refreshing a new list of instances.
     */
    public int clusterDiscoveryDelayMillis = DEFAULT_CLUSTER_DISCOVERY_DELAY_MILLIS;

}
