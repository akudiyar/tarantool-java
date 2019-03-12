package org.tarantool;

import org.tarantool.cluster.TarantoolClusterDiscoverer;
import org.tarantool.cluster.TarantoolClusterStoredFunctionDiscoverer;
import org.tarantool.protocol.TarantoolPacket;
import org.tarantool.util.StringUtils;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;

/**
 * Basic implementation of a client that may work with the cluster
 * of tarantool instances in fault-tolerant way.
 * <p>
 * Failed operations will be retried once connection is re-established
 * unless the configured expiration time is over.
 */
public class TarantoolClusterClient extends TarantoolClientImpl {

    /**
     * Need some execution context to retry writes.
     */
    private Executor executor;

    /**
     * Discovery activity
     */
    private ScheduledExecutorService instancesDiscoveryExecutor;
    private Runnable instancesDiscovererTask;
    private StampedLock discoveryLock = new StampedLock();

    /**
     * Collection of operations to be retried.
     */
    private ConcurrentHashMap<Long, ExpirableOp<?>> retries = new ConcurrentHashMap<>();

    /**
     * @param config    Configuration.
     * @param addresses Array of addresses in the form of host[:port].
     */
    public TarantoolClusterClient(TarantoolClusterClientConfig config, String... addresses) {
        this(config, makeClusterSocketProvider(addresses, config.operationExpiryTimeMillis));
    }

    /**
     * @param provider Socket channel provider.
     * @param config   Configuration.
     */
    public TarantoolClusterClient(TarantoolClusterClientConfig config, SocketChannelProvider provider) {
        super(provider, config);

        this.executor = config.executor == null ?
                Executors.newSingleThreadExecutor() : config.executor;

        if (StringUtils.isNotBlank(config.clusterDiscoveryEntryInstance)
                && StringUtils.isNotBlank(config.clusterDiscoveryEntryFunction)) {
            this.instancesDiscovererTask =
                    createDiscoveryTask(new TarantoolClusterStoredFunctionDiscoverer(config));
            this.instancesDiscoveryExecutor
                    = Executors.newSingleThreadScheduledExecutor(new TarantoolThreadDaemonFactory("tarantoolDiscoverer"));
            int delay = config.clusterDiscoveryDelayMillis > 0
                ? config.clusterDiscoveryDelayMillis
                : TarantoolClusterClientConfig.DEFAULT_CLUSTER_DISCOVERY_DELAY_MILLIS;

            // todo: it's better to start a job later (out of ctor)
            this.instancesDiscoveryExecutor.scheduleWithFixedDelay(
                    this.instancesDiscovererTask,
                    0,
                    delay,
                    TimeUnit.MILLISECONDS
            );
        }

        // run connector thread after all required inits
        super.startConnector(config);
    }

    @Override
    protected void startConnector(TarantoolClientConfig config) {
        // we need to hold over ALIVE-state blocking until
        // the cluster client will start a discovery task
    }

    @Override
    protected boolean isDead(CompletableFuture<?> q) {
        if ((state.getState() & StateHelper.CLOSED) != 0) {
            q.completeExceptionally(new CommunicationException("Connection is dead", thumbstone));
            return true;
        }
        Exception err = thumbstone;
        if (err != null) {
            return checkFail(q, err);
        }
        return false;
    }

    @Override
    protected CompletableFuture<?> doExec(Code code, Object[] args) {
        validateArgs(args);
        long sid = syncId.incrementAndGet();
        ExpirableOp<?> future = makeFuture(sid, code, args);

        long stamp = discoveryLock.readLock();
        try {
            if (isDead(future)) {
                return future;
            }
            futures.put(sid, future);
            if (isDead(future)) {
                futures.remove(sid);
                return future;
            }

            try {
                write(code, sid, null, args);
            } catch (Exception e) {
                futures.remove(sid);
                fail(future, e);
            }

            return future;
        } finally {
            discoveryLock.unlock(stamp);
        }
    }

    @Override
    protected void fail(CompletableFuture<?> q, Exception e) {
        checkFail(q, e);
    }

    protected boolean checkFail(CompletableFuture<?> q, Exception e) {
        assert q instanceof ExpirableOp<?>;
        if (!isTransientError(e) || ((ExpirableOp<?>) q).hasExpired(System.currentTimeMillis())) {
            q.completeExceptionally(e);
            return true;
        } else {
            assert retries != null;
            retries.put(((ExpirableOp<?>) q).getId(), (ExpirableOp<?>) q);
            return false;
        }
    }

    @Override
    protected void close(Exception e) {
        super.close(e);

        if (instancesDiscoveryExecutor != null) {
            instancesDiscoveryExecutor.shutdownNow();
        }

        if (retries == null) {
            // May happen within constructor.
            return;
        }

        for (ExpirableOp<?> op : retries.values()) {
            op.completeExceptionally(e);
        }
    }

    protected boolean isTransientError(Exception e) {
        if (e instanceof CommunicationException) {
            return true;
        }
        if (e instanceof TarantoolException) {
            return ((TarantoolException) e).isTransient();
        }
        return false;
    }

    protected ExpirableOp<?> makeFuture(long id, Code code, Object... args) {
        int expireTime = ((TarantoolClusterClientConfig) config).operationExpiryTimeMillis;
        return new ExpirableOp(id, expireTime, code, args);
    }

    /**
     * Reconnect is over, schedule retries.
     */
    @Override
    protected void onReconnect() {
        if (retries == null || executor == null) {
            // First call is before the constructor finished. Skip it.
            return;
        }
        Collection<ExpirableOp<?>> futuresToRetry = new ArrayList<>(retries.values());
        retries.clear();
        long now = System.currentTimeMillis();
        for (final ExpirableOp<?> future : futuresToRetry) {
            if (!future.hasExpired(now)) {
                executor.execute(() -> resend(future));
            }
        }
    }

    private void resend(ExpirableOp<?> future) {
        futures.put(future.getId(), future);
        try {
            write(future.getCode(), future.getId(), null, future.getArgs());
        } catch (Exception e) {
            futures.remove(future.getId());
            fail(future, e);
        }
    }

    @Override
    protected void complete(TarantoolPacket packet, CompletableFuture<?> future) {
        super.complete(packet, future);
        RefreshableSocketProvider provider = getRefreshableSocketProvider();
        if (provider != null) {
            renewConnectionIfRequired(provider.getAddresses());
        }
    }

    protected void onInstancesRefreshed(Set<String> instances) {
        RefreshableSocketProvider provider = getRefreshableSocketProvider();
        if (provider != null) {
            provider.refreshAddresses(instances);
            renewConnectionIfRequired(provider.getAddresses());
        }
    }

    private RefreshableSocketProvider getRefreshableSocketProvider() {
        return socketProvider instanceof RefreshableSocketProvider
                ? (RefreshableSocketProvider) socketProvider
                : null;
    }

    private void renewConnectionIfRequired(Collection<SocketAddress> addresses) {
        if (pendingResponsesCount.get() > 0 || !isAlive()) {
            return;
        }
        SocketAddress addressInUse = getCurrentAddressOrNull();
        if (!(addressInUse == null || addresses.contains(addressInUse))) {
            long stamp = discoveryLock.tryWriteLock();
            if (!discoveryLock.validate(stamp)) {
                return;
            }
            try {
                if (pendingResponsesCount.get() == 0) {
                    stopIO();
                }
            } finally {
                discoveryLock.unlock(stamp);
            }
        }
    }

    private SocketAddress getCurrentAddressOrNull() {
        try {
            return channel.getRemoteAddress();
        } catch (IOException ignored) {
            return null;
        }
    }

    public void refreshInstances() {
        if (instancesDiscovererTask != null) {
            instancesDiscovererTask.run();
        }
    }

    private static RoundRobinSocketProviderImpl makeClusterSocketProvider(String[] addresses,
                                                                          int connectionTimeout) {
        RoundRobinSocketProviderImpl socketProvider = new RoundRobinSocketProviderImpl(addresses);
        socketProvider.setTimeout(connectionTimeout);
        return socketProvider;
    }

    private Runnable createDiscoveryTask(TarantoolClusterDiscoverer serviceDiscoverer) {
        return new Runnable() {

            private Set<String> lastInstances;

            @Override
            public synchronized void run() {
                try {
                    Set<String> freshInstances = serviceDiscoverer.getInstances();
                    if (!(freshInstances.isEmpty() || Objects.equals(lastInstances, freshInstances))) {
                        lastInstances = freshInstances;
                        onInstancesRefreshed(lastInstances);
                    }
                } catch (Exception ignored) {
                }
            }
        };
    }

    /**
     * Holds operation code and arguments for retry.
     */
    private class ExpirableOp<V> extends CompletableFuture<V> {

        /**
         * Moment in time when operation is not considered for retry.
         */
        final private long deadline;

        /**
         * A task identifier used in {@link TarantoolClientImpl#futures}.
         */
        final private long id;

        /**
         * Tarantool binary protocol operation code.
         */
        final private Code code;

        /**
         * Arguments of operation.
         */
        final private Object[] args;

        /**
         *
         * @param id Sync.
         * @param expireTime Expiration time (relative) in ms.
         * @param code Tarantool operation code.
         * @param args Operation arguments.
         */
        ExpirableOp(long id, int expireTime, Code code, Object...args) {
            this.id = id;
            this.deadline = System.currentTimeMillis() + expireTime;
            this.code = code;
            this.args = args;
        }

        boolean hasExpired(long now) {
            return now > deadline;
        }

        public long getId() {
            return id;
        }

        public Code getCode() {
            return code;
        }

        public Object[] getArgs() {
            return args;
        }
    }
}
